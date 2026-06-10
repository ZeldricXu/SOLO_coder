use chrono::{DateTime, Utc};
use sqlx::{Postgres, Pool};
use uuid::Uuid;

use crate::error::AppResult;
use crate::models::user::{User, UserWithRole, Organization, Team, TeamMember, OAuthCredential};

#[derive(Clone)]
pub struct UserRepository {
    pool: Pool<Postgres>,
}

impl UserRepository {
    pub fn new(pool: Pool<Postgres>) -> Self {
        Self { pool }
    }

    pub async fn create(&self, username: &str, email: &str, avatar_url: Option<&str>) -> AppResult<User> {
        let user = sqlx::query_as!(
            User,
            r#"
            INSERT INTO users (username, email, avatar_url)
            VALUES ($1, $2, $3)
            ON CONFLICT (email) DO UPDATE SET
                username = EXCLUDED.username,
                avatar_url = EXCLUDED.avatar_url
            RETURNING *
            "#,
            username,
            email,
            avatar_url,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(user)
    }

    pub async fn get_by_id(&self, id: Uuid) -> AppResult<Option<User>> {
        let user = sqlx::query_as!(
            User,
            "SELECT * FROM users WHERE id = $1",
            id
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(user)
    }

    pub async fn get_by_email(&self, email: &str) -> AppResult<Option<User>> {
        let user = sqlx::query_as!(
            User,
            "SELECT * FROM users WHERE email = $1",
            email
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(user)
    }

    pub async fn get_by_provider_id(&self, provider: &str, provider_id: &str) -> AppResult<Option<User>> {
        let user = sqlx::query_as!(
            User,
            r#"
            SELECT u.* FROM users u
            JOIN oauth_credentials oc ON u.id = oc.user_id
            WHERE oc.provider = $1 AND oc.provider_id = $2
            "#,
            provider,
            provider_id
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(user)
    }

    pub async fn get_by_username(&self, username: &str) -> AppResult<Option<User>> {
        let user = sqlx::query_as!(
            User,
            "SELECT * FROM users WHERE username = $1",
            username
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(user)
    }

    pub async fn list(&self, page: i32, per_page: i32) -> AppResult<(Vec<User>, i64)> {
        let offset = ((page - 1) * per_page) as i64;
        let limit = per_page as i64;

        let users = sqlx::query_as!(
            User,
            "SELECT * FROM users ORDER BY created_at DESC LIMIT $1 OFFSET $2",
            limit,
            offset
        )
        .fetch_all(&self.pool)
        .await?;

        let total = sqlx::query_scalar!("SELECT COUNT(*) FROM users")
            .fetch_one(&self.pool)
            .await?
            .unwrap_or(0);

        Ok((users, total))
    }

    pub async fn create_oauth_credential(
        &self,
        user_id: Uuid,
        provider: &str,
        provider_id: &str,
        access_token: &str,
        refresh_token: Option<&str>,
        expires_at: Option<DateTime<Utc>>,
    ) -> AppResult<OAuthCredential> {
        let credential = sqlx::query_as!(
            OAuthCredential,
            r#"
            INSERT INTO oauth_credentials (user_id, provider, provider_id, access_token, refresh_token, expires_at)
            VALUES ($1, $2, $3, $4, $5, $6)
            ON CONFLICT (provider, provider_id) DO UPDATE SET
                access_token = EXCLUDED.access_token,
                refresh_token = EXCLUDED.refresh_token,
                expires_at = EXCLUDED.expires_at
            RETURNING *
            "#,
            user_id,
            provider,
            provider_id,
            access_token,
            refresh_token,
            expires_at,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(credential)
    }

    pub async fn get_oauth_credential(&self, user_id: Uuid, provider: &str) -> AppResult<Option<OAuthCredential>> {
        let credential = sqlx::query_as!(
            OAuthCredential,
            "SELECT * FROM oauth_credentials WHERE user_id = $1 AND provider = $2",
            user_id,
            provider
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(credential)
    }

    pub async fn create_organization(&self, name: &str, slug: &str, owner_id: Uuid) -> AppResult<Organization> {
        let org = sqlx::query_as!(
            Organization,
            r#"
            INSERT INTO organizations (name, slug, owner_id)
            VALUES ($1, $2, $3)
            RETURNING *
            "#,
            name,
            slug,
            owner_id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(org)
    }

    pub async fn get_organization(&self, id: Uuid) -> AppResult<Option<Organization>> {
        let org = sqlx::query_as!(
            Organization,
            "SELECT * FROM organizations WHERE id = $1",
            id
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(org)
    }

    pub async fn get_organization_by_slug(&self, slug: &str) -> AppResult<Option<Organization>> {
        let org = sqlx::query_as!(
            Organization,
            "SELECT * FROM organizations WHERE slug = $1",
            slug
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(org)
    }

    pub async fn create_team(&self, organization_id: Uuid, name: &str, description: Option<&str>) -> AppResult<Team> {
        let team = sqlx::query_as!(
            Team,
            r#"
            INSERT INTO teams (organization_id, name, description)
            VALUES ($1, $2, $3)
            RETURNING *
            "#,
            organization_id,
            name,
            description,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(team)
    }

    pub async fn get_team(&self, id: Uuid) -> AppResult<Option<Team>> {
        let team = sqlx::query_as!(
            Team,
            "SELECT * FROM teams WHERE id = $1",
            id
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(team)
    }

    pub async fn list_teams(&self, organization_id: Uuid) -> AppResult<Vec<Team>> {
        let teams = sqlx::query_as!(
            Team,
            "SELECT * FROM teams WHERE organization_id = $1 ORDER BY name",
            organization_id
        )
        .fetch_all(&self.pool)
        .await?;
        Ok(teams)
    }

    pub async fn add_team_member(&self, team_id: Uuid, user_id: Uuid, role: &str) -> AppResult<TeamMember> {
        let member = sqlx::query_as!(
            TeamMember,
            r#"
            INSERT INTO team_members (team_id, user_id, role)
            VALUES ($1, $2, $3)
            ON CONFLICT (team_id, user_id) DO UPDATE SET
                role = EXCLUDED.role
            RETURNING *
            "#,
            team_id,
            user_id,
            role,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(member)
    }

    pub async fn remove_team_member(&self, team_id: Uuid, user_id: Uuid) -> AppResult<()> {
        sqlx::query!(
            "DELETE FROM team_members WHERE team_id = $1 AND user_id = $2",
            team_id,
            user_id
        )
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn update_member_role(&self, team_id: Uuid, user_id: Uuid, role: &str) -> AppResult<TeamMember> {
        let member = sqlx::query_as!(
            TeamMember,
            r#"
            UPDATE team_members
            SET role = $1
            WHERE team_id = $2 AND user_id = $3
            RETURNING *
            "#,
            role,
            team_id,
            user_id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(member)
    }

    pub async fn list_team_members(&self, team_id: Uuid) -> AppResult<Vec<UserWithRole>> {
        let members = sqlx::query_as!(
            UserWithRole,
            r#"
            SELECT u.id, u.username, u.email, u.avatar_url, tm.role, u.created_at
            FROM users u
            JOIN team_members tm ON u.id = tm.user_id
            WHERE tm.team_id = $1
            ORDER BY tm.role, u.username
            "#,
            team_id
        )
        .fetch_all(&self.pool)
        .await?;
        Ok(members)
    }

    pub async fn get_user_highest_role(&self, user_id: Uuid, organization_id: Uuid) -> AppResult<Option<String>> {
        let role = sqlx::query_scalar!(
            r#"
            SELECT tm.role
            FROM team_members tm
            JOIN teams t ON tm.team_id = t.id
            WHERE tm.user_id = $1 AND t.organization_id = $2
            ORDER BY CASE tm.role
                WHEN 'owner' THEN 1
                WHEN 'maintainer' THEN 2
                WHEN 'reviewer' THEN 3
                WHEN 'developer' THEN 4
                ELSE 5
            END
            LIMIT 1
            "#,
            user_id,
            organization_id
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(role)
    }

    pub async fn get_user_teams(&self, user_id: Uuid) -> AppResult<Vec<(Team, String)>> {
        let teams = sqlx::query_as!(
            r#"
            SELECT t.id, t.organization_id, t.name, t.description, t.created_at, tm.role
            FROM teams t
            JOIN team_members tm ON t.id = tm.team_id
            WHERE tm.user_id = $1
            ORDER BY t.name
            "#,
            user_id
        )
        .fetch_all(&self.pool)
        .await?;
        
        let result = teams.into_iter().map(|row| {
            (Team {
                id: row.id,
                organization_id: row.organization_id,
                name: row.name,
                description: row.description,
                created_at: row.created_at,
            }, row.role)
        }).collect();
        
        Ok(result)
    }
}
