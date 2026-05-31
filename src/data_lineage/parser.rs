use sqlparser::ast::{Expr, Query, Select, SelectItem, SetExpr, Statement, TableFactor, Value};
use sqlparser::dialect::GenericDialect;
use sqlparser::parser::Parser;
use crate::models::StreamSQLError;
use super::graph::{TableReference, ColumnReference};

pub struct SqlLineageParser {
    dialect: GenericDialect,
}

impl Default for SqlLineageParser {
    fn default() -> Self {
        Self::new()
    }
}

impl SqlLineageParser {
    pub fn new() -> Self {
        Self {
            dialect: GenericDialect {},
        }
    }

    pub fn parse(&self, sql: &str) -> Result<Vec<Statement>, StreamSQLError> {
        Parser::parse_sql(&self.dialect, sql).map_err(|e| {
            StreamSQLError::Lineage(format!("SQL parse error: {}", e))
        })
    }

    pub fn extract_source_tables(&self, statement: &Statement) -> Vec<TableReference> {
        let mut tables = Vec::new();
        self.extract_tables_from_statement(statement, &mut tables, true);
        tables
    }

    pub fn extract_target_tables(&self, statement: &Statement) -> Vec<TableReference> {
        let mut tables = Vec::new();
        self.extract_tables_from_statement(statement, &mut tables, false);
        tables
    }

    fn extract_tables_from_statement(
        &self,
        statement: &Statement,
        tables: &mut Vec<TableReference>,
        source: bool,
    ) {
        match statement {
            Statement::Query(query) => {
                self.extract_tables_from_query(query, tables);
            }
            Statement::Insert { table_name, .. } | Statement::Update { table_name, .. } | Statement::Delete { table_name, .. } => {
                if !source {
                    tables.push(self.parse_table_factor_name(table_name));
                }
                if let Statement::Insert { source: Some(query), .. } = statement {
                    self.extract_tables_from_query(query, tables);
                }
                if let Statement::Update { from: Some(query), .. } = statement {
                    if let SetExpr::Select(select) = &*query.body {
                        for table in &select.from {
                            if let TableFactor::Table { name, .. } = &table.relation {
                                tables.push(self.parse_table_factor_name(name));
                            }
                        }
                    }
                }
            }
            Statement::CreateTable { query: Some(query), .. } => {
                if let Some(name) = match statement {
                    Statement::CreateTable { name, .. } => Some(name),
                    _ => None,
                } {
                    if !source {
                        tables.push(self.parse_table_factor_name(name));
                    }
                }
                self.extract_tables_from_query(query, tables);
            }
            _ => {}
        }
    }

    fn extract_tables_from_query(&self, query: &Query, tables: &mut Vec<TableReference>) {
        if let SetExpr::Select(select) = &*query.body {
            self.extract_tables_from_select(select, tables);
        }
    }

    fn extract_tables_from_select(&self, select: &Select, tables: &mut Vec<TableReference>) {
        for table in &select.from {
            if let TableFactor::Table { name, .. } = &table.relation {
                tables.push(self.parse_table_factor_name(name));
            }
            if let TableFactor::Derived { subquery, .. } = &table.relation {
                self.extract_tables_from_query(subquery, tables);
            }
        }

        for join in &select.joins {
            if let TableFactor::Table { name, .. } = &join.relation {
                tables.push(self.parse_table_factor_name(name));
            }
        }
    }

    fn parse_table_factor_name(&self, name: &sqlparser::ast::ObjectName) -> TableReference {
        let parts: Vec<String> = name.0.iter().map(|i| i.value.clone()).collect();
        
        match parts.len() {
            1 => TableReference::new("default", parts[0].clone()),
            2 => TableReference::new(parts[0].clone(), parts[1].clone()),
            3 => TableReference::new(parts[0].clone(), parts[2].clone()).with_schema(parts[1].clone()),
            _ => TableReference::new("default", parts.last().cloned().unwrap_or_else(|| "unknown".to_string())),
        }
    }

    pub fn extract_columns(&self, statement: &Statement) -> Vec<ColumnReference> {
        let mut columns = Vec::new();
        
        if let Statement::Query(query) = statement {
            if let SetExpr::Select(select) = &*query.body {
                for item in &select.projection {
                    if let SelectItem::UnnamedExpr(expr) | SelectItem::ExprWithAlias { expr, .. } = item {
                        self.extract_columns_from_expr(expr, &mut columns);
                    }
                }
            }
        }

        columns
    }

    fn extract_columns_from_expr(&self, expr: &Expr, columns: &mut Vec<ColumnReference>) {
        match expr {
            Expr::CompoundIdentifier(idents) => {
                if idents.len() >= 2 {
                    let col_name = idents.last().unwrap().value.clone();
                    let table_parts: Vec<String> = idents.iter().take(idents.len() - 1).map(|i| i.value.clone()).collect();
                    
                    let table_ref = match table_parts.len() {
                        1 => TableReference::new("default", table_parts[0].clone()),
                        2 => TableReference::new(table_parts[0].clone(), table_parts[1].clone()),
                        _ => TableReference::new("default", "unknown"),
                    };
                    
                    columns.push(ColumnReference::new(table_ref, col_name));
                }
            }
            Expr::Identifier(ident) => {
                columns.push(ColumnReference::new(
                    TableReference::new("default", "unknown"),
                    ident.value.clone(),
                ));
            }
            Expr::Nested(expr) => {
                self.extract_columns_from_expr(expr, columns);
            }
            Expr::UnaryOp { expr, .. } => {
                self.extract_columns_from_expr(expr, columns);
            }
            Expr::BinaryOp { left, right, .. } => {
                self.extract_columns_from_expr(left, columns);
                self.extract_columns_from_expr(right, columns);
            }
            Expr::Function { args, .. } => {
                for arg in args {
                    if let sqlparser::ast::FunctionArg::Unnamed(sqlparser::ast::FunctionArgExpr::Expr(expr)) = arg {
                        self.extract_columns_from_expr(expr, columns);
                    }
                }
            }
            Expr::Case { conditions, results, else_result, .. } => {
                for cond in conditions {
                    self.extract_columns_from_expr(cond, columns);
                }
                for result in results {
                    self.extract_columns_from_expr(result, columns);
                }
                if let Some(else_expr) = else_result {
                    self.extract_columns_from_expr(else_expr, columns);
                }
            }
            Expr::InSubquery { expr, subquery, .. } => {
                self.extract_columns_from_expr(expr, columns);
                self.extract_tables_from_query(subquery, &mut Vec::new());
            }
            Expr::Exists { subquery, .. } => {
                self.extract_tables_from_query(subquery, &mut Vec::new());
            }
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_simple_select() {
        let parser = SqlLineageParser::new();
        let sql = "SELECT id, name FROM users";
        
        let statements = parser.parse(sql).unwrap();
        assert_eq!(statements.len(), 1);
        
        let sources = parser.extract_source_tables(&statements[0]);
        assert_eq!(sources.len(), 1);
        assert_eq!(sources[0].table, "users");
    }

    #[test]
    fn test_parse_select_with_join() {
        let parser = SqlLineageParser::new();
        let sql = "SELECT u.id, o.amount FROM users u JOIN orders o ON u.id = o.user_id";
        
        let statements = parser.parse(sql).unwrap();
        let sources = parser.extract_source_tables(&statements[0]);
        
        let table_names: Vec<&str> = sources.iter().map(|t| t.table.as_str()).collect();
        assert!(table_names.contains(&"users"));
        assert!(table_names.contains(&"orders"));
    }

    #[test]
    fn test_parse_insert_select() {
        let parser = SqlLineageParser::new();
        let sql = "INSERT INTO target_table SELECT * FROM source_table";
        
        let statements = parser.parse(sql).unwrap();
        let targets = parser.extract_target_tables(&statements[0]);
        let sources = parser.extract_source_tables(&statements[0]);
        
        assert_eq!(targets.len(), 1);
        assert_eq!(targets[0].table, "target_table");
        assert_eq!(sources.len(), 1);
        assert_eq!(sources[0].table, "source_table");
    }
}
