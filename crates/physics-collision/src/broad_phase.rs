use std::collections::HashMap;

use physics_core::{Body, BodyHandle};
use physics_math::AABB;
use physics_spatial::{AABBProxy, AABBTree};

pub type BodyPair = (physics_core::BodyHandle, physics_core::BodyHandle);

pub trait BroadPhase {
    fn add_body(&mut self, handle: physics_core::BodyHandle, body: &Body);
    fn remove_body(&mut self, handle: physics_core::BodyHandle);
    fn update_body(&mut self, handle: physics_core::BodyHandle, body: &Body) -> bool;
    fn get_potential_pairs(&self) -> Vec<BodyPair>;
    fn query(&self, aabb: &AABB) -> Vec<physics_core::BodyHandle>;
    fn clear(&mut self);
    fn body_count(&self) -> usize;
}

#[derive(Clone, Debug, Default)]
pub struct BruteForceBroadPhase {
    bodies: HashMap<physics_core::BodyHandle, AABB>,
}

impl BruteForceBroadPhase {
    pub fn new() -> Self {
        BruteForceBroadPhase {
            bodies: HashMap::new(),
        }
    }
}

impl BroadPhase for BruteForceBroadPhase {
    fn add_body(&mut self, handle: physics_core::BodyHandle, body: &Body) {
        let aabb = body.shape.compute_aabb(&body.transform);
        self.bodies.insert(handle, aabb);
    }

    fn remove_body(&mut self, handle: physics_core::BodyHandle) {
        self.bodies.remove(&handle);
    }

    fn update_body(&mut self, handle: physics_core::BodyHandle, body: &Body) -> bool {
        if let Some(aabb_ref) = self.bodies.get_mut(&handle) {
            let new_aabb = body.shape.compute_aabb(&body.transform);
            *aabb_ref = new_aabb;
            true
        } else {
            false
        }
    }

    fn get_potential_pairs(&self) -> Vec<BodyPair> {
        let mut pairs = Vec::new();
        let handles: Vec<physics_core::BodyHandle> = self.bodies.keys().copied().collect();

        for i in 0..handles.len() {
            for j in (i + 1)..handles.len() {
                let ha = handles[i];
                let hb = handles[j];

                if let (Some(aabb_a), Some(aabb_b)) = (self.bodies.get(&ha), self.bodies.get(&hb)) {
                    if aabb_a.intersects(aabb_b) {
                        pairs.push((ha, hb));
                    }
                }
            }
        }

        pairs
    }

    fn query(&self, aabb: &AABB) -> Vec<physics_core::BodyHandle> {
        let mut results = Vec::new();
        for (handle, body_aabb) in &self.bodies {
            if body_aabb.intersects(aabb) {
                results.push(*handle);
            }
        }
        results
    }

    fn clear(&mut self) {
        self.bodies.clear();
    }

    fn body_count(&self) -> usize {
        self.bodies.len()
    }
}

#[derive(Clone, Debug)]
pub struct AABBTreeBroadPhase {
    tree: AABBTree,
    body_to_proxy: HashMap<BodyHandle, AABBProxy>,
    proxy_to_body: HashMap<AABBProxy, BodyHandle>,
}

impl AABBTreeBroadPhase {
    pub fn new() -> Self {
        AABBTreeBroadPhase {
            tree: AABBTree::new(),
            body_to_proxy: HashMap::new(),
            proxy_to_body: HashMap::new(),
        }
    }

    pub fn with_extension(extension: f32) -> Self {
        AABBTreeBroadPhase {
            tree: AABBTree::with_extension(extension),
            body_to_proxy: HashMap::new(),
            proxy_to_body: HashMap::new(),
        }
    }
}

impl Default for AABBTreeBroadPhase {
    fn default() -> Self {
        Self::new()
    }
}

impl BroadPhase for AABBTreeBroadPhase {
    fn add_body(&mut self, handle: physics_core::BodyHandle, body: &Body) {
        let aabb = body.shape.compute_aabb(&body.transform);
        let proxy = self.tree.insert_proxy(aabb);
        self.body_to_proxy.insert(handle, proxy);
        self.proxy_to_body.insert(proxy, handle);
    }

    fn remove_body(&mut self, handle: physics_core::BodyHandle) {
        if let Some(proxy) = self.body_to_proxy.remove(&handle) {
            self.tree.remove_proxy(proxy);
            self.proxy_to_body.remove(&proxy);
        }
    }

    fn update_body(&mut self, handle: physics_core::BodyHandle, body: &Body) -> bool {
        if let Some(proxy) = self.body_to_proxy.get(&handle) {
            let aabb = body.shape.compute_aabb(&body.transform);
            self.tree.update_proxy(*proxy, aabb)
        } else {
            false
        }
    }

    fn get_potential_pairs(&self) -> Vec<BodyPair> {
        let mut pairs = Vec::new();
        let proxy_pairs = self.tree.get_pairs();

        for (pa, pb) in proxy_pairs {
            if let (Some(&ha), Some(&hb)) = (self.proxy_to_body.get(&pa), self.proxy_to_body.get(&pb)) {
                pairs.push((ha, hb));
            }
        }

        pairs
    }

    fn query(&self, aabb: &AABB) -> Vec<physics_core::BodyHandle> {
        let mut results = Vec::new();
        let proxies = self.tree.query(aabb);

        for proxy in proxies {
            if let Some(&handle) = self.proxy_to_body.get(&proxy) {
                results.push(handle);
            }
        }

        results
    }

    fn clear(&mut self) {
        self.tree.clear();
        self.body_to_proxy.clear();
        self.proxy_to_body.clear();
    }

    fn body_count(&self) -> usize {
        self.body_to_proxy.len()
    }
}

pub type BroadPhaseDefault = AABBTreeBroadPhase;

#[cfg(test)]
mod tests {
    use super::*;
    use physics_core::{Body, BodyType, Material, Shape, Circle};
    use physics_math::{Transform, Rot2, Vec2};
    use slotmap::SlotMap;

    fn create_test_body(
        bodies: &mut SlotMap<physics_core::BodyHandle, Body>,
        shape: Shape,
        position: Vec2,
    ) -> physics_core::BodyHandle {
        let transform = Transform::new(position, Rot2::new(0.0));
        bodies.insert_with_key(|handle| {
            Body::new(handle, shape, position, 0.0, BodyType::Dynamic, Material::DEFAULT)
        })
    }

    fn test_broad_phase<B: BroadPhase>(mut bp: B) {
        let mut bodies = SlotMap::with_key();

        let circle = Shape::Circle(Circle::new(1.0));

        let h1 = create_test_body(&mut bodies, circle.clone(), Vec2::new(0.0, 0.0));
        let h2 = create_test_body(&mut bodies, circle.clone(), Vec2::new(1.5, 0.0));
        let h3 = create_test_body(&mut bodies, circle.clone(), Vec2::new(10.0, 10.0));

        bp.add_body(h1, bodies.get(h1).unwrap());
        bp.add_body(h2, bodies.get(h2).unwrap());
        bp.add_body(h3, bodies.get(h3).unwrap());

        let pairs = bp.get_potential_pairs();
        assert_eq!(pairs.len(), 1);
        assert!(pairs.contains(&(h1, h2)) || pairs.contains(&(h2, h1)));
    }

    #[test]
    fn test_brute_force_broad_phase() {
        test_broad_phase(BruteForceBroadPhase::new());
    }

    #[test]
    fn test_aabb_tree_broad_phase() {
        test_broad_phase(AABBTreeBroadPhase::new());
    }

    #[test]
    fn test_broad_phase_query() {
        let mut bp = AABBTreeBroadPhase::new();
        let mut bodies = SlotMap::with_key();

        let circle = Shape::Circle(Circle::new(1.0));

        let h1 = create_test_body(&mut bodies, circle.clone(), Vec2::new(0.0, 0.0));
        let h2 = create_test_body(&mut bodies, circle.clone(), Vec2::new(5.0, 0.0));

        bp.add_body(h1, bodies.get(h1).unwrap());
        bp.add_body(h2, bodies.get(h2).unwrap());

        let query_aabb = AABB::new(Vec2::new(-2.0, -2.0), Vec2::new(2.0, 2.0));
        let results = bp.query(&query_aabb);

        assert_eq!(results.len(), 1);
        assert_eq!(results[0], h1);
    }

    #[test]
    fn test_broad_phase_remove_body() {
        let mut bp = BruteForceBroadPhase::new();
        let mut bodies = SlotMap::with_key();

        let circle = Shape::Circle(Circle::new(1.0));
        let h = create_test_body(&mut bodies, circle.clone(), Vec2::new(0.0, 0.0));

        bp.add_body(h, bodies.get(h).unwrap());
        assert_eq!(bp.body_count(), 1);

        bp.remove_body(h);
        assert_eq!(bp.body_count(), 0);
    }
}
