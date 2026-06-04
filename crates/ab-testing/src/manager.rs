use std::collections::HashMap;
use std::sync::Arc;
use uuid::Uuid;
use chrono::Utc;
use common::models::SchedulingStrategy;
use tokio::sync::RwLock;

use crate::models::{Experiment, ExperimentStatus, ExperimentGroup};

pub struct ExperimentManager {
    experiments: Arc<RwLock<HashMap<Uuid, Experiment>>>,
}

impl Clone for ExperimentManager {
    fn clone(&self) -> Self {
        ExperimentManager {
            experiments: self.experiments.clone(),
        }
    }
}

impl ExperimentManager {
    pub fn new() -> Self {
        ExperimentManager {
            experiments: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn create_experiment(
        &self,
        name: String,
        description: String,
        control_strategy: SchedulingStrategy,
        treatment_strategy: SchedulingStrategy,
        traffic_percentage: u32,
        target_nodes: Vec<Uuid>,
    ) -> Uuid {
        let id = Uuid::new_v4();
        let experiment = Experiment {
            id,
            name,
            description,
            control_strategy,
            treatment_strategy,
            traffic_percentage,
            target_nodes,
            status: ExperimentStatus::Draft,
            metrics: Vec::new(),
            created_at: Utc::now(),
            started_at: None,
            completed_at: None,
        };
        self.experiments.write().await.insert(id, experiment);
        id
    }

    pub async fn start_experiment(&self, id: Uuid) -> Result<(), String> {
        let mut experiments = self.experiments.write().await;
        let experiment = experiments
            .get_mut(&id)
            .ok_or_else(|| format!("Experiment {} not found", id))?;
        if experiment.status != ExperimentStatus::Draft && experiment.status != ExperimentStatus::Paused {
            return Err(format!("Cannot start experiment in {:?} status", experiment.status));
        }
        experiment.status = ExperimentStatus::Running;
        experiment.started_at = Some(Utc::now());
        Ok(())
    }

    pub async fn pause_experiment(&self, id: Uuid) -> Result<(), String> {
        let mut experiments = self.experiments.write().await;
        let experiment = experiments
            .get_mut(&id)
            .ok_or_else(|| format!("Experiment {} not found", id))?;
        if experiment.status != ExperimentStatus::Running {
            return Err(format!("Cannot pause experiment in {:?} status", experiment.status));
        }
        experiment.status = ExperimentStatus::Paused;
        Ok(())
    }

    pub async fn complete_experiment(&self, id: Uuid) -> Result<(), String> {
        let mut experiments = self.experiments.write().await;
        let experiment = experiments
            .get_mut(&id)
            .ok_or_else(|| format!("Experiment {} not found", id))?;
        if experiment.status != ExperimentStatus::Running {
            return Err(format!("Cannot complete experiment in {:?} status", experiment.status));
        }
        experiment.status = ExperimentStatus::Completed;
        experiment.completed_at = Some(Utc::now());
        Ok(())
    }

    pub async fn get_experiment(&self, id: Uuid) -> Option<Experiment> {
        let experiments = self.experiments.read().await;
        experiments.get(&id).cloned()
    }

    pub async fn list_experiments(&self) -> Vec<Experiment> {
        let experiments = self.experiments.read().await;
        experiments.values().cloned().collect()
    }

    pub async fn assign_group(&self, experiment_id: Uuid, request_hash: u64) -> Result<ExperimentGroup, String> {
        let experiments = self.experiments.read().await;
        let experiment = experiments
            .get(&experiment_id)
            .ok_or_else(|| format!("Experiment {} not found", experiment_id))?;
        if experiment.status != ExperimentStatus::Running {
            return Err(format!("Experiment {} is not running", experiment_id));
        }
        let bucket = request_hash % 100;
        if bucket < experiment.traffic_percentage as u64 {
            Ok(ExperimentGroup::Treatment)
        } else {
            Ok(ExperimentGroup::Control)
        }
    }

    pub async fn should_apply_treatment(
        &self,
        experiment_id: Uuid,
        node_id: Uuid,
        request_hash: u64,
    ) -> Result<bool, String> {
        let experiments = self.experiments.read().await;
        let experiment = experiments
            .get(&experiment_id)
            .ok_or_else(|| format!("Experiment {} not found", experiment_id))?;
        if experiment.status != ExperimentStatus::Running {
            return Ok(false);
        }
        if !experiment.target_nodes.is_empty() && !experiment.target_nodes.contains(&node_id) {
            return Ok(false);
        }
        let bucket = request_hash % 100;
        Ok(bucket < experiment.traffic_percentage as u64)
    }
}
