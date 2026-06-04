use anyhow::{bail, Result};
use chrono::Duration;
use regex::Regex;
use std::collections::HashMap;

#[derive(Debug, Clone)]
pub enum Aggregation {
    Rate,
    Sum,
    Avg,
    Max,
    Min,
    Count,
    Quantile(f64),
}

#[derive(Debug, Clone)]
pub struct LabelMatcher {
    pub name: String,
    pub value: String,
    pub operator: MatchOperator,
}

#[derive(Debug, Clone, PartialEq)]
pub enum MatchOperator {
    Equal,
    NotEqual,
    RegexMatch,
    RegexNotMatch,
}

#[derive(Debug, Clone)]
pub struct Query {
    pub metric_name: String,
    pub aggregations: Vec<Aggregation>,
    pub label_matchers: Vec<LabelMatcher>,
    pub range_duration: Option<Duration>,
    pub step: Option<Duration>,
}

pub struct QueryParser {
    agg_regex: Regex,
    label_regex: Regex,
    duration_regex: Regex,
}

impl QueryParser {
    pub fn new() -> Self {
        Self {
            agg_regex: Regex::new(r"^(rate|sum|avg|max|min|count|quantile\([0-9.]+\))\(").unwrap(),
            label_regex: Regex::new(r#"([a-zA-Z_][a-zA-Z0-9_]*)\s*(=~|!~|!=|=)\s*"([^"]*)""#).unwrap(),
            duration_regex: Regex::new(r"\[(\d+)([smhdwy])\]").unwrap(),
        }
    }

    pub fn parse(&self, query_str: &str) -> Result<Query> {
        let query_str = query_str.trim();
        let mut remaining = query_str;

        let mut aggregations = Vec::new();
        while let Some(captures) = self.agg_regex.captures(remaining) {
            let agg_str = captures.get(1).unwrap().as_str();
            let agg = self.parse_aggregation(agg_str)?;
            aggregations.push(agg);
            remaining = &remaining[captures.get(0).unwrap().end()..];
        }

        let close_parens = aggregations.len();
        for _ in 0..close_parens {
            if remaining.ends_with(')') {
                remaining = &remaining[..remaining.len() - 1];
            }
        }

        let metric_end = remaining.find('{').unwrap_or(remaining.find('[').unwrap_or(remaining.len()));
        let metric_name = remaining[..metric_end].trim().to_string();
        remaining = &remaining[metric_end..];

        let mut label_matchers = Vec::new();
        if remaining.starts_with('{') {
            if let Some(end) = remaining.find('}') {
                let labels_str = &remaining[1..end];
                for caps in self.label_regex.captures_iter(labels_str) {
                    let name = caps.get(1).unwrap().as_str().to_string();
                    let op_str = caps.get(2).unwrap().as_str();
                    let value = caps.get(3).unwrap().as_str().to_string();

                    let operator = match op_str {
                        "=" => MatchOperator::Equal,
                        "!=" => MatchOperator::NotEqual,
                        "=~" => MatchOperator::RegexMatch,
                        "!~" => MatchOperator::RegexNotMatch,
                        _ => bail!("Unknown operator: {}", op_str),
                    };

                    label_matchers.push(LabelMatcher {
                        name,
                        value,
                        operator,
                    });
                }
                remaining = &remaining[end + 1..];
            }
        }

        let mut range_duration = None;
        if let Some(captures) = self.duration_regex.captures(remaining) {
            let num: i64 = captures.get(1).unwrap().as_str().parse()?;
            let unit = captures.get(2).unwrap().as_str();
            range_duration = Some(self.parse_duration(num, unit)?);
        }

        Ok(Query {
            metric_name,
            aggregations,
            label_matchers,
            range_duration,
            step: None,
        })
    }

    fn parse_aggregation(&self, agg_str: &str) -> Result<Aggregation> {
        if agg_str.starts_with("quantile") {
            let start = agg_str.find('(').unwrap();
            let end = agg_str.find(')').unwrap();
            let q: f64 = agg_str[start + 1..end].parse()?;
            Ok(Aggregation::Quantile(q))
        } else {
            match agg_str {
                "rate" => Ok(Aggregation::Rate),
                "sum" => Ok(Aggregation::Sum),
                "avg" => Ok(Aggregation::Avg),
                "max" => Ok(Aggregation::Max),
                "min" => Ok(Aggregation::Min),
                "count" => Ok(Aggregation::Count),
                _ => bail!("Unknown aggregation: {}", agg_str),
            }
        }
    }

    fn parse_duration(&self, num: i64, unit: &str) -> Result<Duration> {
        match unit {
            "s" => Ok(Duration::seconds(num)),
            "m" => Ok(Duration::minutes(num)),
            "h" => Ok(Duration::hours(num)),
            "d" => Ok(Duration::days(num)),
            "w" => Ok(Duration::weeks(num)),
            "y" => Ok(Duration::days(num * 365)),
            _ => bail!("Unknown duration unit: {}", unit),
        }
    }
}

impl Default for QueryParser {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_simple_query() {
        let parser = QueryParser::new();
        let query = parser.parse("http_requests_total").unwrap();
        assert_eq!(query.metric_name, "http_requests_total");
        assert!(query.aggregations.is_empty());
        assert!(query.label_matchers.is_empty());
    }

    #[test]
    fn test_query_with_labels() {
        let parser = QueryParser::new();
        let query = parser.parse(r#"http_requests_total{status="200", method="GET"}"#).unwrap();
        assert_eq!(query.metric_name, "http_requests_total");
        assert_eq!(query.label_matchers.len(), 2);
        assert_eq!(query.label_matchers[0].name, "status");
        assert_eq!(query.label_matchers[0].value, "200");
    }

    #[test]
    fn test_query_with_aggregation() {
        let parser = QueryParser::new();
        let query = parser.parse("rate(http_requests_total[5m])").unwrap();
        assert_eq!(query.metric_name, "http_requests_total");
        assert_eq!(query.aggregations.len(), 1);
        assert!(matches!(query.aggregations[0], Aggregation::Rate));
        assert_eq!(query.range_duration, Some(Duration::minutes(5)));
    }

    #[test]
    fn test_query_with_regex() {
        let parser = QueryParser::new();
        let query = parser.parse(r#"http_requests_total{status=~"5.."}"#).unwrap();
        assert_eq!(query.label_matchers[0].operator, MatchOperator::RegexMatch);
        assert_eq!(query.label_matchers[0].value, "5..");
    }
}
