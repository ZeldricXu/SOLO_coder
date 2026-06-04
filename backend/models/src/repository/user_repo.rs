use common::error::AppResult;
use rust_decimal::Decimal;
use shared::UserRole;
use sqlx::PgPool;
use uuid::Uuid;

use crate::UserProfile;

pub struct UserRepository;

impl UserRepository {
    pub async fn exists_by_email_or_username(pool: &PgPool, email: &str, username: &str) -> AppResult<bool> {
        let exists: Option<bool> = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM users WHERE email = $1 OR username = $2)"
        )
        .bind(email)
        .bind(username)
        .fetch_one(pool)
        .await?;
        Ok(exists.unwrap_or(false))
    }

    pub async fn create(
        pool: &PgPool,
        id: Uuid,
        username: &str,
        email: &str,
        password_hash: &str,
        role: UserRole,
    ) -> AppResult<()> {
        sqlx::query(
            "
            INSERT INTO users (id, username, email, password_hash, role)
            VALUES ($1, $2, $3, $4, $5)
            "
        )
        .bind(id)
        .bind(username)
        .bind(email)
        .bind(password_hash)
        .bind(role)
        .execute(pool)
        .await?;
        Ok(())
    }

    pub async fn find_credentials_by_email(pool: &PgPool, email: &str) -> AppResult<Option<(Uuid, String, UserRole)>> {
        let row = sqlx::query_as::<_, (Uuid, String, UserRole)>(
            r#"
            SELECT id, password_hash, role
            FROM users
            WHERE email = $1
            "#,
        )
        .bind(email)
        .fetch_optional(pool)
        .await?;
        Ok(row)
    }

    pub async fn find_profile(pool: &PgPool, id: Uuid) -> AppResult<Option<UserProfile>> {
        let profile = sqlx::query_as::<_, UserProfile>(
            "
            SELECT id, username, email, role,
                   balance, frozen_balance, is_verified, created_at
            FROM users WHERE id = $1
            "
        )
        .bind(id)
        .fetch_optional(pool)
        .await?;
        Ok(profile)
    }

    pub async fn get_balance(pool: &PgPool, id: Uuid) -> AppResult<Option<(Decimal, Decimal)>> {
        let result: Option<(Decimal, Decimal)> = sqlx::query_as(
            "SELECT balance, frozen_balance FROM users WHERE id = $1"
        )
        .bind(id)
        .fetch_optional(pool)
        .await?;
        Ok(result)
    }

    pub async fn get_balance_for_update(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
    ) -> AppResult<(Decimal, Decimal)> {
        let result: (Decimal, Decimal) = sqlx::query_as(
            "SELECT balance, frozen_balance FROM users WHERE id = $1 FOR UPDATE"
        )
        .bind(id)
        .fetch_one(&mut **pool)
        .await?;
        Ok(result)
    }

    pub async fn update_balance(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
        balance: Decimal,
        frozen_balance: Option<Decimal>,
    ) -> AppResult<()> {
        match frozen_balance {
            Some(frozen) => {
                sqlx::query("UPDATE users SET balance = $1, frozen_balance = $2 WHERE id = $3")
                    .bind(balance)
                    .bind(frozen)
                    .bind(id)
                    .execute(&mut **pool)
                    .await?;
            }
            None => {
                sqlx::query("UPDATE users SET balance = $1 WHERE id = $2")
                    .bind(balance)
                    .bind(id)
                    .execute(&mut **pool)
                    .await?;
            }
        }
        Ok(())
    }

    pub async fn update_frozen_balance(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
        frozen_balance: Decimal,
    ) -> AppResult<()> {
        sqlx::query("UPDATE users SET frozen_balance = $1 WHERE id = $2")
            .bind(frozen_balance)
            .bind(id)
            .execute(&mut **pool)
            .await?;
        Ok(())
    }

    pub async fn freeze_balance(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
        amount: Decimal,
    ) -> AppResult<bool> {
        let result = sqlx::query(
            "
            UPDATE users
            SET balance = balance - $1,
                frozen_balance = frozen_balance + $1
            WHERE id = $2 AND balance >= $1
            "
        )
        .bind(amount)
        .bind(id)
        .execute(&mut **pool)
        .await?;
        Ok(result.rows_affected() > 0)
    }

    pub async fn unfreeze_balance(
        pool: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        id: Uuid,
        amount: Decimal,
    ) -> AppResult<()> {
        sqlx::query(
            "
            UPDATE users
            SET balance = balance + $1,
                frozen_balance = frozen_balance - $1
            WHERE id = $2
            "
        )
        .bind(amount)
        .bind(id)
        .execute(&mut **pool)
        .await?;
        Ok(())
    }
}
