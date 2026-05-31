use axum::{
    extract::{Path, State, Query},
    Json,
};
use serde::Deserialize;
use std::sync::Arc;

use crate::common::error::AppResult;
use crate::common::context::RequestContext;
use crate::common::response::{ApiResponse, PaginationInfo};
use super::model::{
    CreateRuleRequest, UpdateRuleRequest, DataPoint, RuleResponse,
    TriggerEvent, RuleTriggerHistory, EvaluationResult, RuleStatus,
    CircuitBreakerStatus, RecoveryStats, PendingRecovery,
};
use super::service::RuleEngineService;

#[derive(Debug, Deserialize)]
pub struct PaginationQuery {
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_page_size")]
    pub page_size: u32,
}

#[derive(Debug, Deserialize)]
pub struct ListRulesQuery {
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_page_size")]
    pub page_size: u32,
    pub source: Option<String>,
    pub status: Option<RuleStatus>,
}

#[derive(Debug, Deserialize)]
pub struct HistoryQuery {
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_page_size")]
    pub page_size: u32,
    pub rule_id: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct RecoveryStatsQuery {
    pub rule_id: Option<String>,
}

fn default_page() -> u32 { 1 }
fn default_page_size() -> u32 { 20 }

pub struct RuleEngineHandler {
    pub service: Arc<RuleEngineService>,
}

impl RuleEngineHandler {
    pub fn new(service: Arc<RuleEngineService>) -> Arc<Self> {
        Arc::new(Self { service })
    }

    pub async fn create_rule(
        State(_self): State<Arc<Self>>,
        Json(req): Json<CreateRuleRequest>,
    ) -> AppResult<Json<ApiResponse<RuleResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.create_rule(&ctx, req).await?;
        Ok(Json(ApiResponse::created(result)))
    }

    pub async fn get_rule(
        State(_self): State<Arc<Self>>,
        Path(rule_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<RuleResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.get_rule(&ctx, &rule_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn list_rules(
        State(_self): State<Arc<Self>>,
        Query(query): Query<ListRulesQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<RuleResponse>>>> {
        let (items, total) = _self.service.list_rules(
            query.page,
            query.page_size,
            query.source,
            query.status,
        ).await?;
        let pagination = PaginationInfo::new(query.page, query.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn update_rule(
        State(_self): State<Arc<Self>>,
        Path(rule_id): Path<String>,
        Json(req): Json<UpdateRuleRequest>,
    ) -> AppResult<Json<ApiResponse<RuleResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.update_rule(&ctx, &rule_id, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn enable_rule(
        State(_self): State<Arc<Self>>,
        Path(rule_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<RuleResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.enable_rule(&ctx, &rule_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn disable_rule(
        State(_self): State<Arc<Self>>,
        Path(rule_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<RuleResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.disable_rule(&ctx, &rule_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn delete_rule(
        State(_self): State<Arc<Self>>,
        Path(rule_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.delete_rule(&ctx, &rule_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "deleted" }))))
    }

    pub async fn process_data(
        State(_self): State<Arc<Self>>,
        Json(req): Json<DataPoint>,
    ) -> AppResult<Json<ApiResponse<Vec<TriggerEvent>>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.process_data_point(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn evaluate_data(
        State(_self): State<Arc<Self>>,
        Json(req): Json<DataPoint>,
    ) -> AppResult<Json<ApiResponse<Vec<EvaluationResult>>>> {
        let result = _self.service.evaluate_data_point(req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_trigger_history(
        State(_self): State<Arc<Self>>,
        Query(query): Query<HistoryQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<RuleTriggerHistory>>>> {
        let (items, total) = _self.service.get_trigger_history(
            query.rule_id,
            query.page,
            query.page_size,
        ).await?;
        let pagination = PaginationInfo::new(query.page, query.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn get_circuit_breaker_status(
        State(_self): State<Arc<Self>>,
        Path(rule_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<CircuitBreakerStatus>>> {
        let result = _self.service.get_circuit_breaker_status(&rule_id)?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_all_circuit_breakers(
        State(_self): State<Arc<Self>>,
    ) -> AppResult<Json<ApiResponse<Vec<CircuitBreakerStatus>>>> {
        let result = _self.service.get_all_circuit_breaker_statuses();
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn reset_circuit_breaker(
        State(_self): State<Arc<Self>>,
        Path(rule_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        _self.service.reset_circuit_breaker(&rule_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "reset" }))))
    }

    pub async fn get_recovery_stats(
        State(_self): State<Arc<Self>>,
        Query(query): Query<RecoveryStatsQuery>,
    ) -> AppResult<Json<ApiResponse<RecoveryStats>>> {
        let result = _self.service.get_recovery_stats(query.rule_id.as_deref());
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_pending_recoveries(
        State(_self): State<Arc<Self>>,
        Query(query): Query<RecoveryStatsQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<PendingRecovery>>>> {
        let result = _self.service.get_pending_recoveries(query.rule_id.as_deref());
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_metrics(
        State(_self): State<Arc<Self>>,
    ) -> AppResult<Json<ApiResponse<crate::common::metrics::StatsSnapshot>>> {
        let metrics = _self.service.get_metrics();
        Ok(Json(ApiResponse::success(metrics)))
    }

    pub async fn get_stats(
        State(_self): State<Arc<Self>>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let stats = _self.service.get_stats();
        Ok(Json(ApiResponse::success(stats)))
    }
}

pub fn routes(service: Arc<RuleEngineService>) -> axum::Router {
    let handler = RuleEngineHandler::new(service);
    axum::Router::new()
        .route("/rules", axum::routing::post(RuleEngineHandler::create_rule).get(RuleEngineHandler::list_rules))
        .route("/rules/:rule_id", axum::routing::get(RuleEngineHandler::get_rule).put(RuleEngineHandler::update_rule).delete(RuleEngineHandler::delete_rule))
        .route("/rules/:rule_id/enable", axum::routing::post(RuleEngineHandler::enable_rule))
        .route("/rules/:rule_id/disable", axum::routing::post(RuleEngineHandler::disable_rule))
        .route("/rules/:rule_id/circuit-breaker", axum::routing::get(RuleEngineHandler::get_circuit_breaker_status).post(RuleEngineHandler::reset_circuit_breaker))
        .route("/circuit-breakers", axum::routing::get(RuleEngineHandler::get_all_circuit_breakers))
        .route("/recovery/stats", axum::routing::get(RuleEngineHandler::get_recovery_stats))
        .route("/recovery/pending", axum::routing::get(RuleEngineHandler::get_pending_recoveries))
        .route("/data/process", axum::routing::post(RuleEngineHandler::process_data))
        .route("/data/evaluate", axum::routing::post(RuleEngineHandler::evaluate_data))
        .route("/history/triggers", axum::routing::get(RuleEngineHandler::get_trigger_history))
        .route("/metrics", axum::routing::get(RuleEngineHandler::get_metrics))
        .route("/stats", axum::routing::get(RuleEngineHandler::get_stats))
        .with_state(handler)
}
