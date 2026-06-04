use std::collections::HashMap;
use std::path::PathBuf;
use egui;

use crate::theme::Theme;
use super::bidirectional::LinkDatabase;

#[derive(Debug, Clone)]
pub struct GraphNode {
    pub id: String,
    pub path: PathBuf,
    pub x: f32,
    pub y: f32,
    pub vx: f32,
    pub vy: f32,
    pub size: f32,
}

#[derive(Debug, Clone)]
pub struct GraphEdge {
    pub source: String,
    pub target: String,
}

struct QuadTreeNode {
    x: f32,
    y: f32,
    width: f32,
    height: f32,
    mass: f32,
    center_x: f32,
    center_y: f32,
    children: [Option<Box<QuadTreeNode>>; 4],
    node_index: Option<usize>,
}

impl QuadTreeNode {
    fn new(x: f32, y: f32, width: f32, height: f32) -> Self {
        Self {
            x,
            y,
            width,
            height,
            mass: 0.0,
            center_x: x + width / 2.0,
            center_y: y + height / 2.0,
            children: [None, None, None, None],
            node_index: None,
        }
    }

    fn is_leaf(&self) -> bool {
        self.children.iter().all(|c| c.is_none())
    }

    fn insert(&mut self, node_idx: usize, nodes: &[&GraphNode]) {
        let node = nodes[node_idx];
        
        if self.mass == 0.0 && self.node_index.is_none() {
            self.node_index = Some(node_idx);
            self.mass = node.size;
            self.center_x = node.x;
            self.center_y = node.y;
            return;
        }

        if self.is_leaf() {
            if let Some(existing_idx) = self.node_index {
                let existing_node = &nodes[existing_idx];
                self.node_index = None;
                
                self.subdivide();
                
                self.insert_into_child(existing_idx, nodes);
            }
        }

        self.mass += node.size;
        self.center_x = (self.center_x * (self.mass - node.size) + node.x * node.size) / self.mass;
        self.center_y = (self.center_y * (self.mass - node.size) + node.y * node.size) / self.mass;

        self.insert_into_child(node_idx, nodes);
    }

    fn subdivide(&mut self) {
        let hw = self.width / 2.0;
        let hh = self.height / 2.0;
        
        self.children[0] = Some(Box::new(QuadTreeNode::new(self.x, self.y, hw, hh)));
        self.children[1] = Some(Box::new(QuadTreeNode::new(self.x + hw, self.y, hw, hh)));
        self.children[2] = Some(Box::new(QuadTreeNode::new(self.x, self.y + hh, hw, hh)));
        self.children[3] = Some(Box::new(QuadTreeNode::new(self.x + hw, self.y + hh, hw, hh)));
    }

    fn insert_into_child(&mut self, node_idx: usize, nodes: &[&GraphNode]) {
        let node = nodes[node_idx];
        let hw = self.width / 2.0;
        let hh = self.height / 2.0;
        
        let quadrant = if node.x < self.x + hw {
            if node.y < self.y + hh { 0 } else { 2 }
        } else {
            if node.y < self.y + hh { 1 } else { 3 }
        };
        
        if let Some(child) = &mut self.children[quadrant] {
            child.insert(node_idx, nodes);
        }
    }
}

#[derive(Debug, Clone)]
pub struct KnowledgeGraph {
    pub nodes: HashMap<String, GraphNode>,
    pub edges: Vec<GraphEdge>,
    pub center_x: f32,
    pub center_y: f32,
    pub iterations_per_frame: usize,
    pub max_iterations_per_frame: usize,
    pub theta: f32,
    pub zoom: f32,
}

impl KnowledgeGraph {
    pub fn from_database(db: &LinkDatabase) -> Self {
        let mut nodes = HashMap::new();
        let mut edges = Vec::new();
        let mut connection_counts = HashMap::new();

        for (path, links) in &db.forward_links {
            let title = db.file_to_title
                .get(path)
                .cloned()
                .unwrap_or_else(|| path.to_string_lossy().to_string());
            
            nodes.entry(title.clone()).or_insert_with(|| GraphNode {
                id: title.clone(),
                path: path.clone(),
                x: (rand::random::<f32>() - 0.5) * 400.0,
                y: (rand::random::<f32>() - 0.5) * 400.0,
                vx: 0.0,
                vy: 0.0,
                size: 20.0,
            });

            for link in links {
                let target_title = link.target.clone();
                
                if let Some(target_path) = &link.target_path {
                    nodes.entry(target_title.clone()).or_insert_with(|| GraphNode {
                        id: target_title.clone(),
                        path: target_path.clone(),
                        x: (rand::random::<f32>() - 0.5) * 400.0,
                        y: (rand::random::<f32>() - 0.5) * 400.0,
                        vx: 0.0,
                        vy: 0.0,
                        size: 20.0,
                    });

                    edges.push(GraphEdge {
                        source: title.clone(),
                        target: target_title.clone(),
                    });

                    *connection_counts.entry(title.clone()).or_insert(0) += 1;
                    *connection_counts.entry(target_title).or_insert(0) += 1;
                }
            }
        }

        for (id, count) in connection_counts {
            if let Some(node) = nodes.get_mut(&id) {
                node.size = 15.0 + (count as f32) * 3.0;
            }
        }

        Self {
            nodes,
            edges,
            center_x: 0.0,
            center_y: 0.0,
            iterations_per_frame: 1,
            max_iterations_per_frame: 50,
            theta: 0.7,
            zoom: 1.0,
        }
    }

    pub fn apply_force_directed(&mut self) {
        let repulsion_strength = 5000.0;
        let attraction_strength = 0.01;
        let gravity_strength = 0.1;
        let damping = 0.9;
        let dt = 0.5;

        let node_ids: Vec<String> = self.nodes.keys().cloned().collect();
        let node_vec: Vec<&GraphNode> = node_ids.iter()
            .filter_map(|id| self.nodes.get(id))
            .collect();

        if node_vec.is_empty() {
            return;
        }

        let mut min_x = f32::MAX;
        let mut max_x = f32::MIN;
        let mut min_y = f32::MAX;
        let mut max_y = f32::MIN;
        
        for node in &node_vec {
            min_x = min_x.min(node.x);
            max_x = max_x.max(node.x);
            min_y = min_y.min(node.y);
            max_y = max_y.max(node.y);
        }
        
        let padding = 100.0;
        let width = (max_x - min_x + padding * 2.0).max(800.0);
        let height = (max_y - min_y + padding * 2.0).max(800.0);

        let mut root = QuadTreeNode::new(min_x - padding, min_y - padding, width, height);
        
        for (i, _) in node_vec.iter().enumerate() {
            root.insert(i, &node_vec);
        }

        for _ in 0..self.iterations_per_frame.min(self.max_iterations_per_frame) {
            for (i, node_id) in node_ids.iter().enumerate() {
                if let Some(node) = self.nodes.get(node_id) {
                    let (fx, fy) = self.calculate_barnes_hut_force(
                        i,
                        node,
                        &root,
                        &node_ids,
                        repulsion_strength,
                    );
                    
                    if let Some(node_mut) = self.nodes.get_mut(node_id) {
                        node_mut.vx += fx;
                        node_mut.vy += fy;
                    }
                }
            }

            for edge in &self.edges {
                if let (Some(source), Some(target)) = (
                    self.nodes.get(&edge.source),
                    self.nodes.get(&edge.target),
                ) {
                    let dx = target.x - source.x;
                    let dy = target.y - source.y;
                    let dist = (dx * dx + dy * dy).sqrt().max(1.0);
                    
                    let force = attraction_strength * dist;
                    let fx = force * dx / dist;
                    let fy = force * dy / dist;
                    
                    if let Some(source) = self.nodes.get_mut(&edge.source) {
                        source.vx += fx;
                        source.vy += fy;
                    }
                    if let Some(target) = self.nodes.get_mut(&edge.target) {
                        target.vx -= fx;
                        target.vy -= fy;
                    }
                }
            }

            for node in self.nodes.values_mut() {
                let dx = self.center_x - node.x;
                let dy = self.center_y - node.y;
                node.vx += dx * gravity_strength;
                node.vy += dy * gravity_strength;
            }

            for node in self.nodes.values_mut() {
                node.vx *= damping;
                node.vy *= damping;
                node.x += node.vx * dt;
                node.y += node.vy * dt;
            }
        }
    }

    fn calculate_barnes_hut_force(
        &self,
        node_idx: usize,
        node: &GraphNode,
        quadtree: &QuadTreeNode,
        node_ids: &[String],
        repulsion_strength: f32,
    ) -> (f32, f32) {
        let mut fx = 0.0;
        let mut fy = 0.0;

        if quadtree.mass == 0.0 {
            return (fx, fy);
        }

        if quadtree.is_leaf() {
            if let Some(other_idx) = quadtree.node_index {
                if other_idx != node_idx {
                    if let Some(other_node) = self.nodes.get(&node_ids[other_idx]) {
                        let dx = other_node.x - node.x;
                        let dy = other_node.y - node.y;
                        let dist = (dx * dx + dy * dy).sqrt().max(10.0);
                        let force = repulsion_strength / (dist * dist);
                        
                        fx -= force * dx / dist;
                        fy -= force * dy / dist;
                    }
                }
            }
        } else {
            let dx = quadtree.center_x - node.x;
            let dy = quadtree.center_y - node.y;
            let dist = (dx * dx + dy * dy).sqrt().max(10.0);
            
            if quadtree.width / dist < self.theta {
                let force = repulsion_strength * quadtree.mass / (dist * dist);
                fx -= force * dx / dist;
                fy -= force * dy / dist;
            } else {
                for child in &quadtree.children {
                    if let Some(child) = child {
                        let (cfx, cfy) = self.calculate_barnes_hut_force(
                            node_idx,
                            node,
                            child,
                            node_ids,
                            repulsion_strength,
                        );
                        fx += cfx;
                        fy += cfy;
                    }
                }
            }
        }

        (fx, fy)
    }

    pub fn calculate_kinetic_energy(&self) -> f32 {
        let mut energy = 0.0;
        for node in self.nodes.values() {
            energy += node.size * (node.vx * node.vx + node.vy * node.vy);
        }
        energy
    }

    pub fn adaptive_iterations(&mut self, target_fps: f32) {
        let node_count = self.nodes.len();
        if node_count > 1000 {
            self.iterations_per_frame = 1;
        } else if node_count > 500 {
            self.iterations_per_frame = 5;
        } else if node_count > 100 {
            self.iterations_per_frame = 10;
        } else {
            self.iterations_per_frame = 20;
        }
    }
}

pub fn render_graph(
    graph: &mut KnowledgeGraph,
    ui: &mut egui::Ui,
    theme: &Theme,
) -> Option<PathBuf> {
    let mut clicked_path = None;
    
    let (rect, response) = ui.allocate_at_least(
        egui::vec2(ui.available_width(), 400.0),
        egui::Sense::click_and_drag(),
    );

    let painter = ui.painter_at(rect);
    let center = rect.center();
    graph.center_x = center.x;
    graph.center_y = center.y;

    graph.adaptive_iterations(60.0);
    graph.apply_force_directed();

    let transform = |x: f32, y: f32| egui::pos2(center.x + x * graph.zoom, center.y + y * graph.zoom);

    for edge in &graph.edges {
        if let (Some(source), Some(target)) = (
            graph.nodes.get(&edge.source),
            graph.nodes.get(&edge.target),
        ) {
            painter.line_segment(
                [transform(source.x, source.y), transform(target.x, target.y)],
                egui::Stroke::new(1.0, theme.border_color),
            );
        }
    }

    let mut hovered_node: Option<&GraphNode> = None;
    let pointer_pos = response.hover_pos();

    let node_list: Vec<&GraphNode> = graph.nodes.values().collect();
    let view_distance = 100.0;

    for node in &node_list {
        let pos = transform(node.x, node.y);
        let node_rect = egui::Rect::from_center_size(pos, egui::vec2(node.size * 2.0, node.size * 2.0));
        
        let connection_count = graph.edges
            .iter()
            .filter(|e| e.source == node.id || e.target == node.id)
            .count();
        
        let color_factor = (connection_count as f32).min(10.0) / 10.0;
        let color = egui::Color32::from_rgb(
            (theme.accent_color.r() as f32 * color_factor + 100.0 * (1.0 - color_factor)) as u8,
            (theme.accent_color.g() as f32 * color_factor + 100.0 * (1.0 - color_factor)) as u8,
            (theme.accent_color.b() as f32 * color_factor + 100.0 * (1.0 - color_factor)) as u8,
        );

        painter.circle(pos, node.size * graph.zoom, color, egui::Stroke::new(2.0, theme.border_color));

        if let Some(pointer) = pointer_pos {
            if node_rect.contains(pointer) {
                hovered_node = Some(node);
            }
        }

        if graph.zoom > 0.8 {
            let dist_to_pointer = if let Some(pointer) = pointer_pos {
                ((pos.x - pointer.x).powi(2) + (pos.y - pointer.y).powi(2)).sqrt()
            } else {
                f32::INFINITY
            };

            if dist_to_pointer < view_distance || node.size > 25.0 {
                painter.text(
                    pos + egui::vec2(0.0, node.size * graph.zoom + 12.0),
                    egui::Align2::CENTER_TOP,
                    &node.id,
                    egui::FontId::proportional((10.0 * graph.zoom).max(8.0)),
                    theme.text_color,
                );
            }
        }
    }

    for node in graph.nodes.values() {
        let pos = transform(node.x, node.y);
        let node_rect = egui::Rect::from_center_size(pos, egui::vec2(node.size * 2.0, node.size * 2.0));
        
        if response.clicked() {
            if let Some(pointer) = response.hover_pos() {
                if node_rect.contains(pointer) {
                    clicked_path = Some(node.path.clone());
                }
            }
        }
    }

    if let Some(node) = hovered_node {
        let pos = transform(node.x, node.y);
        painter.text(
            pos + egui::vec2(0.0, -node.size * graph.zoom - 10.0),
            egui::Align2::CENTER_BOTTOM,
            &node.id,
            egui::FontId::proportional(14.0),
            theme.text_color,
        );
    }

    let node_count = graph.nodes.len();
    let status_text = if node_count > 500 {
        format!("{} nodes (Barnes-Hut optimized)", node_count)
    } else {
        format!("{} nodes", node_count)
    };
    
    painter.text(
        rect.left_top() + egui::vec2(8.0, 8.0),
        egui::Align2::LEFT_TOP,
        status_text,
        egui::FontId::proportional(10.0),
        theme.text_color.linear_multiply(0.7),
    );

    clicked_path
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_quad_tree_creation() {
        let mut nodes = HashMap::new();
        nodes.insert("a".to_string(), GraphNode {
            id: "a".to_string(),
            path: PathBuf::from("a.md"),
            x: -100.0,
            y: -100.0,
            vx: 0.0,
            vy: 0.0,
            size: 20.0,
        });
        nodes.insert("b".to_string(), GraphNode {
            id: "b".to_string(),
            path: PathBuf::from("b.md"),
            x: 100.0,
            y: 100.0,
            vx: 0.0,
            vy: 0.0,
            size: 20.0,
        });

        let mut graph = KnowledgeGraph {
            nodes,
            edges: Vec::new(),
            center_x: 0.0,
            center_y: 0.0,
            iterations_per_frame: 5,
            max_iterations_per_frame: 50,
            theta: 0.7,
            zoom: 1.0,
        };

        graph.apply_force_directed();
        assert!(!graph.nodes.is_empty());
    }

    #[test]
    fn test_adaptive_iterations() {
        let mut graph = KnowledgeGraph {
            nodes: HashMap::new(),
            edges: Vec::new(),
            center_x: 0.0,
            center_y: 0.0,
            iterations_per_frame: 10,
            max_iterations_per_frame: 50,
            theta: 0.7,
            zoom: 1.0,
        };

        for i in 0..1200 {
            graph.nodes.insert(format!("node_{}", i), GraphNode {
                id: format!("node_{}", i),
                path: PathBuf::from(format!("node_{}.md", i)),
                x: (rand::random::<f32>() - 0.5) * 400.0,
                y: (rand::random::<f32>() - 0.5) * 400.0,
                vx: 0.0,
                vy: 0.0,
                size: 20.0,
            });
        }

        graph.adaptive_iterations(60.0);
        assert_eq!(graph.iterations_per_frame, 1);
    }
}
