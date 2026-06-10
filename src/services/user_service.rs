use uuid::Uuid;

use crate::models::{
    CreateOrganizationRequest, CreateTeamRequest, Organization, Team, TeamMember,
    TeamMemberWithUser, User, UserWithRole,
};
use crate::repositories::{NotificationRepository, UserRepository};
use crate::utils::{AppError, AppResult, PaginatedResult};

#[derive(Clone)]
pub struct UserService {
    user_repo: UserRepository,
    notification_repo: NotificationRepository,
}

impl UserService {
    pub fn new(user_repo: UserRepository, notification_repo: NotificationRepository) -> Self {
        Self {
            user_repo,
            notification_repo,
        }
    }

    pub async fn get_user(&self, user_id: Uuid) -> AppResult<User> {
        self.user_repo
            .get_by_id(user_id)
            .await?
            .ok_or_else(|| AppError::NotFound(format!("User with id {} not found", user_id)))
    }

    pub async fn get_user_by_provider(&self, provider: &str, provider_id: &str) -> AppResult<User> {
        self.user_repo
            .get_by_provider_id(provider, provider_id)
            .await?
            .ok_or_else(|| {
                AppError::NotFound(format!(
                    "User with provider {} and id {} not found",
                    provider, provider_id
                ))
            })
    }

    pub async fn list_users(
        &self,
        page: i32,
        per_page: i32,
    ) -> AppResult<PaginatedResult<User>> {
        let (users, total) = self.user_repo.list(page, per_page).await?;
        Ok(PaginatedResult::new(users, page, per_page, total))
    }

    pub async fn create_organization(
        &self,
        owner_id: Uuid,
        req: &CreateOrganizationRequest,
    ) -> AppResult<Organization> {
        let existing = self.user_repo.get_organization_by_slug(&req.slug).await?;
        if existing.is_some() {
            return Err(AppError::Conflict(format!(
                "Organization with slug {} already exists",
                req.slug
            )));
        }

        let org = self
            .user_repo
            .create_organization(&req.name, &req.slug, owner_id)
            .await?;

        let team = self
            .user_repo
            .create_team(org.id, "Owners", Some("Organization owners"))
            .await?;

        self.user_repo
            .add_team_member(team.id, owner_id, "owner")
            .await?;

        Ok(org)
    }

    pub async fn create_team(
        &self,
        organization_id: Uuid,
        req: &CreateTeamRequest,
    ) -> AppResult<Team> {
        let org = self
            .user_repo
            .get_organization(organization_id)
            .await?
            .ok_or_else(|| {
                AppError::NotFound(format!(
                    "Organization with id {} not found",
                    organization_id
                ))
            })?;

        let team = self
            .user_repo
            .create_team(org.id, &req.name, req.description.as_deref())
            .await?;

        Ok(team)
    }

    pub async fn add_team_member(
        &self,
        team_id: Uuid,
        user_id: Uuid,
        role: &str,
    ) -> AppResult<TeamMember> {
        self.validate_role(role)?;

        let team = self.user_repo.get_team(team_id).await?.ok_or_else(|| {
            AppError::NotFound(format!("Team with id {} not found", team_id))
        })?;

        let user = self.user_repo.get_by_id(user_id).await?.ok_or_else(|| {
            AppError::NotFound(format!("User with id {} not found", user_id))
        })?;

        let member = self
            .user_repo
            .add_team_member(team.id, user.id, role)
            .await?;

        Ok(member)
    }

    pub async fn remove_team_member(&self, team_id: Uuid, user_id: Uuid) -> AppResult<()> {
        let team = self.user_repo.get_team(team_id).await?.ok_or_else(|| {
            AppError::NotFound(format!("Team with id {} not found", team_id))
        })?;

        let members = self.user_repo.list_team_members(team.id).await?;
        if members.len() == 1 && members[0].id == user_id {
            return Err(AppError::Validation(
                "Cannot remove the last member of a team".to_string(),
            ));
        }

        self.user_repo.remove_team_member(team.id, user_id).await?;

        Ok(())
    }

    pub async fn update_member_role(
        &self,
        team_id: Uuid,
        user_id: Uuid,
        role: &str,
    ) -> AppResult<TeamMember> {
        self.validate_role(role)?;

        let member = self
            .user_repo
            .update_member_role(team_id, user_id, role)
            .await?;

        Ok(member)
    }

    pub async fn list_teams(&self, organization_id: Uuid) -> AppResult<Vec<Team>> {
        let teams = self.user_repo.list_teams(organization_id).await?;
        Ok(teams)
    }

    pub async fn list_team_members(&self, team_id: Uuid) -> AppResult<Vec<UserWithRole>> {
        let members = self.user_repo.list_team_members(team_id).await?;
        Ok(members)
    }

    pub async fn get_user_teams(&self, user_id: Uuid) -> AppResult<Vec<(Team, String)>> {
        let teams = self.user_repo.get_user_teams(user_id).await?;
        Ok(teams)
    }

    pub async fn get_user_highest_role(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
    ) -> AppResult<Option<String>> {
        let role = self
            .user_repo
            .get_user_highest_role(user_id, organization_id)
            .await?;
        Ok(role)
    }

    fn validate_role(&self, role: &str) -> AppResult<()> {
        let valid_roles = ["owner", "maintainer", "reviewer", "developer"];
        if !valid_roles.contains(&role) {
            return Err(AppError::Validation(format!(
                "Invalid role: {}. Valid roles are: {}",
                role,
                valid_roles.join(", ")
            )));
        }
        Ok(())
    }
}
