-- 订单可以按箱/包下单、按支等下级单位履约。履约容量统一使用基础库存数量；
-- requested progress 只作为订单单位展示值，由基础数量按订单快照比例复算。

UPDATE sales_order_item
SET shipped_requested_quantity = CASE
    WHEN quantity > 0 THEN ROUND(shipped_quantity * requested_quantity / quantity, 6)
    ELSE 0
END;

UPDATE purchase_order_item
SET received_requested_quantity = CASE
    WHEN quantity > 0 THEN ROUND(received_quantity * requested_quantity / quantity, 6)
    ELSE 0
END;
