use std::sync::Arc;
use warp::{Filter, Rejection, Reply};

use crate::service::PatternMiningService;
use common::log::LogBatch;

pub fn create_routes(
    service: Arc<PatternMiningService>,
) -> impl Filter<Extract = impl Reply, Error = Rejection> + Clone {
    let service_filter = warp::any().map(move || service.clone());

    let health = warp::path!("health")
        .and(warp::get())
        .map(|| warp::reply::json(&serde_json::json!({"status": "ok"})));

    let ingest = warp::path!("ingest")
        .and(warp::post())
        .and(warp::body::json())
        .and(service_filter.clone())
        .and_then(handle_ingest);

    let get_patterns = warp::path!("patterns")
        .and(warp::get())
        .and(warp::query::<std::collections::HashMap<String, String>>())
        .and(service_filter.clone())
        .and_then(handle_get_patterns);

    let get_service_patterns = warp::path!("patterns" / String)
        .and(warp::get())
        .and(service_filter.clone())
        .and_then(handle_get_service_patterns);

    let get_events = warp::path!("events")
        .and(warp::get())
        .and(warp::query::<std::collections::HashMap<String, String>>())
        .and(service_filter)
        .and_then(handle_get_events);

    health
        .or(ingest)
        .or(get_patterns)
        .or(get_service_patterns)
        .or(get_events)
        .with(warp::cors().allow_any_origin())
}

async fn handle_ingest(
    batch: LogBatch,
    service: Arc<PatternMiningService>,
) -> Result<impl Reply, Rejection> {
    if batch.events.is_empty() {
        return Ok(warp::reply::json(&serde_json::json!({
            "status": "success",
            "events_processed": 0,
            "pattern_changes": []
        })));
    }

    let service_name = batch.events[0].service.clone();
    let events = service.process_log_batch(&service_name, &batch.events);

    Ok(warp::reply::json(&serde_json::json!({
        "status": "success",
        "events_processed": batch.events.len(),
        "pattern_changes": events
    })))
}

async fn handle_get_patterns(
    params: std::collections::HashMap<String, String>,
    service: Arc<PatternMiningService>,
) -> Result<impl Reply, Rejection> {
    let patterns = service.get_all_patterns();
    Ok(warp::reply::json(&patterns))
}

async fn handle_get_service_patterns(
    service_name: String,
    service: Arc<PatternMiningService>,
) -> Result<impl Reply, Rejection> {
    let patterns = service.get_patterns(&service_name);
    Ok(warp::reply::json(&patterns))
}

async fn handle_get_events(
    params: std::collections::HashMap<String, String>,
    service: Arc<PatternMiningService>,
) -> Result<impl Reply, Rejection> {
    let limit = params
        .get("limit")
        .and_then(|l| l.parse().ok())
        .unwrap_or(100);

    let events = service.get_all_change_events(limit);
    Ok(warp::reply::json(&events))
}
