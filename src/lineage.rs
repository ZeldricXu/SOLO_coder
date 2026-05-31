use crate::types::{
    AppError, AppResult, ColumnMapping, LineageConfig, LineageEdge, LineageEdgeType, LineageGraph,
    LineageNode, LineageNodeType, ParsedSqlLineage, generate_id, now_utc,
};
use dashmap::DashMap;
use nom::{
    bytes::complete::{tag, tag_no_case, take_until, take_while},
    character::complete::{multispace0, multispace1},
    combinator::{opt, recognize},
    multi::many0,
    sequence::{delimited, preceded, terminated},
    IResult,
};
use petgraph::graph::{DiGraph, NodeIndex};
use petgraph::Direction;
use std::collections::{HashMap, HashSet};
use std::sync::Arc;

#[derive(Debug, Clone)]
struct SqlToken {
    text: String,
    token_type: SqlTokenType,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum SqlTokenType {
    Keyword,
    Identifier,
    Operator,
    Literal,
    Punctuation,
}

pub struct SqlLineageParser {
    config: LineageConfig,
}

impl SqlLineageParser {
    pub fn new(config: LineageConfig) -> Self {
        Self { config }
    }

    pub fn parse_sql(&self, sql: &str) -> AppResult<ParsedSqlLineage> {
        tracing::debug!(target: "lineage", "解析SQL: {}", sql);

        let normalized_sql = self.normalize_sql(sql);

        let mut source_tables = HashSet::new();
        let mut target_tables = HashSet::new();
        let mut source_columns = Vec::new();
        let mut target_columns = Vec::new();
        let mut column_mappings = Vec::new();

        let is_insert = normalized_sql.contains("INSERT");
        let is_update = normalized_sql.contains("UPDATE");
        let is_delete = normalized_sql.contains("DELETE");
        let is_create = normalized_sql.contains("CREATE");
        let is_alter = normalized_sql.contains("ALTER");

        self.extract_from_tables(&normalized_sql, &mut source_tables);
        self.extract_join_tables(&normalized_sql, &mut source_tables);

        if is_insert {
            self.extract_insert_target(&normalized_sql, &mut target_tables);
        } else if is_update {
            self.extract_update_target(&normalized_sql, &mut target_tables);
        } else if is_create {
            self.extract_create_target(&normalized_sql, &mut target_tables);
        } else if is_alter {
            self.extract_alter_target(&normalized_sql, &mut target_tables);
        }

        if !is_delete && !is_alter {
            self.extract_select_columns(&normalized_sql, &source_tables, &mut source_columns);
        }

        if is_insert || is_create {
            self.extract_target_columns(&normalized_sql, &mut target_columns);
        }

        for (src_table, src_col) in &source_columns {
            for (tgt_table, tgt_col) in &target_columns {
                column_mappings.push(ColumnMapping {
                    source_table: src_table.clone(),
                    source_column: src_col.clone(),
                    target_table: tgt_table.clone(),
                    target_column: tgt_col.clone(),
                    transformation: None,
                });
            }
        }

        Ok(ParsedSqlLineage {
            query_id: generate_id("qry"),
            raw_sql: sql.to_string(),
            source_tables: source_tables.into_iter().collect(),
            target_tables: target_tables.into_iter().collect(),
            source_columns,
            target_columns,
            column_mappings,
            parsed_at: now_utc(),
        })
    }

    fn normalize_sql(&self, sql: &str) -> String {
        let mut result = String::new();
        let mut in_string = false;
        let mut string_char = ' ';

        for ch in sql.chars() {
            if in_string {
                if ch == string_char {
                    in_string = false;
                }
                result.push(ch);
            } else if ch == '\'' || ch == '"' {
                in_string = true;
                string_char = ch;
                result.push(ch);
            } else if ch.is_whitespace() {
                if !result.ends_with(' ') {
                    result.push(' ');
                }
            } else {
                result.push(ch.to_ascii_uppercase());
            }
        }

        result.trim().to_string()
    }

    fn extract_from_tables(&self, sql: &str, tables: &mut HashSet<String>) {
        let re = regex::Regex::new(r"(?i)FROM\s+([A-Za-z0-9_.]+(?:\s*,\s*[A-Za-z0-9_.]+)*)").unwrap();
        if let Some(caps) = re.captures(sql) {
            for table in caps[1].split(',') {
                let table = table.trim();
                if !table.is_empty() {
                    tables.insert(table.to_string());
                }
            }
        }
    }

    fn extract_join_tables(&self, sql: &str, tables: &mut HashSet<String>) {
        let re = regex::Regex::new(r"(?i)JOIN\s+([A-Za-z0-9_.]+)").unwrap();
        for caps in re.captures_iter(sql) {
            tables.insert(caps[1].to_string());
        }
    }

    fn extract_insert_target(&self, sql: &str, tables: &mut HashSet<String>) {
        let re = regex::Regex::new(r"(?i)INTO\s+([A-Za-z0-9_.]+)").unwrap();
        if let Some(caps) = re.captures(sql) {
            tables.insert(caps[1].to_string());
        }
    }

    fn extract_update_target(&self, sql: &str, tables: &mut HashSet<String>) {
        let re = regex::Regex::new(r"(?i)UPDATE\s+([A-Za-z0-9_.]+)").unwrap();
        if let Some(caps) = re.captures(sql) {
            tables.insert(caps[1].to_string());
        }
    }

    fn extract_create_target(&self, sql: &str, tables: &mut HashSet<String>) {
        let re = regex::Regex::new(r"(?i)TABLE\s+([A-Za-z0-9_.]+)").unwrap();
        if let Some(caps) = re.captures(sql) {
            tables.insert(caps[1].to_string());
        }
    }

    fn extract_alter_target(&self, sql: &str, tables: &mut HashSet<String>) {
        let re = regex::Regex::new(r"(?i)TABLE\s+([A-Za-z0-9_.]+)").unwrap();
        if let Some(caps) = re.captures(sql) {
            tables.insert(caps[1].to_string());
        }
    }

    fn extract_select_columns(
        &self,
        sql: &str,
        tables: &HashSet<String>,
        columns: &mut Vec<(String, String)>,
    ) {
        let re = regex::Regex::new(r"(?i)SELECT\s+(.*?)\s+FROM").unwrap();
        if let Some(caps) = re.captures(sql) {
            let select_part = &caps[1];

            for col in select_part.split(',') {
                let col = col.trim();
                if col == "*" {
                    for table in tables {
                        columns.push((table.clone(), "*".to_string()));
                    }
                } else {
                    let parts: Vec<&str> = col.split('.').collect();
                    if parts.len() >= 2 {
                        let table = parts[0].to_string();
                        let col_name = parts[1].to_string();
                        columns.push((table, col_name));
                    } else if !tables.is_empty() {
                        let table = tables.iter().next().unwrap().clone();
                        columns.push((table, col.to_string()));
                    }
                }
            }
        }
    }

    fn extract_target_columns(&self, sql: &str, columns: &mut Vec<(String, String)>) {
        let re = regex::Regex::new(r"\(([^)]+)\)").unwrap();
        if let Some(caps) = re.captures(sql) {
            let col_part = &caps[1];
            for col in col_part.split(',') {
                let col = col.trim();
                columns.push(("target".to_string(), col.to_string()));
            }
        }
    }
}

pub struct LineageDagBuilder {
    parsed_queries: DashMap<String, ParsedSqlLineage>,
    graphs: DashMap<String, LineageGraph>,
}

impl LineageDagBuilder {
    pub fn new() -> Self {
        Self {
            parsed_queries: DashMap::new(),
            graphs: DashMap::new(),
        }
    }

    pub fn add_parsed_query(&self, parsed: ParsedSqlLineage) {
        self.parsed_queries.insert(parsed.query_id.clone(), parsed);
    }

    pub fn build_graph(&self, graph_id: Option<&str>) -> AppResult<LineageGraph> {
        let mut nodes = Vec::new();
        let mut edges = Vec::new();
        let mut node_ids = HashMap::new();

        for query in self.parsed_queries.iter() {
            for src_table in &query.source_tables {
                let fqn = src_table.clone();
                if !node_ids.contains_key(&fqn) {
                    let node = self.create_node(src_table, LineageNodeType::Table);
                    node_ids.insert(fqn.clone(), node.node_id.clone());
                    nodes.push(node);
                }
            }

            for tgt_table in &query.target_tables {
                let fqn = tgt_table.clone();
                if !node_ids.contains_key(&fqn) {
                    let node = self.create_node(tgt_table, LineageNodeType::Table);
                    node_ids.insert(fqn.clone(), node.node_id.clone());
                    nodes.push(node);
                }
            }

            for mapping in &query.column_mappings {
                let src_fqn = format!("{}.{}", mapping.source_table, mapping.source_column);
                let tgt_fqn = format!("{}.{}", mapping.target_table, mapping.target_column);

                if !node_ids.contains_key(&src_fqn) {
                    let node = self.create_column_node(&mapping.source_table, &mapping.source_column);
                    node_ids.insert(src_fqn.clone(), node.node_id.clone());
                    nodes.push(node);
                }

                if !node_ids.contains_key(&tgt_fqn) {
                    let node = self.create_column_node(&mapping.target_table, &mapping.target_column);
                    node_ids.insert(tgt_fqn.clone(), node.node_id.clone());
                    nodes.push(node);
                }

                let src_node_id = node_ids.get(&src_fqn).unwrap();
                let tgt_node_id = node_ids.get(&tgt_fqn).unwrap();

                edges.push(LineageEdge {
                    edge_id: generate_id("edge"),
                    source_node_id: src_node_id.clone(),
                    target_node_id: tgt_node_id.clone(),
                    edge_type: LineageEdgeType::Transform,
                    transformation_logic: mapping.transformation.clone(),
                    sql_query: Some(query.raw_sql.clone()),
                });
            }

            for src_table in &query.source_tables {
                for tgt_table in &query.target_tables {
                    let src_node_id = node_ids.get(src_table).unwrap();
                    let tgt_node_id = node_ids.get(tgt_table).unwrap();

                    edges.push(LineageEdge {
                        edge_id: generate_id("edge"),
                        source_node_id: src_node_id.clone(),
                        target_node_id: tgt_node_id.clone(),
                        edge_type: LineageEdgeType::Select,
                        transformation_logic: None,
                        sql_query: Some(query.raw_sql.clone()),
                    });
                }
            }
        }

        let graph = LineageGraph {
            graph_id: graph_id.unwrap_or(&generate_id("graph")).to_string(),
            nodes,
            edges,
            created_at: now_utc(),
        };

        self.graphs.insert(graph.graph_id.clone(), graph.clone());

        Ok(graph)
    }

    fn create_node(&self, name: &str, node_type: LineageNodeType) -> LineageNode {
        LineageNode {
            node_id: generate_id("node"),
            node_type,
            name: name.to_string(),
            fully_qualified_name: name.to_string(),
            metadata: HashMap::new(),
        }
    }

    fn create_column_node(&self, table: &str, column: &str) -> LineageNode {
        let fqn = format!("{}.{}", table, column);
        LineageNode {
            node_id: generate_id("node"),
            node_type: LineageNodeType::Column,
            name: column.to_string(),
            fully_qualified_name: fqn,
            metadata: {
                let mut meta = HashMap::new();
                meta.insert("table".to_string(), serde_json::json!(table));
                meta
            },
        }
    }

    pub fn get_graph(&self, graph_id: &str) -> Option<LineageGraph> {
        self.graphs.get(graph_id).map(|g| g.clone())
    }

    pub fn list_graphs(&self) -> Vec<LineageGraph> {
        self.graphs.iter().map(|g| g.clone()).collect()
    }

    pub fn get_upstream_nodes(&self, graph_id: &str, node_fqn: &str) -> AppResult<Vec<LineageNode>> {
        let graph = self
            .get_graph(graph_id)
            .ok_or_else(|| AppError::NotFound(format!("图谱不存在: {}", graph_id)))?;

        let (dag, node_index_map) = self.build_petgraph(&graph);

        let start_idx = node_index_map
            .get(node_fqn)
            .ok_or_else(|| AppError::NotFound(format!("节点不存在: {}", node_fqn)))?;

        let mut upstream = Vec::new();
        let mut visited = HashSet::new();

        self.traverse_dag(&dag, *start_idx, Direction::Incoming, &mut visited, &graph, &mut upstream);

        Ok(upstream)
    }

    pub fn get_downstream_nodes(&self, graph_id: &str, node_fqn: &str) -> AppResult<Vec<LineageNode>> {
        let graph = self
            .get_graph(graph_id)
            .ok_or_else(|| AppError::NotFound(format!("图谱不存在: {}", graph_id)))?;

        let (dag, node_index_map) = self.build_petgraph(&graph);

        let start_idx = node_index_map
            .get(node_fqn)
            .ok_or_else(|| AppError::NotFound(format!("节点不存在: {}", node_fqn)))?;

        let mut downstream = Vec::new();
        let mut visited = HashSet::new();

        self.traverse_dag(&dag, *start_idx, Direction::Outgoing, &mut visited, &graph, &mut downstream);

        Ok(downstream)
    }

    fn build_petgraph(
        &self,
        graph: &LineageGraph,
    ) -> (DiGraph<String, String>, HashMap<String, NodeIndex>) {
        let mut dag = DiGraph::new();
        let mut node_index_map = HashMap::new();

        for node in &graph.nodes {
            let idx = dag.add_node(node.fully_qualified_name.clone());
            node_index_map.insert(node.fully_qualified_name.clone(), idx);
        }

        for edge in &graph.edges {
            if let (Some(&src_idx), Some(&tgt_idx)) = (
                node_index_map.get(&edge.source_node_id),
                node_index_map.get(&edge.target_node_id),
            ) {
                dag.add_edge(src_idx, tgt_idx, edge.edge_id.clone());
            }
        }

        (dag, node_index_map)
    }

    fn traverse_dag(
        &self,
        dag: &DiGraph<String, String>,
        start_idx: NodeIndex,
        direction: Direction,
        visited: &mut HashSet<NodeIndex>,
        graph: &LineageGraph,
        results: &mut Vec<LineageNode>,
    ) {
        if visited.contains(&start_idx) {
            return;
        }
        visited.insert(start_idx);

        let node_name = &dag[start_idx];
        if let Some(node) = graph
            .nodes
            .iter()
            .find(|n| n.fully_qualified_name == *node_name)
        {
            results.push(node.clone());
        }

        for neighbor in dag.neighbors_directed(start_idx, direction) {
            self.traverse_dag(dag, neighbor, direction, visited, graph, results);
        }
    }

    pub fn find_cycles(&self, graph_id: &str) -> AppResult<Vec<Vec<String>>> {
        let graph = self
            .get_graph(graph_id)
            .ok_or_else(|| AppError::NotFound(format!("图谱不存在: {}", graph_id)))?;

        let (dag, _) = self.build_petgraph(&graph);

        let mut cycles = Vec::new();
        let mut visited = HashSet::new();
        let mut path = Vec::new();

        for node_idx in dag.node_indices() {
            if !visited.contains(&node_idx) {
                self.detect_cycle(&dag, node_idx, &mut visited, &mut path, &mut cycles);
            }
        }

        Ok(cycles)
    }

    fn detect_cycle(
        &self,
        dag: &DiGraph<String, String>,
        start_idx: NodeIndex,
        visited: &mut HashSet<NodeIndex>,
        path: &mut Vec<String>,
        cycles: &mut Vec<Vec<String>>,
    ) {
        visited.insert(start_idx);
        path.push(dag[start_idx].clone());

        for neighbor in dag.neighbors_directed(start_idx, Direction::Outgoing) {
            if path.contains(&dag[neighbor]) {
                if let Some(pos) = path.iter().position(|x| x == &dag[neighbor]) {
                    let cycle: Vec<String> = path[pos..].to_vec();
                    cycles.push(cycle);
                }
            } else if !visited.contains(&neighbor) {
                self.detect_cycle(dag, neighbor, visited, path, cycles);
            }
        }

        path.pop();
    }
}

impl Default for LineageDagBuilder {
    fn default() -> Self {
        Self::new()
    }
}

pub struct LineageManager {
    parser: SqlLineageParser,
    dag_builder: LineageDagBuilder,
    config: LineageConfig,
}

impl LineageManager {
    pub fn new(config: LineageConfig) -> Self {
        Self {
            parser: SqlLineageParser::new(config.clone()),
            dag_builder: LineageDagBuilder::new(),
            config,
        }
    }

    pub fn parse_sql(&self, sql: &str) -> AppResult<ParsedSqlLineage> {
        let parsed = self.parser.parse_sql(sql)?;

        if self.config.store_parsed_queries {
            self.dag_builder.add_parsed_query(parsed.clone());
        }

        Ok(parsed)
    }

    pub async fn parse_sql_async(&self, sql: &str) -> AppResult<ParsedSqlLineage> {
        let sql = sql.to_string();
        let parser = self.parser.clone();
        let store = self.config.store_parsed_queries;
        let builder = self.dag_builder.clone();

        let parsed = tokio::task::spawn_blocking(move || parser.parse_sql(&sql))
            .await
            .map_err(|e| AppError::LineageError(format!("解析任务失败: {}", e)))??;

        if store {
            builder.add_parsed_query(parsed.clone());
        }

        Ok(parsed)
    }

    pub fn build_dag(&self, graph_id: Option<&str>) -> AppResult<LineageGraph> {
        if !self.config.build_dag {
            return Err(AppError::LineageError(
                "DAG构建未启用".to_string(),
            ));
        }

        self.dag_builder.build_graph(graph_id)
    }

    pub fn get_graph(&self, graph_id: &str) -> Option<LineageGraph> {
        self.dag_builder.get_graph(graph_id)
    }

    pub fn list_graphs(&self) -> Vec<LineageGraph> {
        self.dag_builder.list_graphs()
    }

    pub fn get_upstream(&self, graph_id: &str, node_fqn: &str) -> AppResult<Vec<LineageNode>> {
        self.dag_builder.get_upstream_nodes(graph_id, node_fqn)
    }

    pub fn get_downstream(&self, graph_id: &str, node_fqn: &str) -> AppResult<Vec<LineageNode>> {
        self.dag_builder.get_downstream_nodes(graph_id, node_fqn)
    }

    pub fn find_cycles(&self, graph_id: &str) -> AppResult<Vec<Vec<String>>> {
        self.dag_builder.find_cycles(graph_id)
    }

    pub fn parser(&self) -> &SqlLineageParser {
        &self.parser
    }

    pub fn dag_builder(&self) -> &LineageDagBuilder {
        &self.dag_builder
    }
}

pub fn create_lineage_manager(config: LineageConfig) -> LineageManager {
    LineageManager::new(config)
}
