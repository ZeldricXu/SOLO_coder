use crate::models::StreamSQLError;
use super::graph::{LineageGraph, TableReference};
use std::collections::{HashMap, HashSet, VecDeque};

#[derive(Debug, Clone, Serialize)]
pub struct GraphAnalysis {
    pub total_nodes: usize,
    pub total_edges: usize,
    pub total_tables: usize,
    pub total_columns: usize,
    pub isolated_nodes: Vec<String>,
    pub cycles: Vec<Vec<String>>,
    pub longest_path: Vec<String>,
    pub table_dependencies: HashMap<String, Vec<String>>,
}

pub struct LineageAnalyzer;

impl LineageAnalyzer {
    pub fn analyze(graph: &LineageGraph) -> GraphAnalysis {
        let tables = graph.get_all_tables();
        
        let mut table_deps = HashMap::new();
        for table in &tables {
            let deps = Self::get_direct_dependencies(graph, table);
            table_deps.insert(table.fully_qualified(), deps);
        }

        GraphAnalysis {
            total_nodes: graph.node_count(),
            total_edges: graph.edge_count(),
            total_tables: tables.len(),
            total_columns: 0,
            isolated_nodes: Self::find_isolated_nodes(graph),
            cycles: Self::find_cycles(graph),
            longest_path: Self::find_longest_path(graph),
            table_dependencies: table_deps,
        }
    }

    fn get_direct_dependencies(graph: &LineageGraph, table: &TableReference) -> Vec<String> {
        let node_id = table.fully_qualified();
        graph
            .get_upstream(&node_id)
            .into_iter()
            .map(|n| n.id)
            .collect()
    }

    fn find_isolated_nodes(graph: &LineageGraph) -> Vec<String> {
        let mut isolated = Vec::new();
        
        for table in graph.get_all_tables() {
            let node_id = table.fully_qualified();
            let upstream = graph.get_upstream(&node_id);
            let downstream = graph.get_downstream(&node_id);
            
            if upstream.is_empty() && downstream.is_empty() {
                isolated.push(node_id);
            }
        }
        
        isolated
    }

    fn find_cycles(graph: &LineageGraph) -> Vec<Vec<String>> {
        let mut cycles = Vec::new();
        let mut visited = HashSet::new();
        let mut rec_stack = HashSet::new();
        
        for table in graph.get_all_tables() {
            let node_id = table.fully_qualified();
            let mut path = Vec::new();
            
            if Self::dfs_cycle_detection(
                graph,
                &node_id,
                &mut visited,
                &mut rec_stack,
                &mut path,
                &mut cycles,
            ) {
            }
        }
        
        cycles
    }

    fn dfs_cycle_detection(
        graph: &LineageGraph,
        node_id: &str,
        visited: &mut HashSet<String>,
        rec_stack: &mut HashSet<String>,
        path: &mut Vec<String>,
        cycles: &mut Vec<Vec<String>>,
    ) -> bool {
        if rec_stack.contains(node_id) {
            if let Some(idx) = path.iter().position(|p| p == node_id) {
                let cycle = path[idx..].to_vec();
                if !cycles.contains(&cycle) {
                    cycles.push(cycle);
                }
            }
            return true;
        }
        
        if visited.contains(node_id) {
            return false;
        }
        
        visited.insert(node_id.to_string());
        rec_stack.insert(node_id.to_string());
        path.push(node_id.to_string());
        
        for downstream in graph.get_downstream(node_id) {
            if Self::dfs_cycle_detection(graph, &downstream.id, visited, rec_stack, path, cycles) {
                return true;
            }
        }
        
        path.pop();
        rec_stack.remove(node_id);
        false
    }

    fn find_longest_path(graph: &LineageGraph) -> Vec<String> {
        let mut longest = Vec::new();
        let tables = graph.get_all_tables();
        
        for table in &tables {
            let node_id = table.fully_qualified();
            let path = Self::bfs_longest_path(graph, &node_id);
            
            if path.len() > longest.len() {
                longest = path;
            }
        }
        
        longest
    }

    fn bfs_longest_path(graph: &LineageGraph, start: &str) -> Vec<String> {
        let mut queue = VecDeque::new();
        let mut distances = HashMap::new();
        let mut parents = HashMap::new();
        
        queue.push_back(start.to_string());
        distances.insert(start.to_string(), 0);
        
        while let Some(current) = queue.pop_front() {
            let current_dist = distances[&current];
            
            for downstream in graph.get_downstream(&current) {
                let new_dist = current_dist + 1;
                
                if !distances.contains_key(&downstream.id) || new_dist > distances[&downstream.id] {
                    distances.insert(downstream.id.clone(), new_dist);
                    parents.insert(downstream.id.clone(), current.clone());
                    queue.push_back(downstream.id);
                }
            }
        }
        
        let mut max_dist = 0;
        let mut end_node = start.to_string();
        
        for (node, dist) in &distances {
            if *dist > max_dist {
                max_dist = *dist;
                end_node = node.clone();
            }
        }
        
        let mut path = Vec::new();
        let mut current = end_node;
        
        while let Some(parent) = parents.get(&current) {
            path.push(current.clone());
            current = parent.clone();
        }
        path.push(start.to_string());
        path.reverse();
        
        path
    }

    pub fn topological_sort(graph: &LineageGraph) -> Result<Vec<String>, StreamSQLError> {
        let mut in_degree = HashMap::new();
        let tables = graph.get_all_tables();
        
        for table in &tables {
            let node_id = table.fully_qualified();
            in_degree.insert(node_id.clone(), graph.get_upstream(&node_id).len());
        }
        
        let mut queue: VecDeque<String> = in_degree
            .iter()
            .filter(|(_, &deg)| deg == 0)
            .map(|(id, _)| id.clone())
            .collect();
        
        let mut result = Vec::new();
        
        while let Some(node) = queue.pop_front() {
            result.push(node.clone());
            
            for downstream in graph.get_downstream(&node) {
                if let Some(deg) = in_degree.get_mut(&downstream.id) {
                    *deg -= 1;
                    if *deg == 0 {
                        queue.push_back(downstream.id);
                    }
                }
            }
        }
        
        if result.len() != tables.len() {
            return Err(StreamSQLError::Lineage(
                "Graph contains cycles, cannot perform topological sort".into(),
            ));
        }
        
        Ok(result)
    }

    pub fn get_execution_order(graph: &LineageGraph) -> Result<Vec<Vec<String>>, StreamSQLError> {
        let mut levels = Vec::new();
        let mut in_degree = HashMap::new();
        let tables = graph.get_all_tables();
        
        for table in &tables {
            let node_id = table.fully_qualified();
            in_degree.insert(node_id.clone(), graph.get_upstream(&node_id).len());
        }
        
        let mut current_level: Vec<String> = in_degree
            .iter()
            .filter(|(_, &deg)| deg == 0)
            .map(|(id, _)| id.clone())
            .collect();
        
        while !current_level.is_empty() {
            let next_ids: Vec<String> = current_level
                .iter()
                .flat_map(|id| graph.get_downstream(id).into_iter().map(|n| n.id))
                .collect();
            
            for id in &next_ids {
                if let Some(deg) = in_degree.get_mut(id) {
                    *deg -= 1;
                }
            }
            
            let next_level: Vec<String> = in_degree
                .iter()
                .filter(|(_, &deg)| deg == 0 && !current_level.contains(_.0))
                .map(|(id, _)| id.clone())
                .collect();
            
            levels.push(current_level);
            current_level = next_level;
        }
        
        Ok(levels)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::data_lineage::LineageExtractor;

    #[test]
    fn test_analyze() {
        let extractor = LineageExtractor::new();
        let sqls = vec![
            "INSERT INTO table_b SELECT * FROM table_a".to_string(),
            "INSERT INTO table_c SELECT * FROM table_b".to_string(),
        ];
        
        let graph = extractor.build_graph(&sqls).unwrap();
        let analysis = LineageAnalyzer::analyze(&graph);
        
        assert!(analysis.total_nodes >= 3);
        assert!(analysis.total_tables >= 3);
    }

    #[test]
    fn test_topological_sort() {
        let extractor = LineageExtractor::new();
        let sqls = vec![
            "INSERT INTO table_b SELECT * FROM table_a".to_string(),
            "INSERT INTO table_c SELECT * FROM table_b".to_string(),
        ];
        
        let graph = extractor.build_graph(&sqls).unwrap();
        let order = LineageAnalyzer::topological_sort(&graph);
        
        assert!(order.is_ok());
    }
}
