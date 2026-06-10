use wasm_bindgen::prelude::*;
use serde::{Serialize, Deserialize};
use crate::types::{Point, Polygon, Rect, Scalar};

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum BooleanOp {
    Union,
    Intersection,
    Difference,
    Xor,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum EdgeType {
    Normal,
    NonContributing,
    SameTransition,
    DifferentTransition,
}

#[derive(Debug, Clone)]
struct Edge {
    start: Point,
    end: Point,
    edge_type: EdgeType,
    side: u8,
}

fn point_on_segment(p: &Point, a: &Point, b: &Point, epsilon: Scalar) -> bool {
    let cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x);
    if cross.abs() > epsilon {
        return false;
    }
    let dot = (p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y);
    if dot < -epsilon {
        return false;
    }
    let len_sq = (b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y);
    if dot > len_sq + epsilon {
        return false;
    }
    true
}

fn segment_intersection(
    a1: &Point,
    a2: &Point,
    b1: &Point,
    b2: &Point,
) -> Option<Point> {
    let x1 = a1.x;
    let y1 = a1.y;
    let x2 = a2.x;
    let y2 = a2.y;
    let x3 = b1.x;
    let y3 = b1.y;
    let x4 = b2.x;
    let y4 = b2.y;

    let denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
    if denom.abs() < 1e-10 {
        return None;
    }

    let t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom;
    let u = -((x1 - x2) * (y1 - y3) - (y1 - y2) * (x1 - x3)) / denom;

    if t >= 0.0 && t <= 1.0 && u >= 0.0 && u <= 1.0 {
        Some(Point::new(
            x1 + t * (x2 - x1),
            y1 + t * (y2 - y1),
        ))
    } else {
        None
    }
}

fn point_in_polygon_wn(p: &Point, poly: &[Point]) -> i32 {
    let mut wn = 0;
    let n = poly.len();
    for i in 0..n {
        let j = (i + 1) % n;
        let yi = poly[i].y;
        let yj = poly[j].y;
        if yi <= p.y {
            if yj > p.y {
                let cross = (poly[j].x - poly[i].x) * (p.y - poly[i].y)
                    - (poly[j].y - poly[i].y) * (p.x - poly[i].x);
                if cross > 0.0 {
                    wn += 1;
                }
            }
        } else {
            if yj <= p.y {
                let cross = (poly[j].x - poly[i].x) * (p.y - poly[i].y)
                    - (poly[j].y - poly[i].y) * (p.x - poly[i].x);
                if cross < 0.0 {
                    wn -= 1;
                }
            }
        }
    }
    wn
}

fn polygon_area(points: &[Point]) -> Scalar {
    if points.len() < 3 {
        return 0.0;
    }
    let mut area = 0.0;
    let n = points.len();
    for i in 0..n {
        let j = (i + 1) % n;
        area += points[i].x * points[j].y;
        area -= points[j].x * points[i].y;
    }
    area / 2.0
}

fn is_clockwise(points: &[Point]) -> bool {
    polygon_area(points) < 0.0
}

fn add_intersection_points(
    poly_a: &[Point],
    poly_b: &[Point],
) -> (Vec<Point>, Vec<Point>) {
    let mut result_a: Vec<Point> = Vec::new();
    let mut result_b: Vec<Point> = Vec::new();

    for p in poly_a {
        result_a.push(*p);
    }
    for p in poly_b {
        result_b.push(*p);
    }

    let mut insertions_a: Vec<(usize, Point)> = Vec::new();
    let mut insertions_b: Vec<(usize, Point)> = Vec::new();

    let n_a = poly_a.len();
    let n_b = poly_b.len();

    for i in 0..n_a {
        let a1 = poly_a[i];
        let a2 = poly_a[(i + 1) % n_a];
        for j in 0..n_b {
            let b1 = poly_b[j];
            let b2 = poly_b[(j + 1) % n_b];
            if let Some(intersect) = segment_intersection(&a1, &a2, &b1, &b2) {
                insertions_a.push((i + 1, intersect));
                insertions_b.push((j + 1, intersect));
            }
        }
    }

    insertions_a.sort_by(|a, b| a.0.cmp(&b.0));
    insertions_b.sort_by(|a, b| a.0.cmp(&b.0));

    let mut offset = 0;
    for (idx, p) in insertions_a {
        result_a.insert(idx + offset, p);
        offset += 1;
    }

    offset = 0;
    for (idx, p) in insertions_b {
        result_b.insert(idx + offset, p);
        offset += 1;
    }

    (result_a, result_b)
}

fn mark_edges(poly_a: &[Point], poly_b: &[Point]) -> Vec<Edge> {
    let mut edges = Vec::new();

    let n_a = poly_a.len();
    for i in 0..n_a {
        let start = poly_a[i];
        let end = poly_a[(i + 1) % n_a];
        let mid = Point::new((start.x + end.x) / 2.0, (start.y + end.y) / 2.0);
        let inside = point_in_polygon_wn(&mid, poly_b) != 0;
        let edge_type = if inside {
            EdgeType::Normal
        } else {
            EdgeType::Normal
        };
        edges.push(Edge {
            start,
            end,
            edge_type,
            side: 0,
        });
    }

    let n_b = poly_b.len();
    for i in 0..n_b {
        let start = poly_b[i];
        let end = poly_b[(i + 1) % n_b];
        let mid = Point::new((start.x + end.x) / 2.0, (start.y + end.y) / 2.0);
        let inside = point_in_polygon_wn(&mid, poly_a) != 0;
        let edge_type = if inside {
            EdgeType::Normal
        } else {
            EdgeType::Normal
        };
        edges.push(Edge {
            start,
            end,
            edge_type,
            side: 1,
        });
    }

    edges
}

fn collect_boundary(poly: &[Point], other: &[Point], op: BooleanOp) -> Vec<Point> {
    let mut result = Vec::new();
    let n = poly.len();
    for i in 0..n {
        let start = poly[i];
        let end = poly[(i + 1) % n];
        let mid = Point::new((start.x + end.x) / 2.0, (start.y + end.y) / 2.0);
        let inside_other = point_in_polygon_wn(&mid, other) != 0;

        let include = match op {
            BooleanOp::Union => !inside_other,
            BooleanOp::Intersection => inside_other,
            BooleanOp::Difference => !inside_other,
            BooleanOp::Xor => true,
        };

        if include {
            if result.is_empty() || !points_equal(result.last().unwrap(), &start) {
                result.push(start);
            }
            result.push(end);
        }
    }
    result
}

fn points_equal(a: &Point, b: &Point) -> bool {
    (a.x - b.x).abs() < 1e-6 && (a.y - b.y).abs() < 1e-6
}

fn dedupe_points(points: &[Point]) -> Vec<Point> {
    let mut result = Vec::new();
    for p in points {
        if result.is_empty() || !points_equal(result.last().unwrap(), p) {
            result.push(*p);
        }
    }
    if result.len() >= 2 && points_equal(result.first().unwrap(), result.last().unwrap()) {
        result.pop();
    }
    result
}

fn simple_polygon_boolean(poly_a: &Polygon, poly_b: &Polygon, op: BooleanOp) -> Vec<Polygon> {
    let points_a = poly_a.points();
    let points_b = poly_b.points();

    if points_a.len() < 3 || points_b.len() < 3 {
        return Vec::new();
    }

    let (aug_a, aug_b) = add_intersection_points(&points_a, &points_b);

    let mut result_points = Vec::new();

    let boundary_a = collect_boundary(&aug_a, &aug_b, op);
    let boundary_b = collect_boundary(&aug_b, &aug_a, op);

    result_points.extend(boundary_a);
    result_points.extend(boundary_b);

    result_points = dedupe_points(&result_points);

    if result_points.len() >= 3 {
        vec![Polygon::new(result_points)]
    } else {
        Vec::new()
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct BooleanResult {
    polygons: Vec<Polygon>,
}

#[wasm_bindgen]
impl BooleanResult {
    pub fn new(polygons: Vec<Polygon>) -> Self {
        Self { polygons }
    }

    pub fn empty() -> Self {
        Self { polygons: Vec::new() }
    }

    pub fn polygons(&self) -> Vec<Polygon> {
        self.polygons.clone()
    }

    pub fn num_polygons(&self) -> usize {
        self.polygons.len()
    }

    pub fn get_polygon(&self, index: usize) -> Option<Polygon> {
        self.polygons.get(index).cloned()
    }

    pub fn is_empty(&self) -> bool {
        self.polygons.is_empty()
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct BooleanOperator;

#[wasm_bindgen]
impl BooleanOperator {
    pub fn union(a: &Polygon, b: &Polygon) -> BooleanResult {
        BooleanResult::new(simple_polygon_boolean(a, b, BooleanOp::Union))
    }

    pub fn intersection(a: &Polygon, b: &Polygon) -> BooleanResult {
        BooleanResult::new(simple_polygon_boolean(a, b, BooleanOp::Intersection))
    }

    pub fn difference(a: &Polygon, b: &Polygon) -> BooleanResult {
        BooleanResult::new(simple_polygon_boolean(a, b, BooleanOp::Difference))
    }

    pub fn xor(a: &Polygon, b: &Polygon) -> BooleanResult {
        BooleanResult::new(simple_polygon_boolean(a, b, BooleanOp::Xor))
    }

    pub fn rect_union(a: &Rect, b: &Rect) -> Rect {
        a.union(b)
    }

    pub fn rect_intersection(a: &Rect, b: &Rect) -> Option<Rect> {
        a.intersection(b)
    }

    pub fn rect_difference(a: &Rect, b: &Rect) -> Vec<Rect> {
        let mut result = Vec::new();
        if let Some(inter) = a.intersection(b) {
            if inter == *a {
                return result;
            }
            if inter.y > a.y {
                result.push(Rect::new(a.x, a.y, a.width, inter.y - a.y));
            }
            if inter.y + inter.height < a.y + a.height {
                let top = inter.y + inter.height;
                result.push(Rect::new(a.x, top, a.width, a.y + a.height - top));
            }
            if inter.x > a.x {
                let top = inter.y.max(a.y);
                let bottom = (inter.y + inter.height).min(a.y + a.height);
                result.push(Rect::new(a.x, top, inter.x - a.x, bottom - top));
            }
            if inter.x + inter.width < a.x + a.width {
                let left = inter.x + inter.width;
                let top = inter.y.max(a.y);
                let bottom = (inter.y + inter.height).min(a.y + a.height);
                result.push(Rect::new(left, top, a.x + a.width - left, bottom - top));
            }
        } else {
            result.push(*a);
        }
        result
    }

    pub fn rect_xor(a: &Rect, b: &Rect) -> Vec<Rect> {
        let mut result = Vec::new();
        result.extend(Self::rect_difference(a, b));
        result.extend(Self::rect_difference(b, a));
        result
    }

    pub fn apply(a: &Polygon, b: &Polygon, op: BooleanOp) -> BooleanResult {
        match op {
            BooleanOp::Union => Self::union(a, b),
            BooleanOp::Intersection => Self::intersection(a, b),
            BooleanOp::Difference => Self::difference(a, b),
            BooleanOp::Xor => Self::xor(a, b),
        }
    }
}
