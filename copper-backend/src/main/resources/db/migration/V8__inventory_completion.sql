CREATE TABLE inventory_cost_policy (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  policy_code varchar(32) NOT NULL,
  costing_method varchar(32) NOT NULL COMMENT 'BATCH_ACTUAL',
  quantity_scale tinyint unsigned NOT NULL DEFAULT 3,
  unit_cost_scale tinyint unsigned NOT NULL DEFAULT 4,
  amount_scale tinyint unsigned NOT NULL DEFAULT 2,
  rounding_mode varchar(16) NOT NULL DEFAULT 'HALF_UP',
  retroactive_recalculation varchar(24) NOT NULL DEFAULT 'NONE',
  effective_from date NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_cost_policy_code (policy_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存成本计价策略';

INSERT INTO inventory_cost_policy
  (policy_code,costing_method,quantity_scale,unit_cost_scale,amount_scale,rounding_mode,retroactive_recalculation,effective_from,status)
VALUES ('DEFAULT','BATCH_ACTUAL',3,4,2,'HALF_UP','NONE','2026-01-01','ENABLED');

ALTER TABLE inbound_item
  ADD COLUMN inventory_unit_cost decimal(18,4) NULL COMMENT '含整单折扣及调整分摊后的主库存单位成本' AFTER amount,
  ADD COLUMN inventory_amount_cost decimal(18,2) NULL COMMENT '含整单折扣及调整分摊后的库存成本金额' AFTER inventory_unit_cost;

UPDATE inbound_item i
JOIN inventory_batch b ON b.id=i.inventory_batch_id
SET i.inventory_unit_cost=b.unit_cost,i.inventory_amount_cost=b.amount_cost
WHERE i.inventory_batch_id>0;

ALTER TABLE outbound_item
  ADD COLUMN inventory_unit_cost decimal(18,4) NULL COMMENT '确认出库时的批次成本快照' AFTER amount,
  ADD COLUMN inventory_amount_cost decimal(18,2) NULL COMMENT '确认出库时的库存成本金额快照' AFTER inventory_unit_cost;

UPDATE outbound_item i
JOIN inventory_batch b ON b.id=i.batch_id
SET i.inventory_unit_cost=b.unit_cost,
    i.inventory_amount_cost=round(i.quantity*b.unit_cost,2)
WHERE i.inventory_unit_cost IS NULL;

ALTER TABLE sales_return_item
  ADD COLUMN inventory_unit_cost decimal(18,4) NULL COMMENT '销售退货入库时的批次成本快照' AFTER amount,
  ADD COLUMN inventory_amount_cost decimal(18,2) NULL COMMENT '销售退货入库时的库存成本金额快照' AFTER inventory_unit_cost;

UPDATE sales_return_item i
JOIN inventory_batch b ON b.id=i.inventory_batch_id
SET i.inventory_unit_cost=b.unit_cost,
    i.inventory_amount_cost=round(i.quantity*b.unit_cost,2)
WHERE i.inventory_unit_cost IS NULL;

CREATE TABLE inventory_transfer_order (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  order_no varchar(64) NOT NULL,
  from_warehouse_id bigint unsigned NOT NULL,
  to_warehouse_id bigint unsigned NOT NULL,
  business_date date NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, IN_TRANSIT, COMPLETED, CANCELLED, REVERSED',
  total_quantity decimal(18,3) NOT NULL DEFAULT 0,
  total_cost decimal(18,2) NOT NULL DEFAULT 0,
  memo varchar(500) NOT NULL DEFAULT '',
  cancel_reason varchar(500) NOT NULL DEFAULT '',
  reversal_reason varchar(500) NOT NULL DEFAULT '',
  dispatched_at timestamp NULL DEFAULT NULL,
  received_at timestamp NULL DEFAULT NULL,
  cancelled_at timestamp NULL DEFAULT NULL,
  reversed_at timestamp NULL DEFAULT NULL,
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_transfer_order_no (order_no),
  KEY idx_inventory_transfer_status_date (status,business_date),
  KEY idx_inventory_transfer_from (from_warehouse_id,status),
  KEY idx_inventory_transfer_to (to_warehouse_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存调拨单';

CREATE TABLE inventory_transfer_item (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  transfer_id bigint unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  source_batch_id bigint unsigned NOT NULL,
  target_batch_id bigint unsigned NOT NULL DEFAULT 0,
  quantity decimal(18,3) NOT NULL,
  base_unit varchar(64) NOT NULL DEFAULT '',
  unit_cost decimal(18,4) NOT NULL,
  amount_cost decimal(18,2) NOT NULL,
  packaging_plan_json longtext,
  selected_package_instance_ids_json longtext,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_transfer_batch (transfer_id,source_batch_id),
  KEY idx_inventory_transfer_item_order (transfer_id),
  KEY idx_inventory_transfer_item_source (source_batch_id),
  KEY idx_inventory_transfer_item_target (target_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存调拨明细';

CREATE TABLE inventory_stocktake_order (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  order_no varchar(64) NOT NULL,
  warehouse_id bigint unsigned NOT NULL,
  business_date date NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, CONFIRMED, CANCELLED, REVERSED',
  total_gain_quantity decimal(18,3) NOT NULL DEFAULT 0,
  total_loss_quantity decimal(18,3) NOT NULL DEFAULT 0,
  total_cost_difference decimal(18,2) NOT NULL DEFAULT 0,
  memo varchar(500) NOT NULL DEFAULT '',
  cancel_reason varchar(500) NOT NULL DEFAULT '',
  reversal_reason varchar(500) NOT NULL DEFAULT '',
  confirmed_at timestamp NULL DEFAULT NULL,
  cancelled_at timestamp NULL DEFAULT NULL,
  reversed_at timestamp NULL DEFAULT NULL,
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_stocktake_order_no (order_no),
  KEY idx_inventory_stocktake_status_date (status,business_date),
  KEY idx_inventory_stocktake_warehouse (warehouse_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存盘点单';

CREATE TABLE inventory_stocktake_item (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  stocktake_id bigint unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  batch_id bigint unsigned NOT NULL,
  system_quantity decimal(18,3) NOT NULL,
  counted_quantity decimal(18,3) NOT NULL,
  difference_quantity decimal(18,3) NOT NULL,
  base_unit varchar(64) NOT NULL DEFAULT '',
  unit_cost decimal(18,4) NOT NULL,
  system_amount_cost decimal(18,2) NOT NULL,
  counted_amount_cost decimal(18,2) NOT NULL,
  cost_difference decimal(18,2) NOT NULL,
  difference_reason varchar(500) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_stocktake_batch (stocktake_id,batch_id),
  KEY idx_inventory_stocktake_item_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存盘点明细';

CREATE TABLE purchase_return_order (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  order_no varchar(64) NOT NULL,
  inbound_id bigint unsigned NOT NULL,
  purchase_order_id bigint unsigned NOT NULL DEFAULT 0,
  supplier_id bigint unsigned NOT NULL,
  warehouse_id bigint unsigned NOT NULL,
  business_date date NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, CONFIRMED, CANCELLED, REVERSED',
  total_quantity decimal(18,3) NOT NULL DEFAULT 0,
  total_amount decimal(18,2) NOT NULL DEFAULT 0,
  payable_reduction_amount decimal(18,2) NOT NULL DEFAULT 0,
  supplier_credit_amount decimal(18,2) NOT NULL DEFAULT 0,
  reason varchar(500) NOT NULL DEFAULT '',
  memo varchar(500) NOT NULL DEFAULT '',
  cancel_reason varchar(500) NOT NULL DEFAULT '',
  reversal_reason varchar(500) NOT NULL DEFAULT '',
  confirmed_at timestamp NULL DEFAULT NULL,
  cancelled_at timestamp NULL DEFAULT NULL,
  reversed_at timestamp NULL DEFAULT NULL,
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_purchase_return_order_no (order_no),
  KEY idx_purchase_return_inbound (inbound_id,status),
  KEY idx_purchase_return_purchase (purchase_order_id,status),
  KEY idx_purchase_return_supplier (supplier_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购退货单';

CREATE TABLE purchase_return_item (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  return_id bigint unsigned NOT NULL,
  inbound_item_id bigint unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  batch_id bigint unsigned NOT NULL,
  quantity decimal(18,3) NOT NULL,
  base_unit varchar(64) NOT NULL DEFAULT '',
  inventory_unit_cost decimal(18,4) NOT NULL,
  payable_unit_cost decimal(18,4) NOT NULL,
  amount decimal(18,2) NOT NULL,
  packaging_plan_json longtext,
  selected_package_instance_ids_json longtext,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_purchase_return_source_item (return_id,inbound_item_id),
  KEY idx_purchase_return_item_order (return_id),
  KEY idx_purchase_return_item_inbound (inbound_item_id),
  KEY idx_purchase_return_item_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购退货明细';

CREATE TABLE inventory_cost_adjustment (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  adjustment_no varchar(64) NOT NULL,
  business_date date NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, CONFIRMED, CANCELLED, REVERSED',
  total_difference_amount decimal(18,2) NOT NULL DEFAULT 0,
  reason varchar(500) NOT NULL DEFAULT '',
  memo varchar(500) NOT NULL DEFAULT '',
  cancel_reason varchar(500) NOT NULL DEFAULT '',
  reversal_reason varchar(500) NOT NULL DEFAULT '',
  confirmed_at timestamp NULL DEFAULT NULL,
  cancelled_at timestamp NULL DEFAULT NULL,
  reversed_at timestamp NULL DEFAULT NULL,
  created_by bigint unsigned NOT NULL DEFAULT 0,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_cost_adjustment_no (adjustment_no),
  KEY idx_inventory_cost_adjustment_status_date (status,business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存成本调整单';

CREATE TABLE inventory_cost_adjustment_item (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  adjustment_id bigint unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  warehouse_id bigint unsigned NOT NULL,
  batch_id bigint unsigned NOT NULL,
  quantity_snapshot decimal(18,3) NOT NULL,
  old_unit_cost decimal(18,4) NOT NULL,
  new_unit_cost decimal(18,4) NOT NULL,
  old_amount_cost decimal(18,2) NOT NULL,
  new_amount_cost decimal(18,2) NOT NULL,
  difference_amount decimal(18,2) NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_cost_adjustment_batch (adjustment_id,batch_id),
  KEY idx_inventory_cost_adjustment_item_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存成本调整明细';

CREATE TABLE inventory_document_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  document_type varchar(32) NOT NULL,
  document_id bigint unsigned NOT NULL,
  action varchar(32) NOT NULL,
  reason varchar(500) NOT NULL DEFAULT '',
  operator_id bigint unsigned NOT NULL DEFAULT 0,
  operator_name varchar(128) NOT NULL DEFAULT '',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_inventory_document_event_doc (document_type,document_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存单据状态审计事件';

ALTER TABLE outbound_allocation
  ADD KEY idx_outbound_allocation_reservation (inventory_batch_id,status,outbound_id);

ALTER TABLE inventory_package_instance
  ADD COLUMN operation_ref_type varchar(32) NOT NULL DEFAULT '' AFTER outbound_item_id,
  ADD COLUMN operation_ref_id bigint unsigned NOT NULL DEFAULT 0 AFTER operation_ref_type,
  ADD KEY idx_inventory_package_instance_operation (operation_ref_type,operation_ref_id,status);

INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('inventory.transfer','inventory','库存管理','ACTION','库存调拨','创建、发出、收货、作废和冲正库存调拨单',73,'ENABLED'),
  ('inventory.stocktake','inventory','库存管理','ACTION','库存盘点','创建、确认、作废和冲正库存盘点单',74,'ENABLED'),
  ('purchase.return','purchase','采购管理','ACTION','采购退货','创建、确认、作废和冲正采购退货单',75,'ENABLED'),
  ('inventory.cost.adjust','inventory','库存管理','ACTION','调整库存成本','创建、确认、作废和冲正批次成本调整单',76,'ENABLED');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER')
  AND p.code IN ('inventory.transfer','inventory.stocktake','purchase.return','inventory.cost.adjust');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE (r.code='WAREHOUSE' AND p.code IN ('inventory.transfer','inventory.stocktake'))
   OR (r.code='PURCHASE' AND p.code='purchase.return')
   OR (r.code='MANAGER' AND p.code IN ('inventory.transfer','inventory.stocktake','purchase.return'))
   OR (r.code='FINANCE' AND p.code='inventory.cost.adjust');
