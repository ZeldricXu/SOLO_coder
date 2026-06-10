use wasm_bindgen::prelude::*;
use serde::{Serialize, Deserialize};
use lyon::path::{Path, PathEvent};
use lyon::math::{point, Point as LyonPoint};
use crate::types::{Point, Rect, Circle, Ellipse, Polygon, Star, Line, Scalar};

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum PathCommandType {
    MoveTo,
    LineTo,
    QuadraticTo,
    CubicTo,
    Close,
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct PathCommand {
    pub cmd_type: PathCommandType,
    pub p0: Point,
    pub p1: Point,
    pub p2: Point,
}

#[wasm_bindgen]
impl PathCommand {
    #[wasm_bindgen(constructor)]
    pub fn new(cmd_type: PathCommandType, p0: Point, p1: Point, p2: Point) -> Self {
        Self { cmd_type, p0, p1, p2 }
    }

    pub fn move_to(p: Point) -> Self {
        Self {
            cmd_type: PathCommandType::MoveTo,
            p0: p,
            p1: Point::zero(),
            p2: Point::zero(),
        }
    }

    pub fn line_to(p: Point) -> Self {
        Self {
            cmd_type: PathCommandType::LineTo,
            p0: p,
            p1: Point::zero(),
            p2: Point::zero(),
        }
    }

    pub fn quadratic_to(ctrl: Point, to: Point) -> Self {
        Self {
            cmd_type: PathCommandType::QuadraticTo,
            p0: ctrl,
            p1: to,
            p2: Point::zero(),
        }
    }

    pub fn cubic_to(ctrl1: Point, ctrl2: Point, to: Point) -> Self {
        Self {
            cmd_type: PathCommandType::CubicTo,
            p0: ctrl1,
            p1: ctrl2,
            p2: to,
        }
    }

    pub fn close() -> Self {
        Self {
            cmd_type: PathCommandType::Close,
            p0: Point::zero(),
            p1: Point::zero(),
            p2: Point::zero(),
        }
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RenderPath {
    commands: Vec<PathCommand>,
}

#[wasm_bindgen]
impl RenderPath {
    #[wasm_bindgen(constructor)]
    pub fn new(commands: Vec<PathCommand>) -> Self {
        Self { commands }
    }

    pub fn empty() -> Self {
        Self { commands: Vec::new() }
    }

    pub fn commands(&self) -> Vec<PathCommand> {
        self.commands.clone()
    }

    pub fn num_commands(&self) -> usize {
        self.commands.len()
    }

    pub fn get_command(&self, index: usize) -> Option<PathCommand> {
        self.commands.get(index).copied()
    }

    pub fn add_command(&mut self, cmd: PathCommand) {
        self.commands.push(cmd);
    }

    pub fn move_to(&mut self, p: Point) {
        self.commands.push(PathCommand::move_to(p));
    }

    pub fn line_to(&mut self, p: Point) {
        self.commands.push(PathCommand::line_to(p));
    }

    pub fn quadratic_to(&mut self, ctrl: Point, to: Point) {
        self.commands.push(PathCommand::quadratic_to(ctrl, to));
    }

    pub fn cubic_to(&mut self, ctrl1: Point, ctrl2: Point, to: Point) {
        self.commands.push(PathCommand::cubic_to(ctrl1, ctrl2, to));
    }

    pub fn close(&mut self) {
        self.commands.push(PathCommand::close());
    }

    pub fn clear(&mut self) {
        self.commands.clear();
    }

    pub fn is_empty(&self) -> bool {
        self.commands.is_empty()
    }

    pub fn to_svg(&self) -> String {
        let mut svg = String::new();
        for cmd in &self.commands {
            match cmd.cmd_type {
                PathCommandType::MoveTo => {
                    svg.push_str(&format!("M {} {} ", cmd.p0.x, cmd.p0.y));
                }
                PathCommandType::LineTo => {
                    svg.push_str(&format!("L {} {} ", cmd.p0.x, cmd.p0.y));
                }
                PathCommandType::QuadraticTo => {
                    svg.push_str(&format!("Q {} {} {} {} ", cmd.p0.x, cmd.p0.y, cmd.p1.x, cmd.p1.y));
                }
                PathCommandType::CubicTo => {
                    svg.push_str(&format!("C {} {} {} {} {} {} ", cmd.p0.x, cmd.p0.y, cmd.p1.x, cmd.p1.y, cmd.p2.x, cmd.p2.y));
                }
                PathCommandType::Close => {
                    svg.push_str("Z ");
                }
            }
        }
        svg.trim().to_string()
    }
}

fn to_lyon_point(p: &Point) -> LyonPoint {
    point(p.x as f32, p.y as f32)
}

fn from_lyon_point(p: LyonPoint) -> Point {
    Point::new(p.x as Scalar, p.y as Scalar)
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct PathGenerator {
    circle_tolerance: Scalar,
}

#[wasm_bindgen]
impl PathGenerator {
    #[wasm_bindgen(constructor)]
    pub fn new(circle_tolerance: Scalar) -> Self {
        Self { circle_tolerance }
    }

    pub fn default() -> Self {
        Self { circle_tolerance: 0.1 }
    }

    pub fn from_rect(rect: &Rect) -> RenderPath {
        let mut path = RenderPath::empty();
        path.move_to(rect.top_left());
        path.line_to(rect.top_right());
        path.line_to(rect.bottom_right());
        path.line_to(rect.bottom_left());
        path.close();
        path
    }

    pub fn from_circle(circle: &Circle, segments: u32) -> RenderPath {
        let mut path = RenderPath::empty();
        let n = segments.max(8) as usize;
        for i in 0..n {
            let angle = 2.0 * std::f64::consts::PI * (i as Scalar) / (n as Scalar);
            let p = Point::new(
                circle.center.x + circle.radius * angle.cos(),
                circle.center.y + circle.radius * angle.sin(),
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

    pub fn from_ellipse(ellipse: &Ellipse, segments: u32) -> RenderPath {
        let mut path = RenderPath::empty();
        let n = segments.max(8) as usize;
        let cos = ellipse.rotation.cos();
        let sin = ellipse.rotation.sin();
        for i in 0..n {
            let angle = 2.0 * std::f64::consts::PI * (i as Scalar) / (n as Scalar);
            let local_x = ellipse.radius_x * angle.cos();
            let local_y = ellipse.radius_y * angle.sin();
            let x = ellipse.center.x + local_x * cos - local_y * sin;
            let y = ellipse.center.y + local_x * sin + local_y * cos;
            let p = Point::new(x, y);
            if i == 0 {
                path.move_to(p);
            } else {
                path.line_to(p);
            }
        }
        path.close();
        path
    }

    pub fn from_polygon(polygon: &Polygon) -> RenderPath {
        let mut path = RenderPath::empty();
        let points = polygon.points();
        if points.is_empty() {
            return path;
        }
        for (i, p) in points.iter().enumerate() {
            if i == 0 {
                path.move_to(*p);
            } else {
                path.line_to(*p);
            }
        }
        if points.len() >= 3 {
            path.close();
        }
        path
    }

    pub fn from_star(star: &Star) -> RenderPath {
        Self::from_polygon(&star.to_polygon())
    }

    pub fn from_line(line: &Line) -> RenderPath {
        let mut path = RenderPath::empty();
        path.move_to(line.start);
        path.line_to(line.end);
        path
    }

    pub fn from_lines_vec(lines: Vec<Line>) -> RenderPath {
        let mut path = RenderPath::empty();
        for (i, line) in lines.iter().enumerate() {
            if i == 0 {
                path.move_to(line.start);
            }
            path.line_to(line.end);
        }
        path
    }

    pub fn from_points_vec(points: Vec<Point>, close: bool) -> RenderPath {
        let mut path = RenderPath::empty();
        if points.is_empty() {
            return path;
        }
        for (i, p) in points.iter().enumerate() {
            if i == 0 {
                path.move_to(*p);
            } else {
                path.line_to(*p);
            }
        }
        if close && points.len() >= 3 {
            path.close();
        }
        path
    }

    pub fn from_rounded_rect(
        rect: &Rect,
        corner_radius: Scalar,
        segments_per_corner: u32,
    ) -> RenderPath {
        let r = corner_radius.min(rect.width / 2.0).min(rect.height / 2.0);
        let mut path = RenderPath::empty();
        let segs = segments_per_corner.max(1) as usize;
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

    pub fn arc(
        center: &Point,
        radius: Scalar,
        start_angle: Scalar,
        end_angle: Scalar,
        segments: u32,
    ) -> RenderPath {
        let mut path = RenderPath::empty();
        let n = segments.max(2) as usize;
        let step = (end_angle - start_angle) / ((n - 1) as Scalar);
        for i in 0..n {
            let angle = start_angle + (i as Scalar) * step;
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
        path
    }

    pub fn bezier_curve(
        p0: Point,
        p1: Point,
        p2: Point,
        p3: Point,
    ) -> RenderPath {
        let mut path = RenderPath::empty();
        path.move_to(p0);
        path.cubic_to(p1, p2, p3);
        path
    }

    pub fn quadratic_bezier(p0: Point, p1: Point, p2: Point) -> RenderPath {
        let mut path = RenderPath::empty();
        path.move_to(p0);
        path.quadratic_to(p1, p2);
        path
    }

}

impl PathGenerator {
    pub fn convert_lyon_path(path: &Path) -> RenderPath {
        let mut render_path = RenderPath::empty();
        for event in path.iter() {
            match event {
                PathEvent::Begin { at } => {
                    render_path.move_to(from_lyon_point(at));
                }
                PathEvent::Line { to, .. } => {
                    render_path.line_to(from_lyon_point(to));
                }
                PathEvent::Quadratic { ctrl, to, .. } => {
                    render_path.quadratic_to(from_lyon_point(ctrl), from_lyon_point(to));
                }
                PathEvent::Cubic { ctrl1, ctrl2, to, .. } => {
                    render_path.cubic_to(from_lyon_point(ctrl1), from_lyon_point(ctrl2), from_lyon_point(to));
                }
                PathEvent::End { close: true, .. } => {
                    render_path.close();
                }
                PathEvent::End { close: false, .. } => {}
            }
        }
        render_path
    }

    pub fn to_lyon_path(render_path: &RenderPath) -> Path {
        let mut builder = Path::builder();
        for cmd in &render_path.commands {
            match cmd.cmd_type {
                PathCommandType::MoveTo => {
                    builder.begin(to_lyon_point(&cmd.p0));
                }
                PathCommandType::LineTo => {
                    builder.line_to(to_lyon_point(&cmd.p0));
                }
                PathCommandType::QuadraticTo => {
                    builder.quadratic_bezier_to(to_lyon_point(&cmd.p0), to_lyon_point(&cmd.p1));
                }
                PathCommandType::CubicTo => {
                    builder.cubic_bezier_to(to_lyon_point(&cmd.p0), to_lyon_point(&cmd.p1), to_lyon_point(&cmd.p2));
                }
                PathCommandType::Close => {
                    builder.close();
                }
            }
        }
        builder.build()
    }
}

#[wasm_bindgen]
pub fn rect_to_path(rect: &Rect) -> RenderPath {
    PathGenerator::from_rect(rect)
}

#[wasm_bindgen]
pub fn polygon_to_path(polygon: &Polygon) -> RenderPath {
    PathGenerator::from_polygon(polygon)
}

#[wasm_bindgen]
pub fn circle_to_path(circle: &Circle, segments: u32) -> RenderPath {
    PathGenerator::from_circle(circle, segments)
}
