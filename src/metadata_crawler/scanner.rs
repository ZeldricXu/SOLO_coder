use async_trait::async_trait;
use std::collections::HashMap;
use serde::{Deserialize, Serialize};
use crate::models::StreamSQLError;
use super::schema::*;

pub struct MockMetadataProvider {
    name: String,
    schemas: Vec<String>,
    tables: HashMap<String, Vec<String>>,
    table_schemas: HashMap<String, TableSchema>,
}

impl MockMetadataProvider {
    pub fn new(name: impl Into<String>) -> Self {
        let mut tables = HashMap::new();
        tables.insert(
            "public".to_string(),
            vec!["users".to_string(), "orders".to_string(), "products".to_string()],
        );
        tables.insert(
            "analytics".to_string(),
            vec!["user_activity".to_string(), "daily_metrics".to_string()],
        );

        let mut table_schemas = HashMap::new();
        table_schemas.insert(
            "public.users".to_string(),
            create_users_schema(),
        );
        table_schemas.insert(
            "public.orders".to_string(),
            create_orders_schema(),
        );
        table_schemas.insert(
            "public.products".to_string(),
            create_products_schema(),
        );

        Self {
            name: name.into(),
            schemas: vec!["public".to_string(), "analytics".to_string()],
            tables,
            table_schemas,
        }
    }
}

fn create_users_schema() -> TableSchema {
    TableSchema {
        catalog: "main".to_string(),
        schema: "public".to_string(),
        name: "users".to_string(),
        columns: vec![
            ColumnSchema {
                name: "id".to_string(),
                data_type: "BIGINT".to_string(),
                native_type: Some("bigint".to_string()),
                position: 0,
                nullable: false,
                default_value: None,
                comment: Some("User primary key".to_string()),
                is_primary_key: true,
                is_foreign_key: false,
                is_unique: true,
                is_indexed: true,
                precision: None,
                scale: None,
                length: None,
            },
            ColumnSchema {
                name: "name".to_string(),
                data_type: "VARCHAR".to_string(),
                native_type: Some("varchar(255)".to_string()),
                position: 1,
                nullable: false,
                default_value: None,
                comment: Some("User full name".to_string()),
                is_primary_key: false,
                is_foreign_key: false,
                is_unique: false,
                is_indexed: false,
                precision: None,
                scale: None,
                length: Some(255),
            },
            ColumnSchema {
                name: "email".to_string(),
                data_type: "VARCHAR".to_string(),
                native_type: Some("varchar(255)".to_string()),
                position: 2,
                nullable: false,
                default_value: None,
                comment: Some("User email address".to_string()),
                is_primary_key: false,
                is_foreign_key: false,
                is_unique: true,
                is_indexed: true,
                precision: None,
                scale: None,
                length: Some(255),
            },
            ColumnSchema {
                name: "created_at".to_string(),
                data_type: "TIMESTAMP".to_string(),
                native_type: Some("timestamp".to_string()),
                position: 3,
                nullable: false,
                default_value: Some("CURRENT_TIMESTAMP".to_string()),
                comment: Some("Record creation timestamp".to_string()),
                is_primary_key: false,
                is_foreign_key: false,
                is_unique: false,
                is_indexed: true,
                precision: None,
                scale: None,
                length: None,
            },
            ColumnSchema {
                name: "is_active".to_string(),
                data_type: "BOOLEAN".to_string(),
                native_type: Some("boolean".to_string()),
                position: 4,
                nullable: false,
                default_value: Some("true".to_string()),
                comment: Some("Account active status".to_string()),
                is_primary_key: false,
                is_foreign_key: false,
                is_unique: false,
                is_indexed: false,
                precision: None,
                scale: None,
                length: None,
            },
        ],
        primary_keys: vec!["id".to_string()],
        foreign_keys: Vec::new(),
        table_type: TableType::Table,
        comment: Some("User account information".to_string()),
        row_count: Some(10000),
        size_bytes: Some(2048000),
        created_at: chrono::Utc::now(),
        updated_at: chrono::Utc::now(),
    }
}

fn create_orders_schema() -> TableSchema {
    TableSchema {
        catalog: "main".to_string(),
        schema: "public".to_string(),
        name: "orders".to_string(),
        columns: vec![
            ColumnSchema {
                name: "id".to_string(),
                data_type: "BIGINT".to_string(),
                native_type: Some("bigint".to_string()),
                position: 0,
                nullable: false,
                default_value: None,
                comment: Some("Order primary key".to_string()),
                is_primary_key: true,
                is_foreign_key: false,
                is_unique: true,
                is_indexed: true,
                precision: None,
                scale: None,
                length: None,
            },
            ColumnSchema {
                name: "user_id".to_string(),
                data_type: "BIGINT".to_string(),
                native_type: Some("bigint".to_string()),
                position: 1,
                nullable: false,
                default_value: None,
                comment: Some("User foreign key".to_string()),
                is_primary_key: false,
                is_foreign_key: true,
                is_unique: false,
                is_indexed: true,
                precision: None,
                scale: None,
                length: None,
            },
            ColumnSchema {
                name: "product_id".to_string(),
                data_type: "BIGINT".to_string(),
                native_type: Some("bigint".to_string()),
                position: 2,
                nullable: false,
                default_value: None,
                comment: Some("Product foreign key".to_string()),
                is_primary_key: false,
                is_foreign_key: true,
                is_unique: false,
                is_indexed: true,
                precision: None,
                scale: None,
                length: None,
            },
            ColumnSchema {
                name: "quantity".to_string(),
                data_type: "INTEGER".to_string(),
                native_type: Some("integer".to_string()),
                position: 3,
                nullable: false,
                default_value: Some("1".to_string()),
                comment: Some("Order quantity".to_string()),
                is_primary_key: false,
                is_foreign_key: false,
                is_unique: false,
                is_indexed: false,
                precision: None,
                scale: None,
                length: None,
            },
            ColumnSchema {
                name: "total_amount".to_string(),
                data_type: "DECIMAL".to_string(),
                native_type: Some("decimal(10,2)".to_string()),
                position: 4,
                nullable: false,
                default_value: None,
                comment: Some("Order total amount".to_string()),
                is_primary_key: false,
                is_foreign_key: false,
                is_unique: false,
                is_indexed: false,
                precision: Some(10),
                scale: Some(2),
                length: None,
            },
            ColumnSchema {
                name: "created_at".to_string(),
                data_type: "TIMESTAMP".to_string(),
                native_type: Some("timestamp".to_string()),
                position: 5,
                nullable: false,
                default_value: Some("CURRENT_TIMESTAMP".to_string()),
                comment: Some("Order creation timestamp".to_string()),
                is_primary_key: false,
                is_foreign_key: false,
                is_unique: false,
                is_indexed: true,
                precision: None,
                scale: None,
                length: None,
            },
        ],
        primary_keys: vec!["id".to_string()],
        foreign_keys: vec![
            ForeignKey {
                name: "fk_orders_user".to_string(),
                columns: vec!["user_id".to_string()],
                referenced_table: "users".to_string(),
                referenced_columns: vec!["id".to_string()],
                update_rule: Some("CASCADE".to_string()),
                delete_rule: Some("RESTRICT".to_string()),
            },
            ForeignKey {
                name: "fk_orders_product".to_string(),
                columns: vec!["product_id".to_string()],
                referenced_table: "products".to_string(),
                referenced_columns: vec!["id".to_string()],
                update_rule: Some("CASCADE".to_string()),
                delete_rule: Some("RESTRICT".to_string()),
            },
        ],
        table_type: TableType::Table,
        comment: Some("Customer orders".to_string()),
        row_count: Some(50000),
        size_bytes: Some(8192000),
        created_at: chrono::Utc::now(),
        updated_at: chrono::Utc::now(),
    }
}

fn create_products_schema() -> TableSchema {
    TableSchema {
        catalog: "main".to_string(),
        schema: "public".to_string(),
        name: "products".to_string(),
        columns: vec![
            ColumnSchema {
                name: "id".to_string(),
                data_type: "BIGINT".to_string(),
                native_type: Some("bigint".to_string()),
                position: 0,
                nullable: false,
                default_value: None,
                comment: Some("Product primary key".to_string()),
                is_primary_key: true,
                is_foreign_key: false,
                is_unique: true,
                is_indexed: true,
                precision: None,
                scale: None,
                length: None,
            },
            ColumnSchema {
                name: "name".to_string(),
                data_type: "VARCHAR".to_string(),
                native_type: Some("varchar(255)".to_string()),
                position: 1,
                nullable: false,
                default_value: None,
                comment: Some("Product name".to_string()),
                is_primary_key: false,
                is_foreign_key: false,
                is_unique: false,
                is_indexed: true,
                precision: None,
                scale: None,
                length: Some(255),
            },
            ColumnSchema {
                name: "price".to_string(),
                data_type: "DECIMAL".to_string(),
                native_type: Some("decimal(10,2)".to_string()),
                position: 2,
                nullable: false,
                default_value: None,
                comment: Some("Product price".to_string()),
                is_primary_key: false,
                is_foreign_key: false,
                is_unique: false,
                is_indexed: false,
                precision: Some(10),
                scale: Some(2),
                length: None,
            },
            ColumnSchema {
                name: "stock".to_string(),
                data_type: "INTEGER".to_string(),
                native_type: Some("integer".to_string()),
                position: 3,
                nullable: false,
                default_value: Some("0".to_string()),
                comment: Some("Available stock".to_string()),
                is_primary_key: false,
                is_foreign_key: false,
                is_unique: false,
                is_indexed: false,
                precision: None,
                scale: None,
                length: None,
            },
            ColumnSchema {
                name: "category".to_string(),
                data_type: "VARCHAR".to_string(),
                native_type: Some("varchar(100)".to_string()),
                position: 4,
                nullable: true,
                default_value: None,
                comment: Some("Product category".to_string()),
                is_primary_key: false,
                is_foreign_key: false,
                is_unique: false,
                is_indexed: true,
                precision: None,
                scale: None,
                length: Some(100),
            },
        ],
        primary_keys: vec!["id".to_string()],
        foreign_keys: Vec::new(),
        table_type: TableType::Table,
        comment: Some("Product catalog".to_string()),
        row_count: Some(2500),
        size_bytes: Some(512000),
        created_at: chrono::Utc::now(),
        updated_at: chrono::Utc::now(),
    }
}

#[async_trait]
impl MetadataProvider for MockMetadataProvider {
    async fn get_data_source_info(&self) -> Result<DataSourceInfo, StreamSQLError> {
        Ok(DataSourceInfo {
            id: "mock".to_string(),
            name: self.name.clone(),
            source_type: DataSourceType::Postgresql,
            connection_string: "postgres://localhost:5432/db".to_string(),
            description: Some("Mock metadata provider for testing".to_string()),
            schemas: self
                .schemas
                .iter()
                .map(|s| SchemaInfo {
                    name: s.clone(),
                    tables: self.tables.get(s).cloned().unwrap_or_default(),
                    views: Vec::new(),
                    table_count: self.tables.get(s).map(|t| t.len()).unwrap_or(0),
                })
                .collect(),
            last_scanned_at: Some(chrono::Utc::now()),
            status: DataSourceStatus::Active,
        })
    }

    async fn list_schemas(&self) -> Result<Vec<String>, StreamSQLError> {
        Ok(self.schemas.clone())
    }

    async fn list_tables(&self, schema: &str) -> Result<Vec<String>, StreamSQLError> {
        Ok(self
            .tables
            .get(schema)
            .cloned()
            .unwrap_or_default())
    }

    async fn get_table_schema(
        &self,
        schema: &str,
        table: &str,
    ) -> Result<TableSchema, StreamSQLError> {
        let key = format!("{}.{}", schema, table);
        self.table_schemas
            .get(&key)
            .cloned()
            .ok_or_else(|| StreamSQLError::Metadata(format!("Table {} not found", key)))
    }

    async fn get_table_statistics(
        &self,
        schema: &str,
        table: &str,
    ) -> Result<SchemaStatistics, StreamSQLError> {
        let mut column_stats = HashMap::new();

        if table == "users" {
            column_stats.insert(
                "is_active".to_string(),
                ColumnStatistics {
                    null_count: 0,
                    non_null_count: 10000,
                    distinct_count: 2,
                    min_value: Some("false".to_string()),
                    max_value: Some("true".to_string()),
                    average_length: None,
                    top_values: vec![
                        ValueFrequency {
                            value: "true".to_string(),
                            frequency: 8500,
                        },
                        ValueFrequency {
                            value: "false".to_string(),
                            frequency: 1500,
                        },
                    ],
                    histogram: None,
                },
            );
        }

        Ok(SchemaStatistics {
            table_name: table.to_string(),
            column_statistics: column_stats,
            last_updated: chrono::Utc::now(),
        })
    }

    async fn get_sample_data(
        &self,
        schema: &str,
        table: &str,
        limit: usize,
    ) -> Result<DataSample, StreamSQLError> {
        let mut rows = Vec::new();

        for i in 0..limit.min(10) {
            let row = match table {
                "users" => serde_json::json!({
                    "id": i + 1,
                    "name": format!("User {}", i + 1),
                    "email": format!("user{}@example.com", i + 1),
                    "created_at": chrono::Utc::now().to_rfc3339(),
                    "is_active": i % 5 != 0,
                }),
                "orders" => serde_json::json!({
                    "id": i + 1,
                    "user_id": (i % 100) + 1,
                    "product_id": (i % 50) + 1,
                    "quantity": (i % 5) + 1,
                    "total_amount": ((i + 1) * 25) as f64,
                    "created_at": chrono::Utc::now().to_rfc3339(),
                }),
                "products" => serde_json::json!({
                    "id": i + 1,
                    "name": format!("Product {}", i + 1),
                    "price": ((i + 1) * 10) as f64,
                    "stock": (i + 1) * 100,
                    "category": if i % 2 == 0 { "Electronics" } else { "Clothing" },
                }),
                _ => serde_json::json!({ "id": i + 1 }),
            };
            rows.push(row);
        }

        Ok(DataSample {
            sample_id: crate::models::IdGenerator::generate("sample"),
            table_name: format!("{}.{}", schema, table),
            rows,
            sample_size: limit,
            total_rows: Some(10000),
            sampled_at: chrono::Utc::now(),
            sampling_strategy: SamplingStrategy::FirstN,
        })
    }

    async fn test_connection(&self) -> Result<(), StreamSQLError> {
        Ok(())
    }
}
