INSERT INTO financial_report_account_map
  (entity_id,report_type,line_id,account_id,amount_sign,effective_from)
SELECT e.id,'BALANCE_SHEET',l.id,a.id,1.0000,'2026-01-01'
FROM accounting_entity e
JOIN financial_report_line l ON l.entity_id=e.id AND l.report_type='BALANCE_SHEET'
  AND l.line_code='BS_OTHER_LIABILITY' AND l.effective_from='2026-01-01'
JOIN accounting_account a ON a.entity_id=e.id
  AND a.account_code IN ('22210101','22210102','22210103')
WHERE e.entity_code='DEFAULT';
