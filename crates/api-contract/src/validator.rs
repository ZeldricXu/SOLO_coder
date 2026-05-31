use crate::models::{ValidationLevel, ValidationResult, ValidationIssue, Severity, ApiContract, ContractDiff, MockEndpoint};
use serde_json::Value;
use std::collections::HashSet;
use std::time::Instant;

pub struct ContractValidator;

impl ContractValidator {
    pub fn validate_openapi(schema: &str, level: ValidationLevel) -> ValidationResult {
        let start = Instant::now();
        let mut issues = Vec::new();

        match serde_json::from_str::<Value>(schema) {
            Ok(value) => {
                if !value.get("openapi").is_some() && !value.get("swagger").is_some() {
                    issues.push(ValidationIssue {
                        path: "/".to_string(),
                        message: "Missing openapi/swagger version field".to_string(),
                        severity: Severity::Error,
                    });
                }
                if !value.get("info").is_some() {
                    issues.push(ValidationIssue {
                        path: "/info".to_string(),
                        message: "Missing info section".to_string(),
                        severity: if level == ValidationLevel::Strict { Severity::Error } else { Severity::Warning },
                    });
                }
                if !value.get("paths").is_some() {
                    issues.push(ValidationIssue {
                        path: "/paths".to_string(),
                        message: "Missing paths section".to_string(),
                        severity: Severity::Error,
                    });
                }
            }
            Err(e) => {
                issues.push(ValidationIssue {
                    path: "/".to_string(),
                    message: format!("Invalid JSON: {}", e),
                    severity: Severity::Error,
                });
            }
        }

        let passed = issues.iter().all(|i| i.severity != Severity::Error);
        let duration_ms = start.elapsed().as_millis() as u64;

        ValidationResult {
            passed,
            issues,
            duration_ms,
        }
    }

    pub fn validate_graphql(schema: &str, level: ValidationLevel) -> ValidationResult {
        let start = Instant::now();
        let mut issues = Vec::new();

        if schema.trim().is_empty() {
            issues.push(ValidationIssue {
                path: "/".to_string(),
                message: "Empty GraphQL schema".to_string(),
                severity: Severity::Error,
            });
        }

        if !schema.contains("type") && !schema.contains("schema") {
            issues.push(ValidationIssue {
                path: "/".to_string(),
                message: "No type definitions found".to_string(),
                severity: if level == ValidationLevel::Strict { Severity::Error } else { Severity::Warning },
            });
        }

        let passed = issues.iter().all(|i| i.severity != Severity::Error);
        let duration_ms = start.elapsed().as_millis() as u64;

        ValidationResult {
            passed,
            issues,
            duration_ms,
        }
    }

    pub fn validate_request(endpoint: &MockEndpoint, req_body: Option<&Value>) -> ValidationResult {
        let start = Instant::now();
        let mut issues = Vec::new();

        if let Some(body) = req_body {
            if !body.is_object() {
                issues.push(ValidationIssue {
                    path: endpoint.path.clone(),
                    message: "Request body must be an object".to_string(),
                    severity: Severity::Warning,
                });
            }
        }

        let passed = issues.iter().all(|i| i.severity != Severity::Error);
        let duration_ms = start.elapsed().as_millis() as u64;

        ValidationResult {
            passed,
            issues,
            duration_ms,
        }
    }

    pub fn compare_contracts(old: &ApiContract, new: &ApiContract) -> ContractDiff {
        let old_endpoints: HashSet<String> = old.schema_content.lines()
            .filter(|l| l.contains('"') && l.contains('/'))
            .map(|l| l.trim().to_string())
            .collect();

        let new_endpoints: HashSet<String> = new.schema_content.lines()
            .filter(|l| l.contains('"') && l.contains('/'))
            .map(|l| l.trim().to_string())
            .collect();

        let added_endpoints = new_endpoints.difference(&old_endpoints).cloned().collect();
        let removed_endpoints = old_endpoints.difference(&new_endpoints).cloned().collect();
        let modified_endpoints = old_endpoints.intersection(&new_endpoints).cloned().collect();

        ContractDiff {
            added_endpoints,
            removed_endpoints,
            modified_endpoints,
        }
    }
}
