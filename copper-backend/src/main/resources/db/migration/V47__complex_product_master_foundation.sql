-- Complex product master foundation.
-- Existing transactional rows remain valid. New versioned structures are additive and
-- published snapshots must never be overwritten by later master-data changes.

ALTER TABLE product_series
  ADD COLUMN identity_schema_version int unsigned NOT NULL DEFAULT 1
    COMMENT 'Current product identity schema version';

ALTER TABLE product
  MODIFY COLUMN spec varchar(1000) NOT NULL DEFAULT ''
    COMMENT 'Generated display specification assembled from structured fields',
  ADD COLUMN identity_schema_version_snapshot int unsigned NOT NULL DEFAULT 1
    COMMENT 'Identity schema frozen when the product was created or republished',
  ADD COLUMN identity_status varchar(24) NOT NULL DEFAULT 'PUBLISHED'
    COMMENT 'PENDING_REVIEW,PUBLISHED,DISABLED',
  ADD COLUMN identity_signature char(64) DEFAULT NULL
    COMMENT 'Versioned normalized product identity signature',
  ADD KEY idx_product_identity_review (identity_status,product_series_id,id);

ALTER TABLE product_field_value
  ADD COLUMN source_value_text varchar(1000) NOT NULL DEFAULT ''
    COMMENT 'User or import source value before reference normalization',
  ADD COLUMN reference_value_id bigint unsigned NOT NULL DEFAULT 0
    COMMENT 'Normalized product_reference_value when applicable',
  ADD COLUMN normalized_code varchar(128) NOT NULL DEFAULT ''
    COMMENT 'Stable normalized code used by identity rules',
  ADD KEY idx_product_field_reference (reference_value_id,product_id);

CREATE TABLE product_identity_schema_revision (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  product_series_id bigint unsigned NOT NULL,
  version_no int unsigned NOT NULL,
  change_reason varchar(500) NOT NULL,
  content_hash char(64) NOT NULL,
  published_by bigint unsigned NOT NULL DEFAULT 0,
  published_by_name varchar(128) NOT NULL DEFAULT '',
  published_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_identity_schema_version (product_series_id,version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Immutable product identity schema versions';

CREATE TABLE product_identity_rule (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  schema_revision_id bigint unsigned NOT NULL,
  product_series_id bigint unsigned NOT NULL,
  schema_version int unsigned NOT NULL,
  dimension_key varchar(128) NOT NULL,
  source_type varchar(24) NOT NULL COMMENT 'LEGACY_COLUMN,SERIES_FIELD,STRUCTURED',
  series_field_id bigint unsigned NOT NULL DEFAULT 0,
  reference_type varchar(24) NOT NULL DEFAULT '',
  required tinyint unsigned NOT NULL DEFAULT 0,
  sort_no int NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_identity_rule (schema_revision_id,dimension_key),
  KEY idx_product_identity_rule_series (product_series_id,schema_version,sort_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Versioned dimensions that determine whether products are interchangeable';

CREATE TABLE product_reference_value (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  reference_type varchar(24) NOT NULL COMMENT 'GRADE,TEMPER,STANDARD',
  code varchar(128) NOT NULL,
  name varchar(255) NOT NULL,
  english_name varchar(255) NOT NULL DEFAULT '',
  issuing_body varchar(255) NOT NULL DEFAULT '',
  edition varchar(64) NOT NULL DEFAULT '',
  effective_from date DEFAULT NULL,
  effective_to date DEFAULT NULL,
  alias_json json NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  version_no int unsigned NOT NULL DEFAULT 1,
  change_reason varchar(500) NOT NULL DEFAULT '',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_reference_code (reference_type,code),
  KEY idx_product_reference_name (reference_type,name,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Normalized grade temper and technical-standard reference values';

CREATE TABLE product_reference_alias (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  reference_type varchar(24) NOT NULL,
  alias_text varchar(255) NOT NULL,
  alias_key varchar(255) NOT NULL,
  reference_value_id bigint unsigned NOT NULL,
  source_document_id bigint unsigned NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_reference_alias (reference_type,alias_key,reference_value_id),
  KEY idx_product_reference_alias_lookup (reference_type,alias_key,status,id),
  KEY idx_product_reference_alias_target (reference_value_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Normalized aliases for product master reference values';

CREATE TABLE product_reference_binding (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  product_id bigint unsigned NOT NULL,
  reference_type varchar(24) NOT NULL,
  reference_value_id bigint unsigned NOT NULL,
  source_value_snapshot varchar(255) NOT NULL DEFAULT '',
  normalized_code_snapshot varchar(128) NOT NULL,
  identity_schema_version_snapshot int unsigned NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_reference_binding (product_id,reference_type),
  KEY idx_product_reference_binding_value (reference_value_id,product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Product-to-normalized-reference binding with historical source snapshot';

INSERT IGNORE INTO product_reference_value
  (reference_type,code,name,english_name,issuing_body,edition,effective_from,effective_to,
   alias_json,status,version_no,change_reason)
SELECT 'STANDARD',trim(code),coalesce(nullif(trim(name),''),trim(code)),coalesce(english_name,''),
       coalesce(publisher,''),'',implementation_date,abolition_date,
       json_array(trim(code),coalesce(nullif(trim(name),''),trim(code))),
       status,1,'迁移既有执行标准主数据'
FROM product_standard
WHERE trim(code)<>'';

INSERT IGNORE INTO product_reference_value
  (reference_type,code,name,english_name,issuing_body,edition,effective_from,effective_to,
   alias_json,status,version_no,change_reason)
SELECT 'TEMPER',coalesce(nullif(trim(code),''),trim(name)),trim(name),coalesce(english_name,''),
       '','',null,null,
       json_array(coalesce(nullif(trim(code),''),trim(name)),trim(name)),
       status,1,'迁移既有材料状态主数据'
FROM product_temper
WHERE trim(name)<>'';

INSERT IGNORE INTO product_reference_value
  (reference_type,code,name,english_name,issuing_body,edition,effective_from,effective_to,
   alias_json,status,version_no,change_reason)
VALUES
  ('GRADE','T1','T1紫铜','','','',null,null,json_array('T1','T1紫铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','T2','T2紫铜','','','',null,null,json_array('T2','T2紫铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','T3','T3紫铜','','','',null,null,json_array('T3','T3紫铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','TU0','TU0无氧铜','','','',null,null,json_array('TU0','TU0无氧铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','TU1','TU1无氧铜','','','',null,null,json_array('TU1','TU1无氧铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','TU2','TU2无氧铜','','','',null,null,json_array('TU2','TU2无氧铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','TP1','TP1磷脱氧铜','','','',null,null,json_array('TP1','TP1磷脱氧铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','TP2','TP2磷脱氧铜','','','',null,null,json_array('TP2','TP2磷脱氧铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','H62','H62黄铜','','','',null,null,json_array('H62','H62黄铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','H63','H63黄铜','','','',null,null,json_array('H63','H63黄铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','H65','H65黄铜','','','',null,null,json_array('H65','H65黄铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','H68','H68黄铜','','','',null,null,json_array('H68','H68黄铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','H70','H70黄铜','','','',null,null,json_array('H70','H70黄铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','H85','H85黄铜','','','',null,null,json_array('H85','H85黄铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','QSn4-3','QSn4-3锡青铜','','','',null,null,json_array('QSn4-3','QSN4-3','QSn4-3锡青铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','QSn6.5-0.1','QSn6.5-0.1锡青铜','','','',null,null,json_array('QSn6.5-0.1','QSN6.5-0.1','QSn6.5-0.1锡青铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','QSn7-0.2','QSn7-0.2锡青铜','','','',null,null,json_array('QSn7-0.2','QSN7-0.2','QSn7-0.2锡青铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','B5','B5白铜','','','',null,null,json_array('B5','B5白铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','B10','B10白铜','','','',null,null,json_array('B10','B10白铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','B19','B19白铜','','','',null,null,json_array('B19','B19白铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','B30','B30白铜','','','',null,null,json_array('B30','B30白铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','QCr0.5','QCr0.5铬青铜','','','',null,null,json_array('QCr0.5','QCR0.5','QCr0.5铬青铜','铬锆铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','C10200','C10200无氧铜','','','',null,null,json_array('C10200','CDA102','C10200无氧铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','C11000','C11000韧炼铜','','','',null,null,json_array('C11000','CDA110','C11000韧炼铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','C12200','C12200磷脱氧铜','','','',null,null,json_array('C12200','CDA122','C12200磷脱氧铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','C19400','C19400铜铁合金','','','',null,null,json_array('C19400','C194','C19400铜铁合金'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','C18150','C18150铬锆铜','','','',null,null,json_array('C18150','C1815','C18150铬锆铜'),'ENABLED',1,'初始化通用铜及铜合金牌号'),
  ('GRADE','N35','N35钕铁硼','','','',null,null,json_array('N35','N35钕铁硼'),'ENABLED',1,'初始化通用烧结钕铁硼牌号'),
  ('GRADE','N38','N38钕铁硼','','','',null,null,json_array('N38','N38钕铁硼'),'ENABLED',1,'初始化通用烧结钕铁硼牌号'),
  ('GRADE','N40','N40钕铁硼','','','',null,null,json_array('N40','N40钕铁硼'),'ENABLED',1,'初始化通用烧结钕铁硼牌号'),
  ('GRADE','N42','N42钕铁硼','','','',null,null,json_array('N42','N42钕铁硼'),'ENABLED',1,'初始化通用烧结钕铁硼牌号'),
  ('GRADE','N45','N45钕铁硼','','','',null,null,json_array('N45','N45钕铁硼'),'ENABLED',1,'初始化通用烧结钕铁硼牌号'),
  ('GRADE','N48','N48钕铁硼','','','',null,null,json_array('N48','N48钕铁硼'),'ENABLED',1,'初始化通用烧结钕铁硼牌号'),
  ('GRADE','N50','N50钕铁硼','','','',null,null,json_array('N50','N50钕铁硼'),'ENABLED',1,'初始化通用烧结钕铁硼牌号'),
  ('GRADE','N52','N52钕铁硼','','','',null,null,json_array('N52','N52钕铁硼'),'ENABLED',1,'初始化通用烧结钕铁硼牌号'),
  ('GRADE','N35M','N35M钕铁硼','','','',null,null,json_array('N35M','N35M钕铁硼'),'ENABLED',1,'初始化通用烧结钕铁硼牌号'),
  ('GRADE','N35H','N35H钕铁硼','','','',null,null,json_array('N35H','N35H钕铁硼'),'ENABLED',1,'初始化通用烧结钕铁硼牌号'),
  ('GRADE','N35SH','N35SH钕铁硼','','','',null,null,json_array('N35SH','N35SH钕铁硼'),'ENABLED',1,'初始化通用烧结钕铁硼牌号'),
  ('GRADE','N35UH','N35UH钕铁硼','','','',null,null,json_array('N35UH','N35UH钕铁硼'),'ENABLED',1,'初始化通用烧结钕铁硼牌号'),
  ('GRADE','N35EH','N35EH钕铁硼','','','',null,null,json_array('N35EH','N35EH钕铁硼'),'ENABLED',1,'初始化通用烧结钕铁硼牌号'),
  ('GRADE','N42SH','N42SH钕铁硼','','','',null,null,json_array('N42SH','N42SH钕铁硼'),'ENABLED',1,'初始化通用烧结钕铁硼牌号'),
  ('GRADE','N48H','N48H钕铁硼','','','',null,null,json_array('N48H','N48H钕铁硼'),'ENABLED',1,'初始化通用烧结钕铁硼牌号');

INSERT IGNORE INTO product_reference_value
  (reference_type,code,name,english_name,issuing_body,edition,effective_from,effective_to,
   alias_json,status,version_no,change_reason)
VALUES
  ('TEMPER','O','软态O','','','',null,null,json_array('O','O态','软态','软态O'),'ENABLED',1,'初始化通用材料状态'),
  ('TEMPER','O60','O60软态','','','',null,null,json_array('O60','O60态','O60软态'),'ENABLED',1,'初始化通用材料状态'),
  ('TEMPER','H01','H01状态','','','',null,null,json_array('H01','H01状态'),'ENABLED',1,'初始化通用材料状态'),
  ('TEMPER','H02','H02状态','','','',null,null,json_array('H02','H02状态'),'ENABLED',1,'初始化通用材料状态'),
  ('TEMPER','H04','H04状态','','','',null,null,json_array('H04','H04状态'),'ENABLED',1,'初始化通用材料状态'),
  ('TEMPER','H06','H06状态','','','',null,null,json_array('H06','H06状态'),'ENABLED',1,'初始化通用材料状态'),
  ('TEMPER','H08','H08状态','','','',null,null,json_array('H08','H08状态'),'ENABLED',1,'初始化通用材料状态'),
  ('TEMPER','1/4H','四分之一硬','','','',null,null,json_array('1/4H','1／4H','四分之一硬'),'ENABLED',1,'初始化通用材料状态'),
  ('TEMPER','1/2H','二分之一硬','','','',null,null,json_array('1/2H','1／2H','半硬','二分之一硬'),'ENABLED',1,'初始化通用材料状态');

CREATE TEMPORARY TABLE tmp_product_reference_alias_candidate (
  reference_type varchar(24) NOT NULL,
  alias_text varchar(255) NOT NULL,
  alias_key varchar(255) NOT NULL,
  reference_value_id bigint unsigned NOT NULL,
  status varchar(16) NOT NULL,
  KEY idx_tmp_reference_alias_candidate (reference_type,alias_key,reference_value_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_product_reference_alias_candidate
  (reference_type,alias_text,alias_key,reference_value_id,status)
SELECT reference_type,code,lower(regexp_replace(trim(code),'[[:space:]]+',' ')),id,status
FROM product_reference_value
WHERE trim(code)<>'';

INSERT INTO tmp_product_reference_alias_candidate
  (reference_type,alias_text,alias_key,reference_value_id,status)
SELECT reference_type,name,lower(regexp_replace(trim(name),'[[:space:]]+',' ')),id,status
FROM product_reference_value
WHERE trim(name)<>'';

INSERT INTO tmp_product_reference_alias_candidate
  (reference_type,alias_text,alias_key,reference_value_id,status)
SELECT v.reference_type,j.alias_text,
       lower(regexp_replace(trim(j.alias_text),'[[:space:]]+',' ')),v.id,v.status
FROM product_reference_value v
JOIN json_table(
  v.alias_json,
  '$[*]' columns(alias_text varchar(255) path '$')
) j ON true
WHERE trim(j.alias_text)<>'';

-- A generic name such as a standard title can legitimately describe multiple
-- editions or codes. Such text is not a safe import alias: only candidates
-- resolving to exactly one reference value are published.
CREATE TEMPORARY TABLE tmp_product_reference_alias_unique (
  reference_type varchar(24) NOT NULL,
  alias_key varchar(255) NOT NULL,
  reference_value_id bigint unsigned NOT NULL,
  PRIMARY KEY (reference_type,alias_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_product_reference_alias_unique
  (reference_type,alias_key,reference_value_id)
SELECT reference_type,alias_key,min(reference_value_id)
FROM tmp_product_reference_alias_candidate
GROUP BY reference_type,alias_key
HAVING count(distinct reference_value_id)=1;

INSERT INTO product_reference_alias
  (reference_type,alias_text,alias_key,reference_value_id,status)
SELECT c.reference_type,min(c.alias_text),c.alias_key,c.reference_value_id,min(c.status)
FROM tmp_product_reference_alias_candidate c
JOIN tmp_product_reference_alias_unique u
  ON u.reference_type=c.reference_type
   AND u.alias_key=c.alias_key
   AND u.reference_value_id=c.reference_value_id
GROUP BY c.reference_type,c.alias_key,c.reference_value_id;

DROP TEMPORARY TABLE tmp_product_reference_alias_unique;
DROP TEMPORARY TABLE tmp_product_reference_alias_candidate;

INSERT INTO product_identity_schema_revision
  (product_series_id,version_no,change_reason,content_hash)
SELECT id,1,'初始化既有商品识别规则',
       sha2(concat('IDENTITY_SCHEMA_V1|',id),256)
FROM product_series;

INSERT INTO product_identity_rule
  (schema_revision_id,product_series_id,schema_version,dimension_key,source_type,
   series_field_id,reference_type,required,sort_no,status)
SELECT r.id,r.product_series_id,1,'GRADE','LEGACY_COLUMN',0,'GRADE',0,10,'ENABLED'
FROM product_identity_schema_revision r
WHERE r.version_no=1;

INSERT INTO product_identity_rule
  (schema_revision_id,product_series_id,schema_version,dimension_key,source_type,
   series_field_id,reference_type,required,sort_no,status)
SELECT r.id,r.product_series_id,1,'TEMPER','LEGACY_COLUMN',0,'TEMPER',0,20,'ENABLED'
FROM product_identity_schema_revision r
WHERE r.version_no=1;

INSERT INTO product_identity_rule
  (schema_revision_id,product_series_id,schema_version,dimension_key,source_type,
   series_field_id,reference_type,required,sort_no,status)
SELECT r.id,r.product_series_id,1,'STANDARD','LEGACY_COLUMN',0,'STANDARD',0,30,'ENABLED'
FROM product_identity_schema_revision r
WHERE r.version_no=1;

INSERT INTO product_identity_rule
  (schema_revision_id,product_series_id,schema_version,dimension_key,source_type,
   series_field_id,reference_type,required,sort_no,status)
SELECT r.id,sf.product_series_id,1,concat('SF:',sf.id),'SERIES_FIELD',
       sf.id,
       case
         when sf.field_name in ('牌号','材质牌号') then 'GRADE'
         when sf.field_name in ('材料状态','状态') then 'TEMPER'
         when sf.field_name in ('执行标准','标准') then 'STANDARD'
         else ''
       end,
       sf.required,100+sf.sort_no,sf.status
FROM product_series_field sf
JOIN product_identity_schema_revision r
  ON r.product_series_id=sf.product_series_id AND r.version_no=1
WHERE sf.sku_dimension=1;

UPDATE product_field_value pfv
JOIN product_custom_field cf ON cf.id=pfv.field_id
SET pfv.source_value_text=case
      when cf.field_type='NUMBER' and pfv.value_decimal is not null
        then cast(cast(pfv.value_decimal as decimal(38,10)) as char)
      else trim(pfv.value_text)
    end
WHERE pfv.source_value_text='';

CREATE TABLE product_identity_migration_audit (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  product_id bigint unsigned NOT NULL,
  product_series_id bigint unsigned NOT NULL,
  sku varchar(64) NOT NULL,
  audit_type varchar(48) NOT NULL COMMENT 'INCOMPLETE_OR_UNRESOLVED',
  affected_dimensions varchar(2000) NOT NULL DEFAULT '',
  resolution_action varchar(48) NOT NULL DEFAULT 'RESET_TO_PENDING_REVIEW',
  detected_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_identity_migration_audit (product_id,audit_type),
  KEY idx_product_identity_migration_series (product_series_id,audit_type,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Audit of legacy identities that cannot be safely auto-published';

CREATE TEMPORARY TABLE tmp_product_identity_raw (
  product_id bigint unsigned NOT NULL,
  product_series_id bigint unsigned NOT NULL,
  sku varchar(64) NOT NULL,
  rule_id bigint unsigned NOT NULL,
  dimension_key varchar(128) NOT NULL,
  source_type varchar(24) NOT NULL,
  series_field_id bigint unsigned NOT NULL,
  reference_type varchar(24) NOT NULL,
  required_flag tinyint unsigned NOT NULL,
  sort_no int NOT NULL,
  raw_text varchar(1000) NOT NULL,
  raw_decimal decimal(38,10) DEFAULT NULL,
  field_type varchar(32) NOT NULL DEFAULT '',
  PRIMARY KEY (product_id,rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_product_identity_raw(
  product_id,product_series_id,sku,rule_id,dimension_key,source_type,
  series_field_id,reference_type,required_flag,sort_no,raw_text,raw_decimal,field_type
)
SELECT p.id,p.product_series_id,p.sku,r.id,r.dimension_key,r.source_type,
       r.series_field_id,r.reference_type,r.required,r.sort_no,
       case
         when r.source_type='LEGACY_COLUMN' and r.dimension_key='GRADE' then trim(p.grade)
         when r.source_type='LEGACY_COLUMN' and r.dimension_key='TEMPER' then trim(p.temper)
         when r.source_type='LEGACY_COLUMN' and r.dimension_key='STANDARD' then trim(p.standard)
         when r.source_type='SERIES_FIELD'
           then coalesce(nullif(trim(pfv.source_value_text),''),trim(pfv.value_text),'')
         else ''
       end,
       case when r.source_type='SERIES_FIELD' then pfv.value_decimal else null end,
       coalesce(cf.field_type,'')
FROM product p
JOIN product_identity_rule r
  ON r.product_series_id=p.product_series_id
 AND r.schema_version=1
 AND r.status='ENABLED'
LEFT JOIN product_series_field sf ON sf.id=r.series_field_id
LEFT JOIN product_custom_field cf ON cf.id=sf.field_id
LEFT JOIN product_field_value pfv
  ON pfv.product_id=p.id AND pfv.series_field_id=r.series_field_id;

CREATE TEMPORARY TABLE tmp_product_identity_value (
  product_id bigint unsigned NOT NULL,
  product_series_id bigint unsigned NOT NULL,
  sku varchar(64) NOT NULL,
  rule_id bigint unsigned NOT NULL,
  dimension_key varchar(128) NOT NULL,
  source_type varchar(24) NOT NULL,
  series_field_id bigint unsigned NOT NULL,
  reference_type varchar(24) NOT NULL,
  field_type varchar(32) NOT NULL DEFAULT '',
  required_flag tinyint unsigned NOT NULL,
  sort_no int NOT NULL,
  raw_text varchar(1000) NOT NULL,
  reference_value_id bigint unsigned NOT NULL DEFAULT 0,
  canonical_value varchar(1000) NOT NULL,
  resolution_ok tinyint unsigned NOT NULL,
  PRIMARY KEY (product_id,rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_product_identity_value(
  product_id,product_series_id,sku,rule_id,dimension_key,source_type,
  series_field_id,reference_type,required_flag,sort_no,raw_text,
  field_type,reference_value_id,canonical_value,resolution_ok
)
SELECT x.product_id,x.product_series_id,x.sku,x.rule_id,x.dimension_key,x.source_type,
       x.series_field_id,x.reference_type,x.required_flag,x.sort_no,x.raw_text,x.field_type,
       coalesce(v.id,0),
       case
         when x.reference_type<>'' and trim(x.raw_text)<>'' and v.id is not null
           then lower(trim(v.code))
         when x.reference_type<>'' then ''
         when x.field_type='NUMBER' and x.raw_decimal is not null
           then cast(cast(x.raw_decimal as decimal(38,10)) as char)
         else trim(x.raw_text)
       end,
       case
         when x.reference_type='' or trim(x.raw_text)='' or v.id is not null then 1
         else 0
       end
FROM tmp_product_identity_raw x
LEFT JOIN (
  SELECT reference_type,alias_key,min(reference_value_id) reference_value_id
  FROM product_reference_alias
  WHERE status='ENABLED'
  GROUP BY reference_type,alias_key
  HAVING count(distinct reference_value_id)=1
) a ON a.reference_type=x.reference_type
   AND a.alias_key=lower(regexp_replace(trim(x.raw_text),'[[:space:]]+',' '))
LEFT JOIN product_reference_value v
  ON v.id=a.reference_value_id AND v.status='ENABLED';

UPDATE product_field_value pfv
JOIN tmp_product_identity_value x
  ON x.product_id=pfv.product_id
 AND x.series_field_id=pfv.series_field_id
 AND x.source_type='SERIES_FIELD'
SET pfv.reference_value_id=x.reference_value_id,
    pfv.normalized_code=case
      when x.reference_value_id>0 then x.canonical_value
      when x.field_type='NUMBER' then x.canonical_value
      else pfv.normalized_code
    end;

INSERT INTO product_reference_binding(
  product_id,reference_type,reference_value_id,source_value_snapshot,
  normalized_code_snapshot,identity_schema_version_snapshot,status
)
SELECT x.product_id,x.reference_type,x.reference_value_id,x.raw_text,
       x.canonical_value,1,'ENABLED'
FROM tmp_product_identity_value x
WHERE x.source_type='LEGACY_COLUMN' AND x.reference_value_id>0
ON DUPLICATE KEY UPDATE
  reference_value_id=values(reference_value_id),
  source_value_snapshot=values(source_value_snapshot),
  normalized_code_snapshot=values(normalized_code_snapshot),
  identity_schema_version_snapshot=values(identity_schema_version_snapshot),
  status='ENABLED';

CREATE TEMPORARY TABLE tmp_product_identity_ordered (
  product_id bigint unsigned NOT NULL,
  product_series_id bigint unsigned NOT NULL,
  sku varchar(64) NOT NULL,
  rule_id bigint unsigned NOT NULL,
  dimension_key varchar(128) NOT NULL,
  canonical_value varchar(1000) NOT NULL,
  required_flag tinyint unsigned NOT NULL,
  resolution_ok tinyint unsigned NOT NULL,
  part_no int unsigned NOT NULL,
  part_count int unsigned NOT NULL,
  PRIMARY KEY (product_id,part_no),
  UNIQUE KEY uk_tmp_product_identity_rule (product_id,rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_product_identity_ordered(
  product_id,product_series_id,sku,rule_id,dimension_key,canonical_value,
  required_flag,resolution_ok,part_no,part_count
)
SELECT product_id,product_series_id,sku,rule_id,dimension_key,canonical_value,
       required_flag,resolution_ok,
       row_number() over (
         partition by product_id order by sort_no,rule_id
       ),
       count(*) over (partition by product_id)
FROM tmp_product_identity_value;

-- Hash UTF-8 byte-length-prefixed key/value pairs in deterministic rule order.
-- The encoding is unambiguous even when values contain display separators and it
-- never depends on group_concat_max_len.
CREATE TEMPORARY TABLE tmp_product_identity_head (
  product_id bigint unsigned NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_product_identity_head(product_id)
SELECT DISTINCT product_id
FROM tmp_product_identity_ordered;

CREATE TEMPORARY TABLE tmp_product_identity_chain (
  product_id bigint unsigned NOT NULL,
  part_no int unsigned NOT NULL,
  identity_signature char(64) NOT NULL,
  PRIMARY KEY (product_id,part_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_product_identity_chain(
  product_id,part_no,identity_signature
)
WITH RECURSIVE identity_chain(product_id,part_no,identity_signature) AS (
  SELECT product_id,0,
         sha2(concat('I2',octet_length('1'),':','1'),256)
  FROM tmp_product_identity_head
  UNION ALL
  SELECT c.product_id,p.part_no,
         sha2(concat(
           c.identity_signature,
           octet_length(p.dimension_key),':',p.dimension_key,
           octet_length(p.canonical_value),':',p.canonical_value
         ),256)
  FROM identity_chain c
  JOIN tmp_product_identity_ordered p
    ON p.product_id=c.product_id AND p.part_no=c.part_no+1
)
SELECT product_id,part_no,identity_signature
FROM identity_chain;

CREATE TEMPORARY TABLE tmp_product_identity_eval (
  product_id bigint unsigned NOT NULL PRIMARY KEY,
  product_series_id bigint unsigned NOT NULL,
  sku varchar(64) NOT NULL,
  identity_signature char(64) NOT NULL,
  invalid_count int unsigned NOT NULL,
  invalid_dimensions varchar(2000) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_product_identity_eval(
  product_id,product_series_id,sku,identity_signature,invalid_count,invalid_dimensions
)
SELECT p.product_id,min(p.product_series_id),min(p.sku),min(c.identity_signature),
       sum(case
             when p.resolution_ok=0
               or (p.required_flag=1 and trim(p.canonical_value)='') then 1
             else 0
           end),
       coalesce(group_concat(
         case
           when p.resolution_ok=0
             or (p.required_flag=1 and trim(p.canonical_value)='')
             then p.dimension_key
           else null
         end order by p.part_no separator ','
       ),'')
FROM tmp_product_identity_ordered p
JOIN tmp_product_identity_chain c
  ON c.product_id=p.product_id AND c.part_no=p.part_count
GROUP BY p.product_id;

INSERT INTO product_identity_migration_audit(
  product_id,product_series_id,sku,audit_type,affected_dimensions
)
SELECT product_id,product_series_id,sku,'INCOMPLETE_OR_UNRESOLVED',invalid_dimensions
FROM tmp_product_identity_eval
WHERE invalid_count>0;

UPDATE product p
JOIN tmp_product_identity_eval e ON e.product_id=p.id
SET p.identity_schema_version_snapshot=1,
    p.identity_status=case when e.invalid_count=0 then 'PUBLISHED' else 'PENDING_REVIEW' end,
    p.identity_signature=case
      when e.invalid_count=0 then e.identity_signature
      else null
    end;

DROP TEMPORARY TABLE tmp_product_identity_eval;
DROP TEMPORARY TABLE tmp_product_identity_chain;
DROP TEMPORARY TABLE tmp_product_identity_head;
DROP TEMPORARY TABLE tmp_product_identity_ordered;
DROP TEMPORARY TABLE tmp_product_identity_value;
DROP TEMPORARY TABLE tmp_product_identity_raw;

CREATE TABLE product_identity_conflict_audit (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  product_series_id bigint unsigned NOT NULL,
  identity_signature char(64) NOT NULL,
  kept_product_id bigint unsigned NOT NULL,
  conflicting_product_id bigint unsigned NOT NULL,
  conflicting_sku varchar(64) NOT NULL,
  identity_schema_version_snapshot int unsigned NOT NULL,
  resolution_action varchar(48) NOT NULL DEFAULT 'RESET_TO_PENDING_REVIEW',
  detected_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_identity_conflict_product
    (conflicting_product_id,identity_signature),
  KEY idx_product_identity_conflict_group
    (product_series_id,identity_signature,kept_product_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Migration audit for duplicate normalized product identities';

INSERT INTO product_identity_conflict_audit(
  product_series_id,identity_signature,kept_product_id,conflicting_product_id,
  conflicting_sku,identity_schema_version_snapshot
)
SELECT p.product_series_id,p.identity_signature,g.kept_product_id,p.id,p.sku,
       p.identity_schema_version_snapshot
FROM product p
JOIN (
  SELECT product_series_id,identity_signature,min(id) kept_product_id
  FROM product
  WHERE identity_signature IS NOT NULL
  GROUP BY product_series_id,identity_signature
  HAVING count(*) > 1
) g
  ON g.product_series_id=p.product_series_id
 AND g.identity_signature=p.identity_signature
WHERE p.id<>g.kept_product_id;

UPDATE product p
JOIN product_identity_conflict_audit a
  ON a.conflicting_product_id=p.id
 AND a.resolution_action='RESET_TO_PENDING_REVIEW'
SET p.identity_status='PENDING_REVIEW',
    p.identity_signature=NULL;

ALTER TABLE product
  ADD UNIQUE KEY uk_product_series_identity_signature
    (product_series_id,identity_signature);

CREATE TABLE product_source_document (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  document_code varchar(64) NOT NULL,
  title varchar(255) NOT NULL,
  source_type varchar(32) NOT NULL
    COMMENT 'UPLOAD,URL,MANUAL,SUPPLIER,SUPPLIER_CATALOG,TECHNICAL_STANDARD,QUALITY_RULE,OTHER',
  current_revision_id bigint unsigned NOT NULL DEFAULT 0,
  current_revision_no int unsigned NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  lock_version int unsigned NOT NULL DEFAULT 0,
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_source_document_code (document_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Source-document head for product and supplier master data';

CREATE TABLE product_source_document_revision (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  document_id bigint unsigned NOT NULL,
  revision_no int unsigned NOT NULL,
  title_snapshot varchar(255) NOT NULL,
  source_type_snapshot varchar(32) NOT NULL,
  source_url varchar(1000) NOT NULL DEFAULT '',
  file_name varchar(255) NOT NULL DEFAULT '',
  file_sha256 char(64) NOT NULL DEFAULT '',
  mime_type varchar(128) NOT NULL DEFAULT '',
  file_size bigint unsigned NOT NULL DEFAULT 0,
  publication_date date DEFAULT NULL,
  internal_version varchar(128) NOT NULL DEFAULT '',
  downloaded_at timestamp(6) NULL DEFAULT NULL,
  content_snapshot_json json NOT NULL,
  change_reason varchar(500) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PUBLISHED',
  published_by bigint unsigned NOT NULL DEFAULT 0,
  published_by_name varchar(128) NOT NULL DEFAULT '',
  published_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_source_document_revision (document_id,revision_no),
  KEY idx_product_source_document_hash (file_sha256,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Immutable source-document revisions';

CREATE TABLE product_source_link (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  document_revision_id bigint unsigned NOT NULL,
  target_type varchar(48) NOT NULL,
  target_id bigint unsigned NOT NULL,
  page_no int unsigned NOT NULL DEFAULT 0,
  source_locator varchar(500) NOT NULL DEFAULT '',
  raw_value text,
  normalized_value text,
  confidence decimal(7,6) DEFAULT NULL,
  review_status varchar(24) NOT NULL DEFAULT 'PENDING',
  reviewed_by bigint unsigned NOT NULL DEFAULT 0,
  reviewed_by_name varchar(128) NOT NULL DEFAULT '',
  reviewed_at timestamp(6) NULL DEFAULT NULL,
  review_comment varchar(500) NOT NULL DEFAULT '',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_product_source_link_target (target_type,target_id,id),
  KEY idx_product_source_link_document (document_revision_id,page_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Auditable source evidence attached to normalized master data';

CREATE TABLE product_source_link_review (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  source_link_id bigint unsigned NOT NULL,
  previous_status varchar(24) NOT NULL,
  review_status varchar(24) NOT NULL COMMENT 'APPROVED,REJECTED',
  review_comment varchar(500) NOT NULL DEFAULT '',
  reviewed_by bigint unsigned NOT NULL DEFAULT 0,
  reviewed_by_name varchar(128) NOT NULL DEFAULT '',
  reviewed_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_product_source_link_review (source_link_id,reviewed_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Immutable approval and rejection audit for source evidence';

CREATE TABLE product_series_allowed_unit (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  product_series_id bigint unsigned NOT NULL,
  purpose varchar(24) NOT NULL COMMENT 'PURCHASE,SALES,INVENTORY,PACKAGING,PRICE',
  unit_id bigint unsigned NOT NULL,
  unit_name_snapshot varchar(64) NOT NULL,
  is_default tinyint unsigned NOT NULL DEFAULT 0,
  sort_no int NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_series_allowed_unit (product_series_id,purpose,unit_id),
  KEY idx_product_series_unit_option (product_series_id,purpose,status,is_default,sort_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Purpose-specific unit choices for a product series';

CREATE TABLE packaging_variant (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  product_id bigint unsigned NOT NULL,
  variant_code varchar(64) NOT NULL,
  name varchar(128) NOT NULL,
  current_revision_id bigint unsigned NOT NULL DEFAULT 0,
  current_revision_no int unsigned NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  lock_version int unsigned NOT NULL DEFAULT 0,
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_packaging_variant_code (product_id,variant_code),
  KEY idx_packaging_variant_product (product_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Named physical packaging variants for one product';

CREATE TABLE packaging_variant_revision (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  packaging_variant_id bigint unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  revision_no int unsigned NOT NULL,
  package_unit varchar(64) NOT NULL,
  conversion_mode varchar(24) NOT NULL COMMENT 'FIXED,ACTUAL_WEIGHT,THEORETICAL',
  fixed_base_quantity decimal(24,6) DEFAULT NULL,
  reference_base_quantity decimal(24,6) DEFAULT NULL,
  inner_spec varchar(500) NOT NULL DEFAULT '',
  net_weight decimal(24,6) DEFAULT NULL,
  gross_weight decimal(24,6) DEFAULT NULL,
  tare_weight decimal(24,6) DEFAULT NULL,
  length_mm decimal(18,3) DEFAULT NULL,
  width_mm decimal(18,3) DEFAULT NULL,
  height_mm decimal(18,3) DEFAULT NULL,
  barcode varchar(128) NOT NULL DEFAULT '',
  supplier_id bigint unsigned NOT NULL DEFAULT 0,
  source_document_id bigint unsigned NOT NULL DEFAULT 0,
  source_document_revision_id bigint unsigned NOT NULL DEFAULT 0,
  content_hash char(64) NOT NULL,
  change_reason varchar(500) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PUBLISHED',
  published_by bigint unsigned NOT NULL DEFAULT 0,
  published_by_name varchar(128) NOT NULL DEFAULT '',
  published_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_packaging_variant_revision (packaging_variant_id,revision_no),
  KEY idx_packaging_variant_revision_product (product_id,published_at,id),
  KEY idx_packaging_variant_revision_barcode (barcode,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Immutable product packaging variant revisions';

CREATE TABLE supplier_product_offer (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  supplier_id bigint unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  supplier_product_code varchar(128) NOT NULL DEFAULT '',
  supplier_model varchar(128) NOT NULL DEFAULT '',
  current_revision_id bigint unsigned NOT NULL DEFAULT 0,
  current_revision_no int unsigned NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  lock_version int unsigned NOT NULL DEFAULT 0,
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_supplier_product_offer_code (supplier_id,product_id,supplier_product_code),
  KEY idx_supplier_product_offer_product (product_id,status,supplier_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Supplier-specific product and ordering relationship';

CREATE TABLE supplier_product_offer_revision (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  supplier_product_offer_id bigint unsigned NOT NULL,
  revision_no int unsigned NOT NULL,
  supplier_id bigint unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  supplier_product_code varchar(128) NOT NULL DEFAULT '',
  supplier_model varchar(128) NOT NULL DEFAULT '',
  purchase_unit varchar(64) NOT NULL,
  price_unit varchar(64) NOT NULL,
  quoted_unit_price decimal(24,8) DEFAULT NULL
    COMMENT 'Supplier quoted price per price_unit; null means no quote',
  currency_code char(3) NOT NULL DEFAULT 'CNY',
  price_includes_tax tinyint unsigned NOT NULL DEFAULT 1,
  tax_rate decimal(9,6) NOT NULL DEFAULT 0.000000,
  minimum_order_quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  order_multiple decimal(24,6) NOT NULL DEFAULT 0.000000,
  lead_time_days int unsigned NOT NULL DEFAULT 0,
  effective_from date NOT NULL,
  effective_to date DEFAULT NULL,
  packaging_variant_revision_id bigint unsigned NOT NULL DEFAULT 0,
  source_document_id bigint unsigned NOT NULL DEFAULT 0,
  source_document_revision_id bigint unsigned NOT NULL DEFAULT 0,
  commercial_terms_json json NOT NULL,
  content_hash char(64) NOT NULL,
  change_reason varchar(500) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PUBLISHED',
  published_by bigint unsigned NOT NULL DEFAULT 0,
  published_by_name varchar(128) NOT NULL DEFAULT '',
  published_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_supplier_product_offer_revision (supplier_product_offer_id,revision_no),
  KEY idx_supplier_product_offer_effective (supplier_id,product_id,effective_from,effective_to,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Immutable supplier product ordering-term revisions';

CREATE TABLE supplier_product_packaging_binding (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  supplier_product_offer_id bigint unsigned NOT NULL,
  packaging_variant_id bigint unsigned NOT NULL,
  is_default tinyint unsigned NOT NULL DEFAULT 0,
  effective_from date NOT NULL,
  effective_to date DEFAULT NULL,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_supplier_product_packaging (supplier_product_offer_id,packaging_variant_id,effective_from),
  KEY idx_supplier_product_packaging_effective (supplier_product_offer_id,status,effective_from,effective_to,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Effective supplier-offer to packaging-variant binding';

CREATE TABLE product_port (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  product_id bigint unsigned NOT NULL,
  port_no int unsigned NOT NULL,
  port_role varchar(32) NOT NULL DEFAULT '',
  connection_type varchar(32) NOT NULL,
  gender varchar(16) NOT NULL DEFAULT '',
  nominal_size varchar(64) NOT NULL,
  nominal_size_mm decimal(18,6) DEFAULT NULL,
  pressure_class varchar(64) NOT NULL DEFAULT '',
  angle_degree decimal(9,3) DEFAULT NULL,
  standard_code varchar(128) NOT NULL DEFAULT '',
  material_code varchar(128) NOT NULL DEFAULT '',
  sort_no int NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_port_no (product_id,port_no),
  KEY idx_product_port_product (product_id,status,sort_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Ordered ports for fittings and valves';

CREATE TABLE conductor_construction (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  product_id bigint unsigned NOT NULL,
  construction_type varchar(32) NOT NULL,
  nominal_cross_section_mm2 decimal(18,6) DEFAULT NULL,
  strand_count int unsigned NOT NULL DEFAULT 0,
  single_wire_diameter_mm decimal(18,6) DEFAULT NULL,
  conductor_width_mm decimal(18,6) DEFAULT NULL,
  conductor_thickness_mm decimal(18,6) DEFAULT NULL,
  lay_direction varchar(16) NOT NULL DEFAULT '',
  lay_pitch_mm decimal(18,6) DEFAULT NULL,
  thermal_class varchar(32) NOT NULL DEFAULT '',
  insulation_system_code varchar(128) NOT NULL DEFAULT '',
  construction_signature char(64) NOT NULL DEFAULT '',
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_conductor_construction_product (product_id),
  KEY idx_conductor_construction_signature (construction_signature,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Structured strand and insulated-conductor construction';

CREATE TABLE conductor_construction_component (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  conductor_construction_id bigint unsigned NOT NULL,
  layer_no int unsigned NOT NULL,
  component_type varchar(32) NOT NULL,
  material_code varchar(128) NOT NULL DEFAULT '',
  strand_count int unsigned NOT NULL DEFAULT 0,
  diameter_mm decimal(18,6) DEFAULT NULL,
  thickness_mm decimal(18,6) DEFAULT NULL,
  lay_direction varchar(16) NOT NULL DEFAULT '',
  lay_pitch_mm decimal(18,6) DEFAULT NULL,
  sort_no int NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_conductor_component_layer (conductor_construction_id,layer_no,component_type,sort_no),
  KEY idx_conductor_component_construction (conductor_construction_id,layer_no,sort_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Repeatable conductor strands coatings and insulation layers';

CREATE TABLE product_technical_spec_revision (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  product_id bigint unsigned NOT NULL,
  revision_no int unsigned NOT NULL,
  standard_code varchar(128) NOT NULL DEFAULT '',
  effective_from date NOT NULL,
  effective_to date DEFAULT NULL,
  source_document_id bigint unsigned NOT NULL DEFAULT 0,
  source_document_revision_id bigint unsigned NOT NULL DEFAULT 0,
  content_hash char(64) NOT NULL,
  change_reason varchar(500) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PUBLISHED',
  published_by bigint unsigned NOT NULL DEFAULT 0,
  published_by_name varchar(128) NOT NULL DEFAULT '',
  published_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_technical_spec_revision (product_id,revision_no),
  KEY idx_product_technical_spec_effective (product_id,status,effective_from,effective_to,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Immutable product technical specification revisions';

CREATE TABLE product_technical_characteristic (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  technical_spec_revision_id bigint unsigned NOT NULL,
  characteristic_code varchar(128) NOT NULL,
  name varchar(255) NOT NULL,
  value_type varchar(24) NOT NULL COMMENT 'TEXT,NUMBER,RANGE,BOOLEAN',
  target_text varchar(1000) NOT NULL DEFAULT '',
  minimum_value decimal(30,10) DEFAULT NULL,
  target_value decimal(30,10) DEFAULT NULL,
  maximum_value decimal(30,10) DEFAULT NULL,
  unit_name varchar(64) NOT NULL DEFAULT '',
  test_method varchar(255) NOT NULL DEFAULT '',
  test_condition varchar(500) NOT NULL DEFAULT '',
  is_batch_required tinyint unsigned NOT NULL DEFAULT 0,
  sort_no int NOT NULL DEFAULT 0,
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_technical_characteristic (technical_spec_revision_id,characteristic_code),
  KEY idx_product_technical_characteristic_spec (technical_spec_revision_id,sort_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Typed technical targets and test conditions';

CREATE TABLE inventory_batch_quality_certificate (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  inventory_batch_id bigint unsigned NOT NULL,
  certificate_no varchar(128) NOT NULL,
  certificate_type varchar(32) NOT NULL DEFAULT 'COA',
  supplier_id bigint unsigned NOT NULL DEFAULT 0,
  manufacturer_name varchar(255) NOT NULL DEFAULT '',
  manufacturer_batch_no varchar(128) NOT NULL DEFAULT '',
  heat_no varchar(128) NOT NULL DEFAULT '',
  coil_no varchar(128) NOT NULL DEFAULT '',
  issue_date date DEFAULT NULL,
  source_document_id bigint unsigned NOT NULL DEFAULT 0,
  source_document_revision_id bigint unsigned NOT NULL DEFAULT 0,
  file_archive_id bigint unsigned NOT NULL DEFAULT 0,
  verification_status varchar(24) NOT NULL DEFAULT 'PENDING',
  verified_by bigint unsigned NOT NULL DEFAULT 0,
  verified_by_name varchar(128) NOT NULL DEFAULT '',
  verified_at timestamp(6) NULL DEFAULT NULL,
  review_comment varchar(500) NOT NULL DEFAULT '',
  lock_version int unsigned NOT NULL DEFAULT 1,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_batch_quality_certificate (inventory_batch_id,certificate_no),
  KEY idx_batch_quality_certificate_batch (inventory_batch_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Batch quality certificate and certificate-of-analysis head';

CREATE TABLE inventory_batch_quality_certificate_review (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  certificate_id bigint unsigned NOT NULL,
  previous_status varchar(24) NOT NULL,
  review_status varchar(24) NOT NULL COMMENT 'VERIFIED,REJECTED,VOID',
  review_comment varchar(500) NOT NULL,
  reviewed_by bigint unsigned NOT NULL DEFAULT 0,
  reviewed_by_name varchar(128) NOT NULL DEFAULT '',
  reviewed_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_quality_certificate_review (certificate_id,reviewed_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Immutable batch quality review and void audit';

CREATE TABLE inventory_batch_quality_result (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  certificate_id bigint unsigned NOT NULL,
  technical_characteristic_id bigint unsigned NOT NULL DEFAULT 0,
  characteristic_code varchar(128) NOT NULL,
  measured_text varchar(1000) NOT NULL DEFAULT '',
  measured_value decimal(30,10) DEFAULT NULL,
  unit_name varchar(64) NOT NULL DEFAULT '',
  conclusion varchar(24) NOT NULL DEFAULT 'NOT_EVALUATED',
  test_method_snapshot varchar(255) NOT NULL DEFAULT '',
  tested_at timestamp(6) NULL DEFAULT NULL,
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_batch_quality_result (certificate_id,characteristic_code),
  KEY idx_batch_quality_result_characteristic (technical_characteristic_id,certificate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Measured batch quality values linked to technical targets';

CREATE TABLE product_bom (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  parent_product_id bigint unsigned NOT NULL,
  bom_code varchar(64) NOT NULL,
  name varchar(128) NOT NULL,
  current_revision_id bigint unsigned NOT NULL DEFAULT 0,
  current_revision_no int unsigned NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  lock_version int unsigned NOT NULL DEFAULT 0,
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_bom_parent (parent_product_id),
  UNIQUE KEY uk_product_bom_code (bom_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Current lightweight product assembly BOM head';

CREATE TABLE product_bom_revision (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  bom_id bigint unsigned NOT NULL,
  parent_product_id bigint unsigned NOT NULL,
  revision_no int unsigned NOT NULL,
  output_quantity decimal(24,6) NOT NULL DEFAULT 1.000000,
  output_unit varchar(64) NOT NULL,
  output_base_quantity decimal(24,6) NOT NULL,
  output_base_unit varchar(64) NOT NULL,
  output_effective_revision_id bigint unsigned NOT NULL DEFAULT 0,
  output_conversion_snapshot_json json NOT NULL,
  scrap_rate decimal(9,6) NOT NULL DEFAULT 0.000000,
  effective_from date NOT NULL,
  effective_to date DEFAULT NULL,
  content_hash char(64) NOT NULL,
  change_reason varchar(500) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PUBLISHED',
  published_by bigint unsigned NOT NULL DEFAULT 0,
  published_by_name varchar(128) NOT NULL DEFAULT '',
  published_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_bom_revision (bom_id,revision_no),
  KEY idx_product_bom_effective (parent_product_id,status,effective_from,effective_to,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Immutable effective BOM revisions';

CREATE TABLE product_bom_item (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  bom_revision_id bigint unsigned NOT NULL,
  component_product_id bigint unsigned NOT NULL,
  quantity decimal(24,6) NOT NULL,
  unit varchar(64) NOT NULL,
  base_quantity decimal(24,6) NOT NULL,
  base_unit varchar(64) NOT NULL,
  packaging_effective_revision_id bigint unsigned NOT NULL DEFAULT 0,
  conversion_snapshot_json json NOT NULL,
  scrap_rate decimal(9,6) NOT NULL DEFAULT 0.000000,
  issue_method varchar(24) NOT NULL DEFAULT 'MANUAL',
  sort_no int NOT NULL DEFAULT 0,
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_bom_item (bom_revision_id,component_product_id,sort_no),
  KEY idx_product_bom_item_component (component_product_id,bom_revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Component requirements frozen inside one BOM revision';

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('product.master.complex.view','product-master','商品主数据','PAGE','查看复杂商品主数据',
   '查看来源、规范引用、供应商供货、包装方案、多端口、导体结构、技术质量及BOM',80,'ENABLED'),
  ('product.master.complex.manage','product-master','商品主数据','ACTION','维护复杂商品主数据',
   '按版本维护复杂商品主数据；已发布历史只新增版本不覆盖',81,'ENABLED'),
  ('inventory.quality.manage','inventory','库存管理','ACTION','维护批次质量',
   '维护、复核、驳回和作废批次质保书及实测结果',82,'ENABLED'),
  ('inventory.quality.view','inventory','库存管理','PAGE','查看批次质量',
   '查看授权产品和仓库范围内的批次质保书及实测结果',83,'ENABLED'),
  ('product.supplier.offer.manage','product-master','商品主数据','ACTION','维护供应商供货档案',
   '维护供应商货号、报价、起订量、包装和交期版本',84,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id
FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER','MANAGER')
  AND p.code IN ('product.master.complex.view','product.master.complex.manage',
                 'inventory.quality.view','inventory.quality.manage',
                 'product.supplier.offer.manage');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id
FROM security_role r JOIN security_permission p
WHERE r.code IN ('WAREHOUSE','PURCHASE')
  AND p.code IN ('inventory.quality.view','inventory.quality.manage');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id
FROM security_role r JOIN security_permission p
WHERE r.code='PURCHASE'
  AND p.code IN ('product.cost.view','product.supplier.offer.manage');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id
FROM security_role r JOIN security_permission p
WHERE r.code IN ('PURCHASE','SALES','WAREHOUSE','FINANCE')
  AND p.code='product.master.complex.view';
