ALTER TABLE purchase_order
  ADD COLUMN current_revision_no int unsigned NOT NULL DEFAULT 1 AFTER status,
  ADD COLUMN last_amended_at timestamp NULL DEFAULT NULL AFTER cancelled_at;

ALTER TABLE sales_order
  ADD COLUMN current_revision_no int unsigned NOT NULL DEFAULT 1 AFTER status,
  ADD COLUMN last_amended_at timestamp NULL DEFAULT NULL AFTER cancelled_at;

CREATE TABLE business_order_revision (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  order_type varchar(24) NOT NULL COMMENT 'PURCHASE_ORDER, SALES_ORDER',
  order_id bigint unsigned NOT NULL,
  order_no varchar(64) NOT NULL,
  revision_no int unsigned NOT NULL,
  change_no varchar(64) NOT NULL,
  change_type varchar(24) NOT NULL COMMENT 'INITIAL, AMENDMENT, CANCEL',
  change_reason varchar(500) NOT NULL DEFAULT '',
  snapshot_before_json longtext,
  snapshot_after_json longtext,
  reversed_posting_batch_id bigint unsigned NOT NULL DEFAULT 0,
  posted_batch_id bigint unsigned NOT NULL DEFAULT 0,
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_business_order_revision (order_type,order_id,revision_no),
  UNIQUE KEY uk_business_order_change_no (change_no),
  KEY idx_business_order_revision_order (order_type,order_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='确认后采购销售订单变更版本与前后快照';

INSERT INTO business_order_revision
  (order_type,order_id,order_no,revision_no,change_no,change_type,change_reason,snapshot_after_json,operator_name)
SELECT 'PURCHASE_ORDER',o.id,o.order_no,1,concat('LEGACY-PO-',o.id,'-R1'),'INITIAL','过账模块启用前现状导入',
       JSON_OBJECT('orderNo',o.order_no,'supplierId',o.supplier_id,'businessDate',o.business_date,
         'expectedDate',o.expected_date,'totalQuantity',o.total_quantity,'totalAmount',o.total_amount,
         'status',o.status,'legacyImported',true),'系统迁移'
FROM purchase_order o;

INSERT INTO business_order_revision
  (order_type,order_id,order_no,revision_no,change_no,change_type,change_reason,snapshot_after_json,operator_name)
SELECT 'SALES_ORDER',o.id,o.order_no,1,concat('LEGACY-SO-',o.id,'-R1'),'INITIAL','过账模块启用前现状导入',
       JSON_OBJECT('orderNo',o.order_no,'customerId',o.customer_id,'businessDate',o.business_date,
         'deliveryPlanDate',o.delivery_plan_date,'totalAmount',o.total_amount,
         'status',o.status,'legacyImported',true),'系统迁移'
FROM sales_order o;

INSERT INTO business_posting_batch
  (posting_no,source_type,source_id,source_no,posting_action,source_revision_no,rule_version_id,rule_code,
   business_date,partner_id,idempotency_key,status,reason,operator_name,legacy_imported,posted_at)
SELECT concat('LEGACY-PC-',o.id),'PURCHASE_ORDER',o.id,o.order_no,'COMMITMENT_POST',1,r.id,r.rule_code,
       o.business_date,o.supplier_id,concat('PURCHASE_ORDER:',o.id,':COMMITMENT_POST:1'),'POSTED',
       '过账模块启用前已确认采购承诺导入','系统迁移',1,o.updated_at
FROM purchase_order o
JOIN business_posting_rule_version r ON r.rule_code='PURCHASE_COMMITMENT' AND r.version_no=1
WHERE o.status NOT IN ('DRAFT','CANCELLED');

INSERT INTO business_posting_batch
  (posting_no,source_type,source_id,source_no,posting_action,source_revision_no,rule_version_id,rule_code,
   business_date,partner_id,idempotency_key,status,reason,operator_name,legacy_imported,posted_at)
SELECT concat('LEGACY-SC-',o.id),'SALES_ORDER',o.id,o.order_no,'COMMITMENT_POST',1,r.id,r.rule_code,
       o.business_date,o.customer_id,concat('SALES_ORDER:',o.id,':COMMITMENT_POST:1'),'POSTED',
       '过账模块启用前已确认销售承诺导入','系统迁移',1,o.updated_at
FROM sales_order o
JOIN business_posting_rule_version r ON r.rule_code='SALES_COMMITMENT' AND r.version_no=1
WHERE o.status NOT IN ('DRAFT','CANCELLED');

INSERT INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,product_id,source_line_type,source_line_id,
   quantity,quantity_unit,amount,memo)
SELECT b.id,ROW_NUMBER() OVER (PARTITION BY b.id ORDER BY i.id),'PURCHASE_COMMITMENT_QTY','INCREASE',
       o.supplier_id,i.product_id,'PURCHASE_ORDER_ITEM',i.id,i.quantity,i.base_unit,i.amount,'历史采购承诺明细'
FROM business_posting_batch b
JOIN purchase_order o ON o.id=b.source_id
JOIN purchase_order_item i ON i.purchase_order_id=o.id AND i.status='ENABLED'
WHERE b.source_type='PURCHASE_ORDER' AND b.legacy_imported=1;

INSERT INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,amount,memo)
SELECT b.id,100000,'PURCHASE_COMMITMENT_AMOUNT','INCREASE',o.supplier_id,o.total_amount,'历史采购承诺金额'
FROM business_posting_batch b JOIN purchase_order o ON o.id=b.source_id
WHERE b.source_type='PURCHASE_ORDER' AND b.legacy_imported=1;

INSERT INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,product_id,source_line_type,source_line_id,
   quantity,quantity_unit,amount,memo)
SELECT b.id,ROW_NUMBER() OVER (PARTITION BY b.id ORDER BY i.id),'SALES_COMMITMENT_QTY','INCREASE',
       o.customer_id,i.product_id,'SALES_ORDER_ITEM',i.id,i.quantity,i.base_unit,i.amount,'历史销售承诺明细'
FROM business_posting_batch b
JOIN sales_order o ON o.id=b.source_id
JOIN sales_order_item i ON i.sales_order_id=o.id AND i.status='ENABLED'
WHERE b.source_type='SALES_ORDER' AND b.legacy_imported=1;

INSERT INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,amount,memo)
SELECT b.id,100000,'SALES_COMMITMENT_AMOUNT','INCREASE',o.customer_id,o.total_amount,'历史销售承诺金额'
FROM business_posting_batch b JOIN sales_order o ON o.id=b.source_id
WHERE b.source_type='SALES_ORDER' AND b.legacy_imported=1;

UPDATE business_order_revision r
JOIN business_posting_batch b ON b.source_type=r.order_type AND b.source_id=r.order_id
  AND b.posting_action='COMMITMENT_POST' AND b.source_revision_no=r.revision_no
SET r.posted_batch_id=b.id
WHERE r.revision_no=1;
