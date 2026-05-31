pub mod graph;
pub mod parser;
pub mod extractor;
pub mod analyzer;
pub mod batch;

pub use graph::*;
pub use parser::*;
pub use extractor::*;
pub use analyzer::*;
pub use batch::*;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_builder::TestDataBuilder;
    use std::sync::Arc;
    use tokio::sync::Mutex;
    use std::collections::{HashSet, HashMap};

    #[test]
    fn test_simple_lineage_extraction() {
        let builder = TestDataBuilder::lineage();
        let extractor = LineageExtractor::new();

        let sql = builder.create_simple_sql("target", "source");
        let result = extractor.extract_from_sql(&sql).unwrap();

        assert_eq!(result.source_tables.len(), 1);
        assert_eq!(result.target_tables.len(), 1);
        assert_eq!(result.source_tables[0].table, "source");
        assert_eq!(result.target_tables[0].table, "target");
    }

    #[test]
    fn test_graph_construction() {
        let builder = TestDataBuilder::lineage();
        let extractor = LineageExtractor::new();

        let sqls = builder.create_chain_sqls(&["table_a", "table_b", "table_c", "table_d"]);
        let graph = extractor.build_graph(&sqls).unwrap();

        assert!(graph.node_count() >= 4);
        assert!(graph.edge_count() >= 3);

        let tables = graph.get_all_tables();
        let table_names: HashSet<&str> = tables.iter().map(|t| t.table.as_str()).collect();
        assert!(table_names.contains("table_a"));
        assert!(table_names.contains("table_b"));
        assert!(table_names.contains("table_c"));
        assert!(table_names.contains("table_d"));
    }

    #[test]
    fn test_linear_dag_structure() {
        let builder = TestDataBuilder::lineage();
        let graph = builder.create_linear_dag_graph();

        let table_a = builder.create_table_reference("table_a");
        let table_b = builder.create_table_reference("table_b");
        let table_c = builder.create_table_reference("table_c");
        let table_d = builder.create_table_reference("table_d");

        let downstream_a = graph.get_downstream(&table_a.fully_qualified());
        let upstream_d = graph.get_upstream(&table_d.fully_qualified());

        assert!(!downstream_a.is_empty());
        assert!(!upstream_d.is_empty());
    }

    #[test]
    fn test_fan_out_structure() {
        let builder = TestDataBuilder::lineage();
        let graph = builder.create_fan_out_graph();

        let table_a = builder.create_table_reference("table_a");
        let downstream = graph.get_downstream(&table_a.fully_qualified());

        assert!(downstream.len() >= 3);
    }

    #[test]
    fn test_topological_sort() {
        let builder = TestDataBuilder::lineage();
        let graph = builder.create_linear_dag_graph();

        let order = LineageAnalyzer::topological_sort(&graph).unwrap();

        let table_a = builder.create_table_reference("table_a");
        let table_d = builder.create_table_reference("table_d");

        let pos_a = order.iter().position(|s| s == &table_a.fully_qualified()).unwrap();
        let pos_d = order.iter().position(|s| s == &table_d.fully_qualified()).unwrap();

        assert!(pos_a < pos_d);
    }

    #[test]
    fn test_cycle_detection() {
        let builder = TestDataBuilder::lineage();
        let extractor = LineageExtractor::new();

        let sqls = builder.create_cyclic_sqls();
        let graph = extractor.build_graph(&sqls).unwrap();

        let analysis = LineageAnalyzer::analyze(&graph);
        assert!(!analysis.cycles.is_empty());
    }

    #[test]
    fn test_topological_sort_cyclic_fails() {
        let builder = TestDataBuilder::lineage();
        let extractor = LineageExtractor::new();

        let sqls = builder.create_cyclic_sqls();
        let graph = extractor.build_graph(&sqls).unwrap();

        let result = LineageAnalyzer::topological_sort(&graph);
        assert!(result.is_err());
    }

    #[test]
    fn test_path_finding() {
        let builder = TestDataBuilder::lineage();
        let graph = builder.create_linear_dag_graph();

        let table_a = builder.create_table_reference("table_a");
        let table_d = builder.create_table_reference("table_d");

        let paths = graph.find_paths(&table_a.fully_qualified(), &table_d.fully_qualified());

        assert!(!paths.is_empty());
        for path in &paths {
            assert_eq!(path.first().unwrap(), &table_a.fully_qualified());
            assert_eq!(path.last().unwrap(), &table_d.fully_qualified());
        }
    }

    #[test]
    fn test_impact_analysis() {
        let builder = TestDataBuilder::lineage();
        let extractor = LineageExtractor::new();
        let graph = builder.create_linear_dag_graph();

        let table_b = builder.create_table_reference("table_b");
        let impacted = extractor.get_impact_analysis(&graph, &table_b.table);

        assert!(!impacted.is_empty());
    }

    #[test]
    fn test_execution_order() {
        let builder = TestDataBuilder::lineage();
        let graph = builder.create_linear_dag_graph();

        let levels = LineageAnalyzer::get_execution_order(&graph).unwrap();

        assert!(!levels.is_empty());
        assert!(levels.len() >= 2);
    }

    #[test]
    fn test_graph_isolation() {
        let builder = TestDataBuilder::lineage();
        let extractor = LineageExtractor::new();

        let sqls1 = builder.create_chain_sqls(&["a1", "a2", "a3"]);
        let sqls2 = builder.create_chain_sqls(&["b1", "b2", "b3"]);

        let graph1 = extractor.build_graph(&sqls1).unwrap();
        let graph2 = extractor.build_graph(&sqls2).unwrap();

        let tables1: HashSet<_> = graph1.get_all_tables().iter().map(|t| t.table.clone()).collect();
        let tables2: HashSet<_> = graph2.get_all_tables().iter().map(|t| t.table.clone()).collect();

        assert!(tables1.contains("a1") && tables1.contains("a2") && tables1.contains("a3"));
        assert!(tables2.contains("b1") && tables2.contains("b2") && tables2.contains("b3"));
        assert!(tables1.intersection(&tables2).next().is_none());
    }

    #[tokio::test]
    async fn test_concurrent_graph_construction() {
        let builder = TestDataBuilder::lineage();
        let extractor = Arc::new(LineageExtractor::new());

        let results: Arc<Mutex<Vec<LineageGraph>>> = Arc::new(Mutex::new(Vec::new()));

        let mut handles = Vec::new();
        for i in 0..5 {
            let extractor_clone = extractor.clone();
            let results_clone = results.clone();
            let sqls = builder.create_concurrent_test_sqls(&format!("workflow_{}", i), 3);

            handles.push(tokio::spawn(async move {
                let graph = extractor_clone.build_graph(&sqls).unwrap();
                results_clone.lock().await.push(graph);
            }));
        }

        for handle in handles {
            handle.await.unwrap();
        }

        let all_results = results.lock().await;
        assert_eq!(all_results.len(), 5);

        for (i, graph) in all_results.iter().enumerate() {
            let tables = graph.get_all_tables();
            let expected_prefix = format!("workflow_{}", i);
            for table in tables {
                assert!(
                    table.table.starts_with(&expected_prefix) || table.table == format!("{}_source", expected_prefix),
                    "Table {} should belong to workflow_{}", table.table, i
                );
            }
        }
    }

    #[tokio::test]
    async fn test_concurrent_analysis_isolation() {
        let builder = TestDataBuilder::lineage();

        let graph1 = builder.create_linear_dag_graph();
        let graph2 = builder.create_fan_out_graph();

        let analysis1 = tokio::spawn(async move {
            LineageAnalyzer::analyze(&graph1)
        });

        let analysis2 = tokio::spawn(async move {
            LineageAnalyzer::analyze(&graph2)
        });

        let (result1, result2) = tokio::join!(analysis1, analysis2);

        let a1 = result1.unwrap();
        let a2 = result2.unwrap();

        assert!(a1.longest_path.len() >= 2);
        assert!(a2.total_tables >= 4);
    }

    #[tokio::test]
    async fn test_concurrent_path_queries() {
        let builder = TestDataBuilder::lineage();
        let graph = Arc::new(builder.create_linear_dag_graph());

        let table_a = builder.create_table_reference("table_a");
        let table_d = builder.create_table_reference("table_d");
        let table_b = builder.create_table_reference("table_b");
        let table_c = builder.create_table_reference("table_c");

        let queries = vec![
            (table_a.fully_qualified(), table_d.fully_qualified()),
            (table_a.fully_qualified(), table_c.fully_qualified()),
            (table_b.fully_qualified(), table_d.fully_qualified()),
        ];

        let mut handles = Vec::new();
        for (source, target) in queries {
            let graph_clone = graph.clone();
            handles.push(tokio::spawn(async move {
                graph_clone.find_paths(&source, &target)
            }));
        }

        for handle in handles {
            let paths = handle.await.unwrap();
            assert!(!paths.is_empty());
        }
    }

    #[test]
    fn test_graph_analysis_consistency() {
        let builder = TestDataBuilder::lineage();
        let graph = builder.create_linear_dag_graph();

        let analysis1 = LineageAnalyzer::analyze(&graph);
        let analysis2 = LineageAnalyzer::analyze(&graph);

        assert_eq!(analysis1.total_nodes, analysis2.total_nodes);
        assert_eq!(analysis1.total_edges, analysis2.total_edges);
        assert_eq!(analysis1.total_tables, analysis2.total_tables);
        assert_eq!(analysis1.longest_path, analysis2.longest_path);
    }

    #[test]
    fn test_complex_pipeline_analysis() {
        let builder = TestDataBuilder::lineage();
        let extractor = LineageExtractor::new();

        let sqls = builder.create_complex_pipeline_sqls();
        let graph = extractor.build_graph(&sqls).unwrap();

        let analysis = LineageAnalyzer::analyze(&graph);

        assert!(analysis.total_tables >= 5);
        assert!(!analysis.longest_path.is_empty());
    }

    #[test]
    fn test_upstream_downstream_direction() {
        let builder = TestDataBuilder::lineage();
        let graph = builder.create_linear_dag_graph();

        let table_a = builder.create_table_reference("table_a");
        let table_b = builder.create_table_reference("table_b");
        let table_c = builder.create_table_reference("table_c");

        let downstream_a: HashSet<_> = graph.get_downstream(&table_a.fully_qualified())
            .iter().map(|n| n.id.clone()).collect();
        let upstream_c: HashSet<_> = graph.get_upstream(&table_c.fully_qualified())
            .iter().map(|n| n.id.clone()).collect();

        assert!(downstream_a.contains(&table_b.fully_qualified()));
        assert!(upstream_c.contains(&table_b.fully_qualified()));
    }

    #[tokio::test]
    async fn test_isolated_workflow_modification() {
        let builder = TestDataBuilder::lineage();
        let extractor = Arc::new(LineageExtractor::new());

        let workflow1_sqls = builder.create_concurrent_test_sqls("w1", 2);
        let workflow2_sqls = builder.create_concurrent_test_sqls("w2", 2);

        let graph1 = extractor.build_graph(&workflow1_sqls).unwrap();
        let graph2 = extractor.build_graph(&workflow2_sqls).unwrap();

        let w1_tables_initial: HashSet<_> = graph1.get_all_tables().iter()
            .map(|t| t.table.clone()).collect();
        let w2_tables_initial: HashSet<_> = graph2.get_all_tables().iter()
            .map(|t| t.table.clone()).collect();

        assert_eq!(w1_tables_initial.len(), 3);
        assert_eq!(w2_tables_initial.len(), 3);

        let extractor_clone = extractor.clone();
        let handle = tokio::spawn(async move {
            let new_sqls = vec![
                "INSERT INTO w1_extra SELECT * FROM w1_source".to_string()
            ];
            let mut all_sqls = workflow1_sqls.clone();
            all_sqls.extend(new_sqls);
            extractor_clone.build_graph(&all_sqls)
        });

        let updated_graph = handle.await.unwrap().unwrap();
        let w1_tables_updated: HashSet<_> = updated_graph.get_all_tables().iter()
            .map(|t| t.table.clone()).collect();

        let w2_tables_final: HashSet<_> = graph2.get_all_tables().iter()
            .map(|t| t.table.clone()).collect();

        assert!(w1_tables_updated.contains("w1_extra"));
        assert_eq!(w2_tables_initial, w2_tables_final);
    }

    #[test]
    fn test_node_uniqueness() {
        let builder = TestDataBuilder::lineage();
        let extractor = LineageExtractor::new();

        let sqls = vec![
            "INSERT INTO table_b SELECT * FROM table_a".to_string(),
            "INSERT INTO table_b SELECT * FROM table_a".to_string(),
            "INSERT INTO table_b SELECT * FROM table_a".to_string(),
        ];

        let graph = extractor.build_graph(&sqls).unwrap();

        let tables = graph.get_all_tables();
        let unique_tables: HashSet<_> = tables.iter().map(|t| t.fully_qualified()).collect();

        assert_eq!(tables.len(), unique_tables.len());
        assert_eq!(tables.len(), 2);
    }
}
