-- Complex product catalog foundation.
-- This migration is deliberately vendor-neutral: it configures reusable product families,
-- controlled units and six-decimal quantity facts without importing any supplier catalogue.

-- Fail closed before the first permanent DDL/DML statement. MySQL DDL commits
-- implicitly, so every compatibility check that can be evaluated from the V48
-- state must run against temporary definitions first.
CREATE TEMPORARY TABLE tmp_v49_unit_category_definition (
  name varchar(64) NOT NULL PRIMARY KEY,
  must_exist tinyint unsigned NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_v49_unit_category_definition(name,must_exist) VALUES
  ('长度',1),('包装',1),('重量',1),
  ('数量',0),('面积',0),('压力',0),('温度',0),('角度',0);

CREATE TEMPORARY TABLE tmp_v49_unit_definition (
  category_name varchar(64) NOT NULL,
  name varchar(64) NOT NULL,
  abbreviation varchar(32) NOT NULL,
  unit_kind varchar(24) NOT NULL,
  PRIMARY KEY (abbreviation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_v49_unit_definition(category_name,name,abbreviation,unit_kind) VALUES
  ('长度','千米','km','MEASURE'),
  ('长度','微米','μm','MEASURE'),
  ('数量','张','张','COUNT'),
  ('数量','片','片','COUNT'),
  ('数量','块','块','COUNT'),
  ('数量','只','只','COUNT'),
  ('数量','套','套','COUNT'),
  ('包装','轴','轴','PACKAGE'),
  ('包装','桶','桶','PACKAGE'),
  ('包装','盒','盒','PACKAGE'),
  ('面积','平方毫米','mm²','MEASURE'),
  ('面积','平方米','m²','MEASURE'),
  ('压力','兆帕','MPa','MEASURE'),
  ('温度','摄氏度','℃','MEASURE'),
  ('角度','度','°','MEASURE');

CREATE TEMPORARY TABLE tmp_v49_unit_alias_definition (
  abbreviation varchar(32) NOT NULL,
  alias_name varchar(64) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_v49_unit_alias_definition(abbreviation,alias_name) VALUES
  ('kg','公斤'),('kg','kgs'),('t','公吨'),('t','mt'),
  ('mm','毫米'),('m','米'),('km','千米'),('km','公里'),
  ('mm²','平方毫米'),('m²','平方米'),('卷','卷装'),('盘','盘装'),
  ('件','个'),('件','pcs'),('块','块装'),('托','托盘'),('包','袋'),
  ('轴','线轴'),('轴','spool'),('桶','drum'),('盒','小盒');

CREATE TEMPORARY TABLE tmp_v49_field_definition (
  name varchar(128) NOT NULL PRIMARY KEY,
  field_type varchar(32) NOT NULL,
  value_type varchar(32) NOT NULL,
  unit_category_name varchar(64) NOT NULL,
  option_json varchar(1000) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_v49_field_definition(
  name,field_type,value_type,unit_category_name,option_json
) VALUES
  ('交货形态','SELECT','TEXT','','["直条","盘卷","水平复绕","蚊香盘","卷装","板张","锭","桶装","轴装","箱装"]'),
  ('覆层材质','SELECT','TEXT','','["PE","PVC","橡塑","其他"]'),
  ('覆层厚度','NUMBER','DECIMAL','长度','[]'),
  ('螺旋角','NUMBER','DECIMAL','角度','[]'),
  ('齿顶角','NUMBER','DECIMAL','角度','[]'),
  ('槽底宽','NUMBER','DECIMAL','长度','[]'),
  ('边部状态','SELECT','TEXT','','["切边","不切边","圆边","去毛刺"]'),
  ('卷内径','NUMBER','DECIMAL','长度','[]'),
  ('卷外径','NUMBER','DECIMAL','长度','[]'),
  ('表面状态','SELECT','TEXT','','["轧制","拉制","抛光","镀层","清洗","其他"]'),
  ('棒材截面','SELECT','TEXT','','["圆形","方形","六角形","扁形","异形"]'),
  ('对边尺寸','NUMBER','DECIMAL','长度','[]'),
  ('图纸编号','TEXT','TEXT','','[]'),
  ('图纸版本','TEXT','TEXT','','[]'),
  ('镀层材质','SELECT','TEXT','','["锡","镍","银","锌","铜","其他"]'),
  ('镀层厚度','NUMBER','DECIMAL','长度','[]'),
  ('导体形状','SELECT','TEXT','','["圆形","扁形","并线","绞线","利兹线","异形"]'),
  ('标称截面积','NUMBER','DECIMAL','面积','[]'),
  ('单丝直径','NUMBER','DECIMAL','长度','[]'),
  ('股数','NUMBER','INTEGER','','[]'),
  ('绞向','SELECT','TEXT','','["左向","右向"]'),
  ('绞距','NUMBER','DECIMAL','长度','[]'),
  ('漆膜体系','TEXT','TEXT','','[]'),
  ('热级','SELECT','TEXT','','["105","120","130","155","180","200","220","240"]'),
  ('膜厚等级','SELECT','TEXT','','["1级","2级","3级","加厚","自定义"]'),
  ('线规AWG','TEXT','TEXT','','[]'),
  ('阀门类型','SELECT','TEXT','','["球阀","闸阀","截止阀","止回阀","蝶阀","温控阀","分集水器","其他"]'),
  ('公称通径','TEXT','TEXT','','[]'),
  ('公称压力','TEXT','TEXT','','[]'),
  ('连接形式','TEXT','TEXT','','[]'),
  ('阀体材质','TEXT','TEXT','','[]'),
  ('阀芯材质','TEXT','TEXT','','[]'),
  ('密封材质','TEXT','TEXT','','[]'),
  ('驱动方式','SELECT','TEXT','','["手动","电动","气动","热力","蜗轮","其他"]'),
  ('适用介质','TEXT','TEXT','','[]'),
  ('温度下限','NUMBER','DECIMAL','温度','[]'),
  ('温度上限','NUMBER','DECIMAL','温度','[]'),
  ('磁材牌号','TEXT','TEXT','','[]'),
  ('磁体形状','SELECT','TEXT','','["方块","圆柱","圆环","瓦形","异形"]'),
  ('磁化方向','TEXT','TEXT','','[]'),
  ('涂层体系','TEXT','TEXT','','[]'),
  ('最高工作温度','NUMBER','DECIMAL','温度','[]'),
  ('锭型','TEXT','TEXT','','[]'),
  ('焊材形态','SELECT','TEXT','','["焊丝","焊条","盘丝","直条","桶装连续丝"]'),
  ('绝缘层结构','TEXT','TEXT','','[]'),
  ('客户图号','TEXT','TEXT','','[]'),
  ('质量等级','TEXT','TEXT','','[]'),
  ('圆线直径','NUMBER','DECIMAL','长度','[]'),
  ('扁线宽度','NUMBER','DECIMAL','长度','[]'),
  ('扁线厚度','NUMBER','DECIMAL','长度','[]'),
  ('磁体长度','NUMBER','DECIMAL','长度','[]'),
  ('磁体宽度','NUMBER','DECIMAL','长度','[]'),
  ('磁体厚度','NUMBER','DECIMAL','长度','[]'),
  ('磁体直径','NUMBER','DECIMAL','长度','[]'),
  ('外径','NUMBER','DECIMAL','长度','[]'),
  ('壁厚','NUMBER','DECIMAL','长度','[]'),
  ('长度','NUMBER','DECIMAL','长度','[]'),
  ('颜色','SELECT','TEXT','','["黑色","红色","蓝色"]'),
  ('管件类型','SELECT','TEXT','','["卡槽紫铜直接","异径紫铜直接","紫铜三通","弯头","管帽","直接","更多规格"]'),
  ('内径','NUMBER','DECIMAL','长度','[]'),
  ('底壁厚','NUMBER','DECIMAL','长度','[]'),
  ('齿高','NUMBER','DECIMAL','长度','[]'),
  ('螺纹数','NUMBER','INTEGER','','[]'),
  ('阻燃等级','SELECT','TEXT','','["B1级"]'),
  ('宽度','NUMBER','DECIMAL','长度','[]'),
  ('厚度','NUMBER','DECIMAL','长度','[]'),
  ('牌号','TEXT','TEXT','','[]'),
  ('纯度','TEXT','TEXT','','[]'),
  ('材质','TEXT','TEXT','','[]'),
  ('材料状态','TEXT','TEXT','','[]'),
  ('执行标准','TEXT','TEXT','','[]');

CREATE TEMPORARY TABLE tmp_v49_trade_template_definition (
  code varchar(64) NOT NULL PRIMARY KEY,
  name varchar(128) NOT NULL UNIQUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_v49_trade_template_definition(code,name) VALUES
  ('TRADE-WEIGHT-TUBE','重量计量-管材'),
  ('TRADE-WEIGHT-COIL','重量计量-卷材'),
  ('TRADE-WEIGHT-LONG','重量计量-长材'),
  ('TRADE-WEIGHT-SHEET','重量计量-板片'),
  ('TRADE-WEIGHT-BULK','重量计量-块料'),
  ('TRADE-WEIGHT-WELD','重量计量-焊材');

CREATE TEMPORARY TABLE tmp_v49_series_dependency (
  series_name varchar(128) NOT NULL PRIMARY KEY,
  packaging_template_code varchar(64) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_v49_series_dependency(series_name,packaging_template_code) VALUES
  ('通用圆形铜管','TRADE-WEIGHT-TUBE'),('高强合金铜管','TRADE-WEIGHT-TUBE'),
  ('无氧铜管','TRADE-WEIGHT-TUBE'),('盘卷铜管','TRADE-WEIGHT-TUBE'),
  ('覆层铜管','TRADE-WEIGHT-TUBE'),('保温管材','BASE-STICK'),
  ('内螺纹换热管','TRADE-WEIGHT-TUBE'),('多端口铜管件','BASE-PIECE'),
  ('铜带卷材','TRADE-WEIGHT-COIL'),('铜板材','TRADE-WEIGHT-SHEET'),
  ('圆形铜合金棒','TRADE-WEIGHT-LONG'),('方形铜合金棒','TRADE-WEIGHT-LONG'),
  ('六角铜合金棒','TRADE-WEIGHT-LONG'),('扁形铜合金棒','TRADE-WEIGHT-LONG'),
  ('铜合金锭','TRADE-WEIGHT-BULK'),('异形截面棒材','TRADE-WEIGHT-LONG'),
  ('铜合金圆线','TRADE-WEIGHT-COIL'),('铜合金扁线','TRADE-WEIGHT-COIL'),
  ('电工圆铜杆','TRADE-WEIGHT-COIL'),('电工圆铜单丝','TRADE-WEIGHT-COIL'),
  ('镀锡铜线','TRADE-WEIGHT-COIL'),('并线与绞线','TRADE-WEIGHT-COIL'),
  ('直条矩形铜排','TRADE-WEIGHT-LONG'),('卷制矩形铜排','TRADE-WEIGHT-COIL'),
  ('盘圆铜材','TRADE-WEIGHT-COIL'),('异形铜排','TRADE-WEIGHT-LONG'),
  ('漆包圆线','TRADE-WEIGHT-COIL'),('漆包扁线','TRADE-WEIGHT-COIL'),
  ('特种绝缘导线','TRADE-WEIGHT-COIL'),('工业阀门','BASE-PIECE'),
  ('矩形钕铁硼磁体','BASE-PIECE'),('圆柱环形钕铁硼磁体','BASE-PIECE'),
  ('异形钕铁硼磁体','BASE-PIECE'),('磁性组件','BASE-PIECE'),
  ('铜及铜合金焊丝','TRADE-WEIGHT-WELD'),('铜及铜合金焊条','TRADE-WEIGHT-WELD'),
  ('再生铜阴极','TRADE-WEIGHT-BULK');

CREATE TEMPORARY TABLE tmp_v49_preflight_guard (
  must_be_non_null tinyint NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Existing categories may be absent (they are created below), but an existing
-- dependency must be unique and enabled.
INSERT INTO tmp_v49_preflight_guard(must_be_non_null)
SELECT NULL
FROM tmp_v49_unit_category_definition d
LEFT JOIN unit_category c ON c.name=d.name
GROUP BY d.name
HAVING count(c.id)>1
    OR (max(d.must_exist)=1 AND count(c.id)<>1)
    OR (count(c.id)=1 AND min(c.status)<>'ENABLED')
LIMIT 1;

-- A planned unit may reuse an existing row only when name, abbreviation,
-- category, status and the derived pre-V49 kind are all identical.
INSERT INTO tmp_v49_preflight_guard(must_be_non_null)
SELECT NULL
FROM tmp_v49_unit_definition d
JOIN unit u
  ON lower(regexp_replace(trim(u.name),'[[:space:]]+',''))=
       lower(regexp_replace(trim(d.name),'[[:space:]]+',''))
  OR lower(regexp_replace(trim(u.abbreviation),'[[:space:]]+',''))=
       lower(regexp_replace(trim(d.abbreviation),'[[:space:]]+',''))
JOIN unit_category c ON c.id=u.unit_category_id
WHERE c.name<>d.category_name
   OR lower(regexp_replace(trim(u.name),'[[:space:]]+',''))<>
        lower(regexp_replace(trim(d.name),'[[:space:]]+',''))
   OR lower(regexp_replace(trim(u.abbreviation),'[[:space:]]+',''))<>
        lower(regexp_replace(trim(d.abbreviation),'[[:space:]]+',''))
   OR u.status<>'ENABLED'
   OR (case
         when u.abbreviation in ('卷','箱','盘','托','包') then 'PACKAGE'
         when u.abbreviation in ('根','件','支') then 'COUNT'
         else 'MEASURE'
       end)<>d.unit_kind
LIMIT 1;

CREATE TEMPORARY TABLE tmp_v49_unit_token_candidate (
  logical_unit_key varchar(96) NOT NULL,
  source_text varchar(64) NOT NULL,
  normalized_token varchar(64) NOT NULL,
  KEY idx_tmp_v49_unit_token (normalized_token,logical_unit_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_v49_unit_token_candidate(
  logical_unit_key,source_text,normalized_token
)
SELECT concat('UNIT:',u.id),u.name,
       lower(regexp_replace(trim(u.name),'[[:space:]]+',''))
FROM unit u
UNION ALL
SELECT concat('UNIT:',u.id),u.abbreviation,
       lower(regexp_replace(trim(u.abbreviation),'[[:space:]]+',''))
FROM unit u
WHERE trim(u.abbreviation)<>'';

INSERT INTO tmp_v49_unit_token_candidate(
  logical_unit_key,source_text,normalized_token
)
SELECT coalesce(concat('UNIT:',u.id),concat('NEW:',d.abbreviation)),d.name,
       lower(regexp_replace(trim(d.name),'[[:space:]]+',''))
FROM tmp_v49_unit_definition d
 LEFT JOIN unit u
  ON lower(regexp_replace(trim(u.name),'[[:space:]]+',''))=
       lower(regexp_replace(trim(d.name),'[[:space:]]+',''))
 AND lower(regexp_replace(trim(u.abbreviation),'[[:space:]]+',''))=
       lower(regexp_replace(trim(d.abbreviation),'[[:space:]]+',''));

INSERT INTO tmp_v49_unit_token_candidate(
  logical_unit_key,source_text,normalized_token
)
SELECT coalesce(concat('UNIT:',u.id),concat('NEW:',d.abbreviation)),d.abbreviation,
       lower(regexp_replace(trim(d.abbreviation),'[[:space:]]+',''))
FROM tmp_v49_unit_definition d
LEFT JOIN unit u
  ON lower(regexp_replace(trim(u.name),'[[:space:]]+',''))=
       lower(regexp_replace(trim(d.name),'[[:space:]]+',''))
 AND lower(regexp_replace(trim(u.abbreviation),'[[:space:]]+',''))=
       lower(regexp_replace(trim(d.abbreviation),'[[:space:]]+',''));

INSERT INTO tmp_v49_unit_token_candidate(
  logical_unit_key,source_text,normalized_token
)
SELECT coalesce(concat('UNIT:',u.id),concat('NEW:',d.abbreviation)),a.alias_name,
       lower(regexp_replace(trim(a.alias_name),'[[:space:]]+',''))
FROM tmp_v49_unit_alias_definition a
LEFT JOIN tmp_v49_unit_definition d ON d.abbreviation=a.abbreviation
LEFT JOIN unit u
  ON lower(regexp_replace(trim(u.abbreviation),'[[:space:]]+',''))=
       lower(regexp_replace(trim(a.abbreviation),'[[:space:]]+',''));

-- One normalized token must never resolve to more than one unit. This check
-- includes names, abbreviations and the aliases that will be inserted below.
INSERT INTO tmp_v49_preflight_guard(must_be_non_null)
SELECT NULL
FROM tmp_v49_unit_token_candidate
GROUP BY normalized_token
HAVING normalized_token='' OR count(distinct logical_unit_key)<>1
LIMIT 1;

CREATE TEMPORARY TABLE tmp_v49_required_unit (
  abbreviation varchar(32) NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_v49_required_unit(abbreviation) VALUES
  ('kg'),('t'),('根'),('支'),('盘'),('卷'),('轴'),('张'),('片'),
  ('块'),('托'),('包'),('桶'),('件'),('箱'),('盒');

INSERT INTO tmp_v49_preflight_guard(must_be_non_null)
SELECT NULL
FROM tmp_v49_required_unit required_unit
LEFT JOIN unit u
  ON lower(regexp_replace(trim(u.abbreviation),'[[:space:]]+',''))=
     lower(regexp_replace(trim(required_unit.abbreviation),'[[:space:]]+',''))
LEFT JOIN tmp_v49_unit_definition d
  ON lower(regexp_replace(trim(d.abbreviation),'[[:space:]]+',''))=
     lower(regexp_replace(trim(required_unit.abbreviation),'[[:space:]]+',''))
GROUP BY required_unit.abbreviation
HAVING count(distinct u.id)>1
    OR (count(distinct u.id)=1 AND min(u.status)<>'ENABLED')
    OR (count(distinct u.id)=0 AND count(distinct d.abbreviation)<>1)
LIMIT 1;

-- Planned template codes/names are new immutable heads.
INSERT INTO tmp_v49_preflight_guard(must_be_non_null)
SELECT NULL
FROM tmp_v49_trade_template_definition d
JOIN packaging_template t ON t.code=d.code OR t.name=d.name
LIMIT 1;

-- Existing base templates used by the new series must have one enabled,
-- published current revision and five valid enabled unit references.
INSERT INTO tmp_v49_preflight_guard(must_be_non_null)
SELECT NULL
FROM (
  SELECT DISTINCT packaging_template_code
  FROM tmp_v49_series_dependency
  WHERE packaging_template_code NOT LIKE 'TRADE-WEIGHT-%'
) d
LEFT JOIN packaging_template t ON t.code=d.packaging_template_code
LEFT JOIN packaging_template_revision r ON r.id=t.current_revision_id
LEFT JOIN unit base_u ON base_u.id=r.base_unit_id AND base_u.status='ENABLED'
LEFT JOIN unit sale_u ON sale_u.id=r.minimum_sales_unit_id AND sale_u.status='ENABLED'
LEFT JOIN unit outbound_u ON outbound_u.id=r.minimum_outbound_unit_id AND outbound_u.status='ENABLED'
LEFT JOIN unit purchase_u ON purchase_u.id=r.default_purchase_unit_id AND purchase_u.status='ENABLED'
LEFT JOIN unit default_sale_u ON default_sale_u.id=r.default_sales_unit_id AND default_sale_u.status='ENABLED'
GROUP BY d.packaging_template_code
HAVING count(distinct t.id)<>1
    OR min(t.status)<>'ENABLED'
    OR count(distinct r.id)<>1
    OR min(r.revision_status)<>'PUBLISHED'
    OR count(distinct base_u.id)<>1
    OR count(distinct sale_u.id)<>1
    OR count(distinct outbound_u.id)<>1
    OR count(distinct purchase_u.id)<>1
    OR count(distinct default_sale_u.id)<>1
LIMIT 1;

-- Fields that already exist must be semantically identical. Missing fields are
-- created after all checks pass.
INSERT INTO tmp_v49_preflight_guard(must_be_non_null)
SELECT NULL
FROM tmp_v49_field_definition d
LEFT JOIN unit_category c ON c.name=nullif(d.unit_category_name,'')
JOIN product_custom_field f ON f.name=d.name
WHERE f.field_type<>d.field_type
   OR f.value_type<>d.value_type
   OR coalesce(f.unit_category_id,0)<>coalesce(c.id,0)
   OR NOT (
     json_contains(f.option_json,d.option_json)
     AND json_contains(d.option_json,f.option_json)
   )
   OR f.status<>'ENABLED'
LIMIT 1;

-- New identity series must not reuse an existing head.
INSERT INTO tmp_v49_preflight_guard(must_be_non_null)
SELECT NULL
FROM tmp_v49_series_dependency d
JOIN product_series s ON s.name=d.series_name
LIMIT 1;

DROP TEMPORARY TABLE tmp_v49_unit_token_candidate;
DROP TEMPORARY TABLE tmp_v49_required_unit;
DROP TEMPORARY TABLE tmp_v49_preflight_guard;
DROP TEMPORARY TABLE tmp_v49_series_dependency;
DROP TEMPORARY TABLE tmp_v49_trade_template_definition;
DROP TEMPORARY TABLE tmp_v49_field_definition;
DROP TEMPORARY TABLE tmp_v49_unit_alias_definition;
DROP TEMPORARY TABLE tmp_v49_unit_definition;
DROP TEMPORARY TABLE tmp_v49_unit_category_definition;

ALTER TABLE unit
  ADD COLUMN unit_kind varchar(24) NOT NULL DEFAULT 'MEASURE'
    COMMENT 'MEASURE, COUNT or PACKAGE' AFTER description;

UPDATE unit
SET unit_kind = CASE
  WHEN abbreviation IN ('卷', '箱', '盘', '托', '包') THEN 'PACKAGE'
  WHEN abbreviation IN ('根', '件', '支') THEN 'COUNT'
  ELSE 'MEASURE'
END;

INSERT INTO unit_category(name, description, status)
SELECT '数量', '件、根、支、张、片、只、套等离散数量', 'ENABLED'
WHERE NOT EXISTS (SELECT 1 FROM unit_category WHERE name = '数量');

INSERT INTO unit_category(name, description, status)
SELECT '面积', '板带及导体截面积', 'ENABLED'
WHERE NOT EXISTS (SELECT 1 FROM unit_category WHERE name = '面积');

INSERT INTO unit_category(name, description, status)
SELECT '压力', '公称压力和试验压力', 'ENABLED'
WHERE NOT EXISTS (SELECT 1 FROM unit_category WHERE name = '压力');

INSERT INTO unit_category(name, description, status)
SELECT '温度', '工作温度和试验温度', 'ENABLED'
WHERE NOT EXISTS (SELECT 1 FROM unit_category WHERE name = '温度');

INSERT INTO unit_category(name, description, status)
SELECT '角度', '螺旋角、齿形角及连接角度', 'ENABLED'
WHERE NOT EXISTS (SELECT 1 FROM unit_category WHERE name = '角度');

CREATE TEMPORARY TABLE tmp_product_unit_definition (
  category_name varchar(64) NOT NULL,
  name varchar(64) NOT NULL,
  abbreviation varchar(32) NOT NULL,
  description varchar(255) NOT NULL,
  unit_kind varchar(24) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_product_unit_definition(category_name, name, abbreviation, description, unit_kind) VALUES
  ('长度', '千米', 'km', '长距离线缆和线材', 'MEASURE'),
  ('长度', '微米', 'μm', '镀层和涂层厚度', 'MEASURE'),
  ('数量', '张', '张', '板材张数', 'COUNT'),
  ('数量', '片', '片', '片状材料或磁体', 'COUNT'),
  ('数量', '块', '块', '锭、阴极板或块状材料', 'COUNT'),
  ('数量', '只', '只', '阀门和独立器件', 'COUNT'),
  ('数量', '套', '套', '成套件或组件', 'COUNT'),
  ('包装', '轴', '轴', '线轴或盘具包装', 'PACKAGE'),
  ('包装', '桶', '桶', '桶装焊丝等包装', 'PACKAGE'),
  ('包装', '盒', '盒', '小盒包装', 'PACKAGE'),
  ('面积', '平方毫米', 'mm²', '导体标称截面积', 'MEASURE'),
  ('面积', '平方米', 'm²', '板带面积', 'MEASURE'),
  ('压力', '兆帕', 'MPa', '压力单位', 'MEASURE'),
  ('温度', '摄氏度', '℃', '温度单位', 'MEASURE'),
  ('角度', '度', '°', '角度单位', 'MEASURE');

INSERT INTO unit(unit_category_id, name, abbreviation, description, unit_kind, status)
SELECT c.id, d.name, d.abbreviation, d.description, d.unit_kind, 'ENABLED'
FROM tmp_product_unit_definition d
JOIN unit_category c ON c.name = d.category_name
WHERE NOT EXISTS (
  SELECT 1 FROM unit u
  WHERE lower(regexp_replace(trim(u.abbreviation),'[[:space:]]+','')) =
        lower(regexp_replace(trim(d.abbreviation),'[[:space:]]+',''))
     OR lower(regexp_replace(trim(u.name),'[[:space:]]+','')) =
        lower(regexp_replace(trim(d.name),'[[:space:]]+',''))
);

DROP TEMPORARY TABLE tmp_product_unit_definition;

CREATE TABLE unit_alias (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  unit_id bigint unsigned NOT NULL,
  alias_name varchar(64) NOT NULL,
  normalized_alias varchar(64) GENERATED ALWAYS AS
    (lower(regexp_replace(trim(alias_name),'[[:space:]]+',''))) STORED,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_unit_alias_normalized (normalized_alias),
  KEY idx_unit_alias_unit (unit_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='计量单位导入别名';

INSERT INTO unit_alias(unit_id, alias_name)
SELECT u.id, a.alias_name
FROM (
  SELECT 'kg' abbreviation, '公斤' alias_name
  UNION ALL SELECT 'kg', 'kgs'
  UNION ALL SELECT 't', '公吨'
  UNION ALL SELECT 't', 'mt'
  UNION ALL SELECT 'mm', '毫米'
  UNION ALL SELECT 'm', '米'
  UNION ALL SELECT 'km', '千米'
  UNION ALL SELECT 'km', '公里'
  UNION ALL SELECT 'mm²', '平方毫米'
  UNION ALL SELECT 'm²', '平方米'
  UNION ALL SELECT '卷', '卷装'
  UNION ALL SELECT '盘', '盘装'
  UNION ALL SELECT '件', '个'
  UNION ALL SELECT '件', 'pcs'
  UNION ALL SELECT '块', '块装'
  UNION ALL SELECT '托', '托盘'
  UNION ALL SELECT '包', '袋'
  UNION ALL SELECT '轴', '线轴'
  UNION ALL SELECT '轴', 'spool'
  UNION ALL SELECT '桶', 'drum'
  UNION ALL SELECT '盒', '小盒'
) a
JOIN unit u
  ON lower(regexp_replace(trim(u.abbreviation),'[[:space:]]+','')) =
     lower(regexp_replace(trim(a.abbreviation),'[[:space:]]+',''));

CREATE TABLE unit_lookup_token (
  normalized_token varchar(64) NOT NULL,
  unit_id bigint unsigned NOT NULL,
  token_type varchar(24) NOT NULL COMMENT 'NAME,ABBREVIATION,ALIAS',
  source_text varchar(64) NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (normalized_token),
  KEY idx_unit_lookup_token_unit (unit_id,token_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Global unique lookup token shared by unit names abbreviations and aliases';

INSERT INTO unit_lookup_token(
  normalized_token,unit_id,token_type,source_text
)
SELECT normalized_token,min(unit_id),
       case min(token_priority)
         when 1 then 'NAME'
         when 2 then 'ABBREVIATION'
         else 'ALIAS'
       end,
       min(source_text)
FROM (
  SELECT lower(regexp_replace(trim(name),'[[:space:]]+','')) normalized_token,
         id unit_id,1 token_priority,name source_text
  FROM unit
  UNION ALL
  SELECT lower(regexp_replace(trim(abbreviation),'[[:space:]]+','')),
         id,2,abbreviation
  FROM unit
  WHERE trim(abbreviation)<>''
  UNION ALL
  SELECT normalized_alias,unit_id,3,alias_name
  FROM unit_alias
) token
GROUP BY normalized_token;

-- Product classification remains configuration data; no transaction code depends on a
-- hard-coded category or series identifier.
INSERT INTO product_category(parent_id, name, sort_no, status)
SELECT 0, '铜线材', 80, 'ENABLED'
WHERE NOT EXISTS (SELECT 1 FROM product_category WHERE parent_id = 0 AND name = '铜线材');

INSERT INTO product_category(parent_id, name, sort_no, status)
SELECT 0, '电磁线', 90, 'ENABLED'
WHERE NOT EXISTS (SELECT 1 FROM product_category WHERE parent_id = 0 AND name = '电磁线');

INSERT INTO product_category(parent_id, name, sort_no, status)
SELECT 0, '阀门', 100, 'ENABLED'
WHERE NOT EXISTS (SELECT 1 FROM product_category WHERE parent_id = 0 AND name = '阀门');

INSERT INTO product_category(parent_id, name, sort_no, status)
SELECT 0, '永磁材料', 110, 'ENABLED'
WHERE NOT EXISTS (SELECT 1 FROM product_category WHERE parent_id = 0 AND name = '永磁材料');

CREATE TEMPORARY TABLE tmp_product_category_definition (
  parent_name varchar(128) NOT NULL,
  name varchar(128) NOT NULL,
  sort_no int NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_product_category_definition(parent_name, name, sort_no) VALUES
  ('铜板带', '铜带卷材', 21),
  ('铜板带', '铜板材', 22),
  ('铜棒型材', '合金棒材', 32),
  ('铜线材', '合金线材', 81),
  ('铜线材', '电工铜线杆', 82),
  ('电磁线', '电磁绕组线', 91),
  ('阀门', '工业阀门', 101),
  ('永磁材料', '钕铁硼磁材', 111),
  ('永磁材料', '磁性组件', 112);

INSERT INTO product_category(parent_id, name, sort_no, status)
SELECT p.id, d.name, d.sort_no, 'ENABLED'
FROM tmp_product_category_definition d
JOIN product_category p ON p.parent_id = 0 AND p.name = d.parent_name
WHERE NOT EXISTS (
  SELECT 1 FROM product_category c WHERE c.parent_id = p.id AND c.name = d.name
);

DROP TEMPORARY TABLE tmp_product_category_definition;

CREATE TEMPORARY TABLE tmp_product_field_definition (
  name varchar(128) NOT NULL,
  field_type varchar(32) NOT NULL,
  value_type varchar(32) NOT NULL,
  unit_category_name varchar(64) NOT NULL,
  option_json varchar(1000) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_product_field_definition(name, field_type, value_type, unit_category_name, option_json) VALUES
  ('交货形态', 'SELECT', 'TEXT', '', '["直条","盘卷","水平复绕","蚊香盘","卷装","板张","锭","桶装","轴装","箱装"]'),
  ('覆层材质', 'SELECT', 'TEXT', '', '["PE","PVC","橡塑","其他"]'),
  ('覆层厚度', 'NUMBER', 'DECIMAL', '长度', '[]'),
  ('螺旋角', 'NUMBER', 'DECIMAL', '角度', '[]'),
  ('齿顶角', 'NUMBER', 'DECIMAL', '角度', '[]'),
  ('槽底宽', 'NUMBER', 'DECIMAL', '长度', '[]'),
  ('边部状态', 'SELECT', 'TEXT', '', '["切边","不切边","圆边","去毛刺"]'),
  ('卷内径', 'NUMBER', 'DECIMAL', '长度', '[]'),
  ('卷外径', 'NUMBER', 'DECIMAL', '长度', '[]'),
  ('表面状态', 'SELECT', 'TEXT', '', '["轧制","拉制","抛光","镀层","清洗","其他"]'),
  ('棒材截面', 'SELECT', 'TEXT', '', '["圆形","方形","六角形","扁形","异形"]'),
  ('对边尺寸', 'NUMBER', 'DECIMAL', '长度', '[]'),
  ('图纸编号', 'TEXT', 'TEXT', '', '[]'),
  ('图纸版本', 'TEXT', 'TEXT', '', '[]'),
  ('镀层材质', 'SELECT', 'TEXT', '', '["锡","镍","银","锌","铜","其他"]'),
  ('镀层厚度', 'NUMBER', 'DECIMAL', '长度', '[]'),
  ('导体形状', 'SELECT', 'TEXT', '', '["圆形","扁形","并线","绞线","利兹线","异形"]'),
  ('标称截面积', 'NUMBER', 'DECIMAL', '面积', '[]'),
  ('单丝直径', 'NUMBER', 'DECIMAL', '长度', '[]'),
  ('股数', 'NUMBER', 'INTEGER', '', '[]'),
  ('绞向', 'SELECT', 'TEXT', '', '["左向","右向"]'),
  ('绞距', 'NUMBER', 'DECIMAL', '长度', '[]'),
  ('漆膜体系', 'TEXT', 'TEXT', '', '[]'),
  ('热级', 'SELECT', 'TEXT', '', '["105","120","130","155","180","200","220","240"]'),
  ('膜厚等级', 'SELECT', 'TEXT', '', '["1级","2级","3级","加厚","自定义"]'),
  ('线规AWG', 'TEXT', 'TEXT', '', '[]'),
  ('阀门类型', 'SELECT', 'TEXT', '', '["球阀","闸阀","截止阀","止回阀","蝶阀","温控阀","分集水器","其他"]'),
  ('公称通径', 'TEXT', 'TEXT', '', '[]'),
  ('公称压力', 'TEXT', 'TEXT', '', '[]'),
  ('连接形式', 'TEXT', 'TEXT', '', '[]'),
  ('阀体材质', 'TEXT', 'TEXT', '', '[]'),
  ('阀芯材质', 'TEXT', 'TEXT', '', '[]'),
  ('密封材质', 'TEXT', 'TEXT', '', '[]'),
  ('驱动方式', 'SELECT', 'TEXT', '', '["手动","电动","气动","热力","蜗轮","其他"]'),
  ('适用介质', 'TEXT', 'TEXT', '', '[]'),
  ('温度下限', 'NUMBER', 'DECIMAL', '温度', '[]'),
  ('温度上限', 'NUMBER', 'DECIMAL', '温度', '[]'),
  ('磁材牌号', 'TEXT', 'TEXT', '', '[]'),
  ('磁体形状', 'SELECT', 'TEXT', '', '["方块","圆柱","圆环","瓦形","异形"]'),
  ('磁化方向', 'TEXT', 'TEXT', '', '[]'),
  ('涂层体系', 'TEXT', 'TEXT', '', '[]'),
  ('最高工作温度', 'NUMBER', 'DECIMAL', '温度', '[]'),
  ('锭型', 'TEXT', 'TEXT', '', '[]'),
  ('焊材形态', 'SELECT', 'TEXT', '', '["焊丝","焊条","盘丝","直条","桶装连续丝"]'),
  ('绝缘层结构', 'TEXT', 'TEXT', '', '[]'),
  ('客户图号', 'TEXT', 'TEXT', '', '[]'),
  ('质量等级', 'TEXT', 'TEXT', '', '[]'),
  ('圆线直径', 'NUMBER', 'DECIMAL', '长度', '[]'),
  ('扁线宽度', 'NUMBER', 'DECIMAL', '长度', '[]'),
  ('扁线厚度', 'NUMBER', 'DECIMAL', '长度', '[]'),
  ('磁体长度', 'NUMBER', 'DECIMAL', '长度', '[]'),
  ('磁体宽度', 'NUMBER', 'DECIMAL', '长度', '[]'),
  ('磁体厚度', 'NUMBER', 'DECIMAL', '长度', '[]'),
  ('磁体直径', 'NUMBER', 'DECIMAL', '长度', '[]');

INSERT INTO product_custom_field(name, field_type, value_type, unit_category_id, option_json, status)
SELECT d.name, d.field_type, d.value_type, c.id, d.option_json, 'ENABLED'
FROM tmp_product_field_definition d
LEFT JOIN unit_category c ON c.name = nullif(d.unit_category_name, '')
WHERE NOT EXISTS (SELECT 1 FROM product_custom_field f WHERE f.name = d.name);

DROP TEMPORARY TABLE tmp_product_field_definition;

-- Reusable trade packaging templates keep the inventory fact in kg while allowing
-- fixed tonne conversion and actual-weight physical packages. Each physical package
-- must carry its measured weight at inbound/outbound; estimates never become stock facts.
CREATE TEMPORARY TABLE tmp_trade_packaging_template (
  code varchar(64) NOT NULL,
  name varchar(128) NOT NULL,
  description varchar(500) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_trade_packaging_template(code,name,description) VALUES
  ('TRADE-WEIGHT-TUBE','重量计量-管材','kg库存；支持吨及根、支、盘、卷实重包装'),
  ('TRADE-WEIGHT-COIL','重量计量-卷材','kg库存；支持吨及卷、盘、轴实重包装'),
  ('TRADE-WEIGHT-LONG','重量计量-长材','kg库存；支持吨及根、支、盘实重包装'),
  ('TRADE-WEIGHT-SHEET','重量计量-板片','kg库存；支持吨及张、片实重包装'),
  ('TRADE-WEIGHT-BULK','重量计量-块料','kg库存；支持吨及块、托、包实重包装'),
  ('TRADE-WEIGHT-WELD','重量计量-焊材','kg库存；支持吨及盘、卷、轴、桶、包实重包装');

INSERT INTO packaging_template(
  code,name,description,current_revision_id,current_revision_no,lock_version,status
)
SELECT code,name,description,0,0,0,'ENABLED'
FROM tmp_trade_packaging_template;

INSERT INTO packaging_template_revision(
  template_id,revision_no,revision_status,base_unit_id,base_unit,
  minimum_sales_unit_id,minimum_sales_unit,minimum_outbound_unit_id,minimum_outbound_unit,
  default_purchase_unit_id,default_purchase_unit,default_sales_unit_id,default_sales_unit,
  rounding_policy,content_hash,change_reason,published_at
)
SELECT t.id,1,'PUBLISHED',u.id,u.abbreviation,
       u.id,u.abbreviation,u.id,u.abbreviation,
       u.id,u.abbreviation,u.id,u.abbreviation,
       'REJECT',sha2(concat('TRADE_PACKAGING_V1|',t.code),256),
       '复杂商品重量与实重包装初始模板',current_timestamp
FROM packaging_template t
JOIN tmp_trade_packaging_template d ON d.code=t.code
JOIN unit u ON u.abbreviation='kg';

CREATE TEMPORARY TABLE tmp_trade_packaging_rule (
  template_code varchar(64) NOT NULL,
  parent_unit varchar(32) NOT NULL,
  conversion_mode varchar(24) NOT NULL,
  fixed_child_quantity decimal(24,6) DEFAULT NULL,
  purchase_allowed tinyint unsigned NOT NULL,
  sales_allowed tinyint unsigned NOT NULL,
  outbound_allowed tinyint unsigned NOT NULL,
  sort_no int NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_trade_packaging_rule(
  template_code,parent_unit,conversion_mode,fixed_child_quantity,
  purchase_allowed,sales_allowed,outbound_allowed,sort_no
) VALUES
  ('TRADE-WEIGHT-TUBE','t','FIXED',1000.000000,1,1,1,10),
  ('TRADE-WEIGHT-TUBE','根','ACTUAL_WEIGHT',null,1,1,1,20),
  ('TRADE-WEIGHT-TUBE','支','ACTUAL_WEIGHT',null,1,1,1,30),
  ('TRADE-WEIGHT-TUBE','盘','ACTUAL_WEIGHT',null,1,1,1,40),
  ('TRADE-WEIGHT-TUBE','卷','ACTUAL_WEIGHT',null,1,1,1,50),
  ('TRADE-WEIGHT-COIL','t','FIXED',1000.000000,1,1,1,10),
  ('TRADE-WEIGHT-COIL','卷','ACTUAL_WEIGHT',null,1,1,1,20),
  ('TRADE-WEIGHT-COIL','盘','ACTUAL_WEIGHT',null,1,1,1,30),
  ('TRADE-WEIGHT-COIL','轴','ACTUAL_WEIGHT',null,1,1,1,40),
  ('TRADE-WEIGHT-LONG','t','FIXED',1000.000000,1,1,1,10),
  ('TRADE-WEIGHT-LONG','根','ACTUAL_WEIGHT',null,1,1,1,20),
  ('TRADE-WEIGHT-LONG','支','ACTUAL_WEIGHT',null,1,1,1,30),
  ('TRADE-WEIGHT-LONG','盘','ACTUAL_WEIGHT',null,1,1,1,40),
  ('TRADE-WEIGHT-SHEET','t','FIXED',1000.000000,1,1,1,10),
  ('TRADE-WEIGHT-SHEET','张','ACTUAL_WEIGHT',null,1,1,1,20),
  ('TRADE-WEIGHT-SHEET','片','ACTUAL_WEIGHT',null,1,1,1,30),
  ('TRADE-WEIGHT-BULK','t','FIXED',1000.000000,1,1,1,10),
  ('TRADE-WEIGHT-BULK','块','ACTUAL_WEIGHT',null,1,1,1,20),
  ('TRADE-WEIGHT-BULK','托','ACTUAL_WEIGHT',null,1,1,1,30),
  ('TRADE-WEIGHT-BULK','包','ACTUAL_WEIGHT',null,1,1,1,40),
  ('TRADE-WEIGHT-WELD','t','FIXED',1000.000000,1,1,1,10),
  ('TRADE-WEIGHT-WELD','盘','ACTUAL_WEIGHT',null,1,1,1,20),
  ('TRADE-WEIGHT-WELD','卷','ACTUAL_WEIGHT',null,1,1,1,30),
  ('TRADE-WEIGHT-WELD','轴','ACTUAL_WEIGHT',null,1,1,1,40),
  ('TRADE-WEIGHT-WELD','桶','ACTUAL_WEIGHT',null,1,1,1,50),
  ('TRADE-WEIGHT-WELD','包','ACTUAL_WEIGHT',null,1,1,1,60);

INSERT INTO packaging_template_rule(
  template_revision_id,rule_key,parent_unit_id,parent_unit,child_unit_id,child_unit,
  conversion_mode,fixed_child_quantity,reference_base_quantity,breakable,repackable,
  purchase_allowed,sales_allowed,outbound_allowed,integer_only,sort_no,status
)
SELECT r.id,
       concat(lower(replace(d.parent_unit,' ','')),'-to-kg'),
       pu.id,pu.abbreviation,kg.id,kg.abbreviation,
       d.conversion_mode,d.fixed_child_quantity,null,0,0,
       d.purchase_allowed,d.sales_allowed,d.outbound_allowed,
       case when d.conversion_mode='ACTUAL_WEIGHT' then 1 else 0 end,
       d.sort_no,'ENABLED'
FROM tmp_trade_packaging_rule d
JOIN packaging_template t ON t.code=d.template_code
JOIN packaging_template_revision r ON r.template_id=t.id AND r.revision_no=1
JOIN unit pu ON pu.abbreviation=d.parent_unit
JOIN unit kg ON kg.abbreviation='kg';

UPDATE packaging_template t
JOIN packaging_template_revision r ON r.template_id=t.id AND r.revision_no=1
JOIN tmp_trade_packaging_template d ON d.code=t.code
SET t.current_revision_id=r.id,
    t.current_revision_no=1,
    t.lock_version=1;

DROP TEMPORARY TABLE tmp_trade_packaging_rule;
DROP TEMPORARY TABLE tmp_trade_packaging_template;

CREATE TEMPORARY TABLE tmp_product_series_definition (
  category_parent varchar(128) NOT NULL,
  category_name varchar(128) NOT NULL,
  series_name varchar(128) NOT NULL,
  alias_names varchar(255) NOT NULL,
  package_category_name varchar(64) NOT NULL,
  price_category_name varchar(64) NOT NULL,
  packaging_template_code varchar(64) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_product_series_definition(
  category_parent, category_name, series_name, alias_names,
  package_category_name, price_category_name, packaging_template_code
) VALUES
  ('', '铜管', '通用圆形铜管', '直管,水管,毛细管', '包装', '重量', 'TRADE-WEIGHT-TUBE'),
  ('铜管', '特种铜管', '高强合金铜管', '铜铁合金管,铜锡合金管', '包装', '重量', 'TRADE-WEIGHT-TUBE'),
  ('铜管', '特种铜管', '无氧铜管', '无氧直管,无氧盘管', '包装', '重量', 'TRADE-WEIGHT-TUBE'),
  ('铜管', '制冷铜管', '盘卷铜管', '大散盘,水平复绕盘,蚊香盘', '包装', '重量', 'TRADE-WEIGHT-TUBE'),
  ('铜管', '覆塑铜管', '覆层铜管', '覆塑管,包覆管', '包装', '重量', 'TRADE-WEIGHT-TUBE'),
  ('保温材料', '空调保温材料', '保温管材', '橡塑保温管,管状保温材料', '包装', '数量', 'BASE-STICK'),
  ('铜管', '特种铜管', '内螺纹换热管', '内沟槽管,换热器管', '包装', '重量', 'TRADE-WEIGHT-TUBE'),
  ('铜管件', '紫铜管件', '多端口铜管件', '弯头,三通,异径接头,卡压管件', '包装', '数量', 'BASE-PIECE'),
  ('铜板带', '铜带卷材', '铜带卷材', '紫铜带,黄铜带,青铜带,白铜带', '包装', '重量', 'TRADE-WEIGHT-COIL'),
  ('铜板带', '铜板材', '铜板材', '紫铜板,黄铜板,白铜板', '包装', '重量', 'TRADE-WEIGHT-SHEET'),
  ('铜棒型材', '合金棒材', '圆形铜合金棒', '圆棒,铜圆棒', '包装', '重量', 'TRADE-WEIGHT-LONG'),
  ('铜棒型材', '合金棒材', '方形铜合金棒', '方棒,铜方棒', '包装', '重量', 'TRADE-WEIGHT-LONG'),
  ('铜棒型材', '合金棒材', '六角铜合金棒', '六角棒,铜六角棒', '包装', '重量', 'TRADE-WEIGHT-LONG'),
  ('铜棒型材', '合金棒材', '扁形铜合金棒', '扁棒,矩形棒', '包装', '重量', 'TRADE-WEIGHT-LONG'),
  ('', '铜原料', '铜合金锭', '铜锭,合金锭', '包装', '重量', 'TRADE-WEIGHT-BULK'),
  ('铜棒型材', '合金棒材', '异形截面棒材', '异型棒,图纸棒材', '包装', '重量', 'TRADE-WEIGHT-LONG'),
  ('铜线材', '合金线材', '铜合金圆线', '黄铜圆线,合金圆线', '包装', '重量', 'TRADE-WEIGHT-COIL'),
  ('铜线材', '合金线材', '铜合金扁线', '黄铜扁线,合金扁线', '包装', '重量', 'TRADE-WEIGHT-COIL'),
  ('铜线材', '电工铜线杆', '电工圆铜杆', '铜杆,线坯', '包装', '重量', 'TRADE-WEIGHT-COIL'),
  ('铜线材', '电工铜线杆', '电工圆铜单丝', '裸铜线,圆铜线', '包装', '重量', 'TRADE-WEIGHT-COIL'),
  ('铜线材', '电工铜线杆', '镀锡铜线', '锡铜线,镀层铜线', '包装', '重量', 'TRADE-WEIGHT-COIL'),
  ('铜线材', '电工铜线杆', '并线与绞线', '并线,绞线,束绞线', '包装', '重量', 'TRADE-WEIGHT-COIL'),
  ('铜棒型材', '铜排母线', '直条矩形铜排', '直排,矩形母排', '包装', '重量', 'TRADE-WEIGHT-LONG'),
  ('铜棒型材', '铜排母线', '卷制矩形铜排', '卷排,软铜排', '包装', '重量', 'TRADE-WEIGHT-COIL'),
  ('铜棒型材', '铜排母线', '盘圆铜材', '盘圆,圆形母材', '包装', '重量', 'TRADE-WEIGHT-COIL'),
  ('铜棒型材', '铜排母线', '异形铜排', '异型母排,图纸铜排', '包装', '重量', 'TRADE-WEIGHT-LONG'),
  ('电磁线', '电磁绕组线', '漆包圆线', '漆包铜圆线,绕组圆线', '包装', '重量', 'TRADE-WEIGHT-COIL'),
  ('电磁线', '电磁绕组线', '漆包扁线', '漆包铜扁线,绕组扁线', '包装', '重量', 'TRADE-WEIGHT-COIL'),
  ('电磁线', '电磁绕组线', '特种绝缘导线', '耐电晕线,零针孔线,高性能绝缘线', '包装', '重量', 'TRADE-WEIGHT-COIL'),
  ('阀门', '工业阀门', '工业阀门', '球阀,闸阀,截止阀,止回阀,蝶阀', '包装', '数量', 'BASE-PIECE'),
  ('永磁材料', '钕铁硼磁材', '矩形钕铁硼磁体', '方块磁体,矩形磁钢', '包装', '数量', 'BASE-PIECE'),
  ('永磁材料', '钕铁硼磁材', '圆柱环形钕铁硼磁体', '圆柱磁体,圆环磁体', '包装', '数量', 'BASE-PIECE'),
  ('永磁材料', '钕铁硼磁材', '异形钕铁硼磁体', '瓦形磁体,图纸磁体', '包装', '数量', 'BASE-PIECE'),
  ('永磁材料', '磁性组件', '磁性组件', '磁钢组件,磁性装配件', '包装', '数量', 'BASE-PIECE'),
  ('', '铜焊材', '铜及铜合金焊丝', '铜焊丝,合金焊丝', '包装', '重量', 'TRADE-WEIGHT-WELD'),
  ('', '铜焊材', '铜及铜合金焊条', '铜焊条,合金焊条', '包装', '重量', 'TRADE-WEIGHT-WELD'),
  ('', '铜原料', '再生铜阴极', '再生电解铜,再生阴极铜', '包装', '重量', 'TRADE-WEIGHT-BULK');

CREATE TABLE product_catalog_migration_audit (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  entity_type varchar(32) NOT NULL,
  entity_id bigint unsigned NOT NULL,
  previous_status varchar(16) NOT NULL,
  resulting_status varchar(16) NOT NULL,
  change_reason varchar(500) NOT NULL,
  changed_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_product_catalog_migration_audit (entity_type,entity_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Audited master-data repairs required by the complex product catalogue';

CREATE TEMPORARY TABLE tmp_catalog_category_activation (
  category_id bigint unsigned NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT IGNORE INTO tmp_catalog_category_activation(category_id)
SELECT c.id
FROM tmp_product_series_definition d
JOIN product_category c ON c.name=d.category_name
LEFT JOIN product_category parent_category ON parent_category.id=c.parent_id
WHERE (d.category_parent='' AND c.parent_id=0)
   OR parent_category.name=d.category_parent;

INSERT IGNORE INTO tmp_catalog_category_activation(category_id)
SELECT parent_category.id
FROM tmp_product_series_definition d
JOIN product_category parent_category ON parent_category.name=d.category_parent
WHERE d.category_parent<>'';

INSERT INTO product_catalog_migration_audit(
  entity_type,entity_id,previous_status,resulting_status,change_reason
)
SELECT 'PRODUCT_CATEGORY',c.id,c.status,'ENABLED',
       '复杂商品目录启用引用分类，确保系列可以进入采购销售库存闭环'
FROM product_category c
JOIN tmp_catalog_category_activation a ON a.category_id=c.id
WHERE c.status<>'ENABLED';

UPDATE product_category c
JOIN tmp_catalog_category_activation a ON a.category_id=c.id
SET c.status='ENABLED'
WHERE c.status<>'ENABLED';

DROP TEMPORARY TABLE tmp_catalog_category_activation;

INSERT INTO product_series(
  category_id, name, alias_names, package_unit_category_id, price_unit_category_id, status
)
SELECT c.id, d.series_name, d.alias_names, pc.id, pr.id, 'ENABLED'
FROM tmp_product_series_definition d
JOIN product_category c ON c.name = d.category_name
LEFT JOIN product_category parent_category ON parent_category.id = c.parent_id
JOIN unit_category pc ON pc.name = d.package_category_name
JOIN unit_category pr ON pr.name = d.price_category_name
WHERE (
    (d.category_parent = '' AND c.parent_id = 0)
    OR parent_category.name = d.category_parent
  )
  AND NOT EXISTS (SELECT 1 FROM product_series s WHERE s.name = d.series_name);

INSERT INTO product_series_packaging_binding(
  product_series_id, template_id, lock_version, status, change_reason
)
SELECT s.id, t.id, 1, 'ENABLED', '复杂商品系列初始包装模板'
FROM tmp_product_series_definition d
JOIN product_series s ON s.name = d.series_name
JOIN packaging_template t ON t.code = d.packaging_template_code
WHERE NOT EXISTS (
  SELECT 1 FROM product_series_packaging_binding b WHERE b.product_series_id = s.id
);

-- Materialize purpose-specific unit choices from the active packaging template.
-- This includes the existing baseline series so old and new families share one input rule.
INSERT INTO product_series_allowed_unit(
  product_series_id,purpose,unit_id,unit_name_snapshot,is_default,sort_no,status
)
SELECT x.product_series_id,x.purpose,x.unit_id,
       coalesce(nullif(u.abbreviation,''),u.name),1,x.sort_no,'ENABLED'
FROM (
  SELECT b.product_series_id,'PURCHASE' purpose,r.default_purchase_unit_id unit_id,10 sort_no
  FROM product_series_packaging_binding b
  JOIN packaging_template t ON t.id=b.template_id
  JOIN packaging_template_revision r ON r.id=t.current_revision_id
  WHERE b.status='ENABLED' AND t.status='ENABLED'
  UNION ALL
  SELECT b.product_series_id,'SALES',r.default_sales_unit_id,10
  FROM product_series_packaging_binding b
  JOIN packaging_template t ON t.id=b.template_id
  JOIN packaging_template_revision r ON r.id=t.current_revision_id
  WHERE b.status='ENABLED' AND t.status='ENABLED'
  UNION ALL
  SELECT b.product_series_id,'INVENTORY',r.base_unit_id,10
  FROM product_series_packaging_binding b
  JOIN packaging_template t ON t.id=b.template_id
  JOIN packaging_template_revision r ON r.id=t.current_revision_id
  WHERE b.status='ENABLED' AND t.status='ENABLED'
  UNION ALL
  SELECT b.product_series_id,'PACKAGING',r.base_unit_id,10
  FROM product_series_packaging_binding b
  JOIN packaging_template t ON t.id=b.template_id
  JOIN packaging_template_revision r ON r.id=t.current_revision_id
  WHERE b.status='ENABLED' AND t.status='ENABLED'
  UNION ALL
  SELECT b.product_series_id,'PRICE',r.base_unit_id,10
  FROM product_series_packaging_binding b
  JOIN packaging_template t ON t.id=b.template_id
  JOIN packaging_template_revision r ON r.id=t.current_revision_id
  WHERE b.status='ENABLED' AND t.status='ENABLED'
) x
JOIN unit u ON u.id=x.unit_id
WHERE NOT EXISTS (
  SELECT 1 FROM product_series_allowed_unit a
  WHERE a.product_series_id=x.product_series_id
    AND a.purpose=x.purpose
    AND a.unit_id=x.unit_id
);

INSERT INTO product_series_allowed_unit(
  product_series_id,purpose,unit_id,unit_name_snapshot,is_default,sort_no,status
)
SELECT x.product_series_id,x.purpose,x.unit_id,
       coalesce(nullif(u.abbreviation,''),u.name),0,x.sort_no,'ENABLED'
FROM (
  SELECT b.product_series_id,'PURCHASE' purpose,pr.parent_unit_id unit_id,100+pr.sort_no sort_no
  FROM product_series_packaging_binding b
  JOIN packaging_template t ON t.id=b.template_id
  JOIN packaging_template_rule pr
    ON pr.template_revision_id=t.current_revision_id
   AND pr.status='ENABLED' AND pr.purchase_allowed=1
  WHERE b.status='ENABLED' AND t.status='ENABLED'
  UNION ALL
  SELECT b.product_series_id,'SALES',pr.parent_unit_id,100+pr.sort_no
  FROM product_series_packaging_binding b
  JOIN packaging_template t ON t.id=b.template_id
  JOIN packaging_template_rule pr
    ON pr.template_revision_id=t.current_revision_id
   AND pr.status='ENABLED' AND pr.sales_allowed=1
  WHERE b.status='ENABLED' AND t.status='ENABLED'
  UNION ALL
  SELECT b.product_series_id,'PACKAGING',pr.parent_unit_id,100+pr.sort_no
  FROM product_series_packaging_binding b
  JOIN packaging_template t ON t.id=b.template_id
  JOIN packaging_template_rule pr
    ON pr.template_revision_id=t.current_revision_id AND pr.status='ENABLED'
  WHERE b.status='ENABLED' AND t.status='ENABLED'
  UNION ALL
  SELECT b.product_series_id,'PRICE',pr.parent_unit_id,100+pr.sort_no
  FROM product_series_packaging_binding b
  JOIN packaging_template t ON t.id=b.template_id
  JOIN packaging_template_rule pr
    ON pr.template_revision_id=t.current_revision_id AND pr.status='ENABLED'
  WHERE b.status='ENABLED' AND t.status='ENABLED'
) x
JOIN unit u ON u.id=x.unit_id
WHERE NOT EXISTS (
  SELECT 1 FROM product_series_allowed_unit a
  WHERE a.product_series_id=x.product_series_id
    AND a.purpose=x.purpose
    AND a.unit_id=x.unit_id
);

-- Insulation tubes are stocked by stick. A box is a governed product packaging
-- variant whose quantity may differ by SKU or supplier, never the inventory fact.
INSERT INTO product_series_allowed_unit(
  product_series_id,purpose,unit_id,unit_name_snapshot,is_default,sort_no,status
)
SELECT s.id,p.purpose,u.id,u.abbreviation,0,p.sort_no,'ENABLED'
FROM product_series s
JOIN unit u ON u.abbreviation='箱' AND u.status='ENABLED'
CROSS JOIN (
  SELECT 'PURCHASE' purpose,120 sort_no
  UNION ALL SELECT 'SALES',120
  UNION ALL SELECT 'PACKAGING',120
  UNION ALL SELECT 'PRICE',120
) p
WHERE s.name='保温管材'
  AND NOT EXISTS (
    SELECT 1 FROM product_series_allowed_unit a
    WHERE a.product_series_id=s.id
      AND a.purpose=p.purpose
      AND a.unit_id=u.id
  );

CREATE TEMPORARY TABLE tmp_product_series_field_map (
  series_name varchar(128) NOT NULL,
  field_name varchar(128) NOT NULL,
  required_flag tinyint unsigned NOT NULL,
  sku_dimension tinyint unsigned NOT NULL,
  include_in_spec tinyint unsigned NOT NULL,
  searchable tinyint unsigned NOT NULL,
  order_visible tinyint unsigned NOT NULL,
  sort_no int NOT NULL,
  unit_abbreviation varchar(32) NOT NULL DEFAULT ''
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Common metal identity fields. Supplier codes, packing data and measured quality
-- deliberately do not belong to this signature.
INSERT INTO tmp_product_series_field_map
SELECT series_name, '牌号', 1, 1, 1, 1, 1, 10, ''
FROM tmp_product_series_definition
WHERE series_name NOT IN (
  '保温管材', '多端口铜管件', '漆包圆线', '漆包扁线', '特种绝缘导线',
  '工业阀门', '矩形钕铁硼磁体', '圆柱环形钕铁硼磁体',
  '异形钕铁硼磁体', '磁性组件'
);

INSERT INTO tmp_product_series_field_map
SELECT series_name, '材料状态', 1, 1, 1, 1, 1, 20, ''
FROM tmp_product_series_definition
WHERE series_name NOT IN (
  '保温管材', '多端口铜管件', '漆包圆线', '漆包扁线', '特种绝缘导线',
  '工业阀门', '矩形钕铁硼磁体', '圆柱环形钕铁硼磁体',
  '异形钕铁硼磁体', '磁性组件', '铜合金锭', '再生铜阴极'
);

INSERT INTO tmp_product_series_field_map
SELECT series_name, '执行标准', 1, 1, 1, 1, 1, 30, ''
FROM tmp_product_series_definition
WHERE series_name NOT IN (
  '保温管材', '多端口铜管件', '漆包圆线', '漆包扁线', '特种绝缘导线',
  '工业阀门', '矩形钕铁硼磁体', '圆柱环形钕铁硼磁体',
  '异形钕铁硼磁体', '磁性组件'
);

INSERT INTO tmp_product_series_field_map VALUES
  ('通用圆形铜管','外径',1,1,1,1,1,40,'mm'),
  ('通用圆形铜管','壁厚',1,1,1,1,1,50,'mm'),
  ('通用圆形铜管','长度',0,1,1,1,1,60,'mm'),
  ('通用圆形铜管','交货形态',1,1,1,1,1,70,''),
  ('高强合金铜管','外径',1,1,1,1,1,40,'mm'),
  ('高强合金铜管','壁厚',1,1,1,1,1,50,'mm'),
  ('高强合金铜管','长度',0,1,1,1,1,60,'mm'),
  ('高强合金铜管','交货形态',1,1,1,1,1,70,''),
  ('无氧铜管','外径',1,1,1,1,1,40,'mm'),
  ('无氧铜管','壁厚',1,1,1,1,1,50,'mm'),
  ('无氧铜管','长度',0,1,1,1,1,60,'mm'),
  ('无氧铜管','交货形态',1,1,1,1,1,70,''),
  ('盘卷铜管','外径',1,1,1,1,1,40,'mm'),
  ('盘卷铜管','壁厚',1,1,1,1,1,50,'mm'),
  ('盘卷铜管','交货形态',1,1,1,1,1,60,''),
  ('覆层铜管','外径',1,1,1,1,1,40,'mm'),
  ('覆层铜管','壁厚',1,1,1,1,1,50,'mm'),
  ('覆层铜管','覆层材质',1,1,1,1,1,60,''),
  ('覆层铜管','覆层厚度',1,1,1,1,1,70,'mm'),
  ('覆层铜管','颜色',0,1,1,1,1,80,''),
  ('覆层铜管','交货形态',1,1,1,1,1,90,''),
  ('保温管材','材质',1,1,1,1,1,10,''),
  ('保温管材','内径',1,1,1,1,1,20,'mm'),
  ('保温管材','厚度',1,1,1,1,1,30,'mm'),
  ('保温管材','长度',1,1,1,1,1,40,'mm'),
  ('保温管材','阻燃等级',0,1,1,1,1,50,''),
  ('保温管材','颜色',0,1,1,1,1,60,''),
  ('内螺纹换热管','外径',1,1,1,1,1,40,'mm'),
  ('内螺纹换热管','底壁厚',1,1,1,1,1,50,'mm'),
  ('内螺纹换热管','齿高',1,1,1,1,1,60,'mm'),
  ('内螺纹换热管','螺纹数',1,1,1,1,1,70,''),
  ('内螺纹换热管','螺旋角',1,1,1,1,1,80,'°'),
  ('内螺纹换热管','齿顶角',0,1,1,1,1,90,'°'),
  ('内螺纹换热管','槽底宽',0,1,1,1,1,100,'mm'),
  ('内螺纹换热管','交货形态',1,1,1,1,1,110,''),
  ('多端口铜管件','管件类型',1,1,1,1,1,10,''),
  ('多端口铜管件','材质',1,1,1,1,1,20,''),
  ('多端口铜管件','执行标准',1,1,1,1,1,30,''),
  ('多端口铜管件','交货形态',0,0,1,1,1,40,''),
  ('铜带卷材','厚度',1,1,1,1,1,40,'mm'),
  ('铜带卷材','宽度',1,1,1,1,1,50,'mm'),
  ('铜带卷材','边部状态',0,1,1,1,1,60,''),
  ('铜带卷材','卷内径',0,0,1,1,0,70,'mm'),
  ('铜带卷材','卷外径',0,0,1,1,0,80,'mm'),
  ('铜板材','厚度',1,1,1,1,1,40,'mm'),
  ('铜板材','宽度',1,1,1,1,1,50,'mm'),
  ('铜板材','长度',1,1,1,1,1,60,'mm'),
  ('铜板材','表面状态',0,1,1,1,1,70,''),
  ('圆形铜合金棒','圆线直径',1,1,1,1,1,40,'mm'),
  ('圆形铜合金棒','长度',0,1,1,1,1,50,'mm'),
  ('方形铜合金棒','对边尺寸',1,1,1,1,1,40,'mm'),
  ('方形铜合金棒','长度',0,1,1,1,1,50,'mm'),
  ('六角铜合金棒','对边尺寸',1,1,1,1,1,40,'mm'),
  ('六角铜合金棒','长度',0,1,1,1,1,50,'mm'),
  ('扁形铜合金棒','宽度',1,1,1,1,1,40,'mm'),
  ('扁形铜合金棒','厚度',1,1,1,1,1,50,'mm'),
  ('扁形铜合金棒','长度',0,1,1,1,1,60,'mm'),
  ('铜合金锭','锭型',1,1,1,1,1,20,''),
  ('异形截面棒材','图纸编号',1,1,1,1,1,40,''),
  ('异形截面棒材','图纸版本',1,1,1,1,1,50,''),
  ('异形截面棒材','客户图号',0,0,1,1,1,60,''),
  ('铜合金圆线','圆线直径',1,1,1,1,1,40,'mm'),
  ('铜合金圆线','交货形态',1,1,1,1,1,50,''),
  ('铜合金扁线','扁线宽度',1,1,1,1,1,40,'mm'),
  ('铜合金扁线','扁线厚度',1,1,1,1,1,50,'mm'),
  ('铜合金扁线','交货形态',1,1,1,1,1,60,''),
  ('电工圆铜杆','圆线直径',1,1,1,1,1,40,'mm'),
  ('电工圆铜杆','交货形态',1,1,1,1,1,50,''),
  ('电工圆铜单丝','圆线直径',1,1,1,1,1,40,'mm'),
  ('电工圆铜单丝','交货形态',1,1,1,1,1,50,''),
  ('镀锡铜线','圆线直径',1,1,1,1,1,40,'mm'),
  ('镀锡铜线','镀层材质',1,1,1,1,1,50,''),
  ('镀锡铜线','镀层厚度',1,1,1,1,1,60,'μm'),
  ('并线与绞线','导体形状',1,1,1,1,1,40,''),
  ('并线与绞线','标称截面积',1,1,1,1,1,50,'mm²'),
  ('并线与绞线','单丝直径',1,1,1,1,1,60,'mm'),
  ('并线与绞线','股数',1,1,1,1,1,70,''),
  ('并线与绞线','绞向',1,1,1,1,1,80,''),
  ('并线与绞线','绞距',1,1,1,1,1,90,'mm'),
  ('直条矩形铜排','厚度',1,1,1,1,1,40,'mm'),
  ('直条矩形铜排','宽度',1,1,1,1,1,50,'mm'),
  ('直条矩形铜排','长度',1,1,1,1,1,60,'mm'),
  ('卷制矩形铜排','厚度',1,1,1,1,1,40,'mm'),
  ('卷制矩形铜排','宽度',1,1,1,1,1,50,'mm'),
  ('卷制矩形铜排','卷内径',0,0,1,1,0,60,'mm'),
  ('卷制矩形铜排','卷外径',0,0,1,1,0,70,'mm'),
  ('盘圆铜材','圆线直径',1,1,1,1,1,40,'mm'),
  ('异形铜排','图纸编号',1,1,1,1,1,40,''),
  ('异形铜排','图纸版本',1,1,1,1,1,50,''),
  ('异形铜排','客户图号',0,0,1,1,1,60,''),
  ('漆包圆线','材质',1,1,1,1,1,10,''),
  ('漆包圆线','执行标准',1,1,1,1,1,20,''),
  ('漆包圆线','圆线直径',1,1,1,1,1,30,'mm'),
  ('漆包圆线','线规AWG',0,0,1,1,1,40,''),
  ('漆包圆线','漆膜体系',1,1,1,1,1,50,''),
  ('漆包圆线','热级',1,1,1,1,1,60,''),
  ('漆包圆线','膜厚等级',1,1,1,1,1,70,''),
  ('漆包扁线','材质',1,1,1,1,1,10,''),
  ('漆包扁线','执行标准',1,1,1,1,1,20,''),
  ('漆包扁线','扁线宽度',1,1,1,1,1,30,'mm'),
  ('漆包扁线','扁线厚度',1,1,1,1,1,40,'mm'),
  ('漆包扁线','漆膜体系',1,1,1,1,1,50,''),
  ('漆包扁线','热级',1,1,1,1,1,60,''),
  ('漆包扁线','膜厚等级',1,1,1,1,1,70,''),
  ('特种绝缘导线','导体形状',1,1,1,1,1,10,''),
  ('特种绝缘导线','标称截面积',1,1,1,1,1,20,'mm²'),
  ('特种绝缘导线','绝缘层结构',1,1,1,1,1,30,''),
  ('特种绝缘导线','漆膜体系',1,1,1,1,1,40,''),
  ('特种绝缘导线','热级',1,1,1,1,1,50,''),
  ('特种绝缘导线','执行标准',1,1,1,1,1,60,''),
  ('工业阀门','阀门类型',1,1,1,1,1,10,''),
  ('工业阀门','公称通径',1,1,1,1,1,20,''),
  ('工业阀门','公称压力',1,1,1,1,1,30,''),
  ('工业阀门','连接形式',1,1,1,1,1,40,''),
  ('工业阀门','阀体材质',1,1,1,1,1,50,''),
  ('工业阀门','阀芯材质',0,1,1,1,1,60,''),
  ('工业阀门','密封材质',1,1,1,1,1,70,''),
  ('工业阀门','驱动方式',1,1,1,1,1,80,''),
  ('工业阀门','适用介质',1,1,1,1,1,90,''),
  ('工业阀门','温度下限',0,1,1,1,1,100,'℃'),
  ('工业阀门','温度上限',0,1,1,1,1,110,'℃'),
  ('矩形钕铁硼磁体','磁材牌号',1,1,1,1,1,10,''),
  ('矩形钕铁硼磁体','磁体长度',1,1,1,1,1,20,'mm'),
  ('矩形钕铁硼磁体','磁体宽度',1,1,1,1,1,30,'mm'),
  ('矩形钕铁硼磁体','磁体厚度',1,1,1,1,1,40,'mm'),
  ('矩形钕铁硼磁体','磁化方向',1,1,1,1,1,50,''),
  ('矩形钕铁硼磁体','涂层体系',1,1,1,1,1,60,''),
  ('矩形钕铁硼磁体','最高工作温度',0,1,1,1,1,70,'℃'),
  ('圆柱环形钕铁硼磁体','磁材牌号',1,1,1,1,1,10,''),
  ('圆柱环形钕铁硼磁体','磁体形状',1,1,1,1,1,20,''),
  ('圆柱环形钕铁硼磁体','磁体直径',1,1,1,1,1,30,'mm'),
  ('圆柱环形钕铁硼磁体','内径',0,1,1,1,1,40,'mm'),
  ('圆柱环形钕铁硼磁体','磁体厚度',1,1,1,1,1,50,'mm'),
  ('圆柱环形钕铁硼磁体','磁化方向',1,1,1,1,1,60,''),
  ('圆柱环形钕铁硼磁体','涂层体系',1,1,1,1,1,70,''),
  ('圆柱环形钕铁硼磁体','最高工作温度',0,1,1,1,1,80,'℃'),
  ('异形钕铁硼磁体','磁材牌号',1,1,1,1,1,10,''),
  ('异形钕铁硼磁体','图纸编号',1,1,1,1,1,20,''),
  ('异形钕铁硼磁体','图纸版本',1,1,1,1,1,30,''),
  ('异形钕铁硼磁体','磁化方向',1,1,1,1,1,40,''),
  ('异形钕铁硼磁体','涂层体系',1,1,1,1,1,50,''),
  ('异形钕铁硼磁体','最高工作温度',0,1,1,1,1,60,'℃'),
  ('磁性组件','图纸编号',1,1,1,1,1,10,''),
  ('磁性组件','图纸版本',1,1,1,1,1,20,''),
  ('磁性组件','客户图号',0,0,1,1,1,30,''),
  ('铜及铜合金焊丝','圆线直径',1,1,1,1,1,40,'mm'),
  ('铜及铜合金焊丝','焊材形态',1,1,1,1,1,50,''),
  ('铜及铜合金焊条','圆线直径',1,1,1,1,1,40,'mm'),
  ('铜及铜合金焊条','长度',1,1,1,1,1,50,'mm'),
  ('铜及铜合金焊条','焊材形态',1,1,1,1,1,60,''),
  ('再生铜阴极','纯度',1,1,1,1,1,20,''),
  ('再生铜阴极','质量等级',1,1,1,1,1,30,'');

INSERT INTO product_series_field(
  product_series_id, field_id, field_name, display_name, option_json,
  unit_category_id, unit_id, unit_name, required, sku_dimension,
  include_in_spec, searchable, order_visible, decimal_scale, sort_no, status
)
SELECT s.id, f.id, f.name, f.name, f.option_json,
       f.unit_category_id, u.id, coalesce(u.abbreviation, ''),
       m.required_flag, m.sku_dimension, m.include_in_spec,
       m.searchable, m.order_visible, 6, m.sort_no, 'ENABLED'
FROM tmp_product_series_field_map m
JOIN product_series s ON s.name = m.series_name
JOIN product_custom_field f ON f.name = m.field_name AND f.status='ENABLED'
LEFT JOIN unit u
  ON lower(regexp_replace(trim(u.abbreviation),'[[:space:]]+','')) =
     lower(regexp_replace(trim(nullif(m.unit_abbreviation, '')),'[[:space:]]+',''))
 AND u.status='ENABLED'
WHERE NOT EXISTS (
  SELECT 1 FROM product_series_field sf
  WHERE sf.product_series_id = s.id AND sf.field_id = f.id
);

-- V47 initialized identity revisions for the pre-existing series. New configurable
-- series introduced here receive their own field-based identity schema after all fields
-- exist, so supplier/packaging/quality data cannot accidentally enter the SKU signature.
INSERT INTO product_identity_schema_revision(
  product_series_id,version_no,change_reason,content_hash
)
SELECT s.id,1,'初始化复杂商品字段身份规则',
       sha2(concat('COMPLEX_PRODUCT_IDENTITY_V1|',s.id),256)
FROM tmp_product_series_definition d
JOIN product_series s ON s.name=d.series_name
WHERE NOT EXISTS (
  SELECT 1 FROM product_identity_schema_revision r
  WHERE r.product_series_id=s.id AND r.version_no=1
);

INSERT INTO product_identity_rule(
  schema_revision_id,product_series_id,schema_version,dimension_key,source_type,
  series_field_id,reference_type,required,sort_no,status
)
SELECT r.id,sf.product_series_id,1,concat('SF:',sf.id),'SERIES_FIELD',sf.id,
       case
         when sf.field_name in ('牌号','材质牌号','牌号/材质','磁材牌号') then 'GRADE'
         when sf.field_name in ('材料状态','状态') then 'TEMPER'
         when sf.field_name in ('执行标准','标准') then 'STANDARD'
         else ''
       end,
       sf.required,sf.sort_no,sf.status
FROM tmp_product_series_definition d
JOIN product_series s ON s.name=d.series_name
JOIN product_identity_schema_revision r
  ON r.product_series_id=s.id AND r.version_no=1
JOIN product_series_field sf
  ON sf.product_series_id=s.id AND sf.sku_dimension=1
WHERE NOT EXISTS (
  SELECT 1 FROM product_identity_rule ir
  WHERE ir.schema_revision_id=r.id AND ir.dimension_key=concat('SF:',sf.id)
);

-- Port count, order, connection and nominal dimensions determine fitting
-- interchangeability. A fitting head can be created as PENDING_REVIEW, but it cannot
-- be published or used in transactions until its required structured ports are saved.
INSERT INTO product_identity_rule(
  schema_revision_id,product_series_id,schema_version,dimension_key,source_type,
  series_field_id,reference_type,required,sort_no,status
)
SELECT r.id,s.id,1,'PORTS','STRUCTURED',0,'',1,900,'ENABLED'
FROM product_series s
JOIN product_identity_schema_revision r
  ON r.product_series_id=s.id AND r.version_no=1
WHERE s.name IN ('多端口铜管件','工业阀门')
  AND NOT EXISTS (
    SELECT 1 FROM product_identity_rule ir
    WHERE ir.schema_revision_id=r.id AND ir.dimension_key='PORTS'
  );

-- Multi-layer conductor make-up determines interchangeability for stranded/parallel and
-- special insulated conductors. Enamelled single-wire families remain fully identified by
-- their dimensional and insulation fields and therefore do not require a component tree.
INSERT INTO product_identity_rule(
  schema_revision_id,product_series_id,schema_version,dimension_key,source_type,
  series_field_id,reference_type,required,sort_no,status
)
SELECT r.id,s.id,1,'CONDUCTOR','STRUCTURED',0,'',1,910,'ENABLED'
FROM product_series s
JOIN product_identity_schema_revision r
  ON r.product_series_id=s.id AND r.version_no=1
WHERE s.name IN ('并线与绞线','特种绝缘导线')
  AND NOT EXISTS (
    SELECT 1 FROM product_identity_rule ir
    WHERE ir.schema_revision_id=r.id AND ir.dimension_key='CONDUCTOR'
  );

-- Inventory and operational quantity facts use one fixed precision end to end.
-- Amounts and unit prices retain their existing accounting precision.
ALTER TABLE inbound_item MODIFY quantity decimal(24,6) NOT NULL;
ALTER TABLE inbound_item MODIFY package_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;
ALTER TABLE inbound_order MODIFY total_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;
ALTER TABLE inbound_review_snapshot MODIFY total_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;
ALTER TABLE inbound_review_snapshot_item MODIFY quantity decimal(24,6) NOT NULL DEFAULT 0.000000;
ALTER TABLE inbound_review_snapshot_item MODIFY package_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;
ALTER TABLE inventory_batch
  MODIFY quantity_in decimal(24,6) NOT NULL,
  MODIFY quantity_available decimal(24,6) NOT NULL,
  MODIFY package_quantity_in decimal(24,6) NOT NULL DEFAULT 0.000000,
  MODIFY package_quantity_available decimal(24,6) NOT NULL DEFAULT 0.000000;
ALTER TABLE inventory_movement MODIFY quantity decimal(24,6) NOT NULL;
ALTER TABLE outbound_allocation MODIFY quantity decimal(24,6) NOT NULL;
ALTER TABLE outbound_item MODIFY quantity decimal(24,6) NOT NULL;
ALTER TABLE outbound_item MODIFY package_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;
ALTER TABLE outbound_order MODIFY total_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;
ALTER TABLE outbound_order MODIFY package_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;
ALTER TABLE platform_listing MODIFY quantity decimal(24,6) NOT NULL;
ALTER TABLE purchase_order
  MODIFY total_quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  MODIFY received_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;
ALTER TABLE purchase_order_item
  MODIFY quantity decimal(24,6) NOT NULL,
  MODIFY received_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;
ALTER TABLE sales_order_item MODIFY quantity decimal(24,6) NOT NULL;
ALTER TABLE stock_balance MODIFY quantity_available decimal(24,6) NOT NULL DEFAULT 0.000000;

ALTER TABLE inventory_transfer_order MODIFY total_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;
ALTER TABLE inventory_transfer_item MODIFY quantity decimal(24,6) NOT NULL;
ALTER TABLE inventory_stocktake_order
  MODIFY total_gain_quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  MODIFY total_loss_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;
ALTER TABLE inventory_stocktake_item
  MODIFY system_quantity decimal(24,6) NOT NULL,
  MODIFY counted_quantity decimal(24,6) NOT NULL,
  MODIFY difference_quantity decimal(24,6) NOT NULL;
ALTER TABLE purchase_return_order MODIFY total_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;
ALTER TABLE purchase_return_item MODIFY quantity decimal(24,6) NOT NULL;
ALTER TABLE inventory_cost_adjustment_item MODIFY quantity_snapshot decimal(24,6) NOT NULL;
ALTER TABLE inventory_opening_cost_alignment MODIFY quantity_snapshot decimal(24,6) NOT NULL;
ALTER TABLE business_posting_entry MODIFY quantity decimal(24,6) NOT NULL DEFAULT 0.000000;
ALTER TABLE landed_cost_allocation_item
  MODIFY original_quantity decimal(24,6) NOT NULL,
  MODIFY remaining_quantity_snapshot decimal(24,6) NOT NULL DEFAULT 0.000000;
ALTER TABLE landed_cost_allocation_batch MODIFY quantity_snapshot decimal(24,6) NOT NULL;
ALTER TABLE product_series_package_unit
  MODIFY net_weight decimal(24,6) DEFAULT NULL,
  MODIFY gross_weight decimal(24,6) DEFAULT NULL;

-- Final postcondition: do not report V49 as applied unless every configured
-- series, template, unit token, identity rule and field binding is complete.
CREATE TEMPORARY TABLE tmp_v49_completion_assertion (
  must_be_non_null tinyint NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_v49_completion_assertion(must_be_non_null)
SELECT NULL
FROM tmp_product_series_definition d
LEFT JOIN product_series s
  ON s.name=d.series_name AND s.status='ENABLED'
LEFT JOIN product_series_packaging_binding b
  ON b.product_series_id=s.id AND b.status='ENABLED'
LEFT JOIN packaging_template t
  ON t.id=b.template_id
 AND t.code=d.packaging_template_code
 AND t.status='ENABLED'
LEFT JOIN packaging_template_revision tr
  ON tr.id=t.current_revision_id
 AND tr.template_id=t.id
 AND tr.revision_status='PUBLISHED'
LEFT JOIN product_identity_schema_revision ir
  ON ir.product_series_id=s.id AND ir.version_no=1
GROUP BY d.series_name
HAVING count(distinct s.id)<>1
    OR count(distinct b.product_series_id)<>1
    OR count(distinct t.id)<>1
    OR count(distinct tr.id)<>1
    OR count(distinct ir.id)<>1
LIMIT 1;

INSERT INTO tmp_v49_completion_assertion(must_be_non_null)
SELECT NULL
FROM tmp_product_series_definition d
JOIN product_series s ON s.name=d.series_name
CROSS JOIN (
  SELECT 'PURCHASE' purpose
  UNION ALL SELECT 'SALES'
  UNION ALL SELECT 'INVENTORY'
  UNION ALL SELECT 'PACKAGING'
  UNION ALL SELECT 'PRICE'
) purpose
LEFT JOIN product_series_allowed_unit a
  ON a.product_series_id=s.id
 AND a.purpose=purpose.purpose
 AND a.status='ENABLED'
LEFT JOIN unit u ON u.id=a.unit_id AND u.status='ENABLED'
GROUP BY d.series_name,purpose.purpose
HAVING count(distinct a.id)=0
    OR count(distinct u.id)=0
    OR sum(case when a.is_default=1 then 1 else 0 end)<>1
LIMIT 1;

INSERT INTO tmp_v49_completion_assertion(must_be_non_null)
SELECT NULL
FROM tmp_product_series_field_map m
LEFT JOIN product_series s ON s.name=m.series_name
LEFT JOIN product_custom_field f
  ON f.name=m.field_name AND f.status='ENABLED'
LEFT JOIN product_series_field sf
  ON sf.product_series_id=s.id
 AND sf.field_id=f.id
 AND sf.status='ENABLED'
LEFT JOIN unit u
  ON lower(regexp_replace(trim(u.abbreviation),'[[:space:]]+',''))=
     lower(regexp_replace(trim(nullif(m.unit_abbreviation,'')),'[[:space:]]+',''))
 AND u.status='ENABLED'
WHERE sf.id is null
   OR sf.required<>m.required_flag
   OR sf.sku_dimension<>m.sku_dimension
   OR sf.include_in_spec<>m.include_in_spec
   OR sf.searchable<>m.searchable
   OR sf.order_visible<>m.order_visible
   OR (m.unit_abbreviation<>'' AND (
        u.id is null OR sf.unit_id<>u.id OR sf.unit_category_id<>u.unit_category_id
      ))
LIMIT 1;

INSERT INTO tmp_v49_completion_assertion(must_be_non_null)
SELECT NULL
FROM (
  SELECT id unit_id,name source_text FROM unit
  UNION ALL
  SELECT id,abbreviation FROM unit WHERE trim(abbreviation)<>''
  UNION ALL
  SELECT unit_id,alias_name FROM unit_alias
) source
LEFT JOIN unit_lookup_token token
  ON token.unit_id=source.unit_id
 AND token.normalized_token=
     lower(regexp_replace(trim(source.source_text),'[[:space:]]+',''))
WHERE token.normalized_token is null
LIMIT 1;

INSERT INTO tmp_v49_completion_assertion(must_be_non_null)
SELECT NULL
FROM product_series s
JOIN tmp_product_series_definition d ON d.series_name=s.name
LEFT JOIN product_identity_rule r
  ON r.product_series_id=s.id
 AND r.schema_version=1
 AND r.status='ENABLED'
GROUP BY s.id
HAVING count(r.id)=0
LIMIT 1;

DROP TEMPORARY TABLE tmp_v49_completion_assertion;
DROP TEMPORARY TABLE tmp_product_series_field_map;
DROP TEMPORARY TABLE tmp_product_series_definition;
