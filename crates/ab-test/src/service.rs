use chrono::Utc;
use common::error::AppError;
use common::types::{Experiment, ExperimentGroup};
use common::utils::hash_user_id_to_bucket;
use db::RedisClient;
use redis::AsyncCommands;
use std::collections::HashMap;
use tracing::{debug, info};
use uuid::Uuid;

use crate::recorder::ExperimentRecorder;
use crate::report::ReportGenerator;
use crate::types::{ExperimentExt, ExperimentReport, GroupResult};

const USER_BUCKET_COUNT: u32 = 10000;
const USER_ASSIGNMENT_TTL_SECS: u64 = 3600 * 24 * 30;

pub struct ExperimentService {
    redis: RedisClient,
    recorder: ExperimentRecorder,
}

impl ExperimentService {
    pub fn new(redis: RedisClient, recorder: ExperimentRecorder) -> Self {
        Self { redis, recorder }
    }

    pub fn assign_user_to_group(
        &self,
        user_id: &str,
        experiment: &Experiment,
    ) -> Result<String, AppError> {
        Self::assign_user_to_group_static(user_id, experiment)
    }

    pub fn assign_user_to_group_static(
        user_id: &str,
        experiment: &Experiment,
    ) -> Result<String, AppError> {
        let groups = experiment.all_groups();

        if groups.is_empty() {
            return Err(AppError::InvalidExperimentConfig(
                "Experiment has no groups".to_string(),
            ));
        }

        let total_traffic: u8 = groups.iter().map(|g| g.traffic_percent).sum();
        if total_traffic == 0 {
            return Err(AppError::InvalidExperimentConfig(
                "Total traffic percent is 0".to_string(),
            ));
        }

        let bucket = hash_user_id_to_bucket(user_id, USER_BUCKET_COUNT);
        let bucket_pct = (bucket as f64 / USER_BUCKET_COUNT as f64) * 100.0;

        let mut cumulative = 0.0;
        let mut assigned_group: Option<String> = None;

        for g in &groups {
            cumulative += g.traffic_percent as f64;
            if bucket_pct < cumulative {
                assigned_group = Some(g.name.clone());
                break;
            }
        }

        let group_name = assigned_group.unwrap_or_else(|| {
            groups
                .last()
                .map(|g| g.name.clone())
                .unwrap_or_else(|| "control".to_string())
        });

        debug!(
            "Assigned user {} to group {} in experiment {} (bucket={}, bucket_pct={:.2}%)",
            user_id, group_name, experiment.id, bucket, bucket_pct
        );

        Ok(group_name)
    }

    pub async fn assign_user_to_group_cached(
        &self,
        experiment_id: Uuid,
        user_id: &str,
        experiment: &Experiment,
    ) -> Result<String, AppError> {
        let assignment_key = format!("experiment:{}:assignment:{}", experiment_id, user_id);
        let mut redis_conn = self.redis.manager.clone();

        if let Ok(Some(cached)) = redis_conn
            .get::<_, Option<String>>(&assignment_key)
            .await
        {
            debug!(
                "User {} already assigned to group {} in experiment {} (cached)",
                user_id, cached, experiment_id
            );
            return Ok(cached);
        }

        let group_name = Self::assign_user_to_group_static(user_id, experiment)?;

        let _: Result<(), _> = redis_conn
            .set_ex(&assignment_key, &group_name, USER_ASSIGNMENT_TTL_SECS)
            .await;

        let users_key = format!("experiment:{}:{}:users", experiment_id, group_name);
        let _: Result<(), _> = redis_conn.pfadd(&users_key, user_id).await;

        let all_users_key = format!("experiment:{}:all_users", experiment_id);
        let _: Result<(), _> = redis_conn.pfadd(&all_users_key, user_id).await;

        Ok(group_name)
    }

    pub async fn get_experiment_results(
        &self,
        experiment: &Experiment,
    ) -> Result<ExperimentReport, AppError> {
        info!("Generating results for experiment: {}", experiment.id);

        let _ = self.recorder.flush_all().await;

        let groups = experiment.all_groups();
        let mut group_results = Vec::with_capacity(groups.len());

        for g in &groups {
            let metrics_map = self
                .collect_group_metrics(experiment, g)
                .await
                .unwrap_or_default();
            group_results.push(GroupResult {
                group_name: g.name.clone(),
                model_version_id: g.model_version_id,
                metrics: metrics_map,
            });
        }

        let total_users = self.get_total_users(experiment.id).await.unwrap_or(0);

        let now = Utc::now();
        let start = experiment.start_time;
        let duration_days = (now - start).num_seconds() as f64 / 86400.0;

        let report =
            ReportGenerator::generate(experiment, &group_results, total_users, duration_days);

        Ok(report)
    }

    pub async fn get_experiment_results_markdown(
        &self,
        experiment: &Experiment,
    ) -> Result<String, AppError> {
        let report = self.get_experiment_results(experiment).await?;
        Ok(ReportGenerator::to_markdown(&report))
    }

    pub async fn collect_group_metrics(
        &self,
        experiment: &Experiment,
        group: &ExperimentGroup,
    ) -> Result<HashMap<String, crate::types::MetricValue>, AppError> {
        let mut metrics = HashMap::new();

        for metric_def in &experiment.metrics {
            let mv = self
                .recorder
                .get_metric_stats(experiment.id, &group.name, &metric_def.name)
                .await?;
            metrics.insert(metric_def.name.clone(), mv);
        }

        Ok(metrics)
    }

    pub async fn record_metric(
        &self,
        experiment_id: Uuid,
        group_name: &str,
        metric_name: &str,
        value: f64,
    ) -> Result<(), AppError> {
        self.recorder
            .record_metric(experiment_id, group_name, metric_name, value)
            .await
    }

    pub async fn get_total_users(&self, experiment_id: Uuid) -> Result<u64, AppError> {
        let all_users_key = format!("experiment:{}:all_users", experiment_id);
        let mut redis_conn = self.redis.manager.clone();

        let count: Option<u64> = redis_conn
            .pfcount(&all_users_key)
            .await
            .map_err(|e| AppError::Cache(e.to_string()))?;

        Ok(count.unwrap_or(0))
    }

    pub async fn get_group_user_count(
        &self,
        experiment_id: Uuid,
        group_name: &str,
    ) -> Result<u64, AppError> {
        let users_key = format!("experiment:{}:{}:users", experiment_id, group_name);
        let mut redis_conn = self.redis.manager.clone();

        let count: Option<u64> = redis_conn
            .pfcount(&users_key)
            .await
            .map_err(|e| AppError::Cache(e.to_string()))?;

        Ok(count.unwrap_or(0))
    }

    pub async fn clear_experiment_data(&self, experiment_id: Uuid) -> Result<(), AppError> {
        self.recorder.clear_experiment_data(experiment_id).await?;

        let mut redis_conn = self.redis.manager.clone();
        let pattern = format!("experiment:{}:assignment:*", experiment_id);
        let keys: Vec<String> = redis_conn.keys(&pattern).await.unwrap_or_default();
        if !keys.is_empty() {
            let _: Result<(), _> = redis_conn.del(keys).await;
        }

        info!("Cleared all data for experiment: {}", experiment_id);
        Ok(())
    }

    pub fn recorder(&self) -> &ExperimentRecorder {
        &self.recorder
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::Duration;
    use common::types::{ExperimentGroup, ExperimentStatus, MetricDefinition};
    use uuid::Uuid;

    fn create_test_experiment() -> Experiment {
        let now = Utc::now();
        Experiment {
            id: Uuid::new_v4(),
            name: "test-exp".to_string(),
            model_name: "test-model".to_string(),
            control_group: ExperimentGroup {
                name: "control".to_string(),
                model_version_id: Uuid::new_v4(),
                traffic_percent: 50,
                config: None,
            },
            experiment_groups: vec![ExperimentGroup {
                name: "treatment".to_string(),
                model_version_id: Uuid::new_v4(),
                traffic_percent: 50,
                config: None,
            }],
            metrics: vec![
                MetricDefinition {
                    name: "click_rate".to_string(),
                    metric_type: "proportion".to_string(),
                    description: None,
                    unit: None,
                },
                MetricDefinition {
                    name: "revenue".to_string(),
                    metric_type: "continuous".to_string(),
                    description: None,
                    unit: None,
                },
            ],
            start_time: now - Duration::days(7),
            end_time: None,
            status: ExperimentStatus::Running,
        }
    }

    #[test]
    fn test_assign_user_to_group_stability() {
        let exp = create_test_experiment();

        let user_id = "user-12345";
        let group1 = ExperimentService::assign_user_to_group_static(user_id, &exp).unwrap();
        let group2 = ExperimentService::assign_user_to_group_static(user_id, &exp).unwrap();

        assert_eq!(group1, group2, "User assignment should be stable");
    }

    #[test]
    fn test_assign_user_to_group_distribution() {
        let exp = create_test_experiment();

        let mut counts: HashMap<String, u32> = HashMap::new();

        for i in 0..10000 {
            let user_id = format!("user-{}", i);
            let group = ExperimentService::assign_user_to_group_static(&user_id, &exp).unwrap();
            *counts.entry(group).or_insert(0) += 1;
        }

        let control_count = *counts.get("control").unwrap_or(&0);
        let treatment_count = *counts.get("treatment").unwrap_or(&0);

        let control_pct = control_count as f64 / 10000.0 * 100.0;
        let treatment_pct = treatment_count as f64 / 10000.0 * 100.0;

        assert!(
            control_pct > 40.0 && control_pct < 60.0,
            "Control group should have ~50%, got {:.1}%",
            control_pct
        );
        assert!(
            treatment_pct > 40.0 && treatment_pct < 60.0,
            "Treatment group should have ~50%, got {:.1}%",
            treatment_pct
        );
    }
}
