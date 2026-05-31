use crate::models::StreamSQLError;
use crate::vector_index::vector::{DistanceMetric, SearchResult, Vector};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VectorIndex {
    pub index_id: String,
    pub name: String,
    pub dimensions: usize,
    pub metric: DistanceMetric,
    pub index_type: IndexType,
    pub vectors: HashMap<String, Vector>,
    pub stats: IndexStats,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub updated_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum IndexType {
    Flat,
    Ivf,
    Hnsw,
    Annoy,
    Faiss,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IndexStats {
    pub total_vectors: usize,
    pub memory_usage_bytes: usize,
    pub build_time_ms: u64,
    pub last_refresh: Option<chrono::DateTime<chrono::Utc>>,
}

impl Default for IndexStats {
    fn default() -> Self {
        Self {
            total_vectors: 0,
            memory_usage_bytes: 0,
            build_time_ms: 0,
            last_refresh: None,
        }
    }
}

impl VectorIndex {
    pub fn new(name: impl Into<String>, dimensions: usize, metric: DistanceMetric, index_type: IndexType) -> Self {
        Self {
            index_id: format!("idx_{}", uuid::Uuid::new_v4()),
            name: name.into(),
            dimensions,
            metric,
            index_type,
            vectors: HashMap::new(),
            stats: IndexStats::default(),
            created_at: chrono::Utc::now(),
            updated_at: chrono::Utc::now(),
        }
    }

    pub fn insert(&mut self, vector: Vector) -> Result<(), StreamSQLError> {
        if vector.dimension() != self.dimensions {
            return Err(StreamSQLError::Vector(format!(
                "Dimension mismatch: expected {}, got {}",
                self.dimensions,
                vector.dimension()
            )));
        }

        self.vectors.insert(vector.id.clone(), vector);
        self.stats.total_vectors = self.vectors.len();
        self.updated_at = chrono::Utc::now();

        Ok(())
    }

    pub fn insert_batch(&mut self, vectors: Vec<Vector>) -> Result<usize, StreamSQLError> {
        let mut inserted = 0;
        for v in vectors {
            if self.insert(v).is_ok() {
                inserted += 1;
            }
        }
        Ok(inserted)
    }

    pub fn get(&self, id: &str) -> Option<&Vector> {
        self.vectors.get(id)
    }

    pub fn remove(&mut self, id: &str) -> Option<Vector> {
        let result = self.vectors.remove(id);
        if result.is_some() {
            self.stats.total_vectors = self.vectors.len();
            self.updated_at = chrono::Utc::now();
        }
        result
    }

    pub fn contains(&self, id: &str) -> bool {
        self.vectors.contains_key(id)
    }

    pub fn len(&self) -> usize {
        self.vectors.len()
    }

    pub fn is_empty(&self) -> bool {
        self.vectors.is_empty()
    }

    pub fn search(
        &self,
        query: &Vector,
        top_k: usize,
    ) -> Result<Vec<SearchResult>, StreamSQLError> {
        if query.dimension() != self.dimensions {
            return Err(StreamSQLError::Vector(format!(
                "Query dimension mismatch: expected {}, got {}",
                self.dimensions,
                query.dimension()
            )));
        }

        let mut results: Vec<SearchResult> = Vec::new();

        for (id, vector) in &self.vectors {
            let distance = vector.distance(query, self.metric)?;
            let score = 1.0 / (1.0 + distance);

            results.push(SearchResult {
                vector_id: id.clone(),
                distance,
                score,
                metadata: vector.metadata.clone(),
            });
        }

        results.sort_by(|a, b| {
            a.distance
                .partial_cmp(&b.distance)
                .unwrap_or(std::cmp::Ordering::Equal)
        });

        results.truncate(top_k);

        Ok(results)
    }

    pub fn search_with_filter<F>(
        &self,
        query: &Vector,
        top_k: usize,
        filter: F,
    ) -> Result<Vec<SearchResult>, StreamSQLError>
    where
        F: Fn(&HashMap<String, String>) -> bool,
    {
        if query.dimension() != self.dimensions {
            return Err(StreamSQLError::Vector(format!(
                "Query dimension mismatch: expected {}, got {}",
                self.dimensions,
                query.dimension()
            )));
        }

        let mut results: Vec<SearchResult> = Vec::new();

        for (id, vector) in &self.vectors {
            if !filter(&vector.metadata) {
                continue;
            }

            let distance = vector.distance(query, self.metric)?;
            let score = 1.0 / (1.0 + distance);

            results.push(SearchResult {
                vector_id: id.clone(),
                distance,
                score,
                metadata: vector.metadata.clone(),
            });
        }

        results.sort_by(|a, b| {
            a.distance
                .partial_cmp(&b.distance)
                .unwrap_or(std::cmp::Ordering::Equal)
        });

        results.truncate(top_k);

        Ok(results)
    }

    pub fn refresh(&mut self) {
        self.stats.last_refresh = Some(chrono::Utc::now());
        self.updated_at = chrono::Utc::now();
    }

    pub fn clear(&mut self) {
        self.vectors.clear();
        self.stats.total_vectors = 0;
        self.updated_at = chrono::Utc::now();
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IndexConfig {
    pub dimensions: usize,
    pub metric: DistanceMetric,
    pub index_type: IndexType,
    pub build_params: HashMap<String, String>,
}

impl IndexConfig {
    pub fn new(dimensions: usize, metric: DistanceMetric, index_type: IndexType) -> Self {
        Self {
            dimensions,
            metric,
            index_type,
            build_params: HashMap::new(),
        }
    }

    pub fn with_param(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.build_params.insert(key.into(), value.into());
        self
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_index_creation() {
        let index = VectorIndex::new("test_index", 3, DistanceMetric::Euclidean, IndexType::Flat);
        assert_eq!(index.dimensions, 3);
        assert!(index.is_empty());
    }

    #[test]
    fn test_index_insert_and_search() {
        let mut index = VectorIndex::new("test_index", 2, DistanceMetric::Euclidean, IndexType::Flat);

        let v1 = Vector::new("v1", vec![0.0, 0.0]);
        let v2 = Vector::new("v2", vec![1.0, 1.0]);
        let v3 = Vector::new("v3", vec![3.0, 4.0]);

        index.insert(v1).unwrap();
        index.insert(v2).unwrap();
        index.insert(v3).unwrap();

        assert_eq!(index.len(), 3);

        let query = Vector::new("query", vec![0.0, 0.0]);
        let results = index.search(&query, 2).unwrap();

        assert_eq!(results.len(), 2);
        assert_eq!(results[0].vector_id, "v1");
    }

    #[test]
    fn test_index_remove() {
        let mut index = VectorIndex::new("test_index", 2, DistanceMetric::Euclidean, IndexType::Flat);

        let v1 = Vector::new("v1", vec![0.0, 0.0]);
        index.insert(v1).unwrap();

        assert!(index.contains("v1"));
        index.remove("v1");
        assert!(!index.contains("v1"));
    }

    #[test]
    fn test_search_with_filter() {
        let mut index = VectorIndex::new("test_index", 2, DistanceMetric::Euclidean, IndexType::Flat);

        let mut v1 = Vector::new("v1", vec![0.0, 0.0]);
        v1.metadata.insert("category".to_string(), "A".to_string());

        let mut v2 = Vector::new("v2", vec![1.0, 1.0]);
        v2.metadata.insert("category".to_string(), "B".to_string());

        index.insert(v1).unwrap();
        index.insert(v2).unwrap();

        let query = Vector::new("query", vec![0.0, 0.0]);
        let results = index
            .search_with_filter(&query, 10, |meta| {
                meta.get("category") == Some(&"A".to_string())
            })
            .unwrap();

        assert_eq!(results.len(), 1);
        assert_eq!(results[0].vector_id, "v1");
    }
}
