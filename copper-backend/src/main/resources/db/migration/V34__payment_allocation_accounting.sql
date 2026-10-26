-- ERP-01 accounting split: cash movement and document allocation are separate facts.
-- Unallocated ordinary funds use clearing accounting facts and never enter the
-- operational customer-advance/supplier-prepayment balance pools.

INSERT INTO business_posting_rule_version
  (rule_code,rule_name,source_type,posting_action,version_no,effective_from,rule_json,status,published_at)
VALUES
  ('RECEIPT_POST','客户收款资金与核销过账','PAYMENT','POST',2,'2026-01-01',
   '{"facts":["RECEIVABLE_SETTLEMENT","UNALLOCATED_CUSTOMER_RECEIPT"]}','ENABLED',CURRENT_TIMESTAMP),
  ('SUPPLIER_PAYMENT_POST','供应商付款资金与核销过账','SUPPLIER_PAYMENT','POST',2,'2026-01-01',
   '{"facts":["PAYABLE_SETTLEMENT","UNALLOCATED_SUPPLIER_PAYMENT"]}','ENABLED',CURRENT_TIMESTAMP),
  ('RECEIPT_ALLOCATION_CHANGE','客户收款未分配与应收重分类','PAYMENT','ALLOCATION_CHANGE',1,'2026-01-01',
   '{"facts":["CUSTOMER_RECEIPT_ALLOCATION"],"cashImpact":false}','ENABLED',CURRENT_TIMESTAMP),
  ('SUPPLIER_PAYMENT_ALLOCATION_CHANGE','供应商付款未分配与应付重分类','SUPPLIER_PAYMENT','ALLOCATION_CHANGE',1,'2026-01-01',
   '{"facts":["SUPPLIER_PAYMENT_ALLOCATION"],"cashImpact":false}','ENABLED',CURRENT_TIMESTAMP);

INSERT INTO accounting_rule_version
  (entity_id,rule_code,rule_name,source_type,posting_action,fact_type,fact_direction,
   debit_account_id,credit_account_id,version_no,effective_from,status,change_reason,published_at)
SELECT e.id,r.rule_code,r.rule_name,r.source_type,r.posting_action,r.fact_type,r.fact_direction,
       da.id,ca.id,1,'2026-01-01','ENABLED','ERP-01 资金头与核销分配会计事实分层',CURRENT_TIMESTAMP
FROM accounting_entity e CROSS JOIN (
  SELECT 'RECEIPT_UNALLOCATED' rule_code,'客户普通收款未分配' rule_name,'PAYMENT' source_type,'POST' posting_action,
         'UNALLOCATED_CUSTOMER_RECEIPT' fact_type,'INCREASE' fact_direction,'1002' debit_code,'2203' credit_code UNION ALL
  SELECT 'PAYMENT_UNALLOCATED','供应商普通付款未分配','SUPPLIER_PAYMENT','POST',
         'UNALLOCATED_SUPPLIER_PAYMENT','INCREASE','1123','1002' UNION ALL
  SELECT 'RECEIPT_ALLOCATE','客户收款增加应收核销','PAYMENT','ALLOCATION_CHANGE',
         'CUSTOMER_RECEIPT_ALLOCATION','INCREASE','2203','1122' UNION ALL
  SELECT 'RECEIPT_RELEASE','客户收款释放应收核销','PAYMENT','ALLOCATION_CHANGE',
         'CUSTOMER_RECEIPT_ALLOCATION','DECREASE','1122','2203' UNION ALL
  SELECT 'PAYMENT_ALLOCATE','供应商付款增加应付核销','SUPPLIER_PAYMENT','ALLOCATION_CHANGE',
         'SUPPLIER_PAYMENT_ALLOCATION','INCREASE','2202','1123' UNION ALL
  SELECT 'PAYMENT_RELEASE','供应商付款释放应付核销','SUPPLIER_PAYMENT','ALLOCATION_CHANGE',
         'SUPPLIER_PAYMENT_ALLOCATION','DECREASE','1123','2202'
) r
JOIN accounting_account da ON da.entity_id=e.id AND da.account_code=r.debit_code
JOIN accounting_account ca ON ca.entity_id=e.id AND ca.account_code=r.credit_code
WHERE e.status='ENABLED';
