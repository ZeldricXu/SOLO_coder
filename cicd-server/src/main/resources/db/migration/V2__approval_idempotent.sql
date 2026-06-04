ALTER TABLE approval_decisions ADD CONSTRAINT uk_approval_decision_approver UNIQUE (approval_id, approver);
ALTER TABLE approval_decisions RENAME COLUMN decision TO status;
ALTER TABLE approval_decisions ALTER COLUMN decided_at DROP NOT NULL;
