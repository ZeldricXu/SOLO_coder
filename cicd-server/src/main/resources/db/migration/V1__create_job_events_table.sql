CREATE TABLE IF NOT EXISTS job_events (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL,
    job_token VARCHAR(64) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    step_index INT,
    step_name VARCHAR(200),
    step_status VARCHAR(20),
    runner_id BIGINT,
    log_increment TEXT,
    log_offset INT,
    exit_code INT,
    error_message TEXT,
    event_timestamp TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_job_events_job_id ON job_events(job_id, event_timestamp ASC);
CREATE INDEX IF NOT EXISTS idx_job_events_token ON job_events(job_token);

ALTER TABLE artifacts ADD COLUMN IF NOT EXISTS cleanup_status VARCHAR(20) DEFAULT 'NONE';

CREATE INDEX IF NOT EXISTS idx_artifacts_cleanup_status ON artifacts(cleanup_status);
