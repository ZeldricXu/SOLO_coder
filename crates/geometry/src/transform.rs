use wasm_bindgen::prelude::*;
use serde::{Serialize, Deserialize};
use crate::types::{Point, Vector, Rect, Circle, Ellipse, Polygon, Line, Scalar};

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct Transform2D {
    pub a: Scalar,
    pub b: Scalar,
    pub c: Scalar,
    pub d: Scalar,
    pub e: Scalar,
    pub f: Scalar,
}

#[wasm_bindgen]
impl Transform2D {
    #[wasm_bindgen(constructor)]
    pub fn new(a: Scalar, b: Scalar, c: Scalar, d: Scalar, e: Scalar, f: Scalar) -> Self {
        Self { a, b, c, d, e, f }
    }

    pub fn identity() -> Self {
        Self {
            a: 1.0, b: 0.0,
            c: 0.0, d: 1.0,
            e: 0.0, f: 0.0,
        }
    }

    pub fn translation(tx: Scalar, ty: Scalar) -> Self {
        Self {
            a: 1.0, b: 0.0,
            c: 0.0, d: 1.0,
            e: tx,  f: ty,
        }
    }

    pub fn rotation(angle: Scalar) -> Self {
        let cos = angle.cos();
        let sin = angle.sin();
        Self {
            a: cos, b: -sin,
            c: sin, d: cos,
            e: 0.0, f: 0.0,
        }
    }

    pub fn rotation_around(angle: Scalar, center: &Point) -> Self {
        let cos = angle.cos();
        let sin = angle.sin();
        Self {
            a: cos,
            b: -sin,
            c: sin,
            d: cos,
            e: center.x - cos * center.x + sin * center.y,
            f: center.y - sin * center.x - cos * center.y,
        }
    }

    pub fn scale(sx: Scalar, sy: Scalar) -> Self {
        Self {
            a: sx,  b: 0.0,
            c: 0.0, d: sy,
            e: 0.0, f: 0.0,
        }
    }

    pub fn scale_around(sx: Scalar, sy: Scalar, center: &Point) -> Self {
        Self {
            a: sx,
            b: 0.0,
            c: 0.0,
            d: sy,
            e: center.x - sx * center.x,
            f: center.y - sy * center.y,
        }
    }

    pub fn shear(shx: Scalar, shy: Scalar) -> Self {
        Self {
            a: 1.0, b: shx,
            c: shy, d: 1.0,
            e: 0.0, f: 0.0,
        }
    }

    pub fn then(&self, other: &Transform2D) -> Transform2D {
        Transform2D::new(
            self.a * other.a + self.b * other.c,
            self.a * other.b + self.b * other.d,
            self.c * other.a + self.d * other.c,
            self.c * other.b + self.d * other.d,
            self.e * other.a + self.f * other.c + other.e,
            self.e * other.b + self.f * other.d + other.f,
        )
    }

    pub fn inverse(&self) -> Option<Transform2D> {
        let det = self.a * self.d - self.b * self.c;
        if det.abs() < 1e-10 {
            return None;
        }
        let inv_det = 1.0 / det;
        Some(Transform2D::new(
            self.d * inv_det,
            -self.b * inv_det,
            -self.c * inv_det,
            self.a * inv_det,
            (self.c * self.f - self.d * self.e) * inv_det,
            (self.b * self.e - self.a * self.f) * inv_det,
        ))
    }

    pub fn transform_point(&self, p: &Point) -> Point {
        Point::new(
            self.a * p.x + self.b * p.y + self.e,
            self.c * p.x + self.d * p.y + self.f,
        )
    }

    pub fn transform_vector(&self, v: &Vector) -> Vector {
        Vector::new(
            self.a * v.x + self.b * v.y,
            self.c * v.x + self.d * v.y,
        )
    }

    pub fn transform_line(&self, line: &Line) -> Line {
        Line::new(
            self.transform_point(&line.start),
            self.transform_point(&line.end),
        )
    }

    pub fn transform_rect(&self, rect: &Rect) -> Polygon {
        let points = vec![
            self.transform_point(&rect.top_left()),
            self.transform_point(&rect.top_right()),
            self.transform_point(&rect.bottom_right()),
            self.transform_point(&rect.bottom_left()),
        ];
        Polygon::new(points)
    }

    pub fn transform_circle(&self, circle: &Circle) -> Ellipse {
        let center = self.transform_point(&circle.center);
        let axis_x = self.transform_vector(&Vector::new(circle.radius, 0.0));
        let axis_y = self.transform_vector(&Vector::new(0.0, circle.radius));
        let radius_x = axis_x.length();
        let radius_y = axis_y.length();
        let rotation = if radius_x > 0.0 {
            axis_x.angle()
        } else {
            0.0
        };
        Ellipse::new(center, radius_x, radius_y, rotation)
    }

    pub fn transform_ellipse(&self, ellipse: &Ellipse) -> Ellipse {
        let center = self.transform_point(&ellipse.center);
        let cos = ellipse.rotation.cos();
        let sin = ellipse.rotation.sin();
        let axis1 = Vector::new(
            ellipse.radius_x * cos,
            ellipse.radius_x * sin,
        );
        let axis2 = Vector::new(
            -ellipse.radius_y * sin,
            ellipse.radius_y * cos,
        );
        let transformed_axis1 = self.transform_vector(&axis1);
        let transformed_axis2 = self.transform_vector(&axis2);
        let radius_x = transformed_axis1.length();
        let radius_y = transformed_axis2.length();
        let rotation = if radius_x > 0.0 {
            transformed_axis1.angle()
        } else {
            0.0
        };
        Ellipse::new(center, radius_x, radius_y, rotation)
    }

    pub fn transform_polygon(&self, polygon: &Polygon) -> Polygon {
        let points = polygon.points()
            .iter()
            .map(|p| self.transform_point(p))
            .collect();
        Polygon::new(points)
    }

    pub fn determinant(&self) -> Scalar {
        self.a * self.d - self.b * self.c
    }

    pub fn is_identity(&self, epsilon: Scalar) -> bool {
        (self.a - 1.0).abs() < epsilon &&
        self.b.abs() < epsilon &&
        self.c.abs() < epsilon &&
        (self.d - 1.0).abs() < epsilon &&
        self.e.abs() < epsilon &&
        self.f.abs() < epsilon
    }

    pub fn translation_x(&self) -> Point {
        Point::new(self.e, self.f)
    }

    pub fn scale_x(&self) -> Vector {
        let sx = (self.a * self.a + self.c * self.c).sqrt();
        let sy = (self.b * self.b + self.d * self.d).sqrt();
        Vector::new(sx, sy)
    }

    pub fn rotation_part(&self) -> Scalar {
        self.c.atan2(self.a)
    }
}

pub trait Transformable {
    fn transform(&self, t: &Transform2D) -> Self;
}

impl Transformable for Point {
    fn transform(&self, t: &Transform2D) -> Self {
        t.transform_point(self)
    }
}

impl Transformable for Vector {
    fn transform(&self, t: &Transform2D) -> Self {
        t.transform_vector(self)
    }
}

impl Transformable for Line {
    fn transform(&self, t: &Transform2D) -> Self {
        t.transform_line(self)
    }
}

impl Transformable for Polygon {
    fn transform(&self, t: &Transform2D) -> Self {
        t.transform_polygon(self)
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct Matrix3x3 {
    m: [[Scalar; 3]; 3],
}

#[wasm_bindgen]
impl Matrix3x3 {
    #[wasm_bindgen(constructor)]
    pub fn new(
        m00: Scalar, m01: Scalar, m02: Scalar,
        m10: Scalar, m11: Scalar, m12: Scalar,
        m20: Scalar, m21: Scalar, m22: Scalar,
    ) -> Self {
        Self {
            m: [
                [m00, m01, m02],
                [m10, m11, m12],
                [m20, m21, m22],
            ],
        }
    }

    pub fn identity() -> Self {
        Self::new(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0,
        )
    }

    pub fn from_transform2d(t: &Transform2D) -> Self {
        Self::new(
            t.a, t.b, t.e,
            t.c, t.d, t.f,
            0.0, 0.0, 1.0,
        )
    }

    pub fn to_transform2d(&self) -> Transform2D {
        Transform2D::new(
            self.m[0][0], self.m[0][1],
            self.m[1][0], self.m[1][1],
            self.m[0][2], self.m[1][2],
        )
    }

    pub fn multiply(&self, other: &Matrix3x3) -> Matrix3x3 {
        let mut result = [[0.0; 3]; 3];
        for i in 0..3 {
            for j in 0..3 {
                for k in 0..3 {
                    result[i][j] += self.m[i][k] * other.m[k][j];
                }
            }
        }
        Matrix3x3 { m: result }
    }

    pub fn transform_point(&self, p: &Point) -> Point {
        let w = self.m[2][0] * p.x + self.m[2][1] * p.y + self.m[2][2];
        if w.abs() < 1e-10 {
            return Point::zero();
        }
        let inv_w = 1.0 / w;
        Point::new(
            (self.m[0][0] * p.x + self.m[0][1] * p.y + self.m[0][2]) * inv_w,
            (self.m[1][0] * p.x + self.m[1][1] * p.y + self.m[1][2]) * inv_w,
        )
    }

    pub fn determinant(&self) -> Scalar {
        self.m[0][0] * (self.m[1][1] * self.m[2][2] - self.m[1][2] * self.m[2][1])
            - self.m[0][1] * (self.m[1][0] * self.m[2][2] - self.m[1][2] * self.m[2][0])
            + self.m[0][2] * (self.m[1][0] * self.m[2][1] - self.m[1][1] * self.m[2][0])
    }

    pub fn get(&self, row: usize, col: usize) -> Scalar {
        if row < 3 && col < 3 {
            self.m[row][col]
        } else {
            0.0
        }
    }
}
