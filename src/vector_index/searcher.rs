use crate::models::StreamSQLError;
use crate::vector_index::index::{VectorIndex, IndexType};
use crate::vector_index::vector::{DistanceMetric, SearchResult, Vector};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchQuery {
    pub vector: Vec<f32>,
    pub top_k: usize,
    pub filters: HashMap<String, String>,
    pub metric_override: Option<DistanceMetric>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchResponse {
    pub query_id: String,
    pub results: Vec<SearchResult>,
    pub total_found: usize,
    pub search_time_ms: u64,
    pub cache_hit: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchConfig {
    pub default_top_k: usize,
    pub max_top_k: usize,
    pub enable_cache: bool,
    pub cache_ttl_ms: u64,
    pub ef_search: Option<usize>,
}

impl Default for SearchConfig {
    fn default() -> Self {
        Self {
            default_top_k: 10,
            max_top_k: 100,
            enable_cache: true,
            cache_ttl_ms: 60_000,
            ef_search: Some(100),
        }
    }
}

pub struct VectorSearcher {
    index: Arc<RwLock<VectorIndex>>,
    config: SearchConfig,
    cache: Option<Arc<RwLock<HashMap<String, CachedSearchResult>>>>,
}

#[derive(Clone)]
struct CachedSearchResult {
    results: Vec<SearchResult>,
    timestamp: chrono::DateTime<chrono::Utc>,
}

impl VectorSearcher {
    pub fn new(index: VectorIndex, config: SearchConfig) -> Self {
        let cache = if config.enable_cache {
            Some(Arc::new(RwLock::new(HashMap::new())))
        } else {
            None
        };

        Self {
            index: Arc::new(RwLock::new(index)),
            config,
            cache,
        }
    }

    pub fn from_arc(index: Arc<RwLock<VectorIndex>>, config: SearchConfig) -> Self {
        let cache = if config.enable_cache {
            Some(Arc::new(RwLock::new(HashMap::new())))
        } else {
            None
        };

        Self { index, config, cache }
    }

    pub async fn search(&self, query: SearchQuery) -> Result<SearchResponse, StreamSQLError> {
        let start_time = std::time::Instant::now();
        let query_id = format!("q_{}", uuid::Uuid::new_v4());

        let top_k = std::cmp::min(query.top_k, self.config.max_top_k);

        let cache_key = if self.cache.is_some() {
            Some(self.generate_cache_key(&query))
        } else {
            None
        };

        if let (Some(cache), Some(key)) = (&self.cache, &cache_key) {
            if let Some(cached) = self.check_cache(cache, key).await {
                let elapsed = start_time.elapsed().as_millis() as u64;
                return Ok(SearchResponse {
                    query_id,
                    results: cached,
                    total_found: 0,
                    search_time_ms: elapsed,
                    cache_hit: true,
                });
            }
        }

        let query_vector = Vector::new("query", query.vector);

        let index = self.index.read().await;

        let results = if query.filters.is_empty() {
            index.search(&query_vector, top_k)?
        } else {
            let filters = query.filters.clone();
            index.search_with_filter(&query_vector, top_k, |meta| {
                filters.iter().all(|(k, v)| meta.get(k) == Some(v))
            })?
        };

        let total_found = results.len();
        let elapsed = start_time.elapsed().as_millis() as u64;

        if let (Some(cache), Some(key)) = (&self.cache, &cache_key) {
            self.update_cache(cache, key, results.clone()).await;
        }

        Ok(SearchResponse {
            query_id,
            results,
            total_found,
            search_time_ms: elapsed,
            cache_hit: false,
        })
    }

    pub async fn batch_search(
        &self,
        queries: Vec<SearchQuery>,
    ) -> Result<Vec<SearchResponse>, StreamSQLError> {
        let mut results = Vec::with_capacity(queries.len());

        for query in queries {
            let result = self.search(query).await?;
            results.push(result);
        }

        Ok(results)
    }

    pub async fn add_vector(&self, vector: Vector) -> Result<(), StreamSQLError> {
        let mut index = self.index.write().await;
        index.insert(vector)?;

        if let Some(cache) = &self.cache {
            let mut cache = cache.write().await;
            cache.clear();
        }

        Ok(())
    }

    pub async fn remove_vector(&self, id: &str) -> Option<Vector> {
        let mut index = self.index.write().await;
        let result = index.remove(id);

        if result.is_some() {
            if let Some(cache) = &self.cache {
                let mut cache = cache.write().await;
                cache.clear();
            }
        }

        result
    }

    pub async fn stats(&self) -> SearchStats {
        let index = self.index.read().await;

        SearchStats {
            index_id: index.index_id.clone(),
            index_name: index.name.clone(),
            total_vectors: index.len(),
            dimensions: index.dimensions,
            metric: index.metric,
            index_type: index.index_type,
            last_updated: index.updated_at,
        }
    }

    pub async fn clear_cache(&self) {
        if let Some(cache) = &self.cache {
            let mut cache = cache.write().await;
            cache.clear();
        }
    }

    fn generate_cache_key(&self, query: &SearchQuery) -> String {
        use sha2::{Digest, Sha256};

        let mut hasher = Sha256::new();
        for v in &query.vector {
            hasher.update(v.to_le_bytes());
        }
        hasher.update(query.top_k.to_le_bytes());

        let mut filter_keys: Vec<&String> = query.filters.keys().collect();
        filter_keys.sort();
        for k in filter_keys {
            if let Some(v) = query.filters.get(k) {
                hasher.update(k.as_bytes());
                hasher.update(v.as_bytes());
            }
        }

        format!("{:x}", hasher.finalize())
    }

    async fn check_cache(
        &self,
        cache: &Arc<RwLock<HashMap<String, CachedSearchResult>>>,
        key: &str,
    ) -> Option<Vec<SearchResult>> {
        let cache = cache.read().await;

        if let Some(cached) = cache.get(key) {
            let now = chrono::Utc::now();
            let age = (now - cached.timestamp).num_milliseconds() as u64;

            if age < self.config.cache_ttl_ms {
                return Some(cached.results.clone());
            }
        }

        None
    }

    async fn update_cache(
        &self,
        cache: &Arc<RwLock<HashMap<String, CachedSearchResult>>>,
        key: String,
        results: Vec<SearchResult>,
    ) {
        let mut cache = cache.write().await;

        cache.insert(
            key,
            CachedSearchResult {
                results,
                timestamp: chrono::Utc::now(),
            },
        );

        if cache.len() > 1000 {
            let oldest_key = cache
                .iter()
                .min_by_key(|(_, v)| v.timestamp)
                .map(|(k, _)| k.clone());

            if let Some(key) = oldest_key {
                cache.remove(&key);
            }
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchStats {
    pub index_id: String,
    pub index_name: String,
    pub total_vectors: usize,
    pub dimensions: usize,
    pub metric: DistanceMetric,
    pub index_type: IndexType,
    pub last_updated: chrono::DateTime<chrono::Utc>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_searcher_creation() {
        let index = VectorIndex::new("test_index", 3, DistanceMetric::Euclidean, IndexType::Flat);
        let config = SearchConfig::default();
        let searcher = VectorSearcher::new(index, config);

        let stats = searcher.stats().await;
        assert_eq!(stats.total_vectors, 0);
    }

    #[tokio::test]
    async fn test_searcher_search() {
        let mut index = VectorIndex::new("test_index", 2, DistanceMetric::Euclidean, IndexType::Flat);

        index.insert(Vector::new("v1", vec![0.0, 0.0])).unwrap();
        index.insert(Vector::new("v2", vec![1.0, 1.0])).unwrap();

        let config = SearchConfig::default();
        let searcher = VectorSearcher::new(index, config);

        let query = SearchQuery {
            vector: vec![0.0, 0.0],
            top_k: 5,
            filters: HashMap::new(),
            metric_override: None,
        };

        let response = searcher.search(query).await.unwrap();

        assert_eq!(response.results.len(), 2);
        assert_eq!(response.results[0].vector_id, "v1");
        assert!(!response.cache_hit);
    }

    #[tokio::test]
    async fn test_searcher_with_filter() {
        let mut index = VectorIndex::new("test_index", 2, DistanceMetric::Euclidean, IndexType::Flat);

        let mut v1 = Vector::new("v1", vec![0.0, 0.0]);
        v1.metadata.insert("category".to_string(), "A".to_string());

        let mut v2 = Vector::new("v2", vec![1.0, 1.0]);
        v2.metadata.insert("category".to_string(), "B".to_string());

        index.insert(v1).unwrap();
        index.insert(v2).unwrap();

        let config = SearchConfig::default();
        let searcher = VectorSearcher::new(index, config);

        let mut filters = HashMap::new();
        filters.insert("category".to_string(), "A".to_string());

        let query = SearchQuery {
            vector: vec![0.0, 0.0],
            top_k: 5,
            filters,
            metric_override: None,
        };

        let response = searcher.search(query).await.unwrap();

        assert_eq!(response.results.len(), 1);
        assert_eq!(response.results[0].vector_id, "v1");
    }

    #[tokio::test]
    async fn test_add_and_remove() {
        let index = VectorIndex::new("test_index", 2, DistanceMetric::Euclidean, IndexType::Flat);
        let config = SearchConfig::default();
        let searcher = VectorSearcher::new(index, config);

        let v1 = Vector::new("v1", vec![0.0, 0.0]);
        searcher.add_vector(v1).await.unwrap();

        let stats = searcher.stats().await;
        assert_eq!(stats.total_vectors, 1);

        let removed = searcher.remove_vector("v1").await;
        assert!(removed.is_some());

        let stats = searcher.stats().await;
        assert_eq!(stats.total_vectors, 0);
    }
}
