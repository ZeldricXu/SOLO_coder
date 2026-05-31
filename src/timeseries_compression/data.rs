use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TimeSeriesPoint {
    pub timestamp: i64,
    pub value: f64,
    pub tags: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TimeSeriesBatch {
    pub series_id: String,
    pub metric: String,
    pub points: Vec<TimeSeriesPoint>,
    pub resolution: TimeResolution,
    pub start_time: i64,
    pub end_time: i64,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "snake_case")]
pub enum TimeResolution {
    Raw,
    Second,
    Minute,
    Hour,
    Day,
    Week,
    Month,
}

impl TimeResolution {
    pub fn to_milliseconds(&self) -> i64 {
        match self {
            TimeResolution::Raw => 1,
            TimeResolution::Second => 1_000,
            TimeResolution::Minute => 60_000,
            TimeResolution::Hour => 3_600_000,
            TimeResolution::Day => 86_400_000,
            TimeResolution::Week => 604_800_000,
            TimeResolution::Month => 2_592_000_000,
        }
    }

    pub fn from_interval_ms(ms: i64) -> Self {
        match ms {
            ms if ms < 1_000 => TimeResolution::Raw,
            ms if ms < 60_000 => TimeResolution::Second,
            ms if ms < 3_600_000 => TimeResolution::Minute,
            ms if ms < 86_400_000 => TimeResolution::Hour,
            ms if ms < 604_800_000 => TimeResolution::Day,
            ms if ms < 2_592_000_000 => TimeResolution::Week,
            _ => TimeResolution::Month,
        }
    }
}

impl TimeSeriesBatch {
    pub fn new(series_id: impl Into<String>, metric: impl Into<String>) -> Self {
        Self {
            series_id: series_id.into(),
            metric: metric.into(),
            points: Vec::new(),
            resolution: TimeResolution::Raw,
            start_time: i64::MAX,
            end_time: i64::MIN,
        }
    }

    pub fn with_points(
        series_id: impl Into<String>,
        metric: impl Into<String>,
        points: Vec<TimeSeriesPoint>,
    ) -> Self {
        let mut batch = Self::new(series_id, metric);
        for point in points {
            batch.add_point(point);
        }
        batch
    }

    pub fn add_point(&mut self, point: TimeSeriesPoint) {
        self.start_time = self.start_time.min(point.timestamp);
        self.end_time = self.end_time.max(point.timestamp);
        self.points.push(point);
    }

    pub fn len(&self) -> usize {
        self.points.len()
    }

    pub fn is_empty(&self) -> bool {
        self.points.is_empty()
    }

    pub fn duration_ms(&self) -> i64 {
        if self.is_empty() {
            0
        } else {
            self.end_time - self.start_time
        }
    }

    pub fn min_value(&self) -> Option<f64> {
        self.points.iter().map(|p| p.value).fold(None, |min, v| match min {
            None => Some(v),
            Some(m) => Some(m.min(v)),
        })
    }

    pub fn max_value(&self) -> Option<f64> {
        self.points.iter().map(|p| p.value).fold(None, |max, v| match max {
            None => Some(v),
            Some(m) => Some(m.max(v)),
        })
    }

    pub fn avg_value(&self) -> Option<f64> {
        if self.is_empty() {
            None
        } else {
            let sum: f64 = self.points.iter().map(|p| p.value).sum();
            Some(sum / self.points.len() as f64)
        }
    }

    pub fn sort_by_time(&mut self) {
        self.points.sort_by_key(|p| p.timestamp);
    }

    pub fn dedup(&mut self) {
        self.sort_by_time();
        self.points.dedup_by_key(|p| p.timestamp);
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TimeSeriesStats {
    pub series_id: String,
    pub total_points: usize,
    pub start_time: i64,
    pub end_time: i64,
    pub min_value: Option<f64>,
    pub max_value: Option<f64>,
    pub avg_value: Option<f64>,
    pub resolution: TimeResolution,
}

pub struct TimeSeriesStatsCalculator;

impl TimeSeriesStatsCalculator {
    pub fn calculate(batch: &TimeSeriesBatch) -> TimeSeriesStats {
        TimeSeriesStats {
            series_id: batch.series_id.clone(),
            total_points: batch.len(),
            start_time: if batch.is_empty() {
                0
            } else {
                batch.start_time
            },
            end_time: if batch.is_empty() {
                0
            } else {
                batch.end_time
            },
            min_value: batch.min_value(),
            max_value: batch.max_value(),
            avg_value: batch.avg_value(),
            resolution: batch.resolution,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MultiResolutionSeries {
    pub series_id: String,
    pub metric: String,
    pub resolutions: HashMap<TimeResolution, TimeSeriesBatch>,
}

impl MultiResolutionSeries {
    pub fn new(series_id: impl Into<String>, metric: impl Into<String>) -> Self {
        Self {
            series_id: series_id.into(),
            metric: metric.into(),
            resolutions: HashMap::new(),
        }
    }

    pub fn add_batch(&mut self, batch: TimeSeriesBatch) {
        self.resolutions.insert(batch.resolution, batch);
    }

    pub fn get_resolution(&self, resolution: TimeResolution) -> Option<&TimeSeriesBatch> {
        self.resolutions.get(&resolution)
    }

    pub fn available_resolutions(&self) -> Vec<TimeResolution> {
        let mut resolutions: Vec<TimeResolution> = self.resolutions.keys().cloned().collect();
        resolutions.sort();
        resolutions
    }

    pub fn highest_resolution(&self) -> Option<TimeResolution> {
        self.resolutions.keys().min().cloned()
    }

    pub fn lowest_resolution(&self) -> Option<TimeResolution> {
        self.resolutions.keys().max().cloned()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_batch_creation() {
        let batch = TimeSeriesBatch::new("s1", "cpu_usage");
        assert_eq!(batch.series_id, "s1");
        assert_eq!(batch.metric, "cpu_usage");
        assert!(batch.is_empty());
    }

    #[test]
    fn test_add_points() {
        let mut batch = TimeSeriesBatch::new("s1", "cpu_usage");

        batch.add_point(TimeSeriesPoint {
            timestamp: 1000,
            value: 10.0,
            tags: HashMap::new(),
        });

        batch.add_point(TimeSeriesPoint {
            timestamp: 2000,
            value: 20.0,
            tags: HashMap::new(),
        });

        assert_eq!(batch.len(), 2);
        assert_eq!(batch.start_time, 1000);
        assert_eq!(batch.end_time, 2000);
    }

    #[test]
    fn test_batch_stats() {
        let points = vec![
            TimeSeriesPoint {
                timestamp: 1000,
                value: 10.0,
                tags: HashMap::new(),
            },
            TimeSeriesPoint {
                timestamp: 2000,
                value: 20.0,
                tags: HashMap::new(),
            },
            TimeSeriesPoint {
                timestamp: 3000,
                value: 30.0,
                tags: HashMap::new(),
            },
        ];

        let batch = TimeSeriesBatch::with_points("s1", "cpu_usage", points);

        assert_eq!(batch.min_value(), Some(10.0));
        assert_eq!(batch.max_value(), Some(30.0));
        assert_eq!(batch.avg_value(), Some(20.0));
    }

    #[test]
    fn test_resolution_conversion() {
        assert_eq!(TimeResolution::Second.to_milliseconds(), 1_000);
        assert_eq!(TimeResolution::Minute.to_milliseconds(), 60_000);
        assert_eq!(TimeResolution::Hour.to_milliseconds(), 3_600_000);
    }

    #[test]
    fn test_multi_resolution() {
        let mut series = MultiResolutionSeries::new("s1", "cpu_usage");

        let raw_batch = TimeSeriesBatch {
            series_id: "s1".to_string(),
            metric: "cpu_usage".to_string(),
            points: Vec::new(),
            resolution: TimeResolution::Raw,
            start_time: 0,
            end_time: 0,
        };

        let hour_batch = TimeSeriesBatch {
            series_id: "s1".to_string(),
            metric: "cpu_usage".to_string(),
            points: Vec::new(),
            resolution: TimeResolution::Hour,
            start_time: 0,
            end_time: 0,
        };

        series.add_batch(raw_batch);
        series.add_batch(hour_batch);

        assert_eq!(series.available_resolutions().len(), 2);
        assert_eq!(series.highest_resolution(), Some(TimeResolution::Raw));
        assert_eq!(series.lowest_resolution(), Some(TimeResolution::Hour));
    }
}
