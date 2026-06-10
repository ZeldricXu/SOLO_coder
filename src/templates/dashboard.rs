use maud::{html, Markup};
use crate::models::{AuthUser, ActivityItem, TeamRankingItem, IssueBySeverity, DashboardStats};
use crate::templates::layout::base_layout;
use crate::templates::components::{component_styles, avatar_with_name, svg_line_chart, svg_bar_chart};

pub fn dashboard_page(user: &AuthUser, stats: &DashboardStats, activities: &[ActivityItem], rankings: &[TeamRankingItem], issues_by_severity: &[IssueBySeverity], coverage_data: &[f64], coverage_labels: &[String]) -> Markup {
    base_layout("仪表盘", "dashboard", user, html! {
        style { (component_styles()) }
        style { (dashboard_styles()) }

        div class="stats-grid" id="stats-grid" {
            div class="stat-card" {
                div class="stat-label" {
                    div class="stat-label-icon" { "📋" }
                    "待评审"
                }
                div class="stat-value" { (stats.total_pending_reviews) }
                div class="stat-change positive" {
                    span { "↑" }
                    (stats.my_pending_reviews) " 个我负责的"
                }
            }

            div class="stat-card success" {
                div class="stat-label" {
                    div class="stat-label-icon" { "📤" }
                    "我的MR"
                }
                div class="stat-value" { "8" }
                div class="stat-change neutral" {
                    span { "•" }
                    "3 个评审中"
                }
            }

            div class="stat-card warning" {
                div class="stat-label" {
                    div class="stat-label-icon" { "🐛" }
                    "我的问题"
                }
                div class="stat-value" { (stats.my_open_issues) }
                div class="stat-change negative" {
                    span { "!" }
                    (stats.issues_assigned_to_me) " 个分配给我"
                }
            }

            div class="stat-card purple" {
                div class="stat-label" {
                    div class="stat-label-icon" { "👤" }
                    "分配给我"
                }
                div class="stat-value" { (stats.issues_assigned_to_me) }
                div class="stat-change positive" {
                    span { "✓" }
                    "2 个已解决"
                }
            }
        }

        div class="dashboard-grid" {
            div class="chart-container large" {
                div class="chart-header" {
                    h3 class="chart-title" { "评审覆盖率趋势" }
                    div class="chart-legend" {
                        div class="legend-item" {
                            div class="legend-color" style="background: #3B82F6;";
                            span { "覆盖率" }
                        }
                    }
                }
                div style="height: 280px;" {
                    (svg_line_chart(coverage_data, coverage_labels, "#3B82F6", 280))
                }
            }

            div class="card" {
                div class="card-header" {
                    h3 class="card-title" { "问题按严重程度分布" }
                }
                div class="card-body" {
                    div style="height: 200px; display: flex; align-items: center; justify-content: center;" {
                        (svg_bar_chart(
                            &issues_by_severity.iter().map(|i| i.count).collect::<Vec<_>>(),
                            &issues_by_severity.iter().map(|i| severity_display(&i.severity)).collect::<Vec<_>>(),
                            &["#EF4444", "#F59E0B", "#3B82F6", "#8B5CF6"],
                            200
                        ))
                    }
                    div class="severity-legend" {
                        @for item in issues_by_severity {
                            div class="severity-legend-item" {
                                div class="severity-dot" style={ "background: " (severity_color(&item.severity)) ";" };
                                span class="severity-name" { (severity_display(&item.severity)) }
                                span class="severity-count" { (item.count) }
                                span class="severity-percent" { (format!("{:.1}%", item.percentage)) }
                            }
                        }
                    }
                }
            }

            div class="card large" {
                div class="card-header" {
                    h3 class="card-title" { "最近活动" }
                    a href="/merge-requests" class="btn btn-sm btn-ghost" { "查看全部 →" }
                }
                div class="card-body" style="padding: 0;" {
                    div class="timeline" style="padding: 20px 24px;" {
                        @for activity in activities {
                            div class="timeline-item" {
                                div class={ "timeline-dot " (activity_type_class(&activity.type_)) } {
                                    (activity_type_icon(&activity.type_))
                                }
                                div class="timeline-content" {
                                    div class="timeline-title" { (activity.title) }
                                    div class="timeline-desc" { (activity.description) }
                                    div class="timeline-meta" {
                                        div style="display: flex; align-items: center; gap: 8px;" {
                                            (avatar_with_name(&activity.username, &activity.avatar_url, 20))
                                            span { (activity.username) }
                                        }
                                        span { (format_time_ago(&activity.created_at.to_string())) }
                                    }
                                }
                            }
                        }
                        @if activities.is_empty() {
                            div class="empty-state" {
                                div class="empty-icon" { "📭" }
                                div class="empty-title" { "暂无活动" }
                                div class="empty-desc" { "还没有任何活动记录，开始创建你的第一个MR吧！" }
                            }
                        }
                    }
                }
            }

            div class="card" {
                div class="card-header" {
                    h3 class="card-title" { "团队排行榜" }
                    span class="badge badge-info" { "本月" }
                }
                div class="card-body" style="padding: 16px;" {
                    div class="ranking-list" {
                        @for member in rankings {
                            div class="ranking-item" {
                                div class={ "ranking-number " (rank_class(member.rank)) } {
                                    @if member.rank == 1 { "🥇" }
                                    @else if member.rank == 2 { "🥈" }
                                    @else if member.rank == 3 { "🥉" }
                                    @else { (member.rank) }
                                }
                                div class="ranking-user" {
                                    (avatar_with_name(&member.username, &member.avatar_url, 32))
                                    div {
                                        div style="font-weight: 600; color: #F1F5F9;" { (member.username) }
                                        div style="font-size: 12px; color: #64748B;" {
                                            (member.reviews_count) " 次评审 · " (member.issues_found) " 个问题"
                                        }
                                    }
                                }
                                div class="ranking-score" { (format!("{:.0}", member.score)) }
                            }
                        }
                    }
                }
            }

            div class="card full-width" {
                div class="card-header" {
                    h3 class="card-title" { "快捷操作" }
                }
                div class="card-body" {
                    div class="quick-action-grid" {
                        a href="/merge-requests?status=open" class="quick-action-card" {
                            div class="quick-action-icon" { "🔍" }
                            div class="quick-action-title" { "开始评审" }
                            div class="quick-action-desc" { "查看待评审MR" }
                        }
                        a href="/issues?status=open&assignee=me" class="quick-action-card" {
                            div class="quick-action-icon" { "🐛" }
                            div class="quick-action-title" { "我的问题" }
                            div class="quick-action-desc" { "处理分配的问题" }
                        }
                        a href="/repos/import" class="quick-action-card" {
                            div class="quick-action-icon" { "📦" }
                            div class="quick-action-title" { "导入仓库" }
                            div class="quick-action-desc" { "连接Git仓库" }
                        }
                        a href="/stats" class="quick-action-card" {
                            div class="quick-action-icon" { "📊" }
                            div class="quick-action-title" { "查看统计" }
                            div class="quick-action-desc" { "团队数据报表" }
                        }
                        a href="/merge-requests?author=me" class="quick-action-card" {
                            div class="quick-action-icon" { "📤" }
                            div class="quick-action-title" { "我的MR" }
                            div class="quick-action-desc" { "查看提交记录" }
                        }
                        button class="quick-action-card" onclick="document.getElementById('create-issue-modal').classList.add('active')" {
                            div class="quick-action-icon" { "➕" }
                            div class="quick-action-title" { "创建问题" }
                            div class="quick-action-desc" { "报告代码问题" }
                        }
                    }
                }
            }
        }

        (create_issue_modal())
    })
}

fn dashboard_styles() -> &'static str {
    r#"
    .stats-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
        gap: 20px;
        margin-bottom: 24px;
    }

    .dashboard-grid {
        display: grid;
        grid-template-columns: 2fr 1fr;
        gap: 20px;
    }

    .dashboard-grid .large {
        grid-column: span 1;
    }

    .dashboard-grid .full-width {
        grid-column: 1 / -1;
    }

    @media (max-width: 1200px) {
        .dashboard-grid {
            grid-template-columns: 1fr;
        }
        .dashboard-grid .large {
            grid-column: span 1;
        }
    }

    .severity-legend {
        display: flex;
        flex-direction: column;
        gap: 8px;
        margin-top: 20px;
        padding-top: 20px;
        border-top: 1px solid #334155;
    }

    .severity-legend-item {
        display: flex;
        align-items: center;
        gap: 10px;
        font-size: 13px;
    }

    .severity-dot {
        width: 10px;
        height: 10px;
        border-radius: 50%;
    }

    .severity-name {
        color: #CBD5E1;
        flex: 1;
    }

    .severity-count {
        color: #F1F5F9;
        font-weight: 600;
        min-width: 30px;
        text-align: right;
    }

    .severity-percent {
        color: #64748B;
        min-width: 50px;
        text-align: right;
    }

    .ranking-list {
        max-height: 340px;
        overflow-y: auto;
    }
    "#
}

fn severity_display(severity: &str) -> &str {
    match severity {
        "critical" => "严重",
        "major" => "主要",
        "minor" => "次要",
        "info" => "提示",
        _ => severity,
    }
}

fn severity_color(severity: &str) -> &str {
    match severity {
        "critical" => "#EF4444",
        "major" => "#F59E0B",
        "minor" => "#3B82F6",
        "info" => "#8B5CF6",
        _ => "#64748B",
    }
}

fn activity_type_class(type_: &str) -> &str {
    match type_ {
        "merge_request" => "mr",
        "comment" => "comment",
        "approval" => "approval",
        "issue" => "issue",
        "review" => "review",
        _ => "mr",
    }
}

fn activity_type_icon(type_: &str) -> &str {
    match type_ {
        "merge_request" => "📥",
        "comment" => "💬",
        "approval" => "✅",
        "issue" => "🐛",
        "review" => "🔍",
        _ => "📌",
    }
}

fn rank_class(rank: i32) -> &str {
    match rank {
        1 => "gold",
        2 => "silver",
        3 => "bronze",
        _ => "normal",
    }
}

fn format_time_ago(_time_str: &str) -> String {
    "2小时前".to_string()
}

fn create_issue_modal() -> Markup {
    html! {
        div class="modal-overlay" id="create-issue-modal" onclick="if(event.target === this) this.classList.remove('active')" {
            div class="modal" {
                div class="modal-header" {
                    h3 class="modal-title" { "创建问题" }
                    button class="modal-close" onclick="document.getElementById('create-issue-modal').classList.remove('active')" {
                        svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="18" height="18" {
                            line x1="18" y1="6" x2="6" y2="18";
                            line x1="6" y1="6" x2="18" y2="18";
                        }
                    }
                }
                div class="modal-body" {
                    form id="create-issue-form" {
                        div class="form-group" {
                            label class="form-label" {
                                "问题标题"
                                span class="required" { "*" }
                            }
                            input type="text" class="form-input" placeholder="简要描述问题" required;
                        }
                        div class="form-row" {
                            div class="form-group" {
                                label class="form-label" {
                                    "严重程度"
                                    span class="required" { "*" }
                                }
                                select class="form-select" required {
                                    option value="critical" { "🔴 严重 - 系统崩溃、数据丢失" }
                                    option value="major" { "🟠 主要 - 功能异常、性能问题" }
                                    option value="minor" { "🔵 次要 - 代码规范、边界处理" }
                                    option value="info" { "🟣 提示 - 优化建议、最佳实践" }
                                }
                            }
                            div class="form-group" {
                                label class="form-label" { "处理人" }
                                select class="form-select" {
                                    option value="" { "请选择" }
                                    option value="1" { "张三" }
                                    option value="2" { "李四" }
                                }
                            }
                        }
                        div class="form-group" {
                            label class="form-label" {
                                "问题描述"
                                span class="required" { "*" }
                            }
                            textarea class="form-textarea" placeholder="详细描述问题，包括复现步骤、期望行为等..." rows="4" required;
                        }
                        div class="form-group" {
                            label class="form-label" { "关联MR" }
                            select class="form-select" {
                                option value="" { "无" }
                                option value="1" { "feat: 添加用户认证模块" }
                                option value="2" { "fix: 修复登录页面样式问题" }
                            }
                        }
                    }
                }
                div class="modal-footer" {
                    button type="button" class="btn btn-secondary" onclick="document.getElementById('create-issue-modal').classList.remove('active')" { "取消" }
                    button type="submit" form="create-issue-form" class="btn btn-primary" { "创建问题" }
                }
            }
        }
    }
}
