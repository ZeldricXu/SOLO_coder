use wasm_bindgen::prelude::*;
use serde::{Serialize, Deserialize};
use crate::types::{Point, Rect, Circle, Ellipse, Polygon, Star, Line, Scalar};

pub trait BoundingBox {
    fn bounding_box(&self) -> Rect;
}

impl BoundingBox for Rect {
    fn bounding_box(&self) -> Rect {
        *self
    }
}

impl BoundingBox for Circle {
    fn bounding_box(&self) -> Rect {
        self.bounding_box()
    }
}

impl BoundingBox for Ellipse {
    fn bounding_box(&self) -> Rect {
        self.bounding_box()
    }
}

impl BoundingBox for Polygon {
    fn bounding_box(&self) -> Rect {
        self.bounding_box()
    }
}

impl BoundingBox for Star {
    fn bounding_box(&self) -> Rect {
        self.bounding_box()
    }
}

impl BoundingBox for Line {
    fn bounding_box(&self) -> Rect {
        Rect::from_points(&self.start, &self.end)
    }
}

pub trait ContainsPoint {
    fn contains_point(&self, p: &Point) -> bool;
}

impl ContainsPoint for Rect {
    fn contains_point(&self, p: &Point) -> bool {
        self.contains_point(p)
    }
}

impl ContainsPoint for Circle {
    fn contains_point(&self, p: &Point) -> bool {
        self.contains_point(p)
    }
}

impl ContainsPoint for Ellipse {
    fn contains_point(&self, p: &Point) -> bool {
        self.contains_point(p)
    }
}

impl ContainsPoint for Polygon {
    fn contains_point(&self, p: &Point) -> bool {
        self.contains_point(p)
    }
}

pub trait CollidesWith<T> {
    fn collides_with(&self, other: &T) -> bool;
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct CollisionDetector;

#[wasm_bindgen]
impl CollisionDetector {
    pub fn rect_rect(a: &Rect, b: &Rect) -> bool {
        a.intersects(b)
    }

    pub fn rect_circle(rect: &Rect, circle: &Circle) -> bool {
        let closest_x = circle.center.x.max(rect.x).min(rect.x + rect.width);
        let closest_y = circle.center.y.max(rect.y).min(rect.y + rect.height);
        let dx = circle.center.x - closest_x;
        let dy = circle.center.y - closest_y;
        (dx * dx + dy * dy) <= circle.radius * circle.radius
    }

    pub fn rect_point(rect: &Rect, point: &Point) -> bool {
        rect.contains_point(point)
    }

    pub fn circle_circle(a: &Circle, b: &Circle) -> bool {
        let dx = a.center.x - b.center.x;
        let dy = a.center.y - b.center.y;
        let dist_sq = dx * dx + dy * dy;
        let r_sum = a.radius + b.radius;
        dist_sq <= r_sum * r_sum
    }

    pub fn circle_point(circle: &Circle, point: &Point) -> bool {
        circle.contains_point(point)
    }

    pub fn circle_line(circle: &Circle, line: &Line) -> bool {
        let closest = line.closest_point(&circle.center);
        circle.center.distance(&closest) <= circle.radius
    }

    pub fn ellipse_point(ellipse: &Ellipse, point: &Point) -> bool {
        ellipse.contains_point(point)
    }

    pub fn polygon_point(polygon: &Polygon, point: &Point) -> bool {
        polygon.contains_point(point)
    }

    pub fn polygon_rect(polygon: &Polygon, rect: &Rect) -> bool {
        if !polygon.bounding_box().intersects(rect) {
            return false;
        }
        if rect.contains_point(&polygon.centroid()) {
            return true;
        }
        let points = polygon.points();
        for p in &points {
            if rect.contains_point(p) {
                return true;
            }
        }
        let corners = [
            rect.top_left(),
            rect.top_right(),
            rect.bottom_right(),
            rect.bottom_left(),
        ];
        for c in &corners {
            if polygon.contains_point(c) {
                return true;
            }
        }
        false
    }

    pub fn polygon_circle(polygon: &Polygon, circle: &Circle) -> bool {
        if !polygon.bounding_box().intersects(&circle.bounding_box()) {
            return false;
        }
        if polygon.contains_point(&circle.center) {
            return true;
        }
        let points = polygon.points();
        for i in 0..points.len() {
            let j = (i + 1) % points.len();
            let edge = Line::new(points[i], points[j]);
            let closest = edge.closest_point(&circle.center);
            if circle.center.distance(&closest) <= circle.radius {
                return true;
            }
        }
        false
    }

    pub fn line_point(line: &Line, point: &Point, tolerance: Scalar) -> bool {
        line.distance_to_point(point) <= tolerance
    }

    pub fn line_line(a: &Line, b: &Line) -> bool {
        Self::line_intersection(a, b).is_some()
    }

    pub fn line_intersection(a: &Line, b: &Line) -> Option<Point> {
        let x1 = a.start.x;
        let y1 = a.start.y;
        let x2 = a.end.x;
        let y2 = a.end.y;
        let x3 = b.start.x;
        let y3 = b.start.y;
        let x4 = b.end.x;
        let y4 = b.end.y;

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

    pub fn star_point(star: &Star, point: &Point) -> bool {
        star.to_polygon().contains_point(point)
    }

    pub fn bbox_union(boxes: Vec<Rect>) -> Option<Rect> {
        if boxes.is_empty() {
            return None;
        }
        let mut result = boxes[0];
        for b in &boxes[1..] {
            result = result.union(b);
        }
        Some(result)
    }

    pub fn bbox_intersection(boxes: Vec<Rect>) -> Option<Rect> {
        if boxes.is_empty() {
            return None;
        }
        let mut result = boxes[0];
        for b in &boxes[1..] {
            match result.intersection(b) {
                Some(r) => result = r,
                None => return None,
            }
        }
        Some(result)
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct PointContainment;

#[wasm_bindgen]
impl PointContainment {
    pub fn in_rect(point: &Point, rect: &Rect) -> bool {
        rect.contains_point(point)
    }

    pub fn in_circle(point: &Point, circle: &Circle) -> bool {
        circle.contains_point(point)
    }

    pub fn in_ellipse(point: &Point, ellipse: &Ellipse) -> bool {
        ellipse.contains_point(point)
    }

    pub fn in_polygon(point: &Point, polygon: &Polygon) -> bool {
        polygon.contains_point(point)
    }

    pub fn in_star(point: &Point, star: &Star) -> bool {
        star.to_polygon().contains_point(point)
    }

    pub fn on_line(point: &Point, line: &Line, tolerance: Scalar) -> bool {
        line.distance_to_point(point) <= tolerance
    }

    pub fn distance_to_rect(point: &Point, rect: &Rect) -> Scalar {
        let closest_x = point.x.max(rect.x).min(rect.x + rect.width);
        let closest_y = point.y.max(rect.y).min(rect.y + rect.height);
        let dx = point.x - closest_x;
        let dy = point.y - closest_y;
        (dx * dx + dy * dy).sqrt()
    }

    pub fn distance_to_circle(point: &Point, circle: &Circle) -> Scalar {
        (point.distance(&circle.center) - circle.radius).abs()
    }

    pub fn distance_to_polygon(point: &Point, polygon: &Polygon) -> Scalar {
        if polygon.contains_point(point) {
            return 0.0;
        }
        let points = polygon.points();
        let mut min_dist = Scalar::MAX;
        for i in 0..points.len() {
            let j = (i + 1) % points.len();
            let edge = Line::new(points[i], points[j]);
            let dist = edge.distance_to_point(point);
            min_dist = min_dist.min(dist);
        }
        min_dist
    }
}
