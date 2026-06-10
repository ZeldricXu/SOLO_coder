-- Migration 001: Add API Key plan and approval fields
-- Description: Add plan_id, application_note, approval_note, rejection_reason, contact_email fields to api_keys table
-- Created for: Developer Portal API Key self-service management enhancement

BEGIN;

ALTER TABLE api_keys
    ADD COLUMN IF NOT EXISTS plan_id VARCHAR(50);

ALTER TABLE api_keys
    ADD COLUMN IF NOT EXISTS application_note TEXT;

ALTER TABLE api_keys
    ADD COLUMN IF NOT EXISTS approval_note TEXT;

ALTER TABLE api_keys
    ADD COLUMN IF NOT EXISTS rejection_reason TEXT;

ALTER TABLE api_keys
    ADD COLUMN IF NOT EXISTS contact_email VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_api_keys_plan_id ON api_keys(plan_id);
CREATE INDEX IF NOT EXISTS idx_api_keys_contact_email ON api_keys(contact_email);

COMMENT ON COLUMN api_keys.plan_id IS 'ID of the API key plan/tier selected by the developer';
COMMENT ON COLUMN api_keys.application_note IS 'Application note provided by the developer when requesting the API key';
COMMENT ON COLUMN api_keys.approval_note IS 'Note added by the administrator when approving the API key';
COMMENT ON COLUMN api_keys.rejection_reason IS 'Reason provided by the administrator when rejecting the API key';
COMMENT ON COLUMN api_keys.contact_email IS 'Contact email for the developer who owns this API key';

COMMIT;
