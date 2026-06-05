use reqwest::header::{HeaderMap, AUTHORIZATION, CONTENT_TYPE};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use tracing::{debug, info};

use crate::config::JiraConfig;
use crate::errors::{GitFlowError, Result};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JiraIssue {
    pub id: String,
    pub key: String,
    pub fields: IssueFields,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IssueFields {
    pub summary: String,
    pub description: Option<String>,
    pub issuetype: IssueType,
    pub status: Status,
    pub project: Project,
    pub assignee: Option<User>,
    pub creator: Option<User>,
    pub created: String,
    pub updated: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IssueType {
    pub name: String,
    pub id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Status {
    pub name: String,
    pub id: String,
    pub status_category: Option<StatusCategory>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StatusCategory {
    pub name: String,
    pub key: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Project {
    pub key: String,
    pub name: String,
    pub id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct User {
    pub display_name: String,
    pub email_address: Option<String>,
    pub account_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Transition {
    pub id: String,
    pub name: String,
    pub to: Status,
}

#[derive(Debug, Clone)]
pub struct JiraClient {
    client: Client,
    config: JiraConfig,
    base_url: String,
}

impl JiraClient {
    pub fn new(config: JiraConfig) -> Result<Self> {
        if !config.enabled {
            return Err(GitFlowError::JiraError("JIRA集成未启用".into()));
        }

        let base_url = config
            .base_url
            .clone()
            .ok_or_else(|| GitFlowError::JiraError("JIRA base URL未配置".into()))?;

        let client = Client::builder()
            .timeout(std::time::Duration::from_secs(30))
            .build()?;

        Ok(Self {
            client,
            config,
            base_url: base_url.trim_end_matches('/').to_string(),
        })
    }

    fn auth_header(&self) -> Result<HeaderMap> {
        let mut headers = HeaderMap::new();
        headers.insert(CONTENT_TYPE, "application/json".parse().unwrap());

        let token = self
            .config
            .api_token
            .clone()
            .ok_or_else(|| GitFlowError::JiraError("JIRA API token未配置".into()))?;

        let username = self
            .config
            .username
            .clone()
            .ok_or_else(|| GitFlowError::JiraError("JIRA username未配置".into()))?;

        let auth = format!("{}:{}", username, token);
        let auth_b64 = base64::encode(&auth);
        headers.insert(
            AUTHORIZATION,
            format!("Basic {}", auth_b64).parse().unwrap(),
        );

        Ok(headers)
    }

    pub async fn get_issue(&self, issue_key: &str) -> Result<JiraIssue> {
        let url = format!("{}/rest/api/2/issue/{}", self.base_url, issue_key);
        debug!("获取JIRA issue: {}", url);

        let headers = self.auth_header()?;
        let response = self
            .client
            .get(&url)
            .headers(headers)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let error = response.text().await.unwrap_or_default();
            return Err(GitFlowError::JiraError(format!(
                "获取issue失败 ({}): {}",
                status, error
            )));
        }

        let issue: JiraIssue = response.json().await?;
        info!("获取到JIRA issue: {}", issue.key);
        Ok(issue)
    }

    pub async fn get_transitions(&self, issue_key: &str) -> Result<Vec<Transition>> {
        let url = format!(
            "{}/rest/api/2/issue/{}/transitions",
            self.base_url, issue_key
        );
        debug!("获取JIRA transitions: {}", url);

        let headers = self.auth_header()?;
        let response = self
            .client
            .get(&url)
            .headers(headers)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let error = response.text().await.unwrap_or_default();
            return Err(GitFlowError::JiraError(format!(
                "获取transitions失败 ({}): {}",
                status, error
            )));
        }

        #[derive(Deserialize)]
        struct TransitionResponse {
            transitions: Vec<Transition>,
        }

        let resp: TransitionResponse = response.json().await?;
        Ok(resp.transitions)
    }

    pub async fn transition_issue(
        &self,
        issue_key: &str,
        transition_id: &str,
        comment: Option<&str>,
    ) -> Result<()> {
        let url = format!(
            "{}/rest/api/2/issue/{}/transitions",
            self.base_url, issue_key
        );
        debug!("转换JIRA issue状态: {}", url);

        let mut body: HashMap<&str, serde_json::Value> = HashMap::new();
        body.insert("transition", serde_json::json!({ "id": transition_id }));

        if let Some(comment_text) = comment {
            body.insert(
                "update",
                serde_json::json!({
                    "comment": [{
                        "add": {
                            "body": comment_text
                        }
                    }]
                }),
            );
        }

        let headers = self.auth_header()?;
        let response = self
            .client
            .post(&url)
            .headers(headers)
            .json(&body)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let error = response.text().await.unwrap_or_default();
            return Err(GitFlowError::JiraError(format!(
                "转换issue状态失败 ({}): {}",
                status, error
            )));
        }

        info!("JIRA issue {} 状态已转换", issue_key);
        Ok(())
    }

    pub async fn add_comment(&self, issue_key: &str, comment: &str) -> Result<()> {
        let url = format!("{}/rest/api/2/issue/{}/comment", self.base_url, issue_key);
        debug!("添加JIRA评论: {}", url);

        let body = serde_json::json!({ "body": comment });

        let headers = self.auth_header()?;
        let response = self
            .client
            .post(&url)
            .headers(headers)
            .json(&body)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let error = response.text().await.unwrap_or_default();
            return Err(GitFlowError::JiraError(format!(
                "添加评论失败 ({}): {}",
                status, error
            )));
        }

        info!("已添加评论到JIRA issue {}", issue_key);
        Ok(())
    }

    pub async fn search_issues(&self, jql: &str, limit: usize) -> Result<Vec<JiraIssue>> {
        let url = format!("{}/rest/api/2/search", self.base_url);
        debug!("搜索JIRA issues: {}", url);

        let body = serde_json::json!({
            "jql": jql,
            "maxResults": limit,
            "fields": ["id", "key", "summary", "description", "issuetype", "status", "project", "assignee", "creator", "created", "updated"]
        });

        let headers = self.auth_header()?;
        let response = self
            .client
            .post(&url)
            .headers(headers)
            .json(&body)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let error = response.text().await.unwrap_or_default();
            return Err(GitFlowError::JiraError(format!(
                "搜索issues失败 ({}): {}",
                status, error
            )));
        }

        #[derive(Deserialize)]
        struct SearchResponse {
            issues: Vec<JiraIssue>,
            total: usize,
        }

        let resp: SearchResponse = response.json().await?;
        info!("搜索到 {} 个JIRA issues", resp.total);
        Ok(resp.issues)
    }

    pub async fn get_issue_link_url(&self, issue_key: &str) -> String {
        format!("{}/browse/{}", self.base_url, issue_key)
    }

    pub fn generate_branch_name(
        &self,
        issue: &JiraIssue,
        branch_type: &str,
        name_pattern: &str,
    ) -> String {
        let description = slugify(&issue.fields.summary);
        let issue_key = &issue.key;
        let issue_type = issue.fields.issuetype.name.to_lowercase();

        name_pattern
            .replace("{type}", branch_type)
            .replace("{issue}", issue_key)
            .replace("{description}", &description)
            .replace("{issue_type}", &issue_type)
    }

    pub fn is_enabled(&self) -> bool {
        self.config.enabled
    }

    pub fn get_issue_type_mapping(&self) -> HashMap<String, String> {
        let mut mapping = HashMap::new();
        mapping.insert("Bug".to_string(), "bugfix".to_string());
        mapping.insert("Story".to_string(), "feature".to_string());
        mapping.insert("Task".to_string(), "chore".to_string());
        mapping.insert("Sub-task".to_string(), "chore".to_string());
        mapping.insert("Epic".to_string(), "feature".to_string());
        mapping.insert("Hotfix".to_string(), "hotfix".to_string());
        mapping.insert("Improvement".to_string(), "feature".to_string());
        mapping.insert("New Feature".to_string(), "feature".to_string());
        mapping.insert("Feature".to_string(), "feature".to_string());
        mapping
    }

    pub fn map_issue_type(&self, jira_type: &str) -> String {
        let mapping = self.get_issue_type_mapping();
        mapping
            .get(jira_type)
            .cloned()
            .unwrap_or_else(|| "feature".to_string())
    }
}

pub fn slugify(input: &str) -> String {
    let mut result = input.to_lowercase();
    result = result.replace(|c: char| !c.is_alphanumeric() && c != ' ', "");
    result = result.replace(' ', "-");
    result = result.replace("--", "-");
    result.trim_matches('-').to_string()
}

mod base64 {
    pub fn encode(input: &str) -> String {
        const CHARS: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        let mut result = Vec::new();
        let bytes = input.as_bytes();
        let mut i = 0;

        while i < bytes.len() {
            let b1 = bytes[i];
            let b2 = if i + 1 < bytes.len() { bytes[i + 1] } else { 0 };
            let b3 = if i + 2 < bytes.len() { bytes[i + 2] } else { 0 };

            let e1 = (b1 >> 2) as usize;
            let e2 = (((b1 & 0x03) << 4) | ((b2 >> 4) & 0x0f)) as usize;
            let e3 = (((b2 & 0x0f) << 2) | ((b3 >> 6) & 0x03)) as usize;
            let e4 = (b3 & 0x3f) as usize;

            result.push(CHARS[e1]);
            result.push(CHARS[e2]);
            if i + 1 < bytes.len() {
                result.push(CHARS[e3]);
            } else {
                result.push(b'=');
            }
            if i + 2 < bytes.len() {
                result.push(CHARS[e4]);
            } else {
                result.push(b'=');
            }

            i += 3;
        }

        String::from_utf8(result).unwrap_or_default()
    }
}
