use actix_web::{
    web, HttpResponse,
    body::BoxBody,
};
use chrono::Utc;
use futures_util::stream::Stream;
use models::AuctionEvent;
use mongodb::Collection;
use rust_decimal::Decimal;
use serde::Serialize;
use shared::{AuctionStatus, PriceUpdate};
use std::pin::Pin;
use tokio::sync::broadcast;
use tracing::info;
use uuid::Uuid;

pub type SseStream = Pin<Box<dyn Stream<Item = Result<web::Bytes, std::io::Error>> + Send>>;

#[derive(Debug, Clone, Serialize)]
struct SseMessage<T: Serialize> {
    event: String,
    data: T,
}

pub struct SseHandler {
    price_tx: broadcast::Sender<PriceUpdate>,
    mongo_events: Collection<AuctionEvent>,
}

impl SseHandler {
    pub fn new(
        price_tx: broadcast::Sender<PriceUpdate>,
        mongo_events: Collection<AuctionEvent>,
    ) -> Self {
        Self {
            price_tx,
            mongo_events,
        }
    }

    pub async fn stream_all_prices(&self) -> HttpResponse<BoxBody> {
        let mut rx = self.price_tx.subscribe();

        info!("New SSE client connected for all price updates");

        let stream: SseStream = Box::pin(async_stream::stream! {
            let heartbeat = tokio::time::interval(tokio::time::Duration::from_secs(30));
            tokio::pin!(heartbeat);

            loop {
                tokio::select! {
                    result = rx.recv() => {
                        match result {
                            Ok(update) => {
                                let event = format!(
                                    "event: price_update\ndata: {}\n\n",
                                    serde_json::to_string(&update).unwrap_or_default()
                                );
                                yield Ok(web::Bytes::from(event));
                            }
                            Err(e) => {
                                tracing::warn!(error = %e, "SSE stream error");
                                break;
                            }
                        }
                    }
                    _ = heartbeat.tick() => {
                        let ping = format!("event: heartbeat\ndata: {{\"timestamp\":{}}}\n\n", Utc::now().timestamp());
                        yield Ok(web::Bytes::from(ping));
                    }
                }
            }
        });

        HttpResponse::Ok()
            .insert_header(("Content-Type", "text/event-stream"))
            .insert_header(("Cache-Control", "no-cache"))
            .insert_header(("Connection", "keep-alive"))
            .insert_header(("X-Accel-Buffering", "no"))
            .streaming(stream)
    }

    pub async fn stream_auction_price(&self, auction_id: Uuid) -> HttpResponse<BoxBody> {
        let mut rx = self.price_tx.subscribe();

        info!(auction_id = %auction_id, "New SSE client connected for specific auction");

        let stream: SseStream = Box::pin(async_stream::stream! {
            let heartbeat = tokio::time::interval(tokio::time::Duration::from_secs(30));
            tokio::pin!(heartbeat);

            loop {
                tokio::select! {
                    result = rx.recv() => {
                        match result {
                            Ok(update) if update.auction_id == auction_id => {
                                let event = format!(
                                    "event: price_update\ndata: {}\n\n",
                                    serde_json::to_string(&update).unwrap_or_default()
                                );
                                yield Ok(web::Bytes::from(event));
                            }
                            Err(e) => {
                                tracing::warn!(error = %e, "SSE stream error");
                                break;
                            }
                            _ => {}
                        }
                    }
                    _ = heartbeat.tick() => {
                        let ping = format!("event: heartbeat\ndata: {{\"timestamp\":{}}}\n\n", Utc::now().timestamp());
                        yield Ok(web::Bytes::from(ping));
                    }
                }
            }
        });

        HttpResponse::Ok()
            .insert_header(("Content-Type", "text/event-stream"))
            .insert_header(("Cache-Control", "no-cache"))
            .insert_header(("Connection", "keep-alive"))
            .insert_header(("X-Accel-Buffering", "no"))
            .streaming(stream)
    }

    pub async fn stream_category_prices(&self, category_ids: Vec<Uuid>, pg_pool: sqlx::PgPool) -> HttpResponse<BoxBody> {
        let mut rx = self.price_tx.subscribe();

        info!(?category_ids, "New SSE client connected for category price updates");

        let stream: SseStream = Box::pin(async_stream::stream! {
            let heartbeat = tokio::time::interval(tokio::time::Duration::from_secs(30));
            tokio::pin!(heartbeat);

            loop {
                tokio::select! {
                    result = rx.recv() => {
                        match result {
                            Ok(update) => {
                                let auction_category: Option<Option<Uuid>> = sqlx::query_scalar(
                                    "SELECT category_id FROM auctions WHERE id = $1"
                                )
                                .bind(update.auction_id)
                                .fetch_optional(&pg_pool)
                                .await
                                .unwrap_or(None);

                                let matches = match auction_category.flatten() {
                                    Some(cat_id) => category_ids.contains(&cat_id),
                                    None => false,
                                };

                                if matches {
                                    let event = format!(
                                        "event: price_update\ndata: {}\n\n",
                                        serde_json::to_string(&update).unwrap_or_default()
                                    );
                                    yield Ok(web::Bytes::from(event));
                                }
                            }
                            Err(e) => {
                                tracing::warn!(error = %e, "SSE stream error");
                                break;
                            }
                        }
                    }
                    _ = heartbeat.tick() => {
                        let ping = format!("event: heartbeat\ndata: {{\"timestamp\":{}}}\n\n", Utc::now().timestamp());
                        yield Ok(web::Bytes::from(ping));
                    }
                }
            }
        });

        HttpResponse::Ok()
            .insert_header(("Content-Type", "text/event-stream"))
            .insert_header(("Cache-Control", "no-cache"))
            .insert_header(("Connection", "keep-alive"))
            .insert_header(("X-Accel-Buffering", "no"))
            .streaming(stream)
    }
}

impl Clone for SseHandler {
    fn clone(&self) -> Self {
        Self {
            price_tx: self.price_tx.clone(),
            mongo_events: self.mongo_events.clone(),
        }
    }
}

pub fn create_price_update(auction_id: Uuid, price: Decimal, status: AuctionStatus) -> PriceUpdate {
    PriceUpdate {
        auction_id,
        current_price: price,
        timestamp: Utc::now(),
        status,
        price_history: Vec::new(),
        price_forecast: Vec::new(),
    }
}
