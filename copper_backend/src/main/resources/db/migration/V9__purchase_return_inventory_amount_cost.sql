ALTER TABLE purchase_return_item
  ADD COLUMN inventory_amount_cost decimal(18,2) NULL COMMENT '采购退货确认时的库存成本金额快照' AFTER inventory_unit_cost;

UPDATE purchase_return_item
SET inventory_amount_cost=round(quantity*inventory_unit_cost,2)
WHERE inventory_amount_cost IS NULL;

ALTER TABLE purchase_return_item
  MODIFY COLUMN inventory_amount_cost decimal(18,2) NOT NULL COMMENT '采购退货确认时的库存成本金额快照';
