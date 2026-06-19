use std::collections::HashMap;

use physics_types::{Body, BodyHandle};
use physics_math::AABB;
use physics_spatial::{AABBProxy, AABBTree};

/// 一对可能发生碰撞的物理体句柄对。
pub type BodyPair = (physics_types::BodyHandle, physics_types::BodyHandle);

/// 宽相碰撞检测 trait。
///
/// 宽相检测是碰撞检测的第一阶段，用于快速剔除显然不可能碰撞的物体对，
/// 减少需要进行精确窄相检测的物体对数量。
///
/// # 示例
///
/// ```rust
/// use physics_collision::{BroadPhase, BruteForceBroadPhase};
/// use physics_types::{Body, BodyType, Material, Shape};
/// use physics_types::shape::Circle;
/// use physics_math::{Vec2, Transform, Rot2};
/// use slotmap::SlotMap;
///
/// let mut bp = BruteForceBroadPhase::new();
/// let mut bodies = SlotMap::with_key();
///
/// let circle = Shape::Circle(Circle::new(1.0));
/// let handle = bodies.insert_with_key(|handle| {
///     Body::new(handle, circle, Vec2::new(0.0, 0.0), 0.0, BodyType::Dynamic, Material::DEFAULT)
/// });
///
/// bp.add_body(handle, bodies.get(handle).unwrap());
/// assert_eq!(bp.body_count(), 1);
/// ```
pub trait BroadPhase {
    /// 添加一个物理体到宽相检测中。
    fn add_body(&mut self, handle: physics_types::BodyHandle, body: &Body);
    /// 从宽相检测中移除物理体。
    fn remove_body(&mut self, handle: physics_types::BodyHandle);
    /// 更新物理体的 AABB。
    ///
    /// # 返回
    ///
    /// 如果物理体存在并成功更新返回 `true`，否则返回 `false`。
    fn update_body(&mut self, handle: physics_types::BodyHandle, body: &Body) -> bool;
    /// 获取所有可能发生碰撞的物理体对。
    fn get_potential_pairs(&self) -> Vec<BodyPair>;
    /// 查询与给定 AABB 相交的所有物理体。
    fn query(&self, aabb: &AABB) -> Vec<physics_types::BodyHandle>;
    /// 清空所有物理体。
    fn clear(&mut self);
    /// 获取当前管理的物理体数量。
    fn body_count(&self) -> usize;
}

/// 暴力宽相碰撞检测实现。
///
/// 通过双重循环检查所有物体对，时间复杂度 O(n²)。
/// 适用于物体数量较少的场景或调试用途。
///
/// # 示例
///
/// ```rust
/// use physics_collision::BruteForceBroadPhase;
///
/// let bp = BruteForceBroadPhase::new();
/// ```
#[derive(Clone, Debug, Default)]
pub struct BruteForceBroadPhase {
    bodies: HashMap<physics_types::BodyHandle, AABB>,
}

impl BruteForceBroadPhase {
    /// 创建一个新的暴力宽相检测器。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_collision::BruteForceBroadPhase;
    ///
    /// let bp = BruteForceBroadPhase::new();
    /// ```
    pub fn new() -> Self {
        BruteForceBroadPhase {
            bodies: HashMap::new(),
        }
    }
}

impl BroadPhase for BruteForceBroadPhase {
    fn add_body(&mut self, handle: physics_types::BodyHandle, body: &Body) {
        let aabb = body.shape.compute_aabb(&body.transform);
        self.bodies.insert(handle, aabb);
    }

    fn remove_body(&mut self, handle: physics_types::BodyHandle) {
        self.bodies.remove(&handle);
    }

    fn update_body(&mut self, handle: physics_types::BodyHandle, body: &Body) -> bool {
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
        let handles: Vec<physics_types::BodyHandle> = self.bodies.keys().copied().collect();

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

    fn query(&self, aabb: &AABB) -> Vec<physics_types::BodyHandle> {
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

/// 基于 AABB 树的宽相碰撞检测实现。
///
/// 使用动态 AABB 树空间数据结构，查询效率更高，平均时间复杂度 O(n log n)。
/// 适用于物体数量较多的场景。
///
/// # 示例
///
/// ```rust
/// use physics_collision::AABBTreeBroadPhase;
///
/// let bp = AABBTreeBroadPhase::new();
/// ```
#[derive(Clone, Debug)]
pub struct AABBTreeBroadPhase {
    tree: AABBTree,
    body_to_proxy: HashMap<BodyHandle, AABBProxy>,
    proxy_to_body: HashMap<AABBProxy, BodyHandle>,
}

impl AABBTreeBroadPhase {
    /// 创建一个新的 AABB 树宽相检测器。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_collision::AABBTreeBroadPhase;
    ///
    /// let bp = AABBTreeBroadPhase::new();
    /// ```
    pub fn new() -> Self {
        AABBTreeBroadPhase {
            tree: AABBTree::new(),
            body_to_proxy: HashMap::new(),
            proxy_to_body: HashMap::new(),
        }
    }

    /// 创建一个带有扩展边距的 AABB 树宽相检测器。
    ///
    /// # 参数
    ///
    /// * `extension` - AABB 扩展边距，用于减少物体移动时的树更新频率
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_collision::AABBTreeBroadPhase;
    ///
    /// let bp = AABBTreeBroadPhase::with_extension(0.1);
    /// ```
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
    fn add_body(&mut self, handle: physics_types::BodyHandle, body: &Body) {
        let aabb = body.shape.compute_aabb(&body.transform);
        let proxy = self.tree.insert_proxy(aabb);
        self.body_to_proxy.insert(handle, proxy);
        self.proxy_to_body.insert(proxy, handle);
    }

    fn remove_body(&mut self, handle: physics_types::BodyHandle) {
        if let Some(proxy) = self.body_to_proxy.remove(&handle) {
            self.tree.remove_proxy(proxy);
            self.proxy_to_body.remove(&proxy);
        }
    }

    fn update_body(&mut self, handle: physics_types::BodyHandle, body: &Body) -> bool {
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

    fn query(&self, aabb: &AABB) -> Vec<physics_types::BodyHandle> {
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

/// 默认宽相碰撞检测实现的类型别名。
pub type BroadPhaseDefault = AABBTreeBroadPhase;

#[cfg(test)]
mod tests {
    use super::*;
    use physics_types::{Body, BodyType, Material, Shape, Circle};
    use physics_math::{Transform, Rot2, Vec2};
    use slotmap::SlotMap;

    fn create_test_body(
        bodies: &mut SlotMap<physics_types::BodyHandle, Body>,
        shape: Shape,
        position: Vec2,
    ) -> physics_types::BodyHandle {
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
