use chrono::{DateTime, Duration, NaiveDate, Utc};
use uuid::Uuid;

use crate::models::stats::{
    CoverageTrend, DashboardStats, ExportRequest, HeatmapData, IssueBySeverity, IssueByStatus,
    PersonalStats, ResponseTimeTrend, ReviewStats, StatsQuery, TeamRankingItem,
};
use crate::providers::MinioClient;
use crate::repositories::StatsRepository;
use crate::utils::{AppError, AppResult};

#[derive(Clone)]
pub struct StatsService {
    stats_repo: StatsRepository,
    minio_client: MinioClient,
}

impl StatsService {
    pub fn new(stats_repo: StatsRepository, minio_client: MinioClient) -> Self {
        Self {
            stats_repo,
            minio_client,
        }
    }

    pub async fn get_review_stats(
        &self,
        query: StatsQuery,
        organization_id: Uuid,
    ) -> AppResult<ReviewStats> {
        let query = query.sanitize();
        let (start_date, end_date) = self.parse_date_range(&query.start_date, &query.end_date)?;

        self.stats_repo
            .get_review_stats(
                start_date,
                end_date,
                query.repo_id,
                query.team_id,
                organization_id,
            )
            .await
    }

    pub async fn get_personal_stats(
        &self,
        user_id: Uuid,
        query: StatsQuery,
    ) -> AppResult<PersonalStats> {
        let query = query.sanitize();
        let (start_date, end_date) = self.parse_date_range(&query.start_date, &query.end_date)?;

        self.stats_repo
            .get_personal_stats(user_id, start_date, end_date)
            .await
    }

    pub async fn get_heatmap_data(
        &self,
        query: StatsQuery,
        organization_id: Uuid,
    ) -> AppResult<Vec<HeatmapData>> {
        let query = query.sanitize();
        let (start_date, end_date) = self.parse_date_range(&query.start_date, &query.end_date)?;

        let mut data = self
            .stats_repo
            .get_heatmap_data(organization_id, query.repo_id, start_date, end_date)
            .await?;

        let max_density = data
            .iter()
            .map(|d| d.density_score)
            .fold(0.0_f64, |a, b| a.max(b));

        for item in data.iter_mut() {
            item.density_score = self.calculate_density_score(item.issue_count, item.review_count);
            item.color_hex = self.generate_heatmap_color(item.density_score, max_density);
        }

        Ok(data)
    }

    pub async fn get_coverage_trend(
        &self,
        period: &str,
        repo_id: Option<Uuid>,
        organization_id: Uuid,
    ) -> AppResult<Vec<CoverageTrend>> {
        let days = match period {
            "day" => 7,
            "week" => 30,
            "month" => 90,
            "quarter" => 180,
            "year" => 365,
            _ => 30,
        };

        let trends = self
            .stats_repo
            .get_coverage_trend(organization_id, days, repo_id)
            .await?;

        Ok(self.aggregate_trend_by_period(trends, period))
    }

    pub async fn get_response_time_trend(
        &self,
        period: &str,
        repo_id: Option<Uuid>,
        organization_id: Uuid,
    ) -> AppResult<Vec<ResponseTimeTrend>> {
        let days = match period {
            "day" => 7,
            "week" => 30,
            "month" => 90,
            "quarter" => 180,
            "year" => 365,
            _ => 30,
        };

        let trends = self
            .stats_repo
            .get_response_time_trend(organization_id, days, repo_id)
            .await?;

        Ok(self.aggregate_response_trend_by_period(trends, period))
    }

    pub async fn get_dashboard_stats(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
    ) -> AppResult<DashboardStats> {
        let mut stats = self
            .stats_repo
            .get_dashboard_stats(user_id, organization_id)
            .await?;

        let activity = self.stats_repo.get_recent_activity(organization_id, 20).await?;
        stats.recent_activity = activity;

        Ok(stats)
    }

    pub async fn get_recent_activity(
        &self,
        organization_id: Uuid,
        limit: i32,
    ) -> AppResult<Vec<crate::models::stats::ActivityItem>> {
        self.stats_repo
            .get_recent_activity(organization_id, limit)
            .await
    }

    pub async fn get_team_ranking(
        &self,
        query: StatsQuery,
        organization_id: Uuid,
        limit: i32,
    ) -> AppResult<Vec<TeamRankingItem>> {
        let query = query.sanitize();
        let (start_date, end_date) = self.parse_date_range(&query.start_date, &query.end_date)?;

        self.stats_repo
            .get_team_ranking(organization_id, start_date, end_date, limit)
            .await
    }

    pub async fn get_issues_by_severity(
        &self,
        query: StatsQuery,
        organization_id: Uuid,
    ) -> AppResult<Vec<IssueBySeverity>> {
        let query = query.sanitize();
        let (start_date, end_date) = self.parse_date_range(&query.start_date, &query.end_date)?;

        self.stats_repo
            .get_issues_by_severity(organization_id, start_date, end_date)
            .await
    }

    pub async fn get_issues_by_status(
        &self,
        query: StatsQuery,
        organization_id: Uuid,
    ) -> AppResult<Vec<IssueByStatus>> {
        let query = query.sanitize();
        let (start_date, end_date) = self.parse_date_range(&query.start_date, &query.end_date)?;

        self.stats_repo
            .get_issues_by_status(organization_id, start_date, end_date)
            .await
    }

    pub async fn export_stats_report(
        &self,
        req: ExportRequest,
        organization_id: Uuid,
    ) -> AppResult<String> {
        let format = req.format.to_lowercase();
        if format != "csv" && format != "pdf" {
            return Err(AppError::Validation(format!(
                "Unsupported format: {}. Supported formats: csv, pdf",
                req.format
            )));
        }

        let (start_date, end_date) =
            self.parse_date_range(&Some(req.start_date.clone()), &Some(req.end_date.clone()))?;

        let review_stats = self
            .stats_repo
            .get_review_stats(start_date, end_date, req.repo_id, None, organization_id)
            .await?;

        let issues_by_severity = self
            .stats_repo
            .get_issues_by_severity(organization_id, start_date, end_date)
            .await?;

        let team_ranking = self
            .stats_repo
            .get_team_ranking(organization_id, start_date, end_date, 10)
            .await?;

        let report_id = Uuid::new_v4();

        if format == "csv" {
            let csv_content = self.generate_csv_report(
                &review_stats,
                &issues_by_severity,
                &team_ranking,
                &req.include,
            );
            self.minio_client
                .export_report_csv(report_id, &csv_content)
                .await
        } else {
            let pdf_content = self.generate_pdf_report(
                &review_stats,
                &issues_by_severity,
                &team_ranking,
                &req.include,
                &req.start_date,
                &req.end_date,
            );
            self.minio_client
                .export_report_pdf(report_id, &pdf_content)
                .await
        }
    }

    pub fn calculate_density_score(&self, issue_count: i64, review_count: i64) -> f64 {
        if review_count == 0 {
            return if issue_count > 0 { 10.0 } else { 0.0 };
        }
        let score = (issue_count as f64 * 2.0 + review_count as f64) / review_count as f64;
        (score * 100.0).round() / 100.0
    }

    pub fn generate_heatmap_color(&self, density: f64, max_density: f64) -> String {
        if max_density <= 0.0 {
            return "#E5E7EB".to_string();
        }

        let ratio = density / max_density;

        if ratio > 0.75 {
            "#EF4444".to_string()
        } else if ratio > 0.5 {
            "#F59E0B".to_string()
        } else if ratio > 0.25 {
            "#EAB308".to_string()
        } else if ratio > 0.0 {
            "#22C55E".to_string()
        } else {
            "#E5E7EB".to_string()
        }
    }

    fn parse_date_range(
        &self,
        start_date: &Option<String>,
        end_date: &Option<String>,
    ) -> AppResult<(Option<DateTime<Utc>>, Option<DateTime<Utc>>)> {
        let parse_date = |s: &str| -> AppResult<DateTime<Utc>> {
            NaiveDate::parse_from_str(s, "%Y-%m-%d")
                .map(|d| d.and_hms_opt(0, 0, 0).unwrap().and_utc())
                .map_err(|e| AppError::Validation(format!("Invalid date format: {}", e)))
        };

        let start = match start_date {
            Some(s) => Some(parse_date(s)?),
            None => None,
        };

        let end = match end_date {
            Some(s) => {
                let mut date = parse_date(s)?;
                date = date + Duration::days(1) - Duration::seconds(1);
                Some(date)
            }
            None => None,
        };

        Ok((start, end))
    }

    fn aggregate_trend_by_period(
        &self,
        trends: Vec<CoverageTrend>,
        period: &str,
    ) -> Vec<CoverageTrend> {
        match period {
            "day" => trends,
            "week" | "month" | "quarter" | "year" => {
                let mut aggregated: std::collections::HashMap<String, CoverageTrend> =
                    std::collections::HashMap::new();

                for trend in trends {
                    let key = self.get_period_key(&trend.date, period);
                    let entry = aggregated.entry(key.clone()).or_insert(CoverageTrend {
                        date: key,
                        coverage_rate: 0.0,
                        total_mrs: 0,
                        reviewed_mrs: 0,
                    });

                    entry.total_mrs += trend.total_mrs;
                    entry.reviewed_mrs += trend.reviewed_mrs;
                }

                let mut result: Vec<CoverageTrend> = aggregated
                    .into_values()
                    .map(|mut t| {
                        t.coverage_rate = if t.total_mrs > 0 {
                            (t.reviewed_mrs as f64 / t.total_mrs as f64 * 100.0 * 100.0).round()
                                / 100.0
                        } else {
                            0.0
                        };
                        t
                    })
                    .collect();

                result.sort_by(|a, b| a.date.cmp(&b.date));
                result
            }
            _ => trends,
        }
    }

    fn aggregate_response_trend_by_period(
        &self,
        trends: Vec<ResponseTimeTrend>,
        period: &str,
    ) -> Vec<ResponseTimeTrend> {
        match period {
            "day" => trends,
            "week" | "month" | "quarter" | "year" => {
                let mut aggregated: std::collections::HashMap<
                    String,
                    (Vec<f64>, Vec<f64>),
                > = std::collections::HashMap::new();

                for trend in trends {
                    let key = self.get_period_key(&trend.date, period);
                    let entry = aggregated.entry(key.clone()).or_default();
                    entry.0.push(trend.avg_response_hours);
                    entry.1.push(trend.median_response_hours);
                }

                let mut result: Vec<ResponseTimeTrend> = aggregated
                    .into_iter()
                    .map(|(date, (avgs, medians))| {
                        let avg = if avgs.is_empty() {
                            0.0
                        } else {
                            avgs.iter().sum::<f64>() / avgs.len() as f64
                        };
                        let median = if medians.is_empty() {
                            0.0
                        } else {
                            let mut sorted = medians.clone();
                            sorted.sort_by(|a, b| a.partial_cmp(b).unwrap());
                            let mid = sorted.len() / 2;
                            if sorted.len() % 2 == 0 {
                                (sorted[mid - 1] + sorted[mid]) / 2.0
                            } else {
                                sorted[mid]
                            }
                        };
                        ResponseTimeTrend {
                            date,
                            avg_response_hours: (avg * 100.0).round() / 100.0,
                            median_response_hours: (median * 100.0).round() / 100.0,
                        }
                    })
                    .collect();

                result.sort_by(|a, b| a.date.cmp(&b.date));
                result
            }
            _ => trends,
        }
    }

    fn get_period_key(&self, date_str: &str, period: &str) -> String {
        let date = NaiveDate::parse_from_str(date_str, "%Y-%m-%d").unwrap_or_else(|_| Utc::now().date_naive());

        match period {
            "week" => {
                let week_start = date - Duration::days(date.weekday().num_days_from_monday() as i64);
                week_start.format("%Y-%m-%d").to_string()
            }
            "month" => date.format("%Y-%m").to_string(),
            "quarter" => {
                let quarter = (date.month() - 1) / 3 + 1;
                format!("{}-Q{}", date.year(), quarter)
            }
            "year" => date.format("%Y").to_string(),
            _ => date_str.to_string(),
        }
    }

    fn generate_csv_report(
        &self,
        review_stats: &ReviewStats,
        issues_by_severity: &[IssueBySeverity],
        team_ranking: &[TeamRankingItem],
        include: &[String],
    ) -> String {
        let mut csv = String::new();

        if include.is_empty() || include.iter().any(|i| i == "overview") {
            csv.push_str("=== 评审概览 ===\n");
            csv.push_str("指标,数值\n");
            csv.push_str(&format!("总MR数,{}\n", review_stats.total_mrs));
            csv.push_str(&format!("已评审MR数,{}\n", review_stats.reviewed_mrs));
            csv.push_str(&format!("覆盖率,{:.2}%\n", review_stats.coverage_rate));
            csv.push_str(&format!(
                "平均响应时间(小时),{:.2}\n",
                review_stats.avg_response_time_hours
            ));
            csv.push_str(&format!("总问题数,{}\n", review_stats.total_issues));
            csv.push_str(&format!("问题密度,{:.2}\n", review_stats.issue_density));
            csv.push_str("\n");
        }

        if include.is_empty() || include.iter().any(|i| i == "severity") {
            csv.push_str("=== 问题严重程度分布 ===\n");
            csv.push_str("严重程度,数量,占比(%)\n");
            for item in issues_by_severity {
                csv.push_str(&format!(
                    "{},{},{:.2}\n",
                    item.severity, item.count, item.percentage
                ));
            }
            csv.push_str("\n");
        }

        if include.is_empty() || include.iter().any(|i| i == "ranking") {
            csv.push_str("=== 团队排行榜 ===\n");
            csv.push_str("排名,用户ID,用户名,评审数,发现问题数,评分\n");
            for item in team_ranking {
                csv.push_str(&format!(
                    "{},{},{},{},{},{:.2}\n",
                    item.rank, item.user_id, item.username, item.reviews_count, item.issues_found, item.score
                ));
            }
        }

        csv
    }

    fn generate_pdf_report(
        &self,
        review_stats: &ReviewStats,
        issues_by_severity: &[IssueBySeverity],
        team_ranking: &[TeamRankingItem],
        include: &[String],
        start_date: &str,
        end_date: &str,
    ) -> Vec<u8> {
        let mut html = String::new();
        html.push_str(r#"<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; padding: 20px; }
        h1 { color: #1a1a2e; border-bottom: 3px solid #16213e; padding-bottom: 10px; }
        h2 { color: #16213e; margin-top: 30px; }
        .summary-box { background: #f8f9fa; padding: 20px; border-radius: 8px; margin: 20px 0; }
        .summary-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 15px; }
        .summary-item { text-align: center; padding: 15px; background: white; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .summary-value { font-size: 24px; font-weight: bold; color: #0f3460; }
        .summary-label { font-size: 12px; color: #666; margin-top: 5px; }
        table { width: 100%; border-collapse: collapse; margin: 20px 0; }
        th, td { padding: 12px; text-align: left; border-bottom: 1px solid #ddd; }
        th { background: #16213e; color: white; }
        tr:nth-child(even) { background: #f8f9fa; }
        .critical { color: #dc3545; font-weight: bold; }
        .major { color: #fd7e14; font-weight: bold; }
        .minor { color: #ffc107; font-weight: bold; }
        .info { color: #28a745; font-weight: bold; }
        .date-range { color: #666; font-size: 14px; margin-bottom: 20px; }
        .rank-1 { background: linear-gradient(135deg, #ffd700, #ffec8b) !important; }
        .rank-2 { background: linear-gradient(135deg, #c0c0c0, #e8e8e8) !important; }
        .rank-3 { background: linear-gradient(135deg, #cd7f32, #daa520) !important; }
    </style>
</head>
<body>"#);

        html.push_str(&format!("<h1>📊 代码评审统计报表</h1>"));
        html.push_str(&format!(
            "<div class=\"date-range\">统计周期: {} 至 {}</div>",
            start_date, end_date
        ));

        if include.is_empty() || include.iter().any(|i| i == "overview") {
            html.push_str("<h2>📈 评审概览</h2>");
            html.push_str("<div class=\"summary-box\">");
            html.push_str("<div class=\"summary-grid\">");
            html.push_str(&format!(
                "<div class=\"summary-item\"><div class=\"summary-value\">{}</div><div class=\"summary-label\">总MR数</div></div>",
                review_stats.total_mrs
            ));
            html.push_str(&format!(
                "<div class=\"summary-item\"><div class=\"summary-value\">{}</div><div class=\"summary-label\">已评审MR</div></div>",
                review_stats.reviewed_mrs
            ));
            html.push_str(&format!(
                "<div class=\"summary-item\"><div class=\"summary-value\">{:.1}%</div><div class=\"summary-label\">评审覆盖率</div></div>",
                review_stats.coverage_rate
            ));
            html.push_str(&format!(
                "<div class=\"summary-item\"><div class=\"summary-value\">{:.1}h</div><div class=\"summary-label\">平均响应时间</div></div>",
                review_stats.avg_response_time_hours
            ));
            html.push_str(&format!(
                "<div class=\"summary-item\"><div class=\"summary-value\">{}</div><div class=\"summary-label\">总问题数</div></div>",
                review_stats.total_issues
            ));
            html.push_str(&format!(
                "<div class=\"summary-item\"><div class=\"summary-value\">{:.2}</div><div class=\"summary-label\">问题密度</div></div>",
                review_stats.issue_density
            ));
            html.push_str("</div></div>");
        }

        if include.is_empty() || include.iter().any(|i| i == "severity") {
            html.push_str("<h2>⚠️ 问题严重程度分布</h2>");
            html.push_str("<table><tr><th>严重程度</th><th>数量</th><th>占比</th></tr>");
            for item in issues_by_severity {
                let severity_class = match item.severity.as_str() {
                    "critical" => "critical",
                    "major" => "major",
                    "minor" => "minor",
                    _ => "info",
                };
                html.push_str(&format!(
                    "<tr><td class=\"{}\">{}</td><td>{}</td><td>{:.1}%</td></tr>",
                    severity_class, item.severity, item.count, item.percentage
                ));
            }
            html.push_str("</table>");
        }

        if include.is_empty() || include.iter().any(|i| i == "ranking") {
            html.push_str("<h2>🏆 团队排行榜</h2>");
            html.push_str("<table><tr><th>排名</th><th>用户</th><th>评审数</th><th>发现问题</th><th>评分</th></tr>");
            for item in team_ranking {
                let rank_class = match item.rank {
                    1 => "rank-1",
                    2 => "rank-2",
                    3 => "rank-3",
                    _ => "",
                };
                html.push_str(&format!(
                    "<tr class=\"{}\"><td>{}{}</td><td>{}</td><td>{}</td><td>{}</td><td>{:.2}</td></tr>",
                    rank_class,
                    item.rank,
                    if item.rank == 1 { "🥇" } else if item.rank == 2 { "🥈" } else if item.rank == 3 { "🥉" } else { "" },
                    item.username,
                    item.reviews_count,
                    item.issues_found,
                    item.score
                ));
            }
            html.push_str("</table>");
        }

        html.push_str("</body></html>");

        html.into_bytes()
    }
}
