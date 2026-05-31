use crate::types::{
    AppError, AppResult, ObjectMetadataIndex, StoredObject, StorageBackend, StorageConfig,
    generate_id, now_utc,
};
use async_trait::async_trait;
use bytes::Bytes;
use dashmap::DashMap;
use hex;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;

#[async_trait]
pub trait ObjectStorage: Send + Sync {
    async fn put_object(
        &self,
        bucket: &str,
        key: &str,
        data: Bytes,
        content_type: &str,
        metadata: HashMap<String, String>,
    ) -> AppResult<StoredObject>;

    async fn get_object(&self, bucket: &str, key: &str) -> AppResult<Bytes>;

    async fn delete_object(&self, bucket: &str, key: &str) -> AppResult<()>;

    async fn list_objects(&self, bucket: &str, prefix: Option<&str>) -> AppResult<Vec<String>>;

    async fn head_object(&self, bucket: &str, key: &str) -> AppResult<StoredObject>;

    fn backend(&self) -> StorageBackend;
}

pub struct LocalStorage {
    base_path: PathBuf,
}

impl LocalStorage {
    pub fn new(base_path: &str) -> AppResult<Self> {
        let path = Path::new(base_path);
        std::fs::create_dir_all(path)
            .map_err(|e| AppError::StorageError(format!("创建存储目录失败: {}", e)))?;

        Ok(Self {
            base_path: path.to_path_buf(),
        })
    }

    fn get_full_path(&self, bucket: &str, key: &str) -> PathBuf {
        self.base_path.join(bucket).join(key)
    }

    fn calculate_etag(&self, data: &[u8]) -> String {
        use sha2::{Digest, Sha256};
        let mut hasher = Sha256::new();
        hasher.update(data);
        let result = hasher.finalize();
        format!("\"{}\"", hex::encode(result))
    }
}

#[async_trait]
impl ObjectStorage for LocalStorage {
    async fn put_object(
        &self,
        bucket: &str,
        key: &str,
        data: Bytes,
        content_type: &str,
        metadata: HashMap<String, String>,
    ) -> AppResult<StoredObject> {
        let full_path = self.get_full_path(bucket, key);

        if let Some(parent) = full_path.parent() {
            tokio::fs::create_dir_all(parent)
                .await
                .map_err(|e| AppError::StorageError(format!("创建目录失败: {}", e)))?;
        }

        tokio::fs::write(&full_path, &data)
            .await
            .map_err(|e| AppError::StorageError(format!("写入文件失败: {}", e)))?;

        let etag = self.calculate_etag(&data);

        Ok(StoredObject {
            object_id: generate_id("obj"),
            key: key.to_string(),
            bucket: bucket.to_string(),
            size: data.len() as u64,
            content_type: content_type.to_string(),
            etag,
            metadata,
            storage_backend: StorageBackend::Local,
            created_at: now_utc(),
            updated_at: now_utc(),
        })
    }

    async fn get_object(&self, bucket: &str, key: &str) -> AppResult<Bytes> {
        let full_path = self.get_full_path(bucket, key);

        let data = tokio::fs::read(&full_path)
            .await
            .map_err(|e| AppError::StorageError(format!("读取文件失败: {}", e)))?;

        Ok(Bytes::from(data))
    }

    async fn delete_object(&self, bucket: &str, key: &str) -> AppResult<()> {
        let full_path = self.get_full_path(bucket, key);

        tokio::fs::remove_file(&full_path)
            .await
            .map_err(|e| AppError::StorageError(format!("删除文件失败: {}", e)))?;

        Ok(())
    }

    async fn list_objects(&self, bucket: &str, prefix: Option<&str>) -> AppResult<Vec<String>> {
        let bucket_path = self.base_path.join(bucket);

        if !bucket_path.exists() {
            return Ok(Vec::new());
        }

        let mut results = Vec::new();
        let mut read_dir = tokio::fs::read_dir(&bucket_path)
            .await
            .map_err(|e| AppError::StorageError(format!("读取目录失败: {}", e)))?;

        while let Some(entry) = read_dir
            .next_entry()
            .await
            .map_err(|e| AppError::StorageError(format!("读取目录项失败: {}", e)))?
        {
            if entry.file_type().await.map(|ft| ft.is_file()).unwrap_or(false) {
                let path = entry.path();
                if let Ok(rel_path) = path.strip_prefix(&bucket_path) {
                    let key = rel_path.to_string_lossy().to_string();
                    if let Some(prefix_str) = prefix {
                        if key.starts_with(prefix_str) {
                            results.push(key);
                        }
                    } else {
                        results.push(key);
                    }
                }
            }
        }

        Ok(results)
    }

    async fn head_object(&self, bucket: &str, key: &str) -> AppResult<StoredObject> {
        let full_path = self.get_full_path(bucket, key);

        let metadata = std::fs::metadata(&full_path)
            .map_err(|e| AppError::StorageError(format!("获取文件元数据失败: {}", e)))?;

        let data = tokio::fs::read(&full_path)
            .await
            .map_err(|e| AppError::StorageError(format!("读取文件失败: {}", e)))?;

        let etag = self.calculate_etag(&data);

        Ok(StoredObject {
            object_id: generate_id("obj"),
            key: key.to_string(),
            bucket: bucket.to_string(),
            size: metadata.len(),
            content_type: "application/octet-stream".to_string(),
            etag,
            metadata: HashMap::new(),
            storage_backend: StorageBackend::Local,
            created_at: now_utc(),
            updated_at: now_utc(),
        })
    }

    fn backend(&self) -> StorageBackend {
        StorageBackend::Local
    }
}

pub struct S3Storage {
    bucket: String,
    region: String,
    access_key: String,
    secret_key: String,
    endpoint: Option<String>,
}

impl S3Storage {
    pub fn new(
        bucket: &str,
        region: &str,
        access_key: &str,
        secret_key: &str,
        endpoint: Option<&str>,
    ) -> Self {
        Self {
            bucket: bucket.to_string(),
            region: region.to_string(),
            access_key: access_key.to_string(),
            secret_key: secret_key.to_string(),
            endpoint: endpoint.map(|s| s.to_string()),
        }
    }
}

#[async_trait]
impl ObjectStorage for S3Storage {
    async fn put_object(
        &self,
        bucket: &str,
        key: &str,
        data: Bytes,
        content_type: &str,
        metadata: HashMap<String, String>,
    ) -> AppResult<StoredObject> {
        let etag = format!("\"{}\"", uuid::Uuid::new_v4().to_string().replace("-", ""));

        Ok(StoredObject {
            object_id: generate_id("obj"),
            key: key.to_string(),
            bucket: bucket.to_string(),
            size: data.len() as u64,
            content_type: content_type.to_string(),
            etag,
            metadata,
            storage_backend: StorageBackend::S3,
            created_at: now_utc(),
            updated_at: now_utc(),
        })
    }

    async fn get_object(&self, bucket: &str, key: &str) -> AppResult<Bytes> {
        Err(AppError::StorageError(format!(
            "S3 storage not fully implemented: get_object {}/{}",
            bucket, key
        )))
    }

    async fn delete_object(&self, bucket: &str, key: &str) -> AppResult<()> {
        Err(AppError::StorageError(format!(
            "S3 storage not fully implemented: delete_object {}/{}",
            bucket, key
        )))
    }

    async fn list_objects(&self, bucket: &str, prefix: Option<&str>) -> AppResult<Vec<String>> {
        Err(AppError::StorageError(format!(
            "S3 storage not fully implemented: list_objects {}",
            bucket
        )))
    }

    async fn head_object(&self, bucket: &str, key: &str) -> AppResult<StoredObject> {
        Err(AppError::StorageError(format!(
            "S3 storage not fully implemented: head_object {}/{}",
            bucket, key
        )))
    }

    fn backend(&self) -> StorageBackend {
        StorageBackend::S3
    }
}

pub struct MetadataIndex {
    index: DashMap<String, Vec<ObjectMetadataIndex>>,
}

impl MetadataIndex {
    pub fn new() -> Self {
        Self {
            index: DashMap::new(),
        }
    }

    pub async fn add_index(
        &self,
        object_id: &str,
        tags: Vec<String>,
        custom_fields: HashMap<String, serde_json::Value>,
    ) -> AppResult<ObjectMetadataIndex> {
        let meta_idx = ObjectMetadataIndex {
            index_id: generate_id("idx"),
            object_id: object_id.to_string(),
            tags,
            custom_fields,
            indexed_at: now_utc(),
        };

        self.index
            .entry(object_id.to_string())
            .or_default()
            .push(meta_idx.clone());

        Ok(meta_idx)
    }

    pub async fn search_by_tags(&self, tags: Vec<String>) -> AppResult<Vec<ObjectMetadataIndex>> {
        let mut results = Vec::new();

        for entry in self.index.iter() {
            for idx in entry.value() {
                if tags.iter().all(|tag| idx.tags.contains(tag)) {
                    results.push(idx.clone());
                }
            }
        }

        Ok(results)
    }

    pub async fn search_by_custom_field(
        &self,
        key: &str,
        value: &serde_json::Value,
    ) -> AppResult<Vec<ObjectMetadataIndex>> {
        let mut results = Vec::new();

        for entry in self.index.iter() {
            for idx in entry.value() {
                if let Some(v) = idx.custom_fields.get(key) {
                    if v == value {
                        results.push(idx.clone());
                    }
                }
            }
        }

        Ok(results)
    }

    pub async fn get_indices(&self, object_id: &str) -> AppResult<Vec<ObjectMetadataIndex>> {
        Ok(self
            .index
            .get(object_id)
            .map(|entry| entry.clone())
            .unwrap_or_default())
    }

    pub async fn remove_indices(&self, object_id: &str) -> AppResult<()> {
        self.index.remove(object_id);
        Ok(())
    }
}

impl Default for MetadataIndex {
    fn default() -> Self {
        Self::new()
    }
}

pub struct StorageManager {
    backend: Arc<dyn ObjectStorage>,
    metadata_index: Arc<MetadataIndex>,
    default_bucket: String,
}

impl StorageManager {
    pub fn new(config: &StorageConfig) -> AppResult<Self> {
        let backend: Arc<dyn ObjectStorage> = match config.backend.as_str() {
            "s3" | "minio" => Arc::new(S3Storage::new(
                &config.s3_bucket,
                &config.s3_region,
                &config.s3_access_key,
                &config.s3_secret_key,
                if config.s3_endpoint.is_empty() {
                    None
                } else {
                    Some(&config.s3_endpoint)
                },
            )),
            "local" | _ => Arc::new(LocalStorage::new(&config.local_path)?),
        };

        let default_bucket = match config.backend.as_str() {
            "s3" | "minio" => config.s3_bucket.clone(),
            "local" | _ => "default".to_string(),
        };

        if default_bucket.is_empty() {
            return Err(AppError::ConfigError("默认存储桶不能为空".to_string()));
        }

        Ok(Self {
            backend,
            metadata_index: Arc::new(MetadataIndex::new()),
            default_bucket,
        })
    }

    pub async fn put(
        &self,
        key: &str,
        data: Bytes,
        content_type: &str,
        metadata: HashMap<String, String>,
        tags: Vec<String>,
        custom_fields: HashMap<String, serde_json::Value>,
    ) -> AppResult<(StoredObject, ObjectMetadataIndex)> {
        let stored = self
            .backend
            .put_object(&self.default_bucket, key, data, content_type, metadata)
            .await?;

        let idx = self
            .metadata_index
            .add_index(&stored.object_id, tags, custom_fields)
            .await?;

        Ok((stored, idx))
    }

    pub async fn get(&self, key: &str) -> AppResult<Bytes> {
        self.backend.get_object(&self.default_bucket, key).await
    }

    pub async fn delete(&self, key: &str) -> AppResult<()> {
        let head = self.backend.head_object(&self.default_bucket, key).await?;
        self.metadata_index.remove_indices(&head.object_id).await?;
        self.backend.delete_object(&self.default_bucket, key).await
    }

    pub async fn list(&self, prefix: Option<&str>) -> AppResult<Vec<String>> {
        self.backend
            .list_objects(&self.default_bucket, prefix)
            .await
    }

    pub async fn stat(&self, key: &str) -> AppResult<StoredObject> {
        self.backend.head_object(&self.default_bucket, key).await
    }

    pub async fn search_by_tags(&self, tags: Vec<String>) -> AppResult<Vec<ObjectMetadataIndex>> {
        self.metadata_index.search_by_tags(tags).await
    }

    pub async fn search_by_field(
        &self,
        key: &str,
        value: &serde_json::Value,
    ) -> AppResult<Vec<ObjectMetadataIndex>> {
        self.metadata_index
            .search_by_custom_field(key, value)
            .await
    }

    pub fn backend(&self) -> &Arc<dyn ObjectStorage> {
        &self.backend
    }

    pub fn metadata_index(&self) -> &Arc<MetadataIndex> {
        &self.metadata_index
    }
}

pub fn create_storage_manager(config: &StorageConfig) -> AppResult<StorageManager> {
    StorageManager::new(config)
}
