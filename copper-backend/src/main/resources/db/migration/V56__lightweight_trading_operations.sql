CREATE TABLE inventory_non_trade_document (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  document_no varchar(64) NOT NULL,
  business_type varchar(32) NOT NULL COMMENT 'SAMPLE_OUT, INTERNAL_USE, NORMAL_LOSS, OTHER_OUT, OTHER_IN',
  direction varchar(8) NOT NULL COMMENT 'IN, OUT',
  business_date date NOT NULL,
  product_id bigint unsigned NOT NULL,
  warehouse_id bigint unsigned NOT NULL,
  batch_id bigint unsigned NOT NULL,
  quantity decimal(24,6) NOT NULL,
  base_unit varchar(64) NOT NULL DEFAULT '',
  unit_cost decimal(24,8) NOT NULL,
  amount_cost decimal(18,2) NOT NULL,
  quantity_before decimal(24,6) NOT NULL,
  amount_before decimal(18,2) NOT NULL,
  recipient varchar(128) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL,
  memo varchar(500) NOT NULL DEFAULT '',
  selected_package_instance_ids_json longtext,
  packaging_plan_json longtext,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, CONFIRMED, CANCELLED, REVERSED',
  cancel_reason varchar(500) NOT NULL DEFAULT '',
  reversal_reason varchar(500) NOT NULL DEFAULT '',
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  confirmed_by bigint unsigned NOT NULL DEFAULT 0,
  confirmed_by_name varchar(128) NOT NULL DEFAULT '',
  confirmed_at timestamp NULL DEFAULT NULL,
  cancelled_at timestamp NULL DEFAULT NULL,
  reversed_by bigint unsigned NOT NULL DEFAULT 0,
  reversed_by_name varchar(128) NOT NULL DEFAULT '',
  reversed_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_non_trade_document_no (document_no),
  KEY idx_inventory_non_trade_batch (batch_id,status,id),
  KEY idx_inventory_non_trade_product (product_id,warehouse_id,business_date,id),
  KEY idx_inventory_non_trade_status (status,business_date,id),
  CONSTRAINT chk_inventory_non_trade_quantity CHECK (quantity > 0),
  CONSTRAINT chk_inventory_non_trade_direction CHECK (direction IN ('IN','OUT')),
  CONSTRAINT chk_inventory_non_trade_type CHECK (
    (business_type='OTHER_IN' AND direction='IN')
    OR (business_type IN ('SAMPLE_OUT','INTERNAL_USE','NORMAL_LOSS','OTHER_OUT') AND direction='OUT')
  ),
  CONSTRAINT chk_inventory_non_trade_status CHECK (status IN ('DRAFT','CONFIRMED','CANCELLED','REVERSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='非购销库存事实；送样、领用、损耗和其他出入库均保留来源、成本与冲正';

CREATE TABLE purchase_quote_option (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  purchase_order_id bigint unsigned NOT NULL,
  supplier_id bigint unsigned NOT NULL,
  supplier_name_snapshot varchar(255) NOT NULL,
  quote_date date NOT NULL,
  price_includes_tax tinyint unsigned NOT NULL DEFAULT 1,
  goods_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  freight_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  total_amount decimal(18,2) NOT NULL,
  lead_time_days int unsigned NOT NULL DEFAULT 0,
  settlement_terms varchar(255) NOT NULL DEFAULT '',
  source_note varchar(500) NOT NULL DEFAULT '',
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, SELECTED, VOID',
  selected_by bigint unsigned NOT NULL DEFAULT 0,
  selected_by_name varchar(128) NOT NULL DEFAULT '',
  selected_at timestamp NULL DEFAULT NULL,
  void_reason varchar(500) NOT NULL DEFAULT '',
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_purchase_quote_order (purchase_order_id,status,quote_date,id),
  KEY idx_purchase_quote_supplier (supplier_id,quote_date,id),
  CONSTRAINT chk_purchase_quote_amount CHECK (
    goods_amount >= 0 AND freight_amount >= 0 AND total_amount = goods_amount + freight_amount
  ),
  CONSTRAINT chk_purchase_quote_status CHECK (status IN ('ACTIVE','SELECTED','VOID'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='采购草稿旁的轻量供应商报价比较；不引入采购申请和复杂询价流程';

CREATE TABLE purchase_quote_option_item (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  quote_option_id bigint unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  product_sku_snapshot varchar(128) NOT NULL DEFAULT '',
  product_name_snapshot varchar(255) NOT NULL DEFAULT '',
  requested_quantity decimal(24,6) NOT NULL,
  requested_unit varchar(64) NOT NULL DEFAULT '',
  quoted_unit_price decimal(24,8) NOT NULL,
  amount decimal(18,2) NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_purchase_quote_item_option (quote_option_id,id),
  KEY idx_purchase_quote_item_product (product_id,quote_option_id),
  CONSTRAINT chk_purchase_quote_item_quantity CHECK (requested_quantity > 0),
  CONSTRAINT chk_purchase_quote_item_amount CHECK (quoted_unit_price >= 0 AND amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='轻量采购报价商品明细';

CREATE TABLE sales_cost_allocation (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  treasury_document_id bigint unsigned NOT NULL,
  source_type varchar(16) NOT NULL COMMENT 'SALES_ORDER, OUTBOUND',
  source_id bigint unsigned NOT NULL,
  amount decimal(18,2) NOT NULL,
  reason varchar(500) NOT NULL DEFAULT '',
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, REVERSED',
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  reversed_by bigint unsigned NOT NULL DEFAULT 0,
  reversed_by_name varchar(128) NOT NULL DEFAULT '',
  reversal_reason varchar(500) NOT NULL DEFAULT '',
  reversed_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_sales_cost_document_source (treasury_document_id,source_type,source_id,status),
  KEY idx_sales_cost_source (source_type,source_id,status,id),
  KEY idx_sales_cost_document (treasury_document_id,status,id),
  CONSTRAINT chk_sales_cost_amount CHECK (amount > 0),
  CONSTRAINT chk_sales_cost_source CHECK (source_type IN ('SALES_ORDER','OUTBOUND')),
  CONSTRAINT chk_sales_cost_status CHECK (status IN ('ACTIVE','REVERSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='将已有销售相关费用归集到订单或出库，不重复登记资金与会计事实';

CREATE TABLE outbound_delivery (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  outbound_id bigint unsigned NOT NULL,
  delivery_mode varchar(16) NOT NULL DEFAULT 'LOGISTICS' COMMENT 'SELF_PICKUP, OWN_DELIVERY, LOGISTICS',
  carrier_name varchar(128) NOT NULL DEFAULT '',
  tracking_no varchar(128) NOT NULL DEFAULT '',
  handler_name varchar(128) NOT NULL DEFAULT '',
  status varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, DISPATCHED, SIGNED, EXCEPTION',
  dispatched_at datetime NULL DEFAULT NULL,
  signed_at datetime NULL DEFAULT NULL,
  recipient_name varchar(128) NOT NULL DEFAULT '',
  exception_note varchar(500) NOT NULL DEFAULT '',
  memo varchar(500) NOT NULL DEFAULT '',
  version_no int unsigned NOT NULL DEFAULT 1,
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  updated_by bigint unsigned NOT NULL DEFAULT 0,
  updated_by_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbound_delivery_outbound (outbound_id),
  KEY idx_outbound_delivery_status (status,dispatched_at,id),
  KEY idx_outbound_delivery_tracking (tracking_no),
  CONSTRAINT chk_outbound_delivery_mode CHECK (delivery_mode IN ('SELF_PICKUP','OWN_DELIVERY','LOGISTICS')),
  CONSTRAINT chk_outbound_delivery_status CHECK (status IN ('PENDING','DISPATCHED','SIGNED','EXCEPTION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='嵌入销售出库的轻量交付与签收状态，不等同于运输管理系统';

CREATE TABLE outbound_delivery_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  delivery_id bigint unsigned NOT NULL,
  action varchar(32) NOT NULL,
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_outbound_delivery_event (delivery_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='交付发出、签收和异常的追加式审计事件';

CREATE TABLE external_share_link (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  share_no varchar(64) NOT NULL,
  token_hash char(64) NOT NULL,
  source_type varchar(16) NOT NULL COMMENT 'SALES_ORDER, PURCHASE_ORDER',
  source_id bigint unsigned NOT NULL,
  partner_id bigint unsigned NOT NULL,
  expires_at datetime NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, REVOKED, EXPIRED',
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  revoked_by bigint unsigned NOT NULL DEFAULT 0,
  revoked_by_name varchar(128) NOT NULL DEFAULT '',
  revoke_reason varchar(500) NOT NULL DEFAULT '',
  revoked_at timestamp NULL DEFAULT NULL,
  last_viewed_at timestamp NULL DEFAULT NULL,
  view_count bigint unsigned NOT NULL DEFAULT 0,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_external_share_no (share_no),
  UNIQUE KEY uk_external_share_token (token_hash),
  KEY idx_external_share_source (source_type,source_id,status,id),
  KEY idx_external_share_expiry (status,expires_at,id),
  CONSTRAINT chk_external_share_source CHECK (source_type IN ('SALES_ORDER','PURCHASE_ORDER')),
  CONSTRAINT chk_external_share_status CHECK (status IN ('ACTIVE','REVOKED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='按单据生成的只读、可到期、可撤销分享链接；不暴露内部库存成本和往来余额';

CREATE TABLE integration_access_key (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  key_no varchar(64) NOT NULL,
  key_name varchar(128) NOT NULL,
  token_hash char(64) NOT NULL,
  partner_id bigint unsigned NOT NULL DEFAULT 0,
  scopes varchar(500) NOT NULL DEFAULT 'order.read',
  expires_at datetime NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, REVOKED, EXPIRED',
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  revoked_by bigint unsigned NOT NULL DEFAULT 0,
  revoked_by_name varchar(128) NOT NULL DEFAULT '',
  revoke_reason varchar(500) NOT NULL DEFAULT '',
  revoked_at timestamp NULL DEFAULT NULL,
  last_used_at timestamp NULL DEFAULT NULL,
  use_count bigint unsigned NOT NULL DEFAULT 0,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_integration_access_key_no (key_no),
  UNIQUE KEY uk_integration_access_token (token_hash),
  KEY idx_integration_access_partner (partner_id,status,id),
  KEY idx_integration_access_expiry (status,expires_at,id),
  CONSTRAINT chk_integration_access_status CHECK (status IN ('ACTIVE','REVOKED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='最小开放 API 密钥；仅保存令牌摘要并限制往来单位、范围和有效期';

INSERT INTO business_posting_rule_version
  (rule_code,rule_name,source_type,posting_action,version_no,effective_from,rule_json,status,published_at)
VALUES
  ('INVENTORY_NON_TRADE_POST','非购销库存事实确认','INVENTORY_NON_TRADE','POST',1,'2026-01-01',
   '{"facts":["NON_TRADE_SAMPLE","NON_TRADE_INTERNAL_USE","NON_TRADE_NORMAL_LOSS","NON_TRADE_OTHER_OUT","NON_TRADE_OTHER_IN"],"trigger":"CONFIRM","negativeStock":false}',
   'ENABLED',CURRENT_TIMESTAMP),
  ('INVENTORY_NON_TRADE_REVERSE','非购销库存事实冲正','INVENTORY_NON_TRADE','REVERSE',1,'2026-01-01',
   '{"reverseOf":"INVENTORY_NON_TRADE_POST"}','ENABLED',CURRENT_TIMESTAMP);

INSERT INTO accounting_rule_version
  (entity_id,rule_code,rule_name,source_type,posting_action,fact_type,fact_direction,
   debit_account_id,credit_account_id,version_no,effective_from,status,change_reason,published_at)
SELECT e.id,r.rule_code,r.rule_name,'INVENTORY_NON_TRADE','POST',r.fact_type,r.fact_direction,
       da.id,ca.id,1,'2026-01-01','ENABLED','初始化轻量非购销库存会计规则；待处理损溢由会计复核后规范转销',CURRENT_TIMESTAMP
FROM accounting_entity e CROSS JOIN (
  SELECT 'NON_TRADE_SAMPLE_COST' rule_code,'送样结转销售费用' rule_name,
         'NON_TRADE_SAMPLE' fact_type,'DECREASE' fact_direction,'5601' debit_code,'1405' credit_code UNION ALL
  SELECT 'NON_TRADE_INTERNAL_USE_COST','内部领用结转管理费用',
         'NON_TRADE_INTERNAL_USE','DECREASE','5602','1405' UNION ALL
  SELECT 'NON_TRADE_NORMAL_LOSS_COST','正常损耗转待处理财产损溢',
         'NON_TRADE_NORMAL_LOSS','DECREASE','1901','1405' UNION ALL
  SELECT 'NON_TRADE_OTHER_OUT_COST','其他出库转待处理财产损溢',
         'NON_TRADE_OTHER_OUT','DECREASE','1901','1405' UNION ALL
  SELECT 'NON_TRADE_OTHER_IN_COST','其他入库转待处理财产损溢',
         'NON_TRADE_OTHER_IN','INCREASE','1405','1901'
) r
JOIN accounting_account da ON da.entity_id=e.id AND da.account_code=r.debit_code
JOIN accounting_account ca ON ca.entity_id=e.id AND ca.account_code=r.credit_code
WHERE e.entity_code='DEFAULT';

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('inventory.nontrade.view','inventory','库存管理','PAGE','查看其他出入库','查看送样、领用、损耗及其他出入库来源、成本和审计',116,'ENABLED'),
  ('inventory.nontrade.manage','inventory','库存管理','ACTION','办理其他出入库','从库存批次快速办理送样、领用、损耗及其他出入库并规范冲正',117,'ENABLED'),
  ('operations.insight.view','operations','经营工作台','PAGE','查看经营建议','查看补货、库龄、贡献毛利和老板经营摘要',118,'ENABLED'),
  ('sales.cost.manage','sales','销售管理','ACTION','归集销售相关费用','将已存在的费用源单分配到销售订单或出库，不重复登记资金',119,'ENABLED'),
  ('purchase.quote.manage','purchase','采购管理','ACTION','比较供应商报价','在采购草稿旁登记和选择供应商报价',120,'ENABLED'),
  ('outbound.delivery.manage','outbound','销售出库','ACTION','维护交付签收','记录出库后的发出、签收和异常，不直接改变应收',121,'ENABLED'),
  ('external.share.manage','integration','对外协同','ACTION','管理只读分享','为采购或销售单据生成可到期、可撤销的只读分享链接',122,'ENABLED'),
  ('integration.key.manage','integration','对外协同','ACTION','管理开放 API 密钥','创建、查看和撤销受往来单位与范围限制的开放 API 密钥',123,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER')
  AND p.code IN (
    'inventory.nontrade.view','inventory.nontrade.manage','operations.insight.view',
    'sales.cost.manage','purchase.quote.manage','outbound.delivery.manage',
    'external.share.manage','integration.key.manage'
  );

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE (r.code='WAREHOUSE' AND p.code IN ('inventory.nontrade.view','inventory.nontrade.manage','outbound.delivery.manage'))
   OR (r.code='SALES' AND p.code IN ('outbound.delivery.manage','external.share.manage'))
   OR (r.code='PURCHASE' AND p.code IN ('purchase.quote.manage','external.share.manage'))
   OR (r.code='FINANCE' AND p.code IN ('inventory.nontrade.view','operations.insight.view','sales.cost.manage'))
   OR (r.code='MANAGER' AND p.code IN (
        'inventory.nontrade.view','inventory.nontrade.manage','operations.insight.view',
        'sales.cost.manage','purchase.quote.manage','outbound.delivery.manage','external.share.manage'
      ));
