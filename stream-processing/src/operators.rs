use anyhow::Result;
use async_trait::async_trait;

use crate::pipeline::StreamEvent;

#[async_trait]
pub trait Operator {
    async fn apply(&self, event: StreamEvent) -> Result<Vec<StreamEvent>>;
    fn name(&self) -> &str;
}

pub struct FilterOperator<F>
where
    F: Fn(&StreamEvent) -> bool + Send + Sync,
{
    name: String,
    predicate: F,
}

impl<F> FilterOperator<F>
where
    F: Fn(&StreamEvent) -> bool + Send + Sync,
{
    pub fn new(predicate: F) -> Self {
        Self {
            name: "Filter".to_string(),
            predicate,
        }
    }

    pub fn with_name(mut self, name: String) -> Self {
        self.name = name;
        self
    }
}

#[async_trait]
impl<F> Operator for FilterOperator<F>
where
    F: Fn(&StreamEvent) -> bool + Send + Sync,
{
    async fn apply(&self, event: StreamEvent) -> Result<Vec<StreamEvent>> {
        if (self.predicate)(&event) {
            Ok(vec![event])
        } else {
            Ok(Vec::new())
        }
    }

    fn name(&self) -> &str {
        &self.name
    }
}

pub struct MapOperator<F>
where
    F: Fn(StreamEvent) -> Vec<StreamEvent> + Send + Sync,
{
    name: String,
    mapper: F,
}

impl<F> MapOperator<F>
where
    F: Fn(StreamEvent) -> Vec<StreamEvent> + Send + Sync,
{
    pub fn new(mapper: F) -> Self {
        Self {
            name: "Map".to_string(),
            mapper,
        }
    }

    pub fn with_name(mut self, name: String) -> Self {
        self.name = name;
        self
    }
}

#[async_trait]
impl<F> Operator for MapOperator<F>
where
    F: Fn(StreamEvent) -> Vec<StreamEvent> + Send + Sync,
{
    async fn apply(&self, event: StreamEvent) -> Result<Vec<StreamEvent>> {
        Ok((self.mapper)(event))
    }

    fn name(&self) -> &str {
        &self.name
    }
}

pub struct FlatMapOperator<F>
where
    F: Fn(StreamEvent) -> Vec<StreamEvent> + Send + Sync,
{
    name: String,
    mapper: F,
}

impl<F> FlatMapOperator<F>
where
    F: Fn(StreamEvent) -> Vec<StreamEvent> + Send + Sync,
{
    pub fn new(mapper: F) -> Self {
        Self {
            name: "FlatMap".to_string(),
            mapper,
        }
    }
}

#[async_trait]
impl<F> Operator for FlatMapOperator<F>
where
    F: Fn(StreamEvent) -> Vec<StreamEvent> + Send + Sync,
{
    async fn apply(&self, event: StreamEvent) -> Result<Vec<StreamEvent>> {
        Ok((self.mapper)(event))
    }

    fn name(&self) -> &str {
        &self.name
    }
}

pub struct KeyByOperator<F, K>
where
    F: Fn(&StreamEvent) -> K + Send + Sync,
    K: std::hash::Hash + Eq + Send + Sync,
{
    name: String,
    key_extractor: F,
}

impl<F, K> KeyByOperator<F, K>
where
    F: Fn(&StreamEvent) -> K + Send + Sync,
    K: std::hash::Hash + Eq + Send + Sync,
{
    pub fn new(key_extractor: F) -> Self {
        Self {
            name: "KeyBy".to_string(),
            key_extractor,
        }
    }
}

#[async_trait]
impl<F, K> Operator for KeyByOperator<F, K>
where
    F: Fn(&StreamEvent) -> K + Send + Sync,
    K: std::hash::Hash + Eq + Send + Sync,
{
    async fn apply(&self, event: StreamEvent) -> Result<Vec<StreamEvent>> {
        let _key = (self.key_extractor)(&event);
        Ok(vec![event])
    }

    fn name(&self) -> &str {
        &self.name
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::log::{LogEvent, LogLevel};

    #[tokio::test]
    async fn test_filter_operator() {
        let filter = FilterOperator::new(|event| {
            matches!(event, StreamEvent::Log(_))
        });

        let log_event = LogEvent::new(
            "host".to_string(),
            "service".to_string(),
            LogLevel::Info,
            "test".to_string(),
            "test.log".to_string(),
        );

        let result = filter.apply(StreamEvent::Log(log_event)).await.unwrap();
        assert_eq!(result.len(), 1);
    }

    #[tokio::test]
    async fn test_map_operator() {
        let mapper = MapOperator::new(|event| {
            vec![event.clone(), event]
        });

        let log_event = LogEvent::new(
            "host".to_string(),
            "service".to_string(),
            LogLevel::Info,
            "test".to_string(),
            "test.log".to_string(),
        );

        let result = mapper.apply(StreamEvent::Log(log_event)).await.unwrap();
        assert_eq!(result.len(), 2);
    }
}
