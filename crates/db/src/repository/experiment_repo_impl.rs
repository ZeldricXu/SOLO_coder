use async_trait::async_trait;
use sqlx::{query, query_as, PgPool};
use uuid::Uuid;

use crate::error::DbResult;
use crate::repository::experiment_repo::{
    CreateExperimentGroupParams, CreateExperimentMetricParams, CreateExperimentParams,
    CreateExperimentResultParams, Experiment, ExperimentGroup, ExperimentMetric, ExperimentRepository,
    ExperimentResult, UpdateExperimentParams,
};

pub struct PgExperimentRepository {
    pub pool: PgPool,
}

impl PgExperimentRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}

#[async_trait]
impl ExperimentRepository for PgExperimentRepository {
    async fn create_experiment(
        &self,
        params: &CreateExperimentParams,
    ) -> DbResult<Experiment> {
        let status = params
            .status
            .clone()
            .unwrap_or_else(|| "draft".to_string());
        let experiment = query_as::<_, Experiment>(
            r#"
            INSERT INTO experiments (name, model_name, status, start_time, end_time)
            VALUES ($1, $2, $3, $4, $5)
            RETURNING id, name, model_name, status, start_time, end_time, created_at
            "#,
        )
        .bind(&params.name)
        .bind(&params.model_name)
        .bind(&status)
        .bind(params.start_time)
        .bind(params.end_time)
        .fetch_one(&self.pool)
        .await?;
        Ok(experiment)
    }

    async fn get_experiment_by_id(&self, id: Uuid) -> DbResult<Option<Experiment>> {
        let experiment = query_as::<_, Experiment>(
            r#"
            SELECT id, name, model_name, status, start_time, end_time, created_at
            FROM experiments
            WHERE id = $1
            "#,
        )
        .bind(id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(experiment)
    }

    async fn get_experiment_by_name(&self, name: &str) -> DbResult<Option<Experiment>> {
        let experiment = query_as::<_, Experiment>(
            r#"
            SELECT id, name, model_name, status, start_time, end_time, created_at
            FROM experiments
            WHERE name = $1
            "#,
        )
        .bind(name)
        .fetch_optional(&self.pool)
        .await?;
        Ok(experiment)
    }

    async fn list_experiments(
        &self,
        status: Option<&str>,
        model_name: Option<&str>,
        limit: i64,
        offset: i64,
    ) -> DbResult<Vec<Experiment>> {
        let sql = match (status.is_some(), model_name.is_some()) {
            (true, true) => {
                r#"
                SELECT id, name, model_name, status, start_time, end_time, created_at
                FROM experiments
                WHERE status = $1 AND model_name = $2
                ORDER BY created_at DESC
                LIMIT $3 OFFSET $4
                "#
            }
            (true, false) => {
                r#"
                SELECT id, name, model_name, status, start_time, end_time, created_at
                FROM experiments
                WHERE status = $1
                ORDER BY created_at DESC
                LIMIT $2 OFFSET $3
                "#
            }
            (false, true) => {
                r#"
                SELECT id, name, model_name, status, start_time, end_time, created_at
                FROM experiments
                WHERE model_name = $1
                ORDER BY created_at DESC
                LIMIT $2 OFFSET $3
                "#
            }
            (false, false) => {
                r#"
                SELECT id, name, model_name, status, start_time, end_time, created_at
                FROM experiments
                ORDER BY created_at DESC
                LIMIT $1 OFFSET $2
                "#
            }
        };

        let mut q = query_as::<_, Experiment>(sql);
        match (status, model_name) {
            (Some(s), Some(m)) => {
                q = q.bind(s).bind(m).bind(limit).bind(offset);
            }
            (Some(s), None) => {
                q = q.bind(s).bind(limit).bind(offset);
            }
            (None, Some(m)) => {
                q = q.bind(m).bind(limit).bind(offset);
            }
            (None, None) => {
                q = q.bind(limit).bind(offset);
            }
        }

        let experiments = q.fetch_all(&self.pool).await?;
        Ok(experiments)
    }

    async fn list_active_experiments(&self, model_name: &str) -> DbResult<Vec<Experiment>> {
        let experiments = query_as::<_, Experiment>(
            r#"
            SELECT id, name, model_name, status, start_time, end_time, created_at
            FROM experiments
            WHERE model_name = $1
              AND status = 'running'
              AND (end_time IS NULL OR end_time > NOW())
            ORDER BY start_time ASC
            "#,
        )
        .bind(model_name)
        .fetch_all(&self.pool)
        .await?;
        Ok(experiments)
    }

    async fn update_experiment(
        &self,
        id: Uuid,
        params: &UpdateExperimentParams,
    ) -> DbResult<Experiment> {
        let start_time = params.start_time.as_ref().and_then(|x| x.clone());
        let end_time = params.end_time.as_ref().and_then(|x| x.clone());
        let experiment = query_as::<_, Experiment>(
            r#"
            UPDATE experiments
            SET
                status = COALESCE($1, status),
                start_time = COALESCE($2, start_time),
                end_time = COALESCE($3, end_time)
            WHERE id = $4
            RETURNING id, name, model_name, status, start_time, end_time, created_at
            "#,
        )
        .bind(&params.status)
        .bind(start_time)
        .bind(end_time)
        .bind(id)
        .fetch_one(&self.pool)
        .await?;
        Ok(experiment)
    }

    async fn start_experiment(&self, id: Uuid) -> DbResult<Experiment> {
        let experiment = query_as::<_, Experiment>(
            r#"
            UPDATE experiments
            SET
                status = 'running',
                start_time = NOW()
            WHERE id = $1
            RETURNING id, name, model_name, status, start_time, end_time, created_at
            "#,
        )
        .bind(id)
        .fetch_one(&self.pool)
        .await?;
        Ok(experiment)
    }

    async fn end_experiment(&self, id: Uuid) -> DbResult<Experiment> {
        let experiment = query_as::<_, Experiment>(
            r#"
            UPDATE experiments
            SET
                status = 'completed',
                end_time = NOW()
            WHERE id = $1
            RETURNING id, name, model_name, status, start_time, end_time, created_at
            "#,
        )
        .bind(id)
        .fetch_one(&self.pool)
        .await?;
        Ok(experiment)
    }

    async fn delete_experiment(&self, id: Uuid) -> DbResult<()> {
        query(
            r#"
            DELETE FROM experiments
            WHERE id = $1
            "#,
        )
        .bind(id)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    async fn create_experiment_group(
        &self,
        params: &CreateExperimentGroupParams,
    ) -> DbResult<ExperimentGroup> {
        let is_control = params.is_control.unwrap_or(false);
        let group = query_as::<_, ExperimentGroup>(
            r#"
            INSERT INTO experiment_groups (experiment_id, group_name, model_version_id, traffic_percent, is_control)
            VALUES ($1, $2, $3, $4, $5)
            RETURNING id, experiment_id, group_name, model_version_id, traffic_percent, is_control
            "#,
        )
        .bind(params.experiment_id)
        .bind(&params.group_name)
        .bind(params.model_version_id)
        .bind(params.traffic_percent)
        .bind(is_control)
        .fetch_one(&self.pool)
        .await?;
        Ok(group)
    }

    async fn get_experiment_group_by_id(
        &self,
        id: Uuid,
    ) -> DbResult<Option<ExperimentGroup>> {
        let group = query_as::<_, ExperimentGroup>(
            r#"
            SELECT id, experiment_id, group_name, model_version_id, traffic_percent, is_control
            FROM experiment_groups
            WHERE id = $1
            "#,
        )
        .bind(id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(group)
    }

    async fn list_experiment_groups(
        &self,
        experiment_id: Uuid,
    ) -> DbResult<Vec<ExperimentGroup>> {
        let groups = query_as::<_, ExperimentGroup>(
            r#"
            SELECT id, experiment_id, group_name, model_version_id, traffic_percent, is_control
            FROM experiment_groups
            WHERE experiment_id = $1
            ORDER BY is_control DESC, group_name ASC
            "#,
        )
        .bind(experiment_id)
        .fetch_all(&self.pool)
        .await?;
        Ok(groups)
    }

    async fn get_control_group(
        &self,
        experiment_id: Uuid,
    ) -> DbResult<Option<ExperimentGroup>> {
        let group = query_as::<_, ExperimentGroup>(
            r#"
            SELECT id, experiment_id, group_name, model_version_id, traffic_percent, is_control
            FROM experiment_groups
            WHERE experiment_id = $1 AND is_control = TRUE
            LIMIT 1
            "#,
        )
        .bind(experiment_id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(group)
    }

    async fn update_group_traffic(
        &self,
        id: Uuid,
        traffic_percent: i32,
    ) -> DbResult<ExperimentGroup> {
        let group = query_as::<_, ExperimentGroup>(
            r#"
            UPDATE experiment_groups
            SET traffic_percent = $1
            WHERE id = $2
            RETURNING id, experiment_id, group_name, model_version_id, traffic_percent, is_control
            "#,
        )
        .bind(traffic_percent)
        .bind(id)
        .fetch_one(&self.pool)
        .await?;
        Ok(group)
    }

    async fn delete_experiment_group(&self, id: Uuid) -> DbResult<()> {
        query(
            r#"
            DELETE FROM experiment_groups
            WHERE id = $1
            "#,
        )
        .bind(id)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    async fn create_experiment_metric(
        &self,
        params: &CreateExperimentMetricParams,
    ) -> DbResult<ExperimentMetric> {
        let metric = query_as::<_, ExperimentMetric>(
            r#"
            INSERT INTO experiment_metrics (experiment_id, metric_name, metric_type, description, unit)
            VALUES ($1, $2, $3, $4, $5)
            RETURNING id, experiment_id, metric_name, metric_type, description, unit
            "#,
        )
        .bind(params.experiment_id)
        .bind(&params.metric_name)
        .bind(&params.metric_type)
        .bind(&params.description)
        .bind(&params.unit)
        .fetch_one(&self.pool)
        .await?;
        Ok(metric)
    }

    async fn list_experiment_metrics(
        &self,
        experiment_id: Uuid,
    ) -> DbResult<Vec<ExperimentMetric>> {
        let metrics = query_as::<_, ExperimentMetric>(
            r#"
            SELECT id, experiment_id, metric_name, metric_type, description, unit
            FROM experiment_metrics
            WHERE experiment_id = $1
            ORDER BY metric_name ASC
            "#,
        )
        .bind(experiment_id)
        .fetch_all(&self.pool)
        .await?;
        Ok(metrics)
    }

    async fn delete_experiment_metric(&self, id: Uuid) -> DbResult<()> {
        query(
            r#"
            DELETE FROM experiment_metrics
            WHERE id = $1
            "#,
        )
        .bind(id)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    async fn create_experiment_result(
        &self,
        params: &CreateExperimentResultParams,
    ) -> DbResult<ExperimentResult> {
        let result = query_as::<_, ExperimentResult>(
            r#"
            INSERT INTO experiment_results (
                experiment_id, group_name, metric_name, sample_count,
                mean_value, std_value, p95_value, p99_value
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
            RETURNING id, experiment_id, group_name, metric_name, sample_count,
                      mean_value, std_value, p95_value, p99_value, computed_at
            "#,
        )
        .bind(params.experiment_id)
        .bind(&params.group_name)
        .bind(&params.metric_name)
        .bind(params.sample_count)
        .bind(params.mean_value)
        .bind(params.std_value)
        .bind(params.p95_value)
        .bind(params.p99_value)
        .fetch_one(&self.pool)
        .await?;
        Ok(result)
    }

    async fn list_experiment_results(
        &self,
        experiment_id: Uuid,
    ) -> DbResult<Vec<ExperimentResult>> {
        let results = query_as::<_, ExperimentResult>(
            r#"
            SELECT id, experiment_id, group_name, metric_name, sample_count,
                   mean_value, std_value, p95_value, p99_value, computed_at
            FROM experiment_results
            WHERE experiment_id = $1
            ORDER BY group_name ASC, metric_name ASC
            "#,
        )
        .bind(experiment_id)
        .fetch_all(&self.pool)
        .await?;
        Ok(results)
    }

    async fn get_group_results(
        &self,
        experiment_id: Uuid,
        group_name: &str,
    ) -> DbResult<Vec<ExperimentResult>> {
        let results = query_as::<_, ExperimentResult>(
            r#"
            SELECT id, experiment_id, group_name, metric_name, sample_count,
                   mean_value, std_value, p95_value, p99_value, computed_at
            FROM experiment_results
            WHERE experiment_id = $1 AND group_name = $2
            ORDER BY metric_name ASC
            "#,
        )
        .bind(experiment_id)
        .bind(group_name)
        .fetch_all(&self.pool)
        .await?;
        Ok(results)
    }

    async fn get_metric_results(
        &self,
        experiment_id: Uuid,
        metric_name: &str,
    ) -> DbResult<Vec<ExperimentResult>> {
        let results = query_as::<_, ExperimentResult>(
            r#"
            SELECT id, experiment_id, group_name, metric_name, sample_count,
                   mean_value, std_value, p95_value, p99_value, computed_at
            FROM experiment_results
            WHERE experiment_id = $1 AND metric_name = $2
            ORDER BY group_name ASC
            "#,
        )
        .bind(experiment_id)
        .bind(metric_name)
        .fetch_all(&self.pool)
        .await?;
        Ok(results)
    }

    async fn upsert_experiment_result(
        &self,
        params: &CreateExperimentResultParams,
    ) -> DbResult<ExperimentResult> {
        let result = query_as::<_, ExperimentResult>(
            r#"
            INSERT INTO experiment_results (
                experiment_id, group_name, metric_name, sample_count,
                mean_value, std_value, p95_value, p99_value
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
            ON CONFLICT (experiment_id, group_name, metric_name) DO UPDATE SET
                sample_count = EXCLUDED.sample_count,
                mean_value = EXCLUDED.mean_value,
                std_value = EXCLUDED.std_value,
                p95_value = EXCLUDED.p95_value,
                p99_value = EXCLUDED.p99_value,
                computed_at = NOW()
            RETURNING id, experiment_id, group_name, metric_name, sample_count,
                      mean_value, std_value, p95_value, p99_value, computed_at
            "#,
        )
        .bind(params.experiment_id)
        .bind(&params.group_name)
        .bind(&params.metric_name)
        .bind(params.sample_count)
        .bind(params.mean_value)
        .bind(params.std_value)
        .bind(params.p95_value)
        .bind(params.p99_value)
        .fetch_one(&self.pool)
        .await?;
        Ok(result)
    }

    async fn delete_experiment_results(&self, experiment_id: Uuid) -> DbResult<()> {
        query(
            r#"
            DELETE FROM experiment_results
            WHERE experiment_id = $1
            "#,
        )
        .bind(experiment_id)
        .execute(&self.pool)
        .await?;
        Ok(())
    }
}
