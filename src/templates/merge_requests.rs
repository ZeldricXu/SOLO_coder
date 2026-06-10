use maud::{html, Markup};
use crate::models::{AuthUser, MergeRequestWithDetails};
use crate::templates::layout::base_layout;
use crate::templates::components::{component_styles, status_badge, avatar_with_name, pagination_control, provider_icon};

pub fn mrs_page(user: &AuthUser, mrs: &[MergeRequestWithDetails], current_page: i32, total_pages: i32, per_page: i32, total: i64) -> Markup {
    base_layout("合并请求", "merge_requests", user, html! {
        style { (component_styles()) }
        style { (mrs_styles()) }

        div class="page-header" {
            div {
                h2 class="page-subtitle" { "合并请求" }
                p class="page-desc" { "查看和管理所有合并请求，进行代码评审" }
            }
            div class="header-actions" {
                button class="btn btn-secondary" {
                    svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16" {
                        path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4";
                        polyline points="7 10 12 15 17 10";
                        line x1="12" y1="15" x2="12" y2="3";
                    }
                    "导出"
                }
                button class="btn btn-primary" {
                    svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16" {
                        path d="M12 19l7-7 3 3-7 7-3-3z";
                        path d="M18 13l-1.5-7.5L2 2l3.5 14.5L13 18l5-5z";
                        path d="M2 2l7.586 7.586";
                        circle cx="11" cy="11" r="2";
                    }
                    "新建MR"
                }
            }
        }

        div class="filter-bar" {
            div class="filter-group" {
                label class="filter-label" { "状态" }
                select class="filter-select" {
                    option value="" { "全部状态" }
                    option value="open" { "待评审" }
                    option value="reviewing" { "评审中" }
                    option value="approved" { "已通过" }
                    option value="changes_requested" { "需修改" }
                    option value="merged" { "已合并" }
                    option value="closed" { "已关闭" }
                }
            }
            div class="filter-group" {
                label class="filter-label" { "仓库" }
                select class="filter-select" {
                    option value="" { "全部仓库" }
                    option value="1" { "frontend-web" }
                    option value="2" { "backend-api" }
                    option value="3" { "mobile-app" }
                }
            }
            div class="filter-group" {
                label class="filter-label" { "作者" }
                select class="filter-select" {
                    option value="" { "全部作者" }
                    option value="1" { "张三" }
                    option value="2" { "李四" }
                    option value="3" { "王五" }
                }
            }
            div class="filter-group" {
                label class="filter-label" { "评审人" }
                select class="filter-select" {
                    option value="" { "全部评审人" }
                    option value="1" { "张三" }
                    option value="2" { "李四" }
                }
            }
            div class="filter-group" {
                label class="filter-label" { "时间范围" }
                select class="filter-select" {
                    option value="7d" { "最近7天" }
                    option value="30d" selected { "最近30天" }
                    option value="90d" { "最近90天" }
                    option value="all" { "全部" }
                }
            }
            div class="filter-group" style="flex: 1; min-width: 200px; justify-content: flex-end;" {
                input type="text" class="filter-input" style="width: 240px;" placeholder="搜索MR标题...";
            }
        }

        div class="mrs-table-container" {
            table class="data-table mrs-table" {
                thead {
                    tr {
                        th style="width: 40%;" { "标题" }
                        th { "仓库" }
                        th { "分支" }
                        th { "作者" }
                        th { "评审人" }
                        th { "状态" }
                        th { "更新时间" }
                    }
                }
                tbody {
                    @for mr in mrs {
                        tr onclick=(format!("window.location.href='/merge-requests/{}'", mr.id)) {
                            td {
                                div class="mr-title" {
                                    (provider_icon(&mr.provider))
                                    span { (mr.title) }
                                }
                                div class="mr-meta" {
                                    span class="mr-meta-item" {
                                        svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="12" height="12" {
                                            path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z";
                                        }
                                        (mr.comment_count)
                                    }
                                    span class="mr-meta-item" {
                                        svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="12" height="12" {
                                            path d="M12 22c5.523 0 10-4.477 10-10S17.523 2 12 2 2 6.477 2 12s4.477 10 10 10z";
                                            path d="M12 8v4";
                                            path d="M12 16h.01";
                                        }
                                        (mr.issue_count)
                                    }
                                    @if mr.unresolved_comment_count > 0 {
                                        span class="mr-meta-item unresolved" {
                                            (mr.unresolved_comment_count) " 个未解决"
                                        }
                                    }
                                }
                            }
                            td {
                                span class="repo-name" { (mr.repo_name) }
                            }
                            td {
                                div class="branch-info" {
                                    span class="branch source" { (mr.source_branch) }
                                    svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="14" height="14" style="color: #64748B;" {
                                        line x1="5" y1="12" x2="19" y2="12";
                                        polyline points="12 5 19 12 12 19";
                                    }
                                    span class="branch target" { (mr.target_branch) }
                                }
                            }
                            td {
                                div class="user-cell" {
                                    (avatar_with_name(&mr.author_name, &mr.author_avatar, 24))
                                    span { (mr.author_name) }
                                }
                            }
                            td {
                                div class="reviewers-cell" {
                                    div class="avatar-group" {
                                        (avatar_with_name("评审人1", &None, 24))
                                        (avatar_with_name("评审人2", &None, 24))
                                    }
                                }
                            }
                            td {
                                (status_badge(&mr.status))
                            }
                            td {
                                span class="update-time" { (format_time_ago(&mr.updated_at.to_string())) }
                            }
                        }
                    }
                }
            }

            @if mrs.is_empty() {
                div class="empty-state" {
                    div class="empty-icon" { "📥" }
                    div class="empty-title" { "暂无合并请求" }
                    div class="empty-desc" { "还没有任何合并请求，提交您的第一个MR开始代码评审吧！" }
                }
            }
        }

        @if !mrs.is_empty() {
            (pagination_control(current_page, total_pages, per_page, total))
        }
    })
}

fn mrs_styles() -> &'static str {
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

    .mrs-table-container {
        background: #1E293B;
        border: 1px solid #334155;
        border-radius: 12px;
        overflow: hidden;
    }

    .mrs-table {
        margin-bottom: 0;
    }

    .mr-title {
        display: flex;
        align-items: center;
        gap: 8px;
        font-weight: 500;
        color: #E2E8F0;
        margin-bottom: 6px;
    }

    .mr-title:hover {
        color: #3B82F6;
    }

    .mr-meta {
        display: flex;
        align-items: center;
        gap: 16px;
        font-size: 12px;
        color: #64748B;
    }

    .mr-meta-item {
        display: flex;
        align-items: center;
        gap: 4px;
    }

    .mr-meta-item.unresolved {
        color: #EF4444;
        font-weight: 500;
    }

    .repo-name {
        font-size: 13px;
        color: #94A3B8;
        font-weight: 500;
    }

    .branch-info {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 12px;
    }

    .branch {
        padding: 2px 8px;
        background: #334155;
        border-radius: 4px;
        font-family: 'JetBrains Mono', monospace;
        font-size: 11px;
        color: #CBD5E1;
    }

    .branch.source {
        background: rgba(139, 92, 246, 0.15);
        color: #A78BFA;
    }

    .branch.target {
        background: rgba(16, 185, 129, 0.15);
        color: #34D399;
    }

    .user-cell {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 13px;
        color: #E2E8F0;
    }

    .reviewers-cell {
        display: flex;
        align-items: center;
    }

    .update-time {
        font-size: 12px;
        color: #64748B;
    }
    "#
}

fn format_time_ago(_time_str: &str) -> String {
    "2小时前".to_string()
}
