UPDATE security_permission
SET description='初始化年度及维护开放期间基础信息；正式结账和反结账使用独立权限'
WHERE code='accounting.period.manage';
