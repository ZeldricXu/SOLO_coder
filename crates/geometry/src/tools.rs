use wasm_bindgen::prelude::*;
use serde::{Serialize, Deserialize};
use std::collections::HashMap;
use crate::types::{Point, Scalar, ShapeType};
use crate::path::RenderPath;

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum ToolCategory {
    Selection,
    Drawing,
    Editing,
    Navigation,
    Annotation,
}

pub trait Tool {
    fn id(&self) -> &str;
    fn name(&self) -> &str;
    fn category(&self) -> ToolCategory;
    fn on_press(&self, point: Point, pressure: Scalar) -> ToolResult;
    fn on_drag(&self, from: Point, to: Point, pressure: Scalar) -> ToolResult;
    fn on_release(&self, point: Point) -> ToolResult;
    fn hit_test(&self, shape_path: &RenderPath, point: Point, tolerance: Scalar) -> bool;
    fn render_preview(&self, start: Option<Point>, current: Option<Point>) -> RenderPath;
    fn serialize_shape(&self, data: &JsValue) -> Result<JsValue, JsValue>;
}

#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolResult {
    success: bool,
    shape_type: Option<ShapeType>,
    message: Option<String>,
    needs_render: bool,
}

#[wasm_bindgen]
impl ToolResult {
    #[wasm_bindgen(constructor)]
    pub fn new(success: bool, shape_type: Option<ShapeType>, message: Option<String>, needs_render: bool) -> Self {
        Self { success, shape_type, message, needs_render }
    }

    pub fn ok() -> Self {
        Self { success: true, shape_type: None, message: None, needs_render: false }
    }

    pub fn with_shape(shape_type: ShapeType) -> Self {
        Self { success: true, shape_type: Some(shape_type), message: None, needs_render: true }
    }

    pub fn error(msg: &str) -> Self {
        Self { success: false, shape_type: None, message: Some(msg.to_string()), needs_render: false }
    }

    #[wasm_bindgen(getter)]
    pub fn success(&self) -> bool { self.success }

    #[wasm_bindgen(getter)]
    pub fn shape_type(&self) -> Option<ShapeType> { self.shape_type }

    #[wasm_bindgen(getter)]
    pub fn message(&self) -> Option<String> { self.message.clone() }

    #[wasm_bindgen(getter)]
    pub fn needs_render(&self) -> bool { self.needs_render }
}

#[derive(Clone)]
struct ToolEntry {
    id: String,
    name: String,
    category: ToolCategory,
    default_shape_type: Option<ShapeType>,
}

impl Tool for ToolEntry {
    fn id(&self) -> &str { &self.id }
    fn name(&self) -> &str { &self.name }
    fn category(&self) -> ToolCategory { self.category }

    fn on_press(&self, _point: Point, _pressure: Scalar) -> ToolResult {
        ToolResult::ok()
    }

    fn on_drag(&self, _from: Point, _to: Point, _pressure: Scalar) -> ToolResult {
        if let Some(st) = self.default_shape_type {
            ToolResult::with_shape(st)
        } else {
            ToolResult::ok()
        }
    }

    fn on_release(&self, _point: Point) -> ToolResult {
        if let Some(st) = self.default_shape_type {
            ToolResult::with_shape(st)
        } else {
            ToolResult::ok()
        }
    }

    fn hit_test(&self, shape_path: &RenderPath, point: Point, tolerance: Scalar) -> bool {
        let cmds = shape_path.commands();
        let mut last: Option<Point> = None;
        for cmd in cmds {
            match cmd.cmd_type {
                crate::path::PathCommandType::MoveTo => {
                    last = Some(cmd.p0);
                }
                crate::path::PathCommandType::LineTo => {
                    if let Some(l) = last {
                        let line = crate::types::Line::new(l, cmd.p0);
                        if line.distance_to_point(&point) <= tolerance {
                            return true;
                        }
                    }
                    last = Some(cmd.p0);
                }
                _ => {
                    last = Some(cmd.p0);
                }
            }
        }
        false
    }

    fn render_preview(&self, start: Option<Point>, current: Option<Point>) -> RenderPath {
        let mut path = RenderPath::empty();
        if let (Some(s), Some(c)) = (start, current) {
            match self.default_shape_type {
                Some(ShapeType::Rectangle) => {
                    let rect = crate::types::Rect::from_points(&s, &c);
                    path = crate::path::PathGenerator::from_rect(&rect);
                }
                Some(ShapeType::Circle) => {
                    let radius = s.distance(&c);
                    let circle = crate::types::Circle::new(s, radius);
                    path = crate::path::PathGenerator::from_circle(&circle, 32);
                }
                Some(ShapeType::Ellipse) => {
                    let rx = (c.x - s.x).abs();
                    let ry = (c.y - s.y).abs();
                    let ellipse = crate::types::Ellipse::axis_aligned(s, rx, ry);
                    path = crate::path::PathGenerator::from_ellipse(&ellipse, 32);
                }
                Some(ShapeType::Line | ShapeType::Arrow) => {
                    path.move_to(s);
                    path.line_to(c);
                }
                Some(ShapeType::Star) => {
                    let outer = s.distance(&c);
                    let inner = outer * 0.4;
                    path = crate::shapes::ShapeGenerator::create_star_path(s, outer, inner, 5, 0.0);
                }
                Some(ShapeType::RichText) => {
                    let rect = crate::types::Rect::from_points(&s, &c);
                    path = crate::path::PathGenerator::from_rounded_rect(&rect, 4.0, 4);
                }
                Some(ShapeType::Polygon) => {
                    path.move_to(s);
                    path.line_to(c);
                }
                None => {
                    path.move_to(s);
                    path.line_to(c);
                }
            }
        }
        path
    }

    fn serialize_shape(&self, data: &JsValue) -> Result<JsValue, JsValue> {
        Ok(data.clone())
    }
}

#[wasm_bindgen]
pub struct ToolRegistry {
    tools: HashMap<String, ToolEntry>,
    order: Vec<String>,
}

#[wasm_bindgen]
impl ToolRegistry {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        Self::default()
    }

    pub fn default() -> Self {
        let mut registry = Self {
            tools: HashMap::new(),
            order: Vec::new(),
        };
        registry.register_defaults();
        registry
    }

    pub fn register(
        &mut self,
        id: &str,
        name: &str,
        category: ToolCategory,
        default_shape_type: Option<ShapeType>,
    ) -> bool {
        if self.tools.contains_key(id) {
            return false;
        }
        let entry = ToolEntry {
            id: id.to_string(),
            name: name.to_string(),
            category,
            default_shape_type,
        };
        self.tools.insert(id.to_string(), entry);
        self.order.push(id.to_string());
        true
    }

    pub fn unregister(&mut self, id: &str) -> bool {
        if self.tools.remove(id).is_some() {
            self.order.retain(|o| o != id);
            true
        } else {
            false
        }
    }

    pub fn has_tool(&self, id: &str) -> bool {
        self.tools.contains_key(id)
    }

    pub fn get_tool_name(&self, id: &str) -> Option<String> {
        self.tools.get(id).map(|t| t.name.clone())
    }

    pub fn get_tool_category(&self, id: &str) -> Option<ToolCategory> {
        self.tools.get(id).map(|t| t.category)
    }

    pub fn tool_count(&self) -> usize {
        self.tools.len()
    }

    pub fn list_tools(&self) -> Vec<JsValue> {
        self.order
            .iter()
            .filter_map(|id| self.tools.get(id))
            .map(|t| {
                let obj = js_sys::Object::new();
                js_sys::Reflect::set(&obj, &JsValue::from_str("id"), &JsValue::from_str(&t.id)).ok();
                js_sys::Reflect::set(&obj, &JsValue::from_str("name"), &JsValue::from_str(&t.name)).ok();
                js_sys::Reflect::set(
                    &obj,
                    &JsValue::from_str("category"),
                    &JsValue::from(match t.category {
                        ToolCategory::Selection => "selection",
                        ToolCategory::Drawing => "drawing",
                        ToolCategory::Editing => "editing",
                        ToolCategory::Navigation => "navigation",
                        ToolCategory::Annotation => "annotation",
                    }),
                ).ok();
                obj.into()
            })
            .collect()
    }

    pub fn list_tool_ids(&self) -> Vec<String> {
        self.order.clone()
    }

    pub fn tool_on_press(&self, id: &str, point: Point, pressure: Scalar) -> ToolResult {
        self.tools.get(id).map(|t| t.on_press(point, pressure)).unwrap_or_else(|| ToolResult::error("Tool not found"))
    }

    pub fn tool_on_drag(&self, id: &str, from: Point, to: Point, pressure: Scalar) -> ToolResult {
        self.tools.get(id).map(|t| t.on_drag(from, to, pressure)).unwrap_or_else(|| ToolResult::error("Tool not found"))
    }

    pub fn tool_on_release(&self, id: &str, point: Point) -> ToolResult {
        self.tools.get(id).map(|t| t.on_release(point)).unwrap_or_else(|| ToolResult::error("Tool not found"))
    }

    pub fn tool_hit_test(&self, id: &str, shape_path: &RenderPath, point: Point, tolerance: Scalar) -> bool {
        self.tools.get(id).map(|t| t.hit_test(shape_path, point, tolerance)).unwrap_or(false)
    }

    pub fn tool_render_preview(&self, id: &str, start: Option<Point>, current: Option<Point>) -> RenderPath {
        self.tools.get(id).map(|t| t.render_preview(start, current)).unwrap_or_else(RenderPath::empty)
    }

    pub fn tool_serialize_shape(&self, id: &str, data: &JsValue) -> Result<JsValue, JsValue> {
        self.tools.get(id).map(|t| t.serialize_shape(data)).unwrap_or_else(|| Err(JsValue::from_str("Tool not found")))
    }
}

impl ToolRegistry {
    fn register_defaults(&mut self) {
        self.register("select", "Select", ToolCategory::Selection, None);
        self.register("pen", "Pen", ToolCategory::Drawing, Some(ShapeType::Polygon));
        self.register("eraser", "Eraser", ToolCategory::Editing, None);
        self.register("shape", "Shape", ToolCategory::Drawing, Some(ShapeType::Rectangle));
        self.register("star", "Star", ToolCategory::Drawing, Some(ShapeType::Star));
        self.register("arrow", "Arrow", ToolCategory::Drawing, Some(ShapeType::Arrow));
        self.register("textbox", "Text Box", ToolCategory::Annotation, Some(ShapeType::RichText));
        self.register("comment", "Comment", ToolCategory::Annotation, None);
        self.register("pan", "Pan", ToolCategory::Navigation, None);
    }
}

impl Default for ToolRegistry {
    fn default() -> Self {
        Self {
            tools: HashMap::new(),
            order: Vec::new(),
        }
    }
}
