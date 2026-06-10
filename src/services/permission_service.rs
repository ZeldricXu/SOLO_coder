use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::models::{Issue, Repository};
use crate::repositories::UserRepository;
use crate::utils::{AppError, AppResult};

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
pub enum Permission {
    ManageRepository,
    ReviewMergeRequest,
    CreateIssue,
    EditIssue,
    EditAnyIssue,
    ManageTeam,
    ManageOrganization,
    ViewStatistics,
    AssignReviewer,
    MergeRequest,
}

impl Permission {
    pub fn as_str(&self) -> &str {
        match self {
            Permission::ManageRepository => "manage_repository",
            Permission::ReviewMergeRequest => "review_merge_request",
            Permission::CreateIssue => "create_issue",
            Permission::EditIssue => "edit_issue",
            Permission::EditAnyIssue => "edit_any_issue",
            Permission::ManageTeam => "manage_team",
            Permission::ManageOrganization => "manage_organization",
            Permission::ViewStatistics => "view_statistics",
            Permission::AssignReviewer => "assign_reviewer",
            Permission::MergeRequest => "merge_request",
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct UserPermissions {
    pub user_id: Uuid,
    pub organization_id: Uuid,
    pub role: String,
    pub permissions: Vec<Permission>,
}

#[derive(Clone)]
pub struct PermissionService {
    user_repo: UserRepository,
}

impl PermissionService {
    pub fn new(user_repo: UserRepository) -> Self {
        Self { user_repo }
    }

    pub async fn has_role(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
        required_role: &str,
    ) -> AppResult<bool> {
        let user_role = self
            .user_repo
            .get_user_highest_role(user_id, organization_id)
            .await?;

        match user_role {
            Some(role) => Ok(self.role_has_permission(&role, required_role)),
            None => Ok(false),
        }
    }

    pub async fn require_role(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
        required_role: &str,
    ) -> AppResult<()> {
        let has_role = self
            .has_role(user_id, organization_id, required_role)
            .await?;

        if !has_role {
            return Err(AppError::Authorization(format!(
                "User requires '{}' role to perform this action",
                required_role
            )));
        }

        Ok(())
    }

    pub async fn can_manage_repo(
        &self,
        user_id: Uuid,
        repo: &Repository,
    ) -> AppResult<bool> {
        if let Some(team_id) = repo.team_id {
            let teams = self.user_repo.get_user_teams(user_id).await?;
            for (team, role) in teams {
                if team.id == team_id {
                    return Ok(matches!(
                        role.as_str(),
                        "owner" | "maintainer"
                    ));
                }
            }
        }

        let org_role = self
            .user_repo
            .get_user_highest_role(user_id, repo.organization_id)
            .await?;

        Ok(matches!(
            org_role.as_deref(),
            Some("owner") | Some("maintainer")
        ))
    }

    pub async fn can_review(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
        team_id: Option<Uuid>,
    ) -> AppResult<bool> {
        if let Some(team_id) = team_id {
            let teams = self.user_repo.get_user_teams(user_id).await?;
            for (team, role) in teams {
                if team.id == team_id {
                    return Ok(matches!(
                        role.as_str(),
                        "owner" | "maintainer" | "reviewer"
                    ));
                }
            }
        }

        let org_role = self
            .user_repo
            .get_user_highest_role(user_id, organization_id)
            .await?;

        Ok(matches!(
            org_role.as_deref(),
            Some("owner") | Some("maintainer") | Some("reviewer")
        ))
    }

    pub async fn can_create_issue(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
    ) -> AppResult<bool> {
        let role = self
            .user_repo
            .get_user_highest_role(user_id, organization_id)
            .await?;

        Ok(matches!(
            role.as_deref(),
            Some("owner") | Some("maintainer") | Some("reviewer") | Some("developer")
        ))
    }

    pub async fn can_edit_issue(
        &self,
        user_id: Uuid,
        issue: &Issue,
        organization_id: Uuid,
    ) -> AppResult<bool> {
        if issue.reporter_id == user_id {
            return Ok(true);
        }

        if issue.assignee_id == Some(user_id) {
            return Ok(true);
        }

        let role = self
            .user_repo
            .get_user_highest_role(user_id, organization_id)
            .await?;

        Ok(matches!(
            role.as_deref(),
            Some("owner") | Some("maintainer")
        ))
    }

    pub async fn is_owner(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
    ) -> AppResult<bool> {
        let role = self
            .user_repo
            .get_user_highest_role(user_id, organization_id)
            .await?;
        Ok(matches!(role.as_deref(), Some("owner")))
    }

    pub async fn is_maintainer(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
    ) -> AppResult<bool> {
        let role = self
            .user_repo
            .get_user_highest_role(user_id, organization_id)
            .await?;
        Ok(matches!(role.as_deref(), Some("owner") | Some("maintainer")))
    }

    pub async fn is_reviewer(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
    ) -> AppResult<bool> {
        let role = self
            .user_repo
            .get_user_highest_role(user_id, organization_id)
            .await?;
        Ok(matches!(
            role.as_deref(),
            Some("owner") | Some("maintainer") | Some("reviewer")
        ))
    }

    pub async fn get_user_permissions(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
    ) -> AppResult<UserPermissions> {
        let role = self
            .user_repo
            .get_user_highest_role(user_id, organization_id)
            .await?
            .unwrap_or_else(|| "developer".to_string());

        let permissions = self.get_permissions_for_role(&role);

        Ok(UserPermissions {
            user_id,
            organization_id,
            role,
            permissions,
        })
    }

    fn role_has_permission(&self, user_role: &str, required_role: &str) -> bool {
        let role_priority = ["owner", "maintainer", "reviewer", "developer"];

        let user_idx = role_priority.iter().position(|r| r == user_role);
        let required_idx = role_priority.iter().position(|r| r == required_role);

        match (user_idx, required_idx) {
            (Some(u), Some(r)) => u <= r,
            _ => false,
        }
    }

    fn get_permissions_for_role(&self, role: &str) -> Vec<Permission> {
        let mut permissions = Vec::new();

        match role {
            "owner" => {
                permissions.push(Permission::ManageOrganization);
                permissions.push(Permission::ManageTeam);
                permissions.push(Permission::ManageRepository);
                permissions.push(Permission::ReviewMergeRequest);
                permissions.push(Permission::CreateIssue);
                permissions.push(Permission::EditIssue);
                permissions.push(Permission::EditAnyIssue);
                permissions.push(Permission::ViewStatistics);
                permissions.push(Permission::AssignReviewer);
                permissions.push(Permission::MergeRequest);
            }
            "maintainer" => {
                permissions.push(Permission::ManageTeam);
                permissions.push(Permission::ManageRepository);
                permissions.push(Permission::ReviewMergeRequest);
                permissions.push(Permission::CreateIssue);
                permissions.push(Permission::EditIssue);
                permissions.push(Permission::EditAnyIssue);
                permissions.push(Permission::ViewStatistics);
                permissions.push(Permission::AssignReviewer);
                permissions.push(Permission::MergeRequest);
            }
            "reviewer" => {
                permissions.push(Permission::ReviewMergeRequest);
                permissions.push(Permission::CreateIssue);
                permissions.push(Permission::EditIssue);
                permissions.push(Permission::ViewStatistics);
            }
            "developer" => {
                permissions.push(Permission::CreateIssue);
                permissions.push(Permission::EditIssue);
            }
            _ => {
                permissions.push(Permission::CreateIssue);
            }
        }

        permissions
    }
}
