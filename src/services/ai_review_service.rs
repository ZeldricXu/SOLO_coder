use serde::Deserialize;
use serde_json::Value;
use uuid::Uuid;

use crate::models::ai_review::{
    AiReview, AiSuggestion, AiReviewWithSuggestions, AiSuggestionWithDetails,
    TriggerAiScanRequest, ActOnSuggestionRequest,
    AiReviewStatus, AiSuggestionStatus, AiScanCategory,
    LlmMessage,
};
use crate::models::ai_rule::AiRule;
use crate::models::issue::{CreateIssueRequest, IssueSeverity};
use crate::repositories::{AiReviewRepository, AiRuleRepository, IssueRepository};
use crate::repositories::ai_review_repo::AiSuggestionStatistics;
use crate::utils::{AppError, AppResult, DiffFile, DiffParser};

use super::diff_service::DiffService;
use crate::providers::LlmClient;

#[derive(Clone)]
pub struct AiReviewService {
    ai_review_repo: AiReviewRepository,
    llm_client: LlmClient,
    diff_service: DiffService,
    issue_repo: IssueRepository,
    ai_rule_repo: Option<AiRuleRepository>,
    diff_parser: DiffParser,
}

impl AiReviewService {
    pub fn new(
        ai_review_repo: AiReviewRepository,
        llm_client: LlmClient,
        diff_service: DiffService,
        issue_repo: IssueRepository,
    ) -> Self {
        Self {
            ai_review_repo,
            llm_client,
            diff_service,
            issue_repo,
            ai_rule_repo: None,
            diff_parser: DiffParser::new(),
        }
    }

    pub fn with_extensions(
        self,
        ai_rule_repo: AiRuleRepository,
    ) -> Self {
        Self {
            ai_rule_repo: Some(ai_rule_repo),
            ..self
        }
    }

    pub async fn trigger_scan(
        &self,
        merge_request_id: Uuid,
        _req: &TriggerAiScanRequest,
    ) -> AppResult<Uuid> {
        let review = self.ai_review_repo.create_review(merge_request_id).await?;

        let service_clone = self.clone();
        let review_id = review.id;

        tokio::spawn(async move {
            let _ = service_clone.run_scan(review_id, merge_request_id).await;
        });

        Ok(review_id)
    }

    pub async fn run_scan(
        &self,
        review_id: Uuid,
        _merge_request_id: Uuid,
    ) -> AppResult<()> {
        self.ai_review_repo
            .update_review_status(review_id, AiReviewStatus::Running.as_str())
            .await?;

        Ok(())
    }

    pub async fn get_review(&self, id: Uuid) -> AppResult<AiReviewWithSuggestions> {
        self.ai_review_repo
            .get_review_with_suggestions(id)
            .await?
            .ok_or_else(|| AppError::NotFound(format!("AI review {} not found", id)))
    }

    pub async fn get_latest_review(
        &self,
        merge_request_id: Uuid,
    ) -> AppResult<Option<AiReview>> {
        self.ai_review_repo.get_latest_review(merge_request_id).await
    }

    pub async fn scan_diff_file(
        &self,
        ai_review_id: Uuid,
        diff_file: &DiffFile,
        scan_categories: &[AiScanCategory],
    ) -> AppResult<Vec<AiSuggestion>> {
        let file_content = self.extract_file_content(diff_file);

        let prompt = self.generate_prompt(&diff_file.new_path, &file_content, scan_categories);

        let messages = vec![LlmMessage {
            role: "user".to_string(),
            content: prompt,
        }];

        let response = self.llm_client.chat(messages).await?;

        let suggestions = self.parse_suggestions(ai_review_id, &response)?;

        for suggestion in &suggestions {
            self.ai_review_repo
                .add_suggestion(
                    ai_review_id,
                    &suggestion.file_path,
                    suggestion.line_no,
                    &suggestion.category,
                    &suggestion.severity,
                    &suggestion.title,
                    &suggestion.description,
                    &suggestion.suggestion,
                )
                .await?;
        }

        Ok(suggestions)
    }

    pub fn generate_prompt(
        &self,
        file_path: &str,
        file_content: &str,
        scan_categories: &[AiScanCategory],
    ) -> String {
        let categories_str = scan_categories
            .iter()
            .map(|c| format!("- {}: {}", c.as_str(), self.get_category_description(c)))
            .collect::<Vec<_>>()
            .join("\n");

        format!(
            r#"你是一个专业的代码评审专家。请分析以下代码文件，并提供详细的改进建议。

文件路径: {file_path}

扫描类别:
{categories_str}

代码内容:
```
{file_content}
```

请按照以下JSON格式返回建议列表（不要包含其他文本，只返回JSON）:
{{
    "suggestions": [
        {{
            "file_path": "文件路径",
            "line_no": 行号,
            "category": "类别（code_style/bug_pattern/security/performance/best_practice/maintainability）",
            "severity": "严重程度（info/minor/major/critical）",
            "title": "问题标题",
            "description": "问题详细描述",
            "suggestion": "改进建议"
        }}
    ]
}}"#
        )
    }

    pub fn parse_suggestions(
        &self,
        ai_review_id: Uuid,
        response: &str,
    ) -> AppResult<Vec<AiSuggestion>> {
        let cleaned_response = response
            .trim()
            .trim_start_matches("```json")
            .trim_end_matches("```")
            .trim();

        let parsed: Value = serde_json::from_str(cleaned_response).map_err(|e| {
            AppError::Parse(format!(
                "Failed to parse AI response as JSON: {}. Response: {}",
                e, response
            ))
        })?;

        let suggestions = parsed
            .get("suggestions")
            .and_then(|s| s.as_array())
            .ok_or_else(|| AppError::Parse("AI response missing 'suggestions' array".to_string()))?;

        let mut result = Vec::new();
        let now = chrono::Utc::now();

        for suggestion in suggestions {
            let parsed_suggestion: ParsedAiSuggestion =
                serde_json::from_value(suggestion.clone()).map_err(|e| {
                    AppError::Parse(format!(
                        "Failed to parse suggestion: {}. Suggestion: {}",
                        e, suggestion
                    ))
                })?;

            result.push(AiSuggestion {
                id: Uuid::new_v4(),
                ai_review_id,
                file_path: parsed_suggestion.file_path,
                line_no: parsed_suggestion.line_no,
                category: parsed_suggestion.category,
                severity: parsed_suggestion.severity,
                title: parsed_suggestion.title,
                description: parsed_suggestion.description,
                suggestion: parsed_suggestion.suggestion,
                status: AiSuggestionStatus::Pending.as_str().to_string(),
                acted_by: None,
                acted_at: None,
                created_at: now,
            });
        }

        Ok(result)
    }

    pub async fn act_on_suggestion(
        &self,
        suggestion_id: Uuid,
        user_id: Uuid,
        req: &ActOnSuggestionRequest,
    ) -> AppResult<AiSuggestion> {
        let action = req.action.to_lowercase();
        let status = match action.as_str() {
            "accept" | "accepted" => AiSuggestionStatus::Accepted,
            "ignore" | "ignored" => AiSuggestionStatus::Ignored,
            _ => {
                return Err(AppError::Validation(format!(
                    "Invalid action: {}. Must be 'accept' or 'ignore'",
                    req.action
                )));
            }
        };

        self.ai_review_repo
            .update_suggestion_status(suggestion_id, status.as_str(), user_id)
            .await
    }

    pub async fn auto_create_issues(
        &self,
        ai_review_id: Uuid,
        reporter_id: Uuid,
        min_severity: Option<&str>,
    ) -> AppResult<Vec<Uuid>> {
        let review = self.get_review(ai_review_id).await?;

        let mut created_issue_ids = Vec::new();
        let min_severity = min_severity.unwrap_or("major");

        for suggestion in &review.suggestions {
            if !self.is_severity_gte(&suggestion.severity, min_severity) {
                continue;
            }

            if suggestion.status != AiSuggestionStatus::Pending.as_str() {
                continue;
            }

            let issue_severity = self.map_to_issue_severity(&suggestion.severity);

            let req = CreateIssueRequest {
                merge_request_id: Some(review.merge_request_id),
                file_path: Some(suggestion.file_path.clone()),
                line_no: Some(suggestion.line_no),
                title: suggestion.title.clone(),
                description: format!(
                    "{}\n\n**AI建议:** {}",
                    suggestion.description, suggestion.suggestion
                ),
                severity: issue_severity.as_str().to_string(),
                assignee_id: None,
                code_snippet: None,
            };

            let issue = self
                .issue_repo
                .create(
                    req.merge_request_id,
                    req.file_path.as_deref(),
                    req.line_no,
                    &req.title,
                    &req.description,
                    &req.severity,
                    reporter_id,
                    req.assignee_id,
                    req.code_snippet.as_deref(),
                )
                .await?;

            created_issue_ids.push(issue.id);

            self.ai_review_repo
                .update_suggestion_status(
                    suggestion.id,
                    AiSuggestionStatus::Accepted.as_str(),
                    reporter_id,
                )
                .await?;
        }

        Ok(created_issue_ids)
    }

    pub async fn get_suggestion_statistics(
        &self,
        ai_review_id: Uuid,
    ) -> AppResult<AiSuggestionStatistics> {
        self.ai_review_repo
            .get_suggestion_statistics(ai_review_id)
            .await
    }

    pub async fn scan_single_file(
        &self,
        file_path: &str,
        file_content: &str,
        scan_categories: Option<Vec<String>>,
    ) -> AppResult<Vec<AiSuggestion>> {
        let categories = self.parse_scan_categories(scan_categories);

        let temp_review_id = Uuid::new_v4();

        let prompt = self.generate_prompt(file_path, file_content, &categories);

        let messages = vec![LlmMessage {
            role: "user".to_string(),
            content: prompt,
        }];

        let response = self.llm_client.chat(messages).await?;

        self.parse_suggestions(temp_review_id, &response)
    }

    fn get_category_description(&self, category: &AiScanCategory) -> &str {
        match category {
            AiScanCategory::CodeStyle => "检查代码风格一致性、命名规范、格式规范等",
            AiScanCategory::BugPattern => "识别潜在的bug、空指针异常、边界条件错误等",
            AiScanCategory::Security => "发现安全漏洞、注入风险、敏感信息泄露等",
            AiScanCategory::Performance => "分析性能问题、低效算法、资源泄漏等",
            AiScanCategory::BestPractice => "检查是否遵循最佳实践、设计模式、代码可维护性",
            AiScanCategory::Maintainability => "评估代码可维护性、复杂度、重复代码等",
        }
    }

    fn extract_file_content(&self, diff_file: &DiffFile) -> String {
        let mut content = String::new();
        for hunk in &diff_file.hunks {
            for line in &hunk.lines {
                if line.line_type != "old" {
                    content.push_str(&line.content);
                    content.push('\n');
                }
            }
        }
        content
    }

    fn parse_scan_categories(
        &self,
        categories: Option<Vec<String>>,
    ) -> Vec<AiScanCategory> {
        match categories {
            Some(cats) => cats
                .iter()
                .filter_map(|c| AiScanCategory::all().into_iter().find(|ac| ac.as_str() == c))
                .collect(),
            None => AiScanCategory::all(),
        }
    }

    fn is_severity_gte(&self, severity: &str, min_severity: &str) -> bool {
        let severity_order = ["info", "minor", "major", "critical"];
        let sev_idx = severity_order.iter().position(|s| s == severity);
        let min_idx = severity_order.iter().position(|s| s == min_severity);

        match (sev_idx, min_idx) {
            (Some(s), Some(m)) => s >= m,
            _ => false,
        }
    }

    fn map_to_issue_severity(&self, ai_severity: &str) -> IssueSeverity {
        match ai_severity {
            "critical" => IssueSeverity::Critical,
            "major" => IssueSeverity::Major,
            "minor" => IssueSeverity::Minor,
            _ => IssueSeverity::Info,
        }
    }

    pub async fn scan_diff_with_rules(
        &self,
        ai_review_id: Uuid,
        repo_id: Uuid,
        organization_id: Uuid,
        diff_files: &[DiffFile],
    ) -> AppResult<Vec<AiSuggestion>> {
        let ai_rule_repo = self.ai_rule_repo.as_ref().ok_or_else(|| {
            AppError::Configuration("AiRuleRepository not configured".to_string())
        })?;

        let rules = ai_rule_repo
            .get_active_rules(organization_id, repo_id)
            .await?;

        let mut all_suggestions = Vec::new();

        for diff_file in diff_files {
            if !self.should_scan_file(diff_file, &rules) {
                continue;
            }

            let file_suggestions = self
                .analyze_diff_file(ai_review_id, diff_file, &rules)
                .await?;

            all_suggestions.extend(file_suggestions);
        }

        Ok(all_suggestions)
    }

    pub async fn analyze_diff_file(
        &self,
        ai_review_id: Uuid,
        diff_file: &DiffFile,
        rules: &[AiRule],
    ) -> AppResult<Vec<AiSuggestion>> {
        let context_lines = rules
            .iter()
            .filter_map(|r| r.context_lines)
            .max()
            .unwrap_or(3);

        let prompt = self.build_diff_aware_prompt(diff_file, rules, context_lines);

        let messages = vec![LlmMessage {
            role: "user".to_string(),
            content: prompt,
        }];

        let response = self.llm_client.chat(messages).await?;

        let suggestions = self.parse_suggestions(ai_review_id, &response)?;

        for suggestion in &suggestions {
            self.ai_review_repo
                .add_suggestion(
                    ai_review_id,
                    &suggestion.file_path,
                    suggestion.line_no,
                    &suggestion.category,
                    &suggestion.severity,
                    &suggestion.title,
                    &suggestion.description,
                    &suggestion.suggestion,
                )
                .await?;
        }

        Ok(suggestions)
    }

    pub fn build_diff_aware_prompt(
        &self,
        diff_file: &DiffFile,
        rules: &[AiRule],
        context_lines: i32,
    ) -> String {
        let (added, deleted) = self.count_changed_lines(diff_file);

        let mut hunks_str = String::new();
        for hunk in &diff_file.hunks {
            hunks_str.push_str(&format!(
                "@@ -{},{} +{},{} @@{}\n",
                hunk.old_start, hunk.old_lines, hunk.new_start, hunk.new_lines, hunk.header
            ));

            for line in &hunk.lines {
                let prefix = match line.line_type.as_str() {
                    "new" => "+",
                    "old" => "-",
                    _ => " ",
                };
                hunks_str.push_str(&format!("{}{}\n", prefix, line.content));
            }
        }

        let custom_rules_str: String = rules
            .iter()
            .filter(|r| r.is_active && !r.custom_prompt.is_empty())
            .map(|r| format!("- [{}] {}", r.name, r.custom_prompt))
            .collect::<Vec<_>>()
            .join("\n");

        format!(
            r#"你是一个专业的代码评审专家。请重点分析以下diff变更内容，并提供针对性的改进建议。

文件路径: {file_path}
变更类型: {change_type}
新增: {added}行 删除: {deleted}行

变更区域及上下文:
---
{hunks_str}
---

自定义规则:
{custom_rules_str}

请重点关注变更的行及其上下文。基于变更语义分析：
- 新增的函数是否缺少参数校验
- 修改的逻辑是否引入死代码或边界条件遗漏
- 删除后是否有残留的未清理引用

请按照以下JSON格式返回建议列表（不要包含其他文本，只返回JSON）:
{{
    "suggestions": [
        {{
            "file_path": "文件路径",
            "line_no": 行号,
            "category": "类别（code_style/bug_pattern/security/performance/best_practice/maintainability）",
            "severity": "严重程度（info/minor/major/critical）",
            "title": "问题标题",
            "description": "问题详细描述",
            "suggestion": "改进建议"
        }}
    ]
}}"#,
            file_path = diff_file.new_path,
            change_type = diff_file.status,
            added = added,
            deleted = deleted,
            hunks_str = hunks_str,
            custom_rules_str = custom_rules_str,
        )
    }

    pub fn count_changed_lines(&self, diff_file: &DiffFile) -> (i32, i32) {
        let mut added = 0;
        let mut deleted = 0;

        for hunk in &diff_file.hunks {
            for line in &hunk.lines {
                match line.line_type.as_str() {
                    "new" => added += 1,
                    "old" => deleted += 1,
                    _ => {}
                }
            }
        }

        (added, deleted)
    }

    pub fn should_scan_file(&self, diff_file: &DiffFile, rules: &[AiRule]) -> bool {
        if diff_file.binary {
            return false;
        }

        let (added, deleted) = self.count_changed_lines(diff_file);
        let total_changed = added + deleted;

        if rules.is_empty() {
            return total_changed > 0;
        }

        for rule in rules {
            if !rule.is_active {
                continue;
            }
            match rule.min_changed_lines {
                Some(min) if total_changed < min => continue,
                _ => return true,
            }
        }

        false
    }
}

#[derive(Debug, Deserialize)]
struct ParsedAiSuggestion {
    file_path: String,
    line_no: i32,
    category: String,
    severity: String,
    title: String,
    description: String,
    suggestion: String,
}
