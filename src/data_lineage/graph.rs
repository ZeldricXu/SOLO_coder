use serde::{Deserialize, Serialize};
use petgraph::graph::{DiGraph, NodeIndex};
use petgraph::visit::EdgeRef;
use std::collections::{HashMap, HashSet};
use crate::models::StreamSQLError;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub struct TableReference {
    pub database: String,
    pub schema: Option<String>,
    pub table: String,
}

impl TableReference {
    pub fn new(database: impl Into<String>, table: impl Into<String>) -> Self {
        Self {
            database: database.into(),
            schema: None,
            table: table.into(),
        }
    }

    pub fn with_schema(mut self, schema: impl Into<String>) -> Self {
        self.schema = Some(schema.into());
        self
    }

    pub fn fully_qualified(&self) -> String {
        match &self.schema {
            Some(s) => format!("{}.{}.{}", self.database, s, self.table),
            None => format!("{}.{}", self.database, self.table),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub struct ColumnReference {
    pub table: TableReference,
    pub column: String,
}

impl ColumnReference {
    pub fn new(table: TableReference, column: impl Into<String>) -> Self {
        Self {
            table,
            column: column.into(),
        }
    }

    pub fn fully_qualified(&self) -> String {
        format!("{}.{}", self.table.fully_qualified(), self.column)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LineageNode {
    pub id: String,
    pub node_type: NodeType,
    pub reference: LineageReference,
    pub metadata: HashMap<String, String>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum NodeType {
    Table,
    Column,
    Query,
    Transformation,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum LineageReference {
    Table(TableReference),
    Column(ColumnReference),
    Query(String),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LineageEdge {
    pub id: String,
    pub edge_type: EdgeType,
    pub source_id: String,
    pub target_id: String,
    pub transformation: Option<TransformationInfo>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum EdgeType {
    ReadsFrom,
    WritesTo,
    Transforms,
    Joins,
    Filters,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransformationInfo {
    pub expression: String,
    pub operator: Option<String>,
    pub function: Option<String>,
}

pub struct LineageGraph {
    graph: DiGraph<LineageNode, LineageEdge>,
    node_indices: HashMap<String, NodeIndex>,
}

impl Default for LineageGraph {
    fn default() -> Self {
        Self::new()
    }
}

impl LineageGraph {
    pub fn new() -> Self {
        Self {
            graph: DiGraph::new(),
            node_indices: HashMap::new(),
        }
    }

    pub fn add_node(&mut self, node: LineageNode) -> NodeIndex {
        let node_id = node.id.clone();
        if let Some(&idx) = self.node_indices.get(&node_id) {
            return idx;
        }
        let idx = self.graph.add_node(node);
        self.node_indices.insert(node_id, idx);
        idx
    }

    pub fn add_edge(
        &mut self,
        source_id: &str,
        target_id: &str,
        edge: LineageEdge,
    ) -> Result<(), StreamSQLError> {
        let source_idx = self
            .node_indices
            .get(source_id)
            .ok_or_else(|| StreamSQLError::Lineage(format!("Source node {} not found", source_id)))?;
        let target_idx = self
            .node_indices
            .get(target_id)
            .ok_or_else(|| StreamSQLError::Lineage(format!("Target node {} not found", target_id)))?;

        self.graph.add_edge(*source_idx, *target_idx, edge);
        Ok(())
    }

    pub fn get_node(&self, id: &str) -> Option<&LineageNode> {
        self.node_indices
            .get(id)
            .and_then(|idx| self.graph.node_weight(*idx))
    }

    pub fn get_upstream(&self, node_id: &str) -> Vec<LineageNode> {
        self.get_neighbors(node_id, Direction::Incoming)
    }

    pub fn get_downstream(&self, node_id: &str) -> Vec<LineageNode> {
        self.get_neighbors(node_id, Direction::Outgoing)
    }

    fn get_neighbors(&self, node_id: &str, direction: Direction) -> Vec<LineageNode> {
        self.node_indices
            .get(node_id)
            .map(|idx| match direction {
                Direction::Incoming => self
                    .graph
                    .neighbors_directed(*idx, petgraph::Direction::Incoming),
                Direction::Outgoing => self
                    .graph
                    .neighbors_directed(*idx, petgraph::Direction::Outgoing),
            })
            .map(|neighbors| {
                neighbors
                    .filter_map(|n| self.graph.node_weight(n))
                    .cloned()
                    .collect()
            })
            .unwrap_or_default()
    }

    pub fn get_all_tables(&self) -> Vec<TableReference> {
        self.graph
            .node_weights()
            .filter(|n| matches!(n.node_type, NodeType::Table))
            .filter_map(|n| match &n.reference {
                LineageReference::Table(t) => Some(t.clone()),
                _ => None,
            })
            .collect()
    }

    pub fn find_paths(&self, source_id: &str, target_id: &str) -> Vec<Vec<String>> {
        let mut paths = Vec::new();
        let mut visited = HashSet::new();
        let mut current_path = Vec::new();

        if let (Some(source_idx), Some(target_idx)) = (
            self.node_indices.get(source_id),
            self.node_indices.get(target_id),
        ) {
            self.dfs_find_paths(*source_idx, *target_idx, &mut visited, &mut current_path, &mut paths);
        }

        paths
    }

    fn dfs_find_paths(
        &self,
        current: NodeIndex,
        target: NodeIndex,
        visited: &mut HashSet<NodeIndex>,
        current_path: &mut Vec<String>,
        paths: &mut Vec<Vec<String>>,
    ) {
        if visited.contains(&current) {
            return;
        }

        visited.insert(current);
        if let Some(node) = self.graph.node_weight(current) {
            current_path.push(node.id.clone());
        }

        if current == target {
            paths.push(current_path.clone());
        } else {
            for neighbor in self.graph.neighbors(current) {
                self.dfs_find_paths(neighbor, target, visited, current_path, paths);
            }
        }

        current_path.pop();
        visited.remove(&current);
    }

    pub fn node_count(&self) -> usize {
        self.graph.node_count()
    }

    pub fn edge_count(&self) -> usize {
        self.graph.edge_count()
    }

    pub fn to_json(&self) -> Result<String, StreamSQLError> {
        let nodes: Vec<&LineageNode> = self.graph.node_weights().collect();
        let edges: Vec<&LineageEdge> = self.graph.edge_weights().collect();

        Ok(serde_json::to_string(&serde_json::json!({
            "nodes": nodes,
            "edges": edges,
            "node_count": nodes.len(),
            "edge_count": edges.len(),
        }))?)
    }
}

#[derive(Debug, Clone, Copy)]
enum Direction {
    Incoming,
    Outgoing,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LineageQueryResult {
    pub source_tables: Vec<TableReference>,
    pub target_tables: Vec<TableReference>,
    pub columns: Vec<ColumnLineage>,
    pub transformations: Vec<TransformationInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ColumnLineage {
    pub source: ColumnReference,
    pub target: ColumnReference,
    pub transformation: Option<TransformationInfo>,
}
