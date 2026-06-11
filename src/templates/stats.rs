use maud::{html, Markup, PreEscaped};
use crate::models::stats::{
    CoverageTrend, ResponseTimeTrend, HeatmapData, 
    TeamRankingItem, IssueBySeverity, IssueByStatus, DashboardStats
};
use crate::models::repository::Repository;
use crate::templates::layout::LayoutContext;
use crate::templates::layout::base_layout;
use crate::templates::components::{
    stat_card, user_avatar, card, StatCard, TabData, tabs,
};

pub struct StatsPageContext {
    pub dashboard: DashboardStats,
    pub coverage_trend: Vec<CoverageTrend>,
    pub response_time_trend: Vec<ResponseTimeTrend>,
    pub heatmap_data: Vec<HeatmapData>,
    pub team_ranking: Vec<TeamRankingItem>,
    pub issues_by_severity: Vec<IssueBySeverity>,
    pub issues_by_status: Vec<IssueByStatus>,
    pub repositories: Vec<Repository>,
    pub current_period: String,
    pub current_repo_id: Option<String>,
    pub start_date: String,
    pub end_date: String,
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

fn coverage_trend_chart(data: &[CoverageTrend]) -> Markup {
    let width = 600;
    let height = 250;
    let padding = 40;
    let chart_width = width - padding * 2;
    let chart_height = height - padding * 2;

    if data.is_empty() {
        return html! {
            div class="flex items-center justify-center h-[250px] text-[#64748B]" {
                "暂无数据"
            }
        };
    }

    let max_value = 1.0;
    let min_value = 0.0;

    let points: Vec<(f64, f64)> = data.iter().enumerate().map(|(i, item)| {
        let x = padding as f64 + (i as f64 / (data.len() as f64 - 1.0)) * chart_width as f64;
        let y = padding as f64 + chart_height as f64 - ((item.coverage_rate - min_value) / (max_value - min_value)) * chart_height as f64;
        (x, y)
    }).collect();

    let path_d = points.iter().enumerate().map(|(i, (x, y))| {
        if i == 0 {
            format!("M {} {}", x, y)
        } else {
            format!("L {} {}", x, y)
        }
    }).collect::<Vec<_>>().join(" ");

    let area_d = format!(
        "{} L {} {} L {} {} Z",
        path_d,
        points.last().unwrap().0,
        padding as f64 + chart_height as f64,
        points.first().unwrap().0,
        padding as f64 + chart_height as f64
    );

    html! {
        div class="relative" {
            svg width="100%" height="250" viewBox=(format!("0 0 {} {}", width, height)) preserveAspectRatio="xMidYMid meet" {
                defs {
                    linearGradient id="coverageGradient" x1="0%" y1="0%" x2="0%" y2="100%" {
                        stop offset="0%" stop-color="#3B82F6" stop-opacity="0.3";
                        stop offset="100%" stop-color="#3B82F6" stop-opacity="0.0";
                    }
                    linearGradient id="lineGradient" x1="0%" y1="0%" x2="100%" y2="0%" {
                        stop offset="0%" stop-color="#3B82F6";
                        stop offset="100%" stop-color="#8B5CF6";
                    }
                }
                @for i in 0..=5 {
                    let y = padding as f64 + (i as f64 / 5.0) * chart_height as f64;
                    line x1=(padding) y1=(y) x2=(width - padding) y2=(y) stroke="#334155" stroke-width="1" stroke-dasharray="4,4";
                    text x=(padding - 10) y=(y + 4) text-anchor="end" fill="#64748B" font-size="10" {
                        (format!("{:.0}%", 100.0 - i as f64 * 20.0))
                    }
                }
                path d=(area_d) fill="url(#coverageGradient)";
                path d=(path_d) fill="none" stroke="url(#lineGradient)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round";
                @for (i, (point, item)) in points.iter().zip(data.iter()).enumerate() {
                    @if i % 5 == 0 || i == data.len() - 1 {
                        circle cx=(point.0) cy=(point.1) r="5" fill="#3B82F6" stroke="#1E293B" stroke-width="2" class="chart-point";
                        title { (format!("{}: {:.1}%", item.date, item.coverage_rate * 100.0)) }
                    }
                }
                @for (i, item) in data.iter().enumerate() {
                    @if i % 5 == 0 || i == data.len() - 1 {
                        let x = padding as f64 + (i as f64 / (data.len() as f64 - 1.0)) * chart_width as f64;
                        text x=(x) y=(height - 15) text-anchor="middle" fill="#64748B" font-size="10" {
                            (item.date.split('-').skip(1).collect::<Vec<_>>().join("/"))
                        }
                    }
                }
            }
        }
    }
}

fn response_time_chart(data: &[ResponseTimeTrend]) -> Markup {
    let width = 600;
    let height = 250;
    let padding = 40;
    let chart_width = width - padding * 2;
    let chart_height = height - padding * 2;

    if data.is_empty() {
        return html! {
            div class="flex items-center justify-center h-[250px] text-[#64748B]" {
                "暂无数据"
            }
        };
    }

    let max_value = data.iter().map(|d| d.avg_response_hours).fold(0.0, f64::max).max(1.0);
    let bar_width = (chart_width as f64 / data.len() as f64) * 0.7;
    let gap = (chart_width as f64 / data.len() as f64) * 0.3;

    html! {
        div class="relative" {
            svg width="100%" height="250" viewBox=(format!("0 0 {} {}", width, height)) preserveAspectRatio="xMidYMid meet" {
                defs {
                    linearGradient id="barGradient" x1="0%" y1="0%" x2="0%" y2="100%" {
                        stop offset="0%" stop-color="#10B981";
                        stop offset="100%" stop-color="#059669";
                    }
                }
                @for i in 0..=5 {
                    let y = padding as f64 + (i as f64 / 5.0) * chart_height as f64;
                    let value = max_value - (i as f64 / 5.0) * max_value;
                    line x1=(padding) y1=(y) x2=(width - padding) y2=(y) stroke="#334155" stroke-width="1" stroke-dasharray="4,4";
                    text x=(padding - 10) y=(y + 4) text-anchor="end" fill="#64748B" font-size="10" {
                        (format!("{:.0}h", value))
                    }
                }
                @for (i, item) in data.iter().enumerate() {
                    let x = padding as f64 + i as f64 * (bar_width + gap) + gap / 2.0;
                    let bar_height = (item.avg_response_hours / max_value) * chart_height as f64;
                    let y = padding as f64 + chart_height as f64 - bar_height;
                    rect x=(x) y=(y) width=(bar_width) height=(bar_height) rx="4" fill="url(#barGradient)" class="chart-bar" {
                        title { (format!("{}: {:.1} 小时", item.date, item.avg_response_hours)) }
                    }
                    @if i % 3 == 0 || i == data.len() - 1 {
                        text x=(x + bar_width / 2.0) y=(height - 15) text-anchor="middle" fill="#64748B" font-size="10" {
                            (item.date.split('-').skip(1).collect::<Vec<_>>().join("/"))
                        }
                    }
                }
            }
        }
    }
}

fn heatmap_chart(data: &[HeatmapData]) -> Markup {
    if data.is_empty() {
        return html! {
            div class="flex items-center justify-center h-[300px] text-[#64748B]" {
                "暂无数据"
            }
        };
    }

    let max_density = data.iter().map(|d| d.density_score).fold(0.0, f64::max).max(1.0);

    html! {
        div class="overflow-x-auto" {
            div class="grid gap-1 min-w-[500px]" style="grid-template-columns: 1fr auto auto;" {
                div class="text-xs text-[#64748B] pb-2 font-medium" { "文件路径" }
                div class="text-xs text-[#64748B] pb-2 font-medium text-center" { "问题数" }
                div class="text-xs text-[#64748B] pb-2 font-medium text-center" { "密度" }
                @for item in data {
                    let intensity = (item.density_score / max_density).min(1.0);
                    let r = (16.0 + intensity * 239.0) as u32;
                    let g = (185.0 - intensity * 90.0) as u32;
                    let b = (129.0 - intensity * 85.0) as u32;
                    let bg_color = format!("rgba({}, {}, {}, 0.3)", r, g, b);
                    let border_color = format!("rgba({}, {}, {}, 0.5)", r, g, b);
                    div class="flex items-center gap-2 py-2 px-3 rounded-lg hover:bg-white/5 transition-colors" style={"background: " (bg_color) "; border-left: 3px solid " (border_color) ";"} {
                        span class="text-[#94A3B8] text-sm font-mono truncate" title=(item.file_path) {
                            (item.file_path)
                        }
                    }
                    div class="flex items-center justify-center py-2 px-3 rounded-lg hover:bg-white/5 transition-colors" style={"background: " (bg_color) ";"} {
                        span class="text-white text-sm font-medium" { (item.issue_count) }
                    }
                    div class="flex items-center justify-center py-2 px-3 rounded-lg hover:bg-white/5 transition-colors" style={"background: " (bg_color) ";"} {
                        span class="text-white text-sm font-medium" { (format!("{:.2}", item.density_score)) }
                        title { (format!("问题密度: {:.2} 个/千行", item.density_score)) }
                    }
                }
            }
            div class="flex items-center justify-end gap-2 mt-4 pt-4 border-t border-[#334155]" {
                span class="text-xs text-[#64748B]" { "低" }
                div class="flex h-3 rounded overflow-hidden" {
                    div class="w-8" style="background: rgba(16, 185, 129, 0.3);" {}
                    div class="w-8" style="background: rgba(59, 130, 246, 0.3);" {}
                    div class="w-8" style="background: rgba(245, 158, 11, 0.3);" {}
                    div class="w-8" style="background: rgba(239, 68, 68, 0.3);" {}
                }
                span class="text-xs text-[#64748B]" { "高" }
            }
        }
    }
}

fn severity_pie_chart(data: &[IssueBySeverity]) -> Markup {
    let total: i64 = data.iter().map(|d| d.count).sum();
    
    if total == 0 {
        return html! {
            div class="flex items-center justify-center h-[300px] text-[#64748B]" {
                "暂无数据"
            }
        };
    }

    let colors = [
        ("critical", "#EF4444"),
        ("major", "#F59E0B"),
        ("minor", "#3B82F6"),
        ("info", "#8B5CF6"),
    ];

    let mut current_angle = 0.0;
    let mut paths = Vec::new();
    let center_x = 150.0;
    let center_y = 150.0;
    let radius = 100.0;

    for item in data {
        let color = colors.iter().find(|(k, _)| k == &item.severity.as_str()).map(|(_, c)| c).unwrap_or("#64748B");
        let angle = (item.count as f64 / total as f64) * 360.0;
        let end_angle = current_angle + angle;
        
        let start_rad = (current_angle - 90.0).to_radians();
        let end_rad = (end_angle - 90.0).to_radians();
        
        let x1 = center_x + radius * start_rad.cos();
        let y1 = center_y + radius * start_rad.sin();
        let x2 = center_x + radius * end_rad.cos();
        let y2 = center_y + radius * end_rad.sin();
        
        let large_arc = if angle > 180.0 { 1 } else { 0 };
        
        let path_data = format!(
            "M {},{} A {},{} 0 {},1 {},{} L {},{} Z",
            x1, y1, radius, radius, large_arc, x2, y2, center_x, center_y
        );
        
        paths.push((path_data, color, item.severity.clone(), item.count));
        current_angle = end_angle;
    }

    html! {
        svg width="300" height="300" viewBox="0 0 300 300" class="mx-auto" {
            @for (path, color, label, count) in &paths {
                path d=(path) fill=(color) {
                    title { (format!("{}: {}", label, count)) }
                }
            }
        }
    }
}