use crate::models::StreamSQLError;
use super::graph::{LineageGraph, LineageNode, LineageEdge, LineageReference, NodeType, EdgeType, TableReference, ColumnReference, LineageQueryResult, TransformationInfo, ColumnLineage};
use super::parser::SqlLineageParser;
use sqlparser::ast::Statement;
use std::collections::{HashMap, HashSet};

pub struct LineageExtractor {
    parser: SqlLineageParser,
}

impl Default for LineageExtractor {
    fn default() -> Self {
        Self::new()
    }
}

impl LineageExtractor {
    pub fn new() -> Self {
        Self {
            parser: SqlLineageParser::new(),
        }
    }

    pub fn extract_from_sql(&self, sql: &str) -> Result<LineageQueryResult, StreamSQLError> {
        let statements = self.parser.parse(sql)?;
        if statements.is_empty() {
            return Err(StreamSQLError::Lineage("No statements found in SQL".into()));
        }

        let statement = &statements[0];
        let mut result = LineageQueryResult {
            source_tables: self.parser.extract_source_tables(statement),
            target_tables: self.parser.extract_target_tables(statement),
            columns: Vec::new(),
            transformations: Vec::new(),
        };

        let columns = self.parser.extract_columns(statement);
        
        for (i, col) in columns.iter().enumerate() {
            if i < result.source_tables.len() {
                result.columns.push(ColumnLineage {
                    source: col.clone(),
                    target: ColumnReference::new(
                        result.target_tables.get(0).cloned().unwrap_or_else(|| TableReference::new("default", "output")),
                        col.column.clone(),
                    ),
                    transformation: None,
                });
            }
        }

        Ok(result)
    }

    pub fn build_graph(&self, sql_history: &[String]) -> Result<LineageGraph, StreamSQLError> {
        let mut graph = LineageGraph::new();
        let mut table_set: HashSet<TableReference> = HashSet::new();
        let mut column_set: HashSet<ColumnReference> = HashSet::new();

        for sql in sql_history {
            let result = self.extract_from_sql(sql)?;
            
            for table in &result.source_tables {
                table_set.insert(table.clone());
            }
            for table in &result.target_tables {
                table_set.insert(table.clone());
            }
            for lineage in &result.columns {
                column_set.insert(lineage.source.clone());
                column_set.insert(lineage.target.clone());
            }
        }

        for table in &table_set {
            let node_id = table.fully_qualified();
            graph.add_node(LineageNode {
                id: node_id.clone(),
                node_type: NodeType::Table,
                reference: LineageReference::Table(table.clone()),
                metadata: HashMap::new(),
            });
        }

        for column in &column_set {
            let node_id = column.fully_qualified();
            graph.add_node(LineageNode {
                id: node_id.clone(),
                node_type: NodeType::Column,
                reference: LineageReference::Column(column.clone()),
                metadata: HashMap::new(),
            });

            let table_node_id = column.table.fully_qualified();
            graph.add_edge(
                &table_node_id,
                &node_id,
                LineageEdge {
                    id: format!("table_col_{}", node_id),
                    edge_type: EdgeType::ReadsFrom,
                    source_id: table_node_id,
                    target_id: node_id,
                    transformation: None,
                },
            ).ok();
        }

        for sql in sql_history {
            let result = self.extract_from_sql(sql)?;
            
            for lineage in &result.columns {
                let source_id = lineage.source.fully_qualified();
                let target_id = lineage.target.fully_qualified();
                
                if let (Some(_), Some(_)) = (graph.get_node(&source_id), graph.get_node(&target_id)) {
                    graph.add_edge(
                        &source_id,
                        &target_id,
                        LineageEdge {
                            id: format!("lineage_{}_{}", source_id, target_id),
                            edge_type: EdgeType::Transforms,
                            source_id: source_id.clone(),
                            target_id: target_id.clone(),
                            transformation: lineage.transformation.clone(),
                        },
                    ).ok();
                }
            }

            for source in &result.source_tables {
                for target in &result.target_tables {
                    let source_id = source.fully_qualified();
                    let target_id = target.fully_qualified();
                    
                    if let (Some(_), Some(_)) = (graph.get_node(&source_id), graph.get_node(&target_id)) {
                        graph.add_edge(
                            &source_id,
                            &target_id,
                            LineageEdge {
                                id: format!("table_{}_{}", source_id, target_id),
                                edge_type: EdgeType::WritesTo,
                                source_id: source_id.clone(),
                                target_id: target_id.clone(),
                                transformation: None,
                            },
                        ).ok();
                    }
                }
            }
        }

        Ok(graph)
    }

    pub fn extract_table_lineage(
        &self,
        sql: &str,
    ) -> Result<(Vec<TableReference>, Vec<TableReference>), StreamSQLError> {
        let statements = self.parser.parse(sql)?;
        if statements.is_empty() {
            return Err(StreamSQLError::Lineage("No statements found in SQL".into()));
        }

        Ok((
            self.parser.extract_source_tables(&statements[0]),
            self.parser.extract_target_tables(&statements[0]),
        ))
    }

    pub fn get_impact_analysis(
        &self,
        graph: &LineageGraph,
        table_name: &str,
    ) -> Vec<String> {
        let mut impacted = Vec::new();
        let mut visited = HashSet::new();
        
        for node in graph.get_all_tables() {
            if node.table == table_name {
                let node_id = node.fully_qualified();
                self.traverse_downstream(graph, &node_id, &mut visited, &mut impacted);
            }
        }
        
        impacted
    }

    fn traverse_downstream(
        &self,
        graph: &LineageGraph,
        node_id: &str,
        visited: &mut HashSet<String>,
        impacted: &mut Vec<String>,
    ) {
        if visited.contains(node_id) {
            return;
        }
        visited.insert(node_id.to_string());

        for downstream in graph.get_downstream(node_id) {
            if !impacted.contains(&downstream.id) {
                impacted.push(downstream.id.clone());
            }
            self.traverse_downstream(graph, &downstream.id, visited, impacted);
        }
    }

    pub fn get_data_origin(
        &self,
        graph: &LineageGraph,
        column: &ColumnReference,
    ) -> Vec<ColumnReference> {
        let mut origins = Vec::new();
        let mut visited = HashSet::new();
        let node_id = column.fully_qualified();
        
        self.traverse_upstream_columns(graph, &node_id, &mut visited, &mut origins);
        origins
    }

    fn traverse_upstream_columns(
        &self,
        graph: &LineageGraph,
        node_id: &str,
        visited: &mut HashSet<String>,
        origins: &mut Vec<ColumnReference>,
    ) {
        if visited.contains(node_id) {
            return;
        }
        visited.insert(node_id.to_string());

        let upstream = graph.get_upstream(node_id);
        if upstream.is_empty() {
            if let Some(node) = graph.get_node(node_id) {
                if let LineageReference::Column(col) = &node.reference {
                    if !origins.contains(col) {
                        origins.push(col.clone());
                    }
                }
            }
            return;
        }

        for upstream_node in &upstream {
            if matches!(upstream_node.node_type, NodeType::Column) {
                self.traverse_upstream_columns(graph, &upstream_node.id, visited, origins);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_extract_from_sql() {
        let extractor = LineageExtractor::new();
        let sql = "INSERT INTO target SELECT id, name FROM source";
        
        let result = extractor.extract_from_sql(sql).unwrap();
        
        assert_eq!(result.source_tables.len(), 1);
        assert_eq!(result.source_tables[0].table, "source");
        assert_eq!(result.target_tables.len(), 1);
        assert_eq!(result.target_tables[0].table, "target");
    }

    #[test]
    fn test_build_graph() {
        let extractor = LineageExtractor::new();
        let sqls = vec![
            "INSERT INTO table_b SELECT * FROM table_a".to_string(),
            "INSERT INTO table_c SELECT * FROM table_b".to_string(),
        ];
        
        let graph = extractor.build_graph(&sqls).unwrap();
        
        assert!(graph.node_count() >= 3);
    }
}
