-- Existing documents already changed operational balances before the versioned posting engine existed.
-- Import their immutable business facts without replaying any balance update.

INSERT IGNORE INTO business_posting_batch
  (posting_no,source_type,source_id,source_no,posting_action,source_revision_no,rule_version_id,rule_code,
   business_date,partner_id,idempotency_key,status,reason,operator_name,legacy_imported,posted_at,reversed_at)
SELECT concat('LEG-IN-',o.id,'-POST'),'INBOUND',o.id,o.order_no,'POST',1,r.id,r.rule_code,o.business_date,
       o.supplier_id,concat('INBOUND:',o.id,':POST:1'),if(o.status='REVERSED','REVERSED','POSTED'),
       '历史单据业务事实回填','系统迁移',1,coalesce(o.stocked_at,o.created_at),o.reversed_at
FROM inbound_order o JOIN business_posting_rule_version r
  ON r.source_type='INBOUND' AND r.posting_action='POST' AND r.version_no=1
WHERE o.status IN ('STOCKED','REVERSED');

INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,product_id,warehouse_id,inventory_batch_id,
   source_line_type,source_line_id,quantity,quantity_unit,amount,memo)
SELECT b.id,row_number() over(partition by b.id order by i.id)*2-1,'INVENTORY_ON_HAND','INCREASE',o.supplier_id,
       i.product_id,o.warehouse_id,i.inventory_batch_id,'INBOUND_ITEM',i.id,i.quantity,i.base_unit,0,'历史入库增加实际结存'
FROM inbound_item i JOIN inbound_order o ON o.id=i.inbound_id
JOIN business_posting_batch b ON b.source_type='INBOUND' AND b.source_id=o.id AND b.posting_action='POST' AND b.legacy_imported=1;
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,product_id,warehouse_id,inventory_batch_id,
   source_line_type,source_line_id,quantity,quantity_unit,amount,memo)
SELECT b.id,row_number() over(partition by b.id order by i.id)*2,'INVENTORY_COST','INCREASE',o.supplier_id,
       i.product_id,o.warehouse_id,i.inventory_batch_id,'INBOUND_ITEM',i.id,0,'',i.inventory_amount_cost,'历史入库增加库存成本'
FROM inbound_item i JOIN inbound_order o ON o.id=i.inbound_id
JOIN business_posting_batch b ON b.source_type='INBOUND' AND b.source_id=o.id AND b.posting_action='POST' AND b.legacy_imported=1;
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,source_line_type,source_line_id,amount,memo)
SELECT b.id,(select count(*)*2+1 from inbound_item x where x.inbound_id=o.id),'PAYABLE','INCREASE',o.supplier_id,
       'INBOUND',o.id,o.total_amount,'历史入库形成应付'
FROM inbound_order o JOIN business_posting_batch b ON b.source_type='INBOUND' AND b.source_id=o.id AND b.posting_action='POST' AND b.legacy_imported=1;

INSERT IGNORE INTO business_posting_batch
  (posting_no,source_type,source_id,source_no,posting_action,source_revision_no,rule_version_id,rule_code,
   business_date,partner_id,idempotency_key,status,reason,operator_name,legacy_imported,posted_at,reversed_at)
SELECT concat('LEG-OUT-',o.id,'-SHIP'),'OUTBOUND',o.id,o.order_no,'SHIP',1,r.id,r.rule_code,o.business_date,
       o.customer_id,concat('OUTBOUND:',o.id,':SHIP:1'),if(o.status='REVERSED','REVERSED','POSTED'),
       '历史单据业务事实回填','系统迁移',1,coalesce(o.confirmed_at,o.created_at),o.reversed_at
FROM outbound_order o JOIN business_posting_rule_version r
  ON r.source_type='OUTBOUND' AND r.posting_action='SHIP' AND r.version_no=1
WHERE o.status IN ('SHIPPED','REVERSED');
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,product_id,warehouse_id,inventory_batch_id,
   source_line_type,source_line_id,quantity,quantity_unit,amount,memo)
SELECT b.id,row_number() over(partition by b.id order by i.id)*3-2,'INVENTORY_ON_HAND','DECREASE',o.customer_id,
       i.product_id,o.warehouse_id,i.batch_id,'OUTBOUND_ITEM',i.id,i.quantity,i.base_unit,0,'历史出库减少实际结存'
FROM outbound_item i JOIN outbound_order o ON o.id=i.outbound_id
JOIN business_posting_batch b ON b.source_type='OUTBOUND' AND b.source_id=o.id AND b.posting_action='SHIP' AND b.legacy_imported=1;
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,product_id,warehouse_id,inventory_batch_id,
   source_line_type,source_line_id,quantity,quantity_unit,amount,memo)
SELECT b.id,row_number() over(partition by b.id order by i.id)*3-1,'INVENTORY_COST','DECREASE',o.customer_id,
       i.product_id,o.warehouse_id,i.batch_id,'OUTBOUND_ITEM',i.id,0,'',i.inventory_amount_cost,'历史出库结转库存成本'
FROM outbound_item i JOIN outbound_order o ON o.id=i.outbound_id
JOIN business_posting_batch b ON b.source_type='OUTBOUND' AND b.source_id=o.id AND b.posting_action='SHIP' AND b.legacy_imported=1;
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,product_id,warehouse_id,inventory_batch_id,
   source_line_type,source_line_id,quantity,quantity_unit,amount,memo)
SELECT b.id,row_number() over(partition by b.id order by i.id)*3,'SALES_FULFILLMENT','INCREASE',o.customer_id,
       i.product_id,o.warehouse_id,i.batch_id,'OUTBOUND_ITEM',i.id,i.quantity,i.base_unit,i.amount,'历史销售履约'
FROM outbound_item i JOIN outbound_order o ON o.id=i.outbound_id
JOIN business_posting_batch b ON b.source_type='OUTBOUND' AND b.source_id=o.id AND b.posting_action='SHIP' AND b.legacy_imported=1;

INSERT IGNORE INTO business_posting_batch
  (posting_no,source_type,source_id,source_no,posting_action,source_revision_no,rule_version_id,rule_code,
   business_date,partner_id,idempotency_key,status,reason,operator_name,legacy_imported,posted_at,reversed_at)
SELECT concat('LEG-OUT-',o.id,'-SETTLE'),'OUTBOUND',o.id,o.order_no,'SETTLE',1,r.id,r.rule_code,o.business_date,
       o.customer_id,concat('OUTBOUND:',o.id,':SETTLE:1'),if(o.status='REVERSED','REVERSED','POSTED'),
       '历史单据业务事实回填','系统迁移',1,coalesce(o.settled_at,o.confirmed_at,o.created_at),o.reversed_at
FROM outbound_order o JOIN business_posting_rule_version r
  ON r.source_type='OUTBOUND' AND r.posting_action='SETTLE' AND r.version_no=1
WHERE o.status IN ('SHIPPED','REVERSED') AND o.settlement_status<>'PENDING_PRICING';
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,source_line_type,source_line_id,amount,memo)
SELECT b.id,1,'RECEIVABLE','INCREASE',o.customer_id,'OUTBOUND',o.id,o.total_amount,'历史销售出库形成应收'
FROM outbound_order o JOIN business_posting_batch b ON b.source_type='OUTBOUND' AND b.source_id=o.id AND b.posting_action='SETTLE' AND b.legacy_imported=1;

INSERT IGNORE INTO business_posting_batch
  (posting_no,source_type,source_id,source_no,posting_action,source_revision_no,rule_version_id,rule_code,business_date,
   partner_id,idempotency_key,status,reason,operator_name,legacy_imported,posted_at,reversed_at)
SELECT concat('LEG-SR-',o.id,'-POST'),'SALES_RETURN',o.id,o.return_no,'POST',1,r.id,r.rule_code,o.business_date,o.customer_id,
       concat('SALES_RETURN:',o.id,':POST:1'),if(o.status='REVERSED','REVERSED','POSTED'),'历史单据业务事实回填','系统迁移',1,
       coalesce(o.confirmed_at,o.created_at),o.reversed_at
FROM sales_return_order o JOIN business_posting_rule_version r ON r.source_type='SALES_RETURN' AND r.posting_action='POST' AND r.version_no=1
WHERE o.status IN ('CONFIRMED','REVERSED');
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,product_id,warehouse_id,inventory_batch_id,source_line_type,source_line_id,quantity,quantity_unit,amount,memo)
SELECT b.id,row_number() over(partition by b.id order by i.id)*2-1,'INVENTORY_ON_HAND','INCREASE',o.customer_id,i.product_id,o.warehouse_id,
       i.inventory_batch_id,'SALES_RETURN_ITEM',i.id,i.quantity,i.base_unit,0,'历史销售退货恢复库存'
FROM sales_return_item i JOIN sales_return_order o ON o.id=i.sales_return_id JOIN business_posting_batch b ON b.source_type='SALES_RETURN' AND b.source_id=o.id AND b.posting_action='POST' AND b.legacy_imported=1;
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,product_id,warehouse_id,inventory_batch_id,source_line_type,source_line_id,quantity,quantity_unit,amount,memo)
SELECT b.id,row_number() over(partition by b.id order by i.id)*2,'INVENTORY_COST','INCREASE',o.customer_id,i.product_id,o.warehouse_id,
       i.inventory_batch_id,'SALES_RETURN_ITEM',i.id,0,'',i.inventory_amount_cost,'历史销售退货恢复库存成本'
FROM sales_return_item i JOIN sales_return_order o ON o.id=i.sales_return_id JOIN business_posting_batch b ON b.source_type='SALES_RETURN' AND b.source_id=o.id AND b.posting_action='POST' AND b.legacy_imported=1;
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,source_line_type,source_line_id,amount,memo)
SELECT b.id,(select count(*)*2+1 from sales_return_item x where x.sales_return_id=o.id),'RECEIVABLE','DECREASE',o.customer_id,'SALES_RETURN',o.id,o.total_amount,'历史销售退货冲减应收'
FROM sales_return_order o JOIN business_posting_batch b ON b.source_type='SALES_RETURN' AND b.source_id=o.id AND b.posting_action='POST' AND b.legacy_imported=1;

INSERT IGNORE INTO business_posting_batch
  (posting_no,source_type,source_id,source_no,posting_action,source_revision_no,rule_version_id,rule_code,business_date,partner_id,idempotency_key,status,reason,operator_name,legacy_imported,posted_at,reversed_at)
SELECT concat('LEG-TR-',o.id,'-DISPATCH'),'INVENTORY_TRANSFER',o.id,o.order_no,'DISPATCH',1,r.id,r.rule_code,o.business_date,0,
       concat('INVENTORY_TRANSFER:',o.id,':DISPATCH:1'),if(o.status='REVERSED','REVERSED','POSTED'),'历史单据业务事实回填','系统迁移',1,coalesce(o.dispatched_at,o.created_at),o.reversed_at
FROM inventory_transfer_order o JOIN business_posting_rule_version r ON r.source_type='INVENTORY_TRANSFER' AND r.posting_action='DISPATCH' AND r.version_no=1
WHERE o.status IN ('IN_TRANSIT','COMPLETED','REVERSED');
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,product_id,warehouse_id,inventory_batch_id,source_line_type,source_line_id,quantity,quantity_unit,amount,memo)
SELECT b.id,row_number() over(partition by b.id order by i.id)*2-1,'INVENTORY_ON_HAND','DECREASE',i.product_id,o.from_warehouse_id,i.source_batch_id,
       'TRANSFER_ITEM',i.id,i.quantity,i.base_unit,i.amount_cost,'历史调拨发出减少结存'
FROM inventory_transfer_item i JOIN inventory_transfer_order o ON o.id=i.transfer_id JOIN business_posting_batch b ON b.source_type='INVENTORY_TRANSFER' AND b.source_id=o.id AND b.posting_action='DISPATCH' AND b.legacy_imported=1;
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,product_id,inventory_batch_id,source_line_type,source_line_id,quantity,quantity_unit,amount,memo)
SELECT b.id,row_number() over(partition by b.id order by i.id)*2,'INVENTORY_IN_TRANSIT','INCREASE',i.product_id,i.source_batch_id,
       'TRANSFER_ITEM',i.id,i.quantity,i.base_unit,i.amount_cost,'历史调拨形成在途'
FROM inventory_transfer_item i JOIN inventory_transfer_order o ON o.id=i.transfer_id JOIN business_posting_batch b ON b.source_type='INVENTORY_TRANSFER' AND b.source_id=o.id AND b.posting_action='DISPATCH' AND b.legacy_imported=1;

INSERT IGNORE INTO business_posting_batch
  (posting_no,source_type,source_id,source_no,posting_action,source_revision_no,rule_version_id,rule_code,business_date,partner_id,idempotency_key,status,reason,operator_name,legacy_imported,posted_at,reversed_at)
SELECT concat('LEG-TR-',o.id,'-RECEIVE'),'INVENTORY_TRANSFER',o.id,o.order_no,'RECEIVE',1,r.id,r.rule_code,o.business_date,0,
       concat('INVENTORY_TRANSFER:',o.id,':RECEIVE:1'),if(o.status='REVERSED','REVERSED','POSTED'),'历史单据业务事实回填','系统迁移',1,o.received_at,o.reversed_at
FROM inventory_transfer_order o JOIN business_posting_rule_version r ON r.source_type='INVENTORY_TRANSFER' AND r.posting_action='RECEIVE' AND r.version_no=1
WHERE o.status IN ('COMPLETED','REVERSED') AND o.received_at IS NOT NULL;
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,product_id,inventory_batch_id,source_line_type,source_line_id,quantity,quantity_unit,amount,memo)
SELECT b.id,row_number() over(partition by b.id order by i.id)*2-1,'INVENTORY_IN_TRANSIT','DECREASE',i.product_id,i.source_batch_id,
       'TRANSFER_ITEM',i.id,i.quantity,i.base_unit,i.amount_cost,'历史调拨收货冲减在途'
FROM inventory_transfer_item i JOIN inventory_transfer_order o ON o.id=i.transfer_id JOIN business_posting_batch b ON b.source_type='INVENTORY_TRANSFER' AND b.source_id=o.id AND b.posting_action='RECEIVE' AND b.legacy_imported=1;
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,product_id,warehouse_id,inventory_batch_id,source_line_type,source_line_id,quantity,quantity_unit,amount,memo)
SELECT b.id,row_number() over(partition by b.id order by i.id)*2,'INVENTORY_ON_HAND','INCREASE',i.product_id,o.to_warehouse_id,i.target_batch_id,
       'TRANSFER_ITEM',i.id,i.quantity,i.base_unit,i.amount_cost,'历史调拨收货增加结存'
FROM inventory_transfer_item i JOIN inventory_transfer_order o ON o.id=i.transfer_id JOIN business_posting_batch b ON b.source_type='INVENTORY_TRANSFER' AND b.source_id=o.id AND b.posting_action='RECEIVE' AND b.legacy_imported=1;

INSERT IGNORE INTO business_posting_batch
  (posting_no,source_type,source_id,source_no,posting_action,source_revision_no,rule_version_id,rule_code,business_date,partner_id,idempotency_key,status,reason,operator_name,legacy_imported,posted_at,reversed_at)
SELECT concat('LEG-ST-',o.id,'-POST'),'INVENTORY_STOCKTAKE',o.id,o.order_no,'POST',1,r.id,r.rule_code,o.business_date,0,
       concat('INVENTORY_STOCKTAKE:',o.id,':POST:1'),if(o.status='REVERSED','REVERSED','POSTED'),'历史单据业务事实回填','系统迁移',1,coalesce(o.confirmed_at,o.created_at),o.reversed_at
FROM inventory_stocktake_order o JOIN business_posting_rule_version r ON r.source_type='INVENTORY_STOCKTAKE' AND r.posting_action='POST' AND r.version_no=1
WHERE o.status IN ('CONFIRMED','REVERSED');
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,product_id,warehouse_id,inventory_batch_id,source_line_type,source_line_id,quantity,quantity_unit,amount,memo)
SELECT b.id,row_number() over(partition by b.id order by i.id)*2-1,'INVENTORY_ON_HAND',if(i.difference_quantity>=0,'INCREASE','DECREASE'),i.product_id,o.warehouse_id,
       i.batch_id,'STOCKTAKE_ITEM',i.id,abs(i.difference_quantity),i.base_unit,0,'历史盘点调整结存'
FROM inventory_stocktake_item i JOIN inventory_stocktake_order o ON o.id=i.stocktake_id JOIN business_posting_batch b ON b.source_type='INVENTORY_STOCKTAKE' AND b.source_id=o.id AND b.posting_action='POST' AND b.legacy_imported=1;
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,product_id,warehouse_id,inventory_batch_id,source_line_type,source_line_id,amount,memo)
SELECT b.id,row_number() over(partition by b.id order by i.id)*2,'INVENTORY_COST',if(i.cost_difference>=0,'INCREASE','DECREASE'),i.product_id,o.warehouse_id,
       i.batch_id,'STOCKTAKE_ITEM',i.id,abs(i.cost_difference),'历史盘点调整成本'
FROM inventory_stocktake_item i JOIN inventory_stocktake_order o ON o.id=i.stocktake_id JOIN business_posting_batch b ON b.source_type='INVENTORY_STOCKTAKE' AND b.source_id=o.id AND b.posting_action='POST' AND b.legacy_imported=1;

INSERT IGNORE INTO business_posting_batch
  (posting_no,source_type,source_id,source_no,posting_action,source_revision_no,rule_version_id,rule_code,business_date,partner_id,idempotency_key,status,reason,operator_name,legacy_imported,posted_at,reversed_at)
SELECT concat('LEG-PR-',o.id,'-POST'),'PURCHASE_RETURN',o.id,o.order_no,'POST',1,r.id,r.rule_code,o.business_date,o.supplier_id,
       concat('PURCHASE_RETURN:',o.id,':POST:1'),if(o.status='REVERSED','REVERSED','POSTED'),'历史单据业务事实回填','系统迁移',1,coalesce(o.confirmed_at,o.created_at),o.reversed_at
FROM purchase_return_order o JOIN business_posting_rule_version r ON r.source_type='PURCHASE_RETURN' AND r.posting_action='POST' AND r.version_no=1
WHERE o.status IN ('CONFIRMED','REVERSED');
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,product_id,warehouse_id,inventory_batch_id,source_line_type,source_line_id,quantity,quantity_unit,amount,memo)
SELECT b.id,row_number() over(partition by b.id order by i.id)*2-1,'INVENTORY_ON_HAND','DECREASE',o.supplier_id,i.product_id,o.warehouse_id,i.batch_id,
       'PURCHASE_RETURN_ITEM',i.id,i.quantity,i.base_unit,0,'历史采购退货减少库存'
FROM purchase_return_item i JOIN purchase_return_order o ON o.id=i.return_id JOIN business_posting_batch b ON b.source_type='PURCHASE_RETURN' AND b.source_id=o.id AND b.posting_action='POST' AND b.legacy_imported=1;
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,product_id,warehouse_id,inventory_batch_id,source_line_type,source_line_id,amount,memo)
SELECT b.id,row_number() over(partition by b.id order by i.id)*2,'INVENTORY_COST','DECREASE',o.supplier_id,i.product_id,o.warehouse_id,i.batch_id,
       'PURCHASE_RETURN_ITEM',i.id,i.inventory_amount_cost,'历史采购退货结转库存成本'
FROM purchase_return_item i JOIN purchase_return_order o ON o.id=i.return_id JOIN business_posting_batch b ON b.source_type='PURCHASE_RETURN' AND b.source_id=o.id AND b.posting_action='POST' AND b.legacy_imported=1;
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,source_line_type,source_line_id,amount,memo)
SELECT b.id,(select count(*)*2+1 from purchase_return_item x where x.return_id=o.id),'PAYABLE','DECREASE',o.supplier_id,'PURCHASE_RETURN',o.id,o.payable_reduction_amount,'历史采购退货冲减应付'
FROM purchase_return_order o JOIN business_posting_batch b ON b.source_type='PURCHASE_RETURN' AND b.source_id=o.id AND b.posting_action='POST' AND b.legacy_imported=1
WHERE o.payable_reduction_amount>0;

INSERT IGNORE INTO business_posting_batch
  (posting_no,source_type,source_id,source_no,posting_action,source_revision_no,rule_version_id,rule_code,business_date,partner_id,idempotency_key,status,reason,operator_name,legacy_imported,posted_at,reversed_at)
SELECT concat('LEG-CA-',o.id,'-POST'),'INVENTORY_COST_ADJUSTMENT',o.id,o.adjustment_no,'POST',1,r.id,r.rule_code,o.business_date,0,
       concat('INVENTORY_COST_ADJUSTMENT:',o.id,':POST:1'),if(o.status='REVERSED','REVERSED','POSTED'),'历史单据业务事实回填','系统迁移',1,coalesce(o.confirmed_at,o.created_at),o.reversed_at
FROM inventory_cost_adjustment o JOIN business_posting_rule_version r ON r.source_type='INVENTORY_COST_ADJUSTMENT' AND r.posting_action='POST' AND r.version_no=1
WHERE o.status IN ('CONFIRMED','REVERSED');
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,product_id,warehouse_id,inventory_batch_id,source_line_type,source_line_id,amount,memo)
SELECT b.id,row_number() over(partition by b.id order by i.id),'INVENTORY_COST',if(i.difference_amount>=0,'INCREASE','DECREASE'),i.product_id,i.warehouse_id,i.batch_id,
       'COST_ADJUSTMENT_ITEM',i.id,abs(i.difference_amount),'历史库存成本调整'
FROM inventory_cost_adjustment_item i JOIN inventory_cost_adjustment o ON o.id=i.adjustment_id JOIN business_posting_batch b ON b.source_type='INVENTORY_COST_ADJUSTMENT' AND b.source_id=o.id AND b.posting_action='POST' AND b.legacy_imported=1;

INSERT IGNORE INTO business_posting_batch
  (posting_no,source_type,source_id,source_no,posting_action,source_revision_no,rule_version_id,rule_code,business_date,partner_id,idempotency_key,status,reason,operator_name,legacy_imported,posted_at,reversed_at)
SELECT concat('LEG-PAY-',o.id,'-POST'),'PAYMENT',o.id,o.payment_no,'POST',1,r.id,r.rule_code,o.received_date,o.customer_id,
       concat('PAYMENT:',o.id,':POST:1'),if(o.verification_status='REVERSED','REVERSED','POSTED'),'历史单据业务事实回填','系统迁移',1,o.created_at,o.reversed_at
FROM payment o JOIN business_posting_rule_version r ON r.source_type='PAYMENT' AND r.posting_action='POST' AND r.version_no=1;
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,source_line_type,source_line_id,amount,memo)
SELECT b.id,1,if(o.sales_order_id>0 or coalesce(o.outbound_id,0)>0,'RECEIVABLE_SETTLEMENT','CUSTOMER_ADVANCE'),
       if(o.sales_order_id>0 or coalesce(o.outbound_id,0)>0,'DECREASE','INCREASE'),o.customer_id,'PAYMENT',o.id,o.amount,'历史客户收款'
FROM payment o JOIN business_posting_batch b ON b.source_type='PAYMENT' AND b.source_id=o.id AND b.posting_action='POST' AND b.legacy_imported=1;

INSERT IGNORE INTO business_posting_batch
  (posting_no,source_type,source_id,source_no,posting_action,source_revision_no,rule_version_id,rule_code,business_date,partner_id,idempotency_key,status,reason,operator_name,legacy_imported,posted_at,reversed_at)
SELECT concat('LEG-SP-',o.id,'-POST'),'SUPPLIER_PAYMENT',o.id,o.payment_no,'POST',1,r.id,r.rule_code,o.paid_date,o.supplier_id,
       concat('SUPPLIER_PAYMENT:',o.id,':POST:1'),if(o.verification_status='REVERSED','REVERSED','POSTED'),'历史单据业务事实回填','系统迁移',1,o.created_at,o.reversed_at
FROM supplier_payment o JOIN business_posting_rule_version r ON r.source_type='SUPPLIER_PAYMENT' AND r.posting_action='POST' AND r.version_no=1;
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,source_line_type,source_line_id,amount,memo)
SELECT b.id,1,if(o.purchase_order_id>0 or o.inbound_id>0,'PAYABLE_SETTLEMENT','SUPPLIER_PREPAY'),
       if(o.purchase_order_id>0 or o.inbound_id>0,'DECREASE','INCREASE'),o.supplier_id,'SUPPLIER_PAYMENT',o.id,o.amount,'历史供应商付款'
FROM supplier_payment o JOIN business_posting_batch b ON b.source_type='SUPPLIER_PAYMENT' AND b.source_id=o.id AND b.posting_action='POST' AND b.legacy_imported=1;

INSERT IGNORE INTO business_posting_batch
  (posting_no,source_type,source_id,source_no,posting_action,source_revision_no,rule_version_id,rule_code,business_date,partner_id,idempotency_key,status,reason,operator_name,legacy_imported,posted_at,reversed_at)
SELECT concat('LEG-RA-',o.id,'-POST'),'RECEIVABLE_ADJUSTMENT',o.id,o.adjustment_no,'POST',1,r.id,r.rule_code,o.occurred_date,o.customer_id,
       concat('RECEIVABLE_ADJUSTMENT:',o.id,':POST:1'),if(o.status='REVERSED','REVERSED','POSTED'),'历史单据业务事实回填','系统迁移',1,coalesce(o.confirmed_at,o.created_at),o.reversed_at
FROM receivable_adjustment o JOIN business_posting_rule_version r ON r.source_type='RECEIVABLE_ADJUSTMENT' AND r.posting_action='POST' AND r.version_no=1
WHERE o.status IN ('CONFIRMED','REVERSED');
INSERT IGNORE INTO business_posting_entry
  (posting_batch_id,line_no,fact_type,direction,partner_id,source_line_type,source_line_id,amount,memo)
SELECT b.id,1,'RECEIVABLE_ADJUSTMENT','DECREASE',o.customer_id,'RECEIVABLE_ADJUSTMENT',o.id,o.amount,'历史应收调整'
FROM receivable_adjustment o JOIN business_posting_batch b ON b.source_type='RECEIVABLE_ADJUSTMENT' AND b.source_id=o.id AND b.posting_action='POST' AND b.legacy_imported=1;

SET SESSION group_concat_max_len=1048576;
UPDATE business_posting_batch b
SET b.entry_hash=coalesce((SELECT sha2(group_concat(concat_ws('|',e.fact_type,e.direction,e.partner_id,e.product_id,e.warehouse_id,
        e.inventory_batch_id,e.source_line_type,e.source_line_id,e.quantity,e.quantity_unit,e.amount,e.memo)
        ORDER BY e.line_no SEPARATOR '\n'),256)
  FROM business_posting_entry e WHERE e.posting_batch_id=b.id),'')
WHERE b.legacy_imported=1;

INSERT INTO business_posting_event
  (posting_batch_id,action,from_status,to_status,reason,operator_id,operator_name,created_at)
SELECT b.id,'LEGACY_IMPORT','',b.status,'历史单据只回填业务事实，不重新影响余额',0,'系统迁移',b.created_at
FROM business_posting_batch b
WHERE b.legacy_imported=1 AND NOT EXISTS(
  SELECT 1 FROM business_posting_event e WHERE e.posting_batch_id=b.id AND e.action='LEGACY_IMPORT');
