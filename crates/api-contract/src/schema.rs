use crate::models::{SchemaType, MockEndpoint};
use anyhow::Result;
use serde_json::{Value, Map};
use std::collections::HashMap;

pub struct SchemaParser;

impl SchemaParser {
    pub fn parse_openapi(schema: &str) -> Result<Vec<MockEndpoint>> {
        let schema_value: Value = serde_json::from_str(schema)?;
        let mut endpoints = Vec::new();

        if let Some(paths) = schema_value.get("paths").and_then(|p| p.as_object()) {
            for (path, methods) in paths {
                if let Some(methods_obj) = methods.as_object() {
                    for (method, _) in methods_obj {
                        let method_upper = method.to_uppercase();
                        let response_body = Self::generate_mock_response(&Value::Null);
                        let mut headers = HashMap::new();
                        headers.insert("Content-Type".to_string(), "application/json".to_string());

                        endpoints.push(MockEndpoint {
                            path: path.clone(),
                            method: method_upper,
                            response_body,
                            status_code: 200,
                            response_headers: headers,
                        });
                    }
                }
            }
        }

        Ok(endpoints)
    }

    pub fn parse_graphql(_schema: &str) -> Result<Vec<MockEndpoint>> {
        let mut endpoints = Vec::new();
        let response_body = Self::generate_mock_response(&Value::Null);
        let mut headers = HashMap::new();
        headers.insert("Content-Type".to_string(), "application/json".to_string());

        endpoints.push(MockEndpoint {
            path: "/graphql".to_string(),
            method: "POST".to_string(),
            response_body,
            status_code: 200,
            response_headers: headers,
        });

        Ok(endpoints)
    }

    pub fn extract_endpoints(schema_type: SchemaType, content: &str) -> Result<Vec<MockEndpoint>> {
        match schema_type {
            SchemaType::OpenAPI => Self::parse_openapi(content),
            SchemaType::GraphQL => Self::parse_graphql(content),
        }
    }

    fn generate_mock_response(_schema: &Value) -> Value {
        let mut obj = Map::new();
        obj.insert("success".to_string(), Value::Bool(true));
        obj.insert("message".to_string(), Value::String("Mock response".to_string()));
        obj.insert("timestamp".to_string(), Value::String(chrono::Utc::now().to_rfc3339()));
        Value::Object(obj)
    }
}
