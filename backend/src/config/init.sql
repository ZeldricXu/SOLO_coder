-- 代码提交表
CREATE TABLE IF NOT EXISTS commits (
  commit_id VARCHAR(64) PRIMARY KEY,
  repo_id VARCHAR(64) NOT NULL,
  author VARCHAR(255) NOT NULL,
  commit_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  message TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 代码变更文件表
CREATE TABLE IF NOT EXISTS changed_files (
  id SERIAL PRIMARY KEY,
  commit_id VARCHAR(64) NOT NULL REFERENCES commits(commit_id) ON DELETE CASCADE,
  file_path VARCHAR(512) NOT NULL,
  file_content TEXT,
  old_content TEXT,
  file_type VARCHAR(32),
  language VARCHAR(32),
  status VARCHAR(32) DEFAULT 'modified',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(commit_id, file_path)
);

-- 复杂度分析结果表
CREATE TABLE IF NOT EXISTS complexity_analysis (
  analysis_id VARCHAR(64) PRIMARY KEY,
  commit_id VARCHAR(64) NOT NULL REFERENCES commits(commit_id) ON DELETE CASCADE,
  overall_score INTEGER DEFAULT 0,
  analyzed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  status VARCHAR(32) DEFAULT 'pending'
);

-- 文件复杂度分析结果表
CREATE TABLE IF NOT EXISTS file_complexity (
  id SERIAL PRIMARY KEY,
  analysis_id VARCHAR(64) NOT NULL REFERENCES complexity_analysis(analysis_id) ON DELETE CASCADE,
  file_path VARCHAR(512) NOT NULL,
  language VARCHAR(32),
  total_functions INTEGER DEFAULT 0,
  avg_cyclomatic NUMERIC DEFAULT 0,
  complexity_score INTEGER DEFAULT 0,
  status VARCHAR(32) DEFAULT 'acceptable',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 函数复杂度分析结果表
CREATE TABLE IF NOT EXISTS function_complexity (
  id SERIAL PRIMARY KEY,
  file_complexity_id INTEGER NOT NULL REFERENCES file_complexity(id) ON DELETE CASCADE,
  function_name VARCHAR(255) NOT NULL,
  cyclomatic INTEGER DEFAULT 0,
  lines INTEGER DEFAULT 0,
  params INTEGER DEFAULT 0,
  is_above_threshold BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 规范检测结果表
CREATE TABLE IF NOT EXISTS lint_results (
  id SERIAL PRIMARY KEY,
  commit_id VARCHAR(64) NOT NULL REFERENCES commits(commit_id) ON DELETE CASCADE,
  file_path VARCHAR(512) NOT NULL,
  rule_id VARCHAR(128),
  severity VARCHAR(32),
  line INTEGER,
  column INTEGER,
  message TEXT,
  source TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 重复代码检测结果表
CREATE TABLE IF NOT EXISTS duplicate_results (
  id SERIAL PRIMARY KEY,
  commit_id VARCHAR(64) NOT NULL REFERENCES commits(commit_id) ON DELETE CASCADE,
  file_path1 VARCHAR(512) NOT NULL,
  file_path2 VARCHAR(512) NOT NULL,
  similarity NUMERIC DEFAULT 0,
  lines_count INTEGER DEFAULT 0,
  fragment1 TEXT,
  fragment2 TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 审查任务表
CREATE TABLE IF NOT EXISTS review_tasks (
  task_id VARCHAR(64) PRIMARY KEY,
  commit_id VARCHAR(64) NOT NULL REFERENCES commits(commit_id) ON DELETE CASCADE,
  assignee VARCHAR(255),
  title VARCHAR(512),
  description TEXT,
  status VARCHAR(32) DEFAULT 'pending',
  priority VARCHAR(32) DEFAULT 'medium',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP
);

-- 审查意见表
CREATE TABLE IF NOT EXISTS comments (
  comment_id VARCHAR(64) PRIMARY KEY,
  commit_id VARCHAR(64) NOT NULL REFERENCES commits(commit_id) ON DELETE CASCADE,
  file_path VARCHAR(512) NOT NULL,
  line_start INTEGER NOT NULL,
  line_end INTEGER,
  comment_type VARCHAR(32) DEFAULT 'comment',
  content TEXT NOT NULL,
  author VARCHAR(255) NOT NULL,
  status VARCHAR(32) DEFAULT 'open',
  parent_comment_id VARCHAR(64) REFERENCES comments(comment_id) ON DELETE CASCADE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 质量报告表
CREATE TABLE IF NOT EXISTS quality_reports (
  report_id VARCHAR(64) PRIMARY KEY,
  repo_id VARCHAR(64) NOT NULL,
  commit_id VARCHAR(64) REFERENCES commits(commit_id) ON DELETE CASCADE,
  overall_score INTEGER DEFAULT 0,
  complexity_score INTEGER DEFAULT 0,
  lint_score INTEGER DEFAULT 0,
  duplicate_score INTEGER DEFAULT 0,
  total_issues INTEGER DEFAULT 0,
  resolved_issues INTEGER DEFAULT 0,
  generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  report_data JSONB
);

-- 项目配置表
CREATE TABLE IF NOT EXISTS project_configs (
  id SERIAL PRIMARY KEY,
  repo_id VARCHAR(64) NOT NULL UNIQUE,
  config_name VARCHAR(255) NOT NULL,
  config_value TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 用户表
CREATE TABLE IF NOT EXISTS users (
  user_id VARCHAR(64) PRIMARY KEY,
  username VARCHAR(255) NOT NULL UNIQUE,
  email VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  role VARCHAR(32) DEFAULT 'developer',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_commits_repo_id ON commits(repo_id);
CREATE INDEX IF NOT EXISTS idx_changed_files_commit_id ON changed_files(commit_id);
CREATE INDEX IF NOT EXISTS idx_complexity_analysis_commit_id ON complexity_analysis(commit_id);
CREATE INDEX IF NOT EXISTS idx_file_complexity_analysis_id ON file_complexity(analysis_id);
CREATE INDEX IF NOT EXISTS idx_function_complexity_file_id ON function_complexity(file_complexity_id);
CREATE INDEX IF NOT EXISTS idx_lint_results_commit_id ON lint_results(commit_id);
CREATE INDEX IF NOT EXISTS idx_review_tasks_commit_id ON review_tasks(commit_id);
CREATE INDEX IF NOT EXISTS idx_comments_commit_id ON comments(commit_id);
CREATE INDEX IF NOT EXISTS idx_comments_file_path ON comments(commit_id, file_path);
CREATE INDEX IF NOT EXISTS idx_quality_reports_repo_id ON quality_reports(repo_id);
