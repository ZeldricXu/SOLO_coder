use crate::models::StreamSQLError;
use crate::vector_index::index::{IndexConfig, IndexType, VectorIndex};
use crate::vector_index::vector::{DistanceMetric, Vector, VectorStatsCalculator};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::Mutex;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BuildProgress {
    pub total_vectors: usize,
    pub processed_vectors: usize,
    pub percentage: f64,
    pub current_stage: BuildStage,
    pub started_at: chrono::DateTime<chrono::Utc>,
    pub estimated_completion: Option<chrono::DateTime<chrono::Utc>>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum BuildStage {
    Initializing,
    Validating,
    Processing,
    Optimizing,
    Finalizing,
    Completed,
    Failed,
}

pub struct VectorIndexBuilder {
    config: IndexConfig,
    name: String,
    progress_callback: Option<Arc<dyn Fn(BuildProgress) + Send + Sync>>,
}

impl VectorIndexBuilder {
    pub fn new(name: impl Into<String>, config: IndexConfig) -> Self {
        Self {
            config,
            name: name.into(),
            progress_callback: None,
        }
    }

    pub fn with_progress_callback<F>(mut self, callback: F) -> Self
    where
        F: Fn(BuildProgress) + Send + Sync + 'static,
    {
        self.progress_callback = Some(Arc::new(callback));
        self
    }

    pub fn build(&self, vectors: Vec<Vector>) -> Result<VectorIndex, StreamSQLError> {
        let start_time = chrono::Utc::now();

        self.report_progress(BuildProgress {
            total_vectors: vectors.len(),
            processed_vectors: 0,
            percentage: 0.0,
            current_stage: BuildStage::Initializing,
            started_at: start_time,
            estimated_completion: None,
        });

        let stats = VectorStatsCalculator::calculate(&vectors)?;

        self.report_progress(BuildProgress {
            total_vectors: vectors.len(),
            processed_vectors: 0,
            percentage: 10.0,
            current_stage: BuildStage::Validating,
            started_at: start_time,
            estimated_completion: None,
        });

        let mut index = VectorIndex::new(
            &self.name,
            stats.dimensions,
            self.config.metric,
            self.config.index_type,
        );

        self.report_progress(BuildProgress {
            total_vectors: vectors.len(),
            processed_vectors: 0,
            percentage: 20.0,
            current_stage: BuildStage::Processing,
            started_at: start_time,
            estimated_completion: None,
        });

        let total = vectors.len();
        for (i, v) in vectors.into_iter().enumerate() {
            index.insert(v)?;

            let percentage = 20.0 + (i as f64 / total as f64) * 60.0;
            if i % 100 == 0 || i == total - 1 {
                self.report_progress(BuildProgress {
                    total_vectors: total,
                    processed_vectors: i + 1,
                    percentage,
                    current_stage: BuildStage::Processing,
                    started_at: start_time,
                    estimated_completion: None,
                });
            }
        }

        self.report_progress(BuildProgress {
            total_vectors: total,
            processed_vectors: total,
            percentage: 85.0,
            current_stage: BuildStage::Optimizing,
            started_at: start_time,
            estimated_completion: None,
        });

        index.refresh();

        let elapsed = (chrono::Utc::now() - start_time).num_milliseconds() as u64;
        index.stats.build_time_ms = elapsed;

        self.report_progress(BuildProgress {
            total_vectors: total,
            processed_vectors: total,
            percentage: 100.0,
            current_stage: BuildStage::Completed,
            started_at: start_time,
            estimated_completion: Some(chrono::Utc::now()),
        });

        Ok(index)
    }

    pub async fn build_async(
        &self,
        vectors: Vec<Vector>,
    ) -> Result<VectorIndex, StreamSQLError> {
        let start_time = chrono::Utc::now();
        let total = vectors.len();

        self.report_progress(BuildProgress {
            total_vectors: total,
            processed_vectors: 0,
            percentage: 0.0,
            current_stage: BuildStage::Initializing,
            started_at: start_time,
            estimated_completion: None,
        });

        let stats = VectorStatsCalculator::calculate(&vectors)?;

        self.report_progress(BuildProgress {
            total_vectors: total,
            processed_vectors: 0,
            percentage: 10.0,
            current_stage: BuildStage::Validating,
            started_at: start_time,
            estimated_completion: None,
        });

        let mut index = VectorIndex::new(
            &self.name,
            stats.dimensions,
            self.config.metric,
            self.config.index_type,
        );

        self.report_progress(BuildProgress {
            total_vectors: total,
            processed_vectors: 0,
            percentage: 20.0,
            current_stage: BuildStage::Processing,
            started_at: start_time,
            estimated_completion: None,
        });

        let batch_size = std::cmp::min(1000, total / 10 + 1);
        for (batch_idx, batch) in vectors.chunks(batch_size).enumerate() {
            for v in batch {
                index.insert(v.clone())?;
            }

            let processed = (batch_idx + 1) * batch_size;
            let processed = std::cmp::min(processed, total);
            let percentage = 20.0 + (processed as f64 / total as f64) * 60.0;

            self.report_progress(BuildProgress {
                total_vectors: total,
                processed_vectors: processed,
                percentage,
                current_stage: BuildStage::Processing,
                started_at: start_time,
                estimated_completion: None,
            });

            tokio::task::yield_now().await;
        }

        self.report_progress(BuildProgress {
            total_vectors: total,
            processed_vectors: total,
            percentage: 85.0,
            current_stage: BuildStage::Optimizing,
            started_at: start_time,
            estimated_completion: None,
        });

        index.refresh();

        let elapsed = (chrono::Utc::now() - start_time).num_milliseconds() as u64;
        index.stats.build_time_ms = elapsed;

        self.report_progress(BuildProgress {
            total_vectors: total,
            processed_vectors: total,
            percentage: 100.0,
            current_stage: BuildStage::Completed,
            started_at: start_time,
            estimated_completion: Some(chrono::Utc::now()),
        });

        Ok(index)
    }

    fn report_progress(&self, progress: BuildProgress) {
        if let Some(callback) = &self.progress_callback {
            callback(progress);
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BuildConfig {
    pub index_name: String,
    pub dimensions: usize,
    pub metric: DistanceMetric,
    pub index_type: IndexType,
    pub batch_size: usize,
    pub parallelism: usize,
    pub optimize_after_build: bool,
}

impl Default for BuildConfig {
    fn default() -> Self {
        Self {
            index_name: "default_index".to_string(),
            dimensions: 128,
            metric: DistanceMetric::Cosine,
            index_type: IndexType::Flat,
            batch_size: 1000,
            parallelism: 4,
            optimize_after_build: true,
        }
    }
}

pub struct AsyncIndexBuilder {
    config: BuildConfig,
    progress: Arc<Mutex<BuildProgress>>,
}

impl AsyncIndexBuilder {
    pub fn new(config: BuildConfig) -> Self {
        let start_time = chrono::Utc::now();
        Self {
            config,
            progress: Arc::new(Mutex::new(BuildProgress {
                total_vectors: 0,
                processed_vectors: 0,
                percentage: 0.0,
                current_stage: BuildStage::Initializing,
                started_at: start_time,
                estimated_completion: None,
            })),
        }
    }

    pub async fn get_progress(&self) -> BuildProgress {
        self.progress.lock().await.clone()
    }

    pub async fn build(&self, vectors: Vec<Vector>) -> Result<VectorIndex, StreamSQLError> {
        let config = IndexConfig::new(
            self.config.dimensions,
            self.config.metric,
            self.config.index_type,
        );

        let builder = VectorIndexBuilder::new(&self.config.index_name, config)
            .with_progress_callback({
                let progress = self.progress.clone();
                move |p| {
                    if let Ok(mut guard) = progress.try_lock() {
                        *guard = p;
                    }
                }
            });

        builder.build_async(vectors).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_builder_creation() {
        let config = IndexConfig::new(128, DistanceMetric::Cosine, IndexType::Flat);
        let builder = VectorIndexBuilder::new("test_index", config);

        let vectors: Vec<Vector> = (0..10)
            .map(|i| Vector::new(format!("v_{}", i), vec![i as f32; 128]))
            .collect();

        let index = builder.build(vectors).unwrap();
        assert_eq!(index.len(), 10);
    }

    #[test]
    fn test_build_progress() {
        let config = IndexConfig::new(128, DistanceMetric::Cosine, IndexType::Flat);

        let progress_received = Arc::new(Mutex::new(Vec::new()));
        let pr = progress_received.clone();

        let builder = VectorIndexBuilder::new("test_index", config).with_progress_callback(move |p| {
            if let Ok(mut guard) = pr.try_lock() {
                guard.push(p);
            }
        });

        let vectors: Vec<Vector> = (0..5)
            .map(|i| Vector::new(format!("v_{}", i), vec![i as f32; 128]))
            .collect();

        let _index = builder.build(vectors).unwrap();

        let progress_list = progress_received.lock().unwrap();
        assert!(!progress_list.is_empty());
    }

    #[tokio::test]
    async fn test_async_builder() {
        let config = BuildConfig {
            index_name: "async_test".to_string(),
            dimensions: 64,
            metric: DistanceMetric::Euclidean,
            index_type: IndexType::Flat,
            batch_size: 100,
            parallelism: 2,
            optimize_after_build: true,
        };

        let builder = AsyncIndexBuilder::new(config);

        let vectors: Vec<Vector> = (0..50)
            .map(|i| Vector::new(format!("v_{}", i), vec![i as f32; 64]))
            .collect();

        let index = builder.build(vectors).await.unwrap();
        assert_eq!(index.len(), 50);

        let progress = builder.get_progress().await;
        assert_eq!(progress.current_stage, BuildStage::Completed);
        assert_eq!(progress.percentage, 100.0);
    }
}
