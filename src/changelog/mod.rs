use chrono::Utc;
use colored::Colorize;
use indicatif::{ProgressBar, ProgressStyle};
use regex::Regex;
use std::collections::{BTreeMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tracing::{debug, info, warn};

use crate::command::{CommandHandler, ModuleCommand};
use crate::config::Config;
use crate::context::AppContext;
use crate::errors::{GitFlowError, Result};
use crate::git::{parse_commit_for_conventional, CommitInfo, GitContext, TagInfo};

pub mod cli {
    use clap::Subcommand;

    use super::*;

    #[derive(Subcommand, Debug, Clone)]
    pub enum ChangelogCommands {
        #[command(about = "生成变更日志")]
        Generate {
            #[arg(short, long, help = "版本号，如v1.0.0")]
            version: Option<String>,

            #[arg(short, long, help = "从指定tag开始")]
            from: Option<String>,

            #[arg(short, long, help = "到指定tag结束")]
            to: Option<String>,

            #[arg(short, long, help = "输出文件路径", default_value = "CHANGELOG.md")]
            output: String,

            #[arg(short, long, help = "追加到现有文件")]
            append: bool,

            #[arg(long, help = "不包含未发布的变更")]
            no_unreleased: bool,

            #[arg(short = 'n', long, help = "只显示，不写入文件")]
            dry_run: bool,
        },

        #[command(about = "初始化CHANGELOG.md")]
        Init {
            #[arg(short, long, help = "输出文件路径", default_value = "CHANGELOG.md")]
            output: String,

            #[arg(short, long, help = "强制覆盖现有文件")]
            force: bool,
        },
    }

    pub struct ChangelogHandler {
        ctx: AppContext,
        cmd: ChangelogCommands,
    }

    impl ChangelogHandler {
        pub fn new(ctx: AppContext, cmd: ChangelogCommands) -> Self {
            Self { ctx, cmd }
        }
    }

    #[async_trait::async_trait]
    impl CommandHandler for ChangelogHandler {
        async fn handle(&self) -> Result<()> {
            let config = self.ctx.config.get().await;
            let manager = ChangelogManager::new(self.ctx.git.clone(), config);
            manager.handle(&self.cmd).await
        }
    }

    pub struct ChangelogModule;

    impl ModuleCommand for ChangelogModule {
        type Command = ChangelogCommands;
        type Handler = ChangelogHandler;

        fn name() -> &'static str {
            "changelog"
        }

        fn about() -> &'static str {
            "变更日志 - 自动生成CHANGELOG.md"
        }

        fn create_handler(ctx: crate::context::AppContext, cmd: &Self::Command) -> Result<Self::Handler> {
            Ok(ChangelogHandler::new(ctx, cmd.clone()))
        }
    }
}

pub use cli::{ChangelogCommands, ChangelogHandler, ChangelogModule};

#[derive(Debug, Clone)]
pub struct ChangelogEntry {
    pub version: String,
    pub date: Option<String>,
    pub is_unreleased: bool,
    pub categories: BTreeMap<String, Vec<ChangelogItem>>,
}

#[derive(Debug, Clone)]
pub struct ChangelogItem {
    pub commit_sha: String,
    pub scope: Option<String>,
    pub description: String,
    pub is_breaking: bool,
    pub author: String,
    pub issues: Vec<String>,
}

pub struct ChangelogManager {
    git: Arc<GitContext>,
    config: Config,
}

impl ChangelogManager {
    pub fn new(git: Arc<GitContext>, config: Config) -> Self {
        Self { git, config }
    }

    pub async fn handle(&self, command: &ChangelogCommands) -> Result<()> {
        match command {
            ChangelogCommands::Generate {
                version,
                from,
                to,
                output,
                append,
                no_unreleased,
                dry_run,
            } => self.generate(
                version.as_deref(),
                from.as_deref(),
                to.as_deref(),
                output,
                *append,
                *no_unreleased,
                *dry_run,
            ),
            ChangelogCommands::Init { output, force } => self.init(output, *force),
        }
    }

    fn generate(
        &self,
        version: Option<&str>,
        from: Option<&str>,
        to: Option<&str>,
        output_path: &str,
        append: bool,
        no_unreleased: bool,
        dry_run: bool,
    ) -> Result<()> {
        let pb = ProgressBar::new_spinner();
        pb.set_style(
            ProgressStyle::default_spinner()
                .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏")
                .template("{spinner:.green} {msg}")
                .unwrap(),
        );
        pb.set_message("正在分析提交历史...");

        let tags = self.git.list_tags(Some(&self.config.changelog.version_tag_pattern))?;
        debug!("找到 {} 个标签", tags.len());

        let version_re = Regex::new(&self.config.changelog.version_tag_pattern)?;

        let from_tag = match from {
            Some(f) => Some(f.to_string()),
            None => tags.first().map(|t| t.name.clone()),
        };

        let to_ref = to.unwrap_or("HEAD");

        let commits = self.git.get_commit_range(from_tag.as_deref(), Some(to_ref))?;
        pb.finish_and_clear();

        debug!("分析 {} 个提交", commits.len());

        let mut entries = Vec::new();

        if !no_unreleased && self.config.changelog.include_unreleased {
            let unreleased_commits = self.get_unreleased_commits(&commits, &tags)?;
            if !unreleased_commits.is_empty() {
                let entry = self.build_changelog_entry(
                    "Unreleased",
                    None,
                    true,
                    &unreleased_commits,
                )?;
                entries.push(entry);
            }
        }

        for i in 0..tags.len() {
            let tag = &tags[i];
            if !version_re.is_match(&tag.name) {
                continue;
            }

            let next_tag = if i + 1 < tags.len() {
                Some(&tags[i + 1])
            } else {
                None
            };

            let version_commits = match next_tag {
                Some(next) => {
                    self.git.get_commit_range(
                        Some(&next.name),
                        Some(&tag.name),
                    )?
                }
                None => {
                    let first_commit = self.get_first_commit()?;
                    self.git.get_commit_range(
                        first_commit.as_deref(),
                        Some(&tag.name),
                    )?
                }
            };

            let entry = self.build_changelog_entry(
                &tag.name,
                tag.time.as_ref().map(|t| t.format("%Y-%m-%d").to_string()),
                false,
                &version_commits,
            )?;

            if !entry.categories.is_empty() {
                entries.push(entry);
            }
        }

        if let Some(v) = version {
            let since_tag = tags.first().map(|t| t.name.clone());
            let version_commits = self.git.get_commit_range(since_tag.as_deref(), Some("HEAD"))?;
            let entry = self.build_changelog_entry(
                v,
                Some(Utc::now().format("%Y-%m-%d").to_string()),
                false,
                &version_commits,
            )?;
            if !entry.categories.is_empty() {
                entries.insert(0, entry);
            }
        }

        let changelog_content = self.format_changelog(&entries);

        println!();
        println!("{} 变更日志生成完成!", "📜".cyan().bold());
        println!("  版本数: {}", entries.len());
        println!();

        if dry_run {
            println!("{}", "─".repeat(80));
            println!("{}", changelog_content);
            println!("{}", "─".repeat(80));
            println!();
            println!("{} 模拟模式，未写入文件", "ℹ".blue());
            return Ok(());
        }

        let output_file = PathBuf::from(output_path);

        if append && output_file.exists() {
            self.append_changelog(&output_file, &changelog_content)?;
        } else {
            self.write_changelog(&output_file, &changelog_content)?;
        }

        println!("{} 变更日志已写入: {}", "✓".green(), Path::new(output_path).display());

        Ok(())
    }

    fn get_unreleased_commits(
        &self,
        commits: &[CommitInfo],
        tags: &[TagInfo],
    ) -> Result<Vec<CommitInfo>> {
        if tags.is_empty() {
            return Ok(commits.to_vec());
        }

        let latest_tag = &tags[0];
        let tag_oid = self.git.repo().revparse_single(&latest_tag.name)?.id();

        let unreleased: Vec<CommitInfo> = commits
            .iter()
            .filter(|c| {
                if let Ok(commit_oid) = git2::Oid::from_str(&c.sha) {
                    !self
                        .git
                        .repo()
                        .graph_descendant_of(commit_oid, tag_oid)
                        .unwrap_or(false)
                } else {
                    false
                }
            })
            .cloned()
            .collect();

        Ok(unreleased)
    }

    fn get_first_commit(&self) -> Result<Option<String>> {
        let mut revwalk = self.git.repo().revwalk()?;
        revwalk.push_head()?;
        revwalk.set_sorting(git2::Sort::TIME | git2::Sort::REVERSE)?;

        for oid in revwalk {
            let oid = oid?;
            return Ok(Some(oid.to_string()));
        }

        Ok(None)
    }

    fn build_changelog_entry(
        &self,
        version: &str,
        date: Option<String>,
        is_unreleased: bool,
        commits: &[CommitInfo],
    ) -> Result<ChangelogEntry> {
        let include_types: HashSet<String> = self
            .config
            .changelog
            .include_types
            .iter()
            .cloned()
            .collect();

        let expanded_commits = self.expand_merge_commits(commits)?;

        let mut categories: BTreeMap<String, Vec<ChangelogItem>> = BTreeMap::new();
        let mut breaking_changes = Vec::new();
        let mut processed_shas: HashSet<String> = HashSet::new();

        for commit in &expanded_commits {
            if processed_shas.contains(&commit.sha) {
                continue;
            }
            processed_shas.insert(commit.sha.clone());

            if commit.is_merge() {
                if let Some(pr_num) = commit.pr_number {
                    let issues = vec![format!("#{}", pr_num)];
                    let item = ChangelogItem {
                        commit_sha: commit.short_sha.clone(),
                        scope: Some("merge".to_string()),
                        description: format!("Merge PR #{}: {}", pr_num, commit.summary),
                        is_breaking: false,
                        author: commit.author.clone(),
                        issues,
                    };
                    categories
                        .entry("🔀 合并请求".to_string())
                        .or_default()
                        .push(item);
                }
                continue;
            }

            if let Some(conv) = parse_commit_for_conventional(&commit.message) {
                if !include_types.contains(&conv.r#type) {
                    continue;
                }

                let issues = extract_issues_from_commit(&commit.message);

                let item = ChangelogItem {
                    commit_sha: commit.short_sha.clone(),
                    scope: conv.scope.clone(),
                    description: conv.subject.clone(),
                    is_breaking: conv.is_breaking,
                    author: commit.author.clone(),
                    issues,
                };

                if conv.is_breaking {
                    breaking_changes.push(item.clone());
                }

                let category = self.get_category_name(&conv.r#type);
                categories
                    .entry(category)
                    .or_default()
                    .push(item);
            }
        }

        if !breaking_changes.is_empty() {
            categories.insert("⚠️ 不兼容变更".to_string(), breaking_changes);
        }

        Ok(ChangelogEntry {
            version: version.to_string(),
            date,
            is_unreleased,
            categories,
        })
    }

    fn expand_merge_commits(&self, commits: &[CommitInfo]) -> Result<Vec<CommitInfo>> {
        let mut result = Vec::new();
        let mut seen_shas: HashSet<String> = HashSet::new();

        for commit in commits {
            if seen_shas.contains(&commit.sha) {
                continue;
            }

            if commit.is_merge() {
                result.push(commit.clone());
                seen_shas.insert(commit.sha.clone());

                match self.git.get_merge_feature_commits(commit) {
                    Ok(feature_commits) => {
                        for fc in feature_commits {
                            if !seen_shas.contains(&fc.sha) {
                                result.push(fc.clone());
                                seen_shas.insert(fc.sha);
                            }
                        }
                    }
                    Err(e) => {
                        warn!("无法获取merge commit {} 的feature分支commits: {}", commit.sha, e);
                    }
                }
            } else {
                result.push(commit.clone());
                seen_shas.insert(commit.sha.clone());
            }
        }

        result.sort_by(|a, b| b.time.cmp(&a.time));
        Ok(result)
    }

    fn get_category_name(&self, commit_type: &str) -> String {
        match commit_type {
            "feat" => "🚀 新功能".to_string(),
            "fix" => "🐛 Bug修复".to_string(),
            "perf" => "⚡ 性能优化".to_string(),
            "revert" => "↩️ 回滚".to_string(),
            "docs" => "📝 文档".to_string(),
            "style" => "💄 代码风格".to_string(),
            "refactor" => "♻️ 代码重构".to_string(),
            "test" => "✅ 测试".to_string(),
            "build" => "📦 构建".to_string(),
            "ci" => "🤖 CI".to_string(),
            "chore" => "🔧 杂项".to_string(),
            other => format!("📌 {}", other),
        }
    }

    fn format_changelog(&self, entries: &[ChangelogEntry]) -> String {
        let mut output = String::new();

        output.push_str("# Changelog\n\n");
        output.push_str(
            "> 所有重要的变更都会记录在此文件中。格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，\n"
        );
        output.push_str("> 并遵循 [语义化版本](https://semver.org/lang/zh-CN/) 规范。\n\n");

        let category_order = [
            "⚠️ 不兼容变更",
            "🚀 新功能",
            "🐛 Bug修复",
            "⚡ 性能优化",
            "♻️ 代码重构",
            "📝 文档",
            "✅ 测试",
            "💄 代码风格",
            "📦 构建",
            "🤖 CI",
            "🔧 杂项",
            "↩️ 回滚",
        ];

        for entry in entries {
            let header = if entry.is_unreleased {
                format!("## [{}]\n", entry.version)
            } else {
                match &entry.date {
                    Some(date) => format!("## [{}] - {}\n", entry.version, date),
                    None => format!("## [{}]\n", entry.version),
                }
            };
            output.push_str(&header);
            output.push('\n');

            for category in category_order.iter() {
                if let Some(items) = entry.categories.get(*category) {
                    output.push_str(&format!("### {}\n\n", category));

                    for item in items {
                        let mut line = String::from("- ");

                        if let Some(scope) = &item.scope {
                            if self.config.changelog.group_by_scope {
                                line.push_str(&format!("**{}**: ", scope));
                            }
                        }

                        line.push_str(&item.description);

                        if !item.issues.is_empty() {
                            let issues_str: Vec<String> = item
                                .issues
                                .iter()
                                .map(|i| format!("#{}", i))
                                .collect();
                            line.push_str(&format!(" ({})", issues_str.join(", ")));
                        }

                        line.push_str(&format!(" ([{}]({})", &item.commit_sha[..7], "#"));
                        line.push(')');

                        output.push_str(&line);
                        output.push('\n');
                    }

                    output.push('\n');
                }
            }
        }

        output
    }

    fn write_changelog(&self, path: &PathBuf, content: &str) -> Result<()> {
        fs::write(path, content)?;
        info!("CHANGELOG 已写入: {:?}", path);
        Ok(())
    }

    fn append_changelog(&self, path: &PathBuf, new_content: &str) -> Result<()> {
        let existing = fs::read_to_string(path)?;

        let header_end = existing.find("## [").unwrap_or(existing.len());
        let (header, rest) = existing.split_at(header_end);

        let new_entries = new_content.find("## [").unwrap_or(0);
        let new_entries_content = if new_entries > 0 {
            &new_content[new_entries..]
        } else {
            new_content
        };

        let mut merged = String::new();
        merged.push_str(header);
        merged.push_str(new_entries_content);
        if !rest.is_empty() {
            merged.push_str("\n");
            merged.push_str(rest);
        }

        fs::write(path, merged)?;
        info!("CHANGELOG 已更新: {:?}", path);
        Ok(())
    }

    fn init(&self, output_path: &str, force: bool) -> Result<()> {
        let path = PathBuf::from(output_path);

        if path.exists() && !force {
            return Err(GitFlowError::ConfigError(format!(
                "文件已存在: {:?}，使用 --force 强制覆盖",
                path
            )));
        }

        let initial_content = r#"# Changelog

> 所有重要的变更都会记录在此文件中。格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
> 并遵循 [语义化版本](https://semver.org/lang/zh-CN/) 规范。

## [Unreleased]

### 🚀 新功能

- 初始版本发布

"#;

        fs::write(&path, initial_content)?;

        println!();
        println!("{} CHANGELOG 初始化完成!", "✓".green().bold());
        println!("  文件: {}", path.display());
        println!();
        println!("使用以下命令生成变更日志:");
        println!("  {}", "gitflow changelog generate".dimmed());

        Ok(())
    }
}

fn extract_issues_from_commit(message: &str) -> Vec<String> {
    let re = Regex::new(r"(?:#|GH-)(\d+)").unwrap();
    let mut issues = Vec::new();

    for cap in re.captures_iter(message) {
        if let Some(id) = cap.get(1) {
            issues.push(id.as_str().to_string());
        }
    }

    issues
}
