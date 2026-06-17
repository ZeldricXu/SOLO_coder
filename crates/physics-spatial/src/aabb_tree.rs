use slotmap::{new_key_type, Key};

use physics_math::{AABB, Vec2};

new_key_type! {
    pub struct AABBProxy;
}

const AABB_TREE_CAPACITY: usize = 16;
const AABB_TREE_EXTENSION: f32 = 0.1;

#[derive(Clone, Debug)]
struct AABBNode {
    aabb: AABB,
    parent: Option<usize>,
    left: Option<usize>,
    right: Option<usize>,
    proxy: Option<AABBProxy>,
    height: i32,
}

impl AABBNode {
    fn is_leaf(&self) -> bool {
        self.left.is_none()
    }
}

#[derive(Clone, Debug)]
pub struct AABBTree {
    nodes: Vec<AABBNode>,
    root: Option<usize>,
    free_list: Vec<usize>,
    proxy_map: slotmap::SlotMap<AABBProxy, usize>,
    extension: f32,
}

impl AABBTree {
    pub fn new() -> Self {
        AABBTree {
            nodes: Vec::with_capacity(AABB_TREE_CAPACITY),
            root: None,
            free_list: Vec::new(),
            proxy_map: slotmap::SlotMap::with_key(),
            extension: AABB_TREE_EXTENSION,
        }
    }

    pub fn with_extension(extension: f32) -> Self {
        AABBTree {
            extension,
            ..Self::new()
        }
    }

    pub fn insert_proxy(&mut self, aabb: AABB) -> AABBProxy {
        let fat_aabb = aabb.expand(self.extension);
        let node_index = self.allocate_node();
        let node = &mut self.nodes[node_index];
        node.aabb = fat_aabb;
        node.proxy = None;
        node.height = 0;

        let proxy = self.proxy_map.insert(node_index);
        node.proxy = Some(proxy);

        self.insert_leaf(node_index);
        proxy
    }

    pub fn remove_proxy(&mut self, proxy: AABBProxy) {
        if let Some(node_index) = self.proxy_map.remove(proxy) {
            self.remove_leaf(node_index);
            self.free_node(node_index);
        }
    }

    pub fn update_proxy(&mut self, proxy: AABBProxy, aabb: AABB) -> bool {
        let node_index = *self.proxy_map.get(proxy).unwrap();
        let node = &self.nodes[node_index];

        if node.aabb.contains_aabb(&aabb) {
            return false;
        }

        self.remove_leaf(node_index);

        let fat_aabb = aabb.expand(self.extension);
        self.nodes[node_index].aabb = fat_aabb;

        self.insert_leaf(node_index);
        true
    }

    pub fn get_aabb(&self, proxy: AABBProxy) -> Option<AABB> {
        self.proxy_map
            .get(proxy)
            .map(|&idx| self.nodes[idx].aabb)
    }

    pub fn query(&self, aabb: &AABB) -> Vec<AABBProxy> {
        let mut results = Vec::new();
        let mut stack = Vec::new();

        if let Some(root) = self.root {
            stack.push(root);
        }

        while let Some(node_index) = stack.pop() {
            let node = &self.nodes[node_index];

            if !node.aabb.intersects(aabb) {
                continue;
            }

            if node.is_leaf() {
                if let Some(proxy) = node.proxy {
                    results.push(proxy);
                }
            } else {
                if let Some(left) = node.left {
                    stack.push(left);
                }
                if let Some(right) = node.right {
                    stack.push(right);
                }
            }
        }

        results
    }

    pub fn get_pairs(&self) -> Vec<(AABBProxy, AABBProxy)> {
        let mut pairs = Vec::new();
        let leaves: Vec<(usize, AABBProxy, AABB)> = self
            .nodes
            .iter()
            .enumerate()
            .filter(|(_, n)| n.is_leaf() && n.proxy.is_some())
            .map(|(i, n)| (i, n.proxy.unwrap(), n.aabb))
            .collect();

        let mut stack = Vec::new();
        let mut query_results = Vec::new();

        for &(_, proxy_a, ref aabb_a) in leaves.iter() {
            query_results.clear();
            stack.clear();

            if let Some(root) = self.root {
                stack.push(root);
            }

            while let Some(node_index) = stack.pop() {
                let node = &self.nodes[node_index];

                if !node.aabb.intersects(aabb_a) {
                    continue;
                }

                if node.is_leaf() {
                    if let Some(proxy_b) = node.proxy {
                        if proxy_a.data().as_ffi() < proxy_b.data().as_ffi() {
                            query_results.push(proxy_b);
                        }
                    }
                } else {
                    if let Some(left) = node.left {
                        stack.push(left);
                    }
                    if let Some(right) = node.right {
                        stack.push(right);
                    }
                }
            }

            for &proxy_b in &query_results {
                pairs.push((proxy_a, proxy_b));
            }
        }

        pairs
    }

    fn allocate_node(&mut self) -> usize {
        if let Some(index) = self.free_list.pop() {
            self.nodes[index] = AABBNode {
                aabb: AABB::new(Vec2::ZERO, Vec2::ZERO),
                parent: None,
                left: None,
                right: None,
                proxy: None,
                height: 0,
            };
            return index;
        }

        let index = self.nodes.len();
        self.nodes.push(AABBNode {
            aabb: AABB::new(Vec2::ZERO, Vec2::ZERO),
            parent: None,
            left: None,
            right: None,
            proxy: None,
            height: 0,
        });
        index
    }

    fn free_node(&mut self, index: usize) {
        self.free_list.push(index);
    }

    fn insert_leaf(&mut self, leaf: usize) {
        if self.root.is_none() {
            self.root = Some(leaf);
            self.nodes[leaf].parent = None;
            return;
        }

        let mut current = self.root.unwrap();
        while !self.nodes[current].is_leaf() {
            let left = self.nodes[current].left.unwrap();
            let right = self.nodes[current].right.unwrap();

            let area = self.nodes[current].aabb.perimeter();

            let combined_aabb = self.nodes[current].aabb.merged(&self.nodes[leaf].aabb);
            let combined_area = combined_aabb.perimeter();

            let cost = 2.0 * combined_area;
            let inheritance_cost = 2.0 * (combined_area - area);

            let cost_left = self.compute_cost(left, &self.nodes[leaf].aabb) + inheritance_cost;
            let cost_right = self.compute_cost(right, &self.nodes[leaf].aabb) + inheritance_cost;

            if cost < cost_left && cost < cost_right {
                break;
            }

            if cost_left < cost_right {
                current = left;
            } else {
                current = right;
            }
        }

        let sibling = current;
        let old_parent = self.nodes[sibling].parent;

        let new_parent = self.allocate_node();
        self.nodes[new_parent].parent = old_parent;
        self.nodes[new_parent].aabb = self.nodes[sibling].aabb.merged(&self.nodes[leaf].aabb);
        self.nodes[new_parent].height = self.nodes[sibling].height + 1;

        if let Some(parent) = old_parent {
            if self.nodes[parent].left == Some(sibling) {
                self.nodes[parent].left = Some(new_parent);
            } else {
                self.nodes[parent].right = Some(new_parent);
            }
        } else {
            self.root = Some(new_parent);
        }

        self.nodes[new_parent].left = Some(sibling);
        self.nodes[new_parent].right = Some(leaf);
        self.nodes[sibling].parent = Some(new_parent);
        self.nodes[leaf].parent = Some(new_parent);

        let mut index = self.nodes[leaf].parent;
        while let Some(idx) = index {
            index = self.nodes[idx].parent;
            self.balance(idx);
        }
    }

    fn remove_leaf(&mut self, leaf: usize) {
        if leaf == self.root.unwrap() {
            self.root = None;
            return;
        }

        let parent = self.nodes[leaf].parent.unwrap();
        let grandparent = self.nodes[parent].parent;

        let sibling = if self.nodes[parent].left == Some(leaf) {
            self.nodes[parent].right.unwrap()
        } else {
            self.nodes[parent].left.unwrap()
        };

        if let Some(grand) = grandparent {
            if self.nodes[grand].left == Some(parent) {
                self.nodes[grand].left = Some(sibling);
            } else {
                self.nodes[grand].right = Some(sibling);
            }
            self.nodes[sibling].parent = Some(grand);
            self.free_node(parent);

            let mut index = Some(grand);
            while let Some(idx) = index {
                index = self.nodes[idx].parent;
                self.balance(idx);
            }
        } else {
            self.root = Some(sibling);
            self.nodes[sibling].parent = None;
            self.free_node(parent);
        }

        self.nodes[leaf].parent = None;
    }

    fn compute_cost(&self, index: usize, aabb: &AABB) -> f32 {
        let combined = self.nodes[index].aabb.merged(aabb);
        combined.perimeter()
    }

    fn balance(&mut self, index: usize) {
        let node = &mut self.nodes[index];
        if node.is_leaf() || node.height < 2 {
            return;
        }

        let left = node.left.unwrap();
        let right = node.right.unwrap();

        let balance = self.nodes[right].height - self.nodes[left].height;

        if balance > 1 {
            self.rotate_left(index);
        } else if balance < -1 {
            self.rotate_right(index);
        } else {
            self.sync_height(index);
        }
    }

    fn rotate_left(&mut self, index: usize) {
        let right = self.nodes[index].right.unwrap();
        let right_left = self.nodes[right].left;
        let _right_right = self.nodes[right].right;

        self.nodes[right].left = Some(index);
        self.nodes[right].parent = self.nodes[index].parent;
        self.nodes[index].parent = Some(right);

        if let Some(parent) = self.nodes[right].parent {
            if self.nodes[parent].left == Some(index) {
                self.nodes[parent].left = Some(right);
            } else {
                self.nodes[parent].right = Some(right);
            }
        } else {
            self.root = Some(right);
        }

        self.nodes[index].right = right_left;
        if let Some(rl) = right_left {
            self.nodes[rl].parent = Some(index);
        }

        self.sync_height(index);
        self.sync_height(right);
    }

    fn rotate_right(&mut self, index: usize) {
        let left = self.nodes[index].left.unwrap();
        let _left_left = self.nodes[left].left;
        let left_right = self.nodes[left].right;

        self.nodes[left].right = Some(index);
        self.nodes[left].parent = self.nodes[index].parent;
        self.nodes[index].parent = Some(left);

        if let Some(parent) = self.nodes[left].parent {
            if self.nodes[parent].left == Some(index) {
                self.nodes[parent].left = Some(left);
            } else {
                self.nodes[parent].right = Some(left);
            }
        } else {
            self.root = Some(left);
        }

        self.nodes[index].left = left_right;
        if let Some(lr) = left_right {
            self.nodes[lr].parent = Some(index);
        }

        self.sync_height(index);
        self.sync_height(left);
    }

    fn sync_height(&mut self, index: usize) {
        let left = self.nodes[index].left;
        let right = self.nodes[index].right;

        let left_height = left.map(|l| self.nodes[l].height).unwrap_or(-1);
        let right_height = right.map(|r| self.nodes[r].height).unwrap_or(-1);

        self.nodes[index].height = left_height.max(right_height) + 1;
    }

    pub fn proxy_count(&self) -> usize {
        self.proxy_map.len()
    }

    pub fn clear(&mut self) {
        self.nodes.clear();
        self.root = None;
        self.free_list.clear();
        self.proxy_map.clear();
    }
}

impl Default for AABBTree {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use physics_math::Vec2;

    #[test]
    fn test_insert_and_query() {
        let mut tree = AABBTree::new();

        let aabb1 = AABB::new(Vec2::new(0.0, 0.0), Vec2::new(1.0, 1.0));
        let aabb2 = AABB::new(Vec2::new(0.5, 0.5), Vec2::new(1.5, 1.5));
        let aabb3 = AABB::new(Vec2::new(2.0, 2.0), Vec2::new(3.0, 3.0));

        let p1 = tree.insert_proxy(aabb1);
        let p2 = tree.insert_proxy(aabb2);
        let p3 = tree.insert_proxy(aabb3);

        assert_eq!(tree.proxy_count(), 3);

        let query_aabb = AABB::new(Vec2::new(0.25, 0.25), Vec2::new(1.25, 1.25));
        let results = tree.query(&query_aabb);

        assert_eq!(results.len(), 2);
        assert!(results.contains(&p1));
        assert!(results.contains(&p2));
        assert!(!results.contains(&p3));
    }

    #[test]
    fn test_update() {
        let mut tree = AABBTree::new();

        let aabb = AABB::new(Vec2::new(0.0, 0.0), Vec2::new(1.0, 1.0));
        let proxy = tree.insert_proxy(aabb);

        let new_aabb = AABB::new(Vec2::new(5.0, 5.0), Vec2::new(6.0, 6.0));
        let updated = tree.update_proxy(proxy, new_aabb);
        assert!(updated);

        let query_aabb = AABB::new(Vec2::new(4.5, 4.5), Vec2::new(6.5, 6.5));
        let results = tree.query(&query_aabb);
        assert_eq!(results.len(), 1);
        assert!(results.contains(&proxy));
    }

    #[test]
    fn test_remove() {
        let mut tree = AABBTree::new();

        let aabb = AABB::new(Vec2::new(0.0, 0.0), Vec2::new(1.0, 1.0));
        let proxy = tree.insert_proxy(aabb);
        assert_eq!(tree.proxy_count(), 1);

        tree.remove_proxy(proxy);
        assert_eq!(tree.proxy_count(), 0);

        let query_aabb = AABB::new(Vec2::new(-1.0, -1.0), Vec2::new(2.0, 2.0));
        let results = tree.query(&query_aabb);
        assert_eq!(results.len(), 0);
    }
}
