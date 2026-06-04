use chrono::Utc;
use common::error::{AppError, AppResult};
use models::{AccountTransaction, UserProfile, UserRepository};
use mongodb::Collection;
use rust_decimal::Decimal;
use shared::TransactionType;
use sqlx::PgPool;
use tracing::{info, warn};
use uuid::Uuid;

use crate::transaction::TransactionLedger;

pub struct AccountService {
    pg_pool: PgPool,
    ledger: TransactionLedger,
}

impl AccountService {
    pub fn new(pg_pool: PgPool, ledger: TransactionLedger) -> Self {
        Self { pg_pool, ledger }
    }

    pub fn ledger(&self) -> &TransactionLedger {
        &self.ledger
    }

    pub async fn get_profile(&self, user_id: Uuid) -> AppResult<UserProfile> {
        let profile = UserRepository::find_profile(&self.pg_pool, user_id)
            .await?
            .ok_or_else(|| AppError::NotFound("用户不存在".into()))?;
        Ok(profile)
    }

    pub async fn get_balance(&self, user_id: Uuid) -> AppResult<(Decimal, Decimal)> {
        UserRepository::get_balance(&self.pg_pool, user_id)
            .await?
            .ok_or_else(|| AppError::NotFound("用户不存在".into()))
    }

    pub async fn deposit(&self, user_id: Uuid, amount: Decimal, payment_transaction_id: Option<String>) -> AppResult<Uuid> {
        if amount <= Decimal::ZERO {
            return Err(AppError::Validation("充值金额必须大于0".into()));
        }

        let mut tx = self.pg_pool.begin().await?;

        let (balance_before, frozen_before) = UserRepository::get_balance_for_update(&mut tx, user_id).await?;

        let balance_after = balance_before + amount;

        UserRepository::update_balance(&mut tx, user_id, balance_after, None).await?;

        tx.commit().await?;

        let tx_id = self
            .ledger
            .record_transaction(
                user_id,
                TransactionType::Deposit,
                amount,
                balance_before,
                balance_after,
                frozen_before,
                frozen_before,
                None,
                Some("payment".to_string()),
                format!("账户充值 ¥{:.2}", amount),
            )
            .await?;

        info!(user_id = %user_id, amount = %amount, transaction_id = %tx_id, "Deposit successful");
        Ok(tx_id)
    }

    pub async fn freeze(
        &self,
        user_id: Uuid,
        amount: Decimal,
        reference_id: Option<Uuid>,
        description: String,
    ) -> AppResult<Uuid> {
        if amount <= Decimal::ZERO {
            return Err(AppError::Validation("冻结金额必须大于0".into()));
        }

        let mut tx = self.pg_pool.begin().await?;

        let (balance_before, frozen_before) = UserRepository::get_balance_for_update(&mut tx, user_id).await?;

        if balance_before < amount {
            return Err(AppError::Account("余额不足".into()));
        }

        let balance_after = balance_before - amount;
        let frozen_after = frozen_before + amount;

        UserRepository::update_balance(&mut tx, user_id, balance_after, Some(frozen_after)).await?;

        tx.commit().await?;

        let tx_id = self
            .ledger
            .record_transaction(
                user_id,
                TransactionType::Freeze,
                amount,
                balance_before,
                balance_after,
                frozen_before,
                frozen_after,
                reference_id,
                Some("auction_deposit".to_string()),
                description,
            )
            .await?;

        info!(user_id = %user_id, amount = %amount, transaction_id = %tx_id, "Amount frozen");
        Ok(tx_id)
    }

    pub async fn unfreeze(
        &self,
        user_id: Uuid,
        amount: Decimal,
        reference_id: Option<Uuid>,
        description: String,
    ) -> AppResult<Uuid> {
        if amount <= Decimal::ZERO {
            return Err(AppError::Validation("解冻金额必须大于0".into()));
        }

        let mut tx = self.pg_pool.begin().await?;

        let (balance_before, frozen_before) = UserRepository::get_balance_for_update(&mut tx, user_id).await?;

        if frozen_before < amount {
            warn!(user_id = %user_id, frozen = %frozen_before, requested = %amount, "Insufficient frozen balance");
            return Err(AppError::Account("冻结余额不足".into()));
        }

        let balance_after = balance_before + amount;
        let frozen_after = frozen_before - amount;

        UserRepository::update_balance(&mut tx, user_id, balance_after, Some(frozen_after)).await?;

        tx.commit().await?;

        let tx_id = self
            .ledger
            .record_transaction(
                user_id,
                TransactionType::Unfreeze,
                amount,
                balance_before,
                balance_after,
                frozen_before,
                frozen_after,
                reference_id,
                Some("auction_refund".to_string()),
                description,
            )
            .await?;

        info!(user_id = %user_id, amount = %amount, transaction_id = %tx_id, "Amount unfrozen");
        Ok(tx_id)
    }

    pub async fn payment(
        &self,
        user_id: Uuid,
        amount: Decimal,
        reference_id: Option<Uuid>,
        description: String,
    ) -> AppResult<Uuid> {
        if amount <= Decimal::ZERO {
            return Err(AppError::Validation("支付金额必须大于0".into()));
        }

        let mut tx = self.pg_pool.begin().await?;

        let (balance_before, frozen_before) = UserRepository::get_balance_for_update(&mut tx, user_id).await?;

        if frozen_before < amount {
            return Err(AppError::Account("冻结余额不足，请联系客服".into()));
        }

        let frozen_after = frozen_before - amount;

        UserRepository::update_frozen_balance(&mut tx, user_id, frozen_after).await?;

        tx.commit().await?;

        let tx_id = self
            .ledger
            .record_transaction(
                user_id,
                TransactionType::Payment,
                amount,
                balance_before,
                balance_before,
                frozen_before,
                frozen_after,
                reference_id,
                Some("order_payment".to_string()),
                description,
            )
            .await?;

        info!(user_id = %user_id, amount = %amount, transaction_id = %tx_id, "Payment completed");
        Ok(tx_id)
    }

    pub async fn refund(
        &self,
        user_id: Uuid,
        amount: Decimal,
        reference_id: Option<Uuid>,
        description: String,
    ) -> AppResult<Uuid> {
        if amount <= Decimal::ZERO {
            return Err(AppError::Validation("退款金额必须大于0".into()));
        }

        let mut tx = self.pg_pool.begin().await?;

        let (balance_before, frozen_before) = UserRepository::get_balance_for_update(&mut tx, user_id).await?;

        let balance_after = balance_before + amount;

        UserRepository::update_balance(&mut tx, user_id, balance_after, None).await?;

        tx.commit().await?;

        let tx_id = self
            .ledger
            .record_transaction(
                user_id,
                TransactionType::Refund,
                amount,
                balance_before,
                balance_after,
                frozen_before,
                frozen_before,
                reference_id,
                Some("refund".to_string()),
                description,
            )
            .await?;

        info!(user_id = %user_id, amount = %amount, transaction_id = %tx_id, "Refund processed");
        Ok(tx_id)
    }

    pub async fn settle_to_seller(
        &self,
        seller_id: Uuid,
        amount: Decimal,
        order_id: Option<Uuid>,
        description: String,
    ) -> AppResult<Uuid> {
        if amount <= Decimal::ZERO {
            return Err(AppError::Validation("结算金额必须大于0".into()));
        }

        let mut tx = self.pg_pool.begin().await?;

        let (balance_before, frozen_before) = UserRepository::get_balance_for_update(&mut tx, seller_id).await?;

        let balance_after = balance_before + amount;

        UserRepository::update_balance(&mut tx, seller_id, balance_after, None).await?;

        tx.commit().await?;

        let tx_id = self
            .ledger
            .record_transaction(
                seller_id,
                TransactionType::Settlement,
                amount,
                balance_before,
                balance_after,
                frozen_before,
                frozen_before,
                order_id,
                Some("seller_settlement".to_string()),
                description,
            )
            .await?;

        info!(seller_id = %seller_id, amount = %amount, transaction_id = %tx_id, "Settlement completed");
        Ok(tx_id)
    }

    pub async fn get_transaction_history(
        &self,
        user_id: Uuid,
        page: i64,
        per_page: i64,
    ) -> AppResult<Vec<AccountTransaction>> {
        let offset = (page - 1) * per_page;
        self.ledger
            .get_user_transactions(user_id, per_page, offset)
            .await
    }
}

impl Clone for AccountService {
    fn clone(&self) -> Self {
        Self {
            pg_pool: self.pg_pool.clone(),
            ledger: self.ledger.clone(),
        }
    }
}
