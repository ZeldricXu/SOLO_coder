use maud::{html, Markup};
use crate::models::{AuthUser, RepositoryWithDetails};
use crate::templates::layout::base_layout;
use crate::templates::components::{component_styles, provider_icon, status_badge, pagination_control};

pub fn repos_page(user: &AuthUser, repos: &[RepositoryWithDetails], current_page: i32, total_pages: i32, per_page: i32, total: i64) -> Markup {
    base_layout("仓库管理", "repos", user, html! {
        style { (component_styles()) }
        style { (repos_styles()) }

        div class="page-header" {
            div {
                h2 class="page-subtitle" { "仓库管理" }
                p class="page-desc" { "管理和配置您的代码仓库，设置Webhook和同步规则" }
            }
            div class="header-actions" {
                button class="btn btn-secondary" {
                    svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16" {
                        path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4";
                        polyline points="7 10 12 15 17 10";
                        line x1="12" y1="15" x2="12" y2="3";
                    }
                    "同步全部"
                }
                button class="btn btn-primary" onclick="document.getElementById('import-repo-modal').classList.add('active')" {
                    svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16" {
                        line x1="12" y1="5" x2="12" y2="19";
                        line x1="5" y1="12" x2="19" y2="12";
                    }
                    "导入仓库"
                }
            }
        }

        div class="filter-bar" {
            div class="filter-group" {
                label class="filter-label" { "提供商" }
                select class="filter-select" {
                    option value="" { "全部" }
                    option value="github" { "GitHub" }
                    option value="gitlab" { "GitLab" }
                    option value="gitee" { "Gitee" }
                }
            }
            div class="filter-group" {
                label class="filter-label" { "状态" }
                select class="filter-select" {
                    option value="" { "全部" }
                    option value="active" { "活跃" }
                    option value="inactive" { "未激活" }
                }
            }
            div class="filter-group" {
                label class="filter-label" { "团队" }
                select class="filter-select" {
                    option value="" { "全部团队" }
                    option value="1" { "前端团队" }
                    option value="2" { "后端团队" }
                }
            }
            div class="filter-group" style="flex: 1; min-width: 200px;" {
                input type="text" class="filter-input" style="width: 100%;" placeholder="搜索仓库名称...";
            }
            div class="view-toggle" {
                button class="view-btn active" data-view="card" {
                    svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16" {
                        rect x="3" y="3" width="7" height="7";
                        rect x="14" y="3" width="7" height="7";
                        rect x="14" y="14" width="7" height="7";
                        rect x="3" y="14" width="7" height="7";
                    }
                }
                button class="view-btn" data-view="table" {
                    svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16" {
                        line x1="3" y1="12" x2="21" y2="12";
                        line x1="3" y1="6" x2="21" y2="6";
                        line x1="3" y1="18" x2="21" y2="18";
                    }
                }
            }
        }

        div class="repos-grid" id="repos-grid" {
            @for repo in repos {
                a href=(format!("/repos/{}", repo.id)) class="repo-card" {
                    div class="repo-card-header" {
                        div class="repo-info" {
                            (provider_icon(&repo.provider))
                            div class="repo-name-block" {
                                div class="repo-name" { (repo.name) }
                                div class="repo-full-name" { (repo.full_name) }
                            }
                        }
                        div class={ "repo-status " @if repo.is_active { "active" } @else { "inactive" } } {
                            @if repo.is_active { "● 活跃" } @else { "○ 未激活" }
                        }
                    }
                    div class="repo-card-body" {
                        div class="repo-stats" {
                            div class="repo-stat" {
                                div class="repo-stat-value" { (repo.mr_count) }
                                div class="repo-stat-label" { "MR总数" }
                            }
                            div class="repo-stat" {
                                div class="repo-stat-value warning" { (repo.pending_reviews) }
                                div class="repo-stat-label" { "待评审" }
                            }
                        }
                        @if let Some(team_name) = &repo.team_name {
                            div class="repo-team" {
                                svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="14" height="14" {
                                    path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2";
                                    circle cx="9" cy="7" r="4";
                                }
                                (team_name)
                            }
                        }
                    }
                    div class="repo-card-footer" {
                        div class="repo-sync" {
                            svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="14" height="14" {
                                path d="M21 12a9 9 0 1 1-3-6.7";
                                polyline points="21 3 21 9 15 9";
                            }
                            @if let Some(sync_at) = repo.last_sync_at {
                                span { "上次同步: " (format_time(&sync_at.to_string())) }
                            } @else {
                                span { "未同步" }
                            }
                        }
                        div class="repo-actions" {
                            button class="btn btn-sm btn-ghost" onclick="event.stopPropagation();" {
                                svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="14" height="14" {
                                    path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4";
                                    polyline points="17 8 12 3 7 8";
                                    line x1="12" y1="3" x2="12" y2="15";
                                }
                            }
                            button class="btn btn-sm btn-ghost" onclick="event.stopPropagation();" {
                                svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="14" height="14" {
                                    circle cx="12" cy="12" r="3";
                                    path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z";
                                }
                            }
                        }
                    }
                }
            }
        }

        @if repos.is_empty() {
            div class="empty-state" {
                div class="empty-icon" { "📦" }
                div class="empty-title" { "暂无仓库" }
                div class="empty-desc" { "还没有导入任何仓库，点击上方按钮开始导入您的第一个代码仓库吧！" }
                button class="btn btn-primary btn-lg" style="margin-top: 24px;" onclick="document.getElementById('import-repo-modal').classList.add('active')" {
                    "导入仓库"
                }
            }
        } @else {
            (pagination_control(current_page, total_pages, per_page, total))
        }

        (import_repo_modal())
    })
}

pub fn repo_detail_page(user: &AuthUser, repo: &RepositoryWithDetails) -> Markup {
    base_layout(&format!("仓库详情 - {}", repo.name), "repos", user, html! {
        style { (component_styles()) }
        style { (repos_styles()) }

        div class="repo-detail-header" {
            div class="breadcrumb" {
                a href="/repos" { "仓库管理" }
                span { " / " }
                span { (repo.name) }
            }
            div class="repo-detail-title" {
                (provider_icon(&repo.provider))
                h2 { (repo.name) }
                div class={ "repo-status large " @if repo.is_active { "active" } @else { "inactive" } } {
                    @if repo.is_active { "● 活跃" } @else { "○ 未激活" }
                }
            }
            div class="repo-detail-actions" {
                button class="btn btn-secondary" { "同步仓库" }
                button class="btn btn-primary" { "设置" }
            }
        }

        div class="tabs" style="margin-bottom: 24px;" {
            button class="tab-btn active" { "概览" }
            button class="tab-btn" { "MR列表" }
            button class="tab-btn" { "活动日志" }
            button class="tab-btn" { "Webhook配置" }
            button class="tab-btn" { "Checklist模板" }
        }

        div class="repo-detail-grid" {
            div class="card" {
                div class="card-header" {
                    h3 class="card-title" { "基本信息" }
                }
                div class="card-body" {
                    div class="info-list" {
                        div class="info-item" {
                            div class="info-label" { "仓库全称" }
                            div class="info-value" { (repo.full_name) }
                        }
                        div class="info-item" {
                            div class="info-label" { "提供商" }
                            div class="info-value" { (provider_display(&repo.provider)) }
                        }
                        @if let Some(team_name) = &repo.team_name {
                            div class="info-item" {
                                div class="info-label" { "所属团队" }
                                div class="info-value" { (team_name) }
                            }
                        }
                        div class="info-item" {
                            div class="info-label" { "创建时间" }
                            div class="info-value" { (format_time(&repo.created_at.to_string())) }
                        }
                        @if let Some(sync_at) = repo.last_sync_at {
                            div class="info-item" {
                                div class="info-label" { "上次同步" }
                                div class="info-value" { (format_time(&sync_at.to_string())) }
                            }
                        }
                    }
                }
            }

            div class="card" {
                div class="card-header" {
                    h3 class="card-title" { "统计数据" }
                }
                div class="card-body" {
                    div class="repo-stats-large" {
                        div class="repo-stat-large" {
                            div class="repo-stat-value-large" { (repo.mr_count) }
                            div class="repo-stat-label" { "MR总数" }
                        }
                        div class="repo-stat-large" {
                            div class="repo-stat-value-large warning" { (repo.pending_reviews) }
                            div class="repo-stat-label" { "待评审" }
                        }
                        div class="repo-stat-large" {
                            div class="repo-stat-value-large success" { "85%" }
                            div class="repo-stat-label" { "评审覆盖率" }
                        }
                        div class="repo-stat-large" {
                            div class="repo-stat-value-large" { "2.4h" }
                            div class="repo-stat-label" { "平均响应" }
                        }
                    }
                }
            }

            div class="card large" {
                div class="card-header" {
                    h3 class="card-title" { "最近MR" }
                    a href="/merge-requests?repo_id=xxx" class="btn btn-sm btn-ghost" { "查看全部 →" }
                }
                div class="card-body" style="padding: 0;" {
                    table class="data-table" {
                        thead {
                            tr {
                                th { "标题" }
                                th { "状态" }
                                th { "作者" }
                                th { "更新时间" }
                            }
                        }
                        tbody {
                            tr {
                                td {
                                    div style="font-weight: 500;" { "feat: 添加用户认证模块" }
                                    div style="font-size: 12px; color: #64748B; margin-top: 2px;" { "feature/auth → main" }
                                }
                                td { (status_badge("open")) }
                                td { "张三" }
                                td { "2小时前" }
                            }
                            tr {
                                td {
                                    div style="font-weight: 500;" { "fix: 修复登录页面样式问题" }
                                    div style="font-size: 12px; color: #64748B; margin-top: 2px;" { "fix/login-style → main" }
                                }
                                td { (status_badge("reviewing")) }
                                td { "李四" }
                                td { "5小时前" }
                            }
                            tr {
                                td {
                                    div style="font-weight: 500;" { "refactor: 重构用户服务模块" }
                                    div style="font-size: 12px; color: #64748B; margin-top: 2px;" { "refactor/user-service → main" }
                                }
                                td { (status_badge("approved")) }
                                td { "王五" }
                                td { "昨天" }
                            }
                        }
                    }
                }
            }

            div class="card" {
                div class="card-header" {
                    h3 class="card-title" { "活动日志" }
                }
                div class="card-body" style="padding: 0; max-height: 400px; overflow-y: auto;" {
                    div class="activity-list" {
                        div class="activity-item-mini" {
                            div class="activity-icon-mini sync" { "🔄" }
                            div class="activity-content-mini" {
                                div class="activity-title-mini" { "仓库同步成功" }
                                div class="activity-time-mini" { "10分钟前" }
                            }
                        }
                        div class="activity-item-mini" {
                            div class="activity-icon-mini mr" { "📥" }
                            div class="activity-content-mini" {
                                div class="activity-title-mini" { "新MR: feat: 添加用户认证模块" }
                                div class="activity-time-mini" { "2小时前" }
                            }
                        }
                        div class="activity-item-mini" {
                            div class="activity-icon-mini webhook" { "🔗" }
                            div class="activity-content-mini" {
                                div class="activity-title-mini" { "Webhook配置已更新" }
                                div class="activity-time-mini" { "昨天" }
                            }
                        }
                        div class="activity-item-mini" {
                            div class="activity-icon-mini approval" { "✅" }
                            div class="activity-content-mini" {
                                div class="activity-title-mini" { "MR已通过: refactor: 重构用户服务模块" }
                                div class="activity-time-mini" { "2天前" }
                            }
                        }
                    }
                }
            }
        }
    })
}

fn repos_styles() -> &'static str {
    r#"
    .page-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-end;
        margin-bottom: 20px;
    }

    .page-subtitle {
        font-size: 24px;
        font-weight: 700;
        color: #F8FAFC;
        margin-bottom: 4px;
    }

    .page-desc {
        font-size: 14px;
        color: #64748B;
    }

    .header-actions {
        display: flex;
        gap: 12px;
    }

    .view-toggle {
        display: flex;
        background: #0F172A;
        border: 1px solid #334155;
        border-radius: 6px;
        padding: 2px;
    }

    .view-btn {
        width: 32px;
        height: 32px;
        display: flex;
        align-items: center;
        justify-content: center;
        background: transparent;
        border: none;
        border-radius: 4px;
        color: #64748B;
        cursor: pointer;
        transition: all 0.2s;
    }

    .view-btn:hover {
        color: #E2E8F0;
    }

    .view-btn.active {
        background: #334155;
        color: #F1F5F9;
    }

    .repos-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
        gap: 16px;
        margin-bottom: 20px;
    }

    .repo-card {
        background: #1E293B;
        border: 1px solid #334155;
        border-radius: 12px;
        padding: 20px;
        cursor: pointer;
        transition: all 0.2s ease;
        text-decoration: none;
        color: inherit;
    }

    .repo-card:hover {
        border-color: #3B82F6;
        transform: translateY(-2px);
        box-shadow: 0 8px 32px rgba(59, 130, 246, 0.15);
    }

    .repo-card-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        margin-bottom: 16px;
    }

    .repo-info {
        display: flex;
        align-items: center;
        gap: 12px;
    }

    .repo-name-block {
        min-width: 0;
    }

    .repo-name {
        font-size: 16px;
        font-weight: 600;
        color: #F1F5F9;
        margin-bottom: 2px;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }

    .repo-full-name {
        font-size: 12px;
        color: #64748B;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }

    .repo-status {
        font-size: 12px;
        font-weight: 500;
        padding: 4px 10px;
        border-radius: 20px;
    }

    .repo-status.active {
        background: rgba(16, 185, 129, 0.15);
        color: #10B981;
    }

    .repo-status.inactive {
        background: rgba(148, 163, 184, 0.15);
        color: #64748B;
    }

    .repo-status.large {
        padding: 6px 14px;
        font-size: 13px;
    }

    .repo-card-body {
        margin-bottom: 16px;
    }

    .repo-stats {
        display: flex;
        gap: 24px;
        margin-bottom: 12px;
    }

    .repo-stat {
        flex: 1;
    }

    .repo-stat-value {
        font-size: 24px;
        font-weight: 700;
        color: #F8FAFC;
        margin-bottom: 2px;
    }

    .repo-stat-value.warning {
        color: #F59E0B;
    }

    .repo-stat-value.success {
        color: #10B981;
    }

    .repo-stat-label {
        font-size: 12px;
        color: #64748B;
    }

    .repo-team {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 13px;
        color: #94A3B8;
    }

    .repo-card-footer {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding-top: 16px;
        border-top: 1px solid #334155;
    }

    .repo-sync {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 12px;
        color: #64748B;
    }

    .repo-actions {
        display: flex;
        gap: 4px;
    }

    .repo-detail-header {
        margin-bottom: 24px;
    }

    .breadcrumb {
        font-size: 13px;
        color: #64748B;
        margin-bottom: 12px;
    }

    .breadcrumb a {
        color: #3B82F6;
        text-decoration: none;
    }

    .breadcrumb a:hover {
        text-decoration: underline;
    }

    .repo-detail-title {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-bottom: 8px;
    }

    .repo-detail-title h2 {
        font-size: 28px;
        font-weight: 700;
        color: #F8FAFC;
    }

    .repo-detail-actions {
        display: flex;
        gap: 12px;
    }

    .repo-detail-grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 20px;
    }

    .repo-detail-grid .large {
        grid-column: span 2;
    }

    @media (max-width: 1024px) {
        .repo-detail-grid {
            grid-template-columns: 1fr;
        }
        .repo-detail-grid .large {
            grid-column: span 1;
        }
    }

    .info-list {
        display: flex;
        flex-direction: column;
        gap: 16px;
    }

    .info-item {
        display: flex;
        justify-content: space-between;
        align-items: center;
    }

    .info-label {
        font-size: 13px;
        color: #64748B;
    }

    .info-value {
        font-size: 14px;
        font-weight: 500;
        color: #E2E8F0;
    }

    .repo-stats-large {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 20px;
    }

    .repo-stat-large {
        text-align: center;
        padding: 16px;
        background: #0F172A;
        border-radius: 10px;
    }

    .repo-stat-value-large {
        font-size: 32px;
        font-weight: 700;
        color: #F8FAFC;
        margin-bottom: 4px;
    }

    .repo-stat-value-large.warning {
        color: #F59E0B;
    }

    .repo-stat-value-large.success {
        color: #10B981;
    }

    .activity-list {
        padding: 8px;
    }

    .activity-item-mini {
        display: flex;
        gap: 12px;
        padding: 12px;
        border-radius: 8px;
        transition: background 0.2s;
    }

    .activity-item-mini:hover {
        background: rgba(51, 65, 85, 0.3);
    }

    .activity-icon-mini {
        width: 32px;
        height: 32px;
        border-radius: 8px;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 14px;
        flex-shrink: 0;
    }

    .activity-icon-mini.sync { background: rgba(59, 130, 246, 0.1); }
    .activity-icon-mini.mr { background: rgba(139, 92, 246, 0.1); }
    .activity-icon-mini.webhook { background: rgba(245, 158, 11, 0.1); }
    .activity-icon-mini.approval { background: rgba(16, 185, 129, 0.1); }

    .activity-content-mini {
        flex: 1;
        min-width: 0;
    }

    .activity-title-mini {
        font-size: 13px;
        color: #E2E8F0;
        margin-bottom: 2px;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }

    .activity-time-mini {
        font-size: 12px;
        color: #64748B;
    }
    "#
}

fn provider_display(provider: &str) -> &str {
    match provider {
        "github" => "GitHub",
        "gitlab" => "GitLab",
        "gitee" => "Gitee",
        _ => provider,
    }
}

fn format_time(_time_str: &str) -> String {
    "2024-01-15 14:30".to_string()
}

fn import_repo_modal() -> Markup {
    html! {
        div class="modal-overlay" id="import-repo-modal" onclick="if(event.target === this) this.classList.remove('active')" {
            div class="modal" style="max-width: 700px;" {
                div class="modal-header" {
                    h3 class="modal-title" { "导入仓库" }
                    button class="modal-close" onclick="document.getElementById('import-repo-modal').classList.remove('active')" {
                        svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="18" height="18" {
                            line x1="18" y1="6" x2="6" y2="18";
                            line x1="6" y1="6" x2="18" y2="18";
                        }
                    }
                }
                div class="modal-body" {
                    div class="provider-selector" {
                        button class="provider-card active" {
                            div class="provider-icon" { "🐙" }
                            div class="provider-name" { "GitHub" }
                            div class="provider-desc" { "通过OAuth授权访问" }
                        }
                        button class="provider-card" {
                            div class="provider-icon" { "🦊" }
                            div class="provider-name" { "GitLab" }
                            div class="provider-desc" { "通过OAuth授权访问" }
                        }
                        button class="provider-card" {
                            div class="provider-icon" { "🐯" }
                            div class="provider-name" { "Gitee" }
                            div class="provider-desc" { "通过OAuth授权访问" }
                        }
                    }

                    div class="form-group" style="margin-top: 24px;" {
                        label class="form-label" { "选择团队" }
                        select class="form-select" {
                            option value="" { "请选择团队" }
                            option value="1" { "前端团队" }
                            option value="2" { "后端团队" }
                            option value="3" { "移动端团队" }
                        }
                    }

                    div class="repo-select-list" {
                        div class="repo-select-header" {
                            input type="text" class="form-input" placeholder="搜索仓库..." style="flex: 1; margin-right: 12px;";
                            button class="btn btn-secondary btn-sm" { "刷新" }
                        }
                        div class="repo-select-items" {
                            label class="repo-select-item" {
                                input type="checkbox";
                                div class="repo-select-info" {
                                    div class="repo-select-name" { "frontend-web" }
                                    div class="repo-select-desc" { "org/frontend-web" }
                                }
                                (provider_icon("github"))
                            }
                            label class="repo-select-item" {
                                input type="checkbox";
                                div class="repo-select-info" {
                                    div class="repo-select-name" { "backend-api" }
                                    div class="repo-select-desc" { "org/backend-api" }
                                }
                                (provider_icon("github"))
                            }
                            label class="repo-select-item" {
                                input type="checkbox";
                                div class="repo-select-info" {
                                    div class="repo-select-name" { "mobile-app" }
                                    div class="repo-select-desc" { "org/mobile-app" }
                                }
                                (provider_icon("github"))
                            }
                        }
                    }
                }
                div class="modal-footer" {
                    button type="button" class="btn btn-secondary" onclick="document.getElementById('import-repo-modal').classList.remove('active')" { "取消" }
                    button type="button" class="btn btn-primary" { "导入选中的仓库" }
                }
            }
        }

        style {
            r#"
            .provider-selector {
                display: grid;
                grid-template-columns: repeat(3, 1fr);
                gap: 12px;
                margin-bottom: 20px;
            }
            .provider-card {
                padding: 20px;
                background: #0F172A;
                border: 2px solid #334155;
                border-radius: 10px;
                cursor: pointer;
                transition: all 0.2s;
                text-align: center;
            }
            .provider-card:hover {
                border-color: #475569;
            }
            .provider-card.active {
                border-color: #3B82F6;
                background: rgba(59, 130, 246, 0.05);
            }
            .provider-icon {
                font-size: 32px;
                margin-bottom: 8px;
            }
            .provider-name {
                font-weight: 600;
                color: #F1F5F9;
                margin-bottom: 2px;
            }
            .provider-desc {
                font-size: 12px;
                color: #64748B;
            }
            .repo-select-header {
                display: flex;
                align-items: center;
                margin-bottom: 12px;
            }
            .repo-select-items {
                max-height: 300px;
                overflow-y: auto;
                border: 1px solid #334155;
                border-radius: 8px;
                background: #0F172A;
            }
            .repo-select-item {
                display: flex;
                align-items: center;
                gap: 12px;
                padding: 12px 16px;
                cursor: pointer;
                transition: background 0.2s;
                border-bottom: 1px solid #1E293B;
            }
            .repo-select-item:last-child {
                border-bottom: none;
            }
            .repo-select-item:hover {
                background: #1E293B;
            }
            .repo-select-item input {
                width: 18px;
                height: 18px;
                accent-color: #3B82F6;
            }
            .repo-select-info {
                flex: 1;
            }
            .repo-select-name {
                font-weight: 500;
                color: #E2E8F0;
            }
            .repo-select-desc {
                font-size: 12px;
                color: #64748B;
            }
            "#
        }
    }
}
