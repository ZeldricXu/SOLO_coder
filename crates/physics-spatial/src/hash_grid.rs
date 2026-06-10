use std::collections::HashMap;

use physics_math::Vec2;

#[derive(Clone, Debug)]
pub struct HashGrid {
    cell_size: f32,
    inv_cell_size: f32,
    table: HashMap<i64, Vec<usize>>,
}

impl HashGrid {
    pub fn new(cell_size: f32) -> Self {
        HashGrid {
            cell_size,
            inv_cell_size: 1.0 / cell_size,
            table: HashMap::new(),
        }
    }

    #[inline]
    pub fn cell_size(&self) -> f32 {
        self.cell_size
    }

    #[inline]
    fn cell_key(&self, position: Vec2) -> (i32, i32) {
        (
            (position.x * self.inv_cell_size).floor() as i32,
            (position.y * self.inv_cell_size).floor() as i32,
        )
    }

    #[inline]
    fn key_to_index(&self, cx: i32, cy: i32) -> i64 {
        ((cx as i64) << 32) | (cy as i64 & 0xFFFFFFFF)
    }

    pub fn insert(&mut self, position: Vec2, index: usize) {
        let (cx, cy) = self.cell_key(position);
        let key = self.key_to_index(cx, cy);
        self.table.entry(key).or_default().push(index);
    }

    pub fn clear(&mut self) {
        self.table.clear();
    }

    pub fn query(&self, position: Vec2, radius: f32) -> Vec<usize> {
        let mut results = Vec::new();

        let min_cx = ((position.x - radius) * self.inv_cell_size).floor() as i32;
        let max_cx = ((position.x + radius) * self.inv_cell_size).floor() as i32;
        let min_cy = ((position.y - radius) * self.inv_cell_size).floor() as i32;
        let max_cy = ((position.y + radius) * self.inv_cell_size).floor() as i32;

        for cx in min_cx..=max_cx {
            for cy in min_cy..=max_cy {
                let key = self.key_to_index(cx, cy);
                if let Some(indices) = self.table.get(&key) {
                    for &idx in indices {
                        results.push(idx);
                    }
                }
            }
        }

        results
    }

    pub fn query_filtered(&self, position: Vec2, radius: f32, exclude: usize) -> Vec<usize> {
        let mut results = Vec::new();

        let min_cx = ((position.x - radius) * self.inv_cell_size).floor() as i32;
        let max_cx = ((position.x + radius) * self.inv_cell_size).floor() as i32;
        let min_cy = ((position.y - radius) * self.inv_cell_size).floor() as i32;
        let max_cy = ((position.y + radius) * self.inv_cell_size).floor() as i32;

        for cx in min_cx..=max_cx {
            for cy in min_cy..=max_cy {
                let key = self.key_to_index(cx, cy);
                if let Some(indices) = self.table.get(&key) {
                    for &idx in indices {
                        if idx != exclude {
                            results.push(idx);
                        }
                    }
                }
            }
        }

        results
    }

    pub fn rebuild(&mut self, positions: &[Vec2]) {
        self.clear();
        for (i, pos) in positions.iter().enumerate() {
            self.insert(*pos, i);
        }
    }

    pub fn rebuild_with_filter<F>(&mut self, positions: &[Vec2], filter: F)
    where
        F: Fn(usize) -> bool,
    {
        self.clear();
        for (i, pos) in positions.iter().enumerate() {
            if filter(i) {
                self.insert(*pos, i);
            }
        }
    }

    pub fn len(&self) -> usize {
        self.table.len()
    }

    pub fn is_empty(&self) -> bool {
        self.table.is_empty()
    }

    pub fn element_count(&self) -> usize {
        self.table.values().map(|v| v.len()).sum()
    }
}

impl Default for HashGrid {
    fn default() -> Self {
        Self::new(1.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_insert_and_query() {
        let mut grid = HashGrid::new(2.0);

        let positions = vec![
            Vec2::new(0.0, 0.0),
            Vec2::new(1.0, 1.0),
            Vec2::new(5.0, 5.0),
        ];

        for (i, pos) in positions.iter().enumerate() {
            grid.insert(*pos, i);
        }

        let results = grid.query(Vec2::new(0.5, 0.5), 2.0);

        assert_eq!(results.len(), 2);
        assert!(results.contains(&0));
        assert!(results.contains(&1));
        assert!(!results.contains(&2));
    }

    #[test]
    fn test_rebuild() {
        let mut grid = HashGrid::new(2.0);

        let positions = vec![
            Vec2::new(0.0, 0.0),
            Vec2::new(3.0, 3.0),
        ];

        grid.rebuild(&positions);

        let results = grid.query(Vec2::new(0.0, 0.0), 1.0);
        assert_eq!(results.len(), 1);
        assert!(results.contains(&0));
    }

    #[test]
    fn test_clear() {
        let mut grid = HashGrid::new(1.0);
        grid.insert(Vec2::new(0.0, 0.0), 0);

        assert_eq!(grid.element_count(), 1);

        grid.clear();

        assert_eq!(grid.element_count(), 0);
        let results = grid.query(Vec2::new(0.0, 0.0), 1.0);
        assert_eq!(results.len(), 0);
    }
}
