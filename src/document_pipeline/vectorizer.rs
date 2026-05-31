use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use uuid::Uuid;
use crate::utils::error::Result;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Vector {
    pub vector_id: String,
    pub chunk_id: String,
    pub document_id: String,
    pub values: Vec<f32>,
    pub dimensions: usize,
    pub model_name: String,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

impl Vector {
    pub fn new(chunk_id: String, document_id: String, values: Vec<f32>, model_name: String) -> Self {
        let dimensions = values.len();
        Self {
            vector_id: format!("vec_{}", Uuid::new_v4().simple()),
            chunk_id,
            document_id,
            values,
            dimensions,
            model_name,
            created_at: chrono::Utc::now(),
        }
    }

    pub fn cosine_similarity(&self, other: &Vector) -> f32 {
        if self.dimensions != other.dimensions {
            return 0.0;
        }

        let dot_product: f32 = self.values.iter()
            .zip(other.values.iter())
            .map(|(a, b)| a * b)
            .sum();

        let norm_a: f32 = self.values.iter().map(|x| x * x).sum::<f32>().sqrt();
        let norm_b: f32 = other.values.iter().map(|x| x * x).sum::<f32>().sqrt();

        if norm_a == 0.0 || norm_b == 0.0 {
            0.0
        } else {
            dot_product / (norm_a * norm_b)
        }
    }

    pub fn normalize(&mut self) {
        let norm: f32 = self.values.iter().map(|x| x * x).sum::<f32>().sqrt();
        if norm > 0.0 {
            for v in &mut self.values {
                *v /= norm;
            }
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VectorizeConfig {
    pub model_name: String,
    pub dimensions: usize,
    pub batch_size: usize,
    pub normalize: bool,
}

impl Default for VectorizeConfig {
    fn default() -> Self {
        Self {
            model_name: "text-embedding-ada-002".to_string(),
            dimensions: 1536,
            batch_size: 32,
            normalize: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VectorizeResult {
    pub vectors: Vec<Vector>,
    pub total_vectors: usize,
    pub vectorize_time_ms: u64,
    pub tokens_processed: usize,
}

#[async_trait]
pub trait TextVectorizer: Send + Sync {
    async fn vectorize(&self, texts: Vec<String>) -> Result<VectorizeResult>;
    
    fn dimensions(&self) -> usize;
    
    fn model_name(&self) -> &str;
}

pub struct MockVectorizer {
    config: VectorizeConfig,
}

impl MockVectorizer {
    pub fn new(config: VectorizeConfig) -> Self {
        Self { config }
    }

    fn generate_random_vector(dimensions: usize) -> Vec<f32> {
        use rand::Rng;
        let mut rng = rand::thread_rng();
        (0..dimensions).map(|_| rng.gen_range(-1.0..1.0)).collect()
    }

    fn generate_pseudo_vector(text: &str, dimensions: usize) -> Vec<f32> {
        use std::collections::hash_map::DefaultHasher;
        use std::hash::{Hash, Hasher};

        let mut vec = vec![0.0; dimensions];
        let words: Vec<&str> = text.split_whitespace().collect();
        
        for (i, word) in words.iter().enumerate() {
            let mut hasher = DefaultHasher::new();
            word.hash(&mut hasher);
            let hash = hasher.finish();
            
            let idx = (hash as usize) % dimensions;
            let value = ((hash >> 32) as f32) / (u32::MAX as f32) * 2.0 - 1.0;
            
            vec[idx] += value * (1.0 / (i as f32 + 1.0));
        }

        let norm: f32 = vec.iter().map(|x| x * x).sum::<f32>().sqrt();
        if norm > 0.0 {
            vec.iter_mut().for_each(|v| *v /= norm);
        }

        vec
    }
}

#[async_trait]
impl TextVectorizer for MockVectorizer {
    async fn vectorize(&self, texts: Vec<String>) -> Result<VectorizeResult> {
        let start = std::time::Instant::now();
        let mut vectors = Vec::new();
        let mut tokens_processed = 0;

        for (i, text) in texts.iter().enumerate() {
            let values = Self::generate_pseudo_vector(text, self.config.dimensions);
            let chunk_id = format!("chk_batch_{}", i);
            let doc_id = "batch_doc".to_string();
            
            let mut vector = Vector::new(
                chunk_id,
                doc_id,
                values,
                self.config.model_name.clone(),
            );

            if self.config.normalize {
                vector.normalize();
            }

            vectors.push(vector);
            tokens_processed += text.chars().count() / 4;
        }

        let vectorize_time_ms = start.elapsed().as_millis() as u64;
        let total_vectors = vectors.len();

        Ok(VectorizeResult {
            vectors,
            total_vectors,
            vectorize_time_ms,
            tokens_processed,
        })
    }

    fn dimensions(&self) -> usize {
        self.config.dimensions
    }

    fn model_name(&self) -> &str {
        &self.config.model_name
    }
}

pub struct DocumentVectorizer {
    vectorizer: Arc<dyn TextVectorizer>,
    config: VectorizeConfig,
}

impl DocumentVectorizer {
    pub fn new(vectorizer: Arc<dyn TextVectorizer>, config: VectorizeConfig) -> Self {
        Self { vectorizer, config }
    }

    pub async fn vectorize_chunks(
        &self,
        chunks: Vec<crate::document_pipeline::splitter::Chunk>,
    ) -> Result<Vec<crate::document_pipeline::splitter::Chunk>> {
        use futures::stream::{self, StreamExt};

        let mut enriched_chunks = Vec::new();
        
        let batches: Vec<Vec<crate::document_pipeline::splitter::Chunk>> = chunks
            .chunks(self.config.batch_size)
            .map(|batch| batch.to_vec())
            .collect();

        for batch in batches {
            let texts: Vec<String> = batch.iter().map(|c| c.content.clone()).collect();
            let result = self.vectorizer.vectorize(texts).await?;

            for (i, mut chunk) in batch.into_iter().enumerate() {
                if let Some(vector) = result.vectors.get(i) {
                    chunk.embedding = Some(vector.values.clone());
                }
                enriched_chunks.push(chunk);
            }
        }

        Ok(enriched_chunks)
    }

    pub async fn vectorize_query(&self, query: &str) -> Result<Vector> {
        let result = self.vectorizer.vectorize(vec![query.to_string()]).await?;
        let vector = result.vectors.into_iter().next()
            .ok_or_else(|| crate::utils::error::GatewayError::Internal("Failed to generate vector".to_string()))?;
        Ok(vector)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_mock_vectorizer() {
        let config = VectorizeConfig {
            dimensions: 128,
            normalize: true,
            ..Default::default()
        };
        let vectorizer = MockVectorizer::new(config);
        
        let texts = vec![
            "Hello world".to_string(),
            "This is a test".to_string(),
            "Another document".to_string(),
        ];
        
        let result = vectorizer.vectorize(texts).await.unwrap();
        assert_eq!(result.total_vectors, 3);
        
        for vector in &result.vectors {
            assert_eq!(vector.dimensions, 128);
            assert_eq!(vector.values.len(), 128);
        }
    }

    #[test]
    fn test_cosine_similarity() {
        let v1 = Vector::new(
            "chk_1".to_string(),
            "doc_1".to_string(),
            vec![1.0, 0.0, 0.0],
            "test".to_string(),
        );
        let v2 = Vector::new(
            "chk_2".to_string(),
            "doc_1".to_string(),
            vec![1.0, 0.0, 0.0],
            "test".to_string(),
        );
        let v3 = Vector::new(
            "chk_3".to_string(),
            "doc_1".to_string(),
            vec![0.0, 1.0, 0.0],
            "test".to_string(),
        );

        assert!((v1.cosine_similarity(&v2) - 1.0).abs() < 0.001);
        assert!((v1.cosine_similarity(&v3)).abs() < 0.001);
    }

    #[test]
    fn test_vector_normalization() {
        let mut v = Vector::new(
            "chk_1".to_string(),
            "doc_1".to_string(),
            vec![3.0, 4.0],
            "test".to_string(),
        );
        v.normalize();
        
        let norm: f32 = v.values.iter().map(|x| x * x).sum::<f32>().sqrt();
        assert!((norm - 1.0).abs() < 0.001);
        assert!((v.values[0] - 0.6).abs() < 0.001);
        assert!((v.values[1] - 0.8).abs() < 0.001);
    }

    #[tokio::test]
    async fn test_pseudo_vector_consistency() {
        let config = VectorizeConfig {
            dimensions: 64,
            ..Default::default()
        };
        let vectorizer = MockVectorizer::new(config);
        
        let texts1 = vec!["Hello world".to_string()];
        let texts2 = vec!["Hello world".to_string()];
        
        let r1 = vectorizer.vectorize(texts1).await.unwrap();
        let r2 = vectorizer.vectorize(texts2).await.unwrap();
        
        let similarity = r1.vectors[0].cosine_similarity(&r2.vectors[0]);
        assert!(similarity > 0.99);
    }
}
