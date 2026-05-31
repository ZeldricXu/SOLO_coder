use crate::models::{ApiContract, ValidationResult, MockServerConfig, SchemaType, ValidationLevel};
use crate::validator::ContractValidator;
use crate::mockserver::MockServerManager;
use anyhow::Result;
use uuid::Uuid;


pub fn upload_contract(
    name: String,
    schema_type: SchemaType,
    schema_content: String,
) -> Result<ApiContract> {
    let now = chrono::Utc::now();
    Ok(ApiContract {
        id: Uuid::new_v4(),
        name,
        schema_type,
        schema_content,
        version: "1.0.0".to_string(),
        created_at: now,
        updated_at: now,
    })
}

pub fn validate_contract(
    schema_type: SchemaType,
    schema_content: &str,
    level: ValidationLevel,
) -> ValidationResult {
    match schema_type {
        SchemaType::OpenAPI => ContractValidator::validate_openapi(schema_content, level),
        SchemaType::GraphQL => ContractValidator::validate_graphql(schema_content, level),
    }
}

pub fn list_contracts() -> Result<Vec<ApiContract>> {
    Ok(Vec::new())
}

pub fn create_mock_server(
    manager: &MockServerManager,
    contract: &ApiContract,
) -> Result<MockServerConfig> {
    manager.create_server(contract)
}

pub fn start_mock_server(
    manager: &MockServerManager,
    server_id: Uuid,
) -> Result<()> {
    manager.start_server(server_id)
}

pub fn stop_mock_server(
    manager: &MockServerManager,
    server_id: Uuid,
) -> Result<()> {
    manager.stop_server(server_id)
}

pub fn list_mock_servers(
    manager: &MockServerManager,
) -> Result<Vec<MockServerConfig>> {
    manager.list_servers()
}
