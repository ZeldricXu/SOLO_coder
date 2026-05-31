use crate::models::StreamSQLError;
use bytes::Bytes;

pub trait EventSerializer {
    type Output;
    fn serialize<T: serde::Serialize>(&self, event: &T) -> Result<Self::Output, StreamSQLError>;
    fn deserialize<T: for<'de> serde::Deserialize<'de>>(&self, data: &Self::Output) -> Result<T, StreamSQLError>;
}

#[derive(Debug, Clone, Default)]
pub struct JsonSerializer;

impl EventSerializer for JsonSerializer {
    type Output = String;

    fn serialize<T: serde::Serialize>(&self, event: &T) -> Result<Self::Output, StreamSQLError> {
        serde_json::to_string(event).map_err(StreamSQLError::from)
    }

    fn deserialize<T: for<'de> serde::Deserialize<'de>>(&self, data: &Self::Output) -> Result<T, StreamSQLError> {
        serde_json::from_str(data).map_err(StreamSQLError::from)
    }
}

#[derive(Debug, Clone, Default)]
pub struct SimdJsonSerializer;

impl EventSerializer for SimdJsonSerializer {
    type Output = String;

    fn serialize<T: serde::Serialize>(&self, event: &T) -> Result<Self::Output, StreamSQLError> {
        simd_json::to_string(event).map_err(|e| StreamSQLError::Serialization(e.to_string()))
    }

    fn deserialize<T: for<'de> serde::Deserialize<'de>>(&self, data: &Self::Output) -> Result<T, StreamSQLError> {
        let mut bytes = data.as_bytes().to_vec();
        simd_json::from_slice(&mut bytes).map_err(|e| StreamSQLError::Serialization(e.to_string()))
    }
}

pub fn to_json_bytes<T: serde::Serialize>(value: &T) -> Result<Bytes, StreamSQLError> {
    let json = serde_json::to_vec(value)?;
    Ok(Bytes::from(json))
}

pub fn from_json_bytes<T: for<'de> serde::Deserialize<'de>>(bytes: &Bytes) -> Result<T, StreamSQLError> {
    serde_json::from_slice(bytes).map_err(StreamSQLError::from)
}

pub fn pretty_print<T: serde::Serialize>(value: &T) -> Result<String, StreamSQLError> {
    serde_json::to_string_pretty(value).map_err(StreamSQLError::from)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::{Deserialize, Serialize};

    #[derive(Debug, Serialize, Deserialize, PartialEq)]
    struct TestEvent {
        id: String,
        name: String,
        value: i64,
    }

    #[test]
    fn test_json_serializer() {
        let serializer = JsonSerializer;
        let event = TestEvent {
            id: "1".to_string(),
            name: "test".to_string(),
            value: 42,
        };

        let serialized = serializer.serialize(&event).unwrap();
        let deserialized: TestEvent = serializer.deserialize(&serialized).unwrap();

        assert_eq!(event, deserialized);
    }
}
