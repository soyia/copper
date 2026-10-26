INSERT IGNORE INTO security_permission
  (code, module_code, module_name, permission_type, name, description, sort_no, status)
VALUES
  ('storage.capacity.view', 'data-autonomy', '数据自主', 'VIEW', '查看存储与容量状态',
   '查看数据库、附件、导出、备份占用及客户自有存储目标的容量风险', 161, 'ENABLED');

UPDATE security_permission
SET module_code='data-autonomy', module_name='数据自主', permission_type='VIEW',
    name='查看存储与容量状态', description='查看数据库、附件、导出、备份占用及客户自有存储目标的容量风险',
    sort_no=161, status='ENABLED'
WHERE code='storage.capacity.view';

INSERT IGNORE INTO security_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM security_role r
JOIN security_permission p ON p.code='storage.capacity.view'
WHERE r.code IN ('ADMIN', 'OWNER');
