ALTER TABLE treasury_document
  ADD COLUMN input_tax_treatment varchar(24) NOT NULL DEFAULT 'DEDUCTIBLE' AFTER tax_rule_snapshot,
  ADD CONSTRAINT chk_treasury_input_tax_treatment CHECK (
    input_tax_treatment IN ('DEDUCTIBLE','INCLUDED_IN_COST')
  );
