ALTER TABLE sales_order
  ADD COLUMN fulfillment_warehouse_id bigint unsigned NOT NULL DEFAULT 0 AFTER delivery_plan_date,
  ADD KEY idx_sales_order_atp (fulfillment_warehouse_id,delivery_plan_date,status,id);

ALTER TABLE purchase_order
  ADD COLUMN expected_warehouse_id bigint unsigned NOT NULL DEFAULT 0 AFTER expected_date,
  ADD KEY idx_purchase_order_atp (expected_warehouse_id,expected_date,status,id);

ALTER TABLE inventory_transfer_order
  ADD COLUMN expected_arrival_date date DEFAULT NULL AFTER business_date,
  ADD KEY idx_inventory_transfer_atp (to_warehouse_id,expected_arrival_date,status,id);

-- Existing active orders are assigned only when the source documents prove one warehouse or the
-- installation has a single enabled warehouse. Ambiguous historical orders remain visible as
-- unallocated demand/supply and are never silently assigned to the wrong warehouse.
UPDATE sales_order o
LEFT JOIN (
  SELECT sales_order_id,MIN(warehouse_id) warehouse_id,COUNT(DISTINCT warehouse_id) warehouse_count
  FROM outbound_order WHERE sales_order_id>0 GROUP BY sales_order_id
) x ON x.sales_order_id=o.id
SET o.fulfillment_warehouse_id=CASE
  WHEN x.warehouse_count=1 THEN x.warehouse_id
  WHEN (SELECT COUNT(*) FROM warehouse WHERE status='ENABLED')=1
    THEN (SELECT MIN(id) FROM warehouse WHERE status='ENABLED')
  ELSE 0 END
WHERE o.fulfillment_warehouse_id=0;

UPDATE purchase_order o
LEFT JOIN (
  SELECT purchase_order_id,MIN(warehouse_id) warehouse_id,COUNT(DISTINCT warehouse_id) warehouse_count
  FROM inbound_order WHERE purchase_order_id>0 GROUP BY purchase_order_id
) x ON x.purchase_order_id=o.id
SET o.expected_warehouse_id=CASE
  WHEN x.warehouse_count=1 THEN x.warehouse_id
  WHEN (SELECT COUNT(*) FROM warehouse WHERE status='ENABLED')=1
    THEN (SELECT MIN(id) FROM warehouse WHERE status='ENABLED')
  ELSE 0 END
WHERE o.expected_warehouse_id=0;

UPDATE inventory_transfer_order
SET expected_arrival_date=business_date
WHERE expected_arrival_date IS NULL AND status IN ('IN_TRANSIT','COMPLETED');

CREATE TABLE inventory_atp_lock (
  product_id bigint unsigned NOT NULL,
  warehouse_id bigint unsigned NOT NULL,
  updated_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (product_id,warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ATP仓库商品并发承诺锁';

CREATE TABLE inventory_atp_snapshot (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  snapshot_no varchar(64) NOT NULL,
  action varchar(24) NOT NULL COMMENT 'CONFIRM, AMEND, CANCEL',
  decision varchar(24) NOT NULL COMMENT 'PROMISED, RELEASED',
  sales_order_id bigint unsigned NOT NULL,
  sales_order_item_id bigint unsigned NOT NULL,
  sales_order_no varchar(64) NOT NULL,
  revision_no int unsigned NOT NULL DEFAULT 1,
  product_id bigint unsigned NOT NULL,
  warehouse_id bigint unsigned NOT NULL,
  promise_date date NOT NULL,
  commitment_quantity decimal(24,6) NOT NULL,
  on_hand_quantity decimal(24,6) NOT NULL,
  outbound_draft_quantity decimal(24,6) NOT NULL,
  sales_commitment_quantity decimal(24,6) NOT NULL,
  linked_draft_covered_quantity decimal(24,6) NOT NULL,
  purchase_incoming_quantity decimal(24,6) NOT NULL,
  transfer_incoming_quantity decimal(24,6) NOT NULL,
  atp_before_quantity decimal(24,6) NOT NULL,
  atp_after_quantity decimal(24,6) NOT NULL,
  earliest_shortage_date date DEFAULT NULL,
  calculation_version varchar(32) NOT NULL DEFAULT 'TIME_PHASED_ATP_V1',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_atp_snapshot_no (snapshot_no),
  KEY idx_inventory_atp_snapshot_order (sales_order_id,id),
  KEY idx_inventory_atp_snapshot_scope (product_id,warehouse_id,promise_date,id),
  CONSTRAINT chk_inventory_atp_snapshot_action CHECK (action IN ('CONFIRM','AMEND','CANCEL')),
  CONSTRAINT chk_inventory_atp_snapshot_decision CHECK (decision IN ('PROMISED','RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售承诺ATP不可变快照';
