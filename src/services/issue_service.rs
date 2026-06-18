use chrono::{DateTime, Utc};
use uuid::Uuid;

use crate::models::issue::{
    Issue, IssueWithDetails, CreateIssueRequest, UpdateIssueRequest,
    UpdateIssueStatusRequest, AssignIssueRequest, IssueQuery,
    IssueSeverity, IssueStatus,
};
use crate::models::stats::{IssueBySeverity, IssueByStatus, PersonalStats};
use crate::repositories::{IssueRepository, NotificationRepository, StatsRepository};
use crate::utils::{AppError, AppResult, PaginatedResult};

use super::comment_service::NotificationService;
use super::permission_service::PermissionService;

#[derive(Clone)]
pub struct IssueService {
    issue_repo: IssueRepository,
    notification_service: NotificationRepository,
    permission_service: PermissionService,
    stats_repo: StatsRepository,
}

impl IssueService {
    pub fn new(
        issue_repo: IssueRepository,
        notification_service: NotificationRepository,
        permission_service: PermissionService,
        stats_repo: StatsRepository,
    ) -> Self {
        Self {
            issue_repo,
            notification_service,
            permission_service,
            stats_repo,
        }
    }

    pub async fn create_issue(
        &self,
        reporter_id: Uuid,
        organization_id: Uuid,
        req: &CreateIssueRequest,
    ) -> AppResult<Issue> {
        if !self
            .permission_service
            .can_create_issue(reporter_id, organization_id)
            .await?
        {
            return Err(AppError::Authorization(
                "You don't have permission to create issues".to_string(),
            ));
        }

        IssueSeverity::from_str(&req.severity).ok_or_else(|| {
            AppError::Validation(format!("Invalid severity: {}", req.severity))
        })?;

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

        if let Some(assignee_id) = req.assignee_id {
            let title = "新问题分配";
            let content = format!("你被分配了一个新问题: {}", req.title);
            let related_url = Some(format!("/issues/{}", issue.id));
            let _ = self
                .notification_service
                .send_notification(
                    assignee_id,
                    "issue_assigned",
                    title,
                    &content,
                    related_url.as_deref(),
                )
                .await;
        }

        Ok(issue)
    }

    pub async fn get_issue(&self, id: Uuid) -> AppResult<IssueWithDetails> {
        self.issue_repo
            .get_by_id(id)
            .await?
            .ok_or_else(|| AppError::NotFound(format!("Issue {} not found", id)))
    }

    pub async fn list_issues(
        &self,
        query: IssueQuery,
    ) -> AppResult<PaginatedResult<IssueWithDetails>> {
        let page = query.page.unwrap_or(1);
        let per_page = query.per_page.unwrap_or(20);

        if let Some(severity) = &query.severity {
            if IssueSeverity::from_str(severity).is_none() {
                return Err(AppError::Validation(format!(
                    "Invalid severity: {}",
                    severity
                )));
            }
        }

        if let Some(status) = &query.status {
            if IssueStatus::from_str(status).is_none() {
                return Err(AppError::Validation(format!(
                    "Invalid status: {}",
                    status
                )));
            }
        }

        let (issues, total) = self
            .issue_repo
            .list_with_details(
                query.merge_request_id,
                query.repo_id,
                query.severity.as_deref(),
                query.status.as_deref(),
                query.reporter_id,
                query.assignee_id,
                page,
                per_page,
            )
            .await?;

        Ok(PaginatedResult::new(issues, page, per_page, total))
    }

    pub async fn update_issue(
        &self,
        id: Uuid,
        user_id: Uuid,
        organization_id: Uuid,
        req: &UpdateIssueRequest,
    ) -> AppResult<Issue> {
        let issue = self.get_issue_simple(id).await?;

        if !self
            .permission_service
            .can_edit_issue(user_id, &issue, organization_id)
            .await?
        {
            return Err(AppError::Authorization(
                "You don't have permission to edit this issue".to_string(),
            ));
        }

        if let Some(severity) = &req.severity {
            IssueSeverity::from_str(severity).ok_or_else(|| {
                AppError::Validation(format!("Invalid severity: {}", severity))
            })?;
        }

        let updated = self
            .issue_repo
            .update(
                id,
                req.title.as_deref(),
                req.description.as_deref(),
                req.severity.as_deref(),
                req.assignee_id,
                req.code_snippet.as_deref(),
            )
            .await?;

        if let Some(assignee_id) = req.assignee_id {
            if issue.assignee_id != Some(assignee_id) {
                let title = "问题重新分配";
                let content = format!("问题已重新分配给你: {}", updated.title);
                let related_url = Some(format!("/issues/{}", id));
                let _ = self
                    .notification_service
                    .send_notification(
                        assignee_id,
                        "issue_assigned",
                        title,
                        &content,
                        related_url.as_deref(),
                    )
                    .await;
            }
        }

        Ok(updated)
    }

    pub async fn update_status(
        &self,
        id: Uuid,
        user_id: Uuid,
        organization_id: Uuid,
        req: &UpdateIssueStatusRequest,
    ) -> AppResult<Issue> {
        let issue = self.get_issue_simple(id).await?;

        if !self
            .permission_service
            .can_edit_issue(user_id, &issue, organization_id)
            .await?
        {
            return Err(AppError::Authorization(
                "You don't have permission to update this issue status".to_string(),
            ));
        }

        let current_status = IssueStatus::from_str(&issue.status)
            .ok_or_else(|| AppError::Validation(format!("Invalid current status: {}", issue.status)))?;

        let new_status = IssueStatus::from_str(&req.status)
            .ok_or_else(|| AppError::Validation(format!("Invalid status: {}", req.status)))?;

        self.validate_status_transition(&current_status, &new_status)?;

        let updated = self.issue_repo.update_status(id, &req.status).await?;

        if let Some(assignee_id) = issue.assignee_id {
            let title = "问题状态更新";
            let content = format!(
                "问题 \"{}\" 状态已从 {} 变更为 {}",
                issue.title, issue.status, req.status
            );
            let related_url = Some(format!("/issues/{}", id));
            let _ = self
                .notification_service
                .send_notification(
                    assignee_id,
                    "issue_status_changed",
                    title,
                    &content,
                    related_url.as_deref(),
                )
                .await;
        }

        Ok(updated)
    }

    pub async fn assign_issue(
        &self,
        id: Uuid,
        user_id: Uuid,
        organization_id: Uuid,
        req: &AssignIssueRequest,
    ) -> AppResult<Issue> {
        let issue = self.get_issue_simple(id).await?;

        if !self
            .permission_service
            .can_edit_issue(user_id, &issue, organization_id)
            .await?
        {
            return Err(AppError::Authorization(
                "You don't have permission to assign this issue".to_string(),
            ));
        }

        let updated = self.issue_repo.assign(id, req.assignee_id).await?;

        let title = "问题分配";
        let content = format!("你被分配了一个问题: {}", issue.title);
        let related_url = Some(format!("/issues/{}", id));
        let _ = self
            .notification_service
            .send_notification(
                req.assignee_id,
                "issue_assigned",
                title,
                &content,
                related_url.as_deref(),
            )
            .await;

        Ok(updated)
    }

    pub async fn list_by_reporter(
        &self,
        reporter_id: Uuid,
        page: i32,
        per_page: i32,
    ) -> AppResult<PaginatedResult<IssueWithDetails>> {
        let (issues, total) = self
            .issue_repo
            .list_by_reporter(reporter_id, page, per_page)
            .await?;

        Ok(PaginatedResult::new(issues, page, per_page, total))
    }

    pub async fn list_by_assignee(
        &self,
        assignee_id: Uuid,
        page: i32,
        per_page: i32,
    ) -> AppResult<PaginatedResult<IssueWithDetails>> {
        let (issues, total) = self
            .issue_repo
            .list_by_assignee(assignee_id, page, per_page)
            .await?;

        Ok(PaginatedResult::new(issues, page, per_page, total))
    }

    pub async fn get_issues_by_file(
        &self,
        file_path: &str,
        repo_id: Option<Uuid>,
        page: i32,
        per_page: i32,
    ) -> AppResult<PaginatedResult<IssueWithDetails>> {
        let (issues, total) = self
            .issue_repo
            .get_issues_by_file(file_path, repo_id, page, per_page)
            .await?;

        Ok(PaginatedResult::new(issues, page, per_page, total))
    }

    pub async fn get_issue_statistics(
        &self,
        repo_id: Option<Uuid>,
        merge_request_id: Option<Uuid>,
    ) -> AppResult<(Vec<IssueBySeverity>, Vec<IssueByStatus>)> {
        self.issue_repo
            .get_issue_statistics(repo_id, merge_request_id)
            .await
    }

    pub async fn get_personal_stats(
        &self,
        user_id: Uuid,
        start_date: Option<DateTime<Utc>>,
        end_date: Option<DateTime<Utc>>,
    ) -> AppResult<PersonalStats> {
        self.stats_repo
            .get_personal_stats(user_id, start_date, end_date)
            .await
    }

    pub fn validate_status_transition(
        &self,
        current: &IssueStatus,
        next: &IssueStatus,
    ) -> AppResult<()> {
        current
            .transition_to(*next)
            .map_err(|e| AppError::Validation(e.to_string()))?;
        Ok(())
    }

    async fn get_issue_simple(&self, id: Uuid) -> AppResult<Issue> {
        let details = self.get_issue(id).await?;
        Ok(Issue {
            id: details.id,
            merge_request_id: details.merge_request_id,
            file_path: details.file_path,
            line_no: details.line_no,
            title: details.title,
            description: details.description,
            severity: details.severity,
            status: details.status,
            reporter_id: details.reporter_id,
            assignee_id: details.assignee_id,
            code_snippet: details.code_snippet,
            created_at: details.created_at,
            updated_at: details.updated_at,
        })
    }
}
