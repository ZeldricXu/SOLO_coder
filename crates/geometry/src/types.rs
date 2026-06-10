use wasm_bindgen::prelude::*;
use serde::{Serialize, Deserialize};

pub type Scalar = f64;

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct Point {
    pub x: Scalar,
    pub y: Scalar,
}

#[wasm_bindgen]
impl Point {
    #[wasm_bindgen(constructor)]
    pub fn new(x: Scalar, y: Scalar) -> Self {
        Self { x, y }
    }

    pub fn zero() -> Self {
        Self { x: 0.0, y: 0.0 }
    }

    pub fn distance(&self, other: &Point) -> Scalar {
        let dx = self.x - other.x;
        let dy = self.y - other.y;
        (dx * dx + dy * dy).sqrt()
    }

    pub fn add(&self, other: &Point) -> Point {
        Point::new(self.x + other.x, self.y + other.y)
    }

    pub fn sub(&self, other: &Point) -> Point {
        Point::new(self.x - other.x, self.y - other.y)
    }

    pub fn scale(&self, factor: Scalar) -> Point {
        Point::new(self.x * factor, self.y * factor)
    }

    pub fn length(&self) -> Scalar {
        (self.x * self.x + self.y * self.y).sqrt()
    }

    pub fn normalize(&self) -> Point {
        let len = self.length();
        if len == 0.0 {
            Point::zero()
        } else {
            Point::new(self.x / len, self.y / len)
        }
    }

    pub fn dot(&self, other: &Point) -> Scalar {
        self.x * other.x + self.y * other.y
    }

    pub fn cross(&self, other: &Point) -> Scalar {
        self.x * other.y - self.y * other.x
    }
}

impl From<lyon_geom::Point<Scalar>> for Point {
    fn from(p: lyon_geom::Point<Scalar>) -> Self {
        Point::new(p.x, p.y)
    }
}

impl From<Point> for lyon_geom::Point<Scalar> {
    fn from(p: Point) -> Self {
        lyon_geom::Point::new(p.x, p.y)
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct Vector {
    pub x: Scalar,
    pub y: Scalar,
}

#[wasm_bindgen]
impl Vector {
    #[wasm_bindgen(constructor)]
    pub fn new(x: Scalar, y: Scalar) -> Self {
        Self { x, y }
    }

    pub fn zero() -> Self {
        Self { x: 0.0, y: 0.0 }
    }

    pub fn from_points(from: &Point, to: &Point) -> Self {
        Vector::new(to.x - from.x, to.y - from.y)
    }

    pub fn length(&self) -> Scalar {
        (self.x * self.x + self.y * self.y).sqrt()
    }

    pub fn normalize(&self) -> Vector {
        let len = self.length();
        if len == 0.0 {
            Vector::zero()
        } else {
            Vector::new(self.x / len, self.y / len)
        }
    }

    pub fn dot(&self, other: &Vector) -> Scalar {
        self.x * other.x + self.y * other.y
    }

    pub fn cross(&self, other: &Vector) -> Scalar {
        self.x * other.y - self.y * other.x
    }

    pub fn angle(&self) -> Scalar {
        self.y.atan2(self.x)
    }

    pub fn rotate(&self, angle: Scalar) -> Vector {
        let cos = angle.cos();
        let sin = angle.sin();
        Vector::new(
            self.x * cos - self.y * sin,
            self.x * sin + self.y * cos,
        )
    }

    pub fn scale(&self, factor: Scalar) -> Vector {
        Vector::new(self.x * factor, self.y * factor)
    }

    pub fn add(&self, other: &Vector) -> Vector {
        Vector::new(self.x + other.x, self.y + other.y)
    }

    pub fn sub(&self, other: &Vector) -> Vector {
        Vector::new(self.x - other.x, self.y - other.y)
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct Line {
    pub start: Point,
    pub end: Point,
}

#[wasm_bindgen]
impl Line {
    #[wasm_bindgen(constructor)]
    pub fn new(start: Point, end: Point) -> Self {
        Self { start, end }
    }

    pub fn length(&self) -> Scalar {
        self.start.distance(&self.end)
    }

    pub fn midpoint(&self) -> Point {
        Point::new(
            (self.start.x + self.end.x) / 2.0,
            (self.start.y + self.end.y) / 2.0,
        )
    }

    pub fn direction(&self) -> Vector {
        Vector::from_points(&self.start, &self.end)
    }

    pub fn closest_point(&self, p: &Point) -> Point {
        let v = Vector::from_points(&self.start, &self.end);
        let w = Vector::from_points(&self.start, p);
        let len_sq = v.dot(&v);
        if len_sq == 0.0 {
            return self.start;
        }
        let mut t = w.dot(&v) / len_sq;
        t = t.max(0.0).min(1.0);
        Point::new(
            self.start.x + t * v.x,
            self.start.y + t * v.y,
        )
    }

    pub fn distance_to_point(&self, p: &Point) -> Scalar {
        p.distance(&self.closest_point(p))
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct Rect {
    pub x: Scalar,
    pub y: Scalar,
    pub width: Scalar,
    pub height: Scalar,
}

#[wasm_bindgen]
impl Rect {
    #[wasm_bindgen(constructor)]
    pub fn new(x: Scalar, y: Scalar, width: Scalar, height: Scalar) -> Self {
        Self { x, y, width, height }
    }

    pub fn from_points(min: &Point, max: &Point) -> Self {
        Rect::new(
            min.x.min(max.x),
            min.y.min(max.y),
            (max.x - min.x).abs(),
            (max.y - min.y).abs(),
        )
    }

    pub fn from_center_size(center: &Point, width: Scalar, height: Scalar) -> Self {
        Rect::new(
            center.x - width / 2.0,
            center.y - height / 2.0,
            width,
            height,
        )
    }

    pub fn min(&self) -> Point {
        Point::new(self.x, self.y)
    }

    pub fn max(&self) -> Point {
        Point::new(self.x + self.width, self.y + self.height)
    }

    pub fn center(&self) -> Point {
        Point::new(
            self.x + self.width / 2.0,
            self.y + self.height / 2.0,
        )
    }

    pub fn top_left(&self) -> Point { Point::new(self.x, self.y) }
    pub fn top_right(&self) -> Point { Point::new(self.x + self.width, self.y) }
    pub fn bottom_left(&self) -> Point { Point::new(self.x, self.y + self.height) }
    pub fn bottom_right(&self) -> Point { Point::new(self.x + self.width, self.y + self.height) }

    pub fn left(&self) -> Scalar { self.x }
    pub fn right(&self) -> Scalar { self.x + self.width }
    pub fn top(&self) -> Scalar { self.y }
    pub fn bottom(&self) -> Scalar { self.y + self.height }

    pub fn area(&self) -> Scalar {
        self.width * self.height
    }

    pub fn perimeter(&self) -> Scalar {
        2.0 * (self.width + self.height)
    }

    pub fn contains_point(&self, p: &Point) -> bool {
        p.x >= self.x && p.x <= self.x + self.width &&
        p.y >= self.y && p.y <= self.y + self.height
    }

    pub fn intersects(&self, other: &Rect) -> bool {
        self.x < other.x + other.width &&
        self.x + self.width > other.x &&
        self.y < other.y + other.height &&
        self.y + self.height > other.y
    }

    pub fn union(&self, other: &Rect) -> Rect {
        let min_x = self.x.min(other.x);
        let min_y = self.y.min(other.y);
        let max_x = (self.x + self.width).max(other.x + other.width);
        let max_y = (self.y + self.height).max(other.y + other.height);
        Rect::new(min_x, min_y, max_x - min_x, max_y - min_y)
    }

    pub fn intersection(&self, other: &Rect) -> Option<Rect> {
        let min_x = self.x.max(other.x);
        let min_y = self.y.max(other.y);
        let max_x = (self.x + self.width).min(other.x + other.width);
        let max_y = (self.y + self.height).min(other.y + other.height);
        if max_x > min_x && max_y > min_y {
            Some(Rect::new(min_x, min_y, max_x - min_x, max_y - min_y))
        } else {
            None
        }
    }

    pub fn inflate(&self, amount: Scalar) -> Rect {
        Rect::new(
            self.x - amount,
            self.y - amount,
            self.width + 2.0 * amount,
            self.height + 2.0 * amount,
        )
    }

    pub fn translate(&self, dx: Scalar, dy: Scalar) -> Rect {
        Rect::new(self.x + dx, self.y + dy, self.width, self.height)
    }
}

impl From<lyon_geom::Box2D<Scalar>> for Rect {
    fn from(b: lyon_geom::Box2D<Scalar>) -> Self {
        Rect::new(b.min.x, b.min.y, b.max.x - b.min.x, b.max.y - b.min.y)
    }
}

impl From<Rect> for lyon_geom::Box2D<Scalar> {
    fn from(r: Rect) -> Self {
        lyon_geom::Box2D::new(
            lyon_geom::Point::new(r.x, r.y),
            lyon_geom::Point::new(r.x + r.width, r.y + r.height),
        )
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct Circle {
    pub center: Point,
    pub radius: Scalar,
}

#[wasm_bindgen]
impl Circle {
    #[wasm_bindgen(constructor)]
    pub fn new(center: Point, radius: Scalar) -> Self {
        Self { center, radius }
    }

    pub fn area(&self) -> Scalar {
        std::f64::consts::PI * self.radius * self.radius
    }

    pub fn circumference(&self) -> Scalar {
        2.0 * std::f64::consts::PI * self.radius
    }

    pub fn bounding_box(&self) -> Rect {
        Rect::from_center_size(&self.center, 2.0 * self.radius, 2.0 * self.radius)
    }

    pub fn contains_point(&self, p: &Point) -> bool {
        self.center.distance(p) <= self.radius
    }

    pub fn point_on_circumference(&self, angle: Scalar) -> Point {
        Point::new(
            self.center.x + self.radius * angle.cos(),
            self.center.y + self.radius * angle.sin(),
        )
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct Ellipse {
    pub center: Point,
    pub radius_x: Scalar,
    pub radius_y: Scalar,
    pub rotation: Scalar,
}

#[wasm_bindgen]
impl Ellipse {
    #[wasm_bindgen(constructor)]
    pub fn new(center: Point, radius_x: Scalar, radius_y: Scalar, rotation: Scalar) -> Self {
        Self { center, radius_x, radius_y, rotation }
    }

    pub fn axis_aligned(center: Point, radius_x: Scalar, radius_y: Scalar) -> Self {
        Self { center, radius_x, radius_y, rotation: 0.0 }
    }

    pub fn area(&self) -> Scalar {
        std::f64::consts::PI * self.radius_x * self.radius_y
    }

    pub fn bounding_box(&self) -> Rect {
        let cos = self.rotation.cos();
        let sin = self.rotation.sin();
        let dx = (self.radius_x * cos).abs().max((self.radius_y * sin).abs());
        let dy = (self.radius_x * sin).abs().max((self.radius_y * cos).abs());
        Rect::from_center_size(&self.center, 2.0 * dx, 2.0 * dy)
    }

    pub fn contains_point(&self, p: &Point) -> bool {
        let cos = (-self.rotation).cos();
        let sin = (-self.rotation).sin();
        let dx = p.x - self.center.x;
        let dy = p.y - self.center.y;
        let x = dx * cos - dy * sin;
        let y = dx * sin + dy * cos;
        (x * x) / (self.radius_x * self.radius_x) + (y * y) / (self.radius_y * self.radius_y) <= 1.0
    }

    pub fn point_on_circumference(&self, angle: Scalar) -> Point {
        let x = self.radius_x * angle.cos();
        let y = self.radius_y * angle.sin();
        let cos = self.rotation.cos();
        let sin = self.rotation.sin();
        Point::new(
            self.center.x + x * cos - y * sin,
            self.center.y + x * sin + y * cos,
        )
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Polygon {
    points: Vec<Point>,
}

#[wasm_bindgen]
impl Polygon {
    #[wasm_bindgen(constructor)]
    pub fn new(points: Vec<Point>) -> Self {
        Self { points }
    }

    pub fn empty() -> Self {
        Self { points: Vec::new() }
    }

    pub fn points(&self) -> Vec<Point> {
        self.points.clone()
    }

    pub fn num_points(&self) -> usize {
        self.points.len()
    }

    pub fn get_point(&self, index: usize) -> Option<Point> {
        self.points.get(index).copied()
    }

    pub fn add_point(&mut self, p: Point) {
        self.points.push(p);
    }

    pub fn clear(&mut self) {
        self.points.clear();
    }

    pub fn is_closed(&self) -> bool {
        self.points.len() >= 3
    }

    pub fn area(&self) -> Scalar {
        if self.points.len() < 3 {
            return 0.0;
        }
        let mut area = 0.0;
        let n = self.points.len();
        for i in 0..n {
            let j = (i + 1) % n;
            area += self.points[i].x * self.points[j].y;
            area -= self.points[j].x * self.points[i].y;
        }
        area.abs() / 2.0
    }

    pub fn perimeter(&self) -> Scalar {
        if self.points.len() < 2 {
            return 0.0;
        }
        let mut perimeter = 0.0;
        let n = self.points.len();
        for i in 0..n {
            let j = (i + 1) % n;
            perimeter += self.points[i].distance(&self.points[j]);
        }
        perimeter
    }

    pub fn centroid(&self) -> Point {
        if self.points.is_empty() {
            return Point::zero();
        }
        let mut cx = 0.0;
        let mut cy = 0.0;
        for p in &self.points {
            cx += p.x;
            cy += p.y;
        }
        let n = self.points.len() as Scalar;
        Point::new(cx / n, cy / n)
    }

    pub fn bounding_box(&self) -> Rect {
        if self.points.is_empty() {
            return Rect::new(0.0, 0.0, 0.0, 0.0);
        }
        let mut min_x = self.points[0].x;
        let mut min_y = self.points[0].y;
        let mut max_x = self.points[0].x;
        let mut max_y = self.points[0].y;
        for p in &self.points {
            min_x = min_x.min(p.x);
            min_y = min_y.min(p.y);
            max_x = max_x.max(p.x);
            max_y = max_y.max(p.y);
        }
        Rect::new(min_x, min_y, max_x - min_x, max_y - min_y)
    }

    pub fn contains_point(&self, p: &Point) -> bool {
        if self.points.len() < 3 {
            return false;
        }
        let mut inside = false;
        let n = self.points.len();
        let mut j = n - 1;
        for i in 0..n {
            let xi = self.points[i].x;
            let yi = self.points[i].y;
            let xj = self.points[j].x;
            let yj = self.points[j].y;
            if ((yi > p.y) != (yj > p.y)) &&
               (p.x < (xj - xi) * (p.y - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
            j = i;
        }
        inside
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct Star {
    pub center: Point,
    pub outer_radius: Scalar,
    pub inner_radius: Scalar,
    pub num_points: u32,
    pub rotation: Scalar,
}

#[wasm_bindgen]
impl Star {
    #[wasm_bindgen(constructor)]
    pub fn new(
        center: Point,
        outer_radius: Scalar,
        inner_radius: Scalar,
        num_points: u32,
        rotation: Scalar,
    ) -> Self {
        Self { center, outer_radius, inner_radius, num_points, rotation }
    }

    pub fn to_polygon(&self) -> Polygon {
        let mut points = Vec::new();
        let num = self.num_points as usize;
        for i in 0..num * 2 {
            let angle = self.rotation + (i as Scalar) * std::f64::consts::PI / (num as Scalar);
            let radius = if i % 2 == 0 { self.outer_radius } else { self.inner_radius };
            points.push(Point::new(
                self.center.x + radius * angle.cos(),
                self.center.y + radius * angle.sin(),
            ));
        }
        Polygon::new(points)
    }

    pub fn bounding_box(&self) -> Rect {
        Rect::from_center_size(
            &self.center,
            2.0 * self.outer_radius,
            2.0 * self.outer_radius,
        )
    }
}
