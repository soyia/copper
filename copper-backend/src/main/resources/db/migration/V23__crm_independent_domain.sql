CREATE TABLE crm_option (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  option_type varchar(32) NOT NULL,
  option_code varchar(48) NOT NULL,
  option_name varchar(64) NOT NULL,
  sort_no int NOT NULL DEFAULT 0,
  built_in tinyint unsigned NOT NULL DEFAULT 0,
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_crm_option_type_code (option_type, option_code),
  KEY idx_crm_option_type_status (option_type, status, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM configurable options';

CREATE TABLE crm_number_sequence (
  document_type varchar(24) NOT NULL,
  next_value bigint unsigned NOT NULL DEFAULT 1,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (document_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM document number sequence';

INSERT INTO crm_number_sequence(document_type,next_value) VALUES
('LEAD',1),('OPPORTUNITY',1),('QUOTE',1);

CREATE TABLE crm_lead (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  lead_no varchar(32) NOT NULL,
  subject varchar(128) NOT NULL,
  company_name varchar(128) NOT NULL DEFAULT '',
  contact_name varchar(64) NOT NULL DEFAULT '',
  phone varchar(32) NOT NULL DEFAULT '',
  email varchar(128) NOT NULL DEFAULT '',
  wechat varchar(64) NOT NULL DEFAULT '',
  source_code varchar(48) NOT NULL DEFAULT 'OTHER',
  demand varchar(1000) NOT NULL DEFAULT '',
  estimated_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  owner_user_id bigint unsigned NOT NULL,
  owner_name varchar(64) NOT NULL DEFAULT '',
  status varchar(24) NOT NULL DEFAULT 'OPEN',
  partner_id bigint unsigned DEFAULT NULL,
  contact_id bigint unsigned DEFAULT NULL,
  qualified_profile_id bigint unsigned DEFAULT NULL,
  qualified_opportunity_id bigint unsigned DEFAULT NULL,
  qualified_at timestamp NULL DEFAULT NULL,
  disqualified_reason_code varchar(48) NOT NULL DEFAULT '',
  disqualified_reason varchar(500) NOT NULL DEFAULT '',
  disqualified_at timestamp NULL DEFAULT NULL,
  reactivated_reason varchar(500) NOT NULL DEFAULT '',
  reactivated_at timestamp NULL DEFAULT NULL,
  created_by bigint unsigned NOT NULL,
  updated_by bigint unsigned NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_crm_lead_no (lead_no),
  KEY idx_crm_lead_owner_status (owner_user_id, status, updated_at),
  KEY idx_crm_lead_partner (partner_id),
  KEY idx_crm_lead_phone (phone),
  KEY idx_crm_lead_email (email),
  KEY idx_crm_lead_company (company_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM lead';

CREATE TABLE crm_customer_profile (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  partner_id bigint unsigned NOT NULL,
  lifecycle_stage varchar(24) NOT NULL DEFAULT 'PROSPECT',
  tier_code varchar(48) NOT NULL DEFAULT 'NORMAL',
  source_code varchar(48) NOT NULL DEFAULT 'OTHER',
  owner_user_id bigint unsigned DEFAULT NULL,
  owner_name varchar(64) NOT NULL DEFAULT '',
  tags varchar(500) NOT NULL DEFAULT '',
  annual_potential decimal(18,2) NOT NULL DEFAULT 0.00,
  preferred_category varchar(255) NOT NULL DEFAULT '',
  competitor varchar(255) NOT NULL DEFAULT '',
  next_action varchar(500) NOT NULL DEFAULT '',
  next_action_date date DEFAULT NULL,
  won_reason_code varchar(48) NOT NULL DEFAULT '',
  lost_reason_code varchar(48) NOT NULL DEFAULT '',
  lost_reason varchar(500) NOT NULL DEFAULT '',
  status varchar(16) NOT NULL DEFAULT 'ENABLED',
  created_by bigint unsigned NOT NULL DEFAULT 0,
  updated_by bigint unsigned NOT NULL DEFAULT 0,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_crm_customer_partner (partner_id),
  KEY idx_crm_customer_owner_stage (owner_user_id, lifecycle_stage, updated_at),
  KEY idx_crm_customer_tier (tier_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM-owned customer operating profile';

CREATE TABLE crm_opportunity (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  opportunity_no varchar(32) NOT NULL,
  partner_id bigint unsigned NOT NULL,
  contact_id bigint unsigned DEFAULT NULL,
  source_lead_id bigint unsigned DEFAULT NULL,
  title varchar(128) NOT NULL,
  stage varchar(24) NOT NULL DEFAULT 'QUALIFY',
  probability decimal(5,2) NOT NULL DEFAULT 10.00,
  estimated_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  expected_close_date date DEFAULT NULL,
  owner_user_id bigint unsigned NOT NULL,
  owner_name varchar(64) NOT NULL DEFAULT '',
  demand varchar(1000) NOT NULL DEFAULT '',
  competitor varchar(255) NOT NULL DEFAULT '',
  win_reason_code varchar(48) NOT NULL DEFAULT '',
  win_reason varchar(500) NOT NULL DEFAULT '',
  loss_reason_code varchar(48) NOT NULL DEFAULT '',
  loss_reason varchar(500) NOT NULL DEFAULT '',
  won_at timestamp NULL DEFAULT NULL,
  lost_at timestamp NULL DEFAULT NULL,
  reactivated_reason varchar(500) NOT NULL DEFAULT '',
  reactivated_at timestamp NULL DEFAULT NULL,
  created_by bigint unsigned NOT NULL,
  updated_by bigint unsigned NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_crm_opportunity_no (opportunity_no),
  KEY idx_crm_opportunity_owner_stage (owner_user_id, stage, expected_close_date),
  KEY idx_crm_opportunity_partner (partner_id, updated_at),
  KEY idx_crm_opportunity_lead (source_lead_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM opportunity';

CREATE TABLE crm_quote (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  quote_no varchar(40) NOT NULL,
  revision_no int unsigned NOT NULL DEFAULT 1,
  previous_quote_id bigint unsigned DEFAULT NULL,
  opportunity_id bigint unsigned DEFAULT NULL,
  partner_id bigint unsigned NOT NULL,
  contact_id bigint unsigned DEFAULT NULL,
  address_id bigint unsigned DEFAULT NULL,
  title varchar(255) NOT NULL DEFAULT '',
  quote_date date NOT NULL,
  valid_until date NOT NULL,
  price_includes_tax tinyint unsigned NOT NULL DEFAULT 1,
  tax_rate decimal(7,4) NOT NULL DEFAULT 0.0000,
  tax_rule_snapshot varchar(500) NOT NULL DEFAULT '',
  original_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  discount_rate decimal(6,2) NOT NULL DEFAULT 0.00,
  discount_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  adjustment_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00,
  tax_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  total_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  payment_term varchar(24) NOT NULL DEFAULT 'POSTPAID',
  shipment_payment_policy varchar(24) NOT NULL DEFAULT 'NONE',
  deposit_mode varchar(16) NOT NULL DEFAULT 'AMOUNT',
  deposit_rate decimal(6,2) NOT NULL DEFAULT 0.00,
  required_deposit_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  delivery_plan_date date DEFAULT NULL,
  memo varchar(1000) NOT NULL DEFAULT '',
  status varchar(24) NOT NULL DEFAULT 'DRAFT',
  decision_reason_code varchar(48) NOT NULL DEFAULT '',
  decision_reason varchar(500) NOT NULL DEFAULT '',
  activated_at timestamp NULL DEFAULT NULL,
  accepted_at timestamp NULL DEFAULT NULL,
  rejected_at timestamp NULL DEFAULT NULL,
  cancelled_at timestamp NULL DEFAULT NULL,
  sales_order_id bigint unsigned DEFAULT NULL,
  created_by bigint unsigned NOT NULL,
  updated_by bigint unsigned NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_crm_quote_no_revision (quote_no, revision_no),
  KEY idx_crm_quote_partner_status (partner_id, status, quote_date),
  KEY idx_crm_quote_opportunity (opportunity_id, status),
  KEY idx_crm_quote_previous (previous_quote_id),
  KEY idx_crm_quote_sales_order (sales_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Versioned CRM sales quote';

CREATE TABLE crm_quote_item (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  quote_id bigint unsigned NOT NULL,
  product_id bigint unsigned NOT NULL,
  product_sku_snapshot varchar(128) NOT NULL DEFAULT '',
  product_name_snapshot varchar(255) NOT NULL DEFAULT '',
  product_spec_snapshot varchar(1000) NOT NULL DEFAULT '',
  requested_quantity decimal(24,6) NOT NULL,
  requested_unit varchar(64) NOT NULL DEFAULT '',
  actual_base_quantity decimal(24,6) DEFAULT NULL,
  base_quantity decimal(24,6) NOT NULL,
  base_unit varchar(64) NOT NULL DEFAULT '',
  pricing_quantity decimal(24,6) NOT NULL,
  price_unit varchar(64) NOT NULL DEFAULT '',
  package_unit varchar(64) NOT NULL DEFAULT '',
  packaging_plan_json longtext NOT NULL,
  packaging_effective_revision_id bigint unsigned NOT NULL DEFAULT 0,
  packaging_snapshot_hash char(64) NOT NULL DEFAULT '',
  no_charge tinyint unsigned NOT NULL DEFAULT 0,
  unit_price decimal(18,4) NOT NULL DEFAULT 0.0000,
  tax_rate decimal(7,4) NOT NULL DEFAULT 0.0000,
  amount_excluding_tax decimal(18,2) NOT NULL DEFAULT 0.00,
  tax_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  total_amount decimal(18,2) NOT NULL DEFAULT 0.00,
  sort_no int NOT NULL DEFAULT 0,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_crm_quote_item_quote (quote_id, sort_no),
  KEY idx_crm_quote_item_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM quote frozen product and packaging lines';

CREATE TABLE crm_quote_conversion (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  quote_id bigint unsigned NOT NULL,
  opportunity_id bigint unsigned DEFAULT NULL,
  sales_order_id bigint unsigned NOT NULL,
  converted_by bigint unsigned NOT NULL,
  converted_by_name varchar(64) NOT NULL DEFAULT '',
  converted_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_crm_quote_conversion_quote (quote_id),
  UNIQUE KEY uk_crm_quote_conversion_order (sales_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Auditable optional CRM quote to sales order reference';

CREATE TABLE crm_activity (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  target_type varchar(24) NOT NULL,
  target_id bigint unsigned NOT NULL,
  partner_id bigint unsigned DEFAULT NULL,
  contact_id bigint unsigned DEFAULT NULL,
  activity_type varchar(48) NOT NULL,
  subject varchar(128) NOT NULL,
  occurred_at timestamp NOT NULL,
  duration_minutes int unsigned NOT NULL DEFAULT 0,
  outcome varchar(1000) NOT NULL DEFAULT '',
  next_action varchar(500) NOT NULL DEFAULT '',
  owner_user_id bigint unsigned NOT NULL,
  owner_name varchar(64) NOT NULL DEFAULT '',
  source_type varchar(32) NOT NULL DEFAULT 'CRM',
  source_id bigint unsigned DEFAULT NULL,
  created_by bigint unsigned NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_crm_activity_target (target_type, target_id, occurred_at),
  KEY idx_crm_activity_partner (partner_id, occurred_at),
  KEY idx_crm_activity_owner (owner_user_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Completed CRM sales activity history';

CREATE TABLE crm_followup_plan (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  target_type varchar(24) NOT NULL,
  target_id bigint unsigned NOT NULL,
  partner_id bigint unsigned DEFAULT NULL,
  contact_id bigint unsigned DEFAULT NULL,
  subject varchar(128) NOT NULL,
  plan_type varchar(48) NOT NULL,
  due_at timestamp NOT NULL,
  priority varchar(16) NOT NULL DEFAULT 'NORMAL',
  reminder_minutes int unsigned NOT NULL DEFAULT 60,
  owner_user_id bigint unsigned NOT NULL,
  owner_name varchar(64) NOT NULL DEFAULT '',
  status varchar(24) NOT NULL DEFAULT 'OPEN',
  completion_activity_id bigint unsigned DEFAULT NULL,
  cancel_reason varchar(500) NOT NULL DEFAULT '',
  completed_at timestamp NULL DEFAULT NULL,
  cancelled_at timestamp NULL DEFAULT NULL,
  created_by bigint unsigned NOT NULL,
  updated_by bigint unsigned NOT NULL,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_crm_plan_owner_status_due (owner_user_id, status, due_at),
  KEY idx_crm_plan_target (target_type, target_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM follow-up plan distinct from completed activity';

CREATE TABLE crm_domain_event (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  aggregate_type varchar(24) NOT NULL,
  aggregate_id bigint unsigned NOT NULL,
  event_type varchar(64) NOT NULL,
  event_version int unsigned NOT NULL DEFAULT 1,
  partner_id bigint unsigned DEFAULT NULL,
  actor_user_id bigint unsigned NOT NULL,
  actor_name varchar(64) NOT NULL DEFAULT '',
  payload_json json NOT NULL,
  occurred_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_crm_event_aggregate (aggregate_type, aggregate_id, id),
  KEY idx_crm_event_partner (partner_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Append-only auditable CRM domain event';

INSERT INTO crm_option(option_type,option_code,option_name,sort_no,built_in) VALUES
('LEAD_SOURCE','REFERRAL','客户转介绍',10,1),
('LEAD_SOURCE','PHONE','电话咨询',20,1),
('LEAD_SOURCE','WECHAT','微信咨询',30,1),
('LEAD_SOURCE','STORE','门店来访',40,1),
('LEAD_SOURCE','PLATFORM','平台获客',50,1),
('LEAD_SOURCE','OTHER','其他',99,1),
('CUSTOMER_TIER','KEY','重点客户',10,1),
('CUSTOMER_TIER','GROWTH','成长客户',20,1),
('CUSTOMER_TIER','NORMAL','普通客户',30,1),
('CUSTOMER_TIER','OBSERVE','观察客户',40,1),
('WIN_REASON','PRICE','价格合适',10,1),
('WIN_REASON','QUALITY','质量认可',20,1),
('WIN_REASON','DELIVERY','交付能力',30,1),
('WIN_REASON','RELATIONSHIP','服务关系',40,1),
('WIN_REASON','OTHER','其他',99,1),
('LOSS_REASON','PRICE','价格原因',10,1),
('LOSS_REASON','COMPETITOR','竞争对手',20,1),
('LOSS_REASON','DELIVERY','交付原因',30,1),
('LOSS_REASON','NO_BUDGET','暂无预算',40,1),
('LOSS_REASON','NO_DEMAND','需求取消',50,1),
('LOSS_REASON','OTHER','其他',99,1),
('ACTIVITY_TYPE','CALL','电话',10,1),
('ACTIVITY_TYPE','VISIT','拜访',20,1),
('ACTIVITY_TYPE','WECHAT','微信',30,1),
('ACTIVITY_TYPE','EMAIL','邮件',40,1),
('ACTIVITY_TYPE','MEETING','会议',50,1),
('ACTIVITY_TYPE','OTHER','其他',99,1);

INSERT INTO crm_customer_profile
(partner_id,lifecycle_stage,tier_code,source_code,owner_user_id,owner_name,tags,status,created_by,updated_by)
SELECT p.id,
       CASE WHEN p.status='ENABLED' AND cp.status='ENABLED' THEN 'ACTIVE' ELSE 'DORMANT' END,
       CASE WHEN cp.customer_level IN ('KEY','GROWTH','NORMAL','OBSERVE') THEN cp.customer_level ELSE 'NORMAL' END,
       CASE WHEN p.source='' THEN 'OTHER' ELSE p.source END,
       NULL,'',p.tags,
       CASE WHEN p.status='ENABLED' AND cp.status='ENABLED' THEN 'ENABLED' ELSE 'DISABLED' END,
       0,0
FROM partner p
JOIN partner_customer_profile cp ON cp.partner_id=p.id
WHERE (p.type_mask & 1)=1;

INSERT INTO crm_activity
(target_type,target_id,partner_id,contact_id,activity_type,subject,occurred_at,outcome,next_action,
 owner_user_id,owner_name,source_type,source_id,created_by)
SELECT 'CUSTOMER',p.partner_id,p.partner_id,p.contact_id,
       CASE WHEN p.method_type IN ('CALL','VISIT','WECHAT','EMAIL','MEETING','OTHER') THEN p.method_type
            WHEN p.followup_type='PHONE' THEN 'CALL' WHEN p.followup_type='VISIT' THEN 'VISIT' ELSE 'OTHER' END,
       '历史客户跟进',p.occurred_at,p.memo,
       CASE WHEN p.next_follow_date IS NULL THEN '' ELSE CONCAT('原计划下次跟进：',p.next_follow_date) END,
       0,'历史导入','LEGACY_FOLLOWUP',p.id,0
FROM partner_followup p;

INSERT INTO crm_followup_plan
(target_type,target_id,partner_id,contact_id,subject,plan_type,due_at,priority,reminder_minutes,
 owner_user_id,owner_name,status,created_by,updated_by)
SELECT 'CUSTOMER',p.partner_id,p.partner_id,p.contact_id,'历史跟进计划',
       CASE WHEN p.method_type IN ('CALL','VISIT','WECHAT','EMAIL','MEETING','OTHER') THEN p.method_type ELSE 'OTHER' END,
       TIMESTAMP(p.next_follow_date,'09:00:00'),'NORMAL',60,0,'历史导入','OPEN',0,0
FROM partner_followup p
WHERE p.next_follow_date IS NOT NULL AND p.status='OPEN';

INSERT IGNORE INTO security_permission
(code,module_code,module_name,permission_type,name,description,sort_no,status) VALUES
('crm.record.view','crm','客户经营 CRM','MENU','查看 CRM','查看线索、客户经营、商机、报价、活动和计划',115,'ENABLED'),
('crm.record.manage','crm','客户经营 CRM','ACTION','维护 CRM','维护本人有权处理的 CRM 业务记录',116,'ENABLED'),
('crm.record.view.all','crm','客户经营 CRM','FIELD','查看全部 CRM','跨负责人查看全部 CRM 记录',117,'ENABLED'),
('crm.quote.convert','crm','客户经营 CRM','ACTION','报价转销售草稿','将已接受报价按冻结快照转换为销售订单草稿',118,'ENABLED'),
('crm.analysis.view','crm','客户经营 CRM','FIELD','查看客户经营分析','只读查看交易、欠款、毛利和最近业务汇总',119,'ENABLED'),
('crm.option.manage','crm','客户经营 CRM','ACTION','维护 CRM 选项','维护来源、分层、胜负原因和活动类型',120,'ENABLED');

INSERT IGNORE INTO security_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code IN ('ADMIN','OWNER','MANAGER') AND p.code LIKE 'crm.%';

INSERT IGNORE INTO security_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code='SALES' AND p.code IN
('crm.record.view','crm.record.manage','crm.quote.convert','crm.analysis.view');

INSERT IGNORE INTO security_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM security_role r JOIN security_permission p
WHERE r.code='FINANCE' AND p.code IN ('crm.record.view','crm.analysis.view');
