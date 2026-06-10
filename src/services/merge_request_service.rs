use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::models::{
    Comment, CommentWithDetails, CreateCommentRequest, CreateMergeRequestRequest,
    MergeRequest, MergeRequestQuery, MergeRequestStatus, MergeRequestWithDetails,
    ReviewerAssignment,
};
use crate::providers::{MinioClient, ProviderMergeRequest};
use crate::repositories::{
    CommentRepository, MergeRequestRepository, RepoRepository, UserRepository,
};
use crate::utils::{AppError, AppResult, DiffFile, PaginatedResult};

use super::diff_service::DiffService;

#[derive(Clone)]
pub struct MergeRequestService {
    mr_repo: MergeRequestRepository,
    repo_repo: RepoRepository,
    user_repo: UserRepository,
    comment_repo: CommentRepository,
    minio_client: MinioClient,
    diff_service: DiffService,
}

impl MergeRequestService {
    pub fn new(
        mr_repo: MergeRequestRepository,
        repo_repo: RepoRepository,
        user_repo: UserRepository,
        comment_repo: CommentRepository,
        minio_client: MinioClient,
        diff_service: DiffService,
    ) -> Self {
        Self {
            mr_repo,
            repo_repo,
            user_repo,
            comment_repo,
            minio_client,
            diff_service,
        }
    }

    pub async fn list_mrs(
        &self,
        query: MergeRequestQuery,
    ) -> AppResult<PaginatedResult<MergeRequestWithDetails>> {
        let page = query.page.unwrap_or(1);
        let per_page = query.per_page.unwrap_or(20);

        let (mrs, total) = self
            .mr_repo
            .list_with_details(
                query.repo_id,
                query.status.as_deref(),
                query.author_id,
                page,
                per_page,
            )
            .await?;

        Ok(PaginatedResult::new(mrs, page, per_page, total))
    }

    pub async fn get_mr(&self, id: Uuid) -> AppResult<MergeRequestWithDetails> {
        let mrs = self
            .mr_repo
            .list_with_details(Some(id), None, None, 1, 1)
            .await?;

        mrs.0
            .into_iter()
            .next()
            .ok_or_else(|| AppError::NotFound(format!("Merge request not found: {}", id)))
    }

    pub async fn get_mr_simple(&self, id: Uuid) -> AppResult<MergeRequest> {
        self.mr_repo
            .get_by_id(id)
            .await?
            .ok_or_else(|| AppError::NotFound(format!("Merge request not found: {}", id)))
    }

    pub async fn create_mr(
        &self,
        repo_id: Uuid,
        req: CreateMergeRequestRequest,
    ) -> AppResult<MergeRequest> {
        let author = self
            .user_repo
            .get_by_provider_id(&req.provider, &req.author_provider_id)
            .await?
            .ok_or_else(|| {
                AppError::NotFound(format!(
                    "User not found for provider_id: {}",
                    req.author_provider_id
                ))
            })?;

        let status = MergeRequestStatus::Open.as_str();

        let mr = self
            .mr_repo
            .create(
                repo_id,
                &req.provider,
                &req.provider_id,
                &req.title,
                req.description.as_deref(),
                &req.source_branch,
                &req.target_branch,
                author.id,
                status,
            )
            .await?;

        Ok(mr)
    }

    pub async fn create_mr_from_provider(
        &self,
        repo_id: Uuid,
        provider_mr: &ProviderMergeRequest,
    ) -> AppResult<MergeRequest> {
        let author = self
            .user_repo
            .get_by_provider_id("github", &provider_mr.author.id)
            .await?;

        let author_id = match author {
            Some(user) => user.id,
            None => {
                let user = self
                    .user_repo
                    .create(
                        &provider_mr.author.username,
                        &provider_mr
                            .author
                            .email
                            .clone()
                            .unwrap_or_else(|| format!("{}@noreply.com", provider_mr.author.username)),
                        provider_mr.author.avatar_url.as_deref(),
                    )
                    .await?;
                user.id
            }
        };

        let status = match provider_mr.state.as_str() {
            "open" | "opened" => MergeRequestStatus::Open.as_str(),
            "merged" => MergeRequestStatus::Merged.as_str(),
            "closed" => MergeRequestStatus::Closed.as_str(),
            _ => MergeRequestStatus::Open.as_str(),
        };

        let mr = self
            .mr_repo
            .create(
                repo_id,
                "github",
                &provider_mr.id,
                &provider_mr.title,
                provider_mr.description.as_deref(),
                &provider_mr.source_branch,
                &provider_mr.target_branch,
                author_id,
                status,
            )
            .await?;

        Ok(mr)
    }

    pub async fn update_mr_status(
        &self,
        id: Uuid,
        status: MergeRequestStatus,
    ) -> AppResult<MergeRequest> {
        let status_str = status.as_str();
        let mr = self.mr_repo.update_status(id, status_str).await?;
        Ok(mr)
    }

    pub async fn get_mr_diff(&self, mr_id: Uuid) -> AppResult<Vec<DiffFile>> {
        let mr = self.get_mr_simple(mr_id).await?;
        let repo = self
            .repo_repo
            .get_by_id(mr.repo_id)
            .await?
            .ok_or_else(|| AppError::NotFound("Repository not found".to_string()))?;

        let mr_number: i64 = mr
            .provider_id
            .parse()
            .map_err(|_| AppError::Parse("Invalid MR number".to_string()))?;

        let diff = self.diff_service.get_mr_diff(&repo, mr_number, mr_id).await?;

        Ok(diff)
    }

    pub async fn assign_reviewer(
        &self,
        mr_id: Uuid,
        user_id: Uuid,
    ) -> AppResult<ReviewerAssignment> {
        let _mr = self.get_mr_simple(mr_id).await?;
        let _user = self
            .user_repo
            .get_by_id(user_id)
            .await?
            .ok_or_else(|| AppError::NotFound("User not found".to_string()))?;

        let assignment = self.mr_repo.assign_reviewer(mr_id, user_id).await?;
        Ok(assignment)
    }

    pub async fn list_reviewers(&self, mr_id: Uuid) -> AppResult<Vec<ReviewerAssignment>> {
        let reviewers = self.mr_repo.list_reviewers(mr_id).await?;
        Ok(reviewers)
    }

    pub async fn get_pending_reviews(
        &self,
        user_id: Uuid,
        page: i32,
        per_page: i32,
    ) -> AppResult<PaginatedResult<MergeRequestWithDetails>> {
        let page = if page < 1 { 1 } else { page };
        let per_page = if per_page < 1 || per_page > 100 { 20 } else { per_page };

        let (mrs, total) = self
            .mr_repo
            .get_pending_reviews(user_id, page, per_page)
            .await?;

        Ok(PaginatedResult::new(mrs, page, per_page, total))
    }

    pub async fn get_my_mrs(
        &self,
        user_id: Uuid,
        status: Option<&str>,
        page: i32,
        per_page: i32,
    ) -> AppResult<PaginatedResult<MergeRequestWithDetails>> {
        let page = if page < 1 { 1 } else { page };
        let per_page = if per_page < 1 || per_page > 100 { 20 } else { per_page };

        let (mrs, total) = self
            .mr_repo
            .get_my_mrs(user_id, status, page, per_page)
            .await?;

        Ok(PaginatedResult::new(mrs, page, per_page, total))
    }

    pub async fn trigger_ai_review(&self, mr_id: Uuid) -> AppResult<AiReviewTriggerResult> {
        let _mr = self.get_mr_simple(mr_id).await?;
        let _diff = self.get_mr_diff(mr_id).await?;

        Ok(AiReviewTriggerResult {
            mr_id,
            status: "queued".to_string(),
            message: "AI review has been queued".to_string(),
        })
    }

    pub async fn get_mr_comments(&self, mr_id: Uuid) -> AppResult<Vec<CommentWithDetails>> {
        let _mr = self.get_mr_simple(mr_id).await?;
        let comments = self.comment_repo.list_by_mr(mr_id).await?;
        Ok(comments)
    }

    pub async fn get_mr_file_comments(
        &self,
        mr_id: Uuid,
        file_path: &str,
    ) -> AppResult<Vec<CommentWithDetails>> {
        let _mr = self.get_mr_simple(mr_id).await?;
        let comments = self.comment_repo.list_by_file(mr_id, file_path).await?;
        Ok(comments)
    }

    pub async fn add_comment(
        &self,
        mr_id: Uuid,
        author_id: Uuid,
        req: CreateCommentRequest,
    ) -> AppResult<Comment> {
        let _mr = self.get_mr_simple(mr_id).await?;
        let _user = self
            .user_repo
            .get_by_id(author_id)
            .await?
            .ok_or_else(|| AppError::NotFound("User not found".to_string()))?;

        let comment = self
            .comment_repo
            .create(
                mr_id,
                author_id,
                req.file_path.as_deref(),
                req.line_no,
                req.line_type.as_deref(),
                &req.content,
                req.parent_id,
            )
            .await?;

        Ok(comment)
    }

    pub async fn export_mr(&self, mr_id: Uuid, format: &str) -> AppResult<MrExportResult> {
        let mr = self.get_mr(mr_id).await?;
        let comments = self.get_mr_comments(mr_id).await?;
        let diff = self.get_mr_diff(mr_id).await?;

        let export_content = match format.to_lowercase().as_str() {
            "json" => self.export_to_json(&mr, &comments, &diff)?,
            "csv" => self.export_to_csv(&mr, &comments, &diff)?,
            "markdown" | "md" => self.export_to_markdown(&mr, &comments, &diff)?,
            _ => {
                return Err(AppError::Validation(format!(
                    "Unsupported export format: {}",
                    format
                )))
            }
        };

        let storage_key = self
            .minio_client
            .export_report(mr_id, export_content.as_bytes(), format)
            .await?;

        let download_url = self
            .minio_client
            .presign_get(&storage_key, std::time::Duration::from_secs(3600))
            .await?;

        Ok(MrExportResult {
            mr_id,
            format: format.to_string(),
            storage_key,
            download_url,
        })
    }

    fn export_to_json(
        &self,
        mr: &MergeRequestWithDetails,
        comments: &[CommentWithDetails],
        diff: &[DiffFile],
    ) -> AppResult<String> {
        let export_data = serde_json::json!({
            "merge_request": {
                "id": mr.id,
                "title": mr.title,
                "description": mr.description,
                "repo_name": mr.repo_name,
                "source_branch": mr.source_branch,
                "target_branch": mr.target_branch,
                "author_name": mr.author_name,
                "status": mr.status,
                "comment_count": mr.comment_count,
                "unresolved_comment_count": mr.unresolved_comment_count,
                "issue_count": mr.issue_count,
                "created_at": mr.created_at,
                "updated_at": mr.updated_at,
            },
            "comments": comments,
            "diff": diff,
        });

        let json = serde_json::to_string_pretty(&export_data)?;
        Ok(json)
    }

    fn export_to_csv(
        &self,
        mr: &MergeRequestWithDetails,
        comments: &[CommentWithDetails],
        _diff: &[DiffFile],
    ) -> AppResult<String> {
        let mut csv = String::new();
        
        csv.push_str("Merge Request Details\n");
        csv.push_str(&format!("ID,{}\n", mr.id));
        csv.push_str(&format!("Title,{}\n", mr.title.replace(',', "\\,")));
        csv.push_str(&format!("Repository,{}\n", mr.repo_name));
        csv.push_str(&format!("Status,{}\n", mr.status));
        csv.push_str(&format!("Author,{}\n", mr.author_name));
        csv.push_str(&format!("Comments,{}\n", mr.comment_count));
        csv.push_str(&format!("Unresolved Comments,{}\n", mr.unresolved_comment_count));
        csv.push_str(&format!("Issues,{}\n", mr.issue_count));
        csv.push_str("\n");
        
        csv.push_str("Comments\n");
        csv.push_str("ID,Author,File,Line,Content,Resolved,Created At\n");
        
        for comment in comments {
            let file = comment.file_path.as_deref().unwrap_or("");
            let line = comment.line_no.map(|l| l.to_string()).unwrap_or_default();
            let content = comment.content.replace(',', "\\,").replace('\n', " ");
            let resolved = if comment.resolved { "Yes" } else { "No" };
            
            csv.push_str(&format!(
                "{},{},{},{},{},{},{}\n",
                comment.id,
                comment.author_name.replace(',', "\\,"),
                file.replace(',', "\\,"),
                line,
                content,
                resolved,
                comment.created_at
            ));
        }

        Ok(csv)
    }

    fn export_to_markdown(
        &self,
        mr: &MergeRequestWithDetails,
        comments: &[CommentWithDetails],
        diff: &[DiffFile],
    ) -> AppResult<String> {
        let mut md = String::new();

        md.push_str(&format!("# {}\n\n", mr.title));
        md.push_str(&format!("**Repository**: {}\n", mr.repo_name));
        md.push_str(&format!("**Status**: {}\n", mr.status));
        md.push_str(&format!("**Author**: {}\n", mr.author_name));
        md.push_str(&format!("**Branches**: `{}` → `{}`\n", mr.source_branch, mr.target_branch));
        md.push_str(&format!("**Created**: {}\n", mr.created_at));
        md.push_str(&format!("**Updated**: {}\n\n", mr.updated_at));

        md.push_str("## Statistics\n\n");
        md.push_str(&format!("- Total Comments: {}\n", mr.comment_count));
        md.push_str(&format!("- Unresolved Comments: {}\n", mr.unresolved_comment_count));
        md.push_str(&format!("- Issues: {}\n\n", mr.issue_count));

        if let Some(description) = &mr.description {
            md.push_str("## Description\n\n");
            md.push_str(&format!("{}\n\n", description));
        }

        md.push_str("## Comments\n\n");
        if comments.is_empty() {
            md.push_str("No comments yet.\n\n");
        } else {
            for comment in comments {
                md.push_str(&format!("### {} @ {}\n\n", comment.author_name, comment.created_at));
                
                if let Some(file_path) = &comment.file_path {
                    if let Some(line_no) = comment.line_no {
                        md.push_str(&format!("**File**: `{}:{}`\n\n", file_path, line_no));
                    } else {
                        md.push_str(&format!("**File**: `{}`\n\n", file_path));
                    }
                }

                md.push_str(&format!("{}\n\n", comment.content));
                
                if comment.resolved {
                    md.push_str(&format!("*Resolved by {} at {}*\n\n", 
                        comment.resolved_by_name.as_deref().unwrap_or("Unknown"),
                        comment.resolved_at.map(|t| t.to_string()).as_deref().unwrap_or("Unknown")
                    ));
                }

                if !comment.replies.is_empty() {
                    for reply in &comment.replies {
                        md.push_str(&format!("\n> **{}** @ {}: {}\n", 
                            reply.author_name, reply.created_at, reply.content
                        ));
                    }
                    md.push('\n');
                }
            }
        }

        md.push_str("## Diff Summary\n\n");
        md.push_str(&format!("**Changed Files**: {}\n\n", diff.len()));

        for file in diff {
            let additions: i64 = file.hunks.iter()
                .map(|h| h.lines.iter().filter(|l| l.line_type == "new").count() as i64)
                .sum();
            let deletions: i64 = file.hunks.iter()
                .map(|h| h.lines.iter().filter(|l| l.line_type == "old").count() as i64)
                .sum();

            md.push_str(&format!("- `{}`: +{}/-{}\n", file.new_path, additions, deletions));
        }

        Ok(md)
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct AiReviewTriggerResult {
    pub mr_id: Uuid,
    pub status: String,
    pub message: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct MrExportResult {
    pub mr_id: Uuid,
    pub format: String,
    pub storage_key: String,
    pub download_url: String,
}
