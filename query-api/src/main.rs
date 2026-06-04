use anyhow::Result;
use clap::Parser;
use std::sync::Arc;
use tracing_subscriber::{fmt, prelude::*, EnvFilter};
use warp::Filter;

use query_api::{
    dashboard::{CreateDashboardRequest, DashboardStorage},
    executor::{QueryExecutor, StoreClient},
    models::ApiResponse,
    parser::QueryParser,
};

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(short, long, default_value = "http://localhost:9090")]
    store_url: String,

    #[arg(short, long, default_value = "9091")]
    port: u16,

    #[arg(long, default_value = "dashboards.db")]
    dashboard_db: String,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::registry()
        .with(fmt::layer())
        .with(EnvFilter::from_default_env())
        .init();

    let args = Args::parse();

    let store_client = Arc::new(StoreClient::new(args.store_url));
    let executor = Arc::new(QueryExecutor::new(store_client));
    let parser = Arc::new(QueryParser::new());
    let dashboard_storage = Arc::new(DashboardStorage::new(&args.dashboard_db).await?);

    let executor_filter = warp::any().map(move || executor.clone());
    let parser_filter = warp::any().map(move || parser.clone());
    let dashboard_filter = warp::any().map(move || dashboard_storage.clone());

    let query_route = warp::get()
        .and(warp::path!("api" / "v1" / "query"))
        .and(warp::query::<QueryParams>())
        .and(executor_filter.clone())
        .and(parser_filter.clone())
        .and_then(
            |params: QueryParams, executor: Arc<QueryExecutor>, parser: Arc<QueryParser>| async move {
                match parser.parse(&params.query) {
                    Ok(query) => {
                        match executor.execute(query).await {
                            Ok(result) => {
                                let response = ApiResponse::success(result);
                                Ok::<_, warp::Rejection>(warp::reply::json(&response))
                            }
                            Err(e) => {
                                let response: ApiResponse<()> = ApiResponse::error(
                                    "execution_error".to_string(),
                                    e.to_string(),
                                );
                                Ok(warp::reply::json(&response))
                            }
                        }
                    }
                    Err(e) => {
                        let response: ApiResponse<()> = ApiResponse::error(
                            "parse_error".to_string(),
                            e.to_string(),
                        );
                        Ok(warp::reply::json(&response))
                    }
                }
            },
        );

    let health_route = warp::path!("health")
        .map(|| warp::reply::json(&serde_json::json!({ "status": "healthy" })));

    let dashboard_create = warp::path!("api" / "v1" / "dashboards")
        .and(warp::post())
        .and(warp::body::json())
        .and(dashboard_filter.clone())
        .and_then(|req: CreateDashboardRequest, storage: Arc<DashboardStorage>| async move {
            match storage.create_dashboard(req).await {
                Ok(dashboard) => Ok::<_, warp::Rejection>(warp::reply::json(&ApiResponse::success(dashboard))),
                Err(e) => Ok(warp::reply::json(
                    &ApiResponse::<()>::error("create_error".to_string(), e.to_string()),
                )),
            }
        });

    let dashboard_list = warp::path!("api" / "v1" / "dashboards")
        .and(warp::get())
        .and(dashboard_filter.clone())
        .and_then(|storage: Arc<DashboardStorage>| async move {
            match storage.list_dashboards().await {
                Ok(dashboards) => Ok::<_, warp::Rejection>(warp::reply::json(&ApiResponse::success(dashboards))),
                Err(e) => Ok(warp::reply::json(
                    &ApiResponse::<()>::error("list_error".to_string(), e.to_string()),
                )),
            }
        });

    let dashboard_get = warp::path!("api" / "v1" / "dashboards" / String)
        .and(warp::get())
        .and(dashboard_filter.clone())
        .and_then(|id: String, storage: Arc<DashboardStorage>| async move {
            match storage.get_dashboard(&id).await {
                Ok(Some(dashboard)) => Ok::<_, warp::Rejection>(warp::reply::json(&ApiResponse::success(dashboard))),
                Ok(None) => Ok(warp::reply::json(
                    &ApiResponse::<()>::error("not_found".to_string(), "Dashboard not found".to_string()),
                )),
                Err(e) => Ok(warp::reply::json(
                    &ApiResponse::<()>::error("get_error".to_string(), e.to_string()),
                )),
            }
        });

    let dashboard_delete = warp::path!("api" / "v1" / "dashboards" / String)
        .and(warp::delete())
        .and(dashboard_filter.clone())
        .and_then(|id: String, storage: Arc<DashboardStorage>| async move {
            match storage.delete_dashboard(&id).await {
                Ok(true) => Ok::<_, warp::Rejection>(warp::reply::json(&serde_json::json!({"status": "deleted"}))),
                Ok(false) => Ok(warp::reply::json(
                    &ApiResponse::<()>::error("not_found".to_string(), "Dashboard not found".to_string()),
                )),
                Err(e) => Ok(warp::reply::json(
                    &ApiResponse::<()>::error("delete_error".to_string(), e.to_string()),
                )),
            }
        });

    let dashboard_update = warp::path!("api" / "v1" / "dashboards" / String)
        .and(warp::put())
        .and(warp::body::json())
        .and(dashboard_filter)
        .and_then(
            |id: String, req: CreateDashboardRequest, storage: Arc<DashboardStorage>| async move {
                match storage.update_dashboard(&id, req).await {
                    Ok(Some(dashboard)) => Ok::<_, warp::Rejection>(warp::reply::json(&ApiResponse::success(dashboard))),
                    Ok(None) => Ok(warp::reply::json(
                        &ApiResponse::<()>::error("not_found".to_string(), "Dashboard not found".to_string()),
                    )),
                    Err(e) => Ok(warp::reply::json(
                        &ApiResponse::<()>::error("update_error".to_string(), e.to_string()),
                    )),
                }
            },
        );

    let static_files = warp::path("static")
        .and(warp::fs::dir("./frontend/build/static"));

    let index_route = warp::path::end()
        .and(warp::get())
        .and(warp::fs::file("./frontend/build/index.html"));

    let routes = query_route
        .or(health_route)
        .or(dashboard_create)
        .or(dashboard_list)
        .or(dashboard_get)
        .or(dashboard_delete)
        .or(dashboard_update)
        .or(static_files)
        .or(index_route)
        .with(warp::cors().allow_any_origin());

    tracing::info!("Starting query-api server on port {}", args.port);

    warp::serve(routes)
        .run(([0, 0, 0, 0], args.port))
        .await;

    Ok(())
}

#[derive(serde::Deserialize, Debug)]
struct QueryParams {
    query: String,
}
