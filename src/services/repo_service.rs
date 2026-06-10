use std::sync::Arc;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::models::{
    CreateRepositoryRequest, Repository, RepositoryQuery, RepositoryWithDetails, WebhookLog,
};
use crate::providers::{GitProvider, MinioClient, PaginationParams, RedisClient};
use crate::repositories::{RepoRepository, UserRepository};
use crate::utils::{generate_webhook_secret, AppError, AppResult, PaginatedResult};

#[derive(Clone)]
pub struct RepoService {
    repo_repo: RepoRepository,
    user_repo: UserRepository,
    redis_client: RedisClient,
    minio_client: MinioClient,
    git_provider: Arc<dyn GitProvider>,
}

impl RepoService {
    pub fn new(
        repo_repo: RepoRepository,
        user_repo: UserRepository,
        redis_client: RedisClient,
        minio_client: MinioClient,
        git_provider: Arc<dyn GitProvider>,
    ) -> Self {
        Self {
            repo_repo,
            user_repo,
            redis_client,
            minio_client,
            git_provider,
        }
    }

    pub async fn list_repos(
        &self,
        organization_id: Uuid,
        query: RepositoryQuery,
    ) -> AppResult<PaginatedResult<RepositoryWithDetails>> {
        let page = query.page.unwrap_or(1);
        let per_page = query.per_page.unwrap_or(20);

        let (repos, total) = self
            .repo_repo
            .list_with_details(
                organization_id,
                query.team_id,
                query.provider.as_deref(),
                query.is_active,
                page,
                per_page,
            )
            .await?;

        Ok(PaginatedResult::new(repos, page, per_page, total))
    }

    pub async fn get_repo(&self, id: Uuid) -> AppResult<Repository> {
        self.repo_repo
            .get_by_id(id)
            .await?
            .ok_or_else(|| AppError::NotFound(format!("Repository not found: {}", id)))
    }

    pub async fn create_repo(
        &self,
        organization_id: Uuid,
        req: CreateRepositoryRequest,
    ) -> AppResult<Repository> {
        let webhook_secret = generate_webhook_secret();

        let repo = self
            .repo_repo
            .create(
                organization_id,
                req.team_id,
                &req.provider,
                &req.provider_id,
                &req.name,
                &req.full_name,
                &webhook_secret,
            )
            .await?;

        Ok(repo)
    }

    pub async fn update_repo(
        &self,
        id: Uuid,
        team_id: Option<Uuid>,
    ) -> AppResult<Repository> {
        let repo = self.get_repo(id).await?;

        let updated = self
            .repo_repo
            .create(
                repo.organization_id,
                team_id,
                &repo.provider,
                &repo.provider_id,
                &repo.name,
                &repo.full_name,
                &repo.webhook_secret,
            )
            .await?;

        Ok(updated)
    }

    pub async fn delete_repo(&self, id: Uuid) -> AppResult<()> {
        self.repo_repo.delete(id).await?;
        Ok(())
    }

    pub async fn set_repo_active(&self, id: Uuid, is_active: bool) -> AppResult<()> {
        self.repo_repo.set_active(id, is_active).await?;
        Ok(())
    }

    pub async fn sync_repo(&self, id: Uuid) -> AppResult<()> {
        let repo = self.get_repo(id).await?;

        if !repo.is_active {
            return Err(AppError::Validation(
                "Repository is not active".to_string(),
            ));
        }

        let lock_key = format!("repo:sync:{}", id);
        let locked = self
            .redis_client
            .acquire_lock(&lock_key, std::time::Duration::from_secs(300))
            .await?;

        if !locked {
            return Err(AppError::Conflict(
                "Repository sync already in progress".to_string(),
            ));
        }

        let result = self.sync_repo_internal(&repo).await;

        let _ = self.redis_client.release_lock(&lock_key).await;

        result?;
        self.repo_repo.update_sync_status(id).await?;

        Ok(())
    }

    async fn sync_repo_internal(&self, repo: &Repository) -> AppResult<()> {
        let pagination = PaginationParams::new(1, 100);
        let mrs = self
            .git_provider
            .get_merge_requests(&repo.full_name, None, pagination)
            .await?;

        for mr in mrs.items {
            let author = self
                .user_repo
                .get_by_provider_id(&repo.provider, &mr.author.id)
                .await?;

            if let Some(author) = author {
                let status = match mr.state.as_str() {
                    "open" => "open",
                    "merged" => "merged",
                    "closed" => "closed",
                    _ => "open",
                };

                let _ = self
                    .repo_repo
                    .create(
                        repo.organization_id,
                        repo.team_id,
                        &repo.provider,
                        &repo.provider_id,
                        &repo.name,
                        &repo.full_name,
                        &repo.webhook_secret,
                    )
                    .await?;
            }
        }

        Ok(())
    }

    pub async fn import_repos(
        &self,
        organization_id: Uuid,
        provider: &str,
        team_id: Option<Uuid>,
    ) -> AppResult<Vec<Repository>> {
        let pagination = PaginationParams::new(1, 100);
        let provider_repos = self
            .git_provider
            .get_repositories(pagination)
            .await?;

        let mut imported = Vec::new();
        for provider_repo in provider_repos.items {
            let webhook_secret = generate_webhook_secret();
            let repo = self
                .repo_repo
                .create(
                    organization_id,
                    team_id,
                    provider,
                    &provider_repo.id,
                    &provider_repo.name,
                    &provider_repo.full_name,
                    &webhook_secret,
                )
                .await?;
            imported.push(repo);
        }

        Ok(imported)
    }

    pub async fn get_webhook_config(&self, id: Uuid) -> AppResult<WebhookConfig> {
        let repo = self.get_repo(id).await?;

        Ok(WebhookConfig {
            repo_id: repo.id,
            provider: repo.provider.clone(),
            webhook_secret: repo.webhook_secret.clone(),
            is_active: repo.is_active,
        })
    }

    pub async fn sync_webhook(&self, id: Uuid, webhook_url: &str) -> AppResult<()> {
        let repo = self.get_repo(id).await?;

        let events = vec![
            "pull_request".to_string(),
            "push".to_string(),
            "issue_comment".to_string(),
            "pull_request_review".to_string(),
            "pull_request_review_comment".to_string(),
        ];

        self.git_provider
            .create_webhook(&repo.full_name, webhook_url, &events, &repo.webhook_secret)
            .await?;

        Ok(())
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct WebhookConfig {
    pub repo_id: Uuid,
    pub provider: String,
    pub webhook_secret: String,
    pub is_active: bool,
}
