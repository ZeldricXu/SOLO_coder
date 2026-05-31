use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;
use std::sync::{Arc, Mutex};
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum RequestType {
    Create,
    Read,
    Update,
    Delete,
    Query,
    Execute,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum ResponseStatus {
    Success,
    Failed,
    Partial,
    Pending,
    ValidationError,
    Unauthorized,
    NotFound,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Request {
    pub id: String,
    pub request_type: RequestType,
    pub resource_type: String,
    pub resource_id: Option<String>,
    pub payload: serde_json::Value,
    pub headers: HashMap<String, String>,
    pub query_params: HashMap<String, String>,
    pub created_by: String,
    pub created_at: DateTime<Utc>,
    pub trace_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Response {
    pub id: String,
    pub request_id: String,
    pub status: ResponseStatus,
    pub data: Option<serde_json::Value>,
    pub errors: Vec<String>,
    pub warnings: Vec<String>,
    pub metadata: HashMap<String, String>,
    pub processed_at: DateTime<Utc>,
    pub duration_ms: u64,
    pub trace_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Resource {
    pub id: String,
    pub resource_type: String,
    pub config: serde_json::Value,
    pub labels: HashMap<String, String>,
    pub status: String,
    pub progress: f32,
    pub created_by: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchOperation {
    pub id: String,
    pub operations: Vec<BatchAction>,
    pub status: ResponseStatus,
    pub results: Vec<BatchResult>,
    pub created_by: String,
    pub created_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchAction {
    pub action: String,
    pub id: Option<String>,
    pub params: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchResult {
    pub action_index: usize,
    pub success: bool,
    pub id: Option<String>,
    pub message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ValidationError {
    pub field: String,
    pub message: String,
    pub code: String,
}

#[derive(Debug, Clone)]
pub struct CoreProcessor {
    resources: Arc<Mutex<HashMap<String, Resource>>>,
    requests: Arc<Mutex<HashMap<String, Request>>>,
    responses: Arc<Mutex<HashMap<String, Response>>>,
    batch_operations: Arc<Mutex<HashMap<String, BatchOperation>>>,
}

impl CoreProcessor {
    pub fn new() -> Self {
        Self {
            resources: Arc::new(Mutex::new(HashMap::new())),
            requests: Arc::new(Mutex::new(HashMap::new())),
            responses: Arc::new(Mutex::new(HashMap::new())),
            batch_operations: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    pub fn create_request(
        &self,
        request_type: RequestType,
        resource_type: &str,
        payload: serde_json::Value,
        created_by: &str,
    ) -> Request {
        let id = Uuid::new_v4().to_string();
        let trace_id = Uuid::new_v4().to_string();
        let now = Utc::now();

        let request = Request {
            id: id.clone(),
            request_type,
            resource_type: resource_type.to_string(),
            resource_id: None,
            payload,
            headers: HashMap::new(),
            query_params: HashMap::new(),
            created_by: created_by.to_string(),
            created_at: now,
            trace_id,
        };

        let mut requests = self.requests.lock().unwrap();
        requests.insert(id, request.clone());
        request
    }

    pub fn validate_request(&self, request: &Request) -> Result<(), Vec<ValidationError>> {
        let mut errors = Vec::new();

        if request.resource_type.is_empty() {
            errors.push(ValidationError {
                field: "resource_type".to_string(),
                message: "Resource type is required".to_string(),
                code: "REQUIRED".to_string(),
            });
        }

        if request.created_by.is_empty() {
            errors.push(ValidationError {
                field: "created_by".to_string(),
                message: "Creator is required".to_string(),
                code: "REQUIRED".to_string(),
            });
        }

        if request.payload.is_null() {
            errors.push(ValidationError {
                field: "payload".to_string(),
                message: "Payload cannot be null".to_string(),
                code: "NULL_VALUE".to_string(),
            });
        }

        if errors.is_empty() {
            Ok(())
        } else {
            Err(errors)
        }
    }

    pub fn process_request(&self, request: Request) -> Response {
        let start_time = std::time::Instant::now();
        let response_id = Uuid::new_v4().to_string();

        let validation = self.validate_request(&request);
        if let Err(errors) = validation {
            return Response {
                id: response_id,
                request_id: request.id,
                status: ResponseStatus::ValidationError,
                data: None,
                errors: errors.into_iter().map(|e| format!("{}: {}", e.field, e.message)).collect(),
                warnings: Vec::new(),
                metadata: HashMap::new(),
                processed_at: Utc::now(),
                duration_ms: start_time.elapsed().as_millis() as u64,
                trace_id: request.trace_id,
            };
        }

        let mut metadata = HashMap::new();
        metadata.insert("trace_id".to_string(), request.trace_id.clone());
        metadata.insert("processed_by".to_string(), "core-processor".to_string());

        let response = match request.request_type {
            RequestType::Create => self.handle_create(request, &response_id, start_time),
            RequestType::Read => self.handle_read(request, &response_id, start_time),
            RequestType::Update => self.handle_update(request, &response_id, start_time),
            RequestType::Delete => self.handle_delete(request, &response_id, start_time),
            RequestType::Query => self.handle_query(request, &response_id, start_time),
            RequestType::Execute => self.handle_execute(request, &response_id, start_time),
        };

        let mut responses = self.responses.lock().unwrap();
        responses.insert(response_id, response.clone());
        response
    }

    fn handle_create(&self, request: Request, response_id: &str, start_time: std::time::Instant) -> Response {
        let config = request.payload.clone();
        let labels = request.payload.get("labels")
            .and_then(|l| l.as_object())
            .map(|o| o.iter().map(|(k, v)| (k.clone(), v.as_str().unwrap_or("").to_string())).collect())
            .unwrap_or_default();

        let resource_id = Uuid::new_v4().to_string();
        let now = Utc::now();

        let resource = Resource {
            id: resource_id.clone(),
            resource_type: request.resource_type.clone(),
            config,
            labels,
            status: "provisioning".to_string(),
            progress: 0.0,
            created_by: request.created_by.clone(),
            created_at: now,
            updated_at: now,
        };

        let mut resources = self.resources.lock().unwrap();
        resources.insert(resource_id.clone(), resource);

        let mut data = HashMap::new();
        data.insert("id".to_string(), serde_json::Value::String(resource_id));
        data.insert("status".to_string(), serde_json::Value::String("provisioning".to_string()));

        Response {
            id: response_id.to_string(),
            request_id: request.id,
            status: ResponseStatus::Success,
            data: Some(serde_json::Value::Object(serde_json::Map::from_iter(data))),
            errors: Vec::new(),
            warnings: Vec::new(),
            metadata: HashMap::new(),
            processed_at: Utc::now(),
            duration_ms: start_time.elapsed().as_millis() as u64,
            trace_id: request.trace_id,
        }
    }

    fn handle_read(&self, request: Request, response_id: &str, start_time: std::time::Instant) -> Response {
        let resources = self.resources.lock().unwrap();

        if let Some(resource_id) = &request.resource_id {
            if let Some(resource) = resources.get(resource_id) {
                return Response {
                    id: response_id.to_string(),
                    request_id: request.id,
                    status: ResponseStatus::Success,
                    data: Some(serde_json::to_value(resource).unwrap_or_default()),
                    errors: Vec::new(),
                    warnings: Vec::new(),
                    metadata: HashMap::new(),
                    processed_at: Utc::now(),
                    duration_ms: start_time.elapsed().as_millis() as u64,
                    trace_id: request.trace_id,
                };
            }
        }

        Response {
            id: response_id.to_string(),
            request_id: request.id,
            status: ResponseStatus::NotFound,
            data: None,
            errors: vec!["Resource not found".to_string()],
            warnings: Vec::new(),
            metadata: HashMap::new(),
            processed_at: Utc::now(),
            duration_ms: start_time.elapsed().as_millis() as u64,
            trace_id: request.trace_id,
        }
    }

    fn handle_update(&self, request: Request, response_id: &str, start_time: std::time::Instant) -> Response {
        let mut resources = self.resources.lock().unwrap();

        if let Some(resource_id) = &request.resource_id {
            if let Some(resource) = resources.get_mut(resource_id) {
                resource.config = request.payload.clone();
                resource.updated_at = Utc::now();
                resource.status = "updating".to_string();

                return Response {
                    id: response_id.to_string(),
                    request_id: request.id,
                    status: ResponseStatus::Success,
                    data: Some(serde_json::to_value(resource).unwrap_or_default()),
                    errors: Vec::new(),
                    warnings: Vec::new(),
                    metadata: HashMap::new(),
                    processed_at: Utc::now(),
                    duration_ms: start_time.elapsed().as_millis() as u64,
                    trace_id: request.trace_id,
                };
            }
        }

        Response {
            id: response_id.to_string(),
            request_id: request.id,
            status: ResponseStatus::NotFound,
            data: None,
            errors: vec!["Resource not found".to_string()],
            warnings: Vec::new(),
            metadata: HashMap::new(),
            processed_at: Utc::now(),
            duration_ms: start_time.elapsed().as_millis() as u64,
            trace_id: request.trace_id,
        }
    }

    fn handle_delete(&self, request: Request, response_id: &str, start_time: std::time::Instant) -> Response {
        let mut resources = self.resources.lock().unwrap();

        if let Some(resource_id) = &request.resource_id {
            if resources.remove(resource_id).is_some() {
                return Response {
                    id: response_id.to_string(),
                    request_id: request.id,
                    status: ResponseStatus::Success,
                    data: Some(serde_json::json!({ "deleted": resource_id })),
                    errors: Vec::new(),
                    warnings: Vec::new(),
                    metadata: HashMap::new(),
                    processed_at: Utc::now(),
                    duration_ms: start_time.elapsed().as_millis() as u64,
                    trace_id: request.trace_id,
                };
            }
        }

        Response {
            id: response_id.to_string(),
            request_id: request.id,
            status: ResponseStatus::NotFound,
            data: None,
            errors: vec!["Resource not found".to_string()],
            warnings: Vec::new(),
            metadata: HashMap::new(),
            processed_at: Utc::now(),
            duration_ms: start_time.elapsed().as_millis() as u64,
            trace_id: request.trace_id,
        }
    }

    fn handle_query(&self, request: Request, response_id: &str, start_time: std::time::Instant) -> Response {
        let resources = self.resources.lock().unwrap();

        let filtered: Vec<&Resource> = resources.values()
            .filter(|r| r.resource_type == request.resource_type)
            .collect();

        let data = serde_json::json!({
            "total": filtered.len(),
            "items": filtered,
        });

        Response {
            id: response_id.to_string(),
            request_id: request.id,
            status: ResponseStatus::Success,
            data: Some(data),
            errors: Vec::new(),
            warnings: Vec::new(),
            metadata: HashMap::new(),
            processed_at: Utc::now(),
            duration_ms: start_time.elapsed().as_millis() as u64,
            trace_id: request.trace_id,
        }
    }

    fn handle_execute(&self, request: Request, response_id: &str, start_time: std::time::Instant) -> Response {
        let action = request.payload.get("action")
            .and_then(|a| a.as_str())
            .unwrap_or("execute");

        Response {
            id: response_id.to_string(),
            request_id: request.id,
            status: ResponseStatus::Success,
            data: Some(serde_json::json!({
                "action": action,
                "executed": true,
                "timestamp": Utc::now().to_rfc3339(),
            })),
            errors: Vec::new(),
            warnings: Vec::new(),
            metadata: HashMap::new(),
            processed_at: Utc::now(),
            duration_ms: start_time.elapsed().as_millis() as u64,
            trace_id: request.trace_id,
        }
    }

    pub fn get_resource(&self, resource_id: &str) -> Option<Resource> {
        let resources = self.resources.lock().unwrap();
        resources.get(resource_id).cloned()
    }

    pub fn list_resources(&self, resource_type: Option<&str>) -> Vec<Resource> {
        let resources = self.resources.lock().unwrap();
        resources.values()
            .filter(|r| resource_type.map_or(true, |t| r.resource_type == t))
            .cloned()
            .collect()
    }

    pub fn update_resource_status(&self, resource_id: &str, status: &str, progress: f32) -> Option<Resource> {
        let mut resources = self.resources.lock().unwrap();
        let resource = resources.get_mut(resource_id)?;
        resource.status = status.to_string();
        resource.progress = progress;
        resource.updated_at = Utc::now();
        Some(resource.clone())
    }

    pub fn process_batch(&self, operations: Vec<BatchAction>, created_by: &str) -> BatchOperation {
        let batch_id = Uuid::new_v4().to_string();
        let now = Utc::now();

        let mut results = Vec::new();

        for (index, operation) in operations.iter().enumerate() {
            let success = match operation.action.as_str() {
                "restart" => true,
                "start" => true,
                "stop" => true,
                "delete" => {
                    if let Some(id) = &operation.id {
                        let mut resources = self.resources.lock().unwrap();
                        resources.remove(id).is_some()
                    } else {
                        false
                    }
                }
                _ => false,
            };

            results.push(BatchResult {
                action_index: index,
                success,
                id: operation.id.clone(),
                message: if success { None } else { Some("Action failed".to_string()) },
            });
        }

        let all_success = results.iter().all(|r| r.success);
        let status = if all_success {
            ResponseStatus::Success
        } else if results.iter().any(|r| r.success) {
            ResponseStatus::Partial
        } else {
            ResponseStatus::Failed
        };

        let batch = BatchOperation {
            id: batch_id.clone(),
            operations,
            status,
            results,
            created_by: created_by.to_string(),
            created_at: now,
            completed_at: Some(Utc::now()),
        };

        let mut batches = self.batch_operations.lock().unwrap();
        batches.insert(batch_id, batch.clone());
        batch
    }

    pub fn get_batch_operation(&self, batch_id: &str) -> Option<BatchOperation> {
        let batches = self.batch_operations.lock().unwrap();
        batches.get(batch_id).cloned()
    }

    pub fn get_request(&self, request_id: &str) -> Option<Request> {
        let requests = self.requests.lock().unwrap();
        requests.get(request_id).cloned()
    }

    pub fn get_response(&self, response_id: &str) -> Option<Response> {
        let responses = self.responses.lock().unwrap();
        responses.get(response_id).cloned()
    }

    pub fn get_response_by_request(&self, request_id: &str) -> Option<Response> {
        let responses = self.responses.lock().unwrap();
        responses.values().find(|r| r.request_id == request_id).cloned()
    }
}

impl Default for CoreProcessor {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_create_request() {
        let processor = CoreProcessor::new();
        let payload = serde_json::json!({
            "name": "test-workflow",
            "config": { "timeout": 30 }
        });

        let request = processor.create_request(
            RequestType::Create,
            "workflow",
            payload,
            "user_001",
        );

        assert_eq!(request.request_type, RequestType::Create);
        assert_eq!(request.resource_type, "workflow");
        assert_eq!(request.created_by, "user_001");
        assert!(!request.trace_id.is_empty());
    }

    #[test]
    fn test_validate_request() {
        let processor = CoreProcessor::new();

        let valid_request = processor.create_request(
            RequestType::Create,
            "workflow",
            serde_json::json!({"name": "test"}),
            "user_001",
        );
        assert!(processor.validate_request(&valid_request).is_ok());

        let invalid_request = processor.create_request(
            RequestType::Create,
            "",
            serde_json::Value::Null,
            "",
        );
        let result = processor.validate_request(&invalid_request);
        assert!(result.is_err());
        assert!(!result.unwrap_err().is_empty());
    }

    #[test]
    fn test_process_create_request() {
        let processor = CoreProcessor::new();
        let request = processor.create_request(
            RequestType::Create,
            "workflow",
            serde_json::json!({"name": "test-workflow"}),
            "user_001",
        );

        let response = processor.process_request(request);
        assert_eq!(response.status, ResponseStatus::Success);
        assert!(response.data.is_some());

        let data = response.data.unwrap();
        assert!(data.get("id").is_some());
        assert_eq!(data.get("status").unwrap(), "provisioning");
    }

    #[test]
    fn test_process_read_request() {
        let processor = CoreProcessor::new();
        
        let create_request = processor.create_request(
            RequestType::Create,
            "workflow",
            serde_json::json!({"name": "test"}),
            "user_001",
        );
        let create_response = processor.process_request(create_request);
        let resource_id = create_response.data.unwrap().get("id").unwrap().as_str().unwrap().to_string();

        let mut read_request = processor.create_request(
            RequestType::Read,
            "workflow",
            serde_json::json!({}),
            "user_001",
        );
        read_request.resource_id = Some(resource_id);

        let read_response = processor.process_request(read_request);
        assert_eq!(read_response.status, ResponseStatus::Success);
        assert!(read_response.data.is_some());
    }

    #[test]
    fn test_process_update_request() {
        let processor = CoreProcessor::new();
        
        let create_request = processor.create_request(
            RequestType::Create,
            "workflow",
            serde_json::json!({"name": "test"}),
            "user_001",
        );
        let create_response = processor.process_request(create_request);
        let resource_id = create_response.data.unwrap().get("id").unwrap().as_str().unwrap().to_string();

        let mut update_request = processor.create_request(
            RequestType::Update,
            "workflow",
            serde_json::json!({"name": "updated-test", "timeout": 60}),
            "user_001",
        );
        update_request.resource_id = Some(resource_id);

        let update_response = processor.process_request(update_request);
        assert_eq!(update_response.status, ResponseStatus::Success);
    }

    #[test]
    fn test_process_delete_request() {
        let processor = CoreProcessor::new();
        
        let create_request = processor.create_request(
            RequestType::Create,
            "workflow",
            serde_json::json!({"name": "test"}),
            "user_001",
        );
        let create_response = processor.process_request(create_request);
        let resource_id = create_response.data.unwrap().get("id").unwrap().as_str().unwrap().to_string();

        let mut delete_request = processor.create_request(
            RequestType::Delete,
            "workflow",
            serde_json::json!({}),
            "user_001",
        );
        delete_request.resource_id = Some(resource_id.clone());

        let delete_response = processor.process_request(delete_request);
        assert_eq!(delete_response.status, ResponseStatus::Success);

        assert!(processor.get_resource(&resource_id).is_none());
    }

    #[test]
    fn test_process_query_request() {
        let processor = CoreProcessor::new();
        
        for i in 0..3 {
            let request = processor.create_request(
                RequestType::Create,
                "workflow",
                serde_json::json!({"name": format!("workflow-{}", i)}),
                "user_001",
            );
            processor.process_request(request);
        }

        let query_request = processor.create_request(
            RequestType::Query,
            "workflow",
            serde_json::json!({}),
            "user_001",
        );

        let query_response = processor.process_request(query_request);
        assert_eq!(query_response.status, ResponseStatus::Success);
        
        let data = query_response.data.unwrap();
        assert_eq!(data.get("total").unwrap(), 3);
    }

    #[test]
    fn test_process_execute_request() {
        let processor = CoreProcessor::new();
        let request = processor.create_request(
            RequestType::Execute,
            "workflow",
            serde_json::json!({"action": "deploy"}),
            "user_001",
        );

        let response = processor.process_request(request);
        assert_eq!(response.status, ResponseStatus::Success);
        assert!(response.data.is_some());
        
        let data = response.data.unwrap();
        assert_eq!(data.get("action").unwrap(), "deploy");
        assert_eq!(data.get("executed").unwrap(), true);
    }

    #[test]
    fn test_update_resource_status() {
        let processor = CoreProcessor::new();
        
        let create_request = processor.create_request(
            RequestType::Create,
            "workflow",
            serde_json::json!({"name": "test"}),
            "user_001",
        );
        let create_response = processor.process_request(create_request);
        let resource_id = create_response.data.unwrap().get("id").unwrap().as_str().unwrap().to_string();

        let updated = processor.update_resource_status(&resource_id, "running", 1.0);
        assert!(updated.is_some());
        
        let updated = updated.unwrap();
        assert_eq!(updated.status, "running");
        assert_eq!(updated.progress, 1.0);
    }

    #[test]
    fn test_process_batch() {
        let processor = CoreProcessor::new();
        
        let operations = vec![
            BatchAction {
                action: "restart".to_string(),
                id: None,
                params: serde_json::json!({}),
            },
            BatchAction {
                action: "start".to_string(),
                id: None,
                params: serde_json::json!({}),
            },
        ];

        let batch = processor.process_batch(operations, "admin");
        assert_eq!(batch.operations.len(), 2);
        assert_eq!(batch.results.len(), 2);
        assert_eq!(batch.status, ResponseStatus::Success);
    }

    #[test]
    fn test_not_found_request() {
        let processor = CoreProcessor::new();
        
        let mut read_request = processor.create_request(
            RequestType::Read,
            "workflow",
            serde_json::json!({}),
            "user_001",
        );
        read_request.resource_id = Some("non-existent-id".to_string());

        let response = processor.process_request(read_request);
        assert_eq!(response.status, ResponseStatus::NotFound);
        assert!(!response.errors.is_empty());
    }
}
