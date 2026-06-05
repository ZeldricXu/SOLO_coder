use colored::Colorize;
use dialoguer::{theme::ColorfulTheme, Input, Select, Confirm};
use indicatif::{ProgressBar, ProgressStyle};
use std::process::Command;
use tracing::warn;

use crate::cli::CommitCommands;
use crate::config::Config;
use crate::errors::{GitFlowError, Result};
use crate::git::{parse_commit_for_conventional, ConventionalCommit, GitRepository};

pub struct CommitManager<'a> {
    git: &'a GitRepository,
    config: &'a Config,
}

#[derive(Debug, Clone)]
pub struct ValidationResult {
    pub passed: bool,
    pub errors: Vec<String>,
    pub warnings: Vec<String>,
}

impl<'a> CommitManager<'a> {
    pub fn new(git: &'a GitRepository, config: &'a Config) -> Self {
        Self { git, config }
    }

    pub fn handle(&self, command: &CommitCommands) -> Result<()> {
        match command {
            CommitCommands::Check {
                message,
                file,
                strict,
            } => self.check(message.as_deref(), file.as_deref(), *strict),
            CommitCommands::Create {
                no_lint,
                no_test,
                r#type,
                scope,
                subject,
                body,
                breaking,
                issues,
            } => self.create(
                *no_lint,
                *no_test,
                r#type.as_deref(),
                scope.as_deref(),
                subject.as_deref(),
                body.as_deref(),
                breaking.as_deref(),
                issues,
            ),
            CommitCommands::InstallHook { force, hook_type } => {
                self.install_hook(*force, hook_type)
            }
        }
    }

    fn check(
        &self,
        message: Option<&str>,
        file: Option<&str>,
        strict: bool,
    ) -> Result<()> {
        let commit_message = match (message, file) {
            (Some(msg), _) if msg != "HEAD" => msg.to_string(),
            (_, Some(file_path)) => std::fs::read_to_string(file_path)?,
            _ => {
                let commit = self.git.get_commit("HEAD")?;
                commit.message
            }
        };

        println!();
        println!("{} 检查提交消息...", "🔍".cyan());
        println!();

        let result = self.validate_message(&commit_message, strict)?;

        if result.passed {
            println!("{} 提交消息格式正确!", "✓".green().bold());
            println!();

            if let Some(conv) = parse_commit_for_conventional(&commit_message) {
                self.print_conventional_commit(&conv);
            }
        } else {
            println!("{} 提交消息格式检查失败!", "✗".red().bold());
            println!();

            if !result.errors.is_empty() {
                println!("{} 错误:", "❌".red());
                for error in &result.errors {
                    println!("  - {}", error.red());
                }
                println!();
            }

            if !result.warnings.is_empty() {
                println!("{} 警告:", "⚠".yellow());
                for warning in &result.warnings {
                    println!("  - {}", warning.yellow());
                }
                println!();
            }

            self.print_commit_template();

            return Err(GitFlowError::InvalidCommitMessage(
                "提交消息不符合规范".into(),
            ));
        }

        Ok(())
    }

    fn validate_message(
        &self,
        message: &str,
        strict: bool,
    ) -> Result<ValidationResult> {
        let config = &self.config.commit;
        let mut errors = Vec::new();
        let mut warnings = Vec::new();

        let lines: Vec<&str> = message.lines().collect();

        if lines.is_empty() || lines[0].trim().is_empty() {
            errors.push("提交消息不能为空".into());
            return Ok(ValidationResult {
                passed: false,
                errors,
                warnings,
            });
        }

        if config.conventional_commits {
            match parse_commit_for_conventional(message) {
                Some(conv) => {
                    if !config.allow_custom_types && !config.types.contains(&conv.r#type) {
                        errors.push(format!(
                            "未知的提交类型 '{}'，允许的类型: {}",
                            conv.r#type,
                            config.types.join(", ")
                        ));
                    }

                    if config.require_scope && conv.scope.is_none() {
                        errors.push("提交需要指定范围 (scope)".into());
                    }

                    if let Some(ref scope) = conv.scope {
                        if !config.scopes.is_empty() && !config.scopes.contains(scope) {
                            warnings.push(format!(
                                "范围 '{}' 不在预定义的范围列表中",
                                scope
                            ));
                        }
                    }

                    if conv.subject.len() > config.max_subject_length {
                        errors.push(format!(
                            "提交摘要过长 ({} 字符)，最大允许 {} 字符",
                            conv.subject.len(),
                            config.max_subject_length
                        ));
                    }

                    if conv.subject.ends_with('.') {
                        warnings.push("提交摘要不应以句号结尾".into());
                    }

                    if conv.subject.chars().next().map(|c| c.is_uppercase()).unwrap_or(false) {
                        warnings.push("提交摘要首字母建议小写".into());
                    }
                }
                None => {
                    errors.push(
                        "提交消息不符合 Conventional Commits 格式".into(),
                    );
                }
            }
        }

        if strict && config.require_body && lines.len() < 3 {
            errors.push("提交需要包含详细描述 (body)".into());
        }

        for (i, line) in lines.iter().enumerate().skip(1) {
            if line.len() > config.max_body_line_length {
                warnings.push(format!(
                    "第 {} 行过长 ({} 字符)，建议不超过 {} 字符",
                    i + 1,
                    line.len(),
                    config.max_body_line_length
                ));
            }
        }

        let passed = errors.is_empty() && (!strict || warnings.is_empty());

        Ok(ValidationResult {
            passed,
            errors,
            warnings,
        })
    }

    fn create(
        &self,
        no_lint: bool,
        no_test: bool,
        commit_type: Option<&str>,
        scope: Option<&str>,
        subject: Option<&str>,
        body: Option<&str>,
        breaking: Option<&str>,
        issues: &[String],
    ) -> Result<()> {
        let pre_commit_config = &self.config.commit.pre_commit;

        if pre_commit_config.enabled {
            if !no_lint && pre_commit_config.run_lint {
                self.run_lint(pre_commit_config)?;
            }

            if !no_test && pre_commit_config.run_tests {
                self.run_tests(pre_commit_config)?;
            }
        }

        if self.git.is_clean()? {
            return Err(GitFlowError::Other(
                "没有可提交的变更，请先使用 git add 添加文件".into(),
            ));
        }

        println!();
        println!("{} 创建符合规范的提交", "✍".cyan());
        println!();

        let commit_type = self.select_commit_type(commit_type)?;
        let scope = self.select_scope(scope)?;
        let subject = self.enter_subject(subject)?;
        let body = self.enter_body(body)?;
        let has_breaking = breaking.is_some()
            || Confirm::with_theme(&ColorfulTheme::default())
                .with_prompt("是否包含不兼容变更?")
                .default(false)
                .interact()?;

        let breaking_change = if has_breaking {
            match breaking {
                Some(b) => Some(b.to_string()),
                None => {
                    let input: String = Input::with_theme(&ColorfulTheme::default())
                        .with_prompt("请描述不兼容变更")
                        .allow_empty(false)
                        .interact_text()?;
                    Some(input)
                }
            }
        } else {
            None
        };

        let commit_message = self.build_commit_message(
            &commit_type,
            scope.as_deref(),
            &subject,
            body.as_deref(),
            breaking_change.as_deref(),
            issues,
        );

        let validation = self.validate_message(&commit_message, true)?;
        if !validation.passed {
            println!();
            println!("{} 提交消息验证失败:", "✗".red());
            for e in &validation.errors {
                println!("  - {}", e);
            }
            return Err(GitFlowError::InvalidCommitMessage(
                "提交消息不符合规范".into(),
            ));
        }

        println!();
        println!("{} 提交预览:", "📝".yellow());
        println!("{}", "─".repeat(60));
        println!("{}", commit_message.dimmed());
        println!("{}", "─".repeat(60));
        println!();

        let confirmed = Confirm::with_theme(&ColorfulTheme::default())
            .with_prompt("确认提交?")
            .default(true)
            .interact()?;

        if !confirmed {
            println!("{} 提交已取消", "✗".red());
            return Ok(());
        }

        let sha = self.git.commit(&commit_message, false)?;

        println!();
        println!("{} 提交成功!", "✓".green().bold());
        println!("  SHA: {}", sha[..8].cyan());
        println!("  摘要: {}", subject);

        Ok(())
    }

    fn select_commit_type(&self, preselected: Option<&str>) -> Result<String> {
        if let Some(t) = preselected {
            if self.config.commit.types.iter().any(|x| x == t) {
                return Ok(t.to_string());
            }
        }

        let type_descriptions: Vec<(&str, &str)> = vec![
            ("feat", "新功能"),
            ("fix", "Bug修复"),
            ("docs", "文档更新"),
            ("style", "代码风格（不影响代码运行的变动）"),
            ("refactor", "重构（既不是新功能，也不是修复bug）"),
            ("perf", "性能优化"),
            ("test", "增加测试"),
            ("chore", "构建过程或辅助工具的变动"),
            ("build", "影响构建系统或外部依赖的更改"),
            ("ci", "CI配置文件和脚本的更改"),
            ("revert", "回滚之前的提交"),
        ];

        let items: Vec<String> = type_descriptions
            .iter()
            .filter(|(t, _)| self.config.commit.types.contains(&t.to_string()))
            .map(|(t, d)| format!("{:<10} {}", t, d.dimmed()))
            .collect();

        let display_items: Vec<&str> = self.config.commit.types.iter().map(|s| s.as_str()).collect();

        let selection = Select::with_theme(&ColorfulTheme::default())
            .with_prompt("请选择提交类型")
            .items(&items)
            .default(0)
            .interact()?;

        Ok(self.config.commit.types[selection].clone())
    }

    fn select_scope(&self, preselected: Option<&str>) -> Result<Option<String>> {
        if let Some(s) = preselected {
            return Ok(Some(s.to_string()));
        }

        if self.config.commit.scopes.is_empty() {
            let input: String = Input::with_theme(&ColorfulTheme::default())
                .with_prompt("请输入影响范围 (可选，按回车跳过)")
                .allow_empty(true)
                .interact_text()?;
            return Ok(if input.is_empty() { None } else { Some(input) });
        }

        let items: Vec<String> = self
            .config
            .commit
            .scopes
            .iter()
            .cloned()
            .chain(std::iter::once("自定义...".to_string()))
            .collect();

        let selection = Select::with_theme(&ColorfulTheme::default())
            .with_prompt("请选择影响范围 (可选)")
            .items(&items)
            .default(0)
            .interact()?;

        if selection == self.config.commit.scopes.len() {
            let input: String = Input::with_theme(&ColorfulTheme::default())
                .with_prompt("请输入自定义范围")
                .allow_empty(false)
                .interact_text()?;
            Ok(Some(input))
        } else {
            Ok(Some(self.config.commit.scopes[selection].clone()))
        }
    }

    fn enter_subject(&self, preselected: Option<&str>) -> Result<String> {
        if let Some(s) = preselected {
            return Ok(s.to_string());
        }

        let mut subject: String;
        loop {
            subject = Input::with_theme(&ColorfulTheme::default())
                .with_prompt("请输入提交摘要")
                .validate_with(|input: &String| -> std::result::Result<(), String> {
                    if input.is_empty() {
                        return Err("摘要不能为空".into());
                    }
                    if input.len() > self.config.commit.max_subject_length {
                        return Err(format!(
                            "摘要过长 ({} 字符)，最大允许 {} 字符",
                            input.len(),
                            self.config.commit.max_subject_length
                        ));
                    }
                    Ok(())
                })
                .interact_text()?;

            break;
        }

        Ok(subject)
    }

    fn enter_body(&self, preselected: Option<&str>) -> Result<Option<String>> {
        if let Some(b) = preselected {
            return Ok(Some(b.to_string()));
        }

        if self.config.commit.require_body {
            let input: String = Input::with_theme(&ColorfulTheme::default())
                .with_prompt("请输入详细描述")
                .allow_empty(false)
                .interact_text()?;
            Ok(Some(input))
        } else {
            let input: String = Input::with_theme(&ColorfulTheme::default())
                .with_prompt("请输入详细描述 (可选，按回车跳过)")
                .allow_empty(true)
                .interact_text()?;
            Ok(if input.is_empty() { None } else { Some(input) })
        }
    }

    fn build_commit_message(
        &self,
        commit_type: &str,
        scope: Option<&str>,
        subject: &str,
        body: Option<&str>,
        breaking_change: Option<&str>,
        issues: &[String],
    ) -> String {
        let mut header = commit_type.to_string();

        if let Some(s) = scope {
            header.push_str(&format!("({})", s));
        }

        if breaking_change.is_some() {
            header.push('!');
        }

        header.push_str(": ");
        header.push_str(subject);

        let mut message = header;

        if let Some(b) = body {
            message.push_str("\n\n");
            message.push_str(b);
        }

        if let Some(bc) = breaking_change {
            message.push_str("\n\n");
            message.push_str(&format!("BREAKING CHANGE: {}", bc));
        }

        if !issues.is_empty() {
            message.push_str("\n\n");
            let issue_refs: Vec<String> = issues
                .iter()
                .map(|i| format!("Refs: {}", i))
                .collect();
            message.push_str(&issue_refs.join("\n"));
        }

        message
    }

    fn run_lint(&self, config: &crate::config::PreCommitConfig) -> Result<()> {
        let pb = ProgressBar::new_spinner();
        pb.set_style(
            ProgressStyle::default_spinner()
                .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏")
                .template("{spinner:.green} {msg}")
                .unwrap(),
        );
        pb.set_message("运行代码检查...");

        let command = config
            .lint_command
            .clone()
            .unwrap_or_else(|| "cargo clippy --all-targets --all-features -- -D warnings".to_string());

        let parts: Vec<&str> = command.split_whitespace().collect();
        if parts.is_empty() {
            return Err(GitFlowError::Other("Lint命令为空".into()));
        }

        let output = Command::new(parts[0])
            .args(&parts[1..])
            .output()?;

        pb.finish_and_clear();

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            let stdout = String::from_utf8_lossy(&output.stdout);

            println!("{} 代码检查失败!", "✗".red());
            if !stdout.is_empty() {
                println!("{}", stdout.dimmed());
            }
            if !stderr.is_empty() {
                println!("{}", stderr.dimmed());
            }

            if config.fail_on_warnings {
                return Err(GitFlowError::Other("代码检查失败，提交已终止".into()));
            } else {
                warn!("代码检查有警告，但配置允许继续提交");
            }
        } else {
            println!("{} 代码检查通过", "✓".green());
        }

        Ok(())
    }

    fn run_tests(&self, config: &crate::config::PreCommitConfig) -> Result<()> {
        let pb = ProgressBar::new_spinner();
        pb.set_style(
            ProgressStyle::default_spinner()
                .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏")
                .template("{spinner:.green} {msg}")
                .unwrap(),
        );
        pb.set_message("运行单元测试...");

        let command = config
            .test_command
            .clone()
            .unwrap_or_else(|| "cargo test".to_string());

        let parts: Vec<&str> = command.split_whitespace().collect();
        if parts.is_empty() {
            return Err(GitFlowError::Other("测试命令为空".into()));
        }

        let output = Command::new(parts[0])
            .args(&parts[1..])
            .output()?;

        pb.finish_and_clear();

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            let stdout = String::from_utf8_lossy(&output.stdout);

            println!("{} 单元测试失败!", "✗".red());
            if !stdout.is_empty() {
                println!("{}", stdout.dimmed());
            }
            if !stderr.is_empty() {
                println!("{}", stderr.dimmed());
            }

            return Err(GitFlowError::Other("单元测试失败，提交已终止".into()));
        } else {
            println!("{} 单元测试通过", "✓".green());
        }

        Ok(())
    }

    fn install_hook(&self, force: bool, hook_type: &str) -> Result<()> {
        let git_dir = self.git.git_dir();
        let hooks_dir = git_dir.join("hooks");
        std::fs::create_dir_all(&hooks_dir)?;

        let hook_path = hooks_dir.join(hook_type);

        if hook_path.exists() && !force {
            return Err(GitFlowError::ConfigError(format!(
                "Hook文件已存在: {:?}，使用 --force 强制覆盖",
                hook_path
            )));
        }

        let exe_path = std::env::current_exe()?;
        let exe_str = exe_path.to_string_lossy();

        let hook_content = match hook_type {
            "pre-commit" => format!(
                r#"#!/bin/sh
# GitFlow CLI pre-commit hook
# 自动检查提交规范，运行lint和测试

set -e

# 检查是否有变更需要提交
if git diff --cached --quiet; then
    exit 0
fi

# 运行gitflow commit check
if ! {exe_str} commit check --strict; then
    echo ""
    echo "✗ 提交被pre-commit hook阻止，请修复上述问题后重试"
    exit 1
fi

# 运行预提交检查
if ! {exe_str} commit check --strict; then
    exit 1
fi

exit 0
"#,
                exe_str = exe_str
            ),
            "commit-msg" => format!(
                r#"#!/bin/sh
# GitFlow CLI commit-msg hook
# 检查提交消息格式

set -e

COMMIT_MSG_FILE="$1"

if ! {exe_str} commit check --file "$COMMIT_MSG_FILE" --strict; then
    echo ""
    echo "✗ 提交消息格式不符合规范，请修改后重试"
    exit 1
fi

exit 0
"#,
                exe_str = exe_str
            ),
            _ => {
                return Err(GitFlowError::Other(format!(
                    "不支持的hook类型: {}",
                    hook_type
                )))
            }
        };

        std::fs::write(&hook_path, hook_content)?;

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = std::fs::metadata(&hook_path)?.permissions();
            perms.set_mode(0o755);
            std::fs::set_permissions(&hook_path, perms)?;
        }

        println!();
        println!("{} {} hook 安装成功!", "✓".green().bold(), hook_type);
        println!("  位置: {}", hook_path.display());
        println!();
        println!("现在每次提交都会自动运行规范检查。");

        Ok(())
    }

    fn print_conventional_commit(&self, conv: &ConventionalCommit) {
        println!("{}", "Conventional Commits 解析结果:".bold());
        println!("  类型:    {}", conv.r#type.cyan());
        if let Some(ref scope) = conv.scope {
            println!("  范围:    {}", scope.yellow());
        }
        if conv.is_breaking {
            println!("  破坏性: {}", "是".red().bold());
        }
        println!("  摘要:    {}", conv.subject);
        if let Some(ref body) = conv.body {
            println!("  描述:    {}", body.lines().next().unwrap_or(""));
        }
        if !conv.footers.is_empty() {
            println!("  备注:");
            for (key, value) in &conv.footers {
                println!("    {}: {}", key, value);
            }
        }
    }

    fn print_commit_template(&self) {
        println!();
        println!("{} 提交消息格式说明:", "📖".blue().bold());
        println!();
        println!(
            "  {}",
            "<type>[optional scope][!]: <description>".cyan()
        );
        println!("  [optional body]");
        println!("  [optional footer(s)]");
        println!();
        println!("{} 支持的类型:", "类型:".bold());
        for t in &self.config.commit.types {
            println!("  {:<10} - {}", t.cyan(), get_type_description(t));
        }
        println!();
        println!("{}", "示例:".bold());
        println!("  {} feat(auth): add login functionality", "✓".green());
        println!("  {} fix(api): correct error handling for user endpoint", "✓".green());
        println!("  {} feat!: change authentication flow", "✓".green());
        println!("  {}", "    BREAKING CHANGE: tokens now use JWT format".dimmed());
    }
}

fn get_type_description(t: &str) -> &'static str {
    match t {
        "feat" => "新功能",
        "fix" => "Bug修复",
        "docs" => "文档更新",
        "style" => "代码风格",
        "refactor" => "代码重构",
        "perf" => "性能优化",
        "test" => "测试相关",
        "chore" => "构建/工具",
        "build" => "构建系统",
        "ci" => "CI配置",
        "revert" => "回滚提交",
        _ => "其他",
    }
}
