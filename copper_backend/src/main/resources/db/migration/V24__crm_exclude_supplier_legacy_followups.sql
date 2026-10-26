-- Correct the V23 legacy copy boundary: supplier development/follow-up belongs
-- to purchasing/SRM, not to the independent customer CRM domain. The original
-- partner_followup rows remain intact; only the derived CRM copies are removed.
DELETE a
FROM crm_activity a
JOIN partner p ON p.id=a.partner_id
WHERE a.source_type='LEGACY_FOLLOWUP'
  AND (p.type_mask & 1)=0;

DELETE fp
FROM crm_followup_plan fp
JOIN partner p ON p.id=fp.partner_id
WHERE fp.created_by=0
  AND fp.owner_user_id=0
  AND fp.subject='历史跟进计划'
  AND (p.type_mask & 1)=0;
