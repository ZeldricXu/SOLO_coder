use crate::models::StreamSQLError;
use crate::timeseries_compression::data::{TimeSeriesBatch, TimeSeriesPoint, TimeResolution};
use crate::timeseries_compression::encoder::{CompressedData, CompressionConfig, CompressionAlgorithm};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

pub struct TimeSeriesDecoder {
    config: CompressionConfig,
}

impl TimeSeriesDecoder {
    pub fn new(config: CompressionConfig) -> Self {
        Self { config }
    }

    pub fn decode(&self, compressed: &CompressedData) -> Result<TimeSeriesBatch, StreamSQLError> {
        match compressed.algorithm {
            CompressionAlgorithm::Raw => self.decode_raw(compressed),
            CompressionAlgorithm::Delta => self.decode_delta(compressed),
            CompressionAlgorithm::Gorilla => self.decode_gorilla(compressed),
            CompressionAlgorithm::Simple8b => self.decode_simple8b(compressed),
            CompressionAlgorithm::Lz4 => self.decode_lz4(compressed),
            CompressionAlgorithm::Zstd => self.decode_zstd(compressed),
        }
    }

    fn decode_raw(&self, compressed: &CompressedData) -> Result<TimeSeriesBatch, StreamSQLError> {
        let batch: TimeSeriesBatch = bincode::deserialize(&compressed.data)
            .map_err(|e| StreamSQLError::Compression(e.to_string()))?;
        Ok(batch)
    }

    fn decode_delta(&self, compressed: &CompressedData) -> Result<TimeSeriesBatch, StreamSQLError> {
        let delta_points: Vec<(i64, f64)> = bincode::deserialize(&compressed.data)
            .map_err(|e| StreamSQLError::Compression(e.to_string()))?;

        let mut points = Vec::new();
        let mut prev_timestamp = 0i64;
        let mut prev_value = 0.0f64;

        for (i, (ts, val)) in delta_points.iter().enumerate() {
            if i == 0 {
                prev_timestamp = *ts;
                prev_value = *val;
                points.push(TimeSeriesPoint {
                    timestamp: *ts,
                    value: *val,
                    tags: HashMap::new(),
                });
            } else {
                let actual_ts = prev_timestamp + *ts;
                let actual_val = prev_value + *val;
                points.push(TimeSeriesPoint {
                    timestamp: actual_ts,
                    value: actual_val,
                    tags: HashMap::new(),
                });
                prev_timestamp = actual_ts;
                prev_value = actual_val;
            }
        }

        let mut batch = TimeSeriesBatch::new(&compressed.series_id, &compressed.metric);
        batch.resolution = compressed.resolution;

        for point in points {
            batch.add_point(point);
        }

        Ok(batch)
    }

    fn decode_gorilla(&self, compressed: &CompressedData) -> Result<TimeSeriesBatch, StreamSQLError> {
        if compressed.data.is_empty() {
            return Ok(TimeSeriesBatch::new(
                &compressed.series_id,
                &compressed.metric,
            ));
        }

        let mut points = Vec::new();
        let mut offset = 0;

        let first_ts = i64::from_be_bytes(compressed.data[offset..offset + 8].try_into().unwrap());
        offset += 8;

        let first_val_bits = u64::from_be_bytes(compressed.data[offset..offset + 8].try_into().unwrap());
        offset += 8;

        let first_val = f64::from_bits(first_val_bits);

        points.push(TimeSeriesPoint {
            timestamp: first_ts,
            value: first_val,
            tags: HashMap::new(),
        });

        let mut prev_timestamp = first_ts;
        let mut prev_value = first_val_bits;

        while offset < compressed.data.len() {
            let delta_ts = i64::from_be_bytes(compressed.data[offset..offset + 8].try_into().unwrap());
            offset += 8;

            let xor = u64::from_be_bytes(compressed.data[offset..offset + 8].try_into().unwrap());
            offset += 8;

            let actual_ts = prev_timestamp + delta_ts;
            let actual_val_bits = prev_value ^ xor;
            let actual_val = f64::from_bits(actual_val_bits);

            points.push(TimeSeriesPoint {
                timestamp: actual_ts,
                value: actual_val,
                tags: HashMap::new(),
            });

            prev_timestamp = actual_ts;
            prev_value = actual_val_bits;
        }

        let mut batch = TimeSeriesBatch::new(&compressed.series_id, &compressed.metric);
        batch.resolution = compressed.resolution;

        for point in points {
            batch.add_point(point);
        }

        Ok(batch)
    }

    fn decode_simple8b(&self, compressed: &CompressedData) -> Result<TimeSeriesBatch, StreamSQLError> {
        let num_points = compressed.num_points;

        if num_points == 0 {
            return Ok(TimeSeriesBatch::new(
                &compressed.series_id,
                &compressed.metric,
            ));
        }

        let mut timestamps = Vec::with_capacity(num_points);
        let mut values = Vec::with_capacity(num_points);

        let mut offset = 0;

        for _ in 0..num_points {
            if offset + 8 <= compressed.data.len() {
                let ts = u64::from_be_bytes(compressed.data[offset..offset + 8].try_into().unwrap()) as i64;
                timestamps.push(ts);
                offset += 8;
            }
        }

        for _ in 0..num_points {
            if offset + 8 <= compressed.data.len() {
                let val_bits = u64::from_be_bytes(compressed.data[offset..offset + 8].try_into().unwrap());
                values.push(f64::from_bits(val_bits));
                offset += 8;
            }
        }

        let mut batch = TimeSeriesBatch::new(&compressed.series_id, &compressed.metric);
        batch.resolution = compressed.resolution;

        for i in 0..std::cmp::min(timestamps.len(), values.len()) {
            batch.add_point(TimeSeriesPoint {
                timestamp: timestamps[i],
                value: values[i],
                tags: HashMap::new(),
            });
        }

        Ok(batch)
    }

    fn decode_lz4(&self, compressed: &CompressedData) -> Result<TimeSeriesBatch, StreamSQLError> {
        let decompressed = lz4_flex::decompress(&compressed.data, compressed.original_size_bytes)
            .map_err(|e| StreamSQLError::Compression(e.to_string()))?;

        let batch: TimeSeriesBatch = bincode::deserialize(&decompressed)
            .map_err(|e| StreamSQLError::Compression(e.to_string()))?;

        Ok(batch)
    }

    fn decode_zstd(&self, compressed: &CompressedData) -> Result<TimeSeriesBatch, StreamSQLError> {
        let decompressed = zstd::decode_all(&compressed.data[..])
            .map_err(|e| StreamSQLError::Compression(e.to_string()))?;

        let batch: TimeSeriesBatch = bincode::deserialize(&decompressed)
            .map_err(|e| StreamSQLError::Compression(e.to_string()))?;

        Ok(batch)
    }

    pub fn config(&self) -> &CompressionConfig {
        &self.config
    }
}

impl Default for TimeSeriesDecoder {
    fn default() -> Self {
        Self::new(CompressionConfig::default())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::timeseries_compression::encoder::TimeSeriesEncoder;
    use std::collections::HashMap;

    fn create_test_batch() -> TimeSeriesBatch {
        let points: Vec<TimeSeriesPoint> = (0..50)
            .map(|i| TimeSeriesPoint {
                timestamp: i * 1000,
                value: (i as f64).sin() * 100.0,
                tags: HashMap::new(),
            })
            .collect();

        TimeSeriesBatch::with_points("s1", "cpu_usage", points)
    }

    #[test]
    fn test_decoder_creation() {
        let decoder = TimeSeriesDecoder::default();
        assert_eq!(decoder.config().algorithm, CompressionAlgorithm::Gorilla);
    }

    #[test]
    fn test_encode_decode_raw() {
        let config = CompressionConfig {
            algorithm: CompressionAlgorithm::Raw,
            ..Default::default()
        };
        let encoder = TimeSeriesEncoder::new(config.clone());
        let decoder = TimeSeriesDecoder::new(config);

        let batch = create_test_batch();
        let compressed = encoder.encode(&batch).unwrap();
        let decoded = decoder.decode(&compressed).unwrap();

        assert_eq!(decoded.len(), batch.len());
        assert_eq!(decoded.start_time, batch.start_time);
        assert_eq!(decoded.end_time, batch.end_time);
    }

    #[test]
    fn test_encode_decode_delta() {
        let config = CompressionConfig {
            algorithm: CompressionAlgorithm::Delta,
            ..Default::default()
        };
        let encoder = TimeSeriesEncoder::new(config.clone());
        let decoder = TimeSeriesDecoder::new(config);

        let batch = create_test_batch();
        let compressed = encoder.encode(&batch).unwrap();
        let decoded = decoder.decode(&compressed).unwrap();

        assert_eq!(decoded.len(), batch.len());
    }

    #[test]
    fn test_encode_decode_gorilla() {
        let config = CompressionConfig {
            algorithm: CompressionAlgorithm::Gorilla,
            ..Default::default()
        };
        let encoder = TimeSeriesEncoder::new(config.clone());
        let decoder = TimeSeriesDecoder::new(config);

        let batch = create_test_batch();
        let compressed = encoder.encode(&batch).unwrap();
        let decoded = decoder.decode(&compressed).unwrap();

        assert_eq!(decoded.len(), batch.len());
    }

    #[test]
    fn test_encode_decode_lz4() {
        let config = CompressionConfig {
            algorithm: CompressionAlgorithm::Lz4,
            ..Default::default()
        };
        let encoder = TimeSeriesEncoder::new(config.clone());
        let decoder = TimeSeriesDecoder::new(config);

        let batch = create_test_batch();
        let compressed = encoder.encode(&batch).unwrap();
        let decoded = decoder.decode(&compressed).unwrap();

        assert_eq!(decoded.len(), batch.len());
        assert_eq!(decoded.metric, batch.metric);
    }

    #[test]
    fn test_encode_decode_zstd() {
        let config = CompressionConfig {
            algorithm: CompressionAlgorithm::Zstd,
            level: 3,
            ..Default::default()
        };
        let encoder = TimeSeriesEncoder::new(config.clone());
        let decoder = TimeSeriesDecoder::new(config);

        let batch = create_test_batch();
        let compressed = encoder.encode(&batch).unwrap();
        let decoded = decoder.decode(&compressed).unwrap();

        assert_eq!(decoded.len(), batch.len());
        assert_eq!(decoded.series_id, batch.series_id);
    }

    #[test]
    fn test_empty_batch() {
        let config = CompressionConfig::default();
        let encoder = TimeSeriesEncoder::new(config.clone());
        let decoder = TimeSeriesDecoder::new(config);

        let batch = TimeSeriesBatch::new("s1", "cpu_usage");
        let compressed = encoder.encode(&batch).unwrap();
        let decoded = decoder.decode(&compressed).unwrap();

        assert!(decoded.is_empty());
    }
}
