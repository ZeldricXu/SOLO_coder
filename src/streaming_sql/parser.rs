use serde::{Deserialize, Serialize};
use sqlparser::dialect::GenericDialect;
use sqlparser::parser::Parser;
use crate::models::StreamSQLError;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StreamingQuery {
    pub query_id: String,
    pub sql: String,
    pub source_tables: Vec<String>,
    pub target_table: Option<String>,
    pub window_spec: Option<WindowSpec>,
    pub watermark: Option<WatermarkSpec>,
    pub query_type: QueryType,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum QueryType {
    Select,
    Insert,
    CreateTableAsSelect,
    Join,
    Aggregation,
    WindowedAggregation,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WindowSpec {
    pub window_type: WindowType,
    pub duration_ms: u64,
    pub slide_duration_ms: Option<u64>,
    pub time_column: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum WindowType {
    Tumbling,
    Sliding,
    Session,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WatermarkSpec {
    pub column: String,
    pub delay_ms: u64,
}

pub struct StreamingSqlParser {
    dialect: GenericDialect,
}

impl Default for StreamingSqlParser {
    fn default() -> Self {
        Self::new()
    }
}

impl StreamingSqlParser {
    pub fn new() -> Self {
        Self {
            dialect: GenericDialect {},
        }
    }

    pub fn parse(&self, sql: &str) -> Result<ParsedQuery, StreamSQLError> {
        let statements = Parser::parse_sql(&self.dialect, sql).map_err(|e| {
            StreamSQLError::Sql(format!("SQL parse error: {}", e))
        })?;

        if statements.is_empty() {
            return Err(StreamSQLError::Sql("No statements found".into()));
        }

        let statement = &statements[0];
        let query_type = self.detect_query_type(statement);
        let source_tables = self.extract_source_tables(statement);
        let target_table = self.extract_target_table(statement);
        let window_spec = self.extract_window(sql);
        let watermark = self.extract_watermark(sql);

        Ok(ParsedQuery {
            sql: sql.to_string(),
            query_type,
            source_tables,
            target_table,
            window_spec,
            watermark,
            is_streaming: self.detect_streaming(sql),
        })
    }

    fn detect_query_type(
        &self,
        stmt: &sqlparser::ast::Statement,
    ) -> QueryType {
        match stmt {
            sqlparser::ast::Statement::Query(_) => QueryType::Select,
            sqlparser::ast::Statement::Insert { .. } => QueryType::Insert,
            sqlparser::ast::Statement::CreateTable { query: Some(_), .. } => QueryType::CreateTableAsSelect,
            _ => QueryType::Select,
        }
    }

    fn extract_source_tables(
        &self,
        stmt: &sqlparser::ast::Statement,
    ) -> Vec<String> {
        let mut tables = Vec::new();

        match stmt {
            sqlparser::ast::Statement::Query(query) => {
                if let sqlparser::ast::SetExpr::Select(select) = &*query.body {
                    for table in &select.from {
                        if let sqlparser::ast::TableFactor::Table { name, .. } = &table.relation {
                            tables.push(self.format_table_name(name));
                        }
                    }
                    for join in &select.joins {
                        if let sqlparser::ast::TableFactor::Table { name, .. } = &join.relation {
                            tables.push(self.format_table_name(name));
                        }
                    }
                }
            }
            sqlparser::ast::Statement::Insert { source: Some(query), .. } => {
                if let sqlparser::ast::SetExpr::Select(select) = &*query.body {
                    for table in &select.from {
                        if let sqlparser::ast::TableFactor::Table { name, .. } = &table.relation {
                            tables.push(self.format_table_name(name));
                        }
                    }
                }
            }
            _ => {}
        }

        tables
    }

    fn extract_target_table(
        &self,
        stmt: &sqlparser::ast::Statement,
    ) -> Option<String> {
        match stmt {
            sqlparser::ast::Statement::Insert { table_name, .. } => {
                Some(self.format_table_name(table_name))
            }
            sqlparser::ast::Statement::CreateTable { name, .. } => {
                Some(self.format_table_name(name))
            }
            _ => None,
        }
    }

    fn format_table_name(&self, name: &sqlparser::ast::ObjectName) -> String {
        name.0.iter()
            .map(|i| i.value.clone())
            .collect::<Vec<_>>()
            .join(".")
    }

    fn extract_window(&self, sql: &str) -> Option<WindowSpec> {
        let upper = sql.to_uppercase();
        
        if let Some(idx) = upper.find("TUMBLE") {
            self.parse_tumbling_window(sql, idx)
        } else if let Some(idx) = upper.find("HOP") {
            self.parse_sliding_window(sql, idx)
        } else if let Some(idx) = upper.find("SESSION") {
            self.parse_session_window(sql, idx)
        } else {
            None
        }
    }

    fn parse_tumbling_window(&self, sql: &str, _idx: usize) -> Option<WindowSpec> {
        let duration_ms = self.extract_duration(sql).unwrap_or(60000);
        
        Some(WindowSpec {
            window_type: WindowType::Tumbling,
            duration_ms,
            slide_duration_ms: None,
            time_column: "event_time".to_string(),
        })
    }

    fn parse_sliding_window(&self, sql: &str, _idx: usize) -> Option<WindowSpec> {
        let duration_ms = self.extract_duration(sql).unwrap_or(60000);
        let slide_ms = self.extract_slide(sql).unwrap_or(10000);
        
        Some(WindowSpec {
            window_type: WindowType::Sliding,
            duration_ms,
            slide_duration_ms: Some(slide_ms),
            time_column: "event_time".to_string(),
        })
    }

    fn parse_session_window(&self, sql: &str, _idx: usize) -> Option<WindowSpec> {
        let duration_ms = self.extract_duration(sql).unwrap_or(300000);
        
        Some(WindowSpec {
            window_type: WindowType::Session,
            duration_ms,
            slide_duration_ms: None,
            time_column: "event_time".to_string(),
        })
    }

    fn extract_duration(&self, sql: &str) -> Option<u64> {
        let re = regex::Regex::new(r"(\d+)\s*(minute|hour|second|day)").ok()?;
        let caps = re.captures(&sql.to_lowercase())?;
        
        let amount: u64 = caps[1].parse().ok()?;
        let unit = &caps[2];
        
        let ms = match unit {
            "second" => amount * 1000,
            "minute" => amount * 60 * 1000,
            "hour" => amount * 60 * 60 * 1000,
            "day" => amount * 24 * 60 * 60 * 1000,
            _ => amount * 1000,
        };
        
        Some(ms)
    }

    fn extract_slide(&self, sql: &str) -> Option<u64> {
        let re = regex::Regex::new(r"(\d+)\s*(minute|hour|second|day)").ok()?;
        let caps = re.captures_iter(&sql.to_lowercase()).nth(1)?;
        
        let amount: u64 = caps[1].parse().ok()?;
        let unit = &caps[2];
        
        let ms = match unit {
            "second" => amount * 1000,
            "minute" => amount * 60 * 1000,
            "hour" => amount * 60 * 60 * 1000,
            _ => amount * 1000,
        };
        
        Some(ms)
    }

    fn extract_watermark(&self, sql: &str) -> Option<WatermarkSpec> {
        let upper = sql.to_uppercase();
        
        if let Some(idx) = upper.find("WATERMARK") {
            let after_idx = idx + "WATERMARK".len();
            let rest = &sql[after_idx..];
            
            if let Some(col_start) = rest.find(|c: char| c.is_alphanumeric()) {
                let col_end = rest[col_start..].find(|c: char| !c.is_alphanumeric() && c != '_').unwrap_or(rest.len() - col_start);
                let column = &rest[col_start..col_start + col_end];
                
                Some(WatermarkSpec {
                    column: column.to_string(),
                    delay_ms: 5000,
                })
            } else {
                Some(WatermarkSpec {
                    column: "event_time".to_string(),
                    delay_ms: 5000,
                })
            }
        } else {
            None
        }
    }

    fn detect_streaming(&self, sql: &str) -> bool {
        let upper = sql.to_uppercase();
        upper.contains("STREAM")
            || upper.contains("TUMBLE")
            || upper.contains("HOP")
            || upper.contains("SESSION")
            || upper.contains("WATERMARK")
            || upper.contains("EMIT")
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParsedQuery {
    pub sql: String,
    pub query_type: QueryType,
    pub source_tables: Vec<String>,
    pub target_table: Option<String>,
    pub window_spec: Option<WindowSpec>,
    pub watermark: Option<WatermarkSpec>,
    pub is_streaming: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueryValidationResult {
    pub valid: bool,
    pub errors: Vec<String>,
    pub warnings: Vec<String>,
    pub suggestions: Vec<String>,
}

impl ParsedQuery {
    pub fn validate(&self) -> QueryValidationResult {
        let mut errors = Vec::new();
        let mut warnings = Vec::new();
        let mut suggestions = Vec::new();

        if self.source_tables.is_empty() {
            errors.push("No source tables found in query".to_string());
        }

        if self.is_streaming && self.window_spec.is_none() {
            warnings.push(
                "Streaming query without window specification may cause unbounded state"
                    .to_string(),
            );
            suggestions.push(
                "Consider adding TUMBLE, HOP, or SESSION window for bounded aggregation"
                    .to_string(),
            );
        }

        if self.is_streaming && self.watermark.is_none() {
            warnings.push(
                "Streaming query without watermark may not handle late data properly"
                    .to_string(),
            );
        }

        QueryValidationResult {
            valid: errors.is_empty(),
            errors,
            warnings,
            suggestions,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_simple_select() {
        let parser = StreamingSqlParser::new();
        let sql = "SELECT * FROM orders";
        
        let parsed = parser.parse(sql).unwrap();
        assert_eq!(parsed.query_type, QueryType::Select);
        assert_eq!(parsed.source_tables, vec!["orders"]);
    }

    #[test]
    fn test_parse_insert_select() {
        let parser = StreamingSqlParser::new();
        let sql = "INSERT INTO orders_agg SELECT COUNT(*) as cnt FROM orders GROUP BY user_id";
        
        let parsed = parser.parse(sql).unwrap();
        assert_eq!(parsed.query_type, QueryType::Insert);
        assert_eq!(parsed.source_tables, vec!["orders"]);
        assert_eq!(parsed.target_table, Some("orders_agg".to_string()));
    }
}
