use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use s3::creds::Credentials;
use s3::region::Region;
use s3::Bucket;
use serde::{Deserialize, Serialize};
use tokio::fs;
use tracing::{debug, info, warn};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Object {
    pub key: String,
    pub size_bytes: u64,
    pub last_modified: Option<String>,
    pub etag: Option<String>,
}

#[derive(Clone)]
pub struct MinioClient {
    bucket: Bucket,
    bucket_name: String,
}

impl MinioClient {
    pub fn new(
        endpoint: &str,
        access_key: &str,
        secret_key: &str,
        region: &str,
        bucket: &str,
        use_ssl: bool,
    ) -> Result<Self> {
        let endpoint_full = if use_ssl {
            format!("https://{}", endpoint)
        } else {
            format!("http://{}", endpoint)
        };

        let s3_region = Region::Custom {
            region: region.to_string(),
            endpoint: endpoint_full,
        };

        let credentials = Credentials::new(
            Some(access_key),
            Some(secret_key),
            None,
            None,
            None,
        )
        .context("Failed to create MinIO credentials")?;

        let bucket = Bucket::new(bucket, s3_region, credentials)
            .context("Failed to create MinIO bucket client")?
            .with_path_style();

        let bucket_name = bucket.name.clone();

        Ok(Self {
            bucket,
            bucket_name,
        })
    }

    fn build_object_path(model_name: &str, version: &str) -> String {
        format!("models/{}/{}/model.bin", model_name, version)
    }

    pub async fn upload_model(
        &self,
        model_name: &str,
        version: &str,
        local_path: impl AsRef<Path>,
    ) -> Result<String> {
        let object_path = Self::build_object_path(model_name, version);
        let local = local_path.as_ref();

        info!(
            "Uploading model: {} -> s3://{}/{}",
            local.display(),
            self.bucket_name,
            object_path
        );

        let file_content = fs::read(local)
            .await
            .with_context(|| format!("Failed to read local file: {}", local.display()))?;

        self.bucket
            .put_object(&object_path, &file_content)
            .await
            .context("Failed to upload model to MinIO")?;

        info!(
            "Model uploaded: s3://{}/{} ({} bytes)",
            self.bucket_name,
            object_path,
            file_content.len()
        );

        Ok(object_path)
    }

    pub async fn download_model(
        &self,
        model_name: &str,
        version: &str,
        local_dir: impl AsRef<Path>,
    ) -> Result<PathBuf> {
        let object_path = Self::build_object_path(model_name, version);
        let dir = local_dir.as_ref();

        fs::create_dir_all(dir)
            .await
            .with_context(|| format!("Failed to create directory: {}", dir.display()))?;

        let local_path = dir.join(format!("{}-{}.bin", model_name, version));

        info!(
            "Downloading model: s3://{}/{} -> {}",
            self.bucket_name,
            object_path,
            local_path.display()
        );

        let response_data = self
            .bucket
            .get_object(&object_path)
            .await
            .with_context(|| {
                format!(
                    "Failed to download object s3://{}/{}",
                    self.bucket_name, object_path
                )
            })?;

        let data: Vec<u8> = response_data.to_vec();

        fs::write(&local_path, &data)
            .await
            .with_context(|| format!("Failed to write file: {}", local_path.display()))?;

        info!(
            "Model downloaded: {} ({} bytes)",
            local_path.display(),
            data.len()
        );

        Ok(local_path)
    }

    pub async fn delete_model(&self, model_name: &str, version: &str) -> Result<()> {
        let object_path = Self::build_object_path(model_name, version);

        info!(
            "Deleting model: s3://{}/{}",
            self.bucket_name, object_path
        );

        self.bucket
            .delete_object(&object_path)
            .await
            .with_context(|| {
                format!(
                    "Failed to delete object s3://{}/{}",
                    self.bucket_name, object_path
                )
            })?;

        info!(
            "Model deleted: s3://{}/{}",
            self.bucket_name, object_path
        );

        Ok(())
    }

    pub async fn model_exists(&self, model_name: &str, version: &str) -> bool {
        let object_path = Self::build_object_path(model_name, version);

        debug!("Checking existence: s3://{}/{}", self.bucket_name, object_path);

        match self.bucket.head_object(&object_path).await {
            Ok((_, status_code)) => {
                debug!(
                    "Head object s3://{}/{} status: {}",
                    self.bucket_name, object_path, status_code
                );
                (200..300).contains(&status_code)
            }
            Err(e) => {
                warn!(
                    "Head object s3://{}/{} failed: {}",
                    self.bucket_name, object_path, e
                );
                false
            }
        }
    }

    pub async fn get_model_size(&self, model_name: &str, version: &str) -> u64 {
        let object_path = Self::build_object_path(model_name, version);

        match self.bucket.head_object(&object_path).await {
            Ok((head_result, status_code)) if (200..300).contains(&status_code) => {
                head_result.content_length.unwrap_or(0) as u64
            }
            _ => 0,
        }
    }

    pub async fn list_models(&self, prefix: &str) -> Result<Vec<Object>> {
        let prefix_full = format!("models/{}", prefix.trim_start_matches("models/"));

        debug!("Listing objects with prefix: {}", prefix_full);

        let results = self
            .bucket
            .list(prefix_full.clone(), None)
            .await
            .context("Failed to list objects in MinIO")?;

        let mut objects = Vec::new();

        for list in results {
            for obj in list.contents {
                let etag = obj.e_tag.as_ref().map(|s| s.trim_matches('"').to_string());
                objects.push(Object {
                    key: obj.key,
                    size_bytes: obj.size as u64,
                    last_modified: Some(obj.last_modified),
                    etag,
                });
            }
        }

        debug!("Listed {} objects with prefix {}", objects.len(), prefix_full);

        Ok(objects)
    }

    pub fn bucket_name(&self) -> &str {
        &self.bucket_name
    }

    pub fn bucket(&self) -> &Bucket {
        &self.bucket
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MinioConfig {
    pub endpoint: String,
    pub access_key: String,
    pub secret_key: String,
    pub region: String,
    pub bucket: String,
    pub use_ssl: bool,
}

impl Default for MinioConfig {
    fn default() -> Self {
        Self {
            endpoint: "localhost:9000".to_string(),
            access_key: "minioadmin".to_string(),
            secret_key: "minioadmin".to_string(),
            region: "us-east-1".to_string(),
            bucket: "models".to_string(),
            use_ssl: false,
        }
    }
}

pub type ObjectInfo = Object;

pub type MinioStorage = MinioClient;

impl MinioClient {
    pub fn mock() -> Self {
        let config = MinioConfig::default();
        MinioClient::new(
            &config.endpoint,
            &config.access_key,
            &config.secret_key,
            &config.region,
            &config.bucket,
            config.use_ssl,
        ).unwrap_or_else(|_| {
            panic!("Failed to create mock MinioStorage")
        })
    }
}
