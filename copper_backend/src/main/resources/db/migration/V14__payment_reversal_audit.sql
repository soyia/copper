ALTER TABLE payment
  ADD COLUMN reversal_no varchar(64) NOT NULL DEFAULT '' AFTER verification_status,
  ADD COLUMN reversal_reason varchar(500) NOT NULL DEFAULT '' AFTER reversal_no,
  ADD COLUMN reversed_at timestamp NULL DEFAULT NULL AFTER reversal_reason;

ALTER TABLE supplier_payment
  ADD COLUMN reversal_no varchar(64) NOT NULL DEFAULT '' AFTER verification_status,
  ADD COLUMN reversal_reason varchar(500) NOT NULL DEFAULT '' AFTER reversal_no,
  ADD COLUMN reversed_at timestamp NULL DEFAULT NULL AFTER reversal_reason;

ALTER TABLE inbound_order
  ADD COLUMN reversal_no varchar(64) NOT NULL DEFAULT '' AFTER rejection_reason,
  ADD COLUMN reversal_reason varchar(500) NOT NULL DEFAULT '' AFTER reversal_no,
  ADD COLUMN reversed_at timestamp NULL DEFAULT NULL AFTER reversal_reason;
