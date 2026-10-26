ALTER TABLE sales_order
  ADD COLUMN source_type varchar(32) NOT NULL DEFAULT 'FORMAL' AFTER delivery_plan_date,
  ADD KEY idx_sales_order_source (source_type,status,business_date,id);

CREATE TABLE direct_trade_number_sequence (
  business_date date NOT NULL,
  next_value bigint unsigned NOT NULL DEFAULT 1,
  PRIMARY KEY (business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='直运购销单按业务日期连续编号';

CREATE TABLE direct_trade_order (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  trade_no varchar(64) NOT NULL,
  customer_id bigint unsigned NOT NULL,
  supplier_id bigint unsigned NOT NULL,
  address_id bigint unsigned NOT NULL,
  address_snapshot varchar(1000) NOT NULL,
  business_date date NOT NULL,
  expected_delivery_date date DEFAULT NULL,
  sales_order_id bigint unsigned NOT NULL,
  purchase_order_id bigint unsigned NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'DRAFT'
    COMMENT 'DRAFT, CONFIRMED, PARTIAL_DELIVERED, DELIVERED, CANCELLED',
  total_quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  base_unit_summary varchar(500) NOT NULL DEFAULT '',
  sale_order_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  purchase_order_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  sale_receivable_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  purchase_payable_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  gross_profit_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  sale_price_includes_tax tinyint unsigned NOT NULL DEFAULT 1,
  sale_tax_rate_summary varchar(255) NOT NULL DEFAULT '',
  sale_tax_rule_snapshot longtext,
  purchase_price_includes_tax tinyint unsigned NOT NULL DEFAULT 1,
  purchase_tax_rate_summary varchar(255) NOT NULL DEFAULT '',
  purchase_tax_rule_snapshot longtext,
  memo varchar(500) NOT NULL DEFAULT '',
  cancel_reason varchar(500) NOT NULL DEFAULT '',
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  confirmed_by bigint unsigned NOT NULL DEFAULT 0,
  confirmed_by_name varchar(128) NOT NULL DEFAULT '',
  confirmed_at timestamp NULL DEFAULT NULL,
  cancelled_by bigint unsigned NOT NULL DEFAULT 0,
  cancelled_by_name varchar(128) NOT NULL DEFAULT '',
  cancelled_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_direct_trade_no (trade_no),
  UNIQUE KEY uk_direct_trade_sales_order (sales_order_id),
  UNIQUE KEY uk_direct_trade_purchase_order (purchase_order_id),
  KEY idx_direct_trade_customer (customer_id,status,business_date,id),
  KEY idx_direct_trade_supplier (supplier_id,status,business_date,id),
  KEY idx_direct_trade_status (status,expected_delivery_date,id),
  CONSTRAINT fk_direct_trade_customer FOREIGN KEY (customer_id) REFERENCES partner (id),
  CONSTRAINT fk_direct_trade_supplier FOREIGN KEY (supplier_id) REFERENCES partner (id),
  CONSTRAINT fk_direct_trade_address FOREIGN KEY (address_id) REFERENCES partner_address (id),
  CONSTRAINT fk_direct_trade_sales_order FOREIGN KEY (sales_order_id) REFERENCES sales_order (id),
  CONSTRAINT fk_direct_trade_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_order (id),
  CONSTRAINT chk_direct_trade_status CHECK
    (status IN ('DRAFT','CONFIRMED','PARTIAL_DELIVERED','DELIVERED','CANCELLED')),
  CONSTRAINT chk_direct_trade_partner CHECK (customer_id <> supplier_id),
  CONSTRAINT chk_direct_trade_amount CHECK (
    sale_order_amount >= 0 AND purchase_order_amount >= 0
    AND sale_receivable_amount >= 0 AND purchase_payable_amount >= 0
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='供应商直发客户的一次录入购销聚合；不形成自有库存';

CREATE TABLE direct_trade_item (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  direct_trade_id bigint unsigned NOT NULL,
  line_no int unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  product_sku_snapshot varchar(128) NOT NULL DEFAULT '',
  product_name_snapshot varchar(255) NOT NULL DEFAULT '',
  product_spec_snapshot varchar(1000) NOT NULL DEFAULT '',
  sales_order_item_id bigint unsigned NOT NULL,
  purchase_order_item_id bigint unsigned NOT NULL,
  requested_quantity decimal(24,6) NOT NULL,
  requested_unit varchar(64) NOT NULL DEFAULT '',
  quantity decimal(24,6) NOT NULL,
  base_unit varchar(64) NOT NULL DEFAULT '',
  price_unit varchar(64) NOT NULL DEFAULT '',
  pricing_quantity decimal(24,6) NOT NULL,
  package_unit varchar(64) NOT NULL DEFAULT '',
  packaging_plan_json longtext NOT NULL,
  packaging_effective_revision_id bigint unsigned NOT NULL DEFAULT 0,
  packaging_snapshot_hash char(64) NOT NULL DEFAULT '',
  sale_unit_price decimal(24,8) NOT NULL,
  purchase_unit_price decimal(24,8) NOT NULL,
  sale_amount decimal(18,2) NOT NULL,
  purchase_amount decimal(18,2) NOT NULL,
  delivered_quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  returned_quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  recognized_sale_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  recognized_purchase_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_direct_trade_item_line (direct_trade_id,line_no),
  UNIQUE KEY uk_direct_trade_sales_item (sales_order_item_id),
  UNIQUE KEY uk_direct_trade_purchase_item (purchase_order_item_id),
  KEY idx_direct_trade_item_product (product_id,direct_trade_id),
  CONSTRAINT fk_direct_trade_item_order FOREIGN KEY (direct_trade_id) REFERENCES direct_trade_order (id),
  CONSTRAINT fk_direct_trade_item_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT fk_direct_trade_item_sales FOREIGN KEY (sales_order_item_id) REFERENCES sales_order_item (id),
  CONSTRAINT fk_direct_trade_item_purchase FOREIGN KEY (purchase_order_item_id) REFERENCES purchase_order_item (id),
  CONSTRAINT chk_direct_trade_item_quantity CHECK (
    requested_quantity > 0 AND quantity > 0 AND pricing_quantity > 0
    AND delivered_quantity >= 0 AND returned_quantity >= 0
    AND returned_quantity <= delivered_quantity AND delivered_quantity <= quantity
  ),
  CONSTRAINT chk_direct_trade_item_amount CHECK (
    sale_unit_price >= 0 AND purchase_unit_price >= 0
    AND sale_amount >= 0 AND purchase_amount >= 0
    AND recognized_sale_amount >= 0 AND recognized_purchase_amount >= 0
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='直运购销商品、包装、购销价格及累计交付快照';

CREATE TABLE direct_trade_delivery (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  delivery_no varchar(64) NOT NULL,
  direct_trade_id bigint unsigned NOT NULL,
  sequence_no int unsigned NOT NULL,
  business_date date NOT NULL,
  carrier_name varchar(128) NOT NULL DEFAULT '',
  tracking_no varchar(128) NOT NULL DEFAULT '',
  handler_name varchar(128) NOT NULL DEFAULT '',
  status varchar(24) NOT NULL DEFAULT 'DISPATCHED'
    COMMENT 'DISPATCHED, PARTIAL_ACCEPTED, ACCEPTED',
  dispatched_quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  accepted_quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  sale_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  purchase_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  recipient_name varchar(128) NOT NULL DEFAULT '',
  difference_reason varchar(500) NOT NULL DEFAULT '',
  dispatched_by bigint unsigned NOT NULL DEFAULT 0,
  dispatched_by_name varchar(128) NOT NULL DEFAULT '',
  accepted_by bigint unsigned NOT NULL DEFAULT 0,
  accepted_by_name varchar(128) NOT NULL DEFAULT '',
  dispatched_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  accepted_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_direct_trade_delivery_no (delivery_no),
  UNIQUE KEY uk_direct_trade_delivery_sequence (direct_trade_id,sequence_no),
  KEY idx_direct_trade_delivery_status (direct_trade_id,status,id),
  KEY idx_direct_trade_delivery_tracking (tracking_no),
  CONSTRAINT fk_direct_trade_delivery_order FOREIGN KEY (direct_trade_id) REFERENCES direct_trade_order (id),
  CONSTRAINT chk_direct_trade_delivery_status CHECK
    (status IN ('DISPATCHED','PARTIAL_ACCEPTED','ACCEPTED')),
  CONSTRAINT chk_direct_trade_delivery_quantity CHECK (
    dispatched_quantity > 0 AND accepted_quantity >= 0 AND accepted_quantity <= dispatched_quantity
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='供应商直发批次；确认客户实收后才形成应收应付';

CREATE TABLE direct_trade_delivery_item (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  delivery_id bigint unsigned NOT NULL,
  direct_trade_item_id bigint unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  dispatched_quantity decimal(24,6) NOT NULL,
  accepted_quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  sale_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  purchase_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_direct_trade_delivery_item (delivery_id,direct_trade_item_id),
  KEY idx_direct_trade_delivery_item_trade (direct_trade_item_id,delivery_id),
  CONSTRAINT fk_direct_trade_delivery_item_delivery FOREIGN KEY (delivery_id) REFERENCES direct_trade_delivery (id),
  CONSTRAINT fk_direct_trade_delivery_item_trade FOREIGN KEY (direct_trade_item_id) REFERENCES direct_trade_item (id),
  CONSTRAINT fk_direct_trade_delivery_item_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT chk_direct_trade_delivery_item_quantity CHECK (
    dispatched_quantity > 0 AND accepted_quantity >= 0 AND accepted_quantity <= dispatched_quantity
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='直运发出及客户实收商品明细';

CREATE TABLE direct_trade_return (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  return_no varchar(64) NOT NULL,
  direct_trade_id bigint unsigned NOT NULL,
  business_date date NOT NULL,
  total_quantity decimal(24,6) NOT NULL,
  sale_reduction_amount decimal(18,2) NOT NULL,
  purchase_reduction_amount decimal(18,2) NOT NULL,
  reason varchar(500) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'CONFIRMED' COMMENT 'CONFIRMED, REVERSED',
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_by_name varchar(128) NOT NULL DEFAULT '',
  confirmed_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reversal_reason varchar(500) NOT NULL DEFAULT '',
  reversed_by bigint unsigned NOT NULL DEFAULT 0,
  reversed_by_name varchar(128) NOT NULL DEFAULT '',
  reversed_at timestamp NULL DEFAULT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_direct_trade_return_no (return_no),
  KEY idx_direct_trade_return_trade (direct_trade_id,business_date,id),
  CONSTRAINT fk_direct_trade_return_order FOREIGN KEY (direct_trade_id) REFERENCES direct_trade_order (id),
  CONSTRAINT chk_direct_trade_return_status CHECK (status IN ('CONFIRMED','REVERSED')),
  CONSTRAINT chk_direct_trade_return_amount CHECK (
    total_quantity > 0 AND sale_reduction_amount >= 0 AND purchase_reduction_amount >= 0
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='客户退回并由供应商承接的直运退货；不进入自有库存';

CREATE TABLE direct_trade_return_item (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  return_id bigint unsigned NOT NULL,
  direct_trade_item_id bigint unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  quantity decimal(24,6) NOT NULL,
  sale_reduction_amount decimal(18,2) NOT NULL,
  purchase_reduction_amount decimal(18,2) NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_direct_trade_return_item (return_id,direct_trade_item_id),
  KEY idx_direct_trade_return_item_trade (direct_trade_item_id,return_id),
  CONSTRAINT fk_direct_trade_return_item_return FOREIGN KEY (return_id) REFERENCES direct_trade_return (id),
  CONSTRAINT fk_direct_trade_return_item_trade FOREIGN KEY (direct_trade_item_id) REFERENCES direct_trade_item (id),
  CONSTRAINT fk_direct_trade_return_item_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT chk_direct_trade_return_item_quantity CHECK (quantity > 0),
  CONSTRAINT chk_direct_trade_return_item_amount CHECK (
    sale_reduction_amount >= 0 AND purchase_reduction_amount >= 0
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='直运退货商品及购销金额冲减快照';

CREATE TABLE direct_trade_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  direct_trade_id bigint unsigned NOT NULL,
  event_type varchar(32) NOT NULL,
  ref_type varchar(32) NOT NULL DEFAULT '',
  ref_id bigint unsigned NOT NULL DEFAULT 0,
  from_status varchar(24) NOT NULL DEFAULT '',
  to_status varchar(24) NOT NULL DEFAULT '',
  reason varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_direct_trade_event (direct_trade_id,id),
  CONSTRAINT fk_direct_trade_event_order FOREIGN KEY (direct_trade_id) REFERENCES direct_trade_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='直运确认、发出、实收、差异、退货和作废的追加式审计事件';

INSERT INTO business_posting_rule_version
  (rule_code,rule_name,source_type,posting_action,version_no,effective_from,rule_json,status,published_at)
VALUES
  ('DIRECT_TRADE_DELIVERY','直运客户实收形成购销结算','DIRECT_TRADE','DELIVERY_POST',1,'2026-01-01',
   '{"facts":["RECEIVABLE","PAYABLE"],"trigger":"CUSTOMER_ACCEPTANCE","inventoryImpact":false}',
   'ENABLED',CURRENT_TIMESTAMP),
  ('DIRECT_TRADE_RETURN','直运退货冲减购销结算','DIRECT_TRADE','RETURN_POST',1,'2026-01-01',
   '{"facts":["RECEIVABLE","PAYABLE"],"trigger":"CUSTOMER_RETURN_SUPPLIER_ACCEPTANCE","inventoryImpact":false}',
   'ENABLED',CURRENT_TIMESTAMP),
  ('DIRECT_TRADE_RETURN_REVERSE','直运退货冲正恢复购销结算','DIRECT_TRADE','RETURN_REVERSE',1,'2026-01-01',
   '{"facts":["RECEIVABLE","PAYABLE"],"reverseOf":"DIRECT_TRADE_RETURN","inventoryImpact":false}',
   'ENABLED',CURRENT_TIMESTAMP);

INSERT INTO accounting_rule_version
  (entity_id,rule_code,rule_name,source_type,posting_action,fact_type,fact_direction,
   debit_account_id,credit_account_id,version_no,effective_from,status,change_reason,published_at)
SELECT e.id,r.rule_code,r.rule_name,'DIRECT_TRADE',r.posting_action,r.fact_type,r.fact_direction,
       da.id,ca.id,1,'2026-01-01','ENABLED','直运不经过自有库存；客户实收时直接确认收入、成本及往来',CURRENT_TIMESTAMP
FROM accounting_entity e CROSS JOIN (
  SELECT 'DIRECT_TRADE_AR' rule_code,'直运实收形成应收及收入' rule_name,
         'DELIVERY_POST' posting_action,'RECEIVABLE' fact_type,'INCREASE' fact_direction,
         '1122' debit_code,'5001' credit_code UNION ALL
  SELECT 'DIRECT_TRADE_AP','直运实收形成成本及应付',
         'DELIVERY_POST','PAYABLE','INCREASE','5401','2202' UNION ALL
  SELECT 'DIRECT_TRADE_RETURN_AR','直运退货冲减应收及收入',
         'RETURN_POST','RECEIVABLE','DECREASE','5001','1122' UNION ALL
  SELECT 'DIRECT_TRADE_RETURN_AP','直运退货冲减成本及应付',
         'RETURN_POST','PAYABLE','DECREASE','2202','5401' UNION ALL
  SELECT 'DIRECT_TRADE_RETURN_REVERSE_AR','直运退货冲正恢复应收及收入',
         'RETURN_REVERSE','RECEIVABLE','INCREASE','1122','5001' UNION ALL
  SELECT 'DIRECT_TRADE_RETURN_REVERSE_AP','直运退货冲正恢复成本及应付',
         'RETURN_REVERSE','PAYABLE','INCREASE','5401','2202'
) r
JOIN accounting_account da ON da.entity_id=e.id AND da.account_code=r.debit_code
JOIN accounting_account ca ON ca.entity_id=e.id AND ca.account_code=r.credit_code
WHERE e.entity_code='DEFAULT';

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('direct.trade.view','direct_trade','直运购销','PAGE','查看直运购销','查看供应商直发客户的购销、交付、往来和审计事实',124,'ENABLED'),
  ('direct.trade.manage','direct_trade','直运购销','ACTION','办理直运购销','一次录入直运购销并办理发出、实收、差异和退货',125,'ENABLED'),
  ('direct.trade.confirm','direct_trade','直运购销','ACTION','确认直运购销','确认直运采购与销售承诺或作废未履约直运单',126,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER','MANAGER')
  AND p.code IN ('direct.trade.view','direct.trade.manage','direct.trade.confirm');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE (r.code IN ('SALES','PURCHASE') AND p.code IN ('direct.trade.view','direct.trade.manage'))
   OR (r.code='FINANCE' AND p.code='direct.trade.view');
