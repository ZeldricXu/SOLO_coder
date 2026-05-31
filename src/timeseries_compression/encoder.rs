use crate::models::StreamSQLError;
use crate::timeseries_compression::data::{TimeSeriesBatch, TimeSeriesPoint, TimeResolution};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CompressionAlgorithm {
    Raw,
    Delta,
    Gorilla,
    Simple8b,
    Lz4,
    Zstd,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CompressionConfig {
    pub algorithm: CompressionAlgorithm,
    pub level: u32,
    pub enable_delta: bool,
    pub block_size: usize,
}

impl Default for CompressionConfig {
    fn default() -> Self {
        Self {
            algorithm: CompressionAlgorithm::Gorilla,
            level: 3,
            enable_delta: true,
            block_size: 1024,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CompressedData {
    pub series_id: String,
    pub metric: String,
    pub algorithm: CompressionAlgorithm,
    pub original_size_bytes: usize,
    pub compressed_size_bytes: usize,
    pub compression_ratio: f64,
    pub data: Vec<u8>,
    pub num_points: usize,
    pub start_time: i64,
    pub end_time: i64,
    pub resolution: TimeResolution,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

pub struct TimeSeriesEncoder {
    config: CompressionConfig,
}

impl TimeSeriesEncoder {
    pub fn new(config: CompressionConfig) -> Self {
        Self { config }
    }

    pub fn encode(&self, batch: &TimeSeriesBatch) -> Result<CompressedData, StreamSQLError> {
        let start_time = std::time::Instant::now();

        let (data, original_size) = match self.config.algorithm {
            CompressionAlgorithm::Raw => self.encode_raw(batch),
            CompressionAlgorithm::Delta => self.encode_delta(batch),
            CompressionAlgorithm::Gorilla => self.encode_gorilla(batch),
            CompressionAlgorithm::Simple8b => self.encode_simple8b(batch),
            CompressionAlgorithm::Lz4 => self.encode_lz4(batch),
            CompressionAlgorithm::Zstd => self.encode_zstd(batch),
        }?;

        let compressed_size = data.len();
        let compression_ratio = if compressed_size > 0 {
            original_size as f64 / compressed_size as f64
        } else {
            1.0
        };

        Ok(CompressedData {
            series_id: batch.series_id.clone(),
            metric: batch.metric.clone(),
            algorithm: self.config.algorithm,
            original_size_bytes: original_size,
            compressed_size_bytes: compressed_size,
            compression_ratio,
            data,
            num_points: batch.len(),
            start_time: if batch.is_empty() { 0 } else { batch.start_time },
            end_time: if batch.is_empty() { 0 } else { batch.end_time },
            resolution: batch.resolution,
            created_at: chrono::Utc::now(),
        })
    }

    fn encode_raw(&self, batch: &TimeSeriesBatch) -> Result<(Vec<u8>, usize), StreamSQLError> {
        let serialized = bincode::serialize(batch)
            .map_err(|e| StreamSQLError::Compression(e.to_string()))?;
        let original_size = serialized.len();
        Ok((serialized, original_size))
    }

    fn encode_delta(&self, batch: &TimeSeriesBatch) -> Result<(Vec<u8>, usize), StreamSQLError> {
        let mut points = batch.points.clone();
        points.sort_by_key(|p| p.timestamp);

        let mut delta_points = Vec::new();
        let mut prev_timestamp = 0i64;
        let mut prev_value = 0.0f64;

        for (i, point) in points.iter().enumerate() {
            if i == 0 {
                delta_points.push((point.timestamp, point.value));
                prev_timestamp = point.timestamp;
                prev_value = point.value;
            } else {
                let delta_ts = point.timestamp - prev_timestamp;
                let delta_val = point.value - prev_value;
                delta_points.push((delta_ts, delta_val));
                prev_timestamp = point.timestamp;
                prev_value = point.value;
            }
        }

        let serialized = bincode::serialize(&delta_points)
            .map_err(|e| StreamSQLError::Compression(e.to_string()))?;
        let original_size = bincode::serialize(&points).unwrap().len();

        Ok((serialized, original_size))
    }

    fn encode_gorilla(&self, batch: &TimeSeriesBatch) -> Result<(Vec<u8>, usize), StreamSQLError> {
        let mut points = batch.points.clone();
        points.sort_by_key(|p| p.timestamp);

        let mut encoded = Vec::new();
        let original_size = bincode::serialize(&points).unwrap().len();

        if points.is_empty() {
            return Ok((encoded, original_size));
        }

        let first = &points[0];
        encoded.extend_from_slice(&first.timestamp.to_be_bytes());
        encoded.extend_from_slice(&first.value.to_be_bytes());

        let mut prev_timestamp = first.timestamp;
        let mut prev_value = first.value.to_bits();

        for point in &points[1..] {
            let delta_ts = point.timestamp - prev_timestamp;
            encoded.extend_from_slice(&delta_ts.to_be_bytes());

            let curr_bits = point.value.to_bits();
            let xor = curr_bits ^ prev_value;
            encoded.extend_from_slice(&xor.to_be_bytes());

            prev_timestamp = point.timestamp;
            prev_value = curr_bits;
        }

        Ok((encoded, original_size))
    }

    fn encode_simple8b(&self, batch: &TimeSeriesBatch) -> Result<(Vec<u8>, usize), StreamSQLError> {
        let mut timestamps: Vec<u64> = batch.points.iter().map(|p| p.timestamp as u64).collect();
        let values: Vec<u64> = batch.points.iter().map(|p| p.value.to_bits()).collect();

        timestamps.sort();

        let mut encoded = Vec::new();
        let original_size = (timestamps.len() * 8) + (values.len() * 8);

        for ts in timestamps.chunks(self.config.block_size) {
            for t in ts {
                encoded.extend_from_slice(&t.to_be_bytes());
            }
        }

        for v in values.chunks(self.config.block_size) {
            for val in v {
                encoded.extend_from_slice(&val.to_be_bytes());
            }
        }

        Ok((encoded, original_size))
    }

    fn encode_lz4(&self, batch: &TimeSeriesBatch) -> Result<(Vec<u8>, usize), StreamSQLError> {
        let serialized = bincode::serialize(batch)
            .map_err(|e| StreamSQLError::Compression(e.to_string()))?;
        let original_size = serialized.len();

        let compressed = lz4_flex::compress(&serialized);

        Ok((compressed, original_size))
    }

    fn encode_zstd(&self, batch: &TimeSeriesBatch) -> Result<(Vec<u8>, usize), StreamSQLError> {
        let serialized = bincode::serialize(batch)
            .map_err(|e| StreamSQLError::Compression(e.to_string()))?;
        let original_size = serialized.len();

        let compressed = zstd::encode_all(&serialized[..], self.config.level as i32)
            .map_err(|e| StreamSQLError::Compression(e.to_string()))?;

        Ok((compressed, original_size))
    }

    pub fn config(&self) -> &CompressionConfig {
        &self.config
    }
}

impl Default for TimeSeriesEncoder {
    fn default() -> Self {
        Self::new(CompressionConfig::default())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn create_test_batch() -> TimeSeriesBatch {
        let points: Vec<TimeSeriesPoint> = (0..100)
            .map(|i| TimeSeriesPoint {
                timestamp: i * 1000,
                value: (i as f64).sin() * 100.0,
                tags: HashMap::new(),
            })
            .collect();

        TimeSeriesBatch::with_points("s1", "cpu_usage", points)
    }

    #[test]
    fn test_encoder_creation() {
        let encoder = TimeSeriesEncoder::default();
        assert_eq!(encoder.config().algorithm, CompressionAlgorithm::Gorilla);
    }

    #[test]
    fn test_raw_encoding() {
        let config = CompressionConfig {
            algorithm: CompressionAlgorithm::Raw,
            ..Default::default()
        };
        let encoder = TimeSeriesEncoder::new(config);
        let batch = create_test_batch();

        let result = encoder.encode(&batch).unwrap();
        assert_eq!(result.num_points, 100);
        assert!(result.compression_ratio >= 1.0);
    }

    #[test]
    fn test_delta_encoding() {
        let config = CompressionConfig {
            algorithm: CompressionAlgorithm::Delta,
            ..Default::default()
        };
        let encoder = TimeSeriesEncoder::new(config);
        let batch = create_test_batch();

        let result = encoder.encode(&batch).unwrap();
        assert_eq!(result.num_points, 100);
        assert!(result.compressed_size_bytes > 0);
    }

    #[test]
    fn test_gorilla_encoding() {
        let config = CompressionConfig {
            algorithm: CompressionAlgorithm::Gorilla,
            ..Default::default()
        };
        let encoder = TimeSeriesEncoder::new(config);
        let batch = create_test_batch();

        let result = encoder.encode(&batch).unwrap();
        assert_eq!(result.num_points, 100);
        assert!(result.compressed_size_bytes > 0);
    }

    #[test]
    fn test_lz4_encoding() {
        let config = CompressionConfig {
            algorithm: CompressionAlgorithm::Lz4,
            ..Default::default()
        };
        let encoder = TimeSeriesEncoder::new(config);
        let batch = create_test_batch();

        let result = encoder.encode(&batch).unwrap();
        assert_eq!(result.num_points, 100);
        assert!(result.compression_ratio >= 1.0);
    }

    #[test]
    fn test_zstd_encoding() {
        let config = CompressionConfig {
            algorithm: CompressionAlgorithm::Zstd,
            level: 3,
            ..Default::default()
        };
        let encoder = TimeSeriesEncoder::new(config);
        let batch = create_test_batch();

        let result = encoder.encode(&batch).unwrap();
        assert_eq!(result.num_points, 100);
        assert!(result.compression_ratio >= 1.0);
    }

    #[test]
    fn test_empty_batch() {
        let encoder = TimeSeriesEncoder::default();
        let batch = TimeSeriesBatch::new("s1", "cpu_usage");

        let result = encoder.encode(&batch).unwrap();
        assert_eq!(result.num_points, 0);
    }
}
