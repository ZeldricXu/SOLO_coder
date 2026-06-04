use chrono::{Duration, Utc};
use common::error::AppResult;
use dashmap::DashMap;
use serde_json::json;
use sqlx::PgPool;
use std::sync::Arc;
use tracing::{info, warn};
use uuid::Uuid;

#[derive(Debug, Clone)]
struct BidRecord {
    auction_id: Uuid,
    timestamp: chrono::DateTime<Utc>,
}

pub struct FraudDetectionService {
    pub pg_pool: PgPool,
    recent_bids: Arc<DashMap<Uuid, Vec<BidRecord>>>,
    max_bids_per_minute: usize,
    max_bids_per_hour: usize,
    min_interval_between_bids: std::time::Duration,
}

impl FraudDetectionService {
    pub fn new(pg_pool: PgPool) -> Self {
        Self {
            pg_pool,
            recent_bids: Arc::new(DashMap::new()),
            max_bids_per_minute: 10,
            max_bids_per_hour: 30,
            min_interval_between_bids: std::time::Duration::from_secs(1),
        }
    }

    pub async fn check_suspicious_bid(&self, user_id: Uuid, auction_id: Uuid) -> AppResult<(bool, f64, Vec<String>)> {
        let mut flags = Vec::new();
        let mut risk_score = 0.0;

        let now = Utc::now();
        let record = BidRecord {
            auction_id,
            timestamp: now,
        };

        let mut entry = self.recent_bids.entry(user_id).or_default();
        entry.push(record.clone());

        let one_minute_ago = now - Duration::minutes(1);
        let one_hour_ago = now - Duration::hours(1);

        let bids_last_minute: Vec<_> = entry
            .iter()
            .filter(|b| b.timestamp >= one_minute_ago)
            .cloned()
            .collect();

        let bids_last_hour: Vec<_> = entry
            .iter()
            .filter(|b| b.timestamp >= one_hour_ago)
            .cloned()
            .collect();

        if bids_last_minute.len() > self.max_bids_per_minute {
            flags.push(format!(
                "一分钟内出价 {} 次，超过阈值 {}",
                bids_last_minute.len(),
                self.max_bids_per_minute
            ));
            risk_score += 0.3;
        }

        if bids_last_hour.len() > self.max_bids_per_hour {
            flags.push(format!(
                "一小时内出价 {} 次，超过阈值 {}",
                bids_last_hour.len(),
                self.max_bids_per_hour
            ));
            risk_score += 0.2;
        }

        let unique_auctions: std::collections::HashSet<Uuid> =
            bids_last_hour.iter().map(|b| b.auction_id).collect();

        if unique_auctions.len() >= 5 && bids_last_hour.len() >= 10 {
            flags.push(format!(
                "短时间内跨 {} 个商品出价 {} 次，疑似脚本行为",
                unique_auctions.len(),
                bids_last_hour.len()
            ));
            risk_score += 0.4;
        }

        if bids_last_minute.len() >= 2 {
            let intervals: Vec<i64> = bids_last_minute
                .windows(2)
                .map(|w| (w[1].timestamp - w[0].timestamp).num_milliseconds())
                .collect();

            if let Some(avg_interval) = intervals.iter().sum::<i64>().checked_div(intervals.len() as i64) {
                if avg_interval < self.min_interval_between_bids.as_millis() as i64 {
                    flags.push(format!(
                        "出价间隔异常，平均间隔 {}ms",
                        avg_interval
                    ));
                    risk_score += 0.2;
                }

                if intervals.len() >= 3 {
                    let variance = intervals
                        .iter()
                        .map(|i| (*i - avg_interval).pow(2))
                        .sum::<i64>()
                        / intervals.len() as i64;

                    if variance < 10000 {
                        flags.push("出价间隔高度规律，疑似自动化脚本".into());
                        risk_score += 0.3;
                    }
                }
            }
        }

        let _ = self
            .check_new_account_risk(user_id)
            .await
            .map(|(is_new, age_days)| {
                if is_new {
                    flags.push(format!("新账户（注册 {} 天）高频出价", age_days));
                    risk_score += 0.15;
                }
            });

        let is_suspicious = risk_score >= 0.5;

        if is_suspicious {
            warn!(
                user_id = %user_id,
                risk_score = risk_score,
                flags = ?flags,
                "Suspicious bidding activity detected"
            );

            self.record_risk_event(
                user_id,
                Some(auction_id),
                "suspicious_bidding",
                if risk_score >= 0.8 { "high" } else { "medium" },
                &flags.join("; "),
                json!({
                    "bids_last_minute": bids_last_minute.len(),
                    "bids_last_hour": bids_last_hour.len(),
                    "unique_auctions": unique_auctions.len(),
                    "risk_score": risk_score
                }),
            )
            .await
            .ok();
        }

        entry.retain(|b| b.timestamp >= one_hour_ago);
        if entry.is_empty() {
            self.recent_bids.remove(&user_id);
        }

        Ok((is_suspicious, risk_score, flags))
    }

    async fn check_new_account_risk(&self, user_id: Uuid) -> AppResult<(bool, i64)> {
        let created_at: Option<chrono::DateTime<Utc>> = sqlx::query_scalar(
            "SELECT created_at FROM users WHERE id = $1"
        )
        .bind(user_id)
        .fetch_optional(&self.pg_pool)
        .await?;

        if let Some(created) = created_at {
            let age_days = (Utc::now() - created).num_days();
            Ok((age_days < 7, age_days))
        } else {
            Ok((false, 0))
        }
    }

    async fn record_risk_event(
        &self,
        user_id: Uuid,
        auction_id: Option<Uuid>,
        event_type: &str,
        severity: &str,
        description: &str,
        metadata: serde_json::Value,
    ) -> AppResult<Uuid> {
        let event_id = Uuid::new_v4();

        sqlx::query(
            "
            INSERT INTO risk_events (id, event_type, user_id, auction_id, severity, description, metadata)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            "
        )
        .bind(event_id)
        .bind(event_type)
        .bind(Some(user_id))
        .bind(auction_id)
        .bind(severity)
        .bind(description)
        .bind(metadata)
        .execute(&self.pg_pool)
        .await?;

        info!(event_id = %event_id, user_id = %user_id, severity = severity, "Risk event recorded");
        Ok(event_id)
    }

    pub async fn get_admin_ids(&self) -> AppResult<Vec<Uuid>> {
        let ids: Vec<Uuid> = sqlx::query_scalar(
            "SELECT id FROM users WHERE role = $1::user_role AND is_verified = true"
        )
        .bind("admin")
        .fetch_all(&self.pg_pool)
        .await?;

        Ok(ids)
    }

    pub async fn cleanup_old_records(&self) {
        let one_hour_ago = Utc::now() - Duration::hours(1);
        self.recent_bids.retain(|_, records| {
            records.retain(|r| r.timestamp >= one_hour_ago);
            !records.is_empty()
        });
    }
}

impl Clone for FraudDetectionService {
    fn clone(&self) -> Self {
        Self {
            pg_pool: self.pg_pool.clone(),
            recent_bids: self.recent_bids.clone(),
            max_bids_per_minute: self.max_bids_per_minute,
            max_bids_per_hour: self.max_bids_per_hour,
            min_interval_between_bids: self.min_interval_between_bids,
        }
    }
}
