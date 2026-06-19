use std::path::{Path, PathBuf};
use std::sync::Arc;
use parking_lot::Mutex;

use dashmap::DashMap;
use metrics::{counter, gauge, histogram};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;
use uuid::Uuid;

extern crate s3;
use s3 as s3_crate;

use crate::config::{AppConfig, StorageBackend};
use collab_crdt::{CrdtSnapshot, YataDocument};
use crate::storage::{OplogRepository, SnapshotRecord, StorageError};
use crate::ws::Room;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum SnapshotStatus {
    Pending,
    InProgress,
    Completed,
    Failed,
}

#[derive(Debug, Clone)]
struct PendingSnapshot {
    document_id: Uuid,
    status: SnapshotStatus,
    scheduled_at: chrono::DateTime<chrono::Utc>,
    attempt: u32,
}

pub struct SnapshotService {
    config: AppConfig,
    repo: Arc<OplogRepository>,
    pending: Arc<DashMap<Uuid, PendingSnapshot>>,
    local_dir: PathBuf,
}

impl SnapshotService {
    pub fn new(config: AppConfig, repo: OplogRepository) -> Self {
        let local_dir = PathBuf::from(&config.snapshot.local_dir);
        std::fs::create_dir_all(&local_dir).ok();

        Self {
            config,
            repo: Arc::new(repo),
            pending: Arc::new(DashMap::new()),
            local_dir,
        }
    }

    pub fn schedule_snapshot(&self, document_id: Uuid) -> bool {
        if self.pending.contains_key(&document_id) {
            return false;
        }
        self.pending.insert(
            document_id,
            PendingSnapshot {
                document_id,
                status: SnapshotStatus::Pending,
                scheduled_at: chrono::Utc::now(),
                attempt: 0,
            },
        );
        true
    }

    pub fn should_snapshot(&self, room: &Room) -> bool {
        let version = *room.current_version.lock();
        version >= self.config.snapshot.min_ops_before_snapshot
    }

    pub async fn create_snapshot_for_room(
        &self,
        room: &Room,
        created_by: String,
    ) -> Result<SnapshotRecord, StorageError> {
        let (snapshot, version, doc_id) = {
            let document = room.document.read();
            let s = document.snapshot();
            let v = *room.current_version.lock() as i64;
            let id = room.document_id;
            (s, v, id)
        };

        let id = Uuid::new_v4();
        let (data, checksum) = serialize_snapshot(&snapshot, self.config.storage.snapshot_compression)?;
        let size_bytes = data.len() as i64;

        let storage_path = self.store_snapshot_data(doc_id, id, &data).await?;

        let vector_clock = serde_json::to_value(&snapshot.vector_clock)
            .map_err(|e| StorageError::Serialization(e.to_string()))?;

        let record = SnapshotRecord {
            id,
            document_id: doc_id,
            version,
            ops_count: snapshot.ops_count as i64,
            size_bytes,
            storage_backend: match &self.config.snapshot.storage_backend {
                StorageBackend::Local => "local".to_string(),
                StorageBackend::S3 => "s3".to_string(),
            },
            storage_path,
            checksum,
            created_at: chrono::Utc::now(),
            created_by,
            compressed: self.config.storage.snapshot_compression,
            vector_clock,
        };

        self.repo.save_snapshot_record(&record).await?;
        self.pending.remove(&doc_id);

        counter!("collab_snapshots_created_total").increment(1);
        histogram!("collab_snapshot_size_bytes").record(size_bytes as f64);

        Ok(record)
    }

    async fn store_snapshot_data(
        &self,
        document_id: Uuid,
        snapshot_id: Uuid,
        data: &[u8],
    ) -> Result<String, StorageError> {
        match &self.config.snapshot.storage_backend {
            StorageBackend::Local => {
                let path = self.local_dir
                    .join(document_id.to_string());
                tokio::fs::create_dir_all(&path).await?;

                let file_path = path.join(format!("{}.snap", snapshot_id));
                let mut file = tokio::fs::File::create(&file_path).await?;
                file.write_all(data).await?;
                file.flush().await?;

                Ok(file_path.to_string_lossy().to_string())
            }
            StorageBackend::S3 => {
                let bucket = self.config.snapshot.s3_bucket
                    .as_ref()
                    .ok_or_else(|| StorageError::S3("S3 bucket not configured".into()))?;

                let prefix = self.config.snapshot.s3_prefix
                    .as_deref()
                    .unwrap_or("snapshots");

                let key = format!("{}/{}/{}.snap", prefix, document_id, snapshot_id);

                let region = match &self.config.snapshot.s3_region {
                    Some(r) => s3_crate::Region::Custom {
                        region: r.clone(),
                        endpoint: format!("s3.{}.amazonaws.com", r),
                    },
                    None => s3_crate::Region::UsEast1,
                };

                let creds = s3_crate::creds::Credentials::default()
                    .map_err(|e| StorageError::S3(e.to_string()))?;

                let bucket_obj = s3_crate::Bucket::new(bucket, region, creds)
                    .map_err(|e| StorageError::S3(e.to_string()))?
                    .with_path_style();

                let response = bucket_obj.put_object(&key, data)
                    .await
                    .map_err(|e| StorageError::S3(e.to_string()))?;
                let _status = response.status_code();

                Ok(format!("s3://{}/{}", bucket, key))
            }
        }
    }

    pub async fn load_latest_snapshot(
        &self,
        document_id: Uuid,
    ) -> Result<Option<(CrdtSnapshot, SnapshotRecord)>, StorageError> {
        let record = match self.repo.get_latest_snapshot(document_id).await? {
            Some(r) => r,
            None => return Ok(None),
        };

        let data = self.load_snapshot_data(&record).await?;
        let snapshot = deserialize_snapshot(&data, record.compressed)?;
        Ok(Some((snapshot, record)))
    }

    async fn load_snapshot_data(&self, record: &SnapshotRecord) -> Result<Vec<u8>, StorageError> {
        match record.storage_backend.as_str() {
            "local" => {
                let path = Path::new(&record.storage_path);
                let data = tokio::fs::read(path).await?;
                verify_checksum(&data, &record.checksum)?;
                Ok(data)
            }
            "s3" => {
                let parts: Vec<&str> = record.storage_path
                    .strip_prefix("s3://")
                    .unwrap_or("")
                    .splitn(2, '/')
                    .collect();

                if parts.len() != 2 {
                    return Err(StorageError::S3("Invalid S3 path".into()));
                }

                let bucket_name = parts[0];
                let key = parts[1];

                let region = match &self.config.snapshot.s3_region {
                    Some(r) => s3_crate::Region::Custom {
                        region: r.clone(),
                        endpoint: format!("s3.{}.amazonaws.com", r),
                    },
                    None => s3_crate::Region::UsEast1,
                };

                let creds = s3_crate::creds::Credentials::default()
                    .map_err(|e| StorageError::S3(e.to_string()))?;

                let bucket = s3_crate::Bucket::new(bucket_name, region, creds)
                    .map_err(|e| StorageError::S3(e.to_string()))?
                    .with_path_style();

                let response = bucket.get_object(key)
                    .await
                    .map_err(|e| StorageError::S3(e.to_string()))?;
                let data_vec: Vec<u8> = response.as_slice().to_vec();

                verify_checksum(&data_vec, &record.checksum)?;
                Ok(data_vec.to_vec())
            }
            other => Err(StorageError::S3(format!("Unknown backend: {}", other))),
        }
    }

    pub async fn load_document_with_latest_snapshot(
        &self,
        document_id: Uuid,
        client_id: u64,
    ) -> Result<Option<(YataDocument, i64)>, StorageError> {
        let (snapshot, record) = match self.load_latest_snapshot(document_id).await? {
            Some((s, r)) => (s, r),
            None => return Ok(None),
        };

        let mut doc = YataDocument::from_snapshot(snapshot)
            .map_err(|e| StorageError::Serialization(e.to_string()))?;
        doc.set_client_id(client_id);

        let latest_version = {
            let oplogs = self.repo.query_oplogs(crate::storage::QueryOplogParams {
                document_id,
                from_time: None,
                to_time: None,
                user_id: None,
                sequence_from: Some(record.version + 1),
                sequence_to: None,
                limit: None,
                offset: None,
            }).await?;

            let mut last_seq = record.version;
            for entry in oplogs {
                if let Some(binary) = entry.op_binary {
                    if let Ok(op) = bincode::deserialize::<collab_crdt::Op>(&binary) {
                        let _ = doc.apply_op(&op);
                    }
                }
                last_seq = entry.sequence;
            }
            last_seq
        };

        Ok(Some((doc, latest_version)))
    }

    pub async fn load_document(
        &self,
        document_id: Uuid,
        client_id: u64,
    ) -> Result<Option<(YataDocument, i64)>, StorageError> {
        if let Some(result) = self.load_document_with_latest_snapshot(document_id, client_id).await? {
            return Ok(Some(result));
        }

        let oplogs = self.repo.query_oplogs(crate::storage::QueryOplogParams {
            document_id,
            from_time: None,
            to_time: None,
            user_id: None,
            sequence_from: None,
            sequence_to: None,
            limit: None,
            offset: None,
        }).await?;

        if oplogs.is_empty() {
            return Ok(None);
        }

        let mut doc = YataDocument::new(document_id, client_id);
        let mut last_seq = 0i64;
        for entry in oplogs {
            if let Some(binary) = entry.op_binary {
                if let Ok(op) = bincode::deserialize::<collab_crdt::Op>(&binary) {
                    let _ = doc.apply_op(&op);
                }
            }
            last_seq = entry.sequence;
        }

        Ok(Some((doc, last_seq)))
    }

    pub async fn restore_version(
        &self,
        document_id: Uuid,
        target_version: i64,
    ) -> Result<CrdtSnapshot, StorageError> {
        let (snapshot, record) = self.load_latest_snapshot(document_id).await?
            .ok_or_else(|| StorageError::NotFound(format!("No snapshots for {}", document_id)))?;

        let mut doc = YataDocument::from_snapshot(snapshot.clone())
            .map_err(|e| StorageError::Serialization(e.to_string()))?;

        if target_version > record.version {
            let oplogs = self.repo.query_oplogs(crate::storage::QueryOplogParams {
                document_id,
                from_time: None,
                to_time: None,
                user_id: None,
                sequence_from: Some(record.version + 1),
                sequence_to: Some(target_version),
                limit: None,
                offset: None,
            }).await?;

            for entry in oplogs {
                if let Some(binary) = entry.op_binary {
                    if let Ok(op) = bincode::deserialize::<collab_crdt::Op>(&binary) {
                        let _ = doc.apply_op(&op);
                    }
                }
            }
        }

        Ok(doc.snapshot())
    }

    pub fn process_pending_snapshots(&self, rooms: &DashMap<Uuid, Arc<Room>>) {
        let to_process: Vec<(Uuid, Arc<Room>)> = self.pending
            .iter()
            .filter(|p| matches!(p.status, SnapshotStatus::Pending))
            .filter_map(|p| rooms.get(p.key()).map(|r| (*p.key(), r.clone())))
            .collect();

        for (doc_id, room) in to_process {
            if let Some(mut entry) = self.pending.get_mut(&doc_id) {
                entry.status = SnapshotStatus::InProgress;
            }

            let service = self.clone();
            let pending_doc = doc_id;
            tokio::spawn(async move {
                match service.create_snapshot_for_room(&room, "system".to_string()).await {
                    Ok(_) => gauge!("collab_pending_snapshots").decrement(1f64),
                    Err(e) => {
                        tracing::error!("Snapshot failed for {}: {:?}", pending_doc, e);
                        counter!("collab_snapshots_failed_total").increment(1);
                        if let Some(mut entry) = service.pending.get_mut(&pending_doc) {
                            entry.attempt += 1;
                            entry.status = SnapshotStatus::Pending;
                        }
                    }
                }
            });
        }
    }
}

impl Clone for SnapshotService {
    fn clone(&self) -> Self {
        Self {
            config: self.config.clone(),
            repo: self.repo.clone(),
            pending: self.pending.clone(),
            local_dir: self.local_dir.clone(),
        }
    }
}

fn serialize_snapshot(snapshot: &CrdtSnapshot, compress: bool) -> Result<(Vec<u8>, String), StorageError> {
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use std::io::Write;

    let binary = bincode::serialize(snapshot)?;
    let data = if compress {
        let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(&binary).map_err(|e| StorageError::Io(e.to_string()))?;
        encoder.finish().map_err(|e| StorageError::Io(e.to_string()))?
    } else {
        binary
    };

    let mut hasher = Sha256::new();
    hasher.update(&data);
    let checksum = format!("sha256:{:x}", hasher.finalize());

    Ok((data, checksum))
}

fn deserialize_snapshot(data: &[u8], compressed: bool) -> Result<CrdtSnapshot, StorageError> {
    use flate2::read::GzDecoder;
    use std::io::Read;

    let binary = if compressed {
        let mut decoder = GzDecoder::new(data);
        let mut decompressed = Vec::new();
        decoder.read_to_end(&mut decompressed).map_err(|e| StorageError::Io(e.to_string()))?;
        decompressed
    } else {
        data.to_vec()
    };

    let snapshot: CrdtSnapshot = bincode::deserialize(&binary)?;
    Ok(snapshot)
}

fn verify_checksum(data: &[u8], expected: &str) -> Result<(), StorageError> {
    if let Some(hash) = expected.strip_prefix("sha256:") {
        let mut hasher = Sha256::new();
        hasher.update(data);
        let actual = format!("{:x}", hasher.finalize());
        if actual != hash {
            return Err(StorageError::S3("Checksum mismatch".into()));
        }
    }
    Ok(())
}
