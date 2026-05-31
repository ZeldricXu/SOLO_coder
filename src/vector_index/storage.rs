use crate::models::StreamSQLError;
use crate::vector_index::index::{VectorIndex, IndexType};
use crate::vector_index::vector::{DistanceMetric, Vector};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IndexSnapshot {
    pub index_id: String,
    pub name: String,
    pub dimensions: usize,
    pub metric: DistanceMetric,
    pub index_type: IndexType,
    pub vectors: Vec<Vector>,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub snapshot_version: u32,
}

pub struct IndexStorage {
    base_path: PathBuf,
}

impl IndexStorage {
    pub fn new(base_path: impl AsRef<Path>) -> Self {
        Self {
            base_path: base_path.as_ref().to_path_buf(),
        }
    }

    pub fn save(&self, index: &VectorIndex) -> Result<PathBuf, StreamSQLError> {
        std::fs::create_dir_all(&self.base_path).map_err(StreamSQLError::Io)?;

        let snapshot = self.create_snapshot(index);
        let file_path = self.base_path.join(format!("{}.json", index.index_id));

        let json = serde_json::to_string_pretty(&snapshot)
            .map_err(|e| StreamSQLError::Serialization(e.to_string()))?;

        std::fs::write(&file_path, json).map_err(StreamSQLError::Io)?;

        Ok(file_path)
    }

    pub fn load(&self, index_id: &str) -> Result<VectorIndex, StreamSQLError> {
        let file_path = self.base_path.join(format!("{}.json", index_id));

        if !file_path.exists() {
            return Err(StreamSQLError::NotFound(format!(
                "Index not found: {}",
                index_id
            )));
        }

        let content = std::fs::read_to_string(&file_path).map_err(StreamSQLError::Io)?;

        let snapshot: IndexSnapshot = serde_json::from_str(&content)
            .map_err(|e| StreamSQLError::Serialization(e.to_string()))?;

        self.restore_from_snapshot(snapshot)
    }

    pub fn delete(&self, index_id: &str) -> Result<(), StreamSQLError> {
        let file_path = self.base_path.join(format!("{}.json", index_id));

        if file_path.exists() {
            std::fs::remove_file(file_path).map_err(StreamSQLError::Io)?;
        }

        Ok(())
    }

    pub fn list(&self) -> Result<Vec<String>, StreamSQLError> {
        if !self.base_path.exists() {
            return Ok(Vec::new());
        }

        let mut index_ids = Vec::new();

        for entry in std::fs::read_dir(&self.base_path).map_err(StreamSQLError::Io)? {
            let entry = entry.map_err(StreamSQLError::Io)?;
            let path = entry.path();

            if path.extension().and_then(|s| s.to_str()) == Some("json") {
                if let Some(stem) = path.file_stem().and_then(|s| s.to_str()) {
                    index_ids.push(stem.to_string());
                }
            }
        }

        Ok(index_ids)
    }

    pub fn exists(&self, index_id: &str) -> bool {
        self.base_path.join(format!("{}.json", index_id)).exists()
    }

    fn create_snapshot(&self, index: &VectorIndex) -> IndexSnapshot {
        let vectors: Vec<Vector> = index.vectors.values().cloned().collect();

        IndexSnapshot {
            index_id: index.index_id.clone(),
            name: index.name.clone(),
            dimensions: index.dimensions,
            metric: index.metric,
            index_type: index.index_type,
            vectors,
            created_at: index.created_at,
            snapshot_version: 1,
        }
    }

    fn restore_from_snapshot(&self, snapshot: IndexSnapshot) -> Result<VectorIndex, StreamSQLError> {
        let mut index = VectorIndex::new(
            snapshot.name,
            snapshot.dimensions,
            snapshot.metric,
            snapshot.index_type,
        );

        for v in snapshot.vectors {
            index.insert(v)?;
        }

        Ok(index)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StorageConfig {
    pub base_path: String,
    pub max_snapshots: usize,
    pub auto_save_interval_ms: Option<u64>,
    pub compression: CompressionType,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CompressionType {
    None,
    Gzip,
    Lz4,
}

impl Default for StorageConfig {
    fn default() -> Self {
        Self {
            base_path: "./data/vector_indices".to_string(),
            max_snapshots: 10,
            auto_save_interval_ms: None,
            compression: CompressionType::None,
        }
    }
}

pub struct IndexManager {
    storage: IndexStorage,
    indices: HashMap<String, VectorIndex>,
    config: StorageConfig,
}

impl IndexManager {
    pub fn new(config: StorageConfig) -> Self {
        Self {
            storage: IndexStorage::new(&config.base_path),
            indices: HashMap::new(),
            config,
        }
    }

    pub fn create(
        &mut self,
        name: impl Into<String>,
        dimensions: usize,
        metric: DistanceMetric,
        index_type: IndexType,
    ) -> &VectorIndex {
        let index = VectorIndex::new(name, dimensions, metric, index_type);
        let id = index.index_id.clone();
        self.indices.insert(id, index);
        self.indices.get(&id).unwrap()
    }

    pub fn get(&self, index_id: &str) -> Option<&VectorIndex> {
        self.indices.get(index_id)
    }

    pub fn get_mut(&mut self, index_id: &str) -> Option<&mut VectorIndex> {
        self.indices.get_mut(index_id)
    }

    pub fn load(&mut self, index_id: &str) -> Result<&VectorIndex, StreamSQLError> {
        if self.indices.contains_key(index_id) {
            return Ok(self.indices.get(index_id).unwrap());
        }

        let index = self.storage.load(index_id)?;
        let id = index.index_id.clone();
        self.indices.insert(id, index);

        Ok(self.indices.get(index_id).unwrap())
    }

    pub fn save(&self, index_id: &str) -> Result<PathBuf, StreamSQLError> {
        let index = self
            .indices
            .get(index_id)
            .ok_or_else(|| StreamSQLError::NotFound(format!("Index not loaded: {}", index_id)))?;

        self.storage.save(index)
    }

    pub fn save_all(&self) -> Result<Vec<PathBuf>, StreamSQLError> {
        let mut paths = Vec::new();

        for index_id in self.indices.keys() {
            let path = self.save(index_id)?;
            paths.push(path);
        }

        Ok(paths)
    }

    pub fn delete(&mut self, index_id: &str) -> Result<(), StreamSQLError> {
        self.indices.remove(index_id);
        self.storage.delete(index_id)
    }

    pub fn list_loaded(&self) -> Vec<String> {
        self.indices.keys().cloned().collect()
    }

    pub fn list_stored(&self) -> Result<Vec<String>, StreamSQLError> {
        self.storage.list()
    }

    pub fn insert_vector(&mut self, index_id: &str, vector: Vector) -> Result<(), StreamSQLError> {
        let index = self
            .indices
            .get_mut(index_id)
            .ok_or_else(|| StreamSQLError::NotFound(format!("Index not found: {}", index_id)))?;

        index.insert(vector)
    }

    pub fn search(
        &self,
        index_id: &str,
        query: &Vector,
        top_k: usize,
    ) -> Result<Vec<crate::vector_index::vector::SearchResult>, StreamSQLError> {
        let index = self
            .indices
            .get(index_id)
            .ok_or_else(|| StreamSQLError::NotFound(format!("Index not found: {}", index_id)))?;

        index.search(query, top_k)
    }

    pub fn len(&self) -> usize {
        self.indices.len()
    }

    pub fn is_empty(&self) -> bool {
        self.indices.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn test_storage_save_load() {
        let temp = tempdir().unwrap();
        let storage = IndexStorage::new(temp.path());

        let mut index = VectorIndex::new("test", 3, DistanceMetric::Euclidean, IndexType::Flat);
        index.insert(Vector::new("v1", vec![1.0, 2.0, 3.0])).unwrap();

        let path = storage.save(&index).unwrap();
        assert!(path.exists());

        let loaded = storage.load(&index.index_id).unwrap();
        assert_eq!(loaded.name, "test");
        assert_eq!(loaded.len(), 1);
    }

    #[test]
    fn test_storage_delete() {
        let temp = tempdir().unwrap();
        let storage = IndexStorage::new(temp.path());

        let index = VectorIndex::new("test", 3, DistanceMetric::Euclidean, IndexType::Flat);
        storage.save(&index).unwrap();

        assert!(storage.exists(&index.index_id));

        storage.delete(&index.index_id).unwrap();
        assert!(!storage.exists(&index.index_id));
    }

    #[test]
    fn test_manager_creation() {
        let temp = tempdir().unwrap();
        let config = StorageConfig {
            base_path: temp.path().to_string_lossy().to_string(),
            ..Default::default()
        };

        let mut manager = IndexManager::new(config);
        let index = manager.create("test_index", 128, DistanceMetric::Cosine, IndexType::Flat);

        assert_eq!(index.name, "test_index");
        assert_eq!(manager.len(), 1);
    }

    #[test]
    fn test_manager_insert_and_search() {
        let temp = tempdir().unwrap();
        let config = StorageConfig {
            base_path: temp.path().to_string_lossy().to_string(),
            ..Default::default()
        };

        let mut manager = IndexManager::new(config);
        let index_id = {
            let index = manager.create("test", 2, DistanceMetric::Euclidean, IndexType::Flat);
            index.index_id.clone()
        };

        manager
            .insert_vector(&index_id, Vector::new("v1", vec![0.0, 0.0]))
            .unwrap();
        manager
            .insert_vector(&index_id, Vector::new("v2", vec![1.0, 1.0]))
            .unwrap();

        let query = Vector::new("query", vec![0.0, 0.0]);
        let results = manager.search(&index_id, &query, 5).unwrap();

        assert_eq!(results.len(), 2);
        assert_eq!(results[0].vector_id, "v1");
    }
}
