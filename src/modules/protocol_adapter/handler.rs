use axum::{
    extract::{Path, State, Query},
    Json,
};
use serde::Deserialize;
use std::sync::Arc;

use crate::common::error::AppResult;
use crate::common::context::RequestContext;
use crate::common::response::{ApiResponse, PaginationInfo};
use super::model::*;
use super::service::ProtocolAdapterService;

#[derive(Debug, Deserialize)]
pub struct PaginationQuery {
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_page_size")]
    pub page_size: u32,
    #[serde(default)]
    pub connection_id: Option<String>,
}

fn default_page() -> u32 { 1 }
fn default_page_size() -> u32 { 20 }

pub struct ProtocolAdapterHandler {
    pub service: Arc<ProtocolAdapterService>,
}

impl ProtocolAdapterHandler {
    pub fn new(service: Arc<ProtocolAdapterService>) -> Arc<Self> {
        Arc::new(Self { service })
    }

    pub async fn load_driver(
        State(_self): State<Arc<Self>>,
        Json(req): Json<DriverLoadRequest>,
    ) -> AppResult<Json<ApiResponse<DriverResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.load_driver(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn unload_driver(
        State(_self): State<Arc<Self>>,
        Path(driver_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.unload_driver(&ctx, &driver_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "unloaded" }))))
    }

    pub async fn get_driver(
        State(_self): State<Arc<Self>>,
        Path(driver_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<DriverResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.get_driver(&ctx, &driver_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn list_drivers(
        State(_self): State<Arc<Self>>,
        Query(query): Query<PaginationQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<DriverResponse>>>> {
        let (items, total) = _self.service.list_drivers(query.page, query.page_size).await?;
        let pagination = PaginationInfo::new(query.page, query.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn create_connection(
        State(_self): State<Arc<Self>>,
        Json(req): Json<ConnectionCreateRequest>,
    ) -> AppResult<Json<ApiResponse<ConnectionResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.create_connection(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn connect_device(
        State(_self): State<Arc<Self>>,
        Path(connection_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<ConnectionResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.connect_device(&ctx, &connection_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn disconnect_device(
        State(_self): State<Arc<Self>>,
        Path(connection_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<ConnectionResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.disconnect_device(&ctx, &connection_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_connection(
        State(_self): State<Arc<Self>>,
        Path(connection_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<ConnectionResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.get_connection(&ctx, &connection_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn list_connections(
        State(_self): State<Arc<Self>>,
        Query(query): Query<PaginationQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<ConnectionResponse>>>> {
        let (items, total) = _self.service.list_connections(query.page, query.page_size).await?;
        let pagination = PaginationInfo::new(query.page, query.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn delete_connection(
        State(_self): State<Arc<Self>>,
        Path(connection_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.delete_connection(&ctx, &connection_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "deleted" }))))
    }

    pub async fn create_data_point(
        State(_self): State<Arc<Self>>,
        Json(req): Json<DataPointCreateRequest>,
    ) -> AppResult<Json<ApiResponse<DataPointResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.create_data_point(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_data_point(
        State(_self): State<Arc<Self>>,
        Path(point_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<DataPointResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.get_data_point(&ctx, &point_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn list_data_points(
        State(_self): State<Arc<Self>>,
        Query(query): Query<PaginationQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<DataPointResponse>>>> {
        let (items, total) = _self.service.list_data_points(query.connection_id, query.page, query.page_size).await?;
        let pagination = PaginationInfo::new(query.page, query.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn delete_data_point(
        State(_self): State<Arc<Self>>,
        Path(point_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.delete_data_point(&ctx, &point_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "deleted" }))))
    }

    pub async fn create_conversion_rule(
        State(_self): State<Arc<Self>>,
        Json(req): Json<ConversionRuleCreateRequest>,
    ) -> AppResult<Json<ApiResponse<ConversionRuleResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.create_conversion_rule(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn list_conversion_rules(
        State(_self): State<Arc<Self>>,
        Query(query): Query<PaginationQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<ConversionRuleResponse>>>> {
        let (items, total) = _self.service.list_conversion_rules(query.page, query.page_size).await?;
        let pagination = PaginationInfo::new(query.page, query.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn delete_conversion_rule(
        State(_self): State<Arc<Self>>,
        Path(rule_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.delete_conversion_rule(&ctx, &rule_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "deleted" }))))
    }

    pub async fn create_forward_target(
        State(_self): State<Arc<Self>>,
        Json(req): Json<ForwardTargetCreateRequest>,
    ) -> AppResult<Json<ApiResponse<ForwardTargetResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.create_forward_target(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn list_forward_targets(
        State(_self): State<Arc<Self>>,
        Query(query): Query<PaginationQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<ForwardTargetResponse>>>> {
        let (items, total) = _self.service.list_forward_targets(query.page, query.page_size).await?;
        let pagination = PaginationInfo::new(query.page, query.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn delete_forward_target(
        State(_self): State<Arc<Self>>,
        Path(target_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.delete_forward_target(&ctx, &target_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "deleted" }))))
    }

    pub async fn collect_and_convert_data(
        State(_self): State<Arc<Self>>,
        Path(point_id): Path<String>,
        Json(raw_value): Json<serde_json::Value>,
    ) -> AppResult<Json<ApiResponse<ConvertedData>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.collect_and_convert_data(&ctx, &point_id, raw_value).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn forward_data(
        State(_self): State<Arc<Self>>,
        Json(data): Json<ConvertedData>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.forward_data(&ctx, data).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "forwarded" }))))
    }

    pub async fn get_metrics(
        State(_self): State<Arc<Self>>,
    ) -> AppResult<Json<ApiResponse<crate::common::metrics::StatsSnapshot>>> {
        let result = _self.service.get_metrics();
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn query_converted_data(
        State(_self): State<Arc<Self>>,
        Json(query): Json<DataQueryRequest>,
    ) -> AppResult<Json<ApiResponse<Vec<ConvertedData>>>> {
        let result = _self.service.get_converted_data(&query);
        Ok(Json(ApiResponse::success(result)))
    }
}

pub fn routes(service: Arc<ProtocolAdapterService>) -> axum::Router {
    let handler = ProtocolAdapterHandler::new(service);
    axum::Router::new()
        .route("/drivers", axum::routing::post(ProtocolAdapterHandler::load_driver).get(ProtocolAdapterHandler::list_drivers))
        .route("/drivers/:driver_id", axum::routing::get(ProtocolAdapterHandler::get_driver).delete(ProtocolAdapterHandler::unload_driver))
        .route("/connections", axum::routing::post(ProtocolAdapterHandler::create_connection).get(ProtocolAdapterHandler::list_connections))
        .route("/connections/:connection_id", axum::routing::get(ProtocolAdapterHandler::get_connection).delete(ProtocolAdapterHandler::delete_connection))
        .route("/connections/:connection_id/connect", axum::routing::post(ProtocolAdapterHandler::connect_device))
        .route("/connections/:connection_id/disconnect", axum::routing::post(ProtocolAdapterHandler::disconnect_device))
        .route("/data-points", axum::routing::post(ProtocolAdapterHandler::create_data_point).get(ProtocolAdapterHandler::list_data_points))
        .route("/data-points/:point_id", axum::routing::get(ProtocolAdapterHandler::get_data_point).delete(ProtocolAdapterHandler::delete_data_point))
        .route("/conversion-rules", axum::routing::post(ProtocolAdapterHandler::create_conversion_rule).get(ProtocolAdapterHandler::list_conversion_rules))
        .route("/conversion-rules/:rule_id", axum::routing::delete(ProtocolAdapterHandler::delete_conversion_rule))
        .route("/forward-targets", axum::routing::post(ProtocolAdapterHandler::create_forward_target).get(ProtocolAdapterHandler::list_forward_targets))
        .route("/forward-targets/:target_id", axum::routing::delete(ProtocolAdapterHandler::delete_forward_target))
        .route("/data/:point_id/collect", axum::routing::post(ProtocolAdapterHandler::collect_and_convert_data))
        .route("/data/forward", axum::routing::post(ProtocolAdapterHandler::forward_data))
        .route("/data/query", axum::routing::post(ProtocolAdapterHandler::query_converted_data))
        .route("/metrics", axum::routing::get(ProtocolAdapterHandler::get_metrics))
        .with_state(handler)
}
