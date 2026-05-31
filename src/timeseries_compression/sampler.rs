use crate::timeseries_compression::data::{TimeSeriesBatch, TimeSeriesPoint, TimeResolution, MultiResolutionSeries};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DownsampleMethod {
    First,
    Last,
    Min,
    Max,
    Avg,
    Median,
    Sum,
    Sample,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DownsampleConfig {
    pub target_resolution: TimeResolution,
    pub method: DownsampleMethod,
    pub include_timestamp: bool,
}

impl Default for DownsampleConfig {
    fn default() -> Self {
        Self {
            target_resolution: TimeResolution::Minute,
            method: DownsampleMethod::Avg,
            include_timestamp: true,
        }
    }
}

pub struct TimeSeriesSampler;

impl TimeSeriesSampler {
    pub fn downsample(
        batch: &TimeSeriesBatch,
        config: &DownsampleConfig,
    ) -> TimeSeriesBatch {
        if batch.is_empty() {
            let mut result = TimeSeriesBatch::new(&batch.series_id, &batch.metric);
            result.resolution = config.target_resolution;
            return result;
        }

        let interval_ms = config.target_resolution.to_milliseconds();
        let mut buckets: HashMap<i64, Vec<&TimeSeriesPoint>> = HashMap::new();

        for point in &batch.points {
            let bucket_ts = (point.timestamp / interval_ms) * interval_ms;
            buckets.entry(bucket_ts).or_default().push(point);
        }

        let mut result_points = Vec::new();

        for (bucket_ts, points) in buckets {
            let value = Self::aggregate(&points, config.method);
            result_points.push(TimeSeriesPoint {
                timestamp: bucket_ts,
                value,
                tags: HashMap::new(),
            });
        }

        result_points.sort_by_key(|p| p.timestamp);

        let mut result = TimeSeriesBatch::with_points(
            &batch.series_id,
            &batch.metric,
            result_points,
        );
        result.resolution = config.target_resolution;

        result
    }

    fn aggregate(points: &[&TimeSeriesPoint], method: DownsampleMethod) -> f64 {
        if points.is_empty() {
            return 0.0;
        }

        match method {
            DownsampleMethod::First => points[0].value,
            DownsampleMethod::Last => points[points.len() - 1].value,
            DownsampleMethod::Min => points.iter().map(|p| p.value).fold(f64::INFINITY, f64::min),
            DownsampleMethod::Max => points.iter().map(|p| p.value).fold(f64::NEG_INFINITY, f64::max),
            DownsampleMethod::Avg => {
                let sum: f64 = points.iter().map(|p| p.value).sum();
                sum / points.len() as f64
            }
            DownsampleMethod::Median => {
                let mut values: Vec<f64> = points.iter().map(|p| p.value).collect();
                values.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
                let mid = values.len() / 2;
                if values.len() % 2 == 0 {
                    (values[mid - 1] + values[mid]) / 2.0
                } else {
                    values[mid]
                }
            }
            DownsampleMethod::Sum => points.iter().map(|p| p.value).sum(),
            DownsampleMethod::Sample => {
                use rand::Rng;
                let mut rng = rand::thread_rng();
                let idx = rng.gen_range(0..points.len());
                points[idx].value
            }
        }
    }

    pub fn upsample(
        batch: &TimeSeriesBatch,
        target_resolution: TimeResolution,
        method: InterpolationMethod,
    ) -> TimeSeriesBatch {
        if batch.is_empty() {
            let mut result = TimeSeriesBatch::new(&batch.series_id, &batch.metric);
            result.resolution = target_resolution;
            return result;
        }

        let interval_ms = target_resolution.to_milliseconds();
        let mut sorted_points = batch.points.clone();
        sorted_points.sort_by_key(|p| p.timestamp);

        let mut result_points = Vec::new();

        for window in sorted_points.windows(2) {
            let start = &window[0];
            let end = &window[1];

            let mut current_ts = start.timestamp + interval_ms;
            while current_ts < end.timestamp {
                let value = Self::interpolate(start, end, current_ts, method);
                result_points.push(TimeSeriesPoint {
                    timestamp: current_ts,
                    value,
                    tags: HashMap::new(),
                });
                current_ts += interval_ms;
            }
        }

        let mut result = TimeSeriesBatch::with_points(
            &batch.series_id,
            &batch.metric,
            result_points,
        );
        result.resolution = target_resolution;

        result
    }

    fn interpolate(
        start: &TimeSeriesPoint,
        end: &TimeSeriesPoint,
        target_ts: i64,
        method: InterpolationMethod,
    ) -> f64 {
        match method {
            InterpolationMethod::Linear => {
                let t_range = (end.timestamp - start.timestamp) as f64;
                let v_range = end.value - start.value;
                let t_pos = (target_ts - start.timestamp) as f64 / t_range;
                start.value + v_range * t_pos
            }
            InterpolationMethod::Previous => start.value,
            InterpolationMethod::Next => end.value,
            InterpolationMethod::Nearest => {
                let dist_start = (target_ts - start.timestamp).abs();
                let dist_end = (end.timestamp - target_ts).abs();
                if dist_start <= dist_end {
                    start.value
                } else {
                    end.value
                }
            }
        }
    }

    pub fn create_multi_resolution(
        batch: &TimeSeriesBatch,
        resolutions: &[TimeResolution],
        method: DownsampleMethod,
    ) -> MultiResolutionSeries {
        let mut series = MultiResolutionSeries::new(&batch.series_id, &batch.metric);

        let mut raw_batch = batch.clone();
        raw_batch.resolution = TimeResolution::Raw;
        series.add_batch(raw_batch);

        for &resolution in resolutions {
            if resolution <= TimeResolution::Raw {
                continue;
            }

            let config = DownsampleConfig {
                target_resolution: resolution,
                method,
                include_timestamp: true,
            };

            let downsampled = Self::downsample(batch, &config);
            series.add_batch(downsampled);
        }

        series
    }

    pub fn query_by_resolution(
        series: &MultiResolutionSeries,
        start_time: i64,
        end_time: i64,
        target_resolution: TimeResolution,
    ) -> Option<TimeSeriesBatch> {
        let available = series.available_resolutions();

        let selected = available
            .iter()
            .filter(|&&r| r <= target_resolution)
            .max()
            .or_else(|| available.iter().min());

        selected.and_then(|&res| {
            series.get_resolution(res).map(|batch| {
                let filtered: Vec<TimeSeriesPoint> = batch
                    .points
                    .iter()
                    .filter(|p| p.timestamp >= start_time && p.timestamp <= end_time)
                    .cloned()
                    .collect();

                let mut result = TimeSeriesBatch::with_points(
                    &batch.series_id,
                    &batch.metric,
                    filtered,
                );
                result.resolution = res;
                result
            })
        })
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum InterpolationMethod {
    Linear,
    Previous,
    Next,
    Nearest,
}

impl Default for InterpolationMethod {
    fn default() -> Self {
        InterpolationMethod::Linear
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn create_test_batch() -> TimeSeriesBatch {
        let points: Vec<TimeSeriesPoint> = (0..60)
            .map(|i| TimeSeriesPoint {
                timestamp: i * 1000,
                value: i as f64,
                tags: HashMap::new(),
            })
            .collect();

        TimeSeriesBatch::with_points("s1", "cpu_usage", points)
    }

    #[test]
    fn test_downsample_avg() {
        let batch = create_test_batch();
        let config = DownsampleConfig {
            target_resolution: TimeResolution::Minute,
            method: DownsampleMethod::Avg,
            include_timestamp: true,
        };

        let downsampled = TimeSeriesSampler::downsample(&batch, &config);

        assert!(downsampled.len() <= batch.len());
        assert_eq!(downsampled.resolution, TimeResolution::Minute);
    }

    #[test]
    fn test_downsample_first() {
        let batch = create_test_batch();
        let config = DownsampleConfig {
            target_resolution: TimeResolution::Minute,
            method: DownsampleMethod::First,
            include_timestamp: true,
        };

        let downsampled = TimeSeriesSampler::downsample(&batch, &config);
        assert!(downsampled.len() > 0);
    }

    #[test]
    fn test_downsample_min_max() {
        let batch = create_test_batch();

        let min_config = DownsampleConfig {
            target_resolution: TimeResolution::Minute,
            method: DownsampleMethod::Min,
            include_timestamp: true,
        };

        let max_config = DownsampleConfig {
            target_resolution: TimeResolution::Minute,
            method: DownsampleMethod::Max,
            include_timestamp: true,
        };

        let min_down = TimeSeriesSampler::downsample(&batch, &min_config);
        let max_down = TimeSeriesSampler::downsample(&batch, &max_config);

        assert_eq!(min_down.len(), max_down.len());
    }

    #[test]
    fn test_empty_batch_downsample() {
        let batch = TimeSeriesBatch::new("s1", "cpu_usage");
        let config = DownsampleConfig::default();

        let downsampled = TimeSeriesSampler::downsample(&batch, &config);
        assert!(downsampled.is_empty());
    }

    #[test]
    fn test_multi_resolution() {
        let batch = create_test_batch();
        let resolutions = vec![TimeResolution::Second, TimeResolution::Minute];

        let series = TimeSeriesSampler::create_multi_resolution(
            &batch,
            &resolutions,
            DownsampleMethod::Avg,
        );

        let available = series.available_resolutions();
        assert!(available.len() >= 2);
        assert!(available.contains(&TimeResolution::Raw));
    }

    #[test]
    fn test_query_by_resolution() {
        let batch = create_test_batch();
        let resolutions = vec![TimeResolution::Minute];

        let series = TimeSeriesSampler::create_multi_resolution(
            &batch,
            &resolutions,
            DownsampleMethod::Avg,
        );

        let result = TimeSeriesSampler::query_by_resolution(
            &series,
            0,
            60000,
            TimeResolution::Minute,
        );

        assert!(result.is_some());
    }
}
