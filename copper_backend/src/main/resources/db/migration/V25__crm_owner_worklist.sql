-- CRM should assist small-business follow-up without forcing a sales funnel.
-- Repurchase reminders are optional and customer-specific so that a generic
-- system interval never blocks or dictates the real sales process.
ALTER TABLE crm_customer_profile
  ADD COLUMN repurchase_reminder_enabled tinyint(1) NOT NULL DEFAULT 1
    COMMENT 'Whether CRM may suggest a repurchase follow-up' AFTER next_action_date,
  ADD COLUMN repurchase_cycle_days int NOT NULL DEFAULT 30
    COMMENT 'Customer-specific suggested repurchase interval' AFTER repurchase_reminder_enabled,
  ADD CONSTRAINT chk_crm_customer_repurchase_cycle
    CHECK (repurchase_cycle_days BETWEEN 1 AND 3650);
