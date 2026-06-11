-- ============================================================
-- 迁移 002_enhancements.sql
-- 功能：AI规则配置、图片附件、物化视图统计
-- 保持向后兼容，不修改现有表结构
-- ============================================================

-- ------------------------------------------------------------
-- 1. AI规则配置表 ai_rules
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_rules (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    repo_id UUID REFERENCES repositories(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    scope VARCHAR(32) NOT NULL CHECK (scope IN ('organization', 'repository')),
    severity_level VARCHAR(16) NOT NULL CHECK (severity_level IN ('strict', 'normal', 'loose')),
    custom_prompt TEXT NOT NULL,
    enabled_categories JSONB NOT NULL DEFAULT '[]'::jsonb,
    min_changed_lines INTEGER NOT NULL DEFAULT 3,
    context_lines INTEGER NOT NULL DEFAULT 5,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN ai_rules.created_by IS '创建人用户ID，可空（向后兼容）';

-- 索引：按组织和仓库查询
CREATE INDEX IF NOT EXISTS idx_ai_rules_organization_repo
    ON ai_rules(organization_id, repo_id);

-- 索引：按组织和启用状态查询
CREATE INDEX IF NOT EXISTS idx_ai_rules_organization_active
    ON ai_rules(organization_id, is_active);

-- 部分唯一约束：一个组织只能有一个组织级默认规则
CREATE UNIQUE INDEX IF NOT EXISTS idx_ai_rules_one_default_per_org
    ON ai_rules(organization_id)
    WHERE is_default = TRUE AND repo_id IS NULL;

-- 触发器：自动更新 updated_at 时间戳
CREATE OR REPLACE FUNCTION ai_rules_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ai_rules_updated_at ON ai_rules;
CREATE TRIGGER trg_ai_rules_updated_at
    BEFORE UPDATE ON ai_rules
    FOR EACH ROW
    EXECUTE FUNCTION ai_rules_set_updated_at();


-- ------------------------------------------------------------
-- 2. 图片附件表 attachments
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS attachments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID REFERENCES organizations(id) ON DELETE SET NULL,
    attachment_type VARCHAR(32) NOT NULL CHECK (attachment_type IN ('comment', 'issue', 'ai_suggestion')),
    target_id UUID NOT NULL,
    uploader_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    file_name VARCHAR(512) NOT NULL,
    storage_key VARCHAR(1024) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    width INTEGER,
    height INTEGER,
    thumbnail_key VARCHAR(1024),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN attachments.organization_id IS '组织ID，可空（向后兼容），可通过uploader关联查询';

-- 索引：按附件类型和目标ID查询（获取某评论/问题/AI建议的所有附件）
CREATE INDEX IF NOT EXISTS idx_attachments_type_target
    ON attachments(attachment_type, target_id);

-- 索引：按上传者和创建时间查询（用户上传历史）
CREATE INDEX IF NOT EXISTS idx_attachments_uploader_created
    ON attachments(uploader_id, created_at DESC);

-- 索引：按组织查询（管理用途）
CREATE INDEX IF NOT EXISTS idx_attachments_organization
    ON attachments(organization_id);


-- ------------------------------------------------------------
-- 3. 物化视图：每日仓库健康度 mv_repo_health_daily
-- ------------------------------------------------------------
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_repo_health_daily AS
WITH mr_daily AS (
    -- 每个仓库每日创建的MR统计
    SELECT
        r.organization_id,
        r.id AS repo_id,
        r.name AS repo_name,
        DATE(mr.created_at) AS mr_date,
        COUNT(*) AS daily_total_mrs,
        COUNT(*) FILTER (WHERE mr.status IN ('reviewing', 'approved', 'changes_requested', 'merged', 'closed')) AS daily_reviewed_mrs,
        COUNT(*) FILTER (WHERE mr.status IN ('open', 'reviewing', 'changes_requested')) AS daily_active_mrs
    FROM repositories r
    LEFT JOIN merge_requests mr ON mr.repo_id = r.id
    GROUP BY r.organization_id, r.id, r.name, DATE(mr.created_at)
),
mr_response_times AS (
    -- 每个MR的首次响应时间（创建到第一条评论）
    SELECT
        mr.repo_id,
        DATE(mr.created_at) AS mr_date,
        EXTRACT(EPOCH FROM (MIN(c.created_at) - mr.created_at)) / 3600.0 AS response_hours
    FROM merge_requests mr
    LEFT JOIN comments c ON c.merge_request_id = mr.id
    GROUP BY mr.repo_id, mr.id, mr.created_at
),
issue_daily AS (
    -- 每个仓库每日创建的问题统计
    SELECT
        r.organization_id,
        r.id AS repo_id,
        DATE(i.created_at) AS issue_date,
        COUNT(*) AS daily_total_issues,
        COUNT(*) FILTER (WHERE i.severity = 'critical') AS daily_critical_issues
    FROM repositories r
    LEFT JOIN merge_requests mr ON mr.repo_id = r.id
    LEFT JOIN issues i ON i.merge_request_id = mr.id
    GROUP BY r.organization_id, r.id, DATE(i.created_at)
),
all_dates AS (
    -- 汇总日期维度
    SELECT mr_date AS stat_date, organization_id, repo_id, repo_name FROM mr_daily
    UNION
    SELECT issue_date AS stat_date, organization_id, repo_id, repo_name FROM issue_daily WHERE issue_date IS NOT NULL
),
distinct_dates AS (
    SELECT DISTINCT stat_date, organization_id, repo_id, repo_name FROM all_dates WHERE stat_date IS NOT NULL
)
SELECT
    dd.stat_date,
    dd.organization_id,
    dd.repo_id,
    dd.repo_name,
    COALESCE(m.daily_total_mrs, 0) AS total_mrs,
    COALESCE(m.daily_reviewed_mrs, 0) AS reviewed_mrs,
    CASE
        WHEN COALESCE(m.daily_total_mrs, 0) > 0
        THEN ROUND((m.daily_reviewed_mrs::FLOAT8 / m.daily_total_mrs)::numeric, 4)
        ELSE 0
    END AS coverage_rate,
    COALESCE((
        SELECT ROUND(AVG(rt.response_hours)::numeric, 2)
        FROM mr_response_times rt
        WHERE rt.repo_id = dd.repo_id AND rt.mr_date = dd.stat_date AND rt.response_hours IS NOT NULL
    ), 0) AS avg_response_time_hours,
    COALESCE(i.daily_total_issues, 0) AS total_issues,
    COALESCE(i.daily_critical_issues, 0) AS critical_issues,
    CASE
        WHEN COALESCE(m.daily_total_mrs, 0) > 0
        THEN ROUND((i.daily_total_issues::FLOAT8 / m.daily_total_mrs)::numeric, 4)
        ELSE 0
    END AS issue_density,
    COALESCE(m.daily_active_mrs, 0) AS active_mrs,
    ROUND((
        (CASE WHEN COALESCE(m.daily_total_mrs, 0) > 0 THEN m.daily_reviewed_mrs::FLOAT8 / m.daily_total_mrs ELSE 0 END) * 0.4 +
        GREATEST(0, 1 - (CASE WHEN COALESCE(m.daily_total_mrs, 0) > 0 THEN i.daily_total_issues::FLOAT8 / m.daily_total_mrs ELSE 0 END) / 10.0) * 0.3 +
        GREATEST(0, 1 - COALESCE((
            SELECT AVG(rt.response_hours)
            FROM mr_response_times rt
            WHERE rt.repo_id = dd.repo_id AND rt.mr_date = dd.stat_date AND rt.response_hours IS NOT NULL
        ), 0) / 72.0) * 0.3
    )::numeric, 4) AS health_score
FROM distinct_dates dd
LEFT JOIN mr_daily m ON m.repo_id = dd.repo_id AND m.mr_date = dd.stat_date
LEFT JOIN issue_daily i ON i.repo_id = dd.repo_id AND i.issue_date = dd.stat_date
WITH DATA;

-- 主键索引
CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_repo_health_daily_pk
    ON mv_repo_health_daily(stat_date, repo_id);

-- 辅助索引：按组织查询
CREATE INDEX IF NOT EXISTS idx_mv_repo_health_daily_org
    ON mv_repo_health_daily(organization_id, stat_date DESC);

-- 辅助索引：按健康分排序
CREATE INDEX IF NOT EXISTS idx_mv_repo_health_daily_score
    ON mv_repo_health_daily(health_score DESC);


-- ------------------------------------------------------------
-- 4. 物化视图：每日成员贡献度 mv_contributor_stats_daily
-- ------------------------------------------------------------
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_contributor_stats_daily AS
WITH user_orgs AS (
    -- 用户所属组织（通过团队成员关联）
    SELECT DISTINCT
        t.organization_id,
        tm.user_id
    FROM teams t
    JOIN team_members tm ON tm.team_id = t.id
    UNION
    -- 组织所有者
    SELECT
        o.id AS organization_id,
        o.owner_id AS user_id
    FROM organizations o
),
review_comments AS (
    -- 用户的评审评论（非自己MR的评论视为评审）
    SELECT
        uo.organization_id,
        c.author_id AS user_id,
        DATE(c.created_at) AS comment_date,
        COUNT(*) AS review_count
    FROM comments c
    JOIN user_orgs uo ON uo.user_id = c.author_id
    JOIN merge_requests mr ON mr.id = c.merge_request_id
    WHERE c.author_id != mr.author_id
    GROUP BY uo.organization_id, c.author_id, DATE(c.created_at)
),
all_comments AS (
    -- 用户的所有评论
    SELECT
        uo.organization_id,
        c.author_id AS user_id,
        DATE(c.created_at) AS comment_date,
        COUNT(*) AS comment_count
    FROM comments c
    JOIN user_orgs uo ON uo.user_id = c.author_id
    GROUP BY uo.organization_id, c.author_id, DATE(c.created_at)
),
issues_found AS (
    -- 用户发现的问题
    SELECT
        uo.organization_id,
        i.reporter_id AS user_id,
        DATE(i.created_at) AS issue_date,
        COUNT(*) AS found_count
    FROM issues i
    JOIN user_orgs uo ON uo.user_id = i.reporter_id
    GROUP BY uo.organization_id, i.reporter_id, DATE(i.created_at)
),
issues_fixed AS (
    -- 用户修复的问题（被分配且状态为resolved/closed）
    SELECT
        uo.organization_id,
        i.assignee_id AS user_id,
        DATE(i.updated_at) AS fix_date,
        COUNT(*) AS fixed_count
    FROM issues i
    JOIN user_orgs uo ON uo.user_id = i.assignee_id
    WHERE i.status IN ('resolved', 'closed')
    GROUP BY uo.organization_id, i.assignee_id, DATE(i.updated_at)
),
mrs_merged AS (
    -- 用户合并的MR（作者且状态为merged）
    SELECT
        r.organization_id,
        mr.author_id AS user_id,
        DATE(mr.updated_at) AS merge_date,
        COUNT(*) AS merged_count
    FROM merge_requests mr
    JOIN repositories r ON r.id = mr.repo_id
    WHERE mr.status = 'merged'
    GROUP BY r.organization_id, mr.author_id, DATE(mr.updated_at)
),
lines_changed AS (
    -- 用户创建的MR的变更行数（作者贡献的代码行数）
    SELECT
        r.organization_id,
        mr.author_id AS user_id,
        DATE(mr.created_at) AS change_date,
        COALESCE(SUM(ds.line_count), 0) AS total_lines
    FROM merge_requests mr
    JOIN repositories r ON r.id = mr.repo_id
    LEFT JOIN diff_snapshots ds ON ds.merge_request_id = mr.id
    GROUP BY r.organization_id, mr.author_id, DATE(mr.created_at)
),
all_user_dates AS (
    SELECT organization_id, user_id, comment_date AS activity_date FROM review_comments
    UNION
    SELECT organization_id, user_id, comment_date AS activity_date FROM all_comments
    UNION
    SELECT organization_id, user_id, issue_date AS activity_date FROM issues_found
    UNION
    SELECT organization_id, user_id, fix_date AS activity_date FROM issues_fixed
    UNION
    SELECT organization_id, user_id, merge_date AS activity_date FROM mrs_merged
    UNION
    SELECT organization_id, user_id, change_date AS activity_date FROM lines_changed
),
distinct_user_dates AS (
    SELECT DISTINCT organization_id, user_id, activity_date
    FROM all_user_dates
    WHERE activity_date IS NOT NULL
)
SELECT
    dud.activity_date AS stat_date,
    dud.organization_id,
    dud.user_id,
    u.username,
    COALESCE(rc.review_count, 0) AS reviews_done,
    COALESCE(ac.comment_count, 0) AS comments_count,
    COALESCE(ifs.found_count, 0) AS issues_found,
    COALESCE(iff.fixed_count, 0) AS issues_fixed,
    COALESCE(mm.merged_count, 0) AS mrs_merged,
    COALESCE(lc.total_lines, 0) AS lines_changed,
    ROUND((
        COALESCE(rc.review_count, 0) * 3 +
        COALESCE(ac.comment_count, 0) +
        COALESCE(ifs.found_count, 0) * 2 +
        COALESCE(iff.fixed_count, 0) * 2 +
        COALESCE(mm.merged_count, 0) * 5
    )::numeric, 2) AS contribution_score
FROM distinct_user_dates dud
JOIN users u ON u.id = dud.user_id
LEFT JOIN review_comments rc ON rc.organization_id = dud.organization_id AND rc.user_id = dud.user_id AND rc.comment_date = dud.activity_date
LEFT JOIN all_comments ac ON ac.organization_id = dud.organization_id AND ac.user_id = dud.user_id AND ac.comment_date = dud.activity_date
LEFT JOIN issues_found ifs ON ifs.organization_id = dud.organization_id AND ifs.user_id = dud.user_id AND ifs.issue_date = dud.activity_date
LEFT JOIN issues_fixed iff ON iff.organization_id = dud.organization_id AND iff.user_id = dud.user_id AND iff.fix_date = dud.activity_date
LEFT JOIN mrs_merged mm ON mm.organization_id = dud.organization_id AND mm.user_id = dud.user_id AND mm.merge_date = dud.activity_date
LEFT JOIN lines_changed lc ON lc.organization_id = dud.organization_id AND lc.user_id = dud.user_id AND lc.change_date = dud.activity_date
WITH DATA;

-- 主键索引
CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_contributor_stats_daily_pk
    ON mv_contributor_stats_daily(stat_date, user_id);

-- 辅助索引：按组织查询
CREATE INDEX IF NOT EXISTS idx_mv_contributor_stats_daily_org
    ON mv_contributor_stats_daily(organization_id, stat_date DESC);

-- 辅助索引：按贡献分排序
CREATE INDEX IF NOT EXISTS idx_mv_contributor_stats_daily_score
    ON mv_contributor_stats_daily(contribution_score DESC);


-- ------------------------------------------------------------
-- 5. 物化视图：每日问题类型趋势 mv_issue_type_trend_daily
-- ------------------------------------------------------------
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_issue_type_trend_daily AS
WITH issue_create AS (
    -- 每日按严重级别创建的问题
    SELECT
        r.organization_id,
        i.severity,
        DATE(i.created_at) AS create_date,
        COUNT(*) AS created_count
    FROM issues i
    LEFT JOIN merge_requests mr ON mr.id = i.merge_request_id
    LEFT JOIN repositories r ON r.id = mr.repo_id
    WHERE r.organization_id IS NOT NULL
    GROUP BY r.organization_id, i.severity, DATE(i.created_at)
),
issue_resolve AS (
    -- 每日按严重级别解决的问题及平均解决时长
    SELECT
        r.organization_id,
        i.severity,
        DATE(i.updated_at) AS resolve_date,
        COUNT(*) AS resolved_cnt,
        AVG(EXTRACT(EPOCH FROM (i.updated_at - i.created_at)) / 3600.0) AS avg_hours
    FROM issues i
    LEFT JOIN merge_requests mr ON mr.id = i.merge_request_id
    LEFT JOIN repositories r ON r.id = mr.repo_id
    WHERE r.organization_id IS NOT NULL
        AND i.status IN ('resolved', 'closed')
    GROUP BY r.organization_id, i.severity, DATE(i.updated_at)
),
all_dates AS (
    SELECT organization_id, severity, create_date AS trend_date FROM issue_create
    UNION
    SELECT organization_id, severity, resolve_date AS trend_date FROM issue_resolve WHERE resolve_date IS NOT NULL
),
distinct_dates AS (
    SELECT DISTINCT organization_id, severity, trend_date
    FROM all_dates
    WHERE trend_date IS NOT NULL
)
SELECT
    dd.trend_date AS stat_date,
    dd.organization_id,
    dd.severity,
    COALESCE(ic.created_count, 0) AS issue_count,
    COALESCE(ir.resolved_cnt, 0) AS resolved_count,
    COALESCE(ROUND(ir.avg_hours::numeric, 2), 0) AS avg_resolve_hours
FROM distinct_dates dd
LEFT JOIN issue_create ic
    ON ic.organization_id = dd.organization_id
    AND ic.severity = dd.severity
    AND ic.create_date = dd.trend_date
LEFT JOIN issue_resolve ir
    ON ir.organization_id = dd.organization_id
    AND ir.severity = dd.severity
    AND ir.resolve_date = dd.trend_date
WITH DATA;

-- 主键索引
CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_issue_type_trend_daily_pk
    ON mv_issue_type_trend_daily(stat_date, organization_id, severity);

-- 辅助索引：按组织和严重级别查询趋势
CREATE INDEX IF NOT EXISTS idx_mv_issue_type_trend_daily_org_sev
    ON mv_issue_type_trend_daily(organization_id, severity, stat_date DESC);


-- ------------------------------------------------------------
-- 6. 存储过程：刷新全部物化视图
-- ------------------------------------------------------------
CREATE OR REPLACE PROCEDURE refresh_materialized_views()
LANGUAGE plpgsql
AS $$
DECLARE
    start_time TIMESTAMPTZ;
    end_time TIMESTAMPTZ;
    duration_ms INTEGER;
BEGIN
    -- 刷新仓库健康度
    start_time := clock_timestamp();
    REFRESH MATERIALIZED VIEW mv_repo_health_daily;
    end_time := clock_timestamp();
    duration_ms := EXTRACT(EPOCH FROM (end_time - start_time)) * 1000;
    RAISE NOTICE 'Refreshed mv_repo_health_daily in % ms', duration_ms;

    -- 刷新成员贡献度
    start_time := clock_timestamp();
    REFRESH MATERIALIZED VIEW mv_contributor_stats_daily;
    end_time := clock_timestamp();
    duration_ms := EXTRACT(EPOCH FROM (end_time - start_time)) * 1000;
    RAISE NOTICE 'Refreshed mv_contributor_stats_daily in % ms', duration_ms;

    -- 刷新问题类型趋势
    start_time := clock_timestamp();
    REFRESH MATERIALIZED VIEW mv_issue_type_trend_daily;
    end_time := clock_timestamp();
    duration_ms := EXTRACT(EPOCH FROM (end_time - start_time)) * 1000;
    RAISE NOTICE 'Refreshed mv_issue_type_trend_daily in % ms', duration_ms;
END;
$$;


-- ------------------------------------------------------------
-- 完成标记
-- ------------------------------------------------------------
-- 迁移 002_enhancements.sql 执行完毕
