use actix_web::{web, HttpResponse, Responder};
use common::error::AppResult;
use models::{AccountTransaction, UserProfile};
use rust_decimal::Decimal;
use shared::ApiResponse;
use uuid::Uuid;

use crate::AccountService;

#[derive(Debug, serde::Deserialize)]
pub struct DepositRequest {
    pub amount: Decimal,
    pub payment_method: String,
}

#[derive(Debug, serde::Deserialize)]
pub struct TransactionQuery {
    pub page: Option<i64>,
    pub per_page: Option<i64>,
}

pub struct AccountWebService {
    service: AccountService,
}

impl AccountWebService {
    pub fn new(service: AccountService) -> Self {
        Self { service }
    }

    pub async fn get_profile(&self, user_id: Uuid) -> AppResult<UserProfile> {
        self.service.get_profile(user_id).await
    }

    pub async fn get_balance(&self, user_id: Uuid) -> AppResult<(Decimal, Decimal)> {
        self.service.get_balance(user_id).await
    }

    pub async fn deposit(&self, user_id: Uuid, amount: Decimal) -> AppResult<Uuid> {
        self.service.deposit(user_id, amount, None).await
    }

    pub async fn get_transactions(
        &self,
        user_id: Uuid,
        page: i64,
        per_page: i64,
    ) -> AppResult<Vec<AccountTransaction>> {
        self.service
            .get_transaction_history(user_id, page, per_page)
            .await
    }
}

impl Clone for AccountWebService {
    fn clone(&self) -> Self {
        Self {
            service: self.service.clone(),
        }
    }
}

pub async fn get_profile_handler(
    service: web::Data<AccountWebService>,
    user_id: web::ReqData<Uuid>,
) -> impl Responder {
    match service.get_profile(user_id.into_inner()).await {
        Ok(profile) => HttpResponse::Ok().json(ApiResponse::ok(profile)),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn get_balance_handler(
    service: web::Data<AccountWebService>,
    user_id: web::ReqData<Uuid>,
) -> impl Responder {
    match service.get_balance(user_id.into_inner()).await {
        Ok((balance, frozen)) => HttpResponse::Ok().json(ApiResponse::ok(serde_json::json!({
            "balance": balance,
            "frozen_balance": frozen,
            "available": balance
        }))),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn deposit_handler(
    service: web::Data<AccountWebService>,
    user_id: web::ReqData<Uuid>,
    req: web::Json<DepositRequest>,
) -> impl Responder {
    match service.deposit(user_id.into_inner(), req.amount).await {
        Ok(tx_id) => HttpResponse::Ok().json(ApiResponse::ok(serde_json::json!({
            "transaction_id": tx_id,
            "amount": req.amount
        }))),
        Err(e) => HttpResponse::from_error(e),
    }
}

pub async fn get_transactions_handler(
    service: web::Data<AccountWebService>,
    user_id: web::ReqData<Uuid>,
    query: web::Query<TransactionQuery>,
) -> impl Responder {
    let page = query.page.unwrap_or(1);
    let per_page = std::cmp::min(query.per_page.unwrap_or(20), 100);

    match service
        .get_transactions(user_id.into_inner(), page, per_page)
        .await
    {
        Ok(transactions) => HttpResponse::Ok().json(ApiResponse::ok(transactions)),
        Err(e) => HttpResponse::from_error(e),
    }
}
