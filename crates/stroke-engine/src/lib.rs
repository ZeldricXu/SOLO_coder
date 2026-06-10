use wasm_bindgen::prelude::*;
use serde::{Serialize, Deserialize};
use uuid::Uuid;
use geometry::{Point, Rect, Color, StrokeOptions};

#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct StrokePoint {
    pub position: Point,
    pub pressure: f64,
    pub timestamp: f64,
}

#[wasm_bindgen]
impl StrokePoint {
    #[wasm_bindgen(constructor)]
    pub fn new(position: Point, pressure: f64, timestamp: f64) -> Self {
        Self {
            position,
            pressure,
            timestamp,
        }
    }

    #[wasm_bindgen(getter)]
    pub fn position(&self) -> Point {
        self.position
    }

    #[wasm_bindgen(getter)]
    pub fn pressure(&self) -> f64 {
        self.pressure
    }

    #[wasm_bindgen(getter)]
    pub fn timestamp(&self) -> f64 {
        self.timestamp
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Stroke {
    id: Uuid,
    points: Vec<StrokePoint>,
    color: Color,
    stroke_options: StrokeOptions,
}

#[wasm_bindgen]
impl Stroke {
    #[wasm_bindgen(constructor)]
    pub fn new(color: Color, stroke_options: StrokeOptions) -> Self {
        Self {
            id: Uuid::new_v4(),
            points: Vec::new(),
            color,
            stroke_options,
        }
    }

    #[wasm_bindgen(getter)]
    pub fn id(&self) -> String {
        self.id.to_string()
    }

    #[wasm_bindgen(getter)]
    pub fn color(&self) -> Color {
        self.color
    }

    #[wasm_bindgen(setter)]
    pub fn set_color(&mut self, color: Color) {
        self.color = color;
    }

    #[wasm_bindgen(getter)]
    pub fn stroke_options(&self) -> StrokeOptions {
        self.stroke_options
    }

    pub fn add_point(&mut self, point: StrokePoint) {
        self.points.push(point);
    }

    pub fn add_points(&mut self, points: Vec<StrokePoint>) {
        self.points.extend(points);
    }

    pub fn point_count(&self) -> usize {
        self.points.len()
    }

    pub fn get_point(&self, index: usize) -> Option<StrokePoint> {
        self.points.get(index).cloned()
    }

    pub fn bounds(&self) -> Option<Rect> {
        if self.points.is_empty() {
            return None;
        }
        let mut min_x = self.points[0].position.x;
        let mut min_y = self.points[0].position.y;
        let mut max_x = min_x;
        let mut max_y = min_y;

        for p in &self.points {
            min_x = min_x.min(p.position.x);
            min_y = min_y.min(p.position.y);
            max_x = max_x.max(p.position.x);
            max_y = max_y.max(p.position.y);
        }

        let half_width = self.stroke_options.internal_width() / 2.0;
        Some(Rect::new(
            min_x - half_width,
            min_y - half_width,
            max_x - min_x + self.stroke_options.internal_width(),
            max_y - min_y + self.stroke_options.internal_width(),
        ))
    }

    pub fn simplify(&mut self, tolerance: f64) {
        if self.points.len() <= 2 {
            return;
        }

        let mut simplified: Vec<StrokePoint> = Vec::new();
        simplified.push(self.points[0].clone());

        let mut last_kept = 0;
        for i in 1..self.points.len() - 1 {
            let dist = point_to_line_distance(
                &self.points[i].position,
                &self.points[last_kept].position,
                &self.points[i + 1].position,
            );
            if dist > tolerance {
                simplified.push(self.points[i].clone());
                last_kept = i;
            }
        }

        simplified.push(self.points[self.points.len() - 1].clone());
        self.points = simplified;
    }
}

fn point_to_line_distance(p: &Point, a: &Point, b: &Point) -> f64 {
    let dx = b.x - a.x;
    let dy = b.y - a.y;
    let len_sq = dx * dx + dy * dy;

    if len_sq < 1e-10 {
        let ddx = p.x - a.x;
        let ddy = p.y - a.y;
        return (ddx * ddx + ddy * ddy).sqrt();
    }

    let mut t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len_sq;
    t = t.max(0.0).min(1.0);

    let proj_x = a.x + t * dx;
    let proj_y = a.y + t * dy;

    let ddx = p.x - proj_x;
    let ddy = p.y - proj_y;
    (ddx * ddx + ddy * ddy).sqrt()
}

#[wasm_bindgen(start)]
pub fn init() {
    console_error_panic_hook::set_once();
}
