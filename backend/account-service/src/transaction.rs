use chrono::Utc;
use common::error::AppResult;
use futures_util::TryStreamExt;
use models::AccountTransaction;
use mongodb::Collection;
use rust_decimal::Decimal;
use shared::TransactionType;
use uuid::Uuid;

pub struct TransactionLedger {
    collection: Collection<AccountTransaction>,
}

impl TransactionLedger {
    pub fn new(collection: Collection<AccountTransaction>) -> Self {
        Self { collection }
    }

    pub async fn record_transaction(
        &self,
        user_id: Uuid,
        type_: TransactionType,
        amount: Decimal,
        balance_before: Decimal,
        balance_after: Decimal,
        frozen_before: Decimal,
        frozen_after: Decimal,
        reference_id: Option<Uuid>,
        reference_type: Option<String>,
        description: String,
    ) -> AppResult<Uuid> {
        let transaction_id = Uuid::new_v4();

        let tx = AccountTransaction {
            id: None,
            transaction_id,
            user_id,
            type_,
            amount,
            balance_before,
            balance_after,
            frozen_before,
            frozen_after,
            reference_id,
            reference_type,
            description,
            metadata: None,
            created_at: Utc::now(),
        };

        self.collection.insert_one(tx, None).await?;

        Ok(transaction_id)
    }

    pub async fn get_user_transactions(
        &self,
        user_id: Uuid,
        limit: i64,
        offset: i64,
    ) -> AppResult<Vec<AccountTransaction>> {
        use mongodb::options::FindOptions;

        let options = FindOptions::builder()
            .sort(mongodb::bson::doc! { "created_at": -1 })
            .limit(limit)
            .skip(offset as u64)
            .build();

        let cursor = self
            .collection
            .find(mongodb::bson::doc! { "user_id": user_id }, options)
            .await?;

        let transactions: Vec<AccountTransaction> = cursor
            .try_collect()
            .await
            .map_err(|e| common::error::AppError::MongoDB(e))?;

        Ok(transactions)
    }

    pub async fn get_transaction_by_id(
        &self,
        transaction_id: Uuid,
    ) -> AppResult<Option<AccountTransaction>> {
        let tx = self
            .collection
            .find_one(mongodb::bson::doc! { "transaction_id": transaction_id }, None)
            .await?;
        Ok(tx)
    }

    pub async fn get_balance_history(
        &self,
        user_id: Uuid,
        start_time: chrono::DateTime<Utc>,
        end_time: chrono::DateTime<Utc>,
    ) -> AppResult<Vec<AccountTransaction>> {
        use mongodb::options::FindOptions;

        let options = FindOptions::builder()
            .sort(mongodb::bson::doc! { "created_at": 1 })
            .build();

        let filter = mongodb::bson::doc! {
            "user_id": user_id,
            "created_at": {
                "$gte": start_time,
                "$lte": end_time
            }
        };

        let cursor = self.collection.find(filter, options).await?;
        let transactions: Vec<AccountTransaction> = cursor
            .try_collect()
            .await
            .map_err(|e| common::error::AppError::MongoDB(e))?;

        Ok(transactions)
    }
}

impl Clone for TransactionLedger {
    fn clone(&self) -> Self {
        Self {
            collection: self.collection.clone(),
        }
    }
}
