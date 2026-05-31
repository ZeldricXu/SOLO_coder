use axum::{
    extract::{Path, State, Query, Json},
};
use serde::Deserialize;
use std::sync::Arc;

use crate::common::error::AppResult;
use crate::common::context::RequestContext;
use crate::common::response::{ApiResponse, PaginationInfo};
use super::model::{CacheWriteRequest, CacheWriteResponse, CacheStatus, CachedData, SyncResult, CacheQuery, NetworkStatus};
use super::service::OfflineCacheService;

#[derive(Debug, Deserialize)]
pub struct PaginationQuery {
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_page_size")]
    pub page_size: u32,
}

fn default_page() -> u32 { 1 }
fn default_page_size() -> u32 { 20 }

#[derive(Debug, Deserialize)]
pub struct ClearSyncedQuery {
    pub older_than_hours: Option<u64>,
}

#[derive(Debug, Deserialize)]
pub struct CacheQueryParams {
    pub sync_status: Option<String>,
    pub entity_type: Option<String>,
    pub entity_id: Option<String>,
    pub operation: Option<String>,
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_page_size")]
    pub page_size: u32,
}

pub struct OfflineCacheHandler {
    pub service: Arc<OfflineCacheService>,
}

impl OfflineCacheHandler {
    pub fn new(service: Arc<OfflineCacheService>) -> Arc<Self> {
        Arc::new(Self { service })
    }

    pub async fn write_data(
        State(_self): State<Arc<Self>>,
        Json(req): Json<CacheWriteRequest>,
    ) -> AppResult<Json<ApiResponse<CacheWriteResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.write_data(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_status(
        State(_self): State<Arc<Self>>,
    ) -> AppResult<Json<ApiResponse<CacheStatus>>> {
        let result = _self.service.get_cache_status().await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_network_status(
        State(_self): State<Arc<Self>>,
    ) -> AppResult<Json<ApiResponse<NetworkStatusResponse>>> {
        let status = _self.service.get_network_status().await;
        Ok(Json(ApiResponse::success(NetworkStatusResponse {
            status: status.clone(),
            status_str: status.as_str().to_string(),
        })))
    }

    pub async fn query_cache(
        State(_self): State<Arc<Self>>,
        Query(params): Query<CacheQueryParams>,
    ) -> AppResult<Json<ApiResponse<Vec<CachedData>>>> {
        let sync_status = match params.sync_status.as_deref() {
            Some("pending") => Some(super::model::SyncStatus::Pending),
            Some("syncing") => Some(super::model::SyncStatus::Syncing),
            Some("synced") => Some(super::model::SyncStatus::Synced),
            Some("failed") => Some(super::model::SyncStatus::Failed),
            Some("expired") => Some(super::model::SyncStatus::Expired),
            _ => None,
        };

        let query = CacheQuery {
            sync_status,
            entity_type: params.entity_type,
            entity_id: params.entity_id,
            operation: params.operation,
        };

        let (items, total) = _self.service.query_cache(query, params.page, params.page_size).await?;
        let pagination = PaginationInfo::new(params.page, params.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn get_cached_item(
        State(_self): State<Arc<Self>>,
        Path(cache_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<CachedData>>> {
        let query = CacheQuery {
            sync_status: None,
            entity_type: None,
            entity_id: None,
            operation: None,
        };

        let (items, _) = _self.service.query_cache(query, 1, 1000).await?;
        let item = items.into_iter()
            .find(|item| item.id == cache_id)
            .ok_or_else(|| crate::common::error::AppError::NotFound(format!("缓存数据不存在: {}", cache_id)))?;

        Ok(Json(ApiResponse::success(item)))
    }

    pub async fn trigger_sync(
        State(_self): State<Arc<Self>>,
    ) -> AppResult<Json<ApiResponse<Vec<SyncResult>>>> {
        let ctx = RequestContext::new_with_random();
        let results = _self.service.force_sync(&ctx).await?;
        Ok(Json(ApiResponse::success(results)))
    }

    pub async fn clear_synced(
        State(_self): State<Arc<Self>>,
        Query(params): Query<ClearSyncedQuery>,
    ) -> AppResult<Json<ApiResponse<ClearSyncedResponse>>> {
        let ctx = RequestContext::new_with_random();
        let removed = _self.service.clear_synced(&ctx, params.older_than_hours).await?;
        Ok(Json(ApiResponse::success(ClearSyncedResponse {
            removed_count: removed,
        })))
    }

    pub async fn get_metrics(
        State(_self): State<Arc<Self>>,
    ) -> AppResult<Json<ApiResponse<crate::common::metrics::StatsSnapshot>>> {
        let metrics = _self.service.get_metrics();
        Ok(Json(ApiResponse::success(metrics)))
    }
}

#[derive(Debug, serde::Serialize, serde::Deserialize)]
pub struct NetworkStatusResponse {
    pub status: NetworkStatus,
    pub status_str: String,
}

#[derive(Debug, serde::Serialize, serde::Deserialize)]
pub struct ClearSyncedResponse {
    pub removed_count: u64,
}

pub fn routes(service: Arc<OfflineCacheService>) -> axum::Router {
    let handler = OfflineCacheHandler::new(service);
    axum::Router::new()
        .route("/cache", axum::routing::post(OfflineCacheHandler::write_data).get(OfflineCacheHandler::query_cache))
        .route("/cache/status", axum::routing::get(OfflineCacheHandler::get_status))
        .route("/cache/network", axum::routing::get(OfflineCacheHandler::get_network_status))
        .route("/cache/metrics", axum::routing::get(OfflineCacheHandler::get_metrics))
        .route("/cache/sync", axum::routing::post(OfflineCacheHandler::trigger_sync))
        .route("/cache/clear-synced", axum::routing::post(OfflineCacheHandler::clear_synced))
        .route("/cache/:cache_id", axum::routing::get(OfflineCacheHandler::get_cached_item))
        .with_state(handler)
}
