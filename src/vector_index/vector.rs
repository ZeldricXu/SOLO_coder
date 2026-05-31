use crate::models::StreamSQLError;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Vector {
    pub id: String,
    pub dimensions: Vec<f32>,
    pub metadata: HashMap<String, String>,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DistanceMetric {
    Euclidean,
    Cosine,
    DotProduct,
    Manhattan,
    Chebyshev,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchResult {
    pub vector_id: String,
    pub distance: f32,
    pub score: f32,
    pub metadata: HashMap<String, String>,
}

impl Vector {
    pub fn new(id: impl Into<String>, dimensions: Vec<f32>) -> Self {
        Self {
            id: id.into(),
            dimensions,
            metadata: HashMap::new(),
            created_at: chrono::Utc::now(),
        }
    }

    pub fn with_metadata(mut self, metadata: HashMap<String, String>) -> Self {
        self.metadata = metadata;
        self
    }

    pub fn dimension(&self) -> usize {
        self.dimensions.len()
    }

    pub fn norm(&self) -> f32 {
        self.dimensions.iter().map(|x| x * x).sum::<f32>().sqrt()
    }

    pub fn normalize(&self) -> Vector {
        let norm = self.norm();
        if norm == 0.0 {
            return self.clone();
        }

        Vector {
            id: self.id.clone(),
            dimensions: self.dimensions.iter().map(|x| x / norm).collect(),
            metadata: self.metadata.clone(),
            created_at: self.created_at,
        }
    }

    pub fn dot_product(&self, other: &Vector) -> Result<f32, StreamSQLError> {
        if self.dimension() != other.dimension() {
            return Err(StreamSQLError::Vector(format!(
                "Vector dimension mismatch: {} vs {}",
                self.dimension(),
                other.dimension()
            )));
        }

        Ok(self
            .dimensions
            .iter()
            .zip(other.dimensions.iter())
            .map(|(a, b)| a * b)
            .sum())
    }

    pub fn distance(&self, other: &Vector, metric: DistanceMetric) -> Result<f32, StreamSQLError> {
        if self.dimension() != other.dimension() {
            return Err(StreamSQLError::Vector(format!(
                "Vector dimension mismatch: {} vs {}",
                self.dimension(),
                other.dimension()
            )));
        }

        let dist = match metric {
            DistanceMetric::Euclidean => {
                let sum: f32 = self
                    .dimensions
                    .iter()
                    .zip(other.dimensions.iter())
                    .map(|(a, b)| (a - b).powi(2))
                    .sum();
                sum.sqrt()
            }
            DistanceMetric::Cosine => {
                let dot = self.dot_product(other)?;
                let norm_self = self.norm();
                let norm_other = other.norm();
                if norm_self == 0.0 || norm_other == 0.0 {
                    1.0
                } else {
                    1.0 - dot / (norm_self * norm_other)
                }
            }
            DistanceMetric::DotProduct => {
                -self.dot_product(other)?
            }
            DistanceMetric::Manhattan => self
                .dimensions
                .iter()
                .zip(other.dimensions.iter())
                .map(|(a, b)| (a - b).abs())
                .sum(),
            DistanceMetric::Chebyshev => self
                .dimensions
                .iter()
                .zip(other.dimensions.iter())
                .map(|(a, b)| (a - b).abs())
                .fold(0.0, f32::max),
        };

        Ok(dist)
    }

    pub fn similarity(&self, other: &Vector, metric: DistanceMetric) -> Result<f32, StreamSQLError> {
        let dist = self.distance(other, metric)?;
        Ok(1.0 / (1.0 + dist))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VectorBatch {
    pub batch_id: String,
    pub vectors: Vec<Vector>,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

impl VectorBatch {
    pub fn new(vectors: Vec<Vector>) -> Self {
        Self {
            batch_id: format!("batch_{}", uuid::Uuid::new_v4()),
            vectors,
            created_at: chrono::Utc::now(),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.vectors.is_empty()
    }

    pub fn len(&self) -> usize {
        self.vectors.len()
    }

    pub fn dimensions(&self) -> Option<usize> {
        self.vectors.first().map(|v| v.dimension())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VectorStats {
    pub total_vectors: usize,
    pub dimensions: usize,
    pub average_norm: f32,
    pub min_norm: f32,
    pub max_norm: f32,
    pub calculated_at: chrono::DateTime<chrono::Utc>,
}

pub struct VectorStatsCalculator;

impl VectorStatsCalculator {
    pub fn calculate(vectors: &[Vector]) -> Result<VectorStats, StreamSQLError> {
        if vectors.is_empty() {
            return Err(StreamSQLError::Vector("No vectors provided".into()));
        }

        let dimensions = vectors[0].dimension();

        for v in vectors {
            if v.dimension() != dimensions {
                return Err(StreamSQLError::Vector(format!(
                    "Inconsistent dimensions: expected {}, got {}",
                    dimensions,
                    v.dimension()
                )));
            }
        }

        let norms: Vec<f32> = vectors.iter().map(|v| v.norm()).collect();
        let sum_norm: f32 = norms.iter().sum();
        let avg_norm = sum_norm / norms.len() as f32;
        let min_norm = *norms
            .iter()
            .min_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal))
            .unwrap();
        let max_norm = *norms
            .iter()
            .max_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal))
            .unwrap();

        Ok(VectorStats {
            total_vectors: vectors.len(),
            dimensions,
            average_norm: avg_norm,
            min_norm,
            max_norm,
            calculated_at: chrono::Utc::now(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_vector_creation() {
        let v = Vector::new("v1", vec![1.0, 2.0, 3.0]);
        assert_eq!(v.dimension(), 3);
    }

    #[test]
    fn test_vector_distance() {
        let v1 = Vector::new("v1", vec![0.0, 0.0]);
        let v2 = Vector::new("v2", vec![3.0, 4.0]);

        let euclidean = v1.distance(&v2, DistanceMetric::Euclidean).unwrap();
        assert!((euclidean - 5.0).abs() < 0.001);
    }

    #[test]
    fn test_vector_normalize() {
        let v = Vector::new("v1", vec![3.0, 4.0]);
        let normalized = v.normalize();

        assert!((normalized.norm() - 1.0).abs() < 0.001);
    }

    #[test]
    fn test_vector_stats() {
        let vectors = vec![
            Vector::new("v1", vec![1.0, 0.0]),
            Vector::new("v2", vec![0.0, 1.0]),
            Vector::new("v3", vec![1.0, 1.0]),
        ];

        let stats = VectorStatsCalculator::calculate(&vectors).unwrap();
        assert_eq!(stats.total_vectors, 3);
        assert_eq!(stats.dimensions, 2);
    }
}
