use wasm_bindgen::prelude::*;
use serde::{Serialize, Deserialize};
use crate::types::{Point, Rect, Line, Scalar};

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum SnapType {
    Grid,
    Edge,
    Center,
    Guide,
    Vertex,
    Midpoint,
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct SnapResult {
    pub snapped: Point,
    pub distance: Scalar,
    pub snap_type: SnapType,
    pub guide_line: Option<GuideLine>,
}

#[wasm_bindgen]
impl SnapResult {
    #[wasm_bindgen(constructor)]
    pub fn new(snapped: Point, distance: Scalar, snap_type: SnapType, guide_line: Option<GuideLine>) -> Self {
        Self { snapped, distance, snap_type, guide_line }
    }

    pub fn has_guide(&self) -> bool {
        self.guide_line.is_some()
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct GuideLine {
    pub start: Point,
    pub end: Point,
    pub is_horizontal: bool,
}

#[wasm_bindgen]
impl GuideLine {
    #[wasm_bindgen(constructor)]
    pub fn new(start: Point, end: Point, is_horizontal: bool) -> Self {
        Self { start, end, is_horizontal }
    }

    pub fn horizontal(y: Scalar, x_start: Scalar, x_end: Scalar) -> Self {
        Self {
            start: Point::new(x_start, y),
            end: Point::new(x_end, y),
            is_horizontal: true,
        }
    }

    pub fn vertical(x: Scalar, y_start: Scalar, y_end: Scalar) -> Self {
        Self {
            start: Point::new(x, y_start),
            end: Point::new(x, y_end),
            is_horizontal: false,
        }
    }

    pub fn to_line(&self) -> Line {
        Line::new(self.start, self.end)
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct GridConfig {
    pub enabled: bool,
    pub cell_size: Scalar,
    pub origin: Point,
    pub tolerance: Scalar,
}

#[wasm_bindgen]
impl GridConfig {
    #[wasm_bindgen(constructor)]
    pub fn new(enabled: bool, cell_size: Scalar, origin: Point, tolerance: Scalar) -> Self {
        Self { enabled, cell_size, origin, tolerance }
    }

    pub fn default() -> Self {
        Self {
            enabled: true,
            cell_size: 10.0,
            origin: Point::zero(),
            tolerance: 5.0,
        }
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct SnapConfig {
    pub grid: GridConfig,
    pub edge_tolerance: Scalar,
    pub center_tolerance: Scalar,
    pub vertex_tolerance: Scalar,
    pub midpoint_tolerance: Scalar,
    pub enable_guides: bool,
}

#[wasm_bindgen]
impl SnapConfig {
    #[wasm_bindgen(constructor)]
    pub fn new(
        grid: GridConfig,
        edge_tolerance: Scalar,
        center_tolerance: Scalar,
        vertex_tolerance: Scalar,
        midpoint_tolerance: Scalar,
        enable_guides: bool,
    ) -> Self {
        Self {
            grid,
            edge_tolerance,
            center_tolerance,
            vertex_tolerance,
            midpoint_tolerance,
            enable_guides,
        }
    }

    pub fn default() -> Self {
        Self {
            grid: GridConfig::default(),
            edge_tolerance: 5.0,
            center_tolerance: 5.0,
            vertex_tolerance: 5.0,
            midpoint_tolerance: 5.0,
            enable_guides: true,
        }
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Snapper {
    config: SnapConfig,
    reference_rects: Vec<Rect>,
}

#[wasm_bindgen]
impl Snapper {
    #[wasm_bindgen(constructor)]
    pub fn new(config: SnapConfig) -> Self {
        Self {
            config,
            reference_rects: Vec::new(),
        }
    }

    pub fn default() -> Self {
        Self {
            config: SnapConfig::default(),
            reference_rects: Vec::new(),
        }
    }

    pub fn config(&self) -> SnapConfig {
        self.config
    }

    pub fn set_config(&mut self, config: SnapConfig) {
        self.config = config;
    }

    pub fn add_reference_rect(&mut self, rect: Rect) {
        self.reference_rects.push(rect);
    }

    pub fn clear_references(&mut self) {
        self.reference_rects.clear();
    }

    pub fn reference_rects(&self) -> Vec<Rect> {
        self.reference_rects.clone()
    }

    pub fn snap_point(&self, point: &Point) -> Option<SnapResult> {
        let mut best: Option<SnapResult> = None;

        if let Some(grid_snap) = self.snap_to_grid(point) {
            best = Self::take_better(best, grid_snap);
        }

        for rect in &self.reference_rects {
            if let Some(snap) = self.snap_to_rect_edges(point, rect) {
                best = Self::take_better(best, snap);
            }
            if let Some(snap) = self.snap_to_rect_center(point, rect) {
                best = Self::take_better(best, snap);
            }
            if let Some(snap) = self.snap_to_rect_vertices(point, rect) {
                best = Self::take_better(best, snap);
            }
            if let Some(snap) = self.snap_to_rect_midpoints(point, rect) {
                best = Self::take_better(best, snap);
            }
        }

        best
    }

    pub fn snap_rect(&self, rect: &Rect) -> Option<SnapResult> {
        let center = rect.center();
        self.snap_point(&center)
    }

    pub fn snap_to_grid(&self, point: &Point) -> Option<SnapResult> {
        if !self.config.grid.enabled {
            return None;
        }
        let grid = &self.config.grid;
        let dx = point.x - grid.origin.x;
        let dy = point.y - grid.origin.y;
        let snapped_x = grid.origin.x + (dx / grid.cell_size).round() * grid.cell_size;
        let snapped_y = grid.origin.y + (dy / grid.cell_size).round() * grid.cell_size;
        let snapped = Point::new(snapped_x, snapped_y);
        let distance = point.distance(&snapped);
        if distance <= grid.tolerance {
            Some(SnapResult::new(snapped, distance, SnapType::Grid, None))
        } else {
            None
        }
    }

    pub fn snap_to_rect_edges(&self, point: &Point, rect: &Rect) -> Option<SnapResult> {
        let tol = self.config.edge_tolerance;
        let candidates = [
            (Point::new(point.x, rect.top()), SnapType::Edge, true),
            (Point::new(point.x, rect.bottom()), SnapType::Edge, true),
            (Point::new(rect.left(), point.y), SnapType::Edge, false),
            (Point::new(rect.right(), point.y), SnapType::Edge, false),
        ];

        let mut best: Option<SnapResult> = None;
        for (snapped, snap_type, is_horizontal) in &candidates {
            let distance = point.distance(snapped);
            if distance <= tol {
                let guide = if self.config.enable_guides {
                    Some(if *is_horizontal {
                        GuideLine::horizontal(snapped.y, rect.left(), rect.right())
                    } else {
                        GuideLine::vertical(snapped.x, rect.top(), rect.bottom())
                    })
                } else {
                    None
                };
                let result = SnapResult::new(*snapped, distance, *snap_type, guide);
                best = Self::take_better(best, result);
            }
        }
        best
    }

    pub fn snap_to_rect_center(&self, point: &Point, rect: &Rect) -> Option<SnapResult> {
        let tol = self.config.center_tolerance;
        let center = rect.center();
        let distance = point.distance(&center);
        if distance <= tol {
            let guide = if self.config.enable_guides {
                Some(GuideLine::new(
                    Point::new(rect.left(), center.y),
                    Point::new(rect.right(), center.y),
                    true,
                ))
            } else {
                None
            };
            Some(SnapResult::new(center, distance, SnapType::Center, guide))
        } else {
            None
        }
    }

    pub fn snap_to_rect_vertices(&self, point: &Point, rect: &Rect) -> Option<SnapResult> {
        let tol = self.config.vertex_tolerance;
        let vertices = [
            rect.top_left(),
            rect.top_right(),
            rect.bottom_left(),
            rect.bottom_right(),
        ];

        let mut best: Option<SnapResult> = None;
        for v in &vertices {
            let distance = point.distance(v);
            if distance <= tol {
                let result = SnapResult::new(*v, distance, SnapType::Vertex, None);
                best = Self::take_better(best, result);
            }
        }
        best
    }

    pub fn snap_to_rect_midpoints(&self, point: &Point, rect: &Rect) -> Option<SnapResult> {
        let tol = self.config.midpoint_tolerance;
        let midpoints = [
            Line::new(rect.top_left(), rect.top_right()).midpoint(),
            Line::new(rect.top_right(), rect.bottom_right()).midpoint(),
            Line::new(rect.bottom_right(), rect.bottom_left()).midpoint(),
            Line::new(rect.bottom_left(), rect.top_left()).midpoint(),
        ];

        let mut best: Option<SnapResult> = None;
        for m in &midpoints {
            let distance = point.distance(m);
            if distance <= tol {
                let result = SnapResult::new(*m, distance, SnapType::Midpoint, None);
                best = Self::take_better(best, result);
            }
        }
        best
    }

    pub fn generate_guide_lines(&self, target: &Rect) -> Vec<GuideLine> {
        if !self.config.enable_guides {
            return Vec::new();
        }
        let mut guides = Vec::new();
        let target_edges = [
            target.left(),
            target.right(),
            target.center().x,
        ];
        let target_y_edges = [
            target.top(),
            target.bottom(),
            target.center().y,
        ];

        for rect in &self.reference_rects {
            for tx in &target_edges {
                for rx in [rect.left(), rect.right(), rect.center().x].iter() {
                    if (tx - rx).abs() < self.config.edge_tolerance {
                        let min_y = target.top().min(rect.top());
                        let max_y = target.bottom().max(rect.bottom());
                        guides.push(GuideLine::vertical(*rx, min_y, max_y));
                    }
                }
            }
            for ty in &target_y_edges {
                for ry in [rect.top(), rect.bottom(), rect.center().y].iter() {
                    if (ty - ry).abs() < self.config.edge_tolerance {
                        let min_x = target.left().min(rect.left());
                        let max_x = target.right().max(rect.right());
                        guides.push(GuideLine::horizontal(*ry, min_x, max_x));
                    }
                }
            }
        }
        guides
    }

    fn take_better(a: Option<SnapResult>, b: SnapResult) -> Option<SnapResult> {
        match a {
            None => Some(b),
            Some(a) => if b.distance < a.distance { Some(b) } else { Some(a) },
        }
    }
}

#[wasm_bindgen]
pub fn create_grid_snapper(cell_size: Scalar, tolerance: Scalar) -> Snapper {
    let config = SnapConfig::new(
        GridConfig::new(true, cell_size, Point::zero(), tolerance),
        5.0, 5.0, 5.0, 5.0, true,
    );
    Snapper::new(config)
}
