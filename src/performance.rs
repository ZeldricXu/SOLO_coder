use std::path::PathBuf;
use std::time::{Duration, Instant};
use crate::links::KnowledgeGraph;
use crate::editor::WysiwygEditor;

pub struct PerformanceMetrics {
    pub test_name: String,
    pub duration: Duration,
    pub fps: Option<f32>,
    pub memory_usage: Option<usize>,
}

impl PerformanceMetrics {
    pub fn new(test_name: &str) -> Self {
        Self {
            test_name: test_name.to_string(),
            duration: Duration::from_secs(0),
            fps: None,
            memory_usage: None,
        }
    }

    pub fn measure<F>(&mut self, f: F)
    where
        F: FnOnce(),
    {
        let start = Instant::now();
        f();
        self.duration = start.elapsed();
    }

    pub fn measure_fps<F>(&mut self, frames: usize, mut f: F)
    where
        F: FnMut(),
    {
        let start = Instant::now();
        for _ in 0..frames {
            f();
        }
        self.duration = start.elapsed();
        self.fps = Some(frames as f32 / self.duration.as_secs_f32());
    }

    pub fn is_acceptable(&self) -> bool {
        if let Some(fps) = self.fps {
            fps >= 30.0
        } else {
            self.duration < Duration::from_millis(500)
        }
    }
}

impl std::fmt::Display for PerformanceMetrics {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {:?}", self.test_name, self.duration)?;
        if let Some(fps) = self.fps {
            write!(f, " ({:.1} FPS)", fps)?;
        }
        Ok(())
    }
}

pub fn test_knowledge_graph_performance(node_count: usize) -> PerformanceMetrics {
    let mut metrics = PerformanceMetrics::new(&format!("Knowledge Graph ({} nodes)", node_count));
    
    let mut graph = create_test_graph(node_count);
    
    metrics.measure_fps(60, || {
        graph.apply_force_directed();
    });
    
    metrics
}

pub fn test_wysiwyg_editor_performance(char_count: usize) -> PerformanceMetrics {
    let mut metrics = PerformanceMetrics::new(&format!("WYSIWYG Editor ({} chars)", char_count));
    
    let content = generate_large_document(char_count);
    let mut editor = WysiwygEditor::new();
    
    let ctx = egui::Context::default();
    metrics.measure_fps(60, || {
        editor.update(&content, content.len() / 2, &ctx);
    });
    
    metrics
}

fn create_test_graph(node_count: usize) -> KnowledgeGraph {
    use crate::links::{GraphNode, GraphEdge};
    use std::collections::HashMap;
    
    let mut nodes = HashMap::new();
    let mut edges = Vec::new();
    
    for i in 0..node_count {
        let id = format!("node_{}", i);
        nodes.insert(id.clone(), GraphNode {
            id: id.clone(),
            path: PathBuf::from(format!("{}.md", id)),
            x: (rand::random::<f32>() - 0.5) * 800.0,
            y: (rand::random::<f32>() - 0.5) * 800.0,
            vx: 0.0,
            vy: 0.0,
            size: 15.0 + (i % 20) as f32 * 2.0,
        });
    }
    
    for i in 0..node_count {
        let connections = (i % 5) + 1;
        for j in 0..connections {
            let target = (i * 7 + j * 13) % node_count;
            if i != target {
                edges.push(GraphEdge {
                    source: format!("node_{}", i),
                    target: format!("node_{}", target),
                });
            }
        }
    }
    
    KnowledgeGraph {
        nodes,
        edges,
        center_x: 0.0,
        center_y: 0.0,
        iterations_per_frame: 5,
        max_iterations_per_frame: 50,
        theta: 0.7,
        zoom: 1.0,
    }
}

fn generate_large_document(char_count: usize) -> String {
    let mut content = String::with_capacity(char_count);
    
    let paragraphs = char_count / 500;
    for p in 0..paragraphs {
        if p % 5 == 0 {
            content.push_str(&format!("# Heading Level 1 - Section {}\n\n", p / 5 + 1));
        } else if p % 3 == 0 {
            content.push_str(&format!("## Heading Level 2 - {}\n\n", p / 3 + 1));
        }
        
        for _ in 0..5 {
            content.push_str("This is a **bold** sentence with some *italic* text. ");
            content.push_str("Here is a `code snippet` and a [[wikilink]] to another note. ");
        }
        content.push_str("\n\n");
        
        if p % 4 == 0 {
            content.push_str("```rust\n");
            content.push_str("fn example() {\n");
            content.push_str("    println!(\"Hello, world!\");\n");
            content.push_str("}\n");
            content.push_str("```\n\n");
        }
        
        if p % 6 == 0 {
            content.push_str("| Column 1 | Column 2 | Column 3 |\n");
            content.push_str("|----------|----------|----------|\n");
            content.push_str("| Data A   | Data B   | Data C   |\n");
            content.push_str("| Data D   | Data E   | Data F   |\n\n");
        }
    }
    
    content.truncate(char_count);
    content
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    #[ignore]
    fn test_graph_2000_nodes_performance() {
        let metrics = test_knowledge_graph_performance(2000);
        println!("{}", metrics);
        assert!(metrics.is_acceptable(), "Graph performance too slow: {}", metrics);
    }

    #[test]
    #[ignore]
    fn test_editor_100k_chars_performance() {
        let metrics = test_wysiwyg_editor_performance(100_000);
        println!("{}", metrics);
        assert!(metrics.is_acceptable(), "Editor performance too slow: {}", metrics);
    }

    #[test]
    fn test_graph_100_nodes_quick() {
        let metrics = test_knowledge_graph_performance(100);
        println!("{}", metrics);
        assert!(metrics.duration < Duration::from_secs(5));
    }

    #[test]
    fn test_large_document_generation() {
        let doc = generate_large_document(10_000);
        assert_eq!(doc.len(), 10_000);
        assert!(doc.contains("**bold**"));
        assert!(doc.contains("*italic*"));
        assert!(doc.contains("[[wikilink]]"));
    }
}
