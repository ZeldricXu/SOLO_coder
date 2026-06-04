use anyhow::Result;
use chrono::Duration;
use std::collections::BTreeMap;

use common::metrics::{AggregationFunction, Labels, MetricPoint, TimeSeries};

pub struct QueryExecutor;

impl QueryExecutor {
    pub fn new() -> Self {
        Self
    }

    pub fn execute_aggregation(
        &self,
        series: &TimeSeries,
        function: AggregationFunction,
        window: Option<Duration>,
    ) -> Result<TimeSeries> {
        match function {
            AggregationFunction::Rate => self.rate(series, window.unwrap_or(Duration::minutes(5))),
            AggregationFunction::Sum => self.sum(series),
            AggregationFunction::Avg => self.avg(series),
            AggregationFunction::Max => self.max(series),
            AggregationFunction::Min => self.min(series),
            AggregationFunction::Count => self.count(series),
            AggregationFunction::Quantile(q) => self.quantile(series, q),
        }
    }

    fn rate(&self, series: &TimeSeries, window: Duration) -> Result<TimeSeries> {
        if series.points.len() < 2 {
            return Ok(series.clone());
        }

        let mut result = TimeSeries::new(
            format!("rate({})", series.metric_name),
            series.labels.clone(),
        );

        let window_secs = window.num_seconds() as f64;
        let mut points: Vec<&MetricPoint> = series.points.iter().collect();
        points.sort_by(|a, b| a.timestamp.cmp(&b.timestamp));

        for i in 1..points.len() {
            let dt = (points[i].timestamp - points[i - 1].timestamp).num_seconds() as f64;
            if dt > 0.0 {
                let rate = (points[i].value - points[i - 1].value) / dt * window_secs;
                result.add_point(points[i].timestamp, rate);
            }
        }

        Ok(result)
    }

    fn sum(&self, series: &TimeSeries) -> Result<TimeSeries> {
        let sum: f64 = series.points.iter().map(|p| p.value).sum();
        let mut result = TimeSeries::new(
            format!("sum({})", series.metric_name),
            series.labels.clone(),
        );
        if let Some(last) = series.points.last() {
            result.add_point(last.timestamp, sum);
        }
        Ok(result)
    }

    fn avg(&self, series: &TimeSeries) -> Result<TimeSeries> {
        let avg = if series.points.is_empty() {
            0.0
        } else {
            series.points.iter().map(|p| p.value).sum::<f64>() / series.points.len() as f64
        };
        let mut result = TimeSeries::new(
            format!("avg({})", series.metric_name),
            series.labels.clone(),
        );
        if let Some(last) = series.points.last() {
            result.add_point(last.timestamp, avg);
        }
        Ok(result)
    }

    fn max(&self, series: &TimeSeries) -> Result<TimeSeries> {
        let max = series
            .points
            .iter()
            .map(|p| p.value)
            .fold(f64::NEG_INFINITY, f64::max);
        let mut result = TimeSeries::new(
            format!("max({})", series.metric_name),
            series.labels.clone(),
        );
        if let Some(last) = series.points.last() {
            result.add_point(last.timestamp, max);
        }
        Ok(result)
    }

    fn min(&self, series: &TimeSeries) -> Result<TimeSeries> {
        let min = series
            .points
            .iter()
            .map(|p| p.value)
            .fold(f64::INFINITY, f64::min);
        let mut result = TimeSeries::new(
            format!("min({})", series.metric_name),
            series.labels.clone(),
        );
        if let Some(last) = series.points.last() {
            result.add_point(last.timestamp, min);
        }
        Ok(result)
    }

    fn count(&self, series: &TimeSeries) -> Result<TimeSeries> {
        let count = series.points.len() as f64;
        let mut result = TimeSeries::new(
            format!("count({})", series.metric_name),
            series.labels.clone(),
        );
        if let Some(last) = series.points.last() {
            result.add_point(last.timestamp, count);
        }
        Ok(result)
    }

    fn quantile(&self, series: &TimeSeries, q: f64) -> Result<TimeSeries> {
        let mut values: Vec<f64> = series.points.iter().map(|p| p.value).collect();
        values.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));

        let quantile = if values.is_empty() {
            0.0
        } else {
            let idx = ((values.len() - 1) as f64 * q) as usize;
            values[idx]
        };

        let mut result = TimeSeries::new(
            format!("quantile({}, {})", q, series.metric_name),
            series.labels.clone(),
        );
        if let Some(last) = series.points.last() {
            result.add_point(last.timestamp, quantile);
        }
        Ok(result)
    }

    pub fn aggregate_by_labels(
        &self,
        series_list: &[TimeSeries],
        function: AggregationFunction,
    ) -> Result<Vec<TimeSeries>> {
        let mut groups: BTreeMap<String, Vec<&TimeSeries>> = BTreeMap::new();

        for series in series_list {
            let key = self.group_key(&series.labels);
            groups.entry(key).or_default().push(series);
        }

        let mut results = Vec::new();
        for (_, group) in groups {
            if let Some(first) = group.first() {
                let mut merged = TimeSeries::new(
                    first.metric_name.clone(),
                    first.labels.clone(),
                );

                for s in group {
                    for point in &s.points {
                        merged.points.push(point.clone());
                    }
                }

                let aggregated = self.execute_aggregation(&merged, function.clone(), None)?;
                results.push(aggregated);
            }
        }

        Ok(results)
    }

    fn group_key(&self, labels: &Labels) -> String {
        let mut parts: Vec<String> = labels
            .0
            .iter()
            .map(|l| format!("{}={}", l.name, l.value))
            .collect();
        parts.sort();
        parts.join(",")
    }
}

impl Default for QueryExecutor {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::Utc;
    use common::metrics::Label;

    #[test]
    fn test_sum_aggregation() {
        let executor = QueryExecutor::new();
        let mut series = TimeSeries::new("test".to_string(), Labels::new());
        series.add_point(Utc::now(), 10.0);
        series.add_point(Utc::now(), 20.0);
        series.add_point(Utc::now(), 30.0);

        let result = executor.sum(&series).unwrap();
        assert_eq!(result.points.last().unwrap().value, 60.0);
    }

    #[test]
    fn test_avg_aggregation() {
        let executor = QueryExecutor::new();
        let mut series = TimeSeries::new("test".to_string(), Labels::new());
        series.add_point(Utc::now(), 10.0);
        series.add_point(Utc::now(), 20.0);
        series.add_point(Utc::now(), 30.0);

        let result = executor.avg(&series).unwrap();
        assert_eq!(result.points.last().unwrap().value, 20.0);
    }
}
