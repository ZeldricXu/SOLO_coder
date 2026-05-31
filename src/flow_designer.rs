use std::collections::{HashMap, HashSet, VecDeque};

use anyhow::{anyhow, Result};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use parking_lot::RwLock;
use petgraph::graph::{DiGraph, NodeIndex};
use petgraph::visit::EdgeRef;
use serde::{Deserialize, Serialize};
use tracing::{debug, error, info, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FlowNode {
    pub node_id: String,
    pub node_type: NodeType,
    pub name: String,
    pub description: String,
    pub position: Position,
    pub config: NodeConfig,
    pub inputs: Vec<String>,
    pub outputs: Vec<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum NodeType {
    Start,
    End,
    Process,
    Decision,
    Parallel,
    Wait,
    Custom(String),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Position {
    pub x: f64,
    pub y: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeConfig {
    pub timeout_ms: Option<u64>,
    pub retry_count: Option<u32>,
    pub parameters: serde_json::Value,
    pub script: Option<String>,
    pub handler: Option<String>,
}

impl Default for NodeConfig {
    fn default() -> Self {
        Self {
            timeout_ms: Some(30000),
            retry_count: Some(3),
            parameters: serde_json::Value::Object(serde_json::Map::new()),
            script: None,
            handler: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FlowEdge {
    pub edge_id: String,
    pub source_node_id: String,
    pub target_node_id: String,
    pub source_port: String,
    pub target_port: String,
    pub condition: Option<String>,
    pub label: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FlowDefinition {
    pub flow_id: String,
    pub name: String,
    pub description: String,
    pub version: u64,
    pub nodes: Vec<FlowNode>,
    pub edges: Vec<FlowEdge>,
    pub status: FlowStatus,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum FlowStatus {
    Draft,
    Published,
    Archived,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FlowInstance {
    pub instance_id: String,
    pub flow_id: String,
    pub flow_version: u64,
    pub status: FlowInstanceStatus,
    pub current_node_id: Option<String>,
    pub data: serde_json::Value,
    pub started_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum FlowInstanceStatus {
    Pending,
    Running,
    Paused,
    Completed,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ValidationResult {
    pub valid: bool,
    pub errors: Vec<ValidationError>,
    pub warnings: Vec<ValidationWarning>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ValidationError {
    pub code: String,
    pub message: String,
    pub node_id: Option<String>,
    pub edge_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ValidationWarning {
    pub code: String,
    pub message: String,
    pub node_id: Option<String>,
}

#[derive(Debug, Clone)]
pub enum FlowEventType {
    Created,
    Updated,
    Published,
    Archived,
    InstanceStarted,
    InstanceCompleted,
    InstanceFailed,
}

#[derive(Debug, Clone)]
pub struct FlowEvent {
    pub event_id: String,
    pub flow_id: String,
    pub event_type: FlowEventType,
    pub timestamp: DateTime<Utc>,
    pub details: Option<serde_json::Value>,
}

type FlowEventHandler = Arc<dyn Fn(FlowEvent) -> Result<()> + Send + Sync>;
use std::sync::Arc;

pub struct FlowDesigner {
    flows: DashMap<String, FlowDefinition>,
    instances: DashMap<String, FlowInstance>,
    graphs: RwLock<HashMap<String, DiGraph<FlowNode, FlowEdge>>>,
    event_handlers: RwLock<Vec<FlowEventHandler>>,
}

impl FlowDesigner {
    pub fn new() -> Self {
        Self {
            flows: DashMap::new(),
            instances: DashMap::new(),
            graphs: RwLock::new(HashMap::new()),
            event_handlers: RwLock::new(Vec::new()),
        }
    }

    pub fn register_event_handler<F>(&self, handler: F)
    where
        F: Fn(FlowEvent) -> Result<()> + Send + Sync + 'static,
    {
        self.event_handlers.write().push(Arc::new(handler));
    }

    fn notify_event_handlers(&self, event: FlowEvent) {
        let handlers = self.event_handlers.read();
        for handler in handlers.iter() {
            let event = event.clone();
            let handler = handler.clone();
            tokio::spawn(async move {
                if let Err(e) = handler(event) {
                    error!(error = %e, "Flow event handler failed");
                }
            });
        }
    }

    pub fn create_flow(&self, name: String, description: String) -> FlowDefinition {
        let now = Utc::now();
        let flow = FlowDefinition {
            flow_id: format!("flow_{}", Uuid::new_v4().simple()),
            name,
            description,
            version: 1,
            nodes: Vec::new(),
            edges: Vec::new(),
            status: FlowStatus::Draft,
            created_at: now,
            updated_at: now,
        };

        self.flows.insert(flow.flow_id.clone(), flow.clone());
        self.graphs.write().insert(flow.flow_id.clone(), DiGraph::new());

        self.notify_event_handlers(FlowEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            flow_id: flow.flow_id.clone(),
            event_type: FlowEventType::Created,
            timestamp: now,
            details: Some(serde_json::json!({ "name": flow.name })),
        });

        info!("Created flow: {} ({})", flow.name, flow.flow_id);
        flow
    }

    pub fn get_flow(&self, flow_id: &str) -> Option<FlowDefinition> {
        self.flows.get(flow_id).map(|f| f.clone())
    }

    pub fn list_flows(&self) -> Vec<FlowDefinition> {
        self.flows.iter().map(|f| f.clone()).collect()
    }

    pub fn add_node(&self, flow_id: &str, mut node: FlowNode) -> Result<FlowNode> {
        let mut flow = self.flows.get_mut(flow_id)
            .ok_or_else(|| anyhow!("Flow not found: {}", flow_id))?;

        if node.node_id.is_empty() {
            node.node_id = format!("node_{}", Uuid::new_v4().simple());
        }
        node.created_at = Utc::now();
        node.updated_at = Utc::now();

        flow.nodes.push(node.clone());
        flow.version += 1;
        flow.updated_at = Utc::now();

        let mut graphs = self.graphs.write();
        if let Some(graph) = graphs.get_mut(flow_id) {
            graph.add_node(node.clone());
        }

        drop(graphs);
        drop(flow);

        debug!("Added node {} to flow {}", node.node_id, flow_id);
        Ok(node)
    }

    pub fn remove_node(&self, flow_id: &str, node_id: &str) -> Result<()> {
        let mut flow = self.flows.get_mut(flow_id)
            .ok_or_else(|| anyhow!("Flow not found: {}", flow_id))?;

        flow.nodes.retain(|n| n.node_id != node_id);
        flow.edges.retain(|e| e.source_node_id != node_id && e.target_node_id != node_id);
        flow.version += 1;
        flow.updated_at = Utc::now();

        self.rebuild_graph(flow_id);

        debug!("Removed node {} from flow {}", node_id, flow_id);
        Ok(())
    }

    pub fn add_edge(&self, flow_id: &str, edge: FlowEdge) -> Result<FlowEdge> {
        let mut flow = self.flows.get_mut(flow_id)
            .ok_or_else(|| anyhow!("Flow not found: {}", flow_id))?;

        let source_exists = flow.nodes.iter().any(|n| n.node_id == edge.source_node_id);
        let target_exists = flow.nodes.iter().any(|n| n.node_id == edge.target_node_id);

        if !source_exists {
            return Err(anyhow!("Source node not found: {}", edge.source_node_id));
        }
        if !target_exists {
            return Err(anyhow!("Target node not found: {}", edge.target_node_id));
        }

        let mut edge = edge;
        if edge.edge_id.is_empty() {
            edge.edge_id = format!("edge_{}", Uuid::new_v4().simple());
        }

        flow.edges.push(edge.clone());
        flow.version += 1;
        flow.updated_at = Utc::now();

        self.rebuild_graph(flow_id);

        debug!("Added edge {} from {} to {} in flow {}", 
            edge.edge_id, edge.source_node_id, edge.target_node_id, flow_id);
        Ok(edge)
    }

    pub fn remove_edge(&self, flow_id: &str, edge_id: &str) -> Result<()> {
        let mut flow = self.flows.get_mut(flow_id)
            .ok_or_else(|| anyhow!("Flow not found: {}", flow_id))?;

        flow.edges.retain(|e| e.edge_id != edge_id);
        flow.version += 1;
        flow.updated_at = Utc::now();

        self.rebuild_graph(flow_id);

        debug!("Removed edge {} from flow {}", edge_id, flow_id);
        Ok(())
    }

    fn rebuild_graph(&self, flow_id: &str) {
        let flow = match self.flows.get(flow_id) {
            Some(f) => f.clone(),
            None => return,
        };

        let mut graph = DiGraph::<FlowNode, FlowEdge>::new();
        let mut node_indices: HashMap<String, NodeIndex> = HashMap::new();

        for node in &flow.nodes {
            let idx = graph.add_node(node.clone());
            node_indices.insert(node.node_id.clone(), idx);
        }

        for edge in &flow.edges {
            if let (Some(&source_idx), Some(&target_idx)) = (
                node_indices.get(&edge.source_node_id),
                node_indices.get(&edge.target_node_id),
            ) {
                graph.add_edge(source_idx, target_idx, edge.clone());
            }
        }

        self.graphs.write().insert(flow_id.to_string(), graph);
    }

    pub fn validate_flow(&self, flow_id: &str) -> Result<ValidationResult> {
        let flow = self.flows.get(flow_id)
            .ok_or_else(|| anyhow!("Flow not found: {}", flow_id))?;

        let mut errors = Vec::new();
        let mut warnings = Vec::new();

        let start_nodes: Vec<&FlowNode> = flow.nodes
            .iter()
            .filter(|n| n.node_type == NodeType::Start)
            .collect();
        
        if start_nodes.is_empty() {
            errors.push(ValidationError {
                code: "NO_START_NODE".to_string(),
                message: "Flow must have at least one start node".to_string(),
                node_id: None,
                edge_id: None,
            });
        } else if start_nodes.len() > 1 {
            errors.push(ValidationError {
                code: "MULTIPLE_START_NODES".to_string(),
                message: "Flow can have only one start node".to_string(),
                node_id: Some(start_nodes[1].node_id.clone()),
                edge_id: None,
            });
        }

        let end_nodes: Vec<&FlowNode> = flow.nodes
            .iter()
            .filter(|n| n.node_type == NodeType::End)
            .collect();
        
        if end_nodes.is_empty() {
            warnings.push(ValidationWarning {
                code: "NO_END_NODE".to_string(),
                message: "Flow should have at least one end node".to_string(),
                node_id: None,
            });
        }

        for node in &flow.nodes {
            let incoming_edges: Vec<&FlowEdge> = flow.edges
                .iter()
                .filter(|e| e.target_node_id == node.node_id)
                .collect();
            
            let outgoing_edges: Vec<&FlowEdge> = flow.edges
                .iter()
                .filter(|e| e.source_node_id == node.node_id)
                .collect();

            match node.node_type {
                NodeType::Start => {
                    if !incoming_edges.is_empty() {
                        errors.push(ValidationError {
                            code: "START_NODE_HAS_INCOMING".to_string(),
                            message: "Start node cannot have incoming edges".to_string(),
                            node_id: Some(node.node_id.clone()),
                            edge_id: None,
                        });
                    }
                    if outgoing_edges.is_empty() {
                        warnings.push(ValidationWarning {
                            code: "START_NODE_NO_OUTGOING".to_string(),
                            message: "Start node should have outgoing edges".to_string(),
                            node_id: Some(node.node_id.clone()),
                        });
                    }
                }
                NodeType::End => {
                    if !outgoing_edges.is_empty() {
                        errors.push(ValidationError {
                            code: "END_NODE_HAS_OUTGOING".to_string(),
                            message: "End node cannot have outgoing edges".to_string(),
                            node_id: Some(node.node_id.clone()),
                            edge_id: None,
                        });
                    }
                }
                NodeType::Decision => {
                    if outgoing_edges.len() < 2 {
                        warnings.push(ValidationWarning {
                            code: "DECISION_TOO_FEW_EDGES".to_string(),
                            message: "Decision node should have at least 2 outgoing edges".to_string(),
                            node_id: Some(node.node_id.clone()),
                        });
                    }
                    for edge in &outgoing_edges {
                        if edge.condition.is_none() {
                            warnings.push(ValidationWarning {
                                code: "DECISION_EDGE_NO_CONDITION".to_string(),
                                message: "Decision edges should have conditions".to_string(),
                                node_id: Some(node.node_id.clone()),
                                edge_id: Some(edge.edge_id.clone()),
                            });
                        }
                    }
                }
                _ => {}
            }
        }

        if let Some(graph) = self.graphs.read().get(flow_id) {
            if petgraph::algo::is_cyclic_directed(graph) {
                errors.push(ValidationError {
                    code: "CYCLIC_FLOW".to_string(),
                    message: "Flow contains cycles which are not allowed".to_string(),
                    node_id: None,
                    edge_id: None,
                });
            }
        }

        for edge in &flow.edges {
            let source_node = flow.nodes.iter().find(|n| n.node_id == edge.source_node_id);
            let target_node = flow.nodes.iter().find(|n| n.node_id == edge.target_node_id);

            if let (Some(source), Some(target)) = (source_node, target_node) {
                if source.outputs.is_empty() && !matches!(source.node_type, NodeType::Start | NodeType::Process | NodeType::Decision | NodeType::Parallel) {
                    warnings.push(ValidationWarning {
                        code: "SOURCE_NO_OUTPUTS".to_string(),
                        message: "Source node has no defined outputs".to_string(),
                        node_id: Some(source.node_id.clone()),
                        edge_id: Some(edge.edge_id.clone()),
                    });
                }
                if target.inputs.is_empty() && !matches!(target.node_type, NodeType::End | NodeType::Process) {
                    warnings.push(ValidationWarning {
                        code: "TARGET_NO_INPUTS".to_string(),
                        message: "Target node has no defined inputs".to_string(),
                        node_id: Some(target.node_id.clone()),
                        edge_id: Some(edge.edge_id.clone()),
                    });
                }
            }
        }

        let valid = errors.is_empty();
        Ok(ValidationResult { valid, errors, warnings })
    }

    pub fn publish_flow(&self, flow_id: &str) -> Result<FlowDefinition> {
        let validation = self.validate_flow(flow_id)?;
        if !validation.valid {
            return Err(anyhow!("Flow validation failed: {:?}", validation.errors));
        }

        let mut flow = self.flows.get_mut(flow_id)
            .ok_or_else(|| anyhow!("Flow not found: {}", flow_id))?;

        flow.status = FlowStatus::Published;
        flow.updated_at = Utc::now();
        let updated = flow.clone();
        drop(flow);

        self.notify_event_handlers(FlowEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            flow_id: flow_id.to_string(),
            event_type: FlowEventType::Published,
            timestamp: Utc::now(),
            details: None,
        });

        info!("Published flow: {}", flow_id);
        Ok(updated)
    }

    pub fn archive_flow(&self, flow_id: &str) -> Result<FlowDefinition> {
        let mut flow = self.flows.get_mut(flow_id)
            .ok_or_else(|| anyhow!("Flow not found: {}", flow_id))?;

        flow.status = FlowStatus::Archived;
        flow.updated_at = Utc::now();
        let updated = flow.clone();
        drop(flow);

        self.notify_event_handlers(FlowEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            flow_id: flow_id.to_string(),
            event_type: FlowEventType::Archived,
            timestamp: Utc::now(),
            details: None,
        });

        info!("Archived flow: {}", flow_id);
        Ok(updated)
    }

    pub fn start_instance(&self, flow_id: &str, input_data: serde_json::Value) -> Result<FlowInstance> {
        let flow = self.flows.get(flow_id)
            .ok_or_else(|| anyhow!("Flow not found: {}", flow_id))?;

        if flow.status != FlowStatus::Published {
            return Err(anyhow!("Flow is not published: {}", flow_id));
        }

        let start_node = flow.nodes.iter().find(|n| n.node_type == NodeType::Start);
        let instance = FlowInstance {
            instance_id: format!("inst_{}", Uuid::new_v4().simple()),
            flow_id: flow_id.to_string(),
            flow_version: flow.version,
            status: FlowInstanceStatus::Running,
            current_node_id: start_node.map(|n| n.node_id.clone()),
            data: input_data,
            started_at: Utc::now(),
            completed_at: None,
            error: None,
        };

        self.instances.insert(instance.instance_id.clone(), instance.clone());

        self.notify_event_handlers(FlowEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            flow_id: flow_id.to_string(),
            event_type: FlowEventType::InstanceStarted,
            timestamp: Utc::now(),
            details: Some(serde_json::json!({ "instance_id": instance.instance_id })),
        });

        info!("Started flow instance: {} for flow: {}", instance.instance_id, flow_id);
        Ok(instance)
    }

    pub fn get_instance(&self, instance_id: &str) -> Option<FlowInstance> {
        self.instances.get(instance_id).map(|i| i.clone())
    }

    pub fn get_flow_instances(&self, flow_id: &str) -> Vec<FlowInstance> {
        self.instances
            .iter()
            .filter(|i| i.flow_id == flow_id)
            .map(|i| i.clone())
            .collect()
    }

    pub fn complete_instance(&self, instance_id: &str, result_data: Option<serde_json::Value>) -> Result<FlowInstance> {
        let mut instance = self.instances.get_mut(instance_id)
            .ok_or_else(|| anyhow!("Instance not found: {}", instance_id))?;

        instance.status = FlowInstanceStatus::Completed;
        instance.completed_at = Some(Utc::now());
        if let Some(data) = result_data {
            instance.data = data;
        }
        let updated = instance.clone();
        drop(instance);

        self.notify_event_handlers(FlowEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            flow_id: updated.flow_id.clone(),
            event_type: FlowEventType::InstanceCompleted,
            timestamp: Utc::now(),
            details: Some(serde_json::json!({ "instance_id": instance_id })),
        });

        info!("Completed flow instance: {}", instance_id);
        Ok(updated)
    }

    pub fn fail_instance(&self, instance_id: &str, error: &str) -> Result<FlowInstance> {
        let mut instance = self.instances.get_mut(instance_id)
            .ok_or_else(|| anyhow!("Instance not found: {}", instance_id))?;

        instance.status = FlowInstanceStatus::Failed;
        instance.completed_at = Some(Utc::now());
        instance.error = Some(error.to_string());
        let updated = instance.clone();
        drop(instance);

        self.notify_event_handlers(FlowEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            flow_id: updated.flow_id.clone(),
            event_type: FlowEventType::InstanceFailed,
            timestamp: Utc::now(),
            details: Some(serde_json::json!({
                "instance_id": instance_id,
                "error": error
            })),
        });

        warn!("Failed flow instance: {}: {}", instance_id, error);
        Ok(updated)
    }

    pub fn get_execution_path(&self, flow_id: &str) -> Result<Vec<String>> {
        let flow = self.flows.get(flow_id)
            .ok_or_else(|| anyhow!("Flow not found: {}", flow_id))?;

        let start_node = flow.nodes.iter()
            .find(|n| n.node_type == NodeType::Start)
            .ok_or_else(|| anyhow!("No start node found"))?;

        let mut path = Vec::new();
        let mut visited = HashSet::new();
        let mut queue = VecDeque::new();
        queue.push_back(start_node.node_id.clone());

        while let Some(node_id) = queue.pop_front() {
            if !visited.insert(node_id.clone()) {
                continue;
            }
            path.push(node_id.clone());

            for edge in &flow.edges {
                if edge.source_node_id == node_id {
                    queue.push_back(edge.target_node_id.clone());
                }
            }
        }

        Ok(path)
    }

    pub fn update_node_position(&self, flow_id: &str, node_id: &str, position: Position) -> Result<()> {
        let mut flow = self.flows.get_mut(flow_id)
            .ok_or_else(|| anyhow!("Flow not found: {}", flow_id))?;

        for node in flow.nodes.iter_mut() {
            if node.node_id == node_id {
                node.position = position;
                node.updated_at = Utc::now();
                flow.version += 1;
                flow.updated_at = Utc::now();
                return Ok(());
            }
        }

        Err(anyhow!("Node not found: {}", node_id))
    }

    pub fn update_node_config(&self, flow_id: &str, node_id: &str, config: NodeConfig) -> Result<()> {
        let mut flow = self.flows.get_mut(flow_id)
            .ok_or_else(|| anyhow!("Flow not found: {}", flow_id))?;

        for node in flow.nodes.iter_mut() {
            if node.node_id == node_id {
                node.config = config;
                node.updated_at = Utc::now();
                flow.version += 1;
                flow.updated_at = Utc::now();
                return Ok(());
            }
        }

        Err(anyhow!("Node not found: {}", node_id))
    }
}

impl Default for FlowDesigner {
    fn default() -> Self {
        Self::new()
    }
}

pub fn create_node(
    node_type: NodeType,
    name: String,
    description: String,
    x: f64,
    y: f64,
) -> FlowNode {
    let now = Utc::now();
    FlowNode {
        node_id: String::new(),
        node_type,
        name,
        description,
        position: Position { x, y },
        config: NodeConfig::default(),
        inputs: Vec::new(),
        outputs: vec!["output".to_string()],
        created_at: now,
        updated_at: now,
    }
}

pub fn create_edge(source_node_id: &str, target_node_id: &str) -> FlowEdge {
    FlowEdge {
        edge_id: String::new(),
        source_node_id: source_node_id.to_string(),
        target_node_id: target_node_id.to_string(),
        source_port: "output".to_string(),
        target_port: "input".to_string(),
        condition: None,
        label: None,
    }
}
