CREATE TABLE inventory_opening_cost_alignment (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  batch_id bigint unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  warehouse_id bigint unsigned NOT NULL,
  quantity_snapshot decimal(18,3) NOT NULL,
  unit_cost_snapshot decimal(18,4) NOT NULL,
  batch_amount_before decimal(18,2) NOT NULL,
  batch_amount_after decimal(18,2) NOT NULL,
  difference_amount decimal(18,2) NOT NULL,
  stock_balance_amount_snapshot decimal(18,2) NOT NULL,
  alignment_reason varchar(500) NOT NULL,
  aligned_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_opening_cost_alignment_batch (batch_id),
  KEY idx_inventory_opening_cost_alignment_scope (product_id,warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存成本模块上线期初批次金额对齐审计';

INSERT INTO inventory_opening_cost_alignment
  (batch_id,product_id,warehouse_id,quantity_snapshot,unit_cost_snapshot,
   batch_amount_before,batch_amount_after,difference_amount,stock_balance_amount_snapshot,alignment_reason)
SELECT b.id,b.product_id,b.warehouse_id,b.quantity_available,b.unit_cost,b.amount_cost,
       round(b.quantity_available*b.unit_cost,2),
       round(b.quantity_available*b.unit_cost,2)-b.amount_cost,
       coalesce(s.amount_cost,0),
       '库存成本模块上线期初对齐：按批次实际成本法校正当前批次金额；不重写历史出入库流水'
FROM inventory_batch b
LEFT JOIN stock_balance s ON s.product_id=b.product_id AND s.warehouse_id=b.warehouse_id
WHERE b.amount_cost<>round(b.quantity_available*b.unit_cost,2);

UPDATE inventory_batch b
JOIN inventory_opening_cost_alignment a ON a.batch_id=b.id
SET b.amount_cost=a.batch_amount_after;
