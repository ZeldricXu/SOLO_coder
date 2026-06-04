use common::error::AppResult;
use models::AuctionListItem;
use redis::aio::MultiplexedConnection;
use redis::AsyncCommands;
use sqlx::PgPool;
use std::sync::Arc;
use tracing::info;
use uuid::Uuid;

const RANKING_WINDOW_SECONDS: i64 = 3600;
const HOT_VIEW_KEY: &str = "ranking:hot:views";
const HOT_BID_KEY: &str = "ranking:hot:bids";

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct HotRankingItem {
    pub auction_id: Uuid,
    pub title: String,
    pub current_price: rust_decimal::Decimal,
    pub primary_image: Option<String>,
    pub score: i64,
    pub rank: i32,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct HotRankings {
    pub most_viewed: Vec<HotRankingItem>,
    pub most_bidded: Vec<HotRankingItem>,
}

pub struct HotRankingService {
    redis: MultiplexedConnection,
    pg_pool: PgPool,
}

impl HotRankingService {
    pub fn new(redis: MultiplexedConnection, pg_pool: PgPool) -> Self {
        Self { redis, pg_pool }
    }

    pub async fn record_view(&self, auction_id: Uuid) -> AppResult<()> {
        let mut con = self.redis.clone();
        let member = auction_id.to_string();

        let _: () = con.zincr(HOT_VIEW_KEY, &member, 1).await?;
        let _: () = con.expire(HOT_VIEW_KEY, RANKING_WINDOW_SECONDS).await?;

        info!(auction_id = %auction_id, "View recorded for hot ranking");
        Ok(())
    }

    pub async fn record_bid(&self, auction_id: Uuid) -> AppResult<()> {
        let mut con = self.redis.clone();
        let member = auction_id.to_string();

        let _: () = con.zincr(HOT_BID_KEY, &member, 1).await?;
        let _: () = con.expire(HOT_BID_KEY, RANKING_WINDOW_SECONDS).await?;

        info!(auction_id = %auction_id, "Bid recorded for hot ranking");
        Ok(())
    }

    pub async fn get_hot_rankings(&self, limit: i64) -> AppResult<HotRankings> {
        let mut con = self.redis.clone();
        let limit = limit as isize;

        let viewed_ids: Vec<(String, f64)> = con
            .zrevrange_withscores(HOT_VIEW_KEY, 0, limit - 1)
            .await?;

        let bid_ids: Vec<(String, f64)> = con
            .zrevrange_withscores(HOT_BID_KEY, 0, limit - 1)
            .await?;

        let most_viewed = self.populate_ranking_items(&viewed_ids).await?;
        let most_bidded = self.populate_ranking_items(&bid_ids).await?;

        Ok(HotRankings {
            most_viewed,
            most_bidded,
        })
    }

    async fn populate_ranking_items(
        &self,
        ranked: &[(String, f64)],
    ) -> AppResult<Vec<HotRankingItem>> {
        if ranked.is_empty() {
            return Ok(Vec::new());
        }

        let ids: Vec<Uuid> = ranked
            .iter()
            .filter_map(|(id, _)| Uuid::parse_str(id).ok())
            .collect();

        if ids.is_empty() {
            return Ok(Vec::new());
        }

        let placeholders: Vec<String> = (0..ids.len())
            .map(|i| format!("${}", i + 1))
            .collect();

        let sql = format!(
            r#"
            SELECT
                a.id, a.title, a.current_price,
                (
                    SELECT file_path FROM auction_media am
                    WHERE am.auction_id = a.id AND am.is_primary = true
                    LIMIT 1
                ) as primary_image
            FROM auctions a
            WHERE a.id IN ({})
              AND a.status = 'active'
            "#,
            placeholders.join(",")
        );

        let mut query = sqlx::query_as::<_, (Uuid, String, rust_decimal::Decimal, Option<String>)>(&sql);
        for id in &ids {
            query = query.bind(id);
        }

        let items: Vec<(Uuid, String, rust_decimal::Decimal, Option<String>)> =
            query.fetch_all(&self.pg_pool).await?;

        let mut result = Vec::new();
        for (idx, (id, score)) in ranked.iter().enumerate() {
            let auction_id = Uuid::parse_str(id).unwrap_or_default();
            if let Some((_, title, price, image)) = items.iter().find(|(item_id, _, _, _)| *item_id == auction_id) {
                result.push(HotRankingItem {
                    auction_id,
                    title: title.clone(),
                    current_price: *price,
                    primary_image: image.clone(),
                    score: *score as i64,
                    rank: (idx + 1) as i32,
                });
            }
        }

        Ok(result)
    }
}

impl Clone for HotRankingService {
    fn clone(&self) -> Self {
        Self {
            redis: self.redis.clone(),
            pg_pool: self.pg_pool.clone(),
        }
    }
}
