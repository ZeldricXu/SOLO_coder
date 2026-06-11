use maud::{html, Markup, PreEscaped};
use uuid::Uuid;
use crate::models::org_stats::{
    OrgStatsOverview, RepoHealthRanking, TeamContributionRanking, IssueTypeTrendCompare,
};
use crate::templates::layout::LayoutContext;
use crate::templates::layout::base_layout;
use crate::templates::components::{
    stat_card, StatCard, user_avatar, card,
};

pub struct OrgStatsPageContext {
    pub organization_id: Uuid,
    pub benchmark: String,
    pub overview: OrgStatsOverview,
    pub repo_health: RepoHealthRanking,
    pub contributor_ranking: TeamContributionRanking,
    pub issue_trend: IssueTypeTrendCompare,
    pub can_refresh: bool,
}

fn format_percentage(value: f64) -> String {
    format!("{:.1}%", value * 100.0)
}

fn format_number(value: i64) -> String {
    if value >= 1000 {
        format!("{:.1}K", value as f64 / 1000.0)
    } else {
        value.to_string()
    }
}

fn get_rank_medal(rank: i32) -> &'static str {
    match rank {
        1 => "🥇",
        2 => "🥈",
        3 => "🥉",
        _ => "",
    }
}

fn get_trend_class(trend: &str) -> &'static str {
    match trend {
        "up" => "text-emerald-400",
        "down" => "text-red-400",
        _ => "text-gray-400",
    }
}

fn get_trend_icon(trend: &str) -> &'static str {
    match trend {
        "up" => "↑",
        "down" => "↓",
        _ => "→",
    }
}

fn overview_cards(overview: &OrgStatsOverview) -> Markup {
    let change_pct = overview.compared_last_period * 100.0;
    let is_positive = change_pct >= 0.0;
    let change_text = format!("{:+.1}%", change_pct.abs());

    let cards = vec![
        StatCard {
            title: "总仓库数".to_string(),
            value: format_number(overview.total_repos),
            trend: Some((is_positive, change_text.clone())),
            gradient_from: "#3B82F6".to_string(),
            gradient_to: "#8B5CF6".to_string(),
            icon: "📦".to_string(),
        },
        StatCard {
            title: "总成员数".to_string(),
            value: format_number(overview.total_members),
            trend: Some((is_positive, change_text.clone())),
            gradient_from: "#10B981".to_string(),
            gradient_to: "#059669".to_string(),
            icon: "👥".to_string(),
        },
        StatCard {
            title: "平均评审覆盖率".to_string(),
            value: format_percentage(overview.coverage_rate_avg),
            trend: Some((is_positive, change_text.clone())),
            gradient_from: "#F59E0B".to_string(),
            gradient_to: "#D97706".to_string(),
            icon: "📊".to_string(),
        },
        StatCard {
            title: "平均响应时间".to_string(),
            value: format!("{:.1}h", overview.avg_response_hours_avg),
            trend: Some((!is_positive, change_text.clone())),
            gradient_from: "#EF4444".to_string(),
            gradient_to: "#DC2626".to_string(),
            icon: "⏱️".to_string(),
        },
        StatCard {
            title: "总问题数".to_string(),
            value: format_number(overview.total_issues_period),
            trend: Some((!is_positive, change_text.clone())),
            gradient_from: "#8B5CF6".to_string(),
            gradient_to: "#7C3AED".to_string(),
            icon: "🐛".to_string(),
        },
        StatCard {
            title: "平均健康度分数".to_string(),
            value: format!("{:.0}", overview.health_score_avg * 100.0),
            trend: Some((is_positive, change_text)),
            gradient_from: "#06B6D4".to_string(),
            gradient_to: "#0891B2".to_string(),
            icon: "💚".to_string(),
        },
    ];

    html! {
        div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-4 mb-6" {
            @for card_data in cards {
                (stat_card(card_data))
            }
        }
    }
}

fn repo_health_ranking(repo_health: &RepoHealthRanking) -> Markup {
    let rows: Vec<Markup> = repo_health.items.iter().map(|item| {
        let medal = get_rank_medal(item.rank);
        let trend_class = get_trend_class(&item.trend);
        let trend_icon = get_trend_icon(&item.trend);

        html! {
            tr class="hover:bg-white/5 transition-colors" {
                td class="px-4 py-3 text-sm" {
                    @if item.rank <= 3 {
                        span class="text-2xl" { (medal) }
                    } @else {
                        span class="text-[#64748B] font-medium" { (item.rank) }
                    }
                }
                td class="px-4 py-3 text-sm text-[#CBD5E1] font-medium" { (item.repo_name) }
                td class="px-4 py-3 text-sm text-[#CBD5E1]" { (format_percentage(item.coverage_rate)) }
                td class="px-4 py-3 text-sm text-[#CBD5E1]" { (format!("{:.1}h", item.avg_response_hours)) }
                td class="px-4 py-3 text-sm text-[#CBD5E1]" { (format!("{:.2}", item.issue_density)) }
                td class="px-4 py-3 text-sm" {
                    div class="flex items-center gap-2" {
                        div class="w-16 h-2 bg-[#334155] rounded-full overflow-hidden" {
                            div
                                class="h-full rounded-full"
                                style={
                                    "width: " (item.health_score * 100.0) "%; "
                                    @if item.health_score >= 0.7 { "background: linear-gradient(90deg, #10B981, #34D399);" }
                                    @else if item.health_score >= 0.4 { "background: linear-gradient(90deg, #F59E0B, #FBBF24);" }
                                    @else { "background: linear-gradient(90deg, #EF4444, #F87171);" }
                                }
                            {}
                        }
                        span class="text-[#CBD5E1] font-medium" { (format!("{:.0}", item.health_score * 100.0)) }
                    }
                }
                td class={"px-4 py-3 text-sm font-medium " (trend_class)} {
                    (trend_icon)
                }
            }
        }
    }).collect();

    let headers = vec![
        "排名".to_string(),
        "仓库".to_string(),
        "覆盖率".to_string(),
        "响应时间".to_string(),
        "问题密度".to_string(),
        "健康度".to_string(),
        "趋势".to_string(),
    ];

    html! {
        (card(Some("仓库健康度排行"), html! {
            div class="overflow-x-auto" {
                table class="w-full" {
                    thead class="bg-[#0F172A]/50" {
                        tr {
                            @for header in headers {
                                th class="px-4 py-3 text-left text-xs font-medium text-[#94A3B8] uppercase tracking-wider border-b border-[#334155]" {
                                    (header)
                                }
                            }
                        }
                    }
                    tbody class="divide-y divide-[#334155]/50" {
                        @for row in rows {
                            (row)
                        }
                    }
                }
            }
        }, Some(html! {
            span class="text-xs text-[#64748B]" { "基准日: " (repo_health.benchmark_date) }
        })))
    }
}

fn contributor_ranking(ranking: &TeamContributionRanking) -> Markup {
    let items: Vec<Markup> = ranking.items.iter().enumerate().map(|(idx, item)| {
        let rank = (idx + 1) as i32;
        let medal = get_rank_medal(rank);

        html! {
            div class="flex items-center gap-4 p-3 hover:bg-white/5 rounded-lg transition-colors" {
                div class="w-8 text-center flex-shrink-0" {
                    @if rank <= 3 {
                        span class="text-xl" { (medal) }
                    } @else {
                        span class="text-[#64748B] font-medium" { (rank) }
                    }
                }
                (user_avatar(&item.username, item.avatar_url.as_deref(), 40))
                div class="flex-1 min-w-0" {
                    div class="font-medium text-white truncate" { (item.username) }
                    div class="text-xs text-[#64748B] flex gap-3 mt-1" {
                        span { "评审 " (item.reviews_done) }
                        span { "问题 " (item.issues_found) }
                        span { "修复 " (item.issues_fixed) }
                    }
                }
                div class="text-right flex-shrink-0" {
                    div class="text-lg font-bold text-[#3B82F6]" { (format!("{:.0}", item.contribution_score)) }
                    div class="text-xs text-[#64748B]" { "贡献分" }
                }
            }
        }
    }).collect();

    html! {
        (card(Some("成员贡献度排行"), html! {
            div class="space-y-1" {
                @for item in items {
                    (item)
                }
            }
        }, Some(html! {
            span class="text-xs text-[#64748B]" { "团队平均: " (format!("{:.0}", ranking.team_avg_score)) }
        })))
    }
}

fn issue_type_trend_chart(trend: &IssueTypeTrendCompare) -> Markup {
    let severity_colors = [
        ("critical", "#EF4444", "严重"),
        ("major", "#F59E0B", "重要"),
        ("minor", "#3B82F6", "次要"),
        ("info", "#8B5CF6", "提示"),
    ];

    let current_data: std::collections::HashMap<&str, i64> = trend.current.iter()
        .map(|p| (p.severity.as_str(), p.issue_count))
        .collect();
    let previous_data: std::collections::HashMap<&str, i64> = trend.previous.iter()
        .map(|p| (p.severity.as_str(), p.issue_count))
        .collect();

    let max_value = severity_colors.iter()
        .map(|(s, _, _)| {
            let c = current_data.get(*s).copied().unwrap_or(0);
            let p = previous_data.get(*s).copied().unwrap_or(0);
            c.max(p)
        })
        .max()
        .unwrap_or(1)
        .max(1);

    let bar_max_height = 200;

    html! {
        (card(Some("问题类型趋势对比"), html! {
            div class="space-y-6" {
                div class="flex items-center justify-center gap-6 text-sm" {
                    div class="flex items-center gap-2" {
                        div class="w-4 h-4 rounded bg-[#3B82F6]" {}
                        span class="text-[#94A3B8]" { "本期" }
                    }
                    div class="flex items-center gap-2" {
                        div class="w-4 h-4 rounded bg-[#64748B]" {}
                        span class="text-[#94A3B8]" { "上一期" }
                    }
                }

                div class={ "flex items-end justify-center gap-8 h-" (bar_max_height + 40) "px pt-4" } {
                    @for (severity, color, label) in severity_colors.iter() {
                        @let current_count = current_data.get(*severity).copied().unwrap_or(0);
                        @let previous_count = previous_data.get(*severity).copied().unwrap_or(0);

                        @let current_height = ((current_count as f64 / max_value as f64) * bar_max_height as f64).max(4.0);
                        @let previous_height = ((previous_count as f64 / max_value as f64) * bar_max_height as f64).max(4.0);

                        div class="flex flex-col items-center gap-2" {
                            div class={ "flex items-end gap-2 h-" (bar_max_height) "px" } {
                                div class="flex flex-col items-center" {
                                    span class="text-xs text-[#94A3B8] mb-1" { (current_count) }
                                    div
                                        class="w-10 rounded-t-lg transition-all duration-500"
                                        style={"height: " (current_height) "px; background: " (color) "; opacity: 0.9;"}
                                        title={(label) " 本期: " (current_count)}
                                    {}
                                }
                                div class="flex flex-col items-center" {
                                    span class="text-xs text-[#64748B] mb-1" { (previous_count) }
                                    div
                                        class="w-10 rounded-t-lg transition-all duration-500"
                                        style={"height: " (previous_height) "px; background: #475569; opacity: 0.6;"}
                                        title={(label) " 上一期: " (previous_count)}
                                    {}
                                }
                            }
                            div class="text-xs text-[#CBD5E1] font-medium" { (label) }
                        }
                    }
                }

                if !trend.periods.is_empty() {
                    div class="flex items-center justify-center gap-4 text-xs text-[#64748B] pt-4 border-t border-[#334155]" {
                        span { "上一期: " (trend.periods.get(0).unwrap_or(&String::new())) }
                        span { "→" }
                        span { "本期: " (trend.periods.get(1).unwrap_or(&String::new())) }
                    }
                }
            }
        }, None))
    }
}

pub fn org_stats_page(ctx: LayoutContext, page_ctx: &OrgStatsPageContext) -> Markup {
    base_layout(ctx, html! {
        style { (org_stats_styles()) }

        div class="mb-6" {
            div class="flex items-center justify-between mb-4" {
                div {
                    h1 class="text-2xl font-bold text-white mb-1" { "组织级统计" }
                    p class="text-[#94A3B8] text-sm" { "组织整体研发效能与代码质量数据分析" }
                }
                @if page_ctx.can_refresh {
                    button
                        onclick="refreshMaterializedViews()"
                        class="px-4 py-2 bg-[#8B5CF6] hover:bg-[#7C3AED] text-white rounded-lg font-medium transition-colors flex items-center gap-2"
                    {
                        "🔄"
                        "刷新物化视图"
                    }
                }
            }

            div class="flex items-center gap-2 p-1 bg-[#1E293B] rounded-lg border border-[#334155] inline-flex" {
                @let benchmarks = [
                    ("7d", "7天"),
                    ("30d", "30天"),
                    ("90d", "90天"),
                    ("custom", "自定义"),
                ];
                @for (value, label) in benchmarks.iter() {
                    a
                        href={"?benchmark=" (value)}
                        class={
                            "px-4 py-2 text-sm rounded-md transition-colors"
                            @if page_ctx.benchmark == *value {
                                "bg-[#3B82F6] text-white"
                            } @else {
                                "text-[#94A3B8] hover:text-white"
                            }
                        }
                    {
                        (label)
                    }
                }
            }
        }

        (overview_cards(&page_ctx.overview))

        div class="grid grid-cols-1 xl:grid-cols-2 gap-6 mb-6" {
            (repo_health_ranking(&page_ctx.repo_health))
            (contributor_ranking(&page_ctx.contributor_ranking))
        }

        (issue_type_trend_chart(&page_ctx.issue_trend))

        script {
            (PreEscaped(r#"
                function refreshMaterializedViews() {
                    if (confirm('确定要刷新物化视图吗？这可能需要一些时间。')) {
                        fetch('/api/org-stats/refresh', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' }
                        }).then(r => {
                            if (r.ok) {
                                alert('刷新已启动，请稍后查看结果。');
                            } else {
                                alert('刷新失败');
                            }
                        });
                    }
                }
            "#))
        }
    })
}

fn org_stats_styles() -> &'static str {
    r#"
    .h-\[240px\] {
        height: 240px;
    }
    "#
}
