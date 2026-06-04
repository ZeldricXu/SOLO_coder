use anyhow::Result;
use clap::Parser;
use tracing_subscriber::{fmt, prelude::*, EnvFilter};
use warp::Filter;

use stream_processing::{
    operators::{FilterOperator, MapOperator},
    pipeline::{build_default_pipeline, create_channel, EventSender, StreamEvent, DEFAULT_CHANNEL_CAPACITY},
    window::{WindowConfig, WindowAggregator},
    side_output::SideOutput,
};

use common::log::LogBatch;

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(short, long, default_value = "8080")]
    port: u16,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::registry()
        .with(fmt::layer())
        .with(EnvFilter::from_default_env())
        .init();

    let args = Args::parse();

    let (input_tx, input_rx) = create_channel(10000);
    let (metric_tx, metric_rx) = create_channel(1000);
    let (alert_tx, alert_rx) = create_channel(1000);

    let mut pipeline = build_default_pipeline("main-pipeline".to_string());
    pipeline.add_source(input_rx);
    pipeline.add_sink(metric_tx.clone());

    let filter = FilterOperator::new(|event| {
        matches!(event, StreamEvent::Log(_) | StreamEvent::Batch(_))
    });
    pipeline.add_operator(filter).await?;

    let mapper = MapOperator::new(|event| {
        if let StreamEvent::Batch(batch) = event {
            let mut events = Vec::new();
            for log in batch.events {
                events.push(StreamEvent::Log(log));
            }
            events
        } else {
            vec![event]
        }
    });
    pipeline.add_operator(mapper).await?;

    let error_rate_window_config = WindowConfig::new(
        300,
        60,
        "error_rate".to_string(),
        WindowAggregator::ErrorRate,
    );
    pipeline.add_window(error_rate_window_config, metric_tx.clone());

    let latency_window_config = WindowConfig::new(
        3600,
        300,
        "latency_p99".to_string(),
        WindowAggregator::LatencyPercentile(0.99),
    );
    pipeline.add_window(latency_window_config, metric_tx.clone());

    let alert_output = SideOutput::new("alerts".to_string(), DEFAULT_CHANNEL_CAPACITY);
    pipeline.add_side_output("alerts".to_string(), alert_output.clone());

    let input_tx_clone = input_tx.clone();
    let log_receive_route = warp::post()
        .and(warp::path!("api" / "v1" / "logs"))
        .and(warp::body::json())
        .map(move |batch: LogBatch| {
            let tx = input_tx_clone.clone();
            tokio::spawn(async move {
                let _ = tx.send(StreamEvent::Batch(batch)).await;
            });
            warp::reply::json(&serde_json::json!({ "status": "ok" }))
        });

    let health_route = warp::path!("health")
        .map(|| warp::reply::json(&serde_json::json!({ "status": "healthy" })));

    let routes = log_receive_route.or(health_route);

    tracing::info!("Starting stream-processing server on port {}", args.port);

    let pipeline_handle = tokio::spawn(async move {
        if let Err(e) = pipeline.run().await {
            tracing::error!("Pipeline error: {}", e);
        }
    });

    let server_handle = tokio::spawn(async move {
        warp::serve(routes)
            .run(([0, 0, 0, 0], args.port))
            .await;
    });

    tokio::select! {
        _ = pipeline_handle => {},
        _ = server_handle => {},
    }

    Ok(())
}
