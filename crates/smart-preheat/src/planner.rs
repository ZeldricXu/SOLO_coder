use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};
use uuid::Uuid;

use common::models::PreheatPlanStatus;
use common::utils::generate_id;

use crate::predictor::ExponentialSmoothing;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PreheatPlan {
    pub id: Uuid,
    pub content_urls: Vec<String>,
    pub target_regions: Vec<String>,
    pub scheduled_at: DateTime<Utc>,
    pub bandwidth_limit_bps: u64,
    pub status: PreheatPlanStatus,
}

pub struct PreheatPlanner {
    predictor: ExponentialSmoothing,
    bandwidth_limit_bps: u64,
    heat_threshold: f64,
    predict_hours_ahead: u64,
}

impl PreheatPlanner {
    pub fn new(predictor: ExponentialSmoothing, bandwidth_limit_bps: u64) -> Self {
        PreheatPlanner {
            predictor,
            bandwidth_limit_bps,
            heat_threshold: 5.0,
            predict_hours_ahead: 1,
        }
    }

    pub fn with_threshold(mut self, threshold: f64) -> Self {
        self.heat_threshold = threshold;
        self
    }

    pub fn with_predict_hours(mut self, hours: u64) -> Self {
        self.predict_hours_ahead = hours;
        self
    }

    pub async fn generate_plan(&self) -> PreheatPlan {
        let hot_content = self.predictor
            .predict_all_hot_content(self.heat_threshold)
            .await;

        let mut content_urls: Vec<String> = hot_content
            .iter()
            .map(|(url, _, _)| url.clone())
            .collect();
        content_urls.sort();
        content_urls.dedup();

        let mut target_regions: Vec<String> = hot_content
            .iter()
            .map(|(_, region, _)| region.clone())
            .collect();
        target_regions.sort();
        target_regions.dedup();

        let per_content_bandwidth = if !content_urls.is_empty() {
            self.bandwidth_limit_bps / content_urls.len() as u64
        } else {
            self.bandwidth_limit_bps
        };

        PreheatPlan {
            id: generate_id(),
            content_urls,
            target_regions,
            scheduled_at: Utc::now() + chrono::Duration::hours(self.predict_hours_ahead as i64),
            bandwidth_limit_bps: per_content_bandwidth,
            status: PreheatPlanStatus::Pending,
        }
    }

    pub fn bandwidth_limit(&self) -> u64 {
        self.bandwidth_limit_bps
    }
}
