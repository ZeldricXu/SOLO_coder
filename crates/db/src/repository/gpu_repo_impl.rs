use async_trait::async_trait;
use sqlx::{query, query_as, PgPool};
use uuid::Uuid;

use crate::error::DbResult;
use crate::repository::gpu_repo::{
    CreateGpuDeviceParams, GpuDevice, GpuRepository, UpdateGpuDeviceParams,
};

pub struct PgGpuRepository {
    pub pool: PgPool,
}

impl PgGpuRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}

#[async_trait]
impl GpuRepository for PgGpuRepository {
    async fn create_gpu_device(
        &self,
        params: &CreateGpuDeviceParams,
    ) -> DbResult<GpuDevice> {
        let device = query_as::<_, GpuDevice>(
            r#"
            INSERT INTO gpu_devices (node_id, gpu_uuid, name, total_memory_mb, driver_version)
            VALUES ($1, $2, $3, $4, $5)
            RETURNING id, node_id, gpu_uuid, name, total_memory_mb, driver_version, created_at
            "#,
        )
        .bind(&params.node_id)
        .bind(&params.gpu_uuid)
        .bind(&params.name)
        .bind(params.total_memory_mb)
        .bind(&params.driver_version)
        .fetch_one(&self.pool)
        .await?;
        Ok(device)
    }

    async fn get_gpu_device_by_id(&self, id: Uuid) -> DbResult<Option<GpuDevice>> {
        let device = query_as::<_, GpuDevice>(
            r#"
            SELECT id, node_id, gpu_uuid, name, total_memory_mb, driver_version, created_at
            FROM gpu_devices
            WHERE id = $1
            "#,
        )
        .bind(id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(device)
    }

    async fn get_gpu_device_by_uuid(
        &self,
        gpu_uuid: &str,
    ) -> DbResult<Option<GpuDevice>> {
        let device = query_as::<_, GpuDevice>(
            r#"
            SELECT id, node_id, gpu_uuid, name, total_memory_mb, driver_version, created_at
            FROM gpu_devices
            WHERE gpu_uuid = $1
            "#,
        )
        .bind(gpu_uuid)
        .fetch_optional(&self.pool)
        .await?;
        Ok(device)
    }

    async fn list_gpu_devices(
        &self,
        node_id: Option<&str>,
        min_memory_mb: Option<i32>,
        limit: i64,
        offset: i64,
    ) -> DbResult<Vec<GpuDevice>> {
        let sql = match (node_id.is_some(), min_memory_mb.is_some()) {
            (true, true) => {
                r#"
                SELECT id, node_id, gpu_uuid, name, total_memory_mb, driver_version, created_at
                FROM gpu_devices
                WHERE node_id = $1 AND total_memory_mb >= $2
                ORDER BY total_memory_mb DESC
                LIMIT $3 OFFSET $4
                "#
            }
            (true, false) => {
                r#"
                SELECT id, node_id, gpu_uuid, name, total_memory_mb, driver_version, created_at
                FROM gpu_devices
                WHERE node_id = $1
                ORDER BY total_memory_mb DESC
                LIMIT $2 OFFSET $3
                "#
            }
            (false, true) => {
                r#"
                SELECT id, node_id, gpu_uuid, name, total_memory_mb, driver_version, created_at
                FROM gpu_devices
                WHERE total_memory_mb >= $1
                ORDER BY total_memory_mb DESC
                LIMIT $2 OFFSET $3
                "#
            }
            (false, false) => {
                r#"
                SELECT id, node_id, gpu_uuid, name, total_memory_mb, driver_version, created_at
                FROM gpu_devices
                ORDER BY total_memory_mb DESC
                LIMIT $1 OFFSET $2
                "#
            }
        };

        let mut q = query_as::<_, GpuDevice>(sql);
        match (node_id, min_memory_mb) {
            (Some(n), Some(m)) => {
                q = q.bind(n).bind(m).bind(limit).bind(offset);
            }
            (Some(n), None) => {
                q = q.bind(n).bind(limit).bind(offset);
            }
            (None, Some(m)) => {
                q = q.bind(m).bind(limit).bind(offset);
            }
            (None, None) => {
                q = q.bind(limit).bind(offset);
            }
        }

        let devices = q.fetch_all(&self.pool).await?;
        Ok(devices)
    }

    async fn list_gpu_devices_by_node(&self, node_id: &str) -> DbResult<Vec<GpuDevice>> {
        let devices = query_as::<_, GpuDevice>(
            r#"
            SELECT id, node_id, gpu_uuid, name, total_memory_mb, driver_version, created_at
            FROM gpu_devices
            WHERE node_id = $1
            ORDER BY name ASC
            "#,
        )
        .bind(node_id)
        .fetch_all(&self.pool)
        .await?;
        Ok(devices)
    }

    async fn list_available_gpu_devices(
        &self,
        required_memory_mb: i32,
    ) -> DbResult<Vec<GpuDevice>> {
        let devices = query_as::<_, GpuDevice>(
            r#"
            SELECT id, node_id, gpu_uuid, name, total_memory_mb, driver_version, created_at
            FROM gpu_devices
            WHERE total_memory_mb >= $1
            ORDER BY total_memory_mb ASC
            "#,
        )
        .bind(required_memory_mb)
        .fetch_all(&self.pool)
        .await?;
        Ok(devices)
    }

    async fn update_gpu_device(
        &self,
        id: Uuid,
        params: &UpdateGpuDeviceParams,
    ) -> DbResult<GpuDevice> {
        let driver_version = params.driver_version.as_ref().and_then(|x| x.clone());
        let device = query_as::<_, GpuDevice>(
            r#"
            UPDATE gpu_devices
            SET
                name = COALESCE($1, name),
                total_memory_mb = COALESCE($2, total_memory_mb),
                driver_version = COALESCE($3, driver_version),
                node_id = COALESCE($4, node_id)
            WHERE id = $5
            RETURNING id, node_id, gpu_uuid, name, total_memory_mb, driver_version, created_at
            "#,
        )
        .bind(&params.name)
        .bind(params.total_memory_mb)
        .bind(driver_version)
        .bind(&params.node_id)
        .bind(id)
        .fetch_one(&self.pool)
        .await?;
        Ok(device)
    }

    async fn delete_gpu_device(&self, id: Uuid) -> DbResult<()> {
        query(
            r#"
            DELETE FROM gpu_devices
            WHERE id = $1
            "#,
        )
        .bind(id)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    async fn delete_gpu_device_by_uuid(&self, gpu_uuid: &str) -> DbResult<()> {
        query(
            r#"
            DELETE FROM gpu_devices
            WHERE gpu_uuid = $1
            "#,
        )
        .bind(gpu_uuid)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    async fn count_gpu_devices(&self) -> DbResult<i64> {
        let count: (i64,) = query_as(
            r#"
            SELECT COUNT(*) FROM gpu_devices
            "#,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(count.0)
    }

    async fn count_gpu_devices_by_node(&self, node_id: &str) -> DbResult<i64> {
        let count: (i64,) = query_as(
            r#"
            SELECT COUNT(*) FROM gpu_devices
            WHERE node_id = $1
            "#,
        )
        .bind(node_id)
        .fetch_one(&self.pool)
        .await?;
        Ok(count.0)
    }
}
