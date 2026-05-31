use std::collections::HashMap;

use anyhow::Result;
use petgraph::algo::{kosaraju_scc, toposort};
use petgraph::graph::{DiGraph, NodeIndex};
use rusqlite::Connection;
use uuid::Uuid;

use crate::models::{DependencyRelation, DependencyType};

pub struct DependencyGraph {
    conn: Connection,
}

impl DependencyGraph {
    pub fn new(conn: Connection) -> Result<Self> {
        let graph = Self { conn };
        graph.init_schema()?;
        Ok(graph)
    }

    fn init_schema(&self) -> Result<()> {
        self.conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS dependencies (
                source_id TEXT NOT NULL,
                target_id TEXT NOT NULL,
                dep_type TEXT NOT NULL,
                version_constraint TEXT NOT NULL,
                PRIMARY KEY (source_id, target_id, dep_type)
            );",
        )?;
        Ok(())
    }

    pub fn add_dependency(&self, rel: DependencyRelation) -> Result<()> {
        let dep_type_str = match &rel.dep_type {
            DependencyType::Runtime => "Runtime",
            DependencyType::Build => "Build",
            DependencyType::Dev => "Dev",
            DependencyType::Optional => "Optional",
        };
        self.conn.execute(
            "INSERT OR REPLACE INTO dependencies (source_id, target_id, dep_type, version_constraint) VALUES (?1, ?2, ?3, ?4)",
            rusqlite::params![
                rel.source_id.to_string(),
                rel.target_id.to_string(),
                dep_type_str,
                rel.version_constraint,
            ],
        )?;
        Ok(())
    }

    pub fn remove_dependency(&self, source: Uuid, target: Uuid) -> Result<()> {
        let rows = self.conn.execute(
            "DELETE FROM dependencies WHERE source_id=?1 AND target_id=?2",
            rusqlite::params![source.to_string(), target.to_string()],
        )?;
        if rows == 0 {
            anyhow::bail!("dependency not found: {} -> {}", source, target);
        }
        Ok(())
    }

    pub fn get_dependencies(&self, id: Uuid) -> Result<Vec<DependencyRelation>> {
        let mut stmt = self.conn.prepare(
            "SELECT source_id, target_id, dep_type, version_constraint FROM dependencies WHERE source_id=?1",
        )?;
        let relations = stmt
            .query_map(rusqlite::params![id.to_string()], |row| {
                Ok(row_to_dependency_relation(row))
            })?
            .map(|r| r?)
            .collect::<Result<Vec<DependencyRelation>, _>>()?;
        Ok(relations)
    }

    pub fn get_dependents(&self, id: Uuid) -> Result<Vec<DependencyRelation>> {
        let mut stmt = self.conn.prepare(
            "SELECT source_id, target_id, dep_type, version_constraint FROM dependencies WHERE target_id=?1",
        )?;
        let relations = stmt
            .query_map(rusqlite::params![id.to_string()], |row| {
                Ok(row_to_dependency_relation(row))
            })?
            .map(|r| r?)
            .collect::<Result<Vec<DependencyRelation>, _>>()?;
        Ok(relations)
    }

    pub fn detect_cycles(&self) -> Result<Vec<Vec<Uuid>>> {
        let graph = self.build_graph()?;
        let sccs = kosaraju_scc(&graph);
        let cycles: Vec<Vec<Uuid>> = sccs
            .into_iter()
            .filter(|scc| scc.len() > 1)
            .map(|scc| {
                scc.into_iter()
                    .filter_map(|idx| graph.node_weight(idx).copied())
                    .collect()
            })
            .collect();
        Ok(cycles)
    }

    pub fn topological_sort(&self) -> Result<Vec<Uuid>> {
        let graph = self.build_graph()?;
        match toposort(&graph, None) {
            Ok(sorted) => {
                let result: Vec<Uuid> = sorted
                    .into_iter()
                    .filter_map(|idx| graph.node_weight(idx).copied())
                    .collect();
                Ok(result)
            }
            Err(_) => {
                anyhow::bail!("cycle detected in dependency graph");
            }
        }
    }

    fn build_graph(&self) -> Result<DiGraph<Uuid, ()>> {
        let mut graph = DiGraph::new();
        let mut node_map: HashMap<Uuid, NodeIndex> = HashMap::new();

        let mut stmt = self.conn.prepare(
            "SELECT source_id, target_id FROM dependencies",
        )?;
        let rows: Vec<(Uuid, Uuid)> = stmt
            .query_map([], |row| {
                let source_str: String = row.get(0)?;
                let target_str: String = row.get(1)?;
                Ok((source_str, target_str))
            })?
            .map(|r| r.map_err(anyhow::Error::from))
            .map(|r| -> Result<(Uuid, Uuid)> {
                let (s, t) = r?;
                Ok((Uuid::parse_str(&s)?, Uuid::parse_str(&t)?))
            })
            .collect::<Result<Vec<_>, _>>()?;

        for (source, target) in &rows {
            let source_idx = *node_map.entry(*source).or_insert_with(|| graph.add_node(*source));
            let target_idx = *node_map.entry(*target).or_insert_with(|| graph.add_node(*target));
            graph.add_edge(source_idx, target_idx, ());
        }

        Ok(graph)
    }
}

fn row_to_dependency_relation(row: &rusqlite::Row) -> Result<DependencyRelation> {
    let source_str: String = row.get(0)?;
    let target_str: String = row.get(1)?;
    let dep_type_str: String = row.get(2)?;
    let version_constraint: String = row.get(3)?;

    let source_id = Uuid::parse_str(&source_str)?;
    let target_id = Uuid::parse_str(&target_str)?;
    let dep_type = match dep_type_str.as_str() {
        "Runtime" => DependencyType::Runtime,
        "Build" => DependencyType::Build,
        "Dev" => DependencyType::Dev,
        "Optional" => DependencyType::Optional,
        _ => DependencyType::Optional,
    };

    Ok(DependencyRelation {
        source_id,
        target_id,
        dep_type,
        version_constraint,
    })
}
