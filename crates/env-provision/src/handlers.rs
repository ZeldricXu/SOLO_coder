use anyhow::Result;
use uuid::Uuid;

use crate::models::{PreviewEnvironment, ProvisionRequest, UsageStats};
use crate::provisioner::EnvironmentProvisioner;
use crate::stats::UsageStatsCollector;

pub fn provision_env(
    provisioner: &EnvironmentProvisioner,
    request: ProvisionRequest,
) -> Result<PreviewEnvironment> {
    provisioner.provision(request)
}

pub fn terminate_env(provisioner: &EnvironmentProvisioner, env_id: Uuid) -> Result<()> {
    provisioner.terminate(env_id)
}

pub fn stop_env(provisioner: &EnvironmentProvisioner, env_id: Uuid) -> Result<()> {
    provisioner.stop(env_id)
}

pub fn start_env(provisioner: &EnvironmentProvisioner, env_id: Uuid) -> Result<()> {
    provisioner.start(env_id)
}

pub fn get_env(
    provisioner: &EnvironmentProvisioner,
    env_id: Uuid,
) -> Result<Option<PreviewEnvironment>> {
    provisioner.get_environment(env_id)
}

pub fn list_envs(
    provisioner: &EnvironmentProvisioner,
    team: Option<&str>,
) -> Result<Vec<PreviewEnvironment>> {
    provisioner.list_environments(team)
}

pub fn send_heartbeat(provisioner: &EnvironmentProvisioner, env_id: Uuid) -> Result<()> {
    provisioner.heartbeat(env_id)
}

pub fn get_usage_stats(
    collector: &UsageStatsCollector,
    env_id: Uuid,
) -> Result<Option<UsageStats>> {
    collector.get_env_stats(env_id)
}
