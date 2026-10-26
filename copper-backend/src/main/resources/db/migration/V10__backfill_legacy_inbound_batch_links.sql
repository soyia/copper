UPDATE inbound_item i
JOIN (
  SELECT source_id inbound_item_id,MIN(id) batch_id
  FROM inventory_batch
  WHERE source_type='INBOUND'
  GROUP BY source_id
  HAVING count(*)=1
) legacy ON legacy.inbound_item_id=i.id
JOIN inventory_batch b ON b.id=legacy.batch_id
SET i.inventory_batch_id=b.id,
    i.inventory_unit_cost=coalesce(i.inventory_unit_cost,b.unit_cost),
    i.inventory_amount_cost=coalesce(i.inventory_amount_cost,round(i.quantity*b.unit_cost,2))
WHERE i.inventory_batch_id=0
   OR i.inventory_unit_cost IS NULL
   OR i.inventory_amount_cost IS NULL;
