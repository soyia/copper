ALTER TABLE treasury_document
  DROP CHECK chk_treasury_document_type,
  ADD CONSTRAINT chk_treasury_document_type CHECK (
    document_type IN ('CORPORATE_EXPENSE','OTHER_EXPENSE','OTHER_INCOME','OTHER_RECEIVABLE','OTHER_PAYABLE','PURCHASE_LANDED_COST')
  );

-- ERP-06 的逆序冲回依赖确认时点。原库存单据为秒级时间，同一秒内连续操作会失去先后顺序，
-- 因此统一提升为微秒精度；服务写入同时使用 NOW(6)。
ALTER TABLE inventory_transfer_order
  MODIFY COLUMN dispatched_at timestamp(6) NULL DEFAULT NULL,
  MODIFY COLUMN received_at timestamp(6) NULL DEFAULT NULL;

ALTER TABLE purchase_return_order
  MODIFY COLUMN confirmed_at timestamp(6) NULL DEFAULT NULL;

ALTER TABLE inventory_cost_adjustment
  MODIFY COLUMN confirmed_at timestamp(6) NULL DEFAULT NULL;

CREATE TABLE landed_cost_allocation (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  allocation_no varchar(64) NOT NULL,
  treasury_document_id bigint unsigned NOT NULL,
  business_date date NOT NULL,
  allocation_method varchar(16) NOT NULL COMMENT 'QUANTITY, WEIGHT, AMOUNT, MANUAL',
  fee_amount decimal(18,2) NOT NULL,
  allocated_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  capitalized_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  expensed_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, CONFIRMED, CANCELLED, REVERSED',
  memo varchar(500) NOT NULL DEFAULT '',
  cancel_reason varchar(500) NOT NULL DEFAULT '',
  reversal_reason varchar(500) NOT NULL DEFAULT '',
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  confirmed_by bigint unsigned NOT NULL DEFAULT 0,
  confirmed_by_name varchar(128) NOT NULL DEFAULT '',
  confirmed_at timestamp(6) NULL DEFAULT NULL,
  cancelled_at timestamp NULL DEFAULT NULL,
  reversed_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_landed_cost_allocation_no (allocation_no),
  KEY idx_landed_cost_treasury_document (treasury_document_id,status,id),
  KEY idx_landed_cost_status_date (status,business_date,id),
  CONSTRAINT chk_landed_cost_method CHECK (allocation_method IN ('QUANTITY','WEIGHT','AMOUNT','MANUAL')),
  CONSTRAINT chk_landed_cost_amount CHECK (
    fee_amount > 0 AND allocated_amount >= 0 AND capitalized_amount >= 0 AND expensed_amount >= 0
  ),
  CONSTRAINT chk_landed_cost_status CHECK (status IN ('DRAFT','CONFIRMED','CANCELLED','REVERSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP-06 采购附加费用到岸成本分摊单';

CREATE TABLE landed_cost_allocation_item (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  allocation_id bigint unsigned NOT NULL,
  inbound_id bigint unsigned NOT NULL,
  inbound_item_id bigint unsigned NOT NULL,
  original_batch_id bigint unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  warehouse_id bigint unsigned NOT NULL,
  original_quantity decimal(18,3) NOT NULL,
  base_unit varchar(64) NOT NULL DEFAULT '',
  basis_value decimal(18,6) NOT NULL,
  allocated_amount decimal(18,2) NOT NULL,
  landed_unit_amount decimal(18,6) NOT NULL,
  remaining_quantity_snapshot decimal(18,3) NOT NULL DEFAULT 0.000,
  capitalized_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  expensed_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_landed_cost_allocation_item (allocation_id,inbound_item_id),
  KEY idx_landed_cost_item_inbound (inbound_item_id,allocation_id),
  KEY idx_landed_cost_item_batch (original_batch_id,allocation_id),
  CONSTRAINT chk_landed_cost_item_amount CHECK (
    original_quantity > 0 AND basis_value >= 0 AND allocated_amount >= 0 AND landed_unit_amount >= 0
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='到岸费用在原入库明细上的受控分摊';

CREATE TABLE landed_cost_allocation_batch (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  allocation_id bigint unsigned NOT NULL,
  allocation_item_id bigint unsigned NOT NULL,
  batch_id bigint unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  warehouse_id bigint unsigned NOT NULL,
  quantity_snapshot decimal(18,3) NOT NULL,
  old_unit_cost decimal(18,4) NOT NULL,
  old_amount_cost decimal(18,2) NOT NULL,
  allocated_amount decimal(18,2) NOT NULL,
  new_unit_cost decimal(18,4) NOT NULL,
  new_amount_cost decimal(18,2) NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_landed_cost_batch (allocation_id,allocation_item_id,batch_id),
  KEY idx_landed_cost_batch_current (batch_id,allocation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='到岸费用确认时对当前批次成本的分配快照';

CREATE TABLE inventory_batch_landed_cost_component (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  allocation_id bigint unsigned NOT NULL,
  allocation_item_id bigint unsigned NOT NULL,
  batch_id bigint unsigned NOT NULL,
  landed_unit_amount decimal(18,6) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_batch_landed_component (allocation_item_id,batch_id),
  KEY idx_inventory_batch_landed_active (batch_id,status,allocation_id),
  CONSTRAINT chk_inventory_batch_landed_status CHECK (status IN ('ACTIVE','REVERSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批次继承的单项到岸费用单位组件';

CREATE TABLE landed_cost_allocation_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  allocation_id bigint unsigned NOT NULL,
  action varchar(32) NOT NULL,
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_landed_cost_event (allocation_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='到岸成本分摊不可变状态事件';

ALTER TABLE purchase_return_item
  ADD COLUMN landed_cost_amount decimal(18,2) NOT NULL DEFAULT 0.00 AFTER inventory_amount_cost;

INSERT INTO business_posting_rule_version
  (rule_code,rule_name,source_type,posting_action,version_no,effective_from,rule_json,status,published_at)
SELECT 'LANDED_COST_ALLOCATION_POST','采购附加费用资本化','LANDED_COST_ALLOCATION','POST',1,'2026-01-01',
       '{"facts":["LANDED_COST_CAPITALIZED"],"trigger":"CONFIRM","taxImpact":false}',
       'ENABLED',CURRENT_TIMESTAMP
WHERE NOT EXISTS (
  SELECT 1 FROM business_posting_rule_version
  WHERE rule_code='LANDED_COST_ALLOCATION_POST' AND version_no=1
);

INSERT INTO business_posting_rule_version
  (rule_code,rule_name,source_type,posting_action,version_no,effective_from,rule_json,status,published_at)
SELECT 'LANDED_COST_ALLOCATION_ROLLBACK','采购附加费用按当前结存回退','LANDED_COST_ALLOCATION','ROLLBACK',1,'2026-01-01',
       '{"facts":["LANDED_COST_CAPITALIZED"],"reverseStrategy":"CURRENT_STOCK_ONLY","taxImpact":false}',
       'ENABLED',CURRENT_TIMESTAMP
WHERE NOT EXISTS (
  SELECT 1 FROM business_posting_rule_version
  WHERE rule_code='LANDED_COST_ALLOCATION_ROLLBACK' AND version_no=1
);

INSERT INTO accounting_rule_version
  (entity_id,rule_code,rule_name,source_type,posting_action,fact_type,fact_direction,
   debit_account_id,credit_account_id,version_no,effective_from,status,change_reason,published_at)
SELECT e.id,r.rule_code,r.rule_name,r.source_type,r.posting_action,r.fact_type,r.fact_direction,
       da.id,ca.id,1,'2026-01-01','ENABLED','ERP-06 到岸费用只按当前结存资本化；已耗用部分保留主营成本',CURRENT_TIMESTAMP
FROM accounting_entity e CROSS JOIN (
  SELECT 'LANDED_COST_CAPITALIZE' rule_code,'采购附加费用计入库存' rule_name,
         'LANDED_COST_ALLOCATION' source_type,'POST' posting_action,
         'LANDED_COST_CAPITALIZED' fact_type,'INCREASE' fact_direction,'1405' debit_code,'5401' credit_code UNION ALL
  SELECT 'LANDED_COST_ROLLBACK','采购附加费用退出当前库存',
         'LANDED_COST_ALLOCATION','ROLLBACK','LANDED_COST_CAPITALIZED','DECREASE','5401','1405' UNION ALL
  SELECT 'LANDED_COST_PURCHASE_RETURN','采购退货转出附加费用',
         'PURCHASE_RETURN','POST','LANDED_COST_RETURN_EXPENSE','INCREASE','5401','1405'
) r
JOIN accounting_account da ON da.entity_id=e.id AND da.account_code=r.debit_code
JOIN accounting_account ca ON ca.entity_id=e.id AND ca.account_code=r.credit_code
WHERE NOT EXISTS (
  SELECT 1 FROM accounting_rule_version x
  WHERE x.entity_id=e.id AND x.rule_code=r.rule_code AND x.version_no=1
);

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('landed.cost.view','inventory','采购库存','PAGE','查看采购费用分摊','查看采购附加费用、分摊依据、批次成本及来源凭证',116,'ENABLED'),
  ('landed.cost.manage','inventory','采购库存','ACTION','编制采购费用分摊','按数量、重量、金额或手工方式编制到岸成本分摊草稿',117,'ENABLED'),
  ('landed.cost.confirm','inventory','采购库存','ACTION','确认采购费用分摊','确认当前结存资本化并形成业务过账和会计凭证',118,'ENABLED'),
  ('landed.cost.reverse','inventory','采购库存','ACTION','冲正采购费用分摊','按当前结存回退附加费用并保留完整历史',119,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE (p.code='landed.cost.view' AND r.code IN ('ADMIN','OWNER','MANAGER','PURCHASE','FINANCE'))
   OR (p.code='landed.cost.manage' AND r.code IN ('ADMIN','OWNER','PURCHASE','FINANCE'))
   OR (p.code IN ('landed.cost.confirm','landed.cost.reverse') AND r.code IN ('ADMIN','OWNER','FINANCE'));
