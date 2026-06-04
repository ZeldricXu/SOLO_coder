use std::sync::Arc;
use warp::{Filter, Rejection, Reply, reply::WithStatus};

use crate::manager::AlertManager;
use crate::storage::AlertStorage;
use common::alert::Alert;

pub fn create_routes(
    alert_manager: Arc<AlertManager>,
    storage: Arc<AlertStorage>,
) -> impl Filter<Extract = impl Reply, Error = Rejection> + Clone {
    let alert_manager_filter = warp::any().map(move || alert_manager.clone());
    let storage_filter = warp::any().map(move || storage.clone());

    let health = warp::path!("health")
        .and(warp::get())
        .map(|| warp::reply::json(&serde_json::json!({"status": "ok"})));

    let post_alert = warp::path!("alerts")
        .and(warp::post())
        .and(warp::body::json())
        .and(alert_manager_filter.clone())
        .and_then(handle_post_alert);

    let get_alerts = warp::path!("alerts")
        .and(warp::get())
        .and(warp::query::<std::collections::HashMap<String, String>>())
        .and(storage_filter)
        .and_then(handle_get_alerts);

    let get_incidents = warp::path!("incidents")
        .and(warp::get())
        .and(alert_manager_filter.clone())
        .and_then(handle_get_incidents);

    let resolve_incident = warp::path!("incidents" / String / "resolve")
        .and(warp::post())
        .and(alert_manager_filter)
        .and_then(handle_resolve_incident);

    health
        .or(post_alert)
        .or(get_alerts)
        .or(get_incidents)
        .or(resolve_incident)
        .with(warp::cors().allow_any_origin())
}

async fn handle_post_alert(
    alert: Alert,
    alert_manager: Arc<AlertManager>,
) -> Result<WithStatus<warp::reply::Json>, Rejection> {
    match alert_manager.process_alert(alert).await {
        Ok(_) => Ok(warp::reply::with_status(
            warp::reply::json(&serde_json::json!({
                "status": "success",
                "message": "Alert processed"
            })),
            warp::http::StatusCode::OK,
        )),
        Err(e) => Ok(warp::reply::with_status(
            warp::reply::json(&serde_json::json!({
                "status": "error",
                "message": e.to_string()
            })),
            warp::http::StatusCode::INTERNAL_SERVER_ERROR,
        )),
    }
}

async fn handle_get_alerts(
    params: std::collections::HashMap<String, String>,
    storage: Arc<AlertStorage>,
) -> Result<WithStatus<warp::reply::Json>, Rejection> {
    let limit = params
        .get("limit")
        .and_then(|l| l.parse().ok())
        .unwrap_or(100);

    let active_only = params.get("active") == Some(&"true".to_string());

    let alerts = if active_only {
        storage.get_active_alerts().await
    } else {
        storage.get_alerts(limit).await
    };

    match alerts {
        Ok(alerts) => Ok(warp::reply::with_status(
            warp::reply::json(&alerts),
            warp::http::StatusCode::OK,
        )),
        Err(e) => Ok(warp::reply::with_status(
            warp::reply::json(&serde_json::json!({
                "status": "error",
                "message": e.to_string()
            })),
            warp::http::StatusCode::INTERNAL_SERVER_ERROR,
        )),
    }
}

async fn handle_get_incidents(
    alert_manager: Arc<AlertManager>,
) -> Result<impl Reply, Rejection> {
    let incidents = alert_manager.get_incidents().await;
    Ok(warp::reply::json(&incidents))
}

async fn handle_resolve_incident(
    incident_id: String,
    alert_manager: Arc<AlertManager>,
) -> Result<WithStatus<warp::reply::Json>, Rejection> {
    match alert_manager.resolve_incident(&incident_id).await {
        Ok(_) => Ok(warp::reply::with_status(
            warp::reply::json(&serde_json::json!({
                "status": "success",
                "message": "Incident resolved"
            })),
            warp::http::StatusCode::OK,
        )),
        Err(e) => Ok(warp::reply::with_status(
            warp::reply::json(&serde_json::json!({
                "status": "error",
                "message": e.to_string()
            })),
            warp::http::StatusCode::INTERNAL_SERVER_ERROR,
        )),
    }
}
