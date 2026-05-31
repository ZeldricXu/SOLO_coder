use uuid::Uuid;
use chrono::Utc;

use crate::models::{PreviewEnvironment, CleanupPolicy, ProvisionRequest, EnvStatus, EnvType};

pub struct CleanupScheduler {
    pub cleanup_policy: CleanupPolicy,
}

impl CleanupScheduler {
    pub fn new(policy: CleanupPolicy) -> Self {
        Self {
            cleanup_policy: policy,
        }
    }

    pub fn find_expired_environments(&self, envs: &[PreviewEnvironment]) -> Vec<Uuid> {
        let now = Utc::now();
        envs.iter()
            .filter(|env| env.status != EnvStatus::Terminated && env.expires_at <= now)
            .map(|env| env.id)
            .collect()
    }

    pub fn check_team_quota(team: &str, envs: &[PreviewEnvironment], policy: &CleanupPolicy) -> bool {
        let team_envs = envs.iter()
            .filter(|env| env.owner_team == team && env.status != EnvStatus::Terminated)
            .count();
        team_envs < policy.max_concurrent_per_team as usize
    }

    pub fn check_total_quota(envs: &[PreviewEnvironment], policy: &CleanupPolicy) -> bool {
        let preview_envs = envs.iter()
            .filter(|env| env.env_type == EnvType::Preview && env.status != EnvStatus::Terminated)
            .count();
        preview_envs < policy.max_total_preview_envs as usize
    }

    pub fn can_provision(request: &ProvisionRequest, envs: &[PreviewEnvironment], policy: &CleanupPolicy) -> bool {
        if !Self::check_team_quota(&request.owner_team, envs, policy) {
            return false;
        }
        if request.env_type == EnvType::Preview && !Self::check_total_quota(envs, policy) {
            return false;
        }
        true
    }
}
