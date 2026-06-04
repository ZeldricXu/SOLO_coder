use anyhow::Result;
use clap::Parser;
use std::path::PathBuf;
use std::sync::Arc;
use tracing_subscriber::{fmt, prelude::*, EnvFilter};
use warp::Filter;

use timeseries_store::{storage::TimeSeriesStore, query::QueryExecutor};

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(short, long, default_value = "./data")]
    data_dir: PathBuf,

    #[arg(short, long, default_value = "9090")]
    port: u16,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::registry()
        .with(fmt::layer())
        .with(EnvFilter::from_default_env())
        .init();

    let args = Args::parse();

    std::fs::create_dir_all(&args.data_dir)?;
    tracing::info!("Using data directory: {}", args.data_dir.display());

    let store = Arc::new(TimeSeriesStore::new(args.data_dir.clone()).with_compaction());
    let store_clone = store.clone();

    tokio::spawn(async move {
        store_clone.run_flush_task().await;
    });

    if let Some(compaction_manager) = store.compaction_manager() {
        let compaction_manager_clone = compaction_manager.clone();
        tokio::spawn(async move {
            compaction_manager_clone.run_periodic_compaction().await;
        });
    }

    let query_executor = Arc::new(QueryExecutor::new());

    let store_filter = warp::any().map(move || store.clone());
    let executor_filter = warp::any().map(move || query_executor.clone());

    let insert_route = warp::post()
        .and(warp::path!("api" / "v1" / "metrics"))
        .and(warp::body::json())
        .and(store_filter.clone())
        .and_then(|series: common::metrics::TimeSeries, store: Arc<TimeSeriesStore>| async move {
            match store.insert(&series).await {
                Ok(_) => Ok::<_, warp::Rejection>(warp::reply::json(&serde_json::json!({ "status": "ok" }))),
                Err(e) => Ok::<_, warp::Rejection>(warp::reply::json(&serde_json::json!({ "error": e.to_string() }))),
            }
        });

    let query_route = warp::get()
        .and(warp::path!("api" / "v1" / "query"))
        .and(warp::query::<QueryParams>())
        .and(store_filter.clone())
        .and(executor_filter.clone())
        .and_then(|params: QueryParams, store: Arc<TimeSeriesStore>, executor: Arc<QueryExecutor>| async move {
            use chrono::{Duration, Utc};

            let end = Utc::now();
            let start = end - Duration::hours(params.time_range_h.unwrap_or(1));

            let labels = common::metrics::Labels::new();
            match store.query(&params.metric, &labels, start, end).await {
                Ok(results) => Ok::<_, warp::Rejection>(warp::reply::json(&results)),
                Err(e) => Ok::<_, warp::Rejection>(warp::reply::json(&serde_json::json!({ "error": e.to_string() }))),
            }
        });

    let health_route = warp::path!("health")
        .map(|| warp::reply::json(&serde_json::json!({ "status": "healthy" })));

    let routes = insert_route.or(query_route).or(health_route);

    tracing::info!("Starting timeseries-store server on port {}", args.port);

    warp::serve(routes)
        .run(([0, 0, 0, 0], args.port))
        .await;

    Ok(())
}

#[derive(serde::Deserialize, Debug)]
struct QueryParams {
    metric: String,
    time_range_h: Option<i64>,
}
