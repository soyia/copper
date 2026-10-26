ALTER TABLE inventory_non_trade_document
  ADD COLUMN handler_name varchar(128) NOT NULL DEFAULT ''
    COMMENT '经办人或发放人；与领用人、系统制单人分别记录'
    AFTER recipient,
  ADD COLUMN item_count int unsigned NOT NULL DEFAULT 1
    COMMENT '商品明细行数'
    AFTER handler_name,
  ADD COLUMN total_amount_cost decimal(18,2) NOT NULL DEFAULT 0.00
    COMMENT '全部商品明细的库存成本合计'
    AFTER item_count;

CREATE TABLE inventory_non_trade_item (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  document_id bigint unsigned NOT NULL,
  line_no int unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  warehouse_id bigint unsigned NOT NULL,
  batch_id bigint unsigned NOT NULL,
  quantity decimal(24,6) NOT NULL,
  base_unit varchar(64) NOT NULL DEFAULT '',
  unit_cost decimal(24,8) NOT NULL,
  amount_cost decimal(18,2) NOT NULL,
  quantity_before decimal(24,6) NOT NULL,
  amount_before decimal(18,2) NOT NULL,
  packaging_ref_id bigint unsigned NOT NULL DEFAULT 0
    COMMENT '包装流水引用键；历史单据沿用单据ID，新单据使用明细ID',
  selected_package_instance_ids_json longtext,
  selected_package_allocations_json longtext,
  packaging_plan_json longtext,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_non_trade_item_line (document_id,line_no),
  UNIQUE KEY uk_inventory_non_trade_item_batch (document_id,batch_id),
  KEY idx_inventory_non_trade_item_product (product_id,document_id,id),
  KEY idx_inventory_non_trade_item_warehouse (warehouse_id,document_id,id),
  KEY idx_inventory_non_trade_item_batch (batch_id,document_id,id),
  CONSTRAINT chk_inventory_non_trade_item_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='送样、领用、损耗等非购销库存单的多商品明细；每行独立扣取批次及包装';

INSERT INTO inventory_non_trade_item
  (document_id,line_no,product_id,warehouse_id,batch_id,quantity,base_unit,
   unit_cost,amount_cost,quantity_before,amount_before,packaging_ref_id,
   selected_package_instance_ids_json,selected_package_allocations_json,packaging_plan_json)
SELECT id,1,product_id,warehouse_id,batch_id,quantity,base_unit,
       unit_cost,amount_cost,quantity_before,amount_before,id,
       selected_package_instance_ids_json,selected_package_allocations_json,packaging_plan_json
FROM inventory_non_trade_document;

UPDATE inventory_non_trade_document
SET item_count = 1,
    total_amount_cost = amount_cost;
