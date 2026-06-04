use anyhow::Result;
use chrono::{DateTime, Duration, Utc};
use regex::Regex;
use std::collections::HashMap;
use std::sync::Arc;

use common::metrics::{Labels, MetricPoint, TimeSeries};

use crate::parser::{Aggregation, MatchOperator, Query};

pub struct QueryExecutor {
    store_client: Arc<StoreClient>,
}

pub struct StoreClient {
    base_url: String,
}

impl StoreClient {
    pub fn new(base_url: String) -> Self {
        Self { base_url }
    }

    pub async fn query(
        &self,
        metric: &str,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> Result<Vec<TimeSeries>> {
        let url = format!(
            "{}/api/v1/query?metric={}&start={}&end={}",
            self.base_url,
            metric,
            start.timestamp(),
            end.timestamp()
        );

        let response = reqwest::get(&url).await?;
        let series: Vec<TimeSeries> = response.json().await?;
        Ok(series)
    }
}

impl QueryExecutor {
    pub fn new(store_client: Arc<StoreClient>) -> Self {
        Self { store_client }
    }

    pub async fn execute(&self, query: Query) -> Result<QueryResult> {
        let end = Utc::now();
        let start = end - query.range_duration.unwrap_or(Duration::hours(1));

        let mut series_list = self
            .store_client
            .query(&query.metric_name, start, end)
            .await?;

        series_list = self.apply_label_filters(series_list, &query.label_matchers)?;

        for agg in &query.aggregations {
            series_list = self.apply_aggregation(series_list, agg)?;
        }

        Ok(QueryResult {
            data: series_list.clone(),
            stats: QueryStats {
                series_returned: series_list.len(),
                execution_time_ms: 0,
            },
        })
    }

    fn apply_label_filters(
        &self,
        series_list: Vec<TimeSeries>,
        matchers: &[crate::parser::LabelMatcher],
    ) -> Result<Vec<TimeSeries>> {
        let mut result = Vec::new();

        for series in series_list {
            let mut matches = true;

            for matcher in matchers {
                let label_value = series.labels.get(&matcher.name);
                let is_match = match (matcher.operator.clone(), label_value) {
                    (MatchOperator::Equal, Some(v)) => v == matcher.value,
                    (MatchOperator::NotEqual, Some(v)) => v != matcher.value,
                    (MatchOperator::RegexMatch, Some(v)) => {
                        Regex::new(&matcher.value)?.is_match(v)
                    }
                    (MatchOperator::RegexNotMatch, Some(v)) => {
                        !Regex::new(&matcher.value)?.is_match(v)
                    }
                    (_, None) => false,
                };

                if !is_match {
                    matches = false;
                    break;
                }
            }

            if matches {
                result.push(series);
            }
        }

        Ok(result)
    }

    fn apply_aggregation(&self, series_list: Vec<TimeSeries>, agg: &Aggregation) -> Result<Vec<TimeSeries>> {
        match agg {
            Aggregation::Rate => self.rate_aggregation(series_list),
            Aggregation::Sum => self.sum_aggregation(series_list),
            Aggregation::Avg => self.avg_aggregation(series_list),
            Aggregation::Max => self.max_aggregation(series_list),
            Aggregation::Min => self.min_aggregation(series_list),
            Aggregation::Count => self.count_aggregation(series_list),
            Aggregation::Quantile(q) => self.quantile_aggregation(series_list, *q),
        }
    }

    fn rate_aggregation(&self, series_list: Vec<TimeSeries>) -> Result<Vec<TimeSeries>> {
        let mut results = Vec::new();
        for series in series_list {
            let mut rate_series =
                TimeSeries::new(format!("rate({})", series.metric_name), series.labels);

            if series.points.len() >= 2 {
                for i in 1..series.points.len() {
                    let dt = (series.points[i].timestamp - series.points[i - 1].timestamp)
                        .num_seconds() as f64;
                    if dt > 0.0 {
                        let rate = (series.points[i].value - series.points[i - 1].value) / dt;
                        rate_series.add_point(series.points[i].timestamp, rate);
                    }
                }
            }

            results.push(rate_series);
        }
        Ok(results)
    }

    fn sum_aggregation(&self, series_list: Vec<TimeSeries>) -> Result<Vec<TimeSeries>> {
        let mut groups: HashMap<String, Vec<TimeSeries>> = HashMap::new();

        for series in series_list {
            let key = series.metric_name.clone();
            groups.entry(key).or_default().push(series);
        }

        let mut results = Vec::new();
        for (name, group) in groups {
            let mut merged = TimeSeries::new(format!("sum({})", name), Labels::new());

            let mut all_points: Vec<MetricPoint> =
                group.iter().flat_map(|s| s.points.clone()).collect();
            all_points.sort_by(|a, b| a.timestamp.cmp(&b.timestamp));

            let sum: f64 = all_points.iter().map(|p| p.value).sum();
            if let Some(last) = all_points.last() {
                merged.add_point(last.timestamp, sum);
            }

            results.push(merged);
        }

        Ok(results)
    }

    fn avg_aggregation(&self, series_list: Vec<TimeSeries>) -> Result<Vec<TimeSeries>> {
        let mut results = Vec::new();

        for series in series_list {
            let mut avg_series =
                TimeSeries::new(format!("avg({})", series.metric_name), series.labels);

            if !series.points.is_empty() {
                let avg: f64 = series.points.iter().map(|p| p.value).sum::<f64>() / series.points.len() as f64;
                if let Some(last) = series.points.last() {
                    avg_series.add_point(last.timestamp, avg);
                }
            }

            results.push(avg_series);
        }

        Ok(results)
    }

    fn max_aggregation(&self, series_list: Vec<TimeSeries>) -> Result<Vec<TimeSeries>> {
        let mut results = Vec::new();

        for series in series_list {
            let mut max_series =
                TimeSeries::new(format!("max({})", series.metric_name), series.labels);

            if !series.points.is_empty() {
                let max = series.points.iter().map(|p| p.value).fold(f64::NEG_INFINITY, f64::max);
                if let Some(last) = series.points.last() {
                    max_series.add_point(last.timestamp, max);
                }
            }

            results.push(max_series);
        }

        Ok(results)
    }

    fn min_aggregation(&self, series_list: Vec<TimeSeries>) -> Result<Vec<TimeSeries>> {
        let mut results = Vec::new();

        for series in series_list {
            let mut min_series =
                TimeSeries::new(format!("min({})", series.metric_name), series.labels);

            if !series.points.is_empty() {
                let min = series.points.iter().map(|p| p.value).fold(f64::INFINITY, f64::min);
                if let Some(last) = series.points.last() {
                    min_series.add_point(last.timestamp, min);
                }
            }

            results.push(min_series);
        }

        Ok(results)
    }

    fn count_aggregation(&self, series_list: Vec<TimeSeries>) -> Result<Vec<TimeSeries>> {
        let mut results = Vec::new();

        for series in series_list {
            let mut count_series =
                TimeSeries::new(format!("count({})", series.metric_name), series.labels);

            if !series.points.is_empty() {
                if let Some(last) = series.points.last() {
                    count_series.add_point(last.timestamp, series.points.len() as f64);
                }
            }

            results.push(count_series);
        }

        Ok(results)
    }

    fn quantile_aggregation(&self, series_list: Vec<TimeSeries>, q: f64) -> Result<Vec<TimeSeries>> {
        let mut results = Vec::new();

        for series in series_list {
            let mut q_series =
                TimeSeries::new(format!("quantile({}, {})", q, series.metric_name), series.labels);

            if !series.points.is_empty() {
                let mut values: Vec<f64> = series.points.iter().map(|p| p.value).collect();
                values.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));

                let idx = ((values.len() - 1) as f64 * q) as usize;
                if let Some(last) = series.points.last() {
                    q_series.add_point(last.timestamp, values[idx]);
                }
            }

            results.push(q_series);
        }

        Ok(results)
    }
}

#[derive(Debug, serde::Serialize)]
pub struct QueryResult {
    pub data: Vec<TimeSeries>,
    pub stats: QueryStats,
}

#[derive(Debug, serde::Serialize)]
pub struct QueryStats {
    pub series_returned: usize,
    pub execution_time_ms: u64,
}
