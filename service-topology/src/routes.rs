use std::sync::Arc;
use warp::{Filter, Rejection, Reply};

use crate::service::TopologyService;
use common::log::LogBatch;

pub fn create_routes(
    service: Arc<TopologyService>,
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

    let get_topology = warp::path!("topology")
        .and(warp::get())
        .and(service_filter.clone())
        .and_then(handle_get_topology);

    let get_service_topology = warp::path!("topology" / String)
        .and(warp::get())
        .and(service_filter.clone())
        .and_then(handle_get_service_topology);

    let get_traces = warp::path!("traces")
        .and(warp::get())
        .and(warp::query::<std::collections::HashMap<String, String>>())
        .and(service_filter)
        .and_then(handle_get_traces);

    health
        .or(ingest)
        .or(get_topology)
        .or(get_service_topology)
        .or(get_traces)
        .with(warp::cors().allow_any_origin())
}

async fn handle_ingest(
    batch: LogBatch,
    service: Arc<TopologyService>,
) -> Result<impl Reply, Rejection> {
    service.process_log_batch(&batch.events);

    Ok(warp::reply::json(&serde_json::json!({
        "status": "success",
        "events_processed": batch.events.len()
    })))
}

async fn handle_get_topology(
    service: Arc<TopologyService>,
) -> Result<impl Reply, Rejection> {
    let topology = service.get_topology();
    Ok(warp::reply::json(&topology))
}

async fn handle_get_service_topology(
    service_name: String,
    service: Arc<TopologyService>,
) -> Result<impl Reply, Rejection> {
    let edges = service.get_service_edges(&service_name);
    Ok(warp::reply::json(&edges))
}

async fn handle_get_traces(
    params: std::collections::HashMap<String, String>,
    service: Arc<TopologyService>,
) -> Result<impl Reply, Rejection> {
    let limit = params
        .get("limit")
        .and_then(|l| l.parse().ok())
        .unwrap_or(100);

    let traces = service.get_traces(limit);
    Ok(warp::reply::json(&traces))
}
