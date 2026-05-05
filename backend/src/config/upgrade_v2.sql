-- ============================================
-- 代码审查平台 V2.0 数据库升级脚本
-- 新增功能：用户活跃度追踪、规则配置管理
-- ============================================

-- 1. 用户表扩展：添加活跃度追踪字段
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_activity_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS total_reviews INTEGER DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS completed_reviews INTEGER DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS avg_completion_hours NUMERIC DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_active BOOLEAN DEFAULT TRUE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS preferences JSONB DEFAULT '{}'::jsonb;

-- 2. 创建规则配置表
CREATE TABLE IF NOT EXISTS rule_configs (
  id SERIAL PRIMARY KEY,
  config_type VARCHAR(32) NOT NULL DEFAULT 'project',
  scope_type VARCHAR(32) NOT NULL DEFAULT 'global',
  scope_value VARCHAR(64),
  language VARCHAR(32) NOT NULL,
  rule_id VARCHAR(128) NOT NULL,
  rule_name VARCHAR(255),
  severity VARCHAR(32) DEFAULT 'warn',
  is_enabled BOOLEAN DEFAULT TRUE,
  rule_options JSONB DEFAULT '{}'::jsonb,
  is_override BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(scope_type, scope_value, language, rule_id)
);

-- 3. 创建全局规则配置视图
CREATE OR REPLACE VIEW global_rule_configs AS
SELECT * FROM rule_configs 
WHERE scope_type = 'global' AND is_enabled = TRUE;

-- 4. 创建项目规则配置视图
CREATE OR REPLACE VIEW project_rule_configs AS
SELECT * FROM rule_configs 
WHERE scope_type = 'project' AND is_enabled = TRUE;

-- 5. 为规则配置表创建索引
CREATE INDEX IF NOT EXISTS idx_rule_configs_scope ON rule_configs(scope_type, scope_value);
CREATE INDEX IF NOT EXISTS idx_rule_configs_language ON rule_configs(language);
CREATE INDEX IF NOT EXISTS idx_rule_configs_rule_id ON rule_configs(rule_id);

-- 6. 插入默认的全局规则配置
INSERT INTO rule_configs (config_type, scope_type, scope_value, language, rule_id, rule_name, severity, is_enabled, rule_options) VALUES
-- Python 规则
('lint', 'global', NULL, 'python', 'E', '错误级别问题', 'error', TRUE, '{}'),
('lint', 'global', NULL, 'python', 'F', '致命错误', 'error', TRUE, '{}'),
('lint', 'global', NULL, 'python', 'W', '警告级别问题', 'warn', TRUE, '{}'),
('lint', 'global', NULL, 'python', 'C', '代码规范问题', 'info', TRUE, '{"max-line-length": 100}'),
('lint', 'global', NULL, 'python', 'R', '重构建议', 'info', TRUE, '{}'),

-- JavaScript/TypeScript 规则
('lint', 'global', NULL, 'javascript', 'no-console', '禁止使用console', 'warn', TRUE, '{}'),
('lint', 'global', NULL, 'javascript', 'no-unused-vars', '禁止未使用变量', 'warn', TRUE, '{}'),
('lint', 'global', NULL, 'javascript', 'semi', '分号使用规则', 'error', TRUE, '{"require": true}'),
('lint', 'global', NULL, 'javascript', 'quotes', '引号使用规则', 'warn', TRUE, '{"style": "single"}'),
('lint', 'global', NULL, 'javascript', 'indent', '缩进规则', 'warn', TRUE, '{"size": 2}'),
('lint', 'global', NULL, 'javascript', 'no-multi-spaces', '禁止多余空格', 'warn', TRUE, '{}'),
('lint', 'global', NULL, 'javascript', 'no-trailing-spaces', '禁止行尾空格', 'warn', TRUE, '{}'),
('lint', 'global', NULL, 'javascript', 'eol-last', '文件末尾换行', 'warn', TRUE, '{}'),

-- TypeScript 规则
('lint', 'global', NULL, 'typescript', 'no-console', '禁止使用console', 'warn', TRUE, '{}'),
('lint', 'global', NULL, 'typescript', 'no-unused-vars', '禁止未使用变量', 'warn', TRUE, '{}'),
('lint', 'global', NULL, 'typescript', 'semi', '分号使用规则', 'error', TRUE, '{"require": true}'),
('lint', 'global', NULL, 'typescript', 'quotes', '引号使用规则', 'warn', TRUE, '{"style": "single"}'),

-- Go 规则
('lint', 'global', NULL, 'go', 'golint', 'Go代码规范', 'warn', TRUE, '{}'),
('lint', 'global', NULL, 'go', 'govet', 'Go静态检查', 'error', TRUE, '{}'),
('lint', 'global', NULL, 'go', 'gofmt', 'Go格式检查', 'warn', TRUE, '{}')

ON CONFLICT (scope_type, scope_value, language, rule_id) DO UPDATE SET
  rule_name = EXCLUDED.rule_name,
  severity = EXCLUDED.severity,
  is_enabled = EXCLUDED.is_enabled,
  rule_options = EXCLUDED.rule_options,
  updated_at = CURRENT_TIMESTAMP;

-- 7. 更新现有用户的活跃度字段
UPDATE users SET 
  last_login_at = created_at,
  last_activity_at = created_at,
  is_active = TRUE
WHERE last_login_at IS NULL;

-- 8. 为用户表创建活跃度索引
CREATE INDEX IF NOT EXISTS idx_users_is_active ON users(is_active);
CREATE INDEX IF NOT EXISTS idx_users_last_activity ON users(last_activity_at);
CREATE INDEX IF NOT EXISTS idx_users_role ON users(role);

-- 9. 完成消息
DO $$
BEGIN
  RAISE NOTICE '数据库升级完成！新增特性：';
  RAISE NOTICE '1. 用户表扩展：活跃度追踪字段';
  RAISE NOTICE '2. 规则配置表：支持全局/项目级规则覆盖';
  RAISE NOTICE '3. 默认全局规则配置已初始化';
END $$;
