use std::collections::HashMap;

use physics_core::Body;
use physics_spatial::{AABBProxy, AABBTree};

pub struct BroadPhase {
    tree: AABBTree,
    body_to_proxy: HashMap<physics_core::BodyHandle, AABBProxy>,
}

impl BroadPhase {
    pub fn new() -> Self {
        BroadPhase {
            tree: AABBTree::new(),
            body_to_proxy: HashMap::new(),
        }
    }

    pub fn with_extension(extension: f32) -> Self {
        BroadPhase {
            tree: AABBTree::with_extension(extension),
            body_to_proxy: HashMap::new(),
        }
    }

    pub fn add_body(&mut self, handle: physics_core::BodyHandle, body: &Body) {
        let aabb = body.shape.compute_aabb(&body.transform);
        let proxy = self.tree.insert_proxy(aabb);
        self.body_to_proxy.insert(handle, proxy);
    }

    pub fn remove_body(&mut self, handle: physics_core::BodyHandle) {
        if let Some(proxy) = self.body_to_proxy.remove(&handle) {
            self.tree.remove_proxy(proxy);
        }
    }

    pub fn update_body(&mut self, handle: physics_core::BodyHandle, body: &Body) -> bool {
        if let Some(proxy) = self.body_to_proxy.get(&handle) {
            let aabb = body.shape.compute_aabb(&body.transform);
            self.tree.update_proxy(*proxy, aabb)
        } else {
            false
        }
    }

    pub fn get_potential_pairs(&self) -> Vec<(physics_core::BodyHandle, physics_core::BodyHandle)> {
        let mut pairs = Vec::new();
        let proxy_pairs = self.tree.get_pairs();

        let proxy_to_body: HashMap<_, _> = self
            .body_to_proxy
            .iter()
            .map(|(h, p)| (*p, *h))
            .collect();

        for (pa, pb) in proxy_pairs {
            if let (Some(&ha), Some(&hb)) = (proxy_to_body.get(&pa), proxy_to_body.get(&pb)) {
                pairs.push((ha, hb));
            }
        }

        pairs
    }

    pub fn query(&self, aabb: &physics_math::AABB) -> Vec<physics_core::BodyHandle> {
        let mut results = Vec::new();
        let proxies = self.tree.query(aabb);

        let proxy_to_body: HashMap<_, _> = self
            .body_to_proxy
            .iter()
            .map(|(h, p)| (*p, *h))
            .collect();

        for proxy in proxies {
            if let Some(&handle) = proxy_to_body.get(&proxy) {
                results.push(handle);
            }
        }

        results
    }

    pub fn clear(&mut self) {
        self.tree.clear();
        self.body_to_proxy.clear();
    }

    pub fn body_count(&self) -> usize {
        self.body_to_proxy.len()
    }
}

impl Default for BroadPhase {
    fn default() -> Self {
        Self::new()
    }
}
