use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{debug, info};

use crate::command::{CommandHandler, ModuleCommand};
use crate::context::AppContext;
use crate::errors::{GitFlowError, Result};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Config {
    #[serde(default)]
    pub general: GeneralConfig,

    #[serde(default)]
    pub branch: BranchConfig,

    #[serde(default)]
    pub commit: CommitConfig,

    #[serde(default)]
    pub pr: PrConfig,

    #[serde(default)]
    pub changelog: ChangelogConfig,

    #[serde(default)]
    pub health: HealthConfig,

    #[serde(default)]
    pub jira: JiraConfig,

    #[serde(default)]
    pub git_platform: GitPlatformConfig,

    #[serde(default)]
    pub hooks: HooksConfig,

    #[serde(default)]
    pub custom: HashMap<String, toml::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GeneralConfig {
    #[serde(default = "default_log_level")]
    pub log_level: String,

    #[serde(default)]
    pub color_output: bool,

    #[serde(default = "default_editor")]
    pub editor: String,

    #[serde(default)]
    pub default_remote: String,

    #[serde(default = "default_base_branch")]
    pub default_base_branch: String,
}

impl Default for GeneralConfig {
    fn default() -> Self {
        Self {
            log_level: default_log_level(),
            color_output: true,
            editor: default_editor(),
            default_remote: "origin".to_string(),
            default_base_branch: default_base_branch(),
        }
    }
}

fn default_log_level() -> String {
    "info".to_string()
}

fn default_editor() -> String {
    std::env::var("EDITOR").unwrap_or_else(|_| "vim".to_string())
}

fn default_base_branch() -> String {
    "main".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BranchConfig {
    #[serde(default = "default_branch_types")]
    pub types: Vec<String>,

    #[serde(default = "default_branch_name_pattern")]
    pub name_pattern: String,

    #[serde(default = "default_protected_branches")]
    pub protected_branches: Vec<String>,

    #[serde(default = "default_clean_age_threshold")]
    pub clean_age_threshold_days: i64,

    #[serde(default)]
    pub auto_delete_merged: bool,

    #[serde(default = "default_jira_integration")]
    pub jira_integration: bool,

    #[serde(default = "default_jira_pattern")]
    pub jira_issue_pattern: String,

    #[serde(default = "default_clean_type_rules")]
    pub clean_type_rules: std::collections::HashMap<String, i64>,
}

impl Default for BranchConfig {
    fn default() -> Self {
        Self {
            types: default_branch_types(),
            name_pattern: default_branch_name_pattern(),
            protected_branches: default_protected_branches(),
            clean_age_threshold_days: default_clean_age_threshold(),
            auto_delete_merged: false,
            jira_integration: default_jira_integration(),
            jira_issue_pattern: default_jira_pattern(),
            clean_type_rules: default_clean_type_rules(),
        }
    }
}

fn default_branch_types() -> Vec<String> {
    vec![
        "feature".to_string(),
        "bugfix".to_string(),
        "hotfix".to_string(),
        "release".to_string(),
        "chore".to_string(),
    ]
}

fn default_branch_name_pattern() -> String {
    "{type}/{issue}-{description}".to_string()
}

fn default_protected_branches() -> Vec<String> {
    vec![
        "main".to_string(),
        "master".to_string(),
        "develop".to_string(),
    ]
}

fn default_clean_age_threshold() -> i64 {
    30
}

fn default_jira_integration() -> bool {
    false
}

fn default_jira_pattern() -> String {
    r"[A-Z]+-\d+".to_string()
}

fn default_clean_type_rules() -> std::collections::HashMap<String, i64> {
    let mut rules = std::collections::HashMap::new();
    rules.insert("feature".to_string(), 0);
    rules.insert("bugfix".to_string(), 7);
    rules.insert("hotfix".to_string(), 30);
    rules.insert("release".to_string(), 90);
    rules.insert("chore".to_string(), 3);
    rules
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CommitConfig {
    #[serde(default = "default_commit_types")]
    pub types: Vec<String>,

    #[serde(default = "default_scopes")]
    pub scopes: Vec<String>,

    #[serde(default = "default_max_subject_length")]
    pub max_subject_length: usize,

    #[serde(default = "default_max_body_line_length")]
    pub max_body_line_length: usize,

    #[serde(default)]
    pub require_scope: bool,

    #[serde(default)]
    pub require_body: bool,

    #[serde(default = "default_conventional_commits")]
    pub conventional_commits: bool,

    #[serde(default)]
    pub allow_custom_types: bool,

    #[serde(default = "default_custom_template")]
    pub custom_template: Option<String>,

    #[serde(default)]
    pub pre_commit: PreCommitConfig,
}

impl Default for CommitConfig {
    fn default() -> Self {
        Self {
            types: default_commit_types(),
            scopes: default_scopes(),
            max_subject_length: default_max_subject_length(),
            max_body_line_length: default_max_body_line_length(),
            require_scope: false,
            require_body: false,
            conventional_commits: default_conventional_commits(),
            allow_custom_types: false,
            custom_template: default_custom_template(),
            pre_commit: PreCommitConfig::default(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PreCommitConfig {
    #[serde(default)]
    pub enabled: bool,

    #[serde(default)]
    pub run_lint: bool,

    #[serde(default)]
    pub run_tests: bool,

    #[serde(default)]
    pub lint_command: Option<String>,

    #[serde(default)]
    pub test_command: Option<String>,

    #[serde(default)]
    pub fail_on_warnings: bool,
}

fn default_commit_types() -> Vec<String> {
    vec![
        "feat".to_string(),
        "fix".to_string(),
        "docs".to_string(),
        "style".to_string(),
        "refactor".to_string(),
        "perf".to_string(),
        "test".to_string(),
        "chore".to_string(),
        "build".to_string(),
        "ci".to_string(),
        "revert".to_string(),
    ]
}

fn default_scopes() -> Vec<String> {
    Vec::new()
}

fn default_max_subject_length() -> usize {
    72
}

fn default_max_body_line_length() -> usize {
    100
}

fn default_conventional_commits() -> bool {
    true
}

fn default_custom_template() -> Option<String> {
    None
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrConfig {
    #[serde(default = "default_platform")]
    pub platform: String,

    #[serde(default)]
    pub default_reviewers: Vec<String>,

    #[serde(default)]
    pub default_labels: Vec<String>,

    #[serde(default = "default_pr_template")]
    pub template: String,

    #[serde(default)]
    pub auto_link_jira: bool,

    #[serde(default = "default_default_base")]
    pub default_base: String,

    #[serde(default)]
    pub include_commit_summary: bool,

    #[serde(default)]
    pub draft_by_default: bool,
}

impl Default for PrConfig {
    fn default() -> Self {
        Self {
            platform: default_platform(),
            default_reviewers: Vec::new(),
            default_labels: Vec::new(),
            template: default_pr_template(),
            auto_link_jira: true,
            default_base: default_default_base(),
            include_commit_summary: true,
            draft_by_default: false,
        }
    }
}

fn default_platform() -> String {
    "github".to_string()
}

fn default_pr_template() -> String {
    r#"## 描述
{description}

## 变更类型
- [ ] Bug 修复
- [ ] 新功能
- [ ] 文档更新
- [ ] 性能优化
- [ ] 代码重构

## 关联 Issue
{jira_issue}

## 测试
- [ ] 单元测试已通过
- [ ] 集成测试已通过
- [ ] 已进行代码审查

## 备注
{notes}
"#
    .to_string()
}

fn default_default_base() -> String {
    "main".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChangelogConfig {
    #[serde(default = "default_output_file")]
    pub output_file: String,

    #[serde(default = "default_changelog_format")]
    pub format: String,

    #[serde(default = "default_include_types")]
    pub include_types: Vec<String>,

    #[serde(default)]
    pub include_unreleased: bool,

    #[serde(default)]
    pub group_by_scope: bool,

    #[serde(default = "default_version_pattern")]
    pub version_tag_pattern: String,

    #[serde(default)]
    pub prepend_new_entries: bool,
}

impl Default for ChangelogConfig {
    fn default() -> Self {
        Self {
            output_file: default_output_file(),
            format: default_changelog_format(),
            include_types: default_include_types(),
            include_unreleased: true,
            group_by_scope: false,
            version_tag_pattern: default_version_pattern(),
            prepend_new_entries: true,
        }
    }
}

fn default_output_file() -> String {
    "CHANGELOG.md".to_string()
}

fn default_changelog_format() -> String {
    "keepachangelog".to_string()
}

fn default_include_types() -> Vec<String> {
    vec![
        "feat".to_string(),
        "fix".to_string(),
        "perf".to_string(),
        "revert".to_string(),
    ]
}

fn default_version_pattern() -> String {
    r"^v?\d+\.\d+\.\d+".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthConfig {
    #[serde(default = "default_large_file_threshold")]
    pub large_file_threshold_mb: u64,

    #[serde(default = "default_stale_branch_threshold")]
    pub stale_branch_threshold_days: i64,

    #[serde(default = "default_min_ci_pass_rate")]
    pub min_ci_pass_rate: f64,

    #[serde(default)]
    pub check_large_files: bool,

    #[serde(default)]
    pub check_stale_branches: bool,

    #[serde(default)]
    pub check_dependencies: bool,

    #[serde(default)]
    pub check_ci_status: bool,

    #[serde(default)]
    pub ignored_files: Vec<String>,

    #[serde(default)]
    pub ignored_branches: Vec<String>,
}

impl Default for HealthConfig {
    fn default() -> Self {
        Self {
            large_file_threshold_mb: default_large_file_threshold(),
            stale_branch_threshold_days: default_stale_branch_threshold(),
            min_ci_pass_rate: default_min_ci_pass_rate(),
            check_large_files: true,
            check_stale_branches: true,
            check_dependencies: true,
            check_ci_status: true,
            ignored_files: Vec::new(),
            ignored_branches: Vec::new(),
        }
    }
}

fn default_large_file_threshold() -> u64 {
    5
}

fn default_stale_branch_threshold() -> i64 {
    90
}

fn default_min_ci_pass_rate() -> f64 {
    80.0
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct JiraConfig {
    #[serde(default)]
    pub enabled: bool,

    #[serde(default)]
    pub base_url: Option<String>,

    #[serde(default)]
    pub api_token: Option<String>,

    #[serde(default)]
    pub username: Option<String>,

    #[serde(default)]
    pub project_key: Option<String>,

    #[serde(default)]
    pub issue_types: Vec<String>,

    #[serde(default)]
    pub auto_transition: bool,

    #[serde(default)]
    pub transition_status: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct GitPlatformConfig {
    #[serde(default)]
    pub github: GitHubConfig,

    #[serde(default)]
    pub gitlab: GitLabConfig,

    #[serde(default)]
    pub bitbucket: BitbucketConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct GitHubConfig {
    #[serde(default)]
    pub api_token: Option<String>,

    #[serde(default)]
    pub base_url: Option<String>,

    #[serde(default)]
    pub owner: Option<String>,

    #[serde(default)]
    pub repo: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct GitLabConfig {
    #[serde(default)]
    pub api_token: Option<String>,

    #[serde(default)]
    pub base_url: Option<String>,

    #[serde(default)]
    pub project_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct BitbucketConfig {
    #[serde(default)]
    pub api_token: Option<String>,

    #[serde(default)]
    pub base_url: Option<String>,

    #[serde(default)]
    pub workspace: Option<String>,

    #[serde(default)]
    pub repo_slug: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct HooksConfig {
    #[serde(default)]
    pub pre_commit: Option<Vec<String>>,

    #[serde(default)]
    pub commit_msg: Option<Vec<String>>,

    #[serde(default)]
    pub pre_push: Option<Vec<String>>,
}

pub mod cli {
    use clap::Subcommand;

    use super::*;

    #[derive(Subcommand, Debug, Clone)]
    pub enum ConfigCommands {
        #[command(about = "初始化配置文件")]
        Init {
            #[arg(short, long, help = "全局配置")]
            global: bool,

            #[arg(short, long, help = "强制覆盖现有配置")]
            force: bool,
        },

        #[command(about = "查看配置")]
        Show {
            #[arg(short, long, help = "查看全局配置")]
            global: bool,

            #[arg(short, long, help = "查看所有合并后的配置")]
            merged: bool,
        },

        #[command(about = "设置配置项")]
        Set {
            #[arg(help = "配置键，如 commit.types")]
            key: String,

            #[arg(help = "配置值")]
            value: String,

            #[arg(short, long, help = "设置全局配置")]
            global: bool,
        },

        #[command(about = "获取配置项")]
        Get {
            #[arg(help = "配置键")]
            key: String,

            #[arg(short, long, help = "从全局配置获取")]
            global: bool,
        },
    }

    pub struct ConfigHandler {
        ctx: AppContext,
        cmd: ConfigCommands,
    }

    impl ConfigHandler {
        pub fn new(ctx: AppContext, cmd: ConfigCommands) -> Self {
            Self { ctx, cmd }
        }
    }

    #[async_trait::async_trait]
    impl CommandHandler for ConfigHandler {
        async fn handle(&self) -> Result<()> {
            let manager = self.ctx.config.clone();
            manager.handle(&self.cmd).await
        }
    }

    pub struct ConfigModule;

    impl ModuleCommand for ConfigModule {
        type Command = ConfigCommands;
        type Handler = ConfigHandler;

        fn name() -> &'static str {
            "config"
        }

        fn about() -> &'static str {
            "配置管理 - 管理全局和项目配置"
        }

        fn create_handler(ctx: crate::context::AppContext, cmd: &Self::Command) -> Result<Self::Handler> {
            Ok(ConfigHandler::new(ctx, cmd.clone()))
        }
    }
}

pub use cli::{ConfigCommands, ConfigHandler, ConfigModule};

pub struct ConfigManager {
    global_config_path: PathBuf,
    project_config_path: PathBuf,
    config: Arc<RwLock<Config>>,
}

impl ConfigManager {
    pub fn new() -> Result<Self> {
        let home_dir = dirs::home_dir().ok_or_else(|| GitFlowError::ConfigError("无法获取用户主目录".into()))?;
        let global_config_path = home_dir.join(".config").join("gitflow").join("config.toml");

        let project_config_path = find_project_config()?.unwrap_or_else(|| {
            PathBuf::from(".gitflow.toml")
        });

        let initial_config = Self::load_config_from_paths(&global_config_path, &project_config_path)?;

        Ok(Self {
            global_config_path,
            project_config_path,
            config: Arc::new(RwLock::new(initial_config)),
        })
    }

    pub fn with_custom_path(custom_path: &str) -> Result<Self> {
        let path = PathBuf::from(custom_path);
        let initial_config = Self::load_config_from_paths(&path, &path)?;
        Ok(Self {
            global_config_path: path.clone(),
            project_config_path: path,
            config: Arc::new(RwLock::new(initial_config)),
        })
    }

    pub fn from_config(config: Config) -> Self {
        let home_dir = dirs::home_dir().unwrap_or_else(|| PathBuf::from("/tmp"));
        let global_config_path = home_dir.join(".config").join("gitflow").join("config.toml");
        let project_config_path = PathBuf::from(".gitflow.toml");

        Self {
            global_config_path,
            project_config_path,
            config: Arc::new(RwLock::new(config)),
        }
    }

    pub async fn handle(&self, command: &ConfigCommands) -> Result<()> {
        match command {
            ConfigCommands::Init { global, force } => {
                self.init(*global, *force)
            }
            ConfigCommands::Show { global, merged } => {
                self.show(*global, *merged)
            }
            ConfigCommands::Set { key, value, global } => {
                self.set_value(key, value, *global)
            }
            ConfigCommands::Get { key, global } => {
                let value = self.get_value(key, *global)?;
                println!("{}", value);
                Ok(())
            }
        }
    }

    fn show(&self, global: bool, merged: bool) -> Result<()> {
        let config = if merged {
            self.load_blocking()?
        } else if global {
            self.load_global()?
        } else {
            self.load_project()?
        };

        let toml_str = toml::to_string_pretty(&config)?;
        println!("{}", toml_str);
        Ok(())
    }

    fn load_config_from_paths(global: &Path, project: &Path) -> Result<Config> {
        let mut config = Config::default();

        if global.exists() {
            debug!("加载全局配置: {:?}", global);
            let content = fs::read_to_string(global)?;
            let global_config: Config = toml::from_str(&content)?;
            config = merge_config(config, global_config);
        }

        if project.exists() && project != global {
            debug!("加载项目配置: {:?}", project);
            let content = fs::read_to_string(project)?;
            let project_config: Config = toml::from_str(&content)?;
            config = merge_config(config, project_config);
        }

        Ok(config)
    }

    pub async fn get(&self) -> Config {
        self.config.read().await.clone()
    }

    pub async fn reload(&self) -> Result<()> {
        let new_config = Self::load_config_from_paths(&self.global_config_path, &self.project_config_path)?;
        let mut w = self.config.write().await;
        *w = new_config;
        debug!("配置已重新加载");
        Ok(())
    }

    pub fn load_blocking(&self) -> Result<Config> {
        Self::load_config_from_paths(&self.global_config_path, &self.project_config_path)
    }

    pub fn load_global(&self) -> Result<Config> {
        if self.global_config_path.exists() {
            self.load_from_file(&self.global_config_path)
        } else {
            Ok(Config::default())
        }
    }

    pub fn load_project(&self) -> Result<Config> {
        if self.project_config_path.exists() {
            self.load_from_file(&self.project_config_path)
        } else {
            Ok(Config::default())
        }
    }

    fn load_from_file(&self, path: &Path) -> Result<Config> {
        let content = fs::read_to_string(path)?;
        let config: Config = toml::from_str(&content)?;
        Ok(config)
    }

    pub fn save_global(&self, config: &Config) -> Result<()> {
        if let Some(parent) = self.global_config_path.parent() {
            fs::create_dir_all(parent)?;
        }
        let content = toml::to_string_pretty(config)?;
        fs::write(&self.global_config_path, content)?;
        info!("全局配置已保存到: {:?}", self.global_config_path);
        Ok(())
    }

    pub fn save_project(&self, config: &Config) -> Result<()> {
        if let Some(parent) = self.project_config_path.parent() {
            fs::create_dir_all(parent)?;
        }
        let content = toml::to_string_pretty(config)?;
        fs::write(&self.project_config_path, content)?;
        info!("项目配置已保存到: {:?}", self.project_config_path);
        Ok(())
    }

    pub fn global_path(&self) -> &Path {
        &self.global_config_path
    }

    pub fn project_path(&self) -> &Path {
        &self.project_config_path
    }

    pub fn set_value(&self, key: &str, value: &str, global: bool) -> Result<()> {
        let mut config = if global {
            self.load_global()?
        } else {
            self.load_project()?
        };

        set_config_value(&mut config, key, value)?;

        if global {
            self.save_global(&config)
        } else {
            self.save_project(&config)
        }
    }

    pub fn get_value(&self, key: &str, global: bool) -> Result<String> {
        let config = if global {
            self.load_global()?
        } else {
            self.load_blocking()?
        };

        get_config_value(&config, key)
    }

    pub fn init(&self, global: bool, force: bool) -> Result<()> {
        let path = if global {
            &self.global_config_path
        } else {
            &self.project_config_path
        };

        if path.exists() && !force {
            return Err(GitFlowError::ConfigError(format!(
                "配置文件已存在: {:?}，使用 --force 强制覆盖",
                path
            )));
        }

        let config = Config::default();
        if global {
            self.save_global(&config)
        } else {
            self.save_project(&config)
        }
    }
}

fn find_project_config() -> Result<Option<PathBuf>> {
    let current_dir = std::env::current_dir()?;
    let mut dir = Some(current_dir.as_path());

    while let Some(current) = dir {
        let config_path = current.join(".gitflow.toml");
        if config_path.exists() {
            return Ok(Some(config_path));
        }

        let git_dir = current.join(".git");
        if git_dir.exists() {
            return Ok(Some(current.join(".gitflow.toml")));
        }

        dir = current.parent();
    }

    Ok(None)
}

fn merge_config(mut base: Config, overlay: Config) -> Config {
    base.general = merge_general_config(base.general, overlay.general);
    base.branch = merge_branch_config(base.branch, overlay.branch);
    base.commit = merge_commit_config(base.commit, overlay.commit);
    base.pr = merge_pr_config(base.pr, overlay.pr);
    base.changelog = merge_changelog_config(base.changelog, overlay.changelog);
    base.health = merge_health_config(base.health, overlay.health);
    base.jira = merge_jira_config(base.jira, overlay.jira);
    base.git_platform = merge_git_platform_config(base.git_platform, overlay.git_platform);
    base.hooks = merge_hooks_config(base.hooks, overlay.hooks);
    base.custom.extend(overlay.custom);
    base
}

fn merge_general_config(base: GeneralConfig, overlay: GeneralConfig) -> GeneralConfig {
    GeneralConfig {
        log_level: if overlay.log_level != default_log_level() { overlay.log_level } else { base.log_level },
        color_output: overlay.color_output || base.color_output,
        editor: if !overlay.editor.is_empty() { overlay.editor } else { base.editor },
        default_remote: if !overlay.default_remote.is_empty() { overlay.default_remote } else { base.default_remote },
        default_base_branch: if !overlay.default_base_branch.is_empty() { overlay.default_base_branch } else { base.default_base_branch },
    }
}

fn merge_branch_config(base: BranchConfig, overlay: BranchConfig) -> BranchConfig {
    BranchConfig {
        types: if !overlay.types.is_empty() { overlay.types } else { base.types },
        name_pattern: if !overlay.name_pattern.is_empty() { overlay.name_pattern } else { base.name_pattern },
        protected_branches: if !overlay.protected_branches.is_empty() { overlay.protected_branches } else { base.protected_branches },
        clean_age_threshold_days: overlay.clean_age_threshold_days,
        auto_delete_merged: overlay.auto_delete_merged || base.auto_delete_merged,
        jira_integration: overlay.jira_integration || base.jira_integration,
        jira_issue_pattern: if !overlay.jira_issue_pattern.is_empty() { overlay.jira_issue_pattern } else { base.jira_issue_pattern },
        clean_type_rules: if !overlay.clean_type_rules.is_empty() { overlay.clean_type_rules } else { base.clean_type_rules },
    }
}

fn merge_commit_config(base: CommitConfig, overlay: CommitConfig) -> CommitConfig {
    CommitConfig {
        types: if !overlay.types.is_empty() { overlay.types } else { base.types },
        scopes: if !overlay.scopes.is_empty() { overlay.scopes } else { base.scopes },
        max_subject_length: if overlay.max_subject_length != 0 { overlay.max_subject_length } else { base.max_subject_length },
        max_body_line_length: if overlay.max_body_line_length != 0 { overlay.max_body_line_length } else { base.max_body_line_length },
        require_scope: overlay.require_scope || base.require_scope,
        require_body: overlay.require_body || base.require_body,
        conventional_commits: overlay.conventional_commits && base.conventional_commits,
        allow_custom_types: overlay.allow_custom_types || base.allow_custom_types,
        custom_template: overlay.custom_template.or(base.custom_template),
        pre_commit: merge_pre_commit_config(base.pre_commit, overlay.pre_commit),
    }
}

fn merge_pre_commit_config(base: PreCommitConfig, overlay: PreCommitConfig) -> PreCommitConfig {
    PreCommitConfig {
        enabled: overlay.enabled || base.enabled,
        run_lint: overlay.run_lint || base.run_lint,
        run_tests: overlay.run_tests || base.run_tests,
        lint_command: overlay.lint_command.or(base.lint_command),
        test_command: overlay.test_command.or(base.test_command),
        fail_on_warnings: overlay.fail_on_warnings || base.fail_on_warnings,
    }
}

fn merge_pr_config(base: PrConfig, overlay: PrConfig) -> PrConfig {
    PrConfig {
        platform: if !overlay.platform.is_empty() { overlay.platform } else { base.platform },
        default_reviewers: if !overlay.default_reviewers.is_empty() { overlay.default_reviewers } else { base.default_reviewers },
        default_labels: if !overlay.default_labels.is_empty() { overlay.default_labels } else { base.default_labels },
        template: if !overlay.template.is_empty() { overlay.template } else { base.template },
        auto_link_jira: overlay.auto_link_jira || base.auto_link_jira,
        default_base: if !overlay.default_base.is_empty() { overlay.default_base } else { base.default_base },
        include_commit_summary: overlay.include_commit_summary || base.include_commit_summary,
        draft_by_default: overlay.draft_by_default || base.draft_by_default,
    }
}

fn merge_changelog_config(base: ChangelogConfig, overlay: ChangelogConfig) -> ChangelogConfig {
    ChangelogConfig {
        output_file: if !overlay.output_file.is_empty() { overlay.output_file } else { base.output_file },
        format: if !overlay.format.is_empty() { overlay.format } else { base.format },
        include_types: if !overlay.include_types.is_empty() { overlay.include_types } else { base.include_types },
        include_unreleased: overlay.include_unreleased || base.include_unreleased,
        group_by_scope: overlay.group_by_scope || base.group_by_scope,
        version_tag_pattern: if !overlay.version_tag_pattern.is_empty() { overlay.version_tag_pattern } else { base.version_tag_pattern },
        prepend_new_entries: overlay.prepend_new_entries || base.prepend_new_entries,
    }
}

fn merge_health_config(base: HealthConfig, overlay: HealthConfig) -> HealthConfig {
    HealthConfig {
        large_file_threshold_mb: if overlay.large_file_threshold_mb != 0 { overlay.large_file_threshold_mb } else { base.large_file_threshold_mb },
        stale_branch_threshold_days: if overlay.stale_branch_threshold_days != 0 { overlay.stale_branch_threshold_days } else { base.stale_branch_threshold_days },
        min_ci_pass_rate: if overlay.min_ci_pass_rate != 0.0 { overlay.min_ci_pass_rate } else { base.min_ci_pass_rate },
        check_large_files: overlay.check_large_files || base.check_large_files,
        check_stale_branches: overlay.check_stale_branches || base.check_stale_branches,
        check_dependencies: overlay.check_dependencies || base.check_dependencies,
        check_ci_status: overlay.check_ci_status || base.check_ci_status,
        ignored_files: if !overlay.ignored_files.is_empty() { overlay.ignored_files } else { base.ignored_files },
        ignored_branches: if !overlay.ignored_branches.is_empty() { overlay.ignored_branches } else { base.ignored_branches },
    }
}

fn merge_jira_config(base: JiraConfig, overlay: JiraConfig) -> JiraConfig {
    JiraConfig {
        enabled: overlay.enabled || base.enabled,
        base_url: overlay.base_url.or(base.base_url),
        api_token: overlay.api_token.or(base.api_token),
        username: overlay.username.or(base.username),
        project_key: overlay.project_key.or(base.project_key),
        issue_types: if !overlay.issue_types.is_empty() { overlay.issue_types } else { base.issue_types },
        auto_transition: overlay.auto_transition || base.auto_transition,
        transition_status: if !overlay.transition_status.is_empty() { overlay.transition_status } else { base.transition_status },
    }
}

fn merge_git_platform_config(base: GitPlatformConfig, overlay: GitPlatformConfig) -> GitPlatformConfig {
    GitPlatformConfig {
        github: merge_github_config(base.github, overlay.github),
        gitlab: merge_gitlab_config(base.gitlab, overlay.gitlab),
        bitbucket: merge_bitbucket_config(base.bitbucket, overlay.bitbucket),
    }
}

fn merge_github_config(base: GitHubConfig, overlay: GitHubConfig) -> GitHubConfig {
    GitHubConfig {
        api_token: overlay.api_token.or(base.api_token),
        base_url: overlay.base_url.or(base.base_url),
        owner: overlay.owner.or(base.owner),
        repo: overlay.repo.or(base.repo),
    }
}

fn merge_gitlab_config(base: GitLabConfig, overlay: GitLabConfig) -> GitLabConfig {
    GitLabConfig {
        api_token: overlay.api_token.or(base.api_token),
        base_url: overlay.base_url.or(base.base_url),
        project_id: overlay.project_id.or(base.project_id),
    }
}

fn merge_bitbucket_config(base: BitbucketConfig, overlay: BitbucketConfig) -> BitbucketConfig {
    BitbucketConfig {
        api_token: overlay.api_token.or(base.api_token),
        base_url: overlay.base_url.or(base.base_url),
        workspace: overlay.workspace.or(base.workspace),
        repo_slug: overlay.repo_slug.or(base.repo_slug),
    }
}

fn merge_hooks_config(base: HooksConfig, overlay: HooksConfig) -> HooksConfig {
    HooksConfig {
        pre_commit: overlay.pre_commit.or(base.pre_commit),
        commit_msg: overlay.commit_msg.or(base.commit_msg),
        pre_push: overlay.pre_push.or(base.pre_push),
    }
}

fn set_config_value(config: &mut Config, key: &str, value: &str) -> Result<()> {
    let parts: Vec<&str> = key.split('.').collect();
    if parts.is_empty() {
        return Err(GitFlowError::ConfigError("配置键不能为空".into()));
    }

    match parts[0] {
        "general" => set_general_value(config, &parts[1..], value),
        "branch" => set_branch_value(config, &parts[1..], value),
        "commit" => set_commit_value(config, &parts[1..], value),
        "pr" => set_pr_value(config, &parts[1..], value),
        "changelog" => set_changelog_value(config, &parts[1..], value),
        "health" => set_health_value(config, &parts[1..], value),
        _ => Err(GitFlowError::ConfigError(format!("未知的配置节: {}", parts[0]))),
    }
}

fn set_general_value(config: &mut Config, path: &[&str], value: &str) -> Result<()> {
    if path.is_empty() {
        return Err(GitFlowError::ConfigError("配置键不完整".into()));
    }
    match path[0] {
        "log_level" => config.general.log_level = value.to_string(),
        "color_output" => config.general.color_output = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("color_output必须是布尔值".into()))?,
        "editor" => config.general.editor = value.to_string(),
        "default_remote" => config.general.default_remote = value.to_string(),
        "default_base_branch" => config.general.default_base_branch = value.to_string(),
        _ => return Err(GitFlowError::ConfigError(format!("未知的配置项: general.{}", path[0]))),
    }
    Ok(())
}

fn set_branch_value(config: &mut Config, path: &[&str], value: &str) -> Result<()> {
    if path.is_empty() {
        return Err(GitFlowError::ConfigError("配置键不完整".into()));
    }
    match path[0] {
        "types" => config.branch.types = value.split(',').map(|s| s.trim().to_string()).collect(),
        "name_pattern" => config.branch.name_pattern = value.to_string(),
        "protected_branches" => config.branch.protected_branches = value.split(',').map(|s| s.trim().to_string()).collect(),
        "clean_age_threshold_days" => config.branch.clean_age_threshold_days = value.parse::<i64>().map_err(|_| GitFlowError::ConfigError("clean_age_threshold_days必须是整数".into()))?,
        "auto_delete_merged" => config.branch.auto_delete_merged = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("auto_delete_merged必须是布尔值".into()))?,
        "jira_integration" => config.branch.jira_integration = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("jira_integration必须是布尔值".into()))?,
        "jira_issue_pattern" => config.branch.jira_issue_pattern = value.to_string(),
        _ => return Err(GitFlowError::ConfigError(format!("未知的配置项: branch.{}", path[0]))),
    }
    Ok(())
}

fn set_commit_value(config: &mut Config, path: &[&str], value: &str) -> Result<()> {
    if path.is_empty() {
        return Err(GitFlowError::ConfigError("配置键不完整".into()));
    }
    match path[0] {
        "types" => config.commit.types = value.split(',').map(|s| s.trim().to_string()).collect(),
        "scopes" => config.commit.scopes = value.split(',').map(|s| s.trim().to_string()).collect(),
        "max_subject_length" => config.commit.max_subject_length = value.parse::<usize>().map_err(|_| GitFlowError::ConfigError("max_subject_length必须是正整数".into()))?,
        "max_body_line_length" => config.commit.max_body_line_length = value.parse::<usize>().map_err(|_| GitFlowError::ConfigError("max_body_line_length必须是正整数".into()))?,
        "require_scope" => config.commit.require_scope = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("require_scope必须是布尔值".into()))?,
        "require_body" => config.commit.require_body = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("require_body必须是布尔值".into()))?,
        "conventional_commits" => config.commit.conventional_commits = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("conventional_commits必须是布尔值".into()))?,
        "allow_custom_types" => config.commit.allow_custom_types = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("allow_custom_types必须是布尔值".into()))?,
        _ => return Err(GitFlowError::ConfigError(format!("未知的配置项: commit.{}", path[0]))),
    }
    Ok(())
}

fn set_pr_value(config: &mut Config, path: &[&str], value: &str) -> Result<()> {
    if path.is_empty() {
        return Err(GitFlowError::ConfigError("配置键不完整".into()));
    }
    match path[0] {
        "platform" => config.pr.platform = value.to_string(),
        "default_reviewers" => config.pr.default_reviewers = value.split(',').map(|s| s.trim().to_string()).collect(),
        "default_labels" => config.pr.default_labels = value.split(',').map(|s| s.trim().to_string()).collect(),
        "template" => config.pr.template = value.to_string(),
        "auto_link_jira" => config.pr.auto_link_jira = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("auto_link_jira必须是布尔值".into()))?,
        "default_base" => config.pr.default_base = value.to_string(),
        "include_commit_summary" => config.pr.include_commit_summary = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("include_commit_summary必须是布尔值".into()))?,
        "draft_by_default" => config.pr.draft_by_default = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("draft_by_default必须是布尔值".into()))?,
        _ => return Err(GitFlowError::ConfigError(format!("未知的配置项: pr.{}", path[0]))),
    }
    Ok(())
}

fn set_changelog_value(config: &mut Config, path: &[&str], value: &str) -> Result<()> {
    if path.is_empty() {
        return Err(GitFlowError::ConfigError("配置键不完整".into()));
    }
    match path[0] {
        "output_file" => config.changelog.output_file = value.to_string(),
        "format" => config.changelog.format = value.to_string(),
        "include_types" => config.changelog.include_types = value.split(',').map(|s| s.trim().to_string()).collect(),
        "include_unreleased" => config.changelog.include_unreleased = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("include_unreleased必须是布尔值".into()))?,
        "group_by_scope" => config.changelog.group_by_scope = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("group_by_scope必须是布尔值".into()))?,
        "version_tag_pattern" => config.changelog.version_tag_pattern = value.to_string(),
        "prepend_new_entries" => config.changelog.prepend_new_entries = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("prepend_new_entries必须是布尔值".into()))?,
        _ => return Err(GitFlowError::ConfigError(format!("未知的配置项: changelog.{}", path[0]))),
    }
    Ok(())
}

fn set_health_value(config: &mut Config, path: &[&str], value: &str) -> Result<()> {
    if path.is_empty() {
        return Err(GitFlowError::ConfigError("配置键不完整".into()));
    }
    match path[0] {
        "large_file_threshold_mb" => config.health.large_file_threshold_mb = value.parse::<u64>().map_err(|_| GitFlowError::ConfigError("large_file_threshold_mb必须是正整数".into()))?,
        "stale_branch_threshold_days" => config.health.stale_branch_threshold_days = value.parse::<i64>().map_err(|_| GitFlowError::ConfigError("stale_branch_threshold_days必须是整数".into()))?,
        "min_ci_pass_rate" => config.health.min_ci_pass_rate = value.parse::<f64>().map_err(|_| GitFlowError::ConfigError("min_ci_pass_rate必须是数字".into()))?,
        "check_large_files" => config.health.check_large_files = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("check_large_files必须是布尔值".into()))?,
        "check_stale_branches" => config.health.check_stale_branches = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("check_stale_branches必须是布尔值".into()))?,
        "check_dependencies" => config.health.check_dependencies = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("check_dependencies必须是布尔值".into()))?,
        "check_ci_status" => config.health.check_ci_status = value.parse::<bool>().map_err(|_| GitFlowError::ConfigError("check_ci_status必须是布尔值".into()))?,
        "ignored_files" => config.health.ignored_files = value.split(',').map(|s| s.trim().to_string()).collect(),
        "ignored_branches" => config.health.ignored_branches = value.split(',').map(|s| s.trim().to_string()).collect(),
        _ => return Err(GitFlowError::ConfigError(format!("未知的配置项: health.{}", path[0]))),
    }
    Ok(())
}

fn get_config_value(config: &Config, key: &str) -> Result<String> {
    let parts: Vec<&str> = key.split('.').collect();
    if parts.is_empty() {
        return Err(GitFlowError::ConfigError("配置键不能为空".into()));
    }

    match parts[0] {
        "general" => get_general_value(config, &parts[1..]),
        "branch" => get_branch_value(config, &parts[1..]),
        "commit" => get_commit_value(config, &parts[1..]),
        "pr" => get_pr_value(config, &parts[1..]),
        "changelog" => get_changelog_value(config, &parts[1..]),
        "health" => get_health_value(config, &parts[1..]),
        _ => Err(GitFlowError::ConfigError(format!("未知的配置节: {}", parts[0]))),
    }
}

fn get_general_value(config: &Config, path: &[&str]) -> Result<String> {
    if path.is_empty() {
        return Ok(toml::to_string_pretty(&config.general)?);
    }
    match path[0] {
        "log_level" => Ok(config.general.log_level.clone()),
        "color_output" => Ok(config.general.color_output.to_string()),
        "editor" => Ok(config.general.editor.clone()),
        "default_remote" => Ok(config.general.default_remote.clone()),
        "default_base_branch" => Ok(config.general.default_base_branch.clone()),
        _ => Err(GitFlowError::ConfigError(format!("未知的配置项: general.{}", path[0]))),
    }
}

fn get_branch_value(config: &Config, path: &[&str]) -> Result<String> {
    if path.is_empty() {
        return Ok(toml::to_string_pretty(&config.branch)?);
    }
    match path[0] {
        "types" => Ok(config.branch.types.join(", ")),
        "name_pattern" => Ok(config.branch.name_pattern.clone()),
        "protected_branches" => Ok(config.branch.protected_branches.join(", ")),
        "clean_age_threshold_days" => Ok(config.branch.clean_age_threshold_days.to_string()),
        "auto_delete_merged" => Ok(config.branch.auto_delete_merged.to_string()),
        "jira_integration" => Ok(config.branch.jira_integration.to_string()),
        "jira_issue_pattern" => Ok(config.branch.jira_issue_pattern.clone()),
        _ => Err(GitFlowError::ConfigError(format!("未知的配置项: branch.{}", path[0]))),
    }
}

fn get_commit_value(config: &Config, path: &[&str]) -> Result<String> {
    if path.is_empty() {
        return Ok(toml::to_string_pretty(&config.commit)?);
    }
    match path[0] {
        "types" => Ok(config.commit.types.join(", ")),
        "scopes" => Ok(config.commit.scopes.join(", ")),
        "max_subject_length" => Ok(config.commit.max_subject_length.to_string()),
        "max_body_line_length" => Ok(config.commit.max_body_line_length.to_string()),
        "require_scope" => Ok(config.commit.require_scope.to_string()),
        "require_body" => Ok(config.commit.require_body.to_string()),
        "conventional_commits" => Ok(config.commit.conventional_commits.to_string()),
        "allow_custom_types" => Ok(config.commit.allow_custom_types.to_string()),
        _ => Err(GitFlowError::ConfigError(format!("未知的配置项: commit.{}", path[0]))),
    }
}

fn get_pr_value(config: &Config, path: &[&str]) -> Result<String> {
    if path.is_empty() {
        return Ok(toml::to_string_pretty(&config.pr)?);
    }
    match path[0] {
        "platform" => Ok(config.pr.platform.clone()),
        "default_reviewers" => Ok(config.pr.default_reviewers.join(", ")),
        "default_labels" => Ok(config.pr.default_labels.join(", ")),
        "template" => Ok(config.pr.template.clone()),
        "auto_link_jira" => Ok(config.pr.auto_link_jira.to_string()),
        "default_base" => Ok(config.pr.default_base.clone()),
        "include_commit_summary" => Ok(config.pr.include_commit_summary.to_string()),
        "draft_by_default" => Ok(config.pr.draft_by_default.to_string()),
        _ => Err(GitFlowError::ConfigError(format!("未知的配置项: pr.{}", path[0]))),
    }
}

fn get_changelog_value(config: &Config, path: &[&str]) -> Result<String> {
    if path.is_empty() {
        return Ok(toml::to_string_pretty(&config.changelog)?);
    }
    match path[0] {
        "output_file" => Ok(config.changelog.output_file.clone()),
        "format" => Ok(config.changelog.format.clone()),
        "include_types" => Ok(config.changelog.include_types.join(", ")),
        "include_unreleased" => Ok(config.changelog.include_unreleased.to_string()),
        "group_by_scope" => Ok(config.changelog.group_by_scope.to_string()),
        "version_tag_pattern" => Ok(config.changelog.version_tag_pattern.clone()),
        "prepend_new_entries" => Ok(config.changelog.prepend_new_entries.to_string()),
        _ => Err(GitFlowError::ConfigError(format!("未知的配置项: changelog.{}", path[0]))),
    }
}

fn get_health_value(config: &Config, path: &[&str]) -> Result<String> {
    if path.is_empty() {
        return Ok(toml::to_string_pretty(&config.health)?);
    }
    match path[0] {
        "large_file_threshold_mb" => Ok(config.health.large_file_threshold_mb.to_string()),
        "stale_branch_threshold_days" => Ok(config.health.stale_branch_threshold_days.to_string()),
        "min_ci_pass_rate" => Ok(config.health.min_ci_pass_rate.to_string()),
        "check_large_files" => Ok(config.health.check_large_files.to_string()),
        "check_stale_branches" => Ok(config.health.check_stale_branches.to_string()),
        "check_dependencies" => Ok(config.health.check_dependencies.to_string()),
        "check_ci_status" => Ok(config.health.check_ci_status.to_string()),
        "ignored_files" => Ok(config.health.ignored_files.join(", ")),
        "ignored_branches" => Ok(config.health.ignored_branches.join(", ")),
        _ => Err(GitFlowError::ConfigError(format!("未知的配置项: health.{}", path[0]))),
    }
}
