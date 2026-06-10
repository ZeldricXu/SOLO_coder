use wasm_bindgen::prelude::*;
use crate::types::{Point, Polygon, Rect, Circle, Ellipse, Star, Scalar, ArrowStyle};
use crate::path::RenderPath;

#[wasm_bindgen]
pub struct ShapeGenerator;

#[wasm_bindgen]
impl ShapeGenerator {
    pub fn create_rect(x: Scalar, y: Scalar, width: Scalar, height: Scalar) -> Rect {
        Rect::new(x, y, width, height)
    }

    pub fn create_square(origin: Point, side: Scalar) -> Rect {
        Rect::new(origin.x, origin.y, side, side)
    }

    pub fn create_centered_rect(center: Point, width: Scalar, height: Scalar) -> Rect {
        Rect::from_center_size(&center, width, height)
    }

    pub fn create_circle(center: Point, radius: Scalar) -> Circle {
        Circle::new(center, radius)
    }

    pub fn create_ellipse(center: Point, radius_x: Scalar, radius_y: Scalar, rotation: Scalar) -> Ellipse {
        Ellipse::new(center, radius_x, radius_y, rotation)
    }

    pub fn create_axis_aligned_ellipse(center: Point, radius_x: Scalar, radius_y: Scalar) -> Ellipse {
        Ellipse::axis_aligned(center, radius_x, radius_y)
    }

    pub fn create_star(
        center: Point,
        outer_radius: Scalar,
        inner_radius: Scalar,
        num_points: u32,
        rotation: Scalar,
    ) -> Star {
        Star::new(center, outer_radius, inner_radius, num_points, rotation)
    }

    pub fn create_regular_polygon(
        center: Point,
        radius: Scalar,
        sides: u32,
        rotation: Scalar,
    ) -> Polygon {
        let mut points = Vec::new();
        let n = sides as usize;
        for i in 0..n {
            let angle = rotation + 2.0 * std::f64::consts::PI * (i as Scalar) / (sides as Scalar);
            points.push(Point::new(
                center.x + radius * angle.cos(),
                center.y + radius * angle.sin(),
            ));
        }
        Polygon::new(points)
    }

    pub fn create_triangle(center: Point, radius: Scalar, rotation: Scalar) -> Polygon {
        Self::create_regular_polygon(center, radius, 3, rotation)
    }

    pub fn create_pentagon(center: Point, radius: Scalar, rotation: Scalar) -> Polygon {
        Self::create_regular_polygon(center, radius, 5, rotation)
    }

    pub fn create_hexagon(center: Point, radius: Scalar, rotation: Scalar) -> Polygon {
        Self::create_regular_polygon(center, radius, 6, rotation)
    }

    pub fn create_octagon(center: Point, radius: Scalar, rotation: Scalar) -> Polygon {
        Self::create_regular_polygon(center, radius, 8, rotation)
    }

    pub fn create_rounded_rect(
        x: Scalar,
        y: Scalar,
        width: Scalar,
        height: Scalar,
        corner_radius: Scalar,
        segments_per_corner: u32,
    ) -> Polygon {
        let r = corner_radius.min(width / 2.0).min(height / 2.0);
        let mut points = Vec::new();
        let segs = segments_per_corner.max(1) as usize;
        let step = std::f64::consts::FRAC_PI_2 / (segs as Scalar);

        let corners = [
            (x + width - r, y + r, std::f64::consts::PI * 1.5),
            (x + width - r, y + height - r, 0.0),
            (x + r, y + height - r, std::f64::consts::FRAC_PI_2),
            (x + r, y + r, std::f64::consts::PI),
        ];

        for (cx, cy, start_angle) in &corners {
            for i in 0..segs {
                let angle = start_angle + (i as Scalar) * step;
                points.push(Point::new(
                    cx + r * angle.cos(),
                    cy + r * angle.sin(),
                ));
            }
        }

        Polygon::new(points)
    }

    pub fn create_arc(
        center: Point,
        radius: Scalar,
        start_angle: Scalar,
        end_angle: Scalar,
        segments: u32,
    ) -> Vec<Point> {
        let mut points = Vec::new();
        let n = segments.max(2) as usize;
        let step = (end_angle - start_angle) / ((n - 1) as Scalar);
        for i in 0..n {
            let angle = start_angle + (i as Scalar) * step;
            points.push(Point::new(
                center.x + radius * angle.cos(),
                center.y + radius * angle.sin(),
            ));
        }
        points
    }

    pub fn create_bezier_curve(
        p0: Point,
        p1: Point,
        p2: Point,
        p3: Point,
        segments: u32,
    ) -> Vec<Point> {
        let mut points = Vec::new();
        let n = segments.max(2) as usize;
        for i in 0..=n {
            let t = (i as Scalar) / (n as Scalar);
            let one_minus_t = 1.0 - t;
            let one_minus_t2 = one_minus_t * one_minus_t;
            let one_minus_t3 = one_minus_t2 * one_minus_t;
            let t2 = t * t;
            let t3 = t2 * t;
            points.push(Point::new(
                one_minus_t3 * p0.x + 3.0 * one_minus_t2 * t * p1.x
                    + 3.0 * one_minus_t * t2 * p2.x + t3 * p3.x,
                one_minus_t3 * p0.y + 3.0 * one_minus_t2 * t * p1.y
                    + 3.0 * one_minus_t * t2 * p2.y + t3 * p3.y,
            ));
        }
        points
    }

    pub fn create_quadratic_bezier(
        p0: Point,
        p1: Point,
        p2: Point,
        segments: u32,
    ) -> Vec<Point> {
        let mut points = Vec::new();
        let n = segments.max(2) as usize;
        for i in 0..=n {
            let t = (i as Scalar) / (n as Scalar);
            let one_minus_t = 1.0 - t;
            let one_minus_t2 = one_minus_t * one_minus_t;
            let t2 = t * t;
            points.push(Point::new(
                one_minus_t2 * p0.x + 2.0 * one_minus_t * t * p1.x + t2 * p2.x,
                one_minus_t2 * p0.y + 2.0 * one_minus_t * t * p1.y + t2 * p2.y,
            ));
        }
        points
    }

    pub fn create_star_path(
        center: Point,
        outer_r: Scalar,
        inner_r: Scalar,
        num_points: u32,
        rotation: Scalar,
    ) -> RenderPath {
        let mut path = RenderPath::empty();
        let n = num_points.max(3) as usize;
        for i in 0..n * 2 {
            let angle = rotation + (i as Scalar) * std::f64::consts::PI / (n as Scalar);
            let radius = if i % 2 == 0 { outer_r } else { inner_r };
            let p = Point::new(
                center.x + radius * angle.cos(),
                center.y + radius * angle.sin(),
            );
            if i == 0 {
                path.move_to(p);
            } else {
                path.line_to(p);
            }
        }
        path.close();
        path
    }

    pub fn create_arrow(
        start: Point,
        end: Point,
        head_style: ArrowStyle,
        tail_style: ArrowStyle,
        head_size: Scalar,
        tail_size: Scalar,
    ) -> RenderPath {
        let mut path = RenderPath::empty();

        let dx = end.x - start.x;
        let dy = end.y - start.y;
        let len = (dx * dx + dy * dy).sqrt();
        if len < 1e-6 {
            return path;
        }

        let ux = dx / len;
        let uy = dy / len;
        let nx = -uy;
        let ny = ux;

        let head_anchor: Point;
        match head_style {
            ArrowStyle::Triangle => {
                let hs = head_size.max(0.0);
                let anchor = Point::new(end.x - ux * hs, end.y - uy * hs);
                let left = Point::new(anchor.x + nx * hs * 0.6, anchor.y + ny * hs * 0.6);
                let right = Point::new(anchor.x - nx * hs * 0.6, anchor.y - ny * hs * 0.6);
                path.move_to(anchor);
                path.line_to(end);
                path.line_to(left);
                path.close();
                path.move_to(anchor);
                path.line_to(right);
                path.close();
                head_anchor = anchor;
            }
            ArrowStyle::Diamond => {
                let hs = head_size.max(0.0);
                let anchor = Point::new(end.x - ux * hs, end.y - uy * hs);
                let tip = Point::new(end.x + ux * hs * 0.3, end.y + uy * hs * 0.3);
                let left = Point::new(anchor.x + nx * hs * 0.5, anchor.y + ny * hs * 0.5);
                let right = Point::new(anchor.x - nx * hs * 0.5, anchor.y - ny * hs * 0.5);
                path.move_to(tip);
                path.line_to(right);
                path.line_to(anchor);
                path.line_to(left);
                path.close();
                head_anchor = anchor;
            }
            ArrowStyle::None => {
                head_anchor = Point::new(end.x, end.y);
            }
        }

        let tail_anchor: Point;
        match tail_style {
            ArrowStyle::Triangle => {
                let ts = tail_size.max(0.0);
                let anchor = Point::new(start.x + ux * ts, start.y + uy * ts);
                let left = Point::new(anchor.x + nx * ts * 0.6, anchor.y + ny * ts * 0.6);
                let right = Point::new(anchor.x - nx * ts * 0.6, anchor.y - ny * ts * 0.6);
                path.move_to(anchor);
                path.line_to(start);
                path.line_to(left);
                path.close();
                path.move_to(anchor);
                path.line_to(right);
                path.close();
                tail_anchor = anchor;
            }
            ArrowStyle::Diamond => {
                let ts = tail_size.max(0.0);
                let anchor = Point::new(start.x + ux * ts, start.y + uy * ts);
                let tip = Point::new(start.x - ux * ts * 0.3, start.y - uy * ts * 0.3);
                let left = Point::new(anchor.x + nx * ts * 0.5, anchor.y + ny * ts * 0.5);
                let right = Point::new(anchor.x - nx * ts * 0.5, anchor.y - ny * ts * 0.5);
                path.move_to(tip);
                path.line_to(left);
                path.line_to(anchor);
                path.line_to(right);
                path.close();
                tail_anchor = anchor;
            }
            ArrowStyle::None => {
                tail_anchor = Point::new(start.x, start.y);
            }
        }

        path.move_to(tail_anchor);
        path.line_to(head_anchor);

        path
    }

    pub fn create_rounded_textbox_path(rect: &Rect, radius: Scalar) -> RenderPath {
        let r = radius.min(rect.width / 2.0).min(rect.height / 2.0);
        let mut path = RenderPath::empty();
        let segs = 8usize;
        let step = std::f64::consts::FRAC_PI_2 / (segs as Scalar);

        let corners = [
            (rect.right() - r, rect.top() + r, std::f64::consts::PI * 1.5),
            (rect.right() - r, rect.bottom() - r, 0.0),
            (rect.left() + r, rect.bottom() - r, std::f64::consts::FRAC_PI_2),
            (rect.left() + r, rect.top() + r, std::f64::consts::PI),
        ];

        for (ci, (cx, cy, start_angle)) in corners.iter().enumerate() {
            for i in 0..segs {
                let angle = start_angle + (i as Scalar) * step;
                let p = Point::new(
                    cx + r * angle.cos(),
                    cy + r * angle.sin(),
                );
                if ci == 0 && i == 0 {
                    path.move_to(p);
                } else {
                    path.line_to(p);
                }
            }
        }
        path.close();
        path
    }
}
