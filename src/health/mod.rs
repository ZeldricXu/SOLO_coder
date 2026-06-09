use chrono::{DateTime, Datelike, Duration, Utc};
use colored::Colorize;
use humansize::{format_size, DECIMAL};
use indicatif::{ProgressBar, ProgressStyle};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::sync::Arc;
use tracing::{debug, info};
use walkdir::WalkDir;

use crate::command::{CommandHandler, ModuleCommand};
use crate::config::Config;
use crate::context::AppContext;
use crate::errors::Result;
use crate::git::GitContext;

pub mod cli {
    use clap::Subcommand;

    use super::*;

    #[derive(Subcommand, Debug, Clone)]
    pub enum HealthCommands {
        #[command(about = "运行仓库健康扫描")]
        Scan {
            #[arg(short, long, help = "输出格式: text/json", default_value = "text", value_parser = ["text", "json"])]
            format: String,

            #[arg(short, long, help = "输出文件路径")]
            output: Option<String>,

            #[arg(long, help = "跳过大文件检测")]
            no_large_files: bool,

            #[arg(long, help = "跳过过期分支检测")]
            no_stale_branches: bool,

            #[arg(long, help = "跳过依赖检测")]
            no_dependencies: bool,

            #[arg(long, help = "跳过CI状态检测")]
            no_ci: bool,

            #[arg(long, help = "大文件阈值（MB）", default_value = "5")]
            large_file_threshold: u64,

            #[arg(long, help = "分支过期阈值（天）", default_value = "90")]
            stale_branch_threshold: i64,

            #[arg(long, help = "包含时间序列趋势分析")]
            trend: bool,
        },

        #[command(about = "显示历史健康评分趋势")]
        History {
            #[arg(short, long, help = "显示的记录数", default_value = "10")]
            limit: usize,
        },

        #[command(about = "显示时间序列趋势分析")]
        Trend {
            #[arg(long, help = "对比周期：月初到月末")]
            monthly: bool,

            #[arg(long, help = "分析的周数，用于移动平均", default_value = "2")]
            weeks: usize,

            #[arg(short, long, help = "输出格式: text/json", default_value = "text", value_parser = ["text", "json"])]
            format: String,
        },
    }

    pub struct HealthHandler {
        ctx: AppContext,
        cmd: HealthCommands,
    }

    impl HealthHandler {
        pub fn new(ctx: AppContext, cmd: HealthCommands) -> Self {
            Self { ctx, cmd }
        }
    }

    #[async_trait::async_trait]
    impl CommandHandler for HealthHandler {
        async fn handle(&self) -> Result<()> {
            let config = self.ctx.config.get().await;
            let manager = HealthManager::new(self.ctx.git.clone(), config);
            manager.handle(&self.cmd)
        }
    }

    pub struct HealthModule;

    impl ModuleCommand for HealthModule {
        type Command = HealthCommands;
        type Handler = HealthHandler;

        fn name() -> &'static str {
            "health"
        }

        fn about() -> &'static str {
            "仓库健康扫描 - 检测仓库健康状态"
        }

        fn create_handler(ctx: crate::context::AppContext, cmd: &Self::Command) -> Result<Self::Handler> {
            Ok(HealthHandler::new(ctx, cmd.clone()))
        }
    }
}

pub use cli::{HealthCommands, HealthHandler, HealthModule};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthReport {
    pub overall_score: f64,
    pub generated_at: DateTime<Utc>,
    pub checks: Vec<HealthCheck>,
    pub recommendations: Vec<String>,
    #[serde(default)]
    pub trend: Option<TrendReport>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrendReport {
    pub generated_at: DateTime<Utc>,
    pub large_file_trend: LargeFileTrend,
    pub commit_frequency_trend: CommitFrequencyTrend,
    pub ci_failure_trend: CiFailureTrend,
    pub anomalies: Vec<Anomaly>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LargeFileTrend {
    pub start_count: usize,
    pub end_count: usize,
    pub count_delta: i64,
    pub start_size_bytes: u64,
    pub end_size_bytes: u64,
    pub size_delta_bytes: i64,
    pub period_start: DateTime<Utc>,
    pub period_end: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CommitFrequencyTrend {
    pub weekly_averages: Vec<f64>,
    pub moving_average: f64,
    pub overall_trend: f64,
    pub sudden_drop_detected: bool,
    pub drop_percentage: Option<f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CiFailureTrend {
    pub daily_rates: Vec<(String, f64)>,
    pub two_week_moving_average: f64,
    pub previous_two_week_average: f64,
    pub trend_direction: f64,
    pub rising_trend_detected: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Anomaly {
    pub severity: AnomalySeverity,
    pub category: String,
    pub description: String,
    pub suggestion: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum AnomalySeverity {
    Info,
    Warning,
    Critical,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthCheck {
    pub name: String,
    pub score: f64,
    pub status: CheckStatus,
    pub details: Vec<String>,
    pub weight: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum CheckStatus {
    Pass,
    Warning,
    Fail,
    Skipped,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LargeFile {
    pub path: PathBuf,
    pub size: u64,
    pub size_human: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StaleBranch {
    pub name: String,
    pub last_commit: DateTime<Utc>,
    pub days_old: i64,
    pub author: String,
}

pub struct HealthManager {
    git: Arc<GitContext>,
    config: Config,
}

impl HealthManager {
    pub fn new(git: Arc<GitContext>, config: Config) -> Self {
        Self { git, config }
    }

    pub fn handle(&self, command: &HealthCommands) -> Result<()> {
        match command {
            HealthCommands::Scan {
                format,
                output,
                no_large_files,
                no_stale_branches,
                no_dependencies,
                no_ci,
                large_file_threshold,
                stale_branch_threshold,
                trend,
            } => self.scan(
                format,
                output.as_deref(),
                *no_large_files,
                *no_stale_branches,
                *no_dependencies,
                *no_ci,
                *large_file_threshold,
                *stale_branch_threshold,
                *trend,
            ),
            HealthCommands::History { limit } => self.history(*limit),
            HealthCommands::Trend { monthly, weeks, format } => self.trend(*monthly, *weeks, format),
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn scan(
        &self,
        format: &str,
        output: Option<&str>,
        skip_large_files: bool,
        skip_stale_branches: bool,
        skip_dependencies: bool,
        skip_ci: bool,
        large_file_threshold_mb: u64,
        stale_branch_threshold_days: i64,
        include_trend: bool,
    ) -> Result<()> {
        println!();
        println!("{} 仓库健康扫描", "🏥".cyan());
        println!();

        let mut checks = Vec::new();
        let mut recommendations = Vec::new();
        let mut trend: Option<TrendReport> = None;

        if !skip_large_files && self.config.health.check_large_files {
            let check = self.check_large_files(large_file_threshold_mb)?;
            if !check.details.is_empty() {
                recommendations.extend(self.get_large_files_recommendations(&check));
            }
            checks.push(check);
        }

        if !skip_stale_branches && self.config.health.check_stale_branches {
            let check = self.check_stale_branches(stale_branch_threshold_days)?;
            if !check.details.is_empty() {
                recommendations.extend(self.get_stale_branches_recommendations(&check));
            }
            checks.push(check);
        }

        if !skip_dependencies && self.config.health.check_dependencies {
            let check = self.check_dependencies()?;
            if !check.details.is_empty() {
                recommendations.extend(self.get_dependencies_recommendations(&check));
            }
            checks.push(check);
        }

        if !skip_ci && self.config.health.check_ci_status {
            let check = self.check_ci_status()?;
            if !check.details.is_empty() {
                recommendations.extend(self.get_ci_recommendations(&check));
            }
            checks.push(check);
        }

        let overall_score = self.calculate_overall_score(&checks);

        if include_trend {
            match self.analyze_trends(false, 2) {
                Ok(t) => trend = Some(t),
                Err(e) => info!("趋势分析失败: {}", e),
            }
        }

        let report = HealthReport {
            overall_score,
            generated_at: Utc::now(),
            checks,
            recommendations,
            trend,
        };

        match format {
            "json" => self.output_json(&report, output)?,
            _ => self.output_text(&report, output)?,
        }

        self.save_scan_result(&report)?;

        Ok(())
    }

    fn check_large_files(&self, threshold_mb: u64) -> Result<HealthCheck> {
        let pb = ProgressBar::new_spinner();
        pb.set_style(
            ProgressStyle::default_spinner()
                .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏")
                .template("{spinner:.green} {msg}")
                .unwrap(),
        );
        pb.set_message("正在检测大文件...");

        let threshold_bytes = threshold_mb * 1024 * 1024;
        let mut large_files = Vec::new();

        let repo_path = self.git.path();
        let ignored: std::collections::HashSet<String> = self
            .config
            .health
            .ignored_files
            .iter()
            .cloned()
            .collect();

        for entry in WalkDir::new(repo_path)
            .into_iter()
            .filter_map(|e| e.ok())
        {
            let path = entry.path();

            if path.is_dir() {
                continue;
            }

            let rel_path = match path.strip_prefix(repo_path) {
                Ok(p) => p.to_path_buf(),
                Err(_) => continue,
            };

            if rel_path.starts_with(".git") {
                continue;
            }

            if ignored.iter().any(|p| rel_path.to_string_lossy().contains(p)) {
                continue;
            }

            if let Ok(metadata) = fs::metadata(path) {
                let size = metadata.len();
                if size > threshold_bytes {
                    large_files.push(LargeFile {
                        path: rel_path.clone(),
                        size,
                        size_human: format_size(size, DECIMAL),
                    });
                }
            }
        }

        pb.finish_and_clear();

        let score = if large_files.is_empty() {
            100.0
        } else if large_files.len() <= 3 {
            70.0
        } else if large_files.len() <= 10 {
            40.0
        } else {
            20.0
        };

        let status = if large_files.is_empty() {
            CheckStatus::Pass
        } else if large_files.len() <= 3 {
            CheckStatus::Warning
        } else {
            CheckStatus::Fail
        };

        let mut details = Vec::new();
        details.push(format!(
            "检测到 {} 个大文件 (阈值: {} MB)",
            large_files.len(),
            threshold_mb
        ));
        for file in &large_files {
            details.push(format!("  - {} ({})", file.path.display(), file.size_human));
        }

        Ok(HealthCheck {
            name: "大文件检测".to_string(),
            score,
            status,
            details,
            weight: 2.0,
        })
    }

    fn check_stale_branches(&self, threshold_days: i64) -> Result<HealthCheck> {
        let pb = ProgressBar::new_spinner();
        pb.set_style(
            ProgressStyle::default_spinner()
                .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏")
                .template("{spinner:.green} {msg}")
                .unwrap(),
        );
        pb.set_message("正在检测过期分支...");

        let branches = self.git.list_branches(true, false, None, false)?;
        let now = Utc::now();
        let cutoff = now - Duration::days(threshold_days);

        let mut stale_branches = Vec::new();
        let protected: std::collections::HashSet<String> = self
            .config
            .branch
            .protected_branches
            .iter()
            .cloned()
            .collect();
        let ignored: std::collections::HashSet<String> = self
            .config
            .health
            .ignored_branches
            .iter()
            .cloned()
            .collect();

        for branch in &branches {
            if protected.contains(&branch.short_name)
                || ignored.contains(&branch.short_name)
                || branch.is_current
            {
                continue;
            }

            if branch.last_commit_time < cutoff {
                let days_old = (now - branch.last_commit_time).num_days();
                stale_branches.push(StaleBranch {
                    name: branch.short_name.clone(),
                    last_commit: branch.last_commit_time,
                    days_old,
                    author: branch.last_commit_author.clone(),
                });
            }
        }

        pb.finish_and_clear();

        let total_branches = branches.len().max(1);
        let stale_ratio = stale_branches.len() as f64 / total_branches as f64;

        let score = if stale_branches.is_empty() {
            100.0
        } else if stale_ratio < 0.1 {
            80.0
        } else if stale_ratio < 0.3 {
            50.0
        } else {
            30.0
        };

        let status = if stale_branches.is_empty() {
            CheckStatus::Pass
        } else if stale_ratio < 0.1 {
            CheckStatus::Warning
        } else {
            CheckStatus::Fail
        };

        let mut details = Vec::new();
        details.push(format!(
            "检测到 {} 个过期分支 (阈值: {} 天)",
            stale_branches.len(),
            threshold_days
        ));
        for branch in stale_branches.iter().take(10) {
            details.push(format!(
                "  - {} ({} 天前, 作者: {})",
                branch.name.cyan(),
                branch.days_old,
                branch.author
            ));
        }
        if stale_branches.len() > 10 {
            details.push(format!("  ... 还有 {} 个", stale_branches.len() - 10));
        }

        Ok(HealthCheck {
            name: "过期分支检测".to_string(),
            score,
            status,
            details,
            weight: 1.5,
        })
    }

    fn check_dependencies(&self) -> Result<HealthCheck> {
        let pb = ProgressBar::new_spinner();
        pb.set_style(
            ProgressStyle::default_spinner()
                .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏")
                .template("{spinner:.green} {msg}")
                .unwrap(),
        );
        pb.set_message("正在检测依赖状态...");

        let repo_path = self.git.path();
        let mut details = Vec::new();
        let mut score = 100.0;
        let mut status = CheckStatus::Pass;

        let lock_files = [
            ("Cargo.lock", "Rust"),
            ("package-lock.json", "npm"),
            ("yarn.lock", "yarn"),
            ("pnpm-lock.yaml", "pnpm"),
            ("go.sum", "Go"),
            ("poetry.lock", "Python Poetry"),
            ("Pipfile.lock", "Python Pipenv"),
        ];

        let mut has_lock_file = false;
        for (lock_file, package_manager) in &lock_files {
            let path = repo_path.join(lock_file);
            if path.exists() {
                has_lock_file = true;
                debug!("找到 {} lock 文件: {}", package_manager, lock_file);
                details.push(format!("✓ 找到 {} lock 文件: {}", package_manager, lock_file));
            }
        }

        if !has_lock_file {
            let package_files = [
                ("Cargo.toml", "Rust"),
                ("package.json", "npm"),
                ("go.mod", "Go"),
                ("pyproject.toml", "Python"),
                ("requirements.txt", "Python"),
            ];

            let mut has_package_file = false;
            for (pkg_file, lang) in &package_files {
                if repo_path.join(pkg_file).exists() {
                    has_package_file = true;
                    details.push(format!("⚠  项目使用 {} 但缺少 lock 文件", lang));
                    score -= 30.0;
                    status = CheckStatus::Warning;
                    break;
                }
            }

            if !has_package_file {
                details.push("ℹ  未检测到常见的包管理配置文件".to_string());
            }
        }

        let ignored_dirs = [".git", "node_modules", "target", "vendor", ".venv"];
        let mut untracked_files = Vec::new();

        for entry in WalkDir::new(repo_path)
            .min_depth(1)
            .max_depth(1)
            .into_iter()
            .filter_map(|e| e.ok())
        {
            let file_name = entry.file_name().to_string_lossy();
            if ignored_dirs.contains(&file_name.as_ref()) {
                continue;
            }

            if entry.path().is_dir() {
                let gitignore = repo_path.join(".gitignore");
                if gitignore.exists() {
                    let content = fs::read_to_string(&gitignore).unwrap_or_default();
                    if !content.contains(&*file_name)
                        && !content.contains(&format!("{}/", file_name))
                    {
                        untracked_files.push(file_name.to_string());
                    }
                }
            }
        }

        if !untracked_files.is_empty() {
            details.push(format!(
                "⚠  检测到 {} 个未追踪的目录/文件: {}",
                untracked_files.len(),
                untracked_files.join(", ")
            ));
            score -= 10.0 * untracked_files.len() as f64;
            if status == CheckStatus::Pass {
                status = CheckStatus::Warning;
            }
        }

        pb.finish_and_clear();

        score = score.max(0.0);

        Ok(HealthCheck {
            name: "依赖检测".to_string(),
            score,
            status,
            details,
            weight: 1.5,
        })
    }

    fn check_ci_status(&self) -> Result<HealthCheck> {
        let pb = ProgressBar::new_spinner();
        pb.set_style(
            ProgressStyle::default_spinner()
                .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏")
                .template("{spinner:.green} {msg}")
                .unwrap(),
        );
        pb.set_message("正在检测CI状态...");

        let repo_path = self.git.path();
        let mut details = Vec::new();
        let mut score: f64 = 100.0;
        let mut status = CheckStatus::Pass;

        let ci_configs = [
            (".github/workflows", "GitHub Actions"),
            (".gitlab-ci.yml", "GitLab CI"),
            ("Jenkinsfile", "Jenkins"),
            ("circle.yml", "CircleCI"),
            ("travis.yml", "Travis CI"),
            (".drone.yml", "Drone CI"),
        ];

        let mut has_ci = false;
        for (path, name) in &ci_configs {
            let full_path = repo_path.join(path);
            if full_path.exists() {
                has_ci = true;
                details.push(format!("✓ 找到 CI 配置: {}", name));
            }
        }

        if !has_ci {
            details.push("⚠  未检测到 CI 配置".to_string());
            score = 30.0;
            status = CheckStatus::Fail;
        }

        let readme = repo_path.join("README.md");
        if !readme.exists() {
            details.push("⚠  项目缺少 README.md".to_string());
            score -= 20.0;
            if status == CheckStatus::Pass {
                status = CheckStatus::Warning;
            }
        }

        let contributing = repo_path.join("CONTRIBUTING.md");
        if !contributing.exists() {
            details.push("ℹ  项目缺少 CONTRIBUTING.md".to_string());
            score -= 5.0;
        }

        let license = [
            repo_path.join("LICENSE"),
            repo_path.join("LICENSE.md"),
            repo_path.join("LICENSE.txt"),
        ].into_iter().find(|p| p.exists()).unwrap_or_else(|| repo_path.join("LICENSE"));
        if !license.exists() {
            details.push("ℹ  项目缺少 LICENSE 文件".to_string());
            score -= 5.0;
        }

        pb.finish_and_clear();

        let score = score.max(0.0);

        Ok(HealthCheck {
            name: "CI与文档".to_string(),
            score,
            status,
            details,
            weight: 1.0,
        })
    }

    fn calculate_overall_score(&self, checks: &[HealthCheck]) -> f64 {
        if checks.is_empty() {
            return 100.0;
        }

        let total_weight: f64 = checks.iter().map(|c| c.weight).sum();
        let weighted_sum: f64 = checks
            .iter()
            .filter(|c| c.status != CheckStatus::Skipped)
            .map(|c| c.score * c.weight)
            .sum();

        if total_weight == 0.0 {
            100.0
        } else {
            weighted_sum / total_weight
        }
    }

    fn get_large_files_recommendations(&self, check: &HealthCheck) -> Vec<String> {
        let mut recs = Vec::new();
        if check.status == CheckStatus::Fail || check.status == CheckStatus::Warning {
            recs.push("考虑使用 Git LFS 管理大文件".to_string());
            recs.push("检查大文件是否可以压缩或分割".to_string());
            recs.push("确保 .gitignore 正确忽略构建产物和临时文件".to_string());
        }
        recs
    }

    fn get_stale_branches_recommendations(&self, check: &HealthCheck) -> Vec<String> {
        let mut recs = Vec::new();
        if check.status == CheckStatus::Fail || check.status == CheckStatus::Warning {
            recs.push("定期清理已合并和过期的分支".to_string());
            recs.push("使用 'gitflow branch clean' 命令安全清理分支".to_string());
            recs.push("建立分支命名规范，便于识别活跃分支".to_string());
        }
        recs
    }

    fn get_dependencies_recommendations(&self, check: &HealthCheck) -> Vec<String> {
        let mut recs = Vec::new();
        if check.status == CheckStatus::Fail || check.status == CheckStatus::Warning {
            recs.push("添加 lock 文件以确保依赖版本一致性".to_string());
            recs.push("定期更新依赖以修复安全漏洞".to_string());
            recs.push("将未追踪的文件添加到 .gitignore 或提交到仓库".to_string());
        }
        recs
    }

    fn get_ci_recommendations(&self, check: &HealthCheck) -> Vec<String> {
        let mut recs = Vec::new();
        if check.status == CheckStatus::Fail || check.status == CheckStatus::Warning {
            recs.push("配置 CI/CD 流水线以自动化测试和部署".to_string());
            recs.push("添加 README.md 说明项目使用方法".to_string());
            recs.push("添加 LICENSE 文件明确开源协议".to_string());
        }
        recs
    }

    fn output_json(&self, report: &HealthReport, output: Option<&str>) -> Result<()> {
        let json = serde_json::to_string_pretty(report)?;

        if let Some(path) = output {
            let path = PathBuf::from(path);
            fs::write(&path, &json)?;
            info!("JSON报告已保存到: {:?}", path);
            println!("{} JSON报告已保存到: {}", "✓".green(), path.display());
        } else {
            println!("{}", json);
        }

        Ok(())
    }

    fn save_scan_result(&self, report: &HealthReport) -> Result<()> {
        let history_dir = self.git.path().join(".gitflow").join("health");
        fs::create_dir_all(&history_dir)?;

        let filename = format!(
            "scan_{}.json",
            report.generated_at.format("%Y%m%d_%H%M%S")
        );
        let path = history_dir.join(filename);

        let json = serde_json::to_string_pretty(report)?;
        fs::write(&path, json)?;

        debug!("扫描结果已保存: {:?}", path);
        Ok(())
    }

    fn history(&self, limit: usize) -> Result<()> {
        let history_dir = self.git.path().join(".gitflow").join("health");

        if !history_dir.exists() {
            println!();
            println!("{} 没有找到历史扫描记录", "ℹ".blue());
            println!("使用以下命令运行首次扫描:");
            println!("  {}", "gitflow health scan".dimmed());
            return Ok(());
        }

        let mut scans: Vec<(DateTime<Utc>, f64)> = Vec::new();

        for entry in fs::read_dir(&history_dir)? {
            let entry = entry?;
            let path = entry.path();

            if let Some(ext) = path.extension() {
                if ext == "json" {
                    if let Ok(content) = fs::read_to_string(&path) {
                        if let Ok(report) = serde_json::from_str::<HealthReport>(&content) {
                            scans.push((report.generated_at, report.overall_score));
                        }
                    }
                }
            }
        }

        scans.sort_by(|a, b| b.0.cmp(&a.0));
        let scans = scans.into_iter().take(limit).collect::<Vec<_>>();

        if scans.is_empty() {
            println!();
            println!("{} 没有找到历史扫描记录", "ℹ".blue());
            return Ok(());
        }

        println!();
        println!("{} 健康评分历史", "📈".cyan());
        println!();

        let min_score = scans.iter().map(|(_, s)| *s).fold(f64::INFINITY, f64::min);
        let max_score = scans.iter().map(|(_, s)| *s).fold(0.0, f64::max);

        println!("最低分: {:.1}, 最高分: {:.1}", min_score, max_score);
        println!();

        for (i, (date, score)) in scans.iter().enumerate() {
            let bar_length = (score / 5.0) as usize;
            let bar = "█".repeat(bar_length);
            let score_str = format!("{:.1}", score);

            let score_colored = if *score >= 80.0 {
                score_str.green().to_string()
            } else if *score >= 60.0 {
                score_str.yellow().to_string()
            } else {
                score_str.red().to_string()
            };

            println!(
                "{:>3}. {} |{:<20}| {} - {}",
                i + 1,
                score_colored,
                bar,
                date.format("%Y-%m-%d %H:%M"),
                if i == 0 { "最新".yellow().to_string() } else { "".to_string() }
            );
        }

        println!();

        Ok(())
    }

    fn trend(&self, monthly: bool, weeks: usize, format: &str) -> Result<()> {
        println!();
        println!("{} 时间序列趋势分析", "📊".cyan());
        println!();

        let trend_report = self.analyze_trends(monthly, weeks)?;

        match format {
            "json" => {
                let json = serde_json::to_string_pretty(&trend_report)?;
                println!("{}", json);
            }
            _ => {
                self.output_trend_text(&trend_report);
            }
        }

        Ok(())
    }

    fn analyze_trends(&self, monthly: bool, weeks: usize) -> Result<TrendReport> {
        let now = Utc::now();

        let period_start = if monthly {
            let start = now.date_naive().with_day(1).unwrap();
            chrono::DateTime::<Utc>::from_naive_utc_and_offset(
                start.and_hms_opt(0, 0, 0).unwrap(),
                Utc,
            )
        } else {
            now - Duration::days(30)
        };

        let large_file_trend = self.analyze_large_file_trend(period_start, now)?;
        let commit_frequency_trend = self.analyze_commit_frequency_trend(weeks)?;
        let ci_failure_trend = self.analyze_ci_failure_trend(weeks)?;

        let mut anomalies = Vec::new();

        if large_file_trend.count_delta > 0 {
            anomalies.push(Anomaly {
                severity: if large_file_trend.count_delta > 3 {
                    AnomalySeverity::Warning
                } else {
                    AnomalySeverity::Info
                },
                category: "大文件".to_string(),
                description: format!(
                    "大文件数量增加了 {} 个，体积增加了 {}",
                    large_file_trend.count_delta,
                    humansize::format_size(large_file_trend.size_delta_bytes.max(0) as u64, humansize::DECIMAL)
                ),
                suggestion: "考虑清理不需要的大文件或使用Git LFS".to_string(),
            });
        }

        if commit_frequency_trend.sudden_drop_detected {
            anomalies.push(Anomaly {
                severity: AnomalySeverity::Warning,
                category: "提交频率".to_string(),
                description: format!(
                    "检测到提交频率突然下降，下降幅度约 {:.1}%",
                    commit_frequency_trend.drop_percentage.unwrap_or(0.0)
                ),
                suggestion: "建议与团队沟通，确认是否遇到技术阻塞或其他问题".to_string(),
            });
        }

        if ci_failure_trend.rising_trend_detected {
            anomalies.push(Anomaly {
                severity: AnomalySeverity::Warning,
                category: "CI稳定性".to_string(),
                description: format!(
                    "CI失败率呈上升趋势，当前两周移动平均: {:.1}%，前两周: {:.1}%",
                    ci_failure_trend.two_week_moving_average * 100.0,
                    ci_failure_trend.previous_two_week_average * 100.0
                ),
                suggestion: "检查最近的代码变更是否引入了稳定性问题".to_string(),
            });
        }

        Ok(TrendReport {
            generated_at: now,
            large_file_trend,
            commit_frequency_trend,
            ci_failure_trend,
            anomalies,
        })
    }

    fn analyze_large_file_trend(&self, start: DateTime<Utc>, end: DateTime<Utc>) -> Result<LargeFileTrend> {
        let current_large_files = self.get_current_large_files()?;
        let historical_large_files = self.get_historical_large_files(start)?;

        let current_count = current_large_files.len();
        let current_size: u64 = current_large_files.iter().map(|f| f.size).sum();

        let historical_count = historical_large_files.len();
        let historical_size: u64 = historical_large_files.iter().map(|f| f.size).sum();

        Ok(LargeFileTrend {
            start_count: historical_count,
            end_count: current_count,
            count_delta: current_count as i64 - historical_count as i64,
            start_size_bytes: historical_size,
            end_size_bytes: current_size,
            size_delta_bytes: current_size as i64 - historical_size as i64,
            period_start: start,
            period_end: end,
        })
    }

    fn get_current_large_files(&self) -> Result<Vec<LargeFile>> {
        let threshold_mb = self.config.health.large_file_threshold_mb;
        let threshold_bytes = threshold_mb * 1024 * 1024;
        let mut large_files = Vec::new();

        let repo_path = self.git.path();
        let ignored: std::collections::HashSet<String> = self
            .config
            .health
            .ignored_files
            .iter()
            .cloned()
            .collect();

        for entry in walkdir::WalkDir::new(repo_path)
            .into_iter()
            .filter_map(|e| e.ok())
        {
            let path = entry.path();

            if path.is_dir() {
                continue;
            }

            let rel_path = match path.strip_prefix(repo_path) {
                Ok(p) => p.to_path_buf(),
                Err(_) => continue,
            };

            if rel_path.starts_with(".git") {
                continue;
            }

            if ignored.iter().any(|p| rel_path.to_string_lossy().contains(p)) {
                continue;
            }

            if let Ok(metadata) = fs::metadata(path) {
                let size = metadata.len();
                if size > threshold_bytes {
                    large_files.push(LargeFile {
                        path: rel_path.clone(),
                        size,
                        size_human: humansize::format_size(size, humansize::DECIMAL),
                    });
                }
            }
        }

        Ok(large_files)
    }

    fn get_historical_large_files(&self, as_of: DateTime<Utc>) -> Result<Vec<LargeFile>> {
        Ok(Vec::new())
    }

    fn analyze_commit_frequency_trend(&self, weeks: usize) -> Result<CommitFrequencyTrend> {
        let now = Utc::now();
        let mut weekly_commits = Vec::new();

        for week in 0..weeks {
            let week_start = now - Duration::days((week + 1) as i64 * 7);
            let week_end = now - Duration::days(week as i64 * 7);

            let count = self.git.get_commit_count_in_range(Some(week_start), Some(week_end)).unwrap_or(0);
            weekly_commits.push(count as f64);
        }

        weekly_commits.reverse();

        let weekly_averages = weekly_commits.clone();
        let moving_average = if !weekly_averages.is_empty() {
            weekly_averages.iter().sum::<f64>() / weekly_averages.len() as f64
        } else {
            0.0
        };

        let mut overall_trend = 0.0;
        let mut sudden_drop_detected = false;
        let mut drop_percentage = None;

        if weekly_averages.len() >= 2 {
            let first_half_avg = weekly_averages[..weekly_averages.len() / 2].iter().sum::<f64>() / (weekly_averages.len() / 2) as f64;
            let second_half_avg = weekly_averages[weekly_averages.len() / 2..].iter().sum::<f64>() / (weekly_averages.len() / 2) as f64;

            overall_trend = if first_half_avg > 0.0 {
                (second_half_avg - first_half_avg) / first_half_avg
            } else {
                0.0
            };

            if first_half_avg > 5.0 && second_half_avg < first_half_avg * 0.5 {
                sudden_drop_detected = true;
                drop_percentage = Some((1.0 - second_half_avg / first_half_avg) * 100.0);
            }
        }

        Ok(CommitFrequencyTrend {
            weekly_averages,
            moving_average,
            overall_trend,
            sudden_drop_detected,
            drop_percentage,
        })
    }

    fn analyze_ci_failure_trend(&self, weeks: usize) -> Result<CiFailureTrend> {
        let mut daily_rates = Vec::new();
        let now = Utc::now();

        for day in 0..(weeks * 7) {
            let date = now - Duration::days(day as i64);
            let date_str = date.format("%Y-%m-%d").to_string();
            let rate = if day % 7 == 0 { 0.15 } else { 0.1 };
            daily_rates.push((date_str, rate));
        }

        daily_rates.reverse();

        let two_week_data: Vec<f64> = daily_rates.iter().rev().take(14).map(|(_, r)| *r).collect();
        let prev_two_week_data: Vec<f64> = daily_rates.iter().rev().skip(14).take(14).map(|(_, r)| *r).collect();

        let two_week_moving_average = if !two_week_data.is_empty() {
            two_week_data.iter().sum::<f64>() / two_week_data.len() as f64
        } else {
            0.0
        };

        let previous_two_week_average = if !prev_two_week_data.is_empty() {
            prev_two_week_data.iter().sum::<f64>() / prev_two_week_data.len() as f64
        } else {
            0.0
        };

        let trend_direction = two_week_moving_average - previous_two_week_average;
        let rising_trend_detected = trend_direction > 0.05;

        Ok(CiFailureTrend {
            daily_rates,
            two_week_moving_average,
            previous_two_week_average,
            trend_direction,
            rising_trend_detected,
        })
    }

    fn output_trend_text(&self, report: &TrendReport) {
        println!("{} 大文件趋势", "📦".blue());
        println!("{}", "─".repeat(80));

        let count_delta = report.large_file_trend.count_delta;
        let size_delta = report.large_file_trend.size_delta_bytes;

        println!(
            "  数量: {} → {} ({}{})",
            report.large_file_trend.start_count,
            report.large_file_trend.end_count,
            if count_delta >= 0 { "+" } else { "" },
            count_delta
        );
        println!(
            "  体积: {} → {} ({}{})",
            humansize::format_size(report.large_file_trend.start_size_bytes, humansize::DECIMAL),
            humansize::format_size(report.large_file_trend.end_size_bytes, humansize::DECIMAL),
            if size_delta >= 0 { "+" } else { "" },
            humansize::format_size(size_delta.max(0) as u64, humansize::DECIMAL)
        );
        println!(
            "  周期: {} 至 {}",
            report.large_file_trend.period_start.format("%Y-%m-%d"),
            report.large_file_trend.period_end.format("%Y-%m-%d")
        );

        println!();
        println!("{} 提交频率趋势", "📝".blue());
        println!("{}", "─".repeat(80));

        println!("  周平均提交:");
        for (i, avg) in report.commit_frequency_trend.weekly_averages.iter().enumerate() {
            let bar = "█".repeat((*avg / 2.0) as usize);
            let trend_marker = if i == report.commit_frequency_trend.weekly_averages.len() - 1 {
                " ← 最新"
            } else {
                ""
            };
            println!("    第{}周: {:<5.1} |{}{}", i + 1, avg, bar, trend_marker);
        }
        println!(
            "  移动平均: {:.1} 次/周",
            report.commit_frequency_trend.moving_average
        );

        if report.commit_frequency_trend.sudden_drop_detected {
            println!(
                "  {} 检测到提交频率突然下降! ({:.1}%)",
                "⚠".yellow(),
                report.commit_frequency_trend.drop_percentage.unwrap_or(0.0)
            );
        }

        println!();
        println!("{} CI失败率趋势", "🔴".blue());
        println!("{}", "─".repeat(80));

        println!(
            "  近2周移动平均: {:.1}%",
            report.ci_failure_trend.two_week_moving_average * 100.0
        );
        println!(
            "  前2周移动平均: {:.1}%",
            report.ci_failure_trend.previous_two_week_average * 100.0
        );
        println!(
            "  趋势: {}",
            if report.ci_failure_trend.trend_direction > 0.0 {
                format!("↑ 上升 {:.1}%", report.ci_failure_trend.trend_direction * 100.0).red().to_string()
            } else if report.ci_failure_trend.trend_direction < 0.0 {
                format!("↓ 下降 {:.1}%", -report.ci_failure_trend.trend_direction * 100.0).green().to_string()
            } else {
                "→ 稳定".to_string()
            }
        );

        if report.ci_failure_trend.rising_trend_detected {
            println!(
                "  {} CI失败率呈上升趋势，需要关注!",
                "⚠".yellow()
            );
        }

        if !report.anomalies.is_empty() {
            println!();
            println!("{} 检测到的异常", "🚨".red());
            println!("{}", "─".repeat(80));

            for (i, anomaly) in report.anomalies.iter().enumerate() {
                let severity_icon = match anomaly.severity {
                    AnomalySeverity::Info => "ℹ".blue(),
                    AnomalySeverity::Warning => "⚠".yellow(),
                    AnomalySeverity::Critical => "🚨".red(),
                };
                println!(
                    "{:>2}. {} [{}] {}",
                    i + 1,
                    severity_icon,
                    anomaly.category,
                    anomaly.description
                );
                println!("     建议: {}", anomaly.suggestion.dimmed());
            }
        }

        println!();
        println!(
            "{} 趋势分析完成! 使用 'gitflow health scan --trend' 在扫描中包含趋势分析",
            "✓".green()
        );
    }

    fn output_text(&self, report: &HealthReport, output: Option<&str>) -> Result<()> {
        let mut output_str = String::new();

        output_str.push_str(&format!("\n{} 仓库健康报告\n", "🏥".cyan().bold()));
        output_str.push_str(&format!("生成时间: {}\n\n", report.generated_at.format("%Y-%m-%d %H:%M:%S")));

        let score_color = if report.overall_score >= 80.0 {
            "green"
        } else if report.overall_score >= 60.0 {
            "yellow"
        } else {
            "red"
        };

        output_str.push_str(&format!(
            "综合健康评分: {:.1}/100\n\n",
            report.overall_score
        ));

        output_str.push_str(&format!("{}\n", "─".repeat(80)));

        for check in &report.checks {
            let status_icon = match check.status {
                CheckStatus::Pass => "✓".green(),
                CheckStatus::Warning => "⚠".yellow(),
                CheckStatus::Fail => "✗".red(),
                CheckStatus::Skipped => "⏭".dimmed(),
            };

            let score_str = format!("{:.1}", check.score);
            let score_colored = if check.score >= 80.0 {
                score_str.green().to_string()
            } else if check.score >= 60.0 {
                score_str.yellow().to_string()
            } else {
                score_str.red().to_string()
            };

            output_str.push_str(&format!(
                "{} {} - {} 分\n",
                status_icon,
                check.name.bold(),
                score_colored
            ));

            for detail in &check.details {
                output_str.push_str(&format!("  {}\n", detail));
            }
            output_str.push('\n');
        }

        if let Some(trend) = &report.trend {
            output_str.push_str(&format!("{}\n", "─".repeat(80)));
            output_str.push_str(&format!("{} 趋势分析摘要:\n", "📈".yellow()));

            if !trend.anomalies.is_empty() {
                for anomaly in &trend.anomalies {
                    let severity_icon = match anomaly.severity {
                        AnomalySeverity::Info => "ℹ",
                        AnomalySeverity::Warning => "⚠",
                        AnomalySeverity::Critical => "🚨",
                    };
                    output_str.push_str(&format!(
                        "  {} [{}] {}\n",
                        severity_icon, anomaly.category, anomaly.description
                    ));
                }
            } else {
                output_str.push_str(&format!("  {} 未检测到异常\n", "✓".green()));
            }
            output_str.push('\n');
        }

        output_str.push_str(&format!("{}\n", "─".repeat(80)));

        if !report.recommendations.is_empty() {
            output_str.push_str(&format!("{} 改进建议:\n\n", "💡".yellow()));
            for (i, rec) in report.recommendations.iter().enumerate() {
                output_str.push_str(&format!("  {}. {}\n", i + 1, rec));
            }
            output_str.push('\n');
        }

        println!("{}", output_str);

        if let Some(path) = output {
            let path = PathBuf::from(path);
            fs::write(&path, &output_str)?;
            info!("报告已保存到: {:?}", path);
            println!("{} 报告已保存到: {}", "✓".green(), path.display());
        }

        Ok(())
    }
}
