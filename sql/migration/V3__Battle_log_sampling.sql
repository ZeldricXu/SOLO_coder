-- =============================================
-- 战斗日志采样优化
-- 新增采样相关字段，支持TTL清理
-- =============================================

ALTER TABLE battle_logs
ADD COLUMN is_boss_battle TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否Boss战',
ADD COLUMN sampling_level VARCHAR(16) DEFAULT 'FULL' COMMENT '采样级别: FULL-完整, SAMPLED-采样',
ADD COLUMN log_timestamp BIGINT COMMENT '日志时间戳(用于TTL清理)',
ADD INDEX idx_is_boss_battle (is_boss_battle),
ADD INDEX idx_log_timestamp (log_timestamp);

-- 为现有数据填充log_timestamp（使用start_time）
UPDATE battle_logs SET log_timestamp = start_time WHERE log_timestamp IS NULL;

-- 为现有数据填充is_boss_battle（每10层是Boss）
UPDATE battle_logs SET is_boss_battle = 1 WHERE floor > 0 AND floor % 10 = 0;
UPDATE battle_logs SET is_boss_battle = 0 WHERE floor = 0 OR floor % 10 != 0;

-- 为现有数据填充sampling_level
UPDATE battle_logs SET sampling_level = 'FULL' WHERE is_boss_battle = 1;
UPDATE battle_logs SET sampling_level = 'SAMPLED' WHERE is_boss_battle = 0;
