use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiResponse<T> {
    pub code: u16,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<T>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

impl<T> ApiResponse<T> {
    pub fn success(data: T) -> Self {
        Self {
            code: 200,
            data: Some(data),
            message: None,
        }
    }

    pub fn created(data: T) -> Self {
        Self {
            code: 201,
            data: Some(data),
            message: None,
        }
    }

    pub fn error(code: u16, message: impl Into<String>) -> Self {
        Self {
            code,
            data: None,
            message: Some(message.into()),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceResponse {
    pub id: String,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub resource_type: Option<String>,
}

impl ResourceResponse {
    pub fn new(status: impl Into<String>) -> Self {
        Self {
            id: format!("rsc_{}", Uuid::new_v4().simple()),
            status: status.into(),
            resource_type: None,
        }
    }

    pub fn with_id(id: impl Into<String>, status: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            status: status.into(),
            resource_type: None,
        }
    }

    pub fn with_type(mut self, resource_type: impl Into<String>) -> Self {
        self.resource_type = Some(resource_type.into());
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StatusResponse {
    pub id: String,
    pub status: String,
    pub progress: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub phase: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error_detail: Option<String>,
}

impl StatusResponse {
    pub fn new(id: impl Into<String>, status: impl Into<String>, progress: f64) -> Self {
        Self {
            id: id.into(),
            status: status.into(),
            progress: progress.clamp(0.0, 1.0),
            phase: None,
            error_detail: None,
        }
    }

    pub fn with_phase(mut self, phase: impl Into<String>) -> Self {
        self.phase = Some(phase.into());
        self
    }

    pub fn with_error(mut self, error: impl Into<String>) -> Self {
        self.error_detail = Some(error.into());
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchOperation {
    pub action: String,
    pub id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub params: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchResult {
    pub id: String,
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<serde_json::Value>,
}

impl BatchResult {
    pub fn success(id: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            success: true,
            message: None,
            result: None,
        }
    }

    pub fn failure(id: impl Into<String>, message: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            success: false,
            message: Some(message.into()),
            result: None,
        }
    }

    pub fn with_result(mut self, result: serde_json::Value) -> Self {
        self.result = Some(result);
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchResponse {
    pub batch_id: String,
    pub results: Vec<BatchResult>,
}

impl BatchResponse {
    pub fn new(results: Vec<BatchResult>) -> Self {
        Self {
            batch_id: format!("batch_{}", Uuid::new_v4().simple()),
            results,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct ResourceCreateRequest {
    #[serde(rename = "type")]
    pub resource_type: String,
    pub config: serde_json::Value,
    #[serde(default)]
    pub labels: std::collections::HashMap<String, String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct BatchOperationRequest {
    pub operations: Vec<BatchOperation>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn test_api_response() {
        let response = ApiResponse::success(json!({"key": "value"}));
        assert_eq!(response.code, 200);
        assert!(response.data.is_some());
        
        let error = ApiResponse::<()>::error(404, "not found");
        assert_eq!(error.code, 404);
        assert_eq!(error.message, Some("not found".to_string()));
    }

    #[test]
    fn test_resource_response() {
        let resp = ResourceResponse::new("provisioning");
        assert!(resp.id.starts_with("rsc_"));
        assert_eq!(resp.status, "provisioning");
    }

    #[test]
    fn test_status_response() {
        let resp = StatusResponse::new("ent_001", "running", 0.8)
            .with_phase("processing")
            .with_error("none");
        
        assert_eq!(resp.id, "ent_001");
        assert_eq!(resp.progress, 0.8);
        assert_eq!(resp.phase, Some("processing".to_string()));
    }

    #[test]
    fn test_batch_response() {
        let results = vec![
            BatchResult::success("id1"),
            BatchResult::failure("id2", "error"),
        ];
        let batch = BatchResponse::new(results);
        
        assert!(batch.batch_id.starts_with("batch_"));
        assert_eq!(batch.results.len(), 2);
        assert!(batch.results[0].success);
        assert!(!batch.results[1].success);
    }

    #[test]
    fn test_progress_clamping() {
        let resp = StatusResponse::new("id", "status", 1.5);
        assert_eq!(resp.progress, 1.0);
        
        let resp = StatusResponse::new("id", "status", -0.5);
        assert_eq!(resp.progress, 0.0);
    }
}
