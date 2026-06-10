use wasm_bindgen::prelude::*;
use serde::{Serialize, Deserialize};
use crate::types::Scalar;

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub struct Color {
    pub r: u8,
    pub g: u8,
    pub b: u8,
    pub a: u8,
}

#[wasm_bindgen]
impl Color {
    #[wasm_bindgen(constructor)]
    pub fn new(r: u8, g: u8, b: u8, a: u8) -> Self {
        Self { r, g, b, a }
    }

    pub fn from_rgba(r: f64, g: f64, b: f64, a: f64) -> Self {
        Self {
            r: (r.clamp(0.0, 1.0) * 255.0) as u8,
            g: (g.clamp(0.0, 1.0) * 255.0) as u8,
            b: (b.clamp(0.0, 1.0) * 255.0) as u8,
            a: (a.clamp(0.0, 1.0) * 255.0) as u8,
        }
    }

    pub fn from_hex(hex: u32) -> Self {
        Self {
            r: ((hex >> 24) & 0xFF) as u8,
            g: ((hex >> 16) & 0xFF) as u8,
            b: ((hex >> 8) & 0xFF) as u8,
            a: (hex & 0xFF) as u8,
        }
    }

    pub fn r_f64(&self) -> f64 { self.r as f64 / 255.0 }
    pub fn g_f64(&self) -> f64 { self.g as f64 / 255.0 }
    pub fn b_f64(&self) -> f64 { self.b as f64 / 255.0 }
    pub fn a_f64(&self) -> f64 { self.a as f64 / 255.0 }

    pub fn to_hex(&self) -> u32 {
        ((self.r as u32) << 24) | ((self.g as u32) << 16) | ((self.b as u32) << 8) | (self.a as u32)
    }

    pub fn with_alpha(&self, a: u8) -> Self {
        Self { a, ..*self }
    }

    pub fn black() -> Self { Self::new(0, 0, 0, 255) }
    pub fn white() -> Self { Self::new(255, 255, 255, 255) }
    pub fn red() -> Self { Self::new(255, 0, 0, 255) }
    pub fn green() -> Self { Self::new(0, 255, 0, 255) }
    pub fn blue() -> Self { Self::new(0, 0, 255, 255) }
    pub fn transparent() -> Self { Self::new(0, 0, 0, 0) }
}

impl Color {
    pub fn to_rgba_f64(&self) -> (f64, f64, f64, f64) {
        (
            self.r as f64 / 255.0,
            self.g as f64 / 255.0,
            self.b as f64 / 255.0,
            self.a as f64 / 255.0,
        )
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum FillRule {
    NonZero,
    EvenOdd,
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum LineCap {
    Butt,
    Round,
    Square,
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum LineJoin {
    Miter,
    Round,
    Bevel,
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct StrokeOptions {
    #[serde(skip)]
    _reserved: u8,
    _width: f64,
    _line_cap: LineCap,
    _line_join: LineJoin,
    _miter_limit: f64,
}

#[wasm_bindgen]
impl StrokeOptions {
    #[wasm_bindgen(constructor)]
    pub fn new(width: f64) -> Self {
        Self {
            _reserved: 0,
            _width: width,
            _line_cap: LineCap::Butt,
            _line_join: LineJoin::Miter,
            _miter_limit: 4.0,
        }
    }

    #[wasm_bindgen(getter)]
    pub fn width(&self) -> f64 { self._width }

    #[wasm_bindgen(setter)]
    pub fn set_width(&mut self, w: f64) { self._width = w; }

    #[wasm_bindgen(getter)]
    pub fn line_cap(&self) -> LineCap { self._line_cap }

    #[wasm_bindgen(setter)]
    pub fn set_line_cap(&mut self, cap: LineCap) { self._line_cap = cap; }

    #[wasm_bindgen(getter)]
    pub fn line_join(&self) -> LineJoin { self._line_join }

    #[wasm_bindgen(setter)]
    pub fn set_line_join(&mut self, join: LineJoin) { self._line_join = join; }

    #[wasm_bindgen(getter)]
    pub fn miter_limit(&self) -> f64 { self._miter_limit }

    #[wasm_bindgen(setter)]
    pub fn set_miter_limit(&mut self, limit: f64) { self._miter_limit = limit; }

    pub fn with_line_cap(mut self, cap: LineCap) -> Self {
        self._line_cap = cap;
        self
    }

    pub fn with_line_join(mut self, join: LineJoin) -> Self {
        self._line_join = join;
        self
    }

    pub fn with_miter_limit(mut self, limit: f64) -> Self {
        self._miter_limit = limit;
        self
    }
}

impl StrokeOptions {
    pub fn internal_width(&self) -> f64 { self._width }
    pub fn internal_line_cap(&self) -> LineCap { self._line_cap }
    pub fn internal_line_join(&self) -> LineJoin { self._line_join }
    pub fn internal_miter_limit(&self) -> f64 { self._miter_limit }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct Size {
    #[serde(skip)]
    _reserved: u8,
    _width: f64,
    _height: f64,
}

#[wasm_bindgen]
impl Size {
    #[wasm_bindgen(constructor)]
    pub fn new(width: f64, height: f64) -> Self {
        Self { _reserved: 0, _width: width, _height: height }
    }

    #[wasm_bindgen(getter)]
    pub fn width(&self) -> f64 { self._width }

    #[wasm_bindgen(setter)]
    pub fn set_width(&mut self, w: f64) { self._width = w; }

    #[wasm_bindgen(getter)]
    pub fn height(&self) -> f64 { self._height }

    #[wasm_bindgen(setter)]
    pub fn set_height(&mut self, h: f64) { self._height = h; }

    pub fn area(&self) -> f64 {
        self._width * self._height
    }
}

impl Size {
    pub fn internal_width(&self) -> f64 { self._width }
    pub fn internal_height(&self) -> f64 { self._height }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum BlendMode {
    Normal,
    Multiply,
    Screen,
    Overlay,
    Darken,
    Lighten,
    ColorDodge,
    ColorBurn,
    HardLight,
    SoftLight,
    Difference,
    Exclusion,
    SourceIn,
    DestinationIn,
    SourceOut,
    DestinationOut,
}

pub fn merge_rects(rects: &[crate::types::Rect]) -> Option<crate::types::Rect> {
    if rects.is_empty() {
        return None;
    }
    let mut result = rects[0];
    for r in &rects[1..] {
        result = result.union(r);
    }
    Some(result)
}

pub fn optimize_rects(rects: &[crate::types::Rect], threshold: Scalar) -> Vec<crate::types::Rect> {
    use rustc_hash::FxHashSet;

    if rects.is_empty() {
        return Vec::new();
    }

    let mut result: Vec<crate::types::Rect> = Vec::new();
    let mut used: FxHashSet<usize> = FxHashSet::default();

    for i in 0..rects.len() {
        if used.contains(&i) {
            continue;
        }
        let mut merged = rects[i];
        used.insert(i);

        let mut changed = true;
        while changed {
            changed = false;
            for j in 0..rects.len() {
                if used.contains(&j) {
                    continue;
                }
                let union_rect = merged.union(&rects[j]);
                let intersection = merged.intersection(&rects[j]);
                let overlap = intersection.map(|r| r.area()).unwrap_or(0.0);
                let merged_area = union_rect.area();
                let separate_area = merged.area() + rects[j].area();

                if overlap > 0.0 || (separate_area > 0.0 && (separate_area - merged_area) / separate_area < threshold) {
                    merged = union_rect;
                    used.insert(j);
                    changed = true;
                }
            }
        }
        result.push(merged);
    }

    result
}
