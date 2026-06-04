use sqlx::{PgPool, postgres::PgPoolOptions, FromRow};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::net::IpAddr;
use std::str::FromStr;

use crate::models::*;
use crate::error::CdnResult;
use crate::config::DatabaseConfig;

#[derive(Clone)]
pub struct Database {
    pool: PgPool,
}

impl Database {
    pub async fn new(config: &DatabaseConfig) -> CdnResult<Self> {
        let pool = PgPoolOptions::new()
            .max_connections(config.max_connections)
            .min_connections(config.min_connections)
            .acquire_timeout(std::time::Duration::from_secs(config.acquire_timeout_seconds))
            .connect(&config.url)
            .await?;

        Ok(Database { pool })
    }

    pub fn pool(&self) -> &PgPool {
        &self.pool
    }

    pub async fn create_edge_node(&self, node: &EdgeNode) -> CdnResult<()> {
        sqlx::query(
            r#"
            INSERT INTO edge_nodes (
                id, ip_address, datacenter, region, bandwidth_capacity, bandwidth_usage,
                storage_capacity, current_load, status, weight, latitude,
                longitude, registered_at, last_heartbeat, created_at, updated_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16)
            "#,
        )
        .bind(node.id)
        .bind(node.ip_address.to_string())
        .bind(node.datacenter.clone())
        .bind(node.region.clone())
        .bind(node.bandwidth_capacity as i64)
        .bind(node.bandwidth_usage)
        .bind(node.storage_capacity as i64)
        .bind(node.current_load)
        .bind(serde_json::to_string(&node.status)?)
        .bind(node.weight as i32)
        .bind(node.latitude)
        .bind(node.longitude)
        .bind(node.registered_at)
        .bind(node.last_heartbeat())
        .bind(node.registered_at)
        .bind(node.registered_at)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn update_edge_node_status(&self, node_id: Uuid, status: &NodeStatus) -> CdnResult<()> {
        sqlx::query(
            r#"
            UPDATE edge_nodes
            SET status = $1, updated_at = $2
            WHERE id = $3
            "#,
        )
        .bind(serde_json::to_string(status)?)
        .bind(Utc::now())
        .bind(node_id)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn update_node_heartbeat(&self, node_id: Uuid, heartbeat: &Heartbeat) -> CdnResult<()> {
        sqlx::query(
            r#"
            UPDATE edge_nodes
            SET current_load = $1, last_heartbeat = $2, updated_at = $3
            WHERE id = $4
            "#,
        )
        .bind(heartbeat.load)
        .bind(heartbeat.timestamp)
        .bind(heartbeat.timestamp)
        .bind(node_id)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn get_edge_node(&self, node_id: Uuid) -> CdnResult<Option<EdgeNode>> {
        let row = sqlx::query_as::<_, EdgeNodeRow>(
            r#"SELECT * FROM edge_nodes WHERE id = $1"#,
        )
        .bind(node_id)
        .fetch_optional(&self.pool)
        .await?;

        match row {
            Some(r) => Ok(Some(row_to_edge_node(r)?)),
            None => Ok(None),
        }
    }

    pub async fn get_all_edge_nodes(&self) -> CdnResult<Vec<EdgeNode>> {
        let rows = sqlx::query_as::<_, EdgeNodeRow>(r#"SELECT * FROM edge_nodes"#)
            .fetch_all(&self.pool)
            .await?;

        let mut nodes = Vec::new();
        for row in rows {
            nodes.push(row_to_edge_node(row)?);
        }
        Ok(nodes)
    }

    pub async fn get_online_edge_nodes(&self) -> CdnResult<Vec<EdgeNode>> {
        let rows = sqlx::query_as::<_, EdgeNodeRow>(
            r#"SELECT * FROM edge_nodes WHERE status = 'online'"#
        )
        .fetch_all(&self.pool)
        .await?;

        let mut nodes = Vec::new();
        for row in rows {
            nodes.push(row_to_edge_node(row)?);
        }
        Ok(nodes)
    }

    pub async fn get_edge_nodes_by_region(&self, region: &str) -> CdnResult<Vec<EdgeNode>> {
        let rows = sqlx::query_as::<_, EdgeNodeRow>(
            r#"SELECT * FROM edge_nodes WHERE region = $1 AND status = 'online'"#,
        )
        .bind(region)
        .fetch_all(&self.pool)
        .await?;

        let mut nodes = Vec::new();
        for row in rows {
            nodes.push(row_to_edge_node(row)?);
        }
        Ok(nodes)
    }

    pub async fn delete_edge_node(&self, node_id: Uuid) -> CdnResult<()> {
        sqlx::query(r#"DELETE FROM edge_nodes WHERE id = $1"#)
            .bind(node_id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn create_domain_config(&self, config: &DomainConfig) -> CdnResult<()> {
        sqlx::query(
            r#"
            INSERT INTO domain_configs (
                id, domain, origin_server, cache_ttl, enabled,
                created_at, updated_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7)
            "#,
        )
        .bind(config.id)
        .bind(config.domain.clone())
        .bind(config.origin_server.clone())
        .bind(config.cache_ttl as i32)
        .bind(config.enabled)
        .bind(config.created_at)
        .bind(config.updated_at)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn get_domain_config(&self, domain: &str) -> CdnResult<Option<DomainConfig>> {
        let row = sqlx::query_as::<_, DomainConfigRow>(
            r#"SELECT * FROM domain_configs WHERE domain = $1"#,
        )
        .bind(domain)
        .fetch_optional(&self.pool)
        .await?;

        match row {
            Some(r) => Ok(Some(row_to_domain_config(r))),
            None => Ok(None),
        }
    }

    pub async fn get_all_domains(&self) -> CdnResult<Vec<DomainConfig>> {
        let rows = sqlx::query_as::<_, DomainConfigRow>(r#"SELECT * FROM domain_configs"#)
            .fetch_all(&self.pool)
            .await?;

        Ok(rows.into_iter().map(row_to_domain_config).collect())
    }

    pub async fn create_cache_rule(&self, rule: &CacheRule) -> CdnResult<()> {
        sqlx::query(
            r#"
            INSERT INTO cache_rules (
                id, domain_config_id, domain, path_pattern, eviction_policy,
                ttl_seconds, priority, enabled, ignore_query_params,
                vary_by_ua, vary_by_referer, max_size_bytes, created_at, updated_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
            "#,
        )
        .bind(rule.id)
        .bind(rule.domain_config_id)
        .bind(rule.domain.clone())
        .bind(rule.path_pattern.clone())
        .bind(serde_json::to_string(&rule.eviction_policy)?)
        .bind(rule.ttl_seconds as i32)
        .bind(rule.priority)
        .bind(rule.enabled)
        .bind(&rule.ignore_query_params)
        .bind(rule.vary_by_ua)
        .bind(rule.vary_by_referer)
        .bind(rule.max_size_bytes as i64)
        .bind(rule.created_at)
        .bind(rule.updated_at)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn get_cache_rules_for_domain(&self, domain_config_id: Uuid) -> CdnResult<Vec<CacheRule>> {
        let rows = sqlx::query_as::<_, CacheRuleRow>(
            r#"SELECT * FROM cache_rules WHERE domain_config_id = $1 ORDER BY priority DESC"#,
        )
        .bind(domain_config_id)
        .fetch_all(&self.pool)
        .await?;

        let mut rules = Vec::new();
        for row in rows {
            rules.push(row_to_cache_rule(row)?);
        }
        Ok(rules)
    }

    pub async fn get_cache_rules_by_domain_name(&self, domain: &str) -> CdnResult<Vec<CacheRule>> {
        let rows = sqlx::query_as::<_, CacheRuleRow>(
            r#"SELECT * FROM cache_rules WHERE domain = $1 ORDER BY priority DESC"#,
        )
        .bind(domain)
        .fetch_all(&self.pool)
        .await?;

        let mut rules = Vec::new();
        for row in rows {
            rules.push(row_to_cache_rule(row)?);
        }
        Ok(rules)
    }

    pub async fn insert_node_metrics(&self, metrics: &NodeMetrics) -> CdnResult<()> {
        sqlx::query(
            r#"
            INSERT INTO node_metrics (
                id, node_id, timestamp, qps, bandwidth_usage,
                cache_hit_rate, origin_fetch_rate, error_rate_4xx, error_rate_5xx,
                active_connections, memory_usage, cpu_usage
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
            "#,
        )
        .bind(metrics.id)
        .bind(metrics.node_id)
        .bind(metrics.timestamp)
        .bind(metrics.qps)
        .bind(metrics.bandwidth_usage)
        .bind(metrics.cache_hit_rate)
        .bind(metrics.origin_fetch_rate)
        .bind(metrics.error_rate_4xx)
        .bind(metrics.error_rate_5xx)
        .bind(metrics.active_connections as i32)
        .bind(metrics.memory_usage)
        .bind(metrics.cpu_usage)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn get_latest_node_metrics(&self, node_id: Uuid) -> CdnResult<Option<NodeMetrics>> {
        let row = sqlx::query_as::<_, NodeMetricsRow>(
            r#"SELECT * FROM node_metrics WHERE node_id = $1 ORDER BY timestamp DESC LIMIT 1"#,
        )
        .bind(node_id)
        .fetch_optional(&self.pool)
        .await?;

        Ok(row.map(row_to_node_metrics))
    }

    pub async fn get_all_latest_node_metrics(&self) -> CdnResult<Vec<NodeMetrics>> {
        let rows = sqlx::query_as::<_, NodeMetricsRow>(
            r#"SELECT DISTINCT ON (node_id) * FROM node_metrics ORDER BY node_id, timestamp DESC"#,
        )
        .fetch_all(&self.pool)
        .await?;

        Ok(rows.into_iter().map(row_to_node_metrics).collect())
    }

    pub async fn create_alert(&self, alert: &Alert) -> CdnResult<()> {
        sqlx::query(
            r#"
            INSERT INTO alerts (
                id, alert_type, severity, node_id, message,
                acknowledged, resolved, metadata, created_at, resolved_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
            "#,
        )
        .bind(alert.id)
        .bind(serde_json::to_string(&alert.alert_type)?)
        .bind(serde_json::to_string(&alert.severity)?)
        .bind(alert.node_id)
        .bind(alert.message.clone())
        .bind(alert.acknowledged)
        .bind(alert.resolved)
        .bind(serde_json::to_value(&alert.metadata)?)
        .bind(alert.created_at)
        .bind(alert.resolved_at)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn get_active_alerts(&self) -> CdnResult<Vec<Alert>> {
        let rows = sqlx::query_as::<_, AlertRow>(
            r#"SELECT * FROM alerts WHERE resolved = false ORDER BY created_at DESC"#,
        )
        .fetch_all(&self.pool)
        .await?;

        let mut alerts = Vec::new();
        for row in rows {
            alerts.push(row_to_alert(row)?);
        }
        Ok(alerts)
    }

    pub async fn create_tls_certificate(&self, cert: &TlsCertificate) -> CdnResult<()> {
        sqlx::query(
            r#"
            INSERT INTO tls_certificates (
                id, domain, certificate_pem, private_key_encrypted,
                issuer, not_before, not_after, auto_renew, status, created_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
            "#,
        )
        .bind(cert.id)
        .bind(cert.domain.clone())
        .bind(cert.certificate_pem.clone())
        .bind(cert.private_key_encrypted.clone())
        .bind(cert.issuer.clone())
        .bind(cert.not_before)
        .bind(cert.not_after)
        .bind(cert.auto_renew)
        .bind(serde_json::to_string(&cert.status)?)
        .bind(cert.created_at)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn get_expiring_certificates(&self, days_before: i64) -> CdnResult<Vec<TlsCertificate>> {
        let rows = sqlx::query_as::<_, TlsCertificateRow>(
            r#"
            SELECT * FROM tls_certificates 
            WHERE status = 'active' 
            AND not_after < NOW() + ($1 || ' days')::interval
            "#,
        )
        .bind(days_before)
        .fetch_all(&self.pool)
        .await?;

        let mut certs = Vec::new();
        for row in rows {
            certs.push(row_to_tls_certificate(row)?);
        }
        Ok(certs)
    }

    pub async fn get_certificate(&self, domain: &str) -> CdnResult<Option<TlsCertificate>> {
        let row = sqlx::query_as::<_, TlsCertificateRow>(
            r#"SELECT * FROM tls_certificates WHERE domain = $1 LIMIT 1"#,
        )
        .bind(domain)
        .fetch_optional(&self.pool)
        .await?;

        match row {
            Some(r) => Ok(Some(row_to_tls_certificate(r)?)),
            None => Ok(None),
        }
    }

    pub async fn create_config_version(&self, version: &ConfigVersion) -> CdnResult<()> {
        sqlx::query(
            r#"
            INSERT INTO config_versions (
                id, config_type, version, data, created_by, description, created_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7)
            "#,
        )
        .bind(version.id)
        .bind(version.config_type.clone())
        .bind(version.version as i64)
        .bind(&version.data)
        .bind(version.created_by.clone())
        .bind(version.description.clone())
        .bind(version.created_at)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn create_config_deployment(&self, deployment: &ConfigDeployment) -> CdnResult<()> {
        sqlx::query(
            r#"
            INSERT INTO config_deployments (
                id, config_version_id, status, canary_percent,
                started_at, completed_at, error_message, created_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
            "#,
        )
        .bind(deployment.id)
        .bind(deployment.config_version_id)
        .bind(serde_json::to_string(&deployment.status)?)
        .bind(deployment.canary_percent as i32)
        .bind(deployment.started_at)
        .bind(deployment.completed_at)
        .bind(deployment.error_message.clone())
        .bind(deployment.created_at)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn get_active_deployments(&self) -> CdnResult<Vec<ConfigDeployment>> {
        let rows = sqlx::query_as::<_, ConfigDeploymentRow>(
            r#"SELECT * FROM config_deployments WHERE status = 'in_progress' ORDER BY created_at DESC"#,
        )
        .fetch_all(&self.pool)
        .await?;

        let mut deployments = Vec::new();
        for row in rows {
            deployments.push(row_to_config_deployment(row)?);
        }
        Ok(deployments)
    }

    pub async fn create_operation_log(&self, log: &OperationLog) -> CdnResult<()> {
        sqlx::query(
            r#"
            INSERT INTO operation_logs (
                id, operation_type, entity_type, entity_id, operator,
                description, before_data, after_data, created_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
            "#,
        )
        .bind(log.id)
        .bind(log.operation_type.clone())
        .bind(log.entity_type.clone())
        .bind(log.entity_id)
        .bind(log.operator.clone())
        .bind(log.description.clone())
        .bind(serde_json::to_value(&log.before_data)?)
        .bind(serde_json::to_value(&log.after_data)?)
        .bind(log.created_at)
        .execute(&self.pool)
        .await?;
        Ok(())
    }
}

fn row_to_edge_node(row: EdgeNodeRow) -> CdnResult<EdgeNode> {
    use std::sync::atomic::AtomicU64;
    let ts = match row.last_heartbeat {
        Some(dt) => dt.timestamp_millis() as u64,
        None => 0,
    };
    Ok(EdgeNode {
        id: row.id,
        ip_address: IpAddr::from_str(&row.ip_address)?,
        datacenter: row.datacenter,
        region: row.region,
        bandwidth_capacity: row.bandwidth_capacity as u64,
        bandwidth_usage: row.bandwidth_usage,
        storage_capacity: row.storage_capacity as u64,
        current_load: row.current_load,
        status: serde_json::from_str(&row.status).unwrap_or(NodeStatus::Online),
        weight: row.weight as u32,
        latitude: row.latitude,
        longitude: row.longitude,
        registered_at: row.registered_at,
        last_heartbeat_ts: AtomicU64::new(ts),
        role: NodeRole::Edge,
        parent_node_id: None,
    })
}

fn row_to_domain_config(row: DomainConfigRow) -> DomainConfig {
    DomainConfig {
        id: row.id,
        domain: row.domain,
        origin_server: row.origin_server,
        cache_ttl: row.cache_ttl as u64,
        enabled: row.enabled,
        content_type: None,
        created_at: row.created_at,
        updated_at: row.updated_at,
    }
}

fn row_to_cache_rule(row: CacheRuleRow) -> CdnResult<CacheRule> {
    Ok(CacheRule {
        id: row.id,
        domain_config_id: row.domain_config_id,
        domain: row.domain,
        path_pattern: row.path_pattern,
        eviction_policy: serde_json::from_str(&row.eviction_policy).unwrap_or(CacheEvictionPolicy::LRU),
        ttl_seconds: row.ttl_seconds as u64,
        priority: row.priority,
        enabled: row.enabled,
        ignore_query_params: row.ignore_query_params,
        vary_by_ua: row.vary_by_ua,
        vary_by_referer: row.vary_by_referer,
        max_size_bytes: row.max_size_bytes as u64,
        created_at: row.created_at,
        updated_at: row.updated_at,
    })
}

fn row_to_node_metrics(row: NodeMetricsRow) -> NodeMetrics {
    NodeMetrics {
        id: row.id,
        node_id: row.node_id,
        timestamp: row.timestamp,
        qps: row.qps,
        bandwidth_usage: row.bandwidth_usage,
        cache_hit_rate: row.cache_hit_rate,
        origin_fetch_rate: row.origin_fetch_rate,
        error_rate_4xx: row.error_rate_4xx,
        error_rate_5xx: row.error_rate_5xx,
        active_connections: row.active_connections as u64,
        memory_usage: row.memory_usage,
        cpu_usage: row.cpu_usage,
    }
}

fn row_to_alert(row: AlertRow) -> CdnResult<Alert> {
    Ok(Alert {
        id: row.id,
        alert_type: serde_json::from_str(&row.alert_type).unwrap_or(AlertType::HighLoad),
        severity: serde_json::from_str(&row.severity).unwrap_or(AlertSeverity::Warning),
        node_id: row.node_id,
        message: row.message,
        acknowledged: row.acknowledged,
        resolved: row.resolved,
        metadata: serde_json::from_value(row.metadata).unwrap_or_default(),
        created_at: row.created_at,
        resolved_at: row.resolved_at,
    })
}

fn row_to_tls_certificate(row: TlsCertificateRow) -> CdnResult<TlsCertificate> {
    Ok(TlsCertificate {
        id: row.id,
        domain: row.domain,
        certificate_pem: row.certificate_pem,
        private_key_encrypted: row.private_key_encrypted,
        issuer: row.issuer,
        not_before: row.not_before,
        not_after: row.not_after,
        auto_renew: row.auto_renew,
        status: serde_json::from_str(&row.status).unwrap_or(CertificateStatus::Active),
        created_at: row.created_at,
    })
}

fn row_to_config_deployment(row: ConfigDeploymentRow) -> CdnResult<ConfigDeployment> {
    Ok(ConfigDeployment {
        id: row.id,
        config_version_id: row.config_version_id,
        status: serde_json::from_str(&row.status).unwrap_or(DeploymentStatus::Pending),
        target_nodes: Vec::new(),
        canary_percent: row.canary_percent as u32,
        percentage: 0,
        success_count: 0,
        failure_count: 0,
        started_at: row.started_at,
        completed_at: row.completed_at,
        error_message: row.error_message,
        created_at: row.created_at,
    })
}

#[derive(Debug, FromRow)]
struct EdgeNodeRow {
    id: Uuid,
    ip_address: String,
    datacenter: String,
    region: String,
    bandwidth_capacity: i64,
    bandwidth_usage: f64,
    storage_capacity: i64,
    current_load: f64,
    status: String,
    weight: i32,
    latitude: f64,
    longitude: f64,
    registered_at: DateTime<Utc>,
    last_heartbeat: Option<DateTime<Utc>>,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

#[derive(Debug, FromRow)]
struct DomainConfigRow {
    id: Uuid,
    domain: String,
    origin_server: String,
    cache_ttl: i32,
    enabled: bool,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

#[derive(Debug, FromRow)]
struct CacheRuleRow {
    id: Uuid,
    domain_config_id: Uuid,
    domain: String,
    path_pattern: String,
    eviction_policy: String,
    ttl_seconds: i32,
    priority: i32,
    enabled: bool,
    ignore_query_params: Vec<String>,
    vary_by_ua: bool,
    vary_by_referer: bool,
    max_size_bytes: i64,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

#[derive(Debug, FromRow)]
struct NodeMetricsRow {
    id: Uuid,
    node_id: Uuid,
    timestamp: DateTime<Utc>,
    qps: f64,
    bandwidth_usage: f64,
    cache_hit_rate: f64,
    origin_fetch_rate: f64,
    error_rate_4xx: f64,
    error_rate_5xx: f64,
    active_connections: i32,
    memory_usage: f64,
    cpu_usage: f64,
}

#[derive(Debug, FromRow)]
struct AlertRow {
    id: Uuid,
    alert_type: String,
    severity: String,
    node_id: Option<Uuid>,
    message: String,
    acknowledged: bool,
    resolved: bool,
    metadata: serde_json::Value,
    created_at: DateTime<Utc>,
    resolved_at: Option<DateTime<Utc>>,
}

#[derive(Debug, FromRow)]
struct TlsCertificateRow {
    id: Uuid,
    domain: String,
    certificate_pem: String,
    private_key_encrypted: String,
    issuer: String,
    not_before: DateTime<Utc>,
    not_after: DateTime<Utc>,
    auto_renew: bool,
    status: String,
    created_at: DateTime<Utc>,
}

#[derive(Debug, FromRow)]
struct ConfigDeploymentRow {
    id: Uuid,
    config_version_id: Uuid,
    status: String,
    canary_percent: i32,
    started_at: Option<DateTime<Utc>>,
    completed_at: Option<DateTime<Utc>>,
    error_message: Option<String>,
    created_at: DateTime<Utc>,
}
