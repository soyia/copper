INSERT IGNORE INTO security_permission
  (code,module_code,module_name,permission_type,name,description,sort_no,status)
VALUES
  ('invoice.void','invoice','发票税务','ACTION','作废发票','仅在税务状态和业务条件允许时作废发票登记并保留审计',92,'ENABLED'),
  ('financial.bank.account.manage','financial-close','财务结账','ACTION','维护企业银行账户','新增、修改、启停企业银行账户及其银行存款科目映射',95,'ENABLED'),
  ('financial.close.reopen','financial-close','财务结账','ACTION','执行规范反结账','经负责人批准后按时间倒序重新开放已结账期间并处理损益结转凭证',96,'ENABLED');

UPDATE security_permission
SET name='确认发票', description='确认发票登记并冻结票面业务事实'
WHERE code='invoice.confirm';

UPDATE security_permission
SET name='管理银行对账', description='导入银行流水、匹配、解释差异和确认或重开银行对账单'
WHERE code='financial.bank.manage';

UPDATE security_permission
SET name='执行期末结账', description='运行结账检查、生成损益结转并正式锁定会计期间'
WHERE code='financial.close.manage';

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER')
  AND p.code IN ('invoice.void','financial.bank.account.manage','financial.close.reopen');

INSERT IGNORE INTO security_role_permission (role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code='FINANCE'
  AND p.code IN ('invoice.void','financial.bank.account.manage');

DELETE rp FROM security_role_permission rp
JOIN security_role r ON r.id=rp.role_id
JOIN security_permission p ON p.id=rp.permission_id
WHERE r.code IN ('FINANCE','MANAGER','WAREHOUSE','SALES','PURCHASE')
  AND p.code='financial.close.reopen';

DELETE rp FROM security_role_permission rp
JOIN security_role r ON r.id=rp.role_id
JOIN security_permission p ON p.id=rp.permission_id
WHERE r.code IN ('MANAGER','WAREHOUSE','SALES','PURCHASE')
  AND p.code IN ('invoice.void','financial.bank.account.manage');

UPDATE security_role_data_scope s
JOIN security_role r ON r.id=s.role_id
SET s.scope_mode='ALL', s.selected_ids_json='[]', s.updated_at=CURRENT_TIMESTAMP
WHERE s.dimension='DOCUMENT' AND r.code IN ('FINANCE','WAREHOUSE');
