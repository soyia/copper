ALTER TABLE outbound_order
  ADD COLUMN cancel_reason varchar(500) NOT NULL DEFAULT '' AFTER cancelled_at;

INSERT IGNORE INTO security_permission
  (code, module_code, module_name, permission_type, name, description, sort_no, status)
VALUES
  ('outbound.over-shipment', 'outbound', '销售出库', 'ACTION', '允许超订单发货',
   '仅在有明确业务原因时允许出库数量超过销售订单剩余数量', 93, 'ENABLED');

UPDATE security_permission
SET module_code='outbound', module_name='销售出库', permission_type='ACTION',
    name='允许超订单发货', description='仅在有明确业务原因时允许出库数量超过销售订单剩余数量',
    sort_no=93, status='ENABLED'
WHERE code='outbound.over-shipment';

INSERT IGNORE INTO security_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM security_role r
JOIN security_permission p ON p.code='outbound.over-shipment'
WHERE r.code IN ('ADMIN', 'OWNER');
