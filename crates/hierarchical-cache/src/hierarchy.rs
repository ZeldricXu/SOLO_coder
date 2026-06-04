use std::collections::HashMap;
use uuid::Uuid;
use common::models::{EdgeNode, NodeRole};

pub struct CacheHierarchy {
    child_to_parent: HashMap<Uuid, Uuid>,
    node_roles: HashMap<Uuid, NodeRole>,
    children: HashMap<Uuid, Vec<Uuid>>,
}

impl CacheHierarchy {
    pub fn build_from_nodes(nodes: &[EdgeNode]) -> Self {
        let mut child_to_parent: HashMap<Uuid, Uuid> = HashMap::new();
        let mut node_roles: HashMap<Uuid, NodeRole> = HashMap::new();
        let mut children: HashMap<Uuid, Vec<Uuid>> = HashMap::new();

        for node in nodes {
            node_roles.insert(node.id, node.role.clone());

            if let Some(parent_id) = node.parent_node_id {
                child_to_parent.insert(node.id, parent_id);
                children.entry(parent_id).or_default().push(node.id);
            }
        }

        CacheHierarchy {
            child_to_parent,
            node_roles,
            children,
        }
    }

    pub fn get_parent(&self, node_id: Uuid) -> Option<Uuid> {
        self.child_to_parent.get(&node_id).copied()
    }

    pub fn get_ancestors(&self, node_id: Uuid) -> Vec<Uuid> {
        let mut ancestors = Vec::new();
        let mut current = node_id;

        while let Some(parent_id) = self.child_to_parent.get(&current) {
            ancestors.push(*parent_id);
            current = *parent_id;
        }

        ancestors
    }

    pub fn get_children(&self, parent_id: Uuid) -> Vec<Uuid> {
        self.children.get(&parent_id).cloned().unwrap_or_default()
    }

    pub fn get_origin_nodes(&self) -> Vec<Uuid> {
        self.node_roles
            .iter()
            .filter(|(_, role)| **role == NodeRole::Origin)
            .map(|(id, _)| *id)
            .collect()
    }

    pub fn get_role(&self, node_id: Uuid) -> Option<&NodeRole> {
        self.node_roles.get(&node_id)
    }

    pub fn contains(&self, node_id: Uuid) -> bool {
        self.node_roles.contains_key(&node_id)
    }
}
