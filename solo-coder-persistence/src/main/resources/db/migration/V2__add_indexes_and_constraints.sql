-- V2__add_indexes_and_constraints.sql
-- Add additional indexes and constraints for performance

ALTER TABLE t_log_level_config ADD INDEX idx_scope (scope);
ALTER TABLE t_log_level_config ADD INDEX idx_created_at (created_at);

ALTER TABLE t_notification_template ADD INDEX idx_channel (channel);

ALTER TABLE t_task_execution ADD INDEX idx_status (status);
ALTER TABLE t_task_execution ADD INDEX idx_started_at (started_at);

ALTER TABLE t_gpu_resource ADD INDEX idx_status (status);

ALTER TABLE t_prompt_version ADD INDEX idx_version_number (prompt_id, version_number);

ALTER TABLE t_experiment ADD INDEX idx_status (status);
ALTER TABLE t_experiment ADD INDEX idx_prompt_id (prompt_id);

ALTER TABLE t_feature_definition ADD INDEX idx_type (type);
ALTER TABLE t_feature_definition ADD INDEX idx_owner (owner);

ALTER TABLE t_model_provider ADD INDEX idx_status (status);
ALTER TABLE t_model_provider ADD INDEX idx_priority (priority);
