use wasm_bindgen::prelude::*;
use serde::{Serialize, Deserialize};
use uuid::Uuid;
use rustc_hash::{FxHashMap, FxHashSet, FxHasher};
use std::hash::{Hash, Hasher};

use geometry::{
    Point, Rect, Size, Transform2D, Color, FillRule, LineCap, LineJoin, StrokeOptions,
    BlendMode, ToolRegistry,
};
use stroke_engine::Stroke;
use crdt::WasmYrsBoard;

use lyon::math::{Point as LyonPoint};
use lyon::path::Path;
use lyon::tessellation::{
    StrokeTessellator, FillTessellator,
    StrokeOptions as LyonStrokeOptions, FillOptions as LyonFillOptions,
    BuffersBuilder, VertexBuffers, FillVertex, StrokeVertex,
    FillRule as LyonFillRule, LineCap as LyonLineCap, LineJoin as LyonLineJoin,
};

#[global_allocator]
static ALLOC: wee_alloc::WeeAlloc = wee_alloc::WeeAlloc::INIT;

fn hash_uuid(u: &Uuid) -> u64 {
    let mut hasher = FxHasher::default();
    u.as_bytes().hash(&mut hasher);
    hasher.finish()
}

fn to_lyon_point(p: &Point) -> LyonPoint {
    LyonPoint::new(p.x as f32, p.y as f32)
}

fn to_lyon_fill_rule(rule: FillRule) -> LyonFillRule {
    match rule {
        FillRule::NonZero => LyonFillRule::NonZero,
        FillRule::EvenOdd => LyonFillRule::EvenOdd,
    }
}

fn to_lyon_line_cap(cap: LineCap) -> LyonLineCap {
    match cap {
        LineCap::Butt => LyonLineCap::Butt,
        LineCap::Round => LyonLineCap::Round,
        LineCap::Square => LyonLineCap::Square,
    }
}

fn to_lyon_line_join(join: LineJoin) -> LyonLineJoin {
    match join {
        LineJoin::Miter => LyonLineJoin::Miter,
        LineJoin::Round => LyonLineJoin::Round,
        LineJoin::Bevel => LyonLineJoin::Bevel,
    }
}

// ==========================================
// Viewport 视口管理
// ==========================================

/// @deprecated Use CanvasFacade instead
#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Viewport {
    offset: Point,
    scale: f64,
    dpr: f64,
    min_scale: f64,
    max_scale: f64,
    view_size: Size,
}

#[wasm_bindgen]
impl Viewport {
    #[wasm_bindgen(constructor)]
    pub fn new(view_width: f64, view_height: f64) -> Self {
        Self {
            offset: Point::new(0.0, 0.0),
            scale: 1.0,
            dpr: 1.0,
            min_scale: 0.01,
            max_scale: 100.0,
            view_size: Size::new(view_width, view_height),
        }
    }

    #[wasm_bindgen(getter)]
    pub fn offset(&self) -> Point {
        self.offset
    }

    #[wasm_bindgen(getter)]
    pub fn scale(&self) -> f64 {
        self.scale
    }

    #[wasm_bindgen(getter)]
    pub fn dpr(&self) -> f64 {
        self.dpr
    }

    #[wasm_bindgen(setter)]
    pub fn set_dpr(&mut self, dpr: f64) {
        self.dpr = dpr.max(0.1);
    }

    #[wasm_bindgen(getter)]
    pub fn view_size(&self) -> Size {
        self.view_size
    }

    pub fn set_view_size(&mut self, width: f64, height: f64) {
        self.view_size = Size::new(width, height);
    }

    pub fn set_scale_range(&mut self, min: f64, max: f64) {
        self.min_scale = min.max(0.001);
        self.max_scale = max.max(self.min_scale);
        self.scale = self.scale.clamp(self.min_scale, self.max_scale);
    }

    pub fn pan(&mut self, dx: f64, dy: f64) {
        self.offset = Point::new(
            self.offset.x + dx,
            self.offset.y + dy,
        );
    }

    pub fn zoom(&mut self, factor: f64, center: Option<Point>) {
        let new_scale = (self.scale * factor).clamp(self.min_scale, self.max_scale);
        let actual_factor = new_scale / self.scale;

        if let Some(center) = center {
            let screen_center = self.world_to_screen(&center);
            self.offset = Point::new(
                screen_center.x - (screen_center.x - self.offset.x) * actual_factor,
                screen_center.y - (screen_center.y - self.offset.y) * actual_factor,
            );
        }

        self.scale = new_scale;
    }

    pub fn zoom_at(&mut self, screen_x: f64, screen_y: f64, factor: f64) {
        let new_scale = (self.scale * factor).clamp(self.min_scale, self.max_scale);
        let actual_factor = new_scale / self.scale;

        self.offset = Point::new(
            screen_x - (screen_x - self.offset.x) * actual_factor,
            screen_y - (screen_y - self.offset.y) * actual_factor,
        );
        self.scale = new_scale;
    }

    pub fn set_scale(&mut self, scale: f64, center: Option<Point>) {
        let factor = scale.clamp(self.min_scale, self.max_scale) / self.scale;
        self.zoom(factor, center);
    }

    pub fn reset(&mut self) {
        self.offset = Point::new(0.0, 0.0);
        self.scale = 1.0;
    }

    pub fn world_to_screen(&self, p: &Point) -> Point {
        Point::new(
            (p.x * self.scale + self.offset.x) * self.dpr,
            (p.y * self.scale + self.offset.y) * self.dpr,
        )
    }

    pub fn screen_to_world(&self, p: &Point) -> Point {
        Point::new(
            (p.x / self.dpr - self.offset.x) / self.scale,
            (p.y / self.dpr - self.offset.y) / self.scale,
        )
    }

    pub fn world_rect_to_screen(&self, r: &Rect) -> Rect {
        let origin = self.world_to_screen(&r.min());
        let far = self.world_to_screen(&r.max());
        Rect::new(origin.x, origin.y, far.x - origin.x, far.y - origin.y)
    }

    pub fn screen_rect_to_world(&self, r: &Rect) -> Rect {
        let origin = self.screen_to_world(&r.min());
        let far = self.screen_to_world(&r.max());
        Rect::new(origin.x, origin.y, far.x - origin.x, far.y - origin.y)
    }

    pub fn visible_world_rect(&self) -> Rect {
        let top_left = self.screen_to_world(&Point::new(0.0, 0.0));
        let bottom_right = self.screen_to_world(&Point::new(
            self.view_size.width() * self.dpr,
            self.view_size.height() * self.dpr,
        ));
        Rect::new(
            top_left.x,
            top_left.y,
            bottom_right.x - top_left.x,
            bottom_right.y - top_left.y,
        )
    }

    pub fn to_transform(&self) -> Transform2D {
        Transform2D::new(
            self.scale * self.dpr,
            0.0,
            0.0,
            self.scale * self.dpr,
            self.offset.x * self.dpr,
            self.offset.y * self.dpr,
        )
    }

    pub fn fit_to_rect(&mut self, target: &Rect, padding: f64) {
        let view_width = self.view_size.width() * self.dpr;
        let view_height = self.view_size.height() * self.dpr;
        let padded_view_w = view_width - 2.0 * padding;
        let padded_view_h = view_height - 2.0 * padding;

        let scale_x = padded_view_w / target.width;
        let scale_y = padded_view_h / target.height;
        let new_scale = scale_x.min(scale_y).clamp(self.min_scale, self.max_scale);

        let scaled_target_w = target.width * new_scale;
        let scaled_target_h = target.height * new_scale;
        let offset_x = (view_width - scaled_target_w) / 2.0 - target.x * new_scale;
        let offset_y = (view_height - scaled_target_h) / 2.0 - target.y * new_scale;

        self.scale = new_scale;
        self.offset = Point::new(offset_x / self.dpr, offset_y / self.dpr);
    }
}

// ==========================================
// LayerTree 图层树
// ==========================================

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash, Copy)]
enum LayerType {
    Shape,
    Stroke,
    Image,
    Group,
    Text,
    Arrow,
    RichText,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct LayerData {
    id: Uuid,
    name: String,
    layer_type: LayerType,
    z_index: i32,
    visible: bool,
    locked: bool,
    opacity: f64,
    blend_mode: BlendMode,
    transform: Transform2D,
    bounds: Option<Rect>,
    parent_id: Option<Uuid>,
    children: Vec<Uuid>,
    dirty: bool,
    dirty_element_regions: Vec<Rect>,
}

/// @deprecated Use CanvasFacade instead
#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LayerTree {
    layers: FxHashMap<u64, LayerData>,
    root_children: Vec<Uuid>,
    z_order_sorted: Vec<Uuid>,
    dirty_layers: FxHashSet<u64>,
}

#[wasm_bindgen]
impl LayerTree {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        Self {
            layers: FxHashMap::default(),
            root_children: Vec::new(),
            z_order_sorted: Vec::new(),
            dirty_layers: FxHashSet::default(),
        }
    }

    pub fn create_shape_layer(&mut self, name: &str) -> String {
        self.create_layer_internal_helper(name, LayerType::Shape).to_string()
    }

    pub fn create_stroke_layer(&mut self, name: &str) -> String {
        self.create_layer_internal_helper(name, LayerType::Stroke).to_string()
    }

    pub fn create_image_layer(&mut self, name: &str) -> String {
        self.create_layer_internal_helper(name, LayerType::Image).to_string()
    }

    pub fn create_group_layer(&mut self, name: &str) -> String {
        self.create_layer_internal_helper(name, LayerType::Group).to_string()
    }

    pub fn create_text_layer(&mut self, name: &str) -> String {
        self.create_layer_internal_helper(name, LayerType::Text).to_string()
    }

    pub fn create_arrow_layer(&mut self, name: &str) -> String {
        self.create_layer_internal_helper(name, LayerType::Arrow).to_string()
    }

    pub fn create_richtext_layer(&mut self, name: &str) -> String {
        self.create_layer_internal_helper(name, LayerType::RichText).to_string()
    }

    pub fn layer_count(&self) -> usize {
        self.layers.len()
    }

    pub fn has_layer(&self, id: &str) -> bool {
        Uuid::parse_str(id).ok().map(|u| self.layers.contains_key(&hash_uuid(&u))).unwrap_or(false)
    }

    pub fn remove_layer(&mut self, id: &str) -> bool {
        if let Ok(uuid) = Uuid::parse_str(id) {
            let key = hash_uuid(&uuid);
            if let Some(layer) = self.layers.remove(&key) {
                self.dirty_layers.remove(&key);
                if let Some(parent_id) = layer.parent_id {
                    if let Some(parent) = self.layers.get_mut(&hash_uuid(&parent_id)) {
                        parent.children.retain(|c| c != &uuid);
                    }
                } else {
                    self.root_children.retain(|c| c != &uuid);
                }
                let children = layer.children.clone();
                for child_id in &children {
                    self.remove_layer(&child_id.to_string());
                }
                self.resort_z_order_helper();
                return true;
            }
        }
        false
    }

    pub fn get_layer_name(&self, id: &str) -> Option<String> {
        Uuid::parse_str(id).ok()
            .and_then(|u| self.layers.get(&hash_uuid(&u)))
            .map(|l| l.name.clone())
    }

    pub fn set_layer_name(&mut self, id: &str, name: &str) -> bool {
        if let Ok(uuid) = Uuid::parse_str(id) {
            if let Some(layer) = self.layers.get_mut(&hash_uuid(&uuid)) {
                layer.name = name.to_string();
                return true;
            }
        }
        false
    }

    pub fn is_layer_visible(&self, id: &str) -> bool {
        Uuid::parse_str(id).ok()
            .and_then(|u| self.layers.get(&hash_uuid(&u)))
            .map(|l| l.visible)
            .unwrap_or(false)
    }

    pub fn set_layer_visible(&mut self, id: &str, visible: bool) -> bool {
        if let Ok(uuid) = Uuid::parse_str(id) {
            let key = hash_uuid(&uuid);
            if let Some(layer) = self.layers.get_mut(&key) {
                layer.visible = visible;
                layer.dirty = true;
                self.dirty_layers.insert(key);
                return true;
            }
        }
        false
    }

    pub fn is_layer_locked(&self, id: &str) -> bool {
        Uuid::parse_str(id).ok()
            .and_then(|u| self.layers.get(&hash_uuid(&u)))
            .map(|l| l.locked)
            .unwrap_or(false)
    }

    pub fn set_layer_locked(&mut self, id: &str, locked: bool) -> bool {
        if let Ok(uuid) = Uuid::parse_str(id) {
            if let Some(layer) = self.layers.get_mut(&hash_uuid(&uuid)) {
                layer.locked = locked;
                return true;
            }
        }
        false
    }

    pub fn get_layer_opacity(&self, id: &str) -> f64 {
        Uuid::parse_str(id).ok()
            .and_then(|u| self.layers.get(&hash_uuid(&u)))
            .map(|l| l.opacity)
            .unwrap_or(1.0)
    }

    pub fn set_layer_opacity(&mut self, id: &str, opacity: f64) -> bool {
        if let Ok(uuid) = Uuid::parse_str(id) {
            let key = hash_uuid(&uuid);
            if let Some(layer) = self.layers.get_mut(&key) {
                layer.opacity = opacity.clamp(0.0, 1.0);
                layer.dirty = true;
                self.dirty_layers.insert(key);
                return true;
            }
        }
        false
    }

    pub fn get_layer_blend_mode(&self, id: &str) -> BlendMode {
        Uuid::parse_str(id).ok()
            .and_then(|u| self.layers.get(&hash_uuid(&u)))
            .map(|l| l.blend_mode)
            .unwrap_or(BlendMode::Normal)
    }

    pub fn set_layer_blend_mode(&mut self, id: &str, mode: BlendMode) -> bool {
        if let Ok(uuid) = Uuid::parse_str(id) {
            let key = hash_uuid(&uuid);
            if let Some(layer) = self.layers.get_mut(&key) {
                layer.blend_mode = mode;
                layer.dirty = true;
                self.dirty_layers.insert(key);
                return true;
            }
        }
        false
    }

    pub fn get_layer_z_index(&self, id: &str) -> i32 {
        Uuid::parse_str(id).ok()
            .and_then(|u| self.layers.get(&hash_uuid(&u)))
            .map(|l| l.z_index)
            .unwrap_or(0)
    }

    pub fn set_layer_z_index(&mut self, id: &str, z_index: i32) -> bool {
        if let Ok(uuid) = Uuid::parse_str(id) {
            let key = hash_uuid(&uuid);
            if let Some(layer) = self.layers.get_mut(&key) {
                layer.z_index = z_index;
                layer.dirty = true;
                self.dirty_layers.insert(key);
                self.resort_z_order_helper();
                return true;
            }
        }
        false
    }

    pub fn move_layer_up(&mut self, id: &str) -> bool {
        self.adjust_layer_z(id, 1)
    }

    pub fn move_layer_down(&mut self, id: &str) -> bool {
        self.adjust_layer_z(id, -1)
    }

    pub fn move_layer_to_front(&mut self, id: &str) -> bool {
        if let Ok(uuid) = Uuid::parse_str(id) {
            let key = hash_uuid(&uuid);
            if self.layers.contains_key(&key) {
                let max_z = self.root_children.iter()
                    .filter_map(|cid| self.layers.get(&hash_uuid(cid)))
                    .map(|l| l.z_index)
                    .max()
                    .unwrap_or(0);
                return self.set_layer_z_index(id, max_z + 1);
            }
        }
        false
    }

    pub fn move_layer_to_back(&mut self, id: &str) -> bool {
        if let Ok(uuid) = Uuid::parse_str(id) {
            let key = hash_uuid(&uuid);
            if self.layers.contains_key(&key) {
                let min_z = self.root_children.iter()
                    .filter_map(|cid| self.layers.get(&hash_uuid(cid)))
                    .map(|l| l.z_index)
                    .min()
                    .unwrap_or(0);
                return self.set_layer_z_index(id, min_z - 1);
            }
        }
        false
    }

    fn adjust_layer_z(&mut self, id: &str, delta: i32) -> bool {
        if let Ok(uuid) = Uuid::parse_str(id) {
            let key = hash_uuid(&uuid);
            if let Some(current_z) = self.layers.get(&key).map(|l| l.z_index) {
                return self.set_layer_z_index(id, current_z + delta);
            }
        }
        false
    }

    pub fn get_layer_transform(&self, id: &str) -> Option<Transform2D> {
        Uuid::parse_str(id).ok()
            .and_then(|u| self.layers.get(&hash_uuid(&u)))
            .map(|l| l.transform)
    }

    pub fn set_layer_transform(&mut self, id: &str, transform: &Transform2D) -> bool {
        if let Ok(uuid) = Uuid::parse_str(id) {
            let key = hash_uuid(&uuid);
            if let Some(layer) = self.layers.get_mut(&key) {
                layer.transform = *transform;
                layer.dirty = true;
                self.dirty_layers.insert(key);
                return true;
            }
        }
        false
    }

    pub fn get_layer_bounds(&self, id: &str) -> Option<Rect> {
        Uuid::parse_str(id).ok()
            .and_then(|u| self.layers.get(&hash_uuid(&u)))
            .and_then(|l| l.bounds)
    }

    pub fn set_layer_bounds(&mut self, id: &str, bounds: &Rect) -> bool {
        if let Ok(uuid) = Uuid::parse_str(id) {
            let key = hash_uuid(&uuid);
            if let Some(layer) = self.layers.get_mut(&key) {
                layer.bounds = Some(*bounds);
                layer.dirty = true;
                self.dirty_layers.insert(key);
                return true;
            }
        }
        false
    }

    pub fn reparent_layer(&mut self, id: &str, new_parent_id: Option<String>) -> bool {
        if let Ok(uuid) = Uuid::parse_str(id) {
            let key = hash_uuid(&uuid);
            if !self.layers.contains_key(&key) {
                return false;
            }

            let new_parent = new_parent_id.and_then(|s| Uuid::parse_str(&s).ok());
            if let Some(np) = new_parent {
                if !self.layers.contains_key(&hash_uuid(&np)) {
                    return false;
                }
            }

            let old_parent = self.layers.get(&key).and_then(|l| l.parent_id);
            if let Some(op) = old_parent {
                if let Some(parent) = self.layers.get_mut(&hash_uuid(&op)) {
                    parent.children.retain(|c| c != &uuid);
                }
            } else {
                self.root_children.retain(|c| c != &uuid);
            }

            if let Some(np) = new_parent {
                if let Some(parent) = self.layers.get_mut(&hash_uuid(&np)) {
                    parent.children.push(uuid);
                }
            } else {
                self.root_children.push(uuid);
            }

            if let Some(layer) = self.layers.get_mut(&key) {
                layer.parent_id = new_parent;
                layer.dirty = true;
                self.dirty_layers.insert(key);
            }

            self.resort_z_order_helper();
            return true;
        }
        false
    }

    pub fn get_visible_layers_sorted(&self) -> Vec<JsValue> {
        self.z_order_sorted.iter()
            .filter(|id| {
                self.layers.get(&hash_uuid(id))
                    .map(|l| l.visible)
                    .unwrap_or(false)
            })
            .map(|id| JsValue::from_str(&id.to_string()))
            .collect()
    }

    pub fn get_all_layers_sorted(&self) -> Vec<JsValue> {
        self.z_order_sorted.iter()
            .map(|id| JsValue::from_str(&id.to_string()))
            .collect()
    }

    pub fn is_layer_dirty(&self, id: &str) -> bool {
        Uuid::parse_str(id).ok()
            .and_then(|u| self.layers.get(&hash_uuid(&u)))
            .map(|l| l.dirty)
            .unwrap_or(false)
    }

    pub fn mark_layer_dirty(&mut self, id: &str) -> bool {
        if let Ok(uuid) = Uuid::parse_str(id) {
            let key = hash_uuid(&uuid);
            if let Some(layer) = self.layers.get_mut(&key) {
                layer.dirty = true;
            }
            self.dirty_layers.insert(key);
            return true;
        }
        false
    }

    pub fn has_dirty_layers(&self) -> bool {
        !self.dirty_layers.is_empty()
    }

    pub fn dirty_layer_count(&self) -> usize {
        self.dirty_layers.len()
    }

    pub fn clear_dirty(&mut self) {
        for key in &self.dirty_layers {
            if let Some(layer) = self.layers.get_mut(key) {
                layer.dirty = false;
                layer.dirty_element_regions.clear();
            }
        }
        self.dirty_layers.clear();
    }

    pub fn mark_element_dirty(&mut self, element_id: &str, layer_id: &str, bounds: &Rect) -> bool {
        if let Ok(uuid) = Uuid::parse_str(layer_id) {
            let key = hash_uuid(&uuid);
            if let Some(layer) = self.layers.get_mut(&key) {
                layer.dirty_element_regions.push(*bounds);
                self.dirty_layers.insert(key);
                return true;
            }
        }
        let _ = element_id;
        false
    }

    pub fn mark_elements_dirty(&mut self, elements: Vec<JsValue>) -> bool {
        let mut any_success = false;
        for val in &elements {
            if let Some(json_str) = val.as_string() {
                if let Ok(arr) = serde_json::from_str::<Vec<serde_json::Value>>(&json_str) {
                    for item in &arr {
                        let element_id = item.get("element_id").and_then(|v| v.as_str()).unwrap_or("");
                        let layer_id = item.get("layer_id").and_then(|v| v.as_str()).unwrap_or("");
                        let bounds = item.get("bounds");
                        if let (Ok(uuid), Some(b)) = (Uuid::parse_str(layer_id), bounds) {
                            let x = b.get("x").and_then(|v| v.as_f64()).unwrap_or(0.0);
                            let y = b.get("y").and_then(|v| v.as_f64()).unwrap_or(0.0);
                            let w = b.get("width").and_then(|v| v.as_f64()).unwrap_or(0.0);
                            let h = b.get("height").and_then(|v| v.as_f64()).unwrap_or(0.0);
                            if w > 0.0 && h > 0.0 {
                                let key = hash_uuid(&uuid);
                                if let Some(layer) = self.layers.get_mut(&key) {
                                    layer.dirty_element_regions.push(Rect::new(x, y, w, h));
                                    self.dirty_layers.insert(key);
                                    any_success = true;
                                }
                            }
                        }
                        let _ = element_id;
                    }
                }
            }
        }
        any_success
    }

    pub fn get_element_dirty_regions(&self, layer_id: &str) -> Vec<Rect> {
        Uuid::parse_str(layer_id).ok()
            .and_then(|u| self.layers.get(&hash_uuid(&u)))
            .map(|l| l.dirty_element_regions.clone())
            .unwrap_or_default()
    }

    pub fn clear_element_dirty(&mut self, layer_id: &str) -> bool {
        if let Ok(uuid) = Uuid::parse_str(layer_id) {
            let key = hash_uuid(&uuid);
            if let Some(layer) = self.layers.get_mut(&key) {
                layer.dirty_element_regions.clear();
                return true;
            }
        }
        false
    }

    pub fn hit_test(&self, world_point: &Point) -> Option<String> {
        for id in self.z_order_sorted.iter().rev() {
            if let Some(layer) = self.layers.get(&hash_uuid(id)) {
                if !layer.visible || layer.locked {
                    continue;
                }
                if let Some(bounds) = layer.bounds {
                    let local_point = if let Some(inv) = layer.transform.inverse() {
                        inv.transform_point(world_point)
                    } else {
                        *world_point
                    };
                    if bounds.contains_point(&local_point) {
                        return Some(id.to_string());
                    }
                }
            }
        }
        None
    }

    pub fn layers_in_rect(&self, rect: &Rect) -> Vec<JsValue> {
        let mut result = Vec::new();
        for id in &self.z_order_sorted {
            if let Some(layer) = self.layers.get(&hash_uuid(id)) {
                if !layer.visible {
                    continue;
                }
                if let Some(bounds) = layer.bounds {
                    let world_bounds_poly = layer.transform.transform_rect(&bounds);
                    let world_bounds = world_bounds_poly.bounding_box();
                    if world_bounds.intersects(rect) {
                        result.push(JsValue::from_str(&id.to_string()));
                    }
                }
            }
        }
        result
    }
}

impl LayerTree {
    fn create_layer_internal_helper(&mut self, name: &str, layer_type: LayerType) -> Uuid {
        let id = Uuid::new_v4();
        let max_z = self.root_children.iter()
            .filter_map(|cid| self.layers.get(&hash_uuid(cid)))
            .map(|l| l.z_index)
            .max()
            .unwrap_or(0);

        let layer = LayerData {
            id,
            name: name.to_string(),
            layer_type,
            z_index: max_z + 1,
            visible: true,
            locked: false,
            opacity: 1.0,
            blend_mode: BlendMode::Normal,
            transform: Transform2D::identity(),
            bounds: None,
            parent_id: None,
            children: Vec::new(),
            dirty: true,
            dirty_element_regions: Vec::new(),
        };

        let key = hash_uuid(&id);
        self.layers.insert(key, layer);
        self.root_children.push(id);
        self.dirty_layers.insert(key);
        self.resort_z_order_helper();
        id
    }

    fn resort_z_order_helper(&mut self) {
        let mut all_ids: Vec<Uuid> = self.layers.values().map(|l| l.id).collect();
        all_ids.sort_by(|a, b| {
            let la = self.layers.get(&hash_uuid(a)).unwrap();
            let lb = self.layers.get(&hash_uuid(b)).unwrap();
            la.z_index.cmp(&lb.z_index)
        });
        self.z_order_sorted = all_ids;
    }
}

// ==========================================
// DirtyRect 增量脏区重绘系统
// ==========================================

#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ElementDirtyEntry {
    element_id: String,
    layer_id: String,
    bounds: Rect,
}

#[wasm_bindgen]
impl ElementDirtyEntry {
    #[wasm_bindgen(constructor)]
    pub fn new(element_id: &str, layer_id: &str, bounds: &Rect) -> Self {
        Self {
            element_id: element_id.to_string(),
            layer_id: layer_id.to_string(),
            bounds: *bounds,
        }
    }

    #[wasm_bindgen(getter)]
    pub fn element_id(&self) -> String {
        self.element_id.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn layer_id(&self) -> String {
        self.layer_id.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn bounds(&self) -> Rect {
        self.bounds
    }
}

/// @deprecated Use CanvasFacade instead
#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DirtyRectManager {
    dirty_regions: Vec<Rect>,
    element_entries: Vec<ElementDirtyEntry>,
    max_regions: usize,
    merge_threshold: f64,
}

#[wasm_bindgen]
impl DirtyRectManager {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        Self {
            dirty_regions: Vec::new(),
            element_entries: Vec::new(),
            max_regions: 64,
            merge_threshold: 0.3,
        }
    }

    pub fn with_capacity(capacity: usize) -> Self {
        Self {
            dirty_regions: Vec::with_capacity(capacity),
            element_entries: Vec::new(),
            max_regions: 64,
            merge_threshold: 0.3,
        }
    }

    pub fn set_max_regions(&mut self, max: usize) {
        self.max_regions = max.max(1);
    }

    pub fn set_merge_threshold(&mut self, threshold: f64) {
        self.merge_threshold = threshold.clamp(0.0, 1.0);
    }

    pub fn add_rect(&mut self, rect: &Rect) {
        if rect.width <= 0.0 || rect.height <= 0.0 {
            return;
        }

        if self.dirty_regions.len() >= self.max_regions {
            if !self.dirty_regions.is_empty() {
                let mut merged = self.dirty_regions[0];
                for r in &self.dirty_regions[1..] {
                    merged = merged.union(r);
                }
                let combined = merged.union(rect);
                self.dirty_regions.clear();
                self.dirty_regions.push(combined);
                return;
            }
        }

        let mut merged = false;
        let mut i = 0;
        while i < self.dirty_regions.len() {
            let existing = self.dirty_regions[i];
            if existing.intersects(rect) {
                let union_rect = existing.union(rect);
                self.dirty_regions[i] = union_rect;
                merged = true;
                self.merge_adjacent(i);
                break;
            }

            let union_rect = existing.union(rect);
            let union_area = union_rect.area();
            let separate_area = existing.area() + rect.area();
            if separate_area > 0.0 && (separate_area - union_area) / separate_area < self.merge_threshold {
                self.dirty_regions[i] = union_rect;
                merged = true;
                self.merge_adjacent(i);
                break;
            }
            i += 1;
        }

        if !merged {
            self.dirty_regions.push(*rect);
        }
    }

    fn merge_adjacent(&mut self, index: usize) {
        if index >= self.dirty_regions.len() {
            return;
        }

        let mut changed = true;
        while changed {
            changed = false;
            if index >= self.dirty_regions.len() {
                break;
            }
            let current = self.dirty_regions[index];
            let mut i = 0;
            while i < self.dirty_regions.len() {
                if i == index {
                    i += 1;
                    continue;
                }
                let other = self.dirty_regions[i];
                if current.intersects(&other) {
                    self.dirty_regions[index] = current.union(&other);
                    self.dirty_regions.remove(i);
                    changed = true;
                    break;
                }

                let union_rect = current.union(&other);
                let union_area = union_rect.area();
                let separate_area = current.area() + other.area();
                if separate_area > 0.0 && (separate_area - union_area) / separate_area < self.merge_threshold {
                    self.dirty_regions[index] = union_rect;
                    self.dirty_regions.remove(i);
                    changed = true;
                    break;
                }
                i += 1;
            }
        }
    }

    pub fn add_rects(&mut self, rects: Vec<Rect>) {
        for r in &rects {
            self.add_rect(r);
        }
    }

    pub fn intersect_with_clip(&mut self, clip_rect: &Rect) {
        let mut new_regions = Vec::new();
        for r in &self.dirty_regions {
            if let Some(intersection) = r.intersection(clip_rect) {
                if intersection.width > 0.0 && intersection.height > 0.0 {
                    new_regions.push(intersection);
                }
            }
        }
        self.dirty_regions = new_regions;
    }

    pub fn clip_to_viewport(&mut self, viewport: &Viewport) {
        let visible = viewport.visible_world_rect();
        let screen_visible = viewport.world_rect_to_screen(&visible);
        self.intersect_with_clip(&screen_visible);
    }

    pub fn clear(&mut self) {
        self.dirty_regions.clear();
    }

    pub fn is_dirty(&self) -> bool {
        !self.dirty_regions.is_empty()
    }

    pub fn region_count(&self) -> usize {
        self.dirty_regions.len()
    }

    pub fn get_regions(&self) -> Vec<Rect> {
        self.dirty_regions.clone()
    }

    pub fn get_region(&self, index: usize) -> Option<Rect> {
        self.dirty_regions.get(index).copied()
    }

    pub fn total_bounds(&self) -> Option<Rect> {
        if self.dirty_regions.is_empty() {
            return None;
        }
        let mut result = self.dirty_regions[0];
        for r in &self.dirty_regions[1..] {
            result = result.union(r);
        }
        Some(result)
    }

    pub fn total_area(&self) -> f64 {
        self.dirty_regions.iter().map(|r| r.area()).sum()
    }

    pub fn expand_all(&mut self, padding: f64) {
        for r in &mut self.dirty_regions {
            *r = r.inflate(padding);
        }
    }

    pub fn needs_redraw(&self, rect: &Rect) -> bool {
        if !self.is_dirty() {
            return false;
        }
        for dirty in &self.dirty_regions {
            if dirty.intersects(rect) {
                return true;
            }
        }
        false
    }

    pub fn optimize(&mut self) {
        if self.dirty_regions.len() <= 1 {
            return;
        }
        let optimized = geometry::optimize_rects(&self.dirty_regions, self.merge_threshold);
        self.dirty_regions = optimized;
    }

    pub fn add_element_region(&mut self, element_id: &str, layer_id: &str, bounds: &Rect) {
        if bounds.width <= 0.0 || bounds.height <= 0.0 {
            return;
        }
        self.element_entries.push(ElementDirtyEntry::new(element_id, layer_id, bounds));
        self.add_rect(bounds);
    }

    pub fn get_element_dirty_entries(&self) -> Vec<ElementDirtyEntry> {
        self.element_entries.clone()
    }

    pub fn element_entry_count(&self) -> usize {
        self.element_entries.len()
    }

    pub fn clear_element_entries(&mut self) {
        self.element_entries.clear();
    }

    pub fn clear_all(&mut self) {
        self.dirty_regions.clear();
        self.element_entries.clear();
    }
}

// ==========================================
// 矢量图形光栅化 (Lyon)
// ==========================================

#[derive(Copy, Clone)]
struct LyonVertex {
    pos: LyonPoint,
}

/// @deprecated Use CanvasFacade instead
#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Vertex {
    pub x: f32,
    pub y: f32,
    pub u: f32,
    pub v: f32,
}

#[wasm_bindgen]
impl Vertex {
    #[wasm_bindgen(constructor)]
    pub fn new(x: f32, y: f32, u: f32, v: f32) -> Self {
        Self { x, y, u, v }
    }
}

/// @deprecated Use CanvasFacade instead
#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MeshData {
    vertices: Vec<Vertex>,
    indices: Vec<u32>,
}

#[wasm_bindgen]
impl MeshData {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        Self {
            vertices: Vec::new(),
            indices: Vec::new(),
        }
    }

    pub fn vertex_count(&self) -> usize {
        self.vertices.len()
    }

    pub fn index_count(&self) -> usize {
        self.indices.len()
    }

    pub fn vertices_ptr(&self) -> *const Vertex {
        self.vertices.as_ptr()
    }

    pub fn indices_ptr(&self) -> *const u32 {
        self.indices.as_ptr()
    }

    pub fn get_vertex(&self, index: usize) -> Option<Vertex> {
        self.vertices.get(index).cloned()
    }

    pub fn get_index(&self, index: usize) -> Option<u32> {
        self.indices.get(index).copied()
    }

    pub fn is_empty(&self) -> bool {
        self.vertices.is_empty()
    }
}

/// @deprecated Use CanvasFacade instead
#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PathBuilder {
    commands: Vec<u8>,
    points: Vec<Point>,
}

#[wasm_bindgen]
impl PathBuilder {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        Self {
            commands: Vec::new(),
            points: Vec::new(),
        }
    }

    pub fn move_to(&mut self, x: f64, y: f64) {
        self.commands.push(0);
        self.points.push(Point::new(x, y));
    }

    pub fn line_to(&mut self, x: f64, y: f64) {
        self.commands.push(1);
        self.points.push(Point::new(x, y));
    }

    pub fn quadratic_to(&mut self, cpx: f64, cpy: f64, x: f64, y: f64) {
        self.commands.push(2);
        self.points.push(Point::new(cpx, cpy));
        self.points.push(Point::new(x, y));
    }

    pub fn cubic_to(&mut self, cp1x: f64, cp1y: f64, cp2x: f64, cp2y: f64, x: f64, y: f64) {
        self.commands.push(3);
        self.points.push(Point::new(cp1x, cp1y));
        self.points.push(Point::new(cp2x, cp2y));
        self.points.push(Point::new(x, y));
    }

    pub fn close(&mut self) {
        self.commands.push(4);
    }

    pub fn rect(&mut self, x: f64, y: f64, w: f64, h: f64) {
        self.move_to(x, y);
        self.line_to(x + w, y);
        self.line_to(x + w, y + h);
        self.line_to(x, y + h);
        self.close();
    }

    pub fn rounded_rect(&mut self, x: f64, y: f64, w: f64, h: f64, radius: f64) {
        let r = radius.min(w / 2.0).min(h / 2.0);
        self.move_to(x + r, y);
        self.line_to(x + w - r, y);
        self.quadratic_to(x + w, y, x + w, y + r);
        self.line_to(x + w, y + h - r);
        self.quadratic_to(x + w, y + h, x + w - r, y + h);
        self.line_to(x + r, y + h);
        self.quadratic_to(x, y + h, x, y + h - r);
        self.line_to(x, y + r);
        self.quadratic_to(x, y, x + r, y);
        self.close();
    }

    pub fn circle(&mut self, cx: f64, cy: f64, r: f64) {
        let k = 0.5522847498;
        self.move_to(cx, cy - r);
        self.cubic_to(cx + r * k, cy - r, cx + r, cy - r * k, cx + r, cy);
        self.cubic_to(cx + r, cy + r * k, cx + r * k, cy + r, cx, cy + r);
        self.cubic_to(cx - r * k, cy + r, cx - r, cy + r * k, cx - r, cy);
        self.cubic_to(cx - r, cy - r * k, cx - r * k, cy - r, cx, cy - r);
        self.close();
    }

    pub fn ellipse(&mut self, cx: f64, cy: f64, rx: f64, ry: f64) {
        let kx = 0.5522847498 * rx;
        let ky = 0.5522847498 * ry;
        self.move_to(cx, cy - ry);
        self.cubic_to(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy);
        self.cubic_to(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry);
        self.cubic_to(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy);
        self.cubic_to(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry);
        self.close();
    }

    pub fn star(&mut self, x: f64, y: f64, outer_r: f64, inner_r: f64, num_points: u32, rotation: f64) {
        let n = num_points.max(3) as usize;
        let total_points = n * 2;
        let angle_step = std::f64::consts::PI / n as f64;
        let start_angle = rotation - std::f64::consts::FRAC_PI_2;

        for i in 0..total_points {
            let angle = start_angle + i as f64 * angle_step;
            let r = if i % 2 == 0 { outer_r } else { inner_r };
            let px = x + r * angle.cos();
            let py = y + r * angle.sin();
            if i == 0 {
                self.move_to(px, py);
            } else {
                self.line_to(px, py);
            }
        }
        self.close();
    }

    pub fn arrow(&mut self, x1: f64, y1: f64, x2: f64, y2: f64, head_size: f64, double_headed: bool) {
        let dx = x2 - x1;
        let dy = y2 - y1;
        let len = (dx * dx + dy * dy).sqrt();
        if len < 1e-6 {
            return;
        }

        let nx = dx / len;
        let ny = dy / len;
        let px = -ny;
        let py = nx;

        self.move_to(x1, y1);
        self.line_to(x2, y2);

        let hx = x2 - nx * head_size;
        let hy = y2 - ny * head_size;
        let hw = head_size * 0.5;
        self.move_to(x2, y2);
        self.line_to(hx + px * hw, hy + py * hw);
        self.line_to(hx - px * hw, hy - py * hw);
        self.close();

        if double_headed {
            let hx1 = x1 + nx * head_size;
            let hy1 = y1 + ny * head_size;
            let hw1 = head_size * 0.5;
            self.move_to(x1, y1);
            self.line_to(hx1 + px * hw1, hy1 + py * hw1);
            self.line_to(hx1 - px * hw1, hy1 - py * hw1);
            self.close();
        }
    }

    pub fn clear(&mut self) {
        self.commands.clear();
        self.points.clear();
    }

    pub fn command_count(&self) -> usize {
        self.commands.len()
    }

    fn build_lyon_path(&self) -> Path {
        let mut builder = Path::builder();
        let mut pi = 0;
        for cmd in &self.commands {
            match cmd {
                0 => {
                    let p = self.points[pi];
                    pi += 1;
                    builder.begin(to_lyon_point(&p));
                }
                1 => {
                    let p = self.points[pi];
                    pi += 1;
                    builder.line_to(to_lyon_point(&p));
                }
                2 => {
                    let cp = self.points[pi];
                    let to = self.points[pi + 1];
                    pi += 2;
                    builder.quadratic_bezier_to(to_lyon_point(&cp), to_lyon_point(&to));
                }
                3 => {
                    let cp1 = self.points[pi];
                    let cp2 = self.points[pi + 1];
                    let to = self.points[pi + 2];
                    pi += 3;
                    builder.cubic_bezier_to(to_lyon_point(&cp1), to_lyon_point(&cp2), to_lyon_point(&to));
                }
                4 => {
                    builder.close();
                }
                _ => {}
            }
        }
        builder.build()
    }

    pub fn bounds(&self) -> Option<Rect> {
        if self.points.is_empty() {
            return None;
        }
        let mut min_x = f64::INFINITY;
        let mut min_y = f64::INFINITY;
        let mut max_x = f64::NEG_INFINITY;
        let mut max_y = f64::NEG_INFINITY;
        for p in &self.points {
            min_x = min_x.min(p.x);
            min_y = min_y.min(p.y);
            max_x = max_x.max(p.x);
            max_y = max_y.max(p.y);
        }
        if min_x.is_finite() && min_y.is_finite() {
            Some(Rect::new(min_x, min_y, max_x - min_x, max_y - min_y))
        } else {
            None
        }
    }
}

/// @deprecated Use CanvasFacade instead
#[wasm_bindgen]
pub struct Rasterizer {
    fill_tessellator: Option<FillTessellator>,
    stroke_tessellator: Option<StrokeTessellator>,
}

#[wasm_bindgen]
impl Rasterizer {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        Self {
            fill_tessellator: Some(FillTessellator::new()),
            stroke_tessellator: Some(StrokeTessellator::new()),
        }
    }

    pub fn tessellate_fill(
        &mut self,
        path: &PathBuilder,
        fill_rule: FillRule,
        tolerance: f64,
    ) -> MeshData {
        let lyon_path = path.build_lyon_path();
        let options = LyonFillOptions::default()
            .with_fill_rule(to_lyon_fill_rule(fill_rule))
            .with_tolerance(tolerance.max(0.0001) as f32);

        let mut buffers: VertexBuffers<LyonVertex, u32> = VertexBuffers::new();
        let mut tess = self.fill_tessellator.take().unwrap_or_else(FillTessellator::new);

        let result = tess.tessellate_path(
            &lyon_path,
            &options,
            &mut BuffersBuilder::new(&mut buffers, |vertex: FillVertex| LyonVertex {
                pos: vertex.position(),
            }),
        );

        self.fill_tessellator = Some(tess);

        if result.is_err() {
            return MeshData::new();
        }

        let mut mesh = MeshData::new();
        for v in &buffers.vertices {
            mesh.vertices.push(Vertex::new(v.pos.x, v.pos.y, 0.0, 0.0));
        }
        mesh.indices = buffers.indices.clone();
        mesh
    }

    pub fn tessellate_stroke(
        &mut self,
        path: &PathBuilder,
        stroke_options: &StrokeOptions,
        tolerance: f64,
    ) -> MeshData {
        let lyon_path = path.build_lyon_path();
        let options = LyonStrokeOptions::default()
            .with_line_width(stroke_options.internal_width().max(0.0) as f32)
            .with_line_cap(to_lyon_line_cap(stroke_options.internal_line_cap()))
            .with_line_join(to_lyon_line_join(stroke_options.internal_line_join()))
            .with_miter_limit(stroke_options.internal_miter_limit().max(0.0) as f32)
            .with_tolerance(tolerance.max(0.0001) as f32);

        let mut buffers: VertexBuffers<LyonVertex, u32> = VertexBuffers::new();
        let mut tess = self.stroke_tessellator.take().unwrap_or_else(StrokeTessellator::new);

        let result = tess.tessellate_path(
            &lyon_path,
            &options,
            &mut BuffersBuilder::new(&mut buffers, |vertex: StrokeVertex| LyonVertex {
                pos: vertex.position(),
            }),
        );

        self.stroke_tessellator = Some(tess);

        if result.is_err() {
            return MeshData::new();
        }

        let mut mesh = MeshData::new();
        for v in &buffers.vertices {
            mesh.vertices.push(Vertex::new(v.pos.x, v.pos.y, 0.0, 0.0));
        }
        mesh.indices = buffers.indices.clone();
        mesh
    }

    pub fn tessellate_stroke_fill(
        &mut self,
        path: &PathBuilder,
        stroke_options: &StrokeOptions,
        fill_rule: FillRule,
        tolerance: f64,
    ) -> MeshData {
        let stroke_mesh = self.tessellate_stroke(path, stroke_options, tolerance);
        let fill_mesh = self.tessellate_fill(path, fill_rule, tolerance);

        let mut combined = MeshData::new();
        let index_offset = stroke_mesh.vertices.len() as u32;

        for v in &stroke_mesh.vertices {
            combined.vertices.push(v.clone());
        }
        for idx in &stroke_mesh.indices {
            combined.indices.push(*idx);
        }

        for v in &fill_mesh.vertices {
            combined.vertices.push(v.clone());
        }
        for idx in &fill_mesh.indices {
            combined.indices.push(*idx + index_offset);
        }

        combined
    }

    pub fn tessellate_rect_fill(&mut self, rect: &Rect, fill_rule: FillRule) -> MeshData {
        let mut pb = PathBuilder::new();
        pb.rect(rect.x, rect.y, rect.width, rect.height);
        self.tessellate_fill(&pb, fill_rule, 0.1)
    }

    pub fn tessellate_rect_stroke(&mut self, rect: &Rect, stroke_options: &StrokeOptions) -> MeshData {
        let mut pb = PathBuilder::new();
        pb.rect(rect.x, rect.y, rect.width, rect.height);
        self.tessellate_stroke(&pb, stroke_options, 0.1)
    }

    pub fn tessellate_circle_fill(&mut self, cx: f64, cy: f64, r: f64, fill_rule: FillRule) -> MeshData {
        let mut pb = PathBuilder::new();
        pb.circle(cx, cy, r);
        self.tessellate_fill(&pb, fill_rule, 0.1)
    }

    pub fn tessellate_circle_stroke(&mut self, cx: f64, cy: f64, r: f64, stroke_options: &StrokeOptions) -> MeshData {
        let mut pb = PathBuilder::new();
        pb.circle(cx, cy, r);
        self.tessellate_stroke(&pb, stroke_options, 0.1)
    }

    pub fn tessellate_rounded_rect_fill(
        &mut self,
        x: f64,
        y: f64,
        w: f64,
        h: f64,
        r: f64,
        fill_rule: FillRule,
    ) -> MeshData {
        let mut pb = PathBuilder::new();
        pb.rounded_rect(x, y, w, h, r);
        self.tessellate_fill(&pb, fill_rule, 0.1)
    }

    pub fn tessellate_rounded_rect_stroke(
        &mut self,
        x: f64,
        y: f64,
        w: f64,
        h: f64,
        r: f64,
        stroke_options: &StrokeOptions,
    ) -> MeshData {
        let mut pb = PathBuilder::new();
        pb.rounded_rect(x, y, w, h, r);
        self.tessellate_stroke(&pb, stroke_options, 0.1)
    }

    pub fn tessellate_line(&mut self, x1: f64, y1: f64, x2: f64, y2: f64, stroke_options: &StrokeOptions) -> MeshData {
        let mut pb = PathBuilder::new();
        pb.move_to(x1, y1);
        pb.line_to(x2, y2);
        self.tessellate_stroke(&pb, stroke_options, 0.1)
    }

    pub fn tessellate_stroke_points(&mut self, stroke: &Stroke, tolerance: f64) -> MeshData {
        if stroke.point_count() < 2 {
            return MeshData::new();
        }

        let mut pb = PathBuilder::new();
        if let Some(first) = stroke.get_point(0) {
            pb.move_to(first.position.x, first.position.y);
        }

        for i in 1..stroke.point_count() {
            if let Some(p) = stroke.get_point(i) {
                if i == 1 {
                    pb.line_to(p.position.x, p.position.y);
                } else {
                    if let (Some(prev), Some(_prev_prev)) = (stroke.get_point(i - 1), stroke.get_point(i - 2)) {
                        let cp_x = prev.position.x;
                        let cp_y = prev.position.y;
                        let to_x = (prev.position.x + p.position.x) / 2.0;
                        let to_y = (prev.position.y + p.position.y) / 2.0;
                        pb.quadratic_to(cp_x, cp_y, to_x, to_y);
                    } else {
                        pb.line_to(p.position.x, p.position.y);
                    }
                }
            }
        }

        self.tessellate_stroke(&pb, &stroke.stroke_options(), tolerance)
    }

    pub fn tessellate_star_fill(
        &mut self,
        x: f64,
        y: f64,
        outer_r: f64,
        inner_r: f64,
        num_points: u32,
        rotation: f64,
        fill_rule: FillRule,
    ) -> MeshData {
        let mut pb = PathBuilder::new();
        pb.star(x, y, outer_r, inner_r, num_points, rotation);
        self.tessellate_fill(&pb, fill_rule, 0.1)
    }

    pub fn tessellate_star_stroke(
        &mut self,
        x: f64,
        y: f64,
        outer_r: f64,
        inner_r: f64,
        num_points: u32,
        rotation: f64,
        stroke_options: &StrokeOptions,
    ) -> MeshData {
        let mut pb = PathBuilder::new();
        pb.star(x, y, outer_r, inner_r, num_points, rotation);
        self.tessellate_stroke(&pb, stroke_options, 0.1)
    }

    pub fn tessellate_arrow_stroke(
        &mut self,
        x1: f64,
        y1: f64,
        x2: f64,
        y2: f64,
        head_size: f64,
        double_headed: bool,
        stroke_options: &StrokeOptions,
    ) -> MeshData {
        let mut pb = PathBuilder::new();
        pb.arrow(x1, y1, x2, y2, head_size, double_headed);
        self.tessellate_stroke(&pb, stroke_options, 0.1)
    }

    pub fn tessellate_arrow_fill(
        &mut self,
        x1: f64,
        y1: f64,
        x2: f64,
        y2: f64,
        head_size: f64,
        double_headed: bool,
        fill_rule: FillRule,
    ) -> MeshData {
        let mut pb = PathBuilder::new();
        pb.arrow(x1, y1, x2, y2, head_size, double_headed);
        self.tessellate_fill(&pb, fill_rule, 0.1)
    }
}

// ==========================================
// 渲染上下文 / Renderer 主入口
// ==========================================

/// @deprecated Use CanvasFacade instead
#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RenderContext {
    viewport: Viewport,
    layer_tree: LayerTree,
    dirty_manager: DirtyRectManager,
    background_color: Color,
    clear_color: Color,
}

#[wasm_bindgen]
impl RenderContext {
    #[wasm_bindgen(constructor)]
    pub fn new(view_width: f64, view_height: f64) -> Self {
        Self {
            viewport: Viewport::new(view_width, view_height),
            layer_tree: LayerTree::new(),
            dirty_manager: DirtyRectManager::new(),
            background_color: Color::white(),
            clear_color: Color::white(),
        }
    }

    #[wasm_bindgen(getter)]
    pub fn viewport(&self) -> Viewport {
        self.viewport.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn layer_tree(&self) -> LayerTree {
        self.layer_tree.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn dirty_manager(&self) -> DirtyRectManager {
        self.dirty_manager.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn background_color(&self) -> Color {
        self.background_color
    }

    #[wasm_bindgen(setter)]
    pub fn set_background_color(&mut self, color: Color) {
        self.background_color = color;
        self.mark_all_dirty();
    }

    #[wasm_bindgen(getter)]
    pub fn clear_color(&self) -> Color {
        self.clear_color
    }

    #[wasm_bindgen(setter)]
    pub fn set_clear_color(&mut self, color: Color) {
        self.clear_color = color;
    }

    pub fn resize(&mut self, width: f64, height: f64) {
        self.viewport.set_view_size(width, height);
        self.mark_all_dirty();
    }

    pub fn mark_all_dirty(&mut self) {
        let view_rect = Rect::new(0.0, 0.0, self.viewport.view_size.width(), self.viewport.view_size.height());
        self.dirty_manager.add_rect(&view_rect);
    }

    pub fn mark_layer_region_dirty(&mut self, layer_id: &str) -> bool {
        if let Some(bounds) = self.layer_tree.get_layer_bounds(layer_id) {
            if let Some(transform) = self.layer_tree.get_layer_transform(layer_id) {
                let world_bounds_poly = transform.transform_rect(&bounds);
                let world_bounds = world_bounds_poly.bounding_box();
                let screen_bounds = self.viewport.world_rect_to_screen(&world_bounds);
                self.dirty_manager.add_rect(&screen_bounds.inflate(2.0));
                return true;
            }
        }
        false
    }

    pub fn mark_element_dirty(&mut self, layer_id: &str, element_id: &str, element_bounds: &Rect) -> bool {
        if let Some(transform) = self.layer_tree.get_layer_transform(layer_id) {
            let world_bounds_poly = transform.transform_rect(element_bounds);
            let world_bounds = world_bounds_poly.bounding_box();
            let screen_bounds = self.viewport.world_rect_to_screen(&world_bounds);
            self.dirty_manager.add_element_region(element_id, layer_id, &screen_bounds.inflate(2.0));
            self.layer_tree.mark_element_dirty(element_id, layer_id, element_bounds);
            return true;
        }
        false
    }

    pub fn mark_layer_dirty(&mut self, layer_id: &str) -> bool {
        self.layer_tree.mark_layer_dirty(layer_id)
    }

    pub fn needs_redraw(&self) -> bool {
        self.dirty_manager.is_dirty() || self.layer_tree.has_dirty_layers()
    }

    pub fn begin_frame(&mut self) {
        let layer_ids = self.layer_tree.get_all_layers_sorted();
        for id_js in &layer_ids {
            if let Some(id_str) = id_js.as_string() {
                let element_regions = self.layer_tree.get_element_dirty_regions(&id_str);
                for bounds in &element_regions {
                    if let Some(transform) = self.layer_tree.get_layer_transform(&id_str) {
                        let world_bounds_poly = transform.transform_rect(bounds);
                        let world_bounds = world_bounds_poly.bounding_box();
                        let screen_bounds = self.viewport.world_rect_to_screen(&world_bounds);
                        self.dirty_manager.add_rect(&screen_bounds.inflate(2.0));
                    }
                }
            }
        }

        self.dirty_manager.clip_to_viewport(&self.viewport);
        self.dirty_manager.optimize();
    }

    pub fn end_frame(&mut self) {
        self.dirty_manager.clear_all();
        self.layer_tree.clear_dirty();
    }

    pub fn get_draw_regions(&self) -> Vec<Rect> {
        self.dirty_manager.get_regions()
    }

    pub fn pan(&mut self, dx: f64, dy: f64) {
        self.viewport.pan(dx, dy);
        self.mark_all_dirty();
    }

    pub fn zoom(&mut self, factor: f64, center: Option<Point>) {
        self.viewport.zoom(factor, center);
        self.mark_all_dirty();
    }

    pub fn zoom_at(&mut self, screen_x: f64, screen_y: f64, factor: f64) {
        self.viewport.zoom_at(screen_x, screen_y, factor);
        self.mark_all_dirty();
    }

    pub fn reset_view(&mut self) {
        self.viewport.reset();
        self.mark_all_dirty();
    }

    pub fn fit_to_content(&mut self, padding: f64) {
        let layer_ids = self.layer_tree.get_all_layers_sorted();
        let mut all_bounds: Vec<Rect> = Vec::new();

        for id_js in &layer_ids {
            if let Some(id_str) = id_js.as_string() {
                if let (Some(bounds), Some(transform)) = (
                    self.layer_tree.get_layer_bounds(&id_str),
                    self.layer_tree.get_layer_transform(&id_str),
                ) {
                    let world_bounds_poly = transform.transform_rect(&bounds);
                    all_bounds.push(world_bounds_poly.bounding_box());
                }
            }
        }

        if let Some(combined) = geometry::merge_rects(&all_bounds) {
            self.viewport.fit_to_rect(&combined, padding);
            self.mark_all_dirty();
        }
    }

    pub fn screen_to_world(&self, x: f64, y: f64) -> Point {
        self.viewport.screen_to_world(&Point::new(x, y))
    }

    pub fn world_to_screen(&self, x: f64, y: f64) -> Point {
        self.viewport.world_to_screen(&Point::new(x, y))
    }

    pub fn hit_test(&self, screen_x: f64, screen_y: f64) -> Option<String> {
        let world_p = self.screen_to_world(screen_x, screen_y);
        self.layer_tree.hit_test(&world_p)
    }

    pub fn set_dpr(&mut self, dpr: f64) {
        self.viewport.set_dpr(dpr);
        self.mark_all_dirty();
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_default()
    }

    pub fn from_json(json: &str) -> Option<RenderContext> {
        serde_json::from_str(json).ok()
    }
}

// ==========================================
// Artboard 画板（用于多页 PDF 导出）
// ==========================================

/// @deprecated Use ExportFacade instead
#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Artboard {
    id: String,
    name: String,
    x: f64,
    y: f64,
    width: f64,
    height: f64,
    layer_ids: Vec<String>,
}

#[wasm_bindgen]
impl Artboard {
    #[wasm_bindgen(constructor)]
    pub fn new(name: &str, x: f64, y: f64, width: f64, height: f64) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            name: name.to_string(),
            x,
            y,
            width,
            height,
            layer_ids: Vec::new(),
        }
    }

    #[wasm_bindgen(getter)]
    pub fn id(&self) -> String {
        self.id.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn name(&self) -> String {
        self.name.clone()
    }

    #[wasm_bindgen(setter)]
    pub fn set_name(&mut self, name: &str) {
        self.name = name.to_string();
    }

    #[wasm_bindgen(getter)]
    pub fn x(&self) -> f64 {
        self.x
    }

    #[wasm_bindgen(setter)]
    pub fn set_x(&mut self, x: f64) {
        self.x = x;
    }

    #[wasm_bindgen(getter)]
    pub fn y(&self) -> f64 {
        self.y
    }

    #[wasm_bindgen(setter)]
    pub fn set_y(&mut self, y: f64) {
        self.y = y;
    }

    #[wasm_bindgen(getter)]
    pub fn width(&self) -> f64 {
        self.width
    }

    #[wasm_bindgen(setter)]
    pub fn set_width(&mut self, width: f64) {
        self.width = width;
    }

    #[wasm_bindgen(getter)]
    pub fn height(&self) -> f64 {
        self.height
    }

    #[wasm_bindgen(setter)]
    pub fn set_height(&mut self, height: f64) {
        self.height = height;
    }

    pub fn set_position(&mut self, x: f64, y: f64) {
        self.x = x;
        self.y = y;
    }

    pub fn set_size(&mut self, width: f64, height: f64) {
        self.width = width;
        self.height = height;
    }

    pub fn add_layer(&mut self, layer_id: &str) {
        if !self.layer_ids.iter().any(|id| id == layer_id) {
            self.layer_ids.push(layer_id.to_string());
        }
    }

    pub fn remove_layer(&mut self, layer_id: &str) {
        self.layer_ids.retain(|id| id != layer_id);
    }

    pub fn layer_count(&self) -> usize {
        self.layer_ids.len()
    }

    pub fn get_layer_ids(&self) -> Vec<JsValue> {
        self.layer_ids.iter().map(|id| JsValue::from_str(id)).collect()
    }

    pub fn rect(&self) -> Rect {
        Rect::new(self.x, self.y, self.width, self.height)
    }
}

// ==========================================
// SVGExport SVG 导出器
// ==========================================

/// @deprecated Use ExportFacade instead
#[wasm_bindgen]
#[derive(Debug, Clone)]
pub struct SVGExport;

#[wasm_bindgen]
impl SVGExport {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        Self
    }

    pub fn to_svg_string(&self, layers: &LayerTree, viewport: &Viewport) -> String {
        let visible_rect = viewport.visible_world_rect();
        let vb_x = visible_rect.x;
        let vb_y = visible_rect.y;
        let vb_w = visible_rect.width;
        let vb_h = visible_rect.height;

        let mut svg = String::new();
        svg.push_str(&format!(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\
             <svg xmlns=\"http://www.w3.org/2000/svg\" \
             viewBox=\"{} {} {} {}\" \
             width=\"{}\" height=\"{}\" version=\"1.1\">\n",
            vb_x, vb_y, vb_w, vb_h,
            viewport.view_size.width(), viewport.view_size.height()
        ));
        svg.push_str(&format!(
            "  <defs>\n    <style type=\"text/css\"><![CDATA[\n      .layer {{ opacity: 1; }}\n    ]]></style>\n  </defs>\n"
        ));

        let sorted_ids = layers.get_all_layers_sorted();
        let mut group_stack: Vec<(String, usize)> = Vec::new();
        let mut written_groups: FxHashSet<String> = FxHashSet::default();

        for id_js in &sorted_ids {
            if let Some(id_str) = id_js.as_string() {
                if let Some(layer) = layers.layers.get(&hash_uuid(&Uuid::parse_str(&id_str).unwrap())) {
                    if !layer.visible {
                        continue;
                    }

                    while let Some((parent_id, depth)) = group_stack.last() {
                        if layer.parent_id.map(|p| p.to_string()).as_ref() != Some(parent_id) {
                            for _ in 0..*depth {
                                svg.push_str("  </g>\n");
                            }
                            group_stack.pop();
                        } else {
                            break;
                        }
                    }

                    let depth = group_stack.last().map(|(_, d)| *d + 2).unwrap_or(2);
                    let indent = "  ".repeat(depth);

                    let layer_name_escaped = Self::escape_xml(&layer.name);
                    let opacity = layer.opacity;
                    let transform = layer.transform;
                    let transform_str = if !transform.is_identity(1e-6) {
                        format!(
                            " transform=\"matrix({:.6} {:.6} {:.6} {:.6} {:.6} {:.6})\"",
                            transform.a, transform.b, transform.c,
                            transform.d, transform.e, transform.f
                        )
                    } else {
                        String::new()
                    };

                    match layer.layer_type {
                        LayerType::Group => {
                            if !written_groups.contains(&id_str) {
                                svg.push_str(&format!(
                                    "{}<g id=\"layer-{}\" class=\"layer group\" data-name=\"{}\" opacity=\"{:.3}\"{}>\n",
                                    indent, id_str, layer_name_escaped, opacity, transform_str
                                ));
                                group_stack.push((id_str.clone(), depth));
                                written_groups.insert(id_str.clone());
                            }
                        }
                        LayerType::Text | LayerType::RichText => {
                            svg.push_str(&format!(
                                "{}<g id=\"layer-{}\" class=\"layer text\" data-name=\"{}\" opacity=\"{:.3}\"{}>\n",
                                indent, id_str, layer_name_escaped, opacity, transform_str
                            ));
                            if let Some(bounds) = layer.bounds {
                                let text_content = if layer.layer_type == LayerType::RichText {
                                    format!(
                                        "<foreignObject x=\"{}\" y=\"{}\" width=\"{}\" height=\"{}\">\n\
                                         {}  <div xmlns=\"http://www.w3.org/1999/xhtml\" style=\"font-family:sans-serif;font-size:14px;\">\n\
                                         {}    RichText Placeholder\n\
                                         {}  </div>\n\
                                         {}</foreignObject>",
                                        bounds.x, bounds.y, bounds.width, bounds.height,
                                        indent, indent, indent, indent
                                    )
                                } else {
                                    format!(
                                        "<text x=\"{}\" y=\"{}\" font-family=\"sans-serif\" font-size=\"14\" fill=\"#000000\">Text Placeholder</text>",
                                        bounds.x, bounds.y + 14.0
                                    )
                                };
                                svg.push_str(&format!("{}{}\n", "  ".repeat(depth + 1), text_content));
                            }
                            svg.push_str(&format!("{}</g>\n", indent));
                        }
                        _ => {
                            let class_name = match layer.layer_type {
                                LayerType::Shape => "shape",
                                LayerType::Stroke => "stroke",
                                LayerType::Arrow => "arrow",
                                LayerType::Image => "image",
                                _ => "unknown",
                            };
                            svg.push_str(&format!(
                                "{}<g id=\"layer-{}\" class=\"layer {}\" data-name=\"{}\" opacity=\"{:.3}\"{}>\n",
                                indent, id_str, class_name, layer_name_escaped, opacity, transform_str
                            ));
                            if let Some(bounds) = layer.bounds {
                                if layer.layer_type == LayerType::Image {
                                    svg.push_str(&format!(
                                        "{}  <rect x=\"{}\" y=\"{}\" width=\"{}\" height=\"{}\" fill=\"#cccccc\" stroke=\"#999999\" stroke-width=\"1\"/>\n",
                                        indent, bounds.x, bounds.y, bounds.width, bounds.height
                                    ));
                                } else {
                                    svg.push_str(&format!(
                                        "{}  <path d=\"M {} {} L {} {} L {} {} L {} {} Z\" fill=\"none\" stroke=\"#333333\" stroke-width=\"2\" data-placeholder=\"path\"/>\n",
                                        indent,
                                        bounds.x, bounds.y,
                                        bounds.x + bounds.width, bounds.y,
                                        bounds.x + bounds.width, bounds.y + bounds.height,
                                        bounds.x, bounds.y + bounds.height
                                    ));
                                }
                            }
                            svg.push_str(&format!("{}</g>\n", indent));
                        }
                    }
                }
            }
        }

        while let Some((_, depth)) = group_stack.pop() {
            for _ in 0..depth {
                svg.push_str("  </g>\n");
            }
        }

        svg.push_str("</svg>\n");
        svg
    }

    fn escape_xml(s: &str) -> String {
        s.replace('&', "&amp;")
            .replace('<', "&lt;")
            .replace('>', "&gt;")
            .replace('"', "&quot;")
            .replace('\'', "&apos;")
    }
}

impl Default for SVGExport {
    fn default() -> Self {
        Self::new()
    }
}

// ==========================================
// PDFExport PDF 导出器（矢量路径导出）
// ==========================================

/// @deprecated Use ExportFacade instead
#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PDFExport {
    artboards: Vec<Artboard>,
    title: String,
    author: String,
    subject: String,
}

#[wasm_bindgen]
impl PDFExport {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        Self {
            artboards: Vec::new(),
            title: String::new(),
            author: String::new(),
            subject: String::new(),
        }
    }

    #[wasm_bindgen(getter)]
    pub fn title(&self) -> String {
        self.title.clone()
    }

    #[wasm_bindgen(setter)]
    pub fn set_title(&mut self, title: &str) {
        self.title = title.to_string();
    }

    #[wasm_bindgen(getter)]
    pub fn author(&self) -> String {
        self.author.clone()
    }

    #[wasm_bindgen(setter)]
    pub fn set_author(&mut self, author: &str) {
        self.author = author.to_string();
    }

    #[wasm_bindgen(getter)]
    pub fn subject(&self) -> String {
        self.subject.clone()
    }

    #[wasm_bindgen(setter)]
    pub fn set_subject(&mut self, subject: &str) {
        self.subject = subject.to_string();
    }

    pub fn add_artboard(&mut self, artboard: &Artboard) {
        self.artboards.push(artboard.clone());
    }

    pub fn remove_artboard(&mut self, id: &str) -> bool {
        let before = self.artboards.len();
        self.artboards.retain(|a| a.id != id);
        self.artboards.len() < before
    }

    pub fn artboard_count(&self) -> usize {
        self.artboards.len()
    }

    pub fn get_artboard(&self, index: usize) -> Option<Artboard> {
        self.artboards.get(index).cloned()
    }

    pub fn get_artboard_ids(&self) -> Vec<JsValue> {
        self.artboards.iter().map(|a| JsValue::from_str(&a.id)).collect()
    }

    pub fn clear_artboards(&mut self) {
        self.artboards.clear();
    }

    pub fn generate_page_descriptions(&self, layers: &LayerTree) -> Vec<JsValue> {
        let mut pages = Vec::new();
        for artboard in &self.artboards {
            let page_desc = serde_json::json!({
                "id": artboard.id,
                "name": artboard.name,
                "width": artboard.width,
                "height": artboard.height,
                "x": artboard.x,
                "y": artboard.y,
                "layer_ids": artboard.layer_ids,
            });
            let json_str = serde_json::to_string(&page_desc).unwrap_or_default();
            pages.push(js_sys::JSON::parse(&json_str).unwrap_or(JsValue::NULL));
        }
        pages
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_default()
    }

    pub fn from_json(json: &str) -> Option<PDFExport> {
        serde_json::from_str(json).ok()
    }
}

impl Default for PDFExport {
    fn default() -> Self {
        Self::new()
    }
}

// ==========================================
// CanvasFacade 渲染统一入口
// ==========================================

#[wasm_bindgen]
pub struct CanvasFacade {
    context: RenderContext,
    rasterizer: Rasterizer,
    tool_registry: ToolRegistry,
}

#[wasm_bindgen]
impl CanvasFacade {
    #[wasm_bindgen(constructor)]
    pub fn new(width: f64, height: f64) -> Self {
        Self {
            context: RenderContext::new(width, height),
            rasterizer: Rasterizer::new(),
            tool_registry: ToolRegistry::new(),
        }
    }

    pub fn pan(&mut self, dx: f64, dy: f64) {
        self.context.pan(dx, dy);
    }

    pub fn zoom(&mut self, factor: f64, cx: f64, cy: f64) {
        self.context.zoom(factor, Some(Point::new(cx, cy)));
    }

    pub fn zoom_at(&mut self, sx: f64, sy: f64, factor: f64) {
        self.context.zoom_at(sx, sy, factor);
    }

    pub fn reset_view(&mut self) {
        self.context.reset_view();
    }

    pub fn fit_to_content(&mut self, padding: f64) {
        self.context.fit_to_content(padding);
    }

    pub fn set_dpr(&mut self, dpr: f64) {
        self.context.set_dpr(dpr);
    }

    pub fn resize(&mut self, width: f64, height: f64) {
        self.context.resize(width, height);
    }

    pub fn get_viewport_json(&self) -> String {
        serde_json::to_string(&self.context.viewport).unwrap_or_default()
    }

    pub fn screen_to_world(&self, x: f64, y: f64) -> Point {
        self.context.screen_to_world(x, y)
    }

    pub fn world_to_screen(&self, x: f64, y: f64) -> Point {
        self.context.world_to_screen(x, y)
    }

    pub fn create_shape_layer(&mut self, name: &str) -> String {
        self.context.layer_tree.create_shape_layer(name)
    }

    pub fn create_stroke_layer(&mut self, name: &str) -> String {
        self.context.layer_tree.create_stroke_layer(name)
    }

    pub fn create_image_layer(&mut self, name: &str) -> String {
        self.context.layer_tree.create_image_layer(name)
    }

    pub fn create_group_layer(&mut self, name: &str) -> String {
        self.context.layer_tree.create_group_layer(name)
    }

    pub fn create_text_layer(&mut self, name: &str) -> String {
        self.context.layer_tree.create_text_layer(name)
    }

    pub fn create_arrow_layer(&mut self, name: &str) -> String {
        self.context.layer_tree.create_arrow_layer(name)
    }

    pub fn create_richtext_layer(&mut self, name: &str) -> String {
        self.context.layer_tree.create_richtext_layer(name)
    }

    pub fn remove_layer(&mut self, id: &str) -> bool {
        self.context.layer_tree.remove_layer(id)
    }

    pub fn set_layer_visible(&mut self, id: &str, visible: bool) -> bool {
        self.context.layer_tree.set_layer_visible(id, visible)
    }

    pub fn set_layer_opacity(&mut self, id: &str, opacity: f64) -> bool {
        self.context.layer_tree.set_layer_opacity(id, opacity)
    }

    pub fn set_layer_bounds(&mut self, id: &str, x: f64, y: f64, w: f64, h: f64) -> bool {
        self.context.layer_tree.set_layer_bounds(id, &Rect::new(x, y, w, h))
    }

    pub fn move_layer_up(&mut self, id: &str) -> bool {
        self.context.layer_tree.move_layer_up(id)
    }

    pub fn move_layer_down(&mut self, id: &str) -> bool {
        self.context.layer_tree.move_layer_down(id)
    }

    pub fn get_layers_json(&self) -> String {
        serde_json::to_string(&self.context.layer_tree).unwrap_or_default()
    }

    pub fn mark_element_dirty(&mut self, layer_id: &str, _element_id: &str, x: f64, y: f64, w: f64, h: f64) {
        self.context.dirty_manager.add_rect(&Rect::new(x, y, w, h));
        let key = Uuid::parse_str(layer_id).ok().map(|u| hash_uuid(&u));
        if let Some(k) = key {
            if let Some(layer) = self.context.layer_tree.layers.get_mut(&k) {
                layer.dirty = true;
            }
            self.context.layer_tree.dirty_layers.insert(k);
        }
    }

    pub fn mark_layer_dirty(&mut self, id: &str) -> bool {
        self.context.layer_tree.mark_layer_dirty(id)
    }

    pub fn mark_all_dirty(&mut self) {
        self.context.mark_all_dirty();
    }

    pub fn needs_redraw(&self) -> bool {
        self.context.needs_redraw()
    }

    pub fn begin_frame(&mut self) {
        self.context.begin_frame();
    }

    pub fn end_frame(&mut self) {
        self.context.end_frame();
    }

    pub fn get_dirty_regions_json(&self) -> String {
        serde_json::to_string(&self.context.dirty_manager.get_regions()).unwrap_or_default()
    }

    pub fn hit_test(&self, sx: f64, sy: f64) -> Option<String> {
        self.context.hit_test(sx, sy)
    }

    pub fn tessellate_rect(&mut self, x: f64, y: f64, w: f64, h: f64, fill: bool) -> MeshData {
        let rect = Rect::new(x, y, w, h);
        if fill {
            self.rasterizer.tessellate_rect_fill(&rect, FillRule::NonZero)
        } else {
            self.rasterizer.tessellate_rect_stroke(&rect, &StrokeOptions::new(1.0))
        }
    }

    pub fn tessellate_circle(&mut self, cx: f64, cy: f64, r: f64, fill: bool) -> MeshData {
        if fill {
            self.rasterizer.tessellate_circle_fill(cx, cy, r, FillRule::NonZero)
        } else {
            self.rasterizer.tessellate_circle_stroke(cx, cy, r, &StrokeOptions::new(1.0))
        }
    }

    pub fn tessellate_ellipse(&mut self, cx: f64, cy: f64, rx: f64, ry: f64, fill: bool) -> MeshData {
        let mut pb = PathBuilder::new();
        pb.ellipse(cx, cy, rx, ry);
        if fill {
            self.rasterizer.tessellate_fill(&pb, FillRule::NonZero, 0.1)
        } else {
            self.rasterizer.tessellate_stroke(&pb, &StrokeOptions::new(1.0), 0.1)
        }
    }

    pub fn tessellate_star(&mut self, cx: f64, cy: f64, outer_r: f64, inner_r: f64, points: u32, rotation: f64, fill: bool) -> MeshData {
        if fill {
            self.rasterizer.tessellate_star_fill(cx, cy, outer_r, inner_r, points, rotation, FillRule::NonZero)
        } else {
            self.rasterizer.tessellate_star_stroke(cx, cy, outer_r, inner_r, points, rotation, &StrokeOptions::new(1.0))
        }
    }

    pub fn tessellate_arrow(&mut self, x1: f64, y1: f64, x2: f64, y2: f64, head_size: f64, double: bool, fill: bool) -> MeshData {
        if fill {
            self.rasterizer.tessellate_arrow_fill(x1, y1, x2, y2, head_size, double, FillRule::NonZero)
        } else {
            self.rasterizer.tessellate_arrow_stroke(x1, y1, x2, y2, head_size, double, &StrokeOptions::new(1.0))
        }
    }

    pub fn tessellate_line(&mut self, x1: f64, y1: f64, x2: f64, y2: f64, width: f64) -> MeshData {
        self.rasterizer.tessellate_line(x1, y1, x2, y2, &StrokeOptions::new(width))
    }

    pub fn to_json(&self) -> String {
        self.context.to_json()
    }

    pub fn from_json(json: &str, _width: f64, _height: f64) -> Option<CanvasFacade> {
        RenderContext::from_json(json).map(|ctx| CanvasFacade {
            context: ctx,
            rasterizer: Rasterizer::new(),
            tool_registry: ToolRegistry::new(),
        })
    }
}

// ==========================================
// SyncFacade 协同统一入口
// ==========================================

#[wasm_bindgen]
pub struct SyncFacade {
    doc: WasmYrsBoard,
}

#[wasm_bindgen]
impl SyncFacade {
    #[wasm_bindgen(constructor)]
    pub fn new(document_id: &str, user_id: &str, username: &str) -> Self {
        Self {
            doc: WasmYrsBoard::new(
                document_id.to_string(),
                user_id.to_string(),
                username.to_string(),
            ),
        }
    }

    pub fn add_shape(&mut self, shape_type: &str, x: f64, y: f64, w: f64, h: f64, props_json: Option<String>) -> String {
        self.doc.add_shape(shape_type.to_string(), x, y, w, h, props_json)
            .unwrap_or_default()
    }

    pub fn add_stroke(&mut self, points_json: &str, style_json: Option<String>) -> String {
        self.doc.add_stroke(points_json.to_string(), style_json)
            .unwrap_or_default()
    }

    pub fn add_text(&mut self, x: f64, y: f64, content: &str, props_json: Option<String>) -> String {
        self.doc.add_text(content.to_string(), x, y, props_json)
            .unwrap_or_default()
    }

    pub fn update_element(&mut self, id: &str, props_json: Option<String>) -> bool {
        self.doc.update_block(id.to_string(), None, props_json).is_some()
    }

    pub fn delete_element(&mut self, id: &str) -> bool {
        self.doc.delete_element(id.to_string()).is_ok()
    }

    pub fn move_element(&mut self, id: &str, x: f64, y: f64) -> bool {
        self.doc.update_shape(id.to_string(), Some(x), Some(y), None, None, None).is_ok()
    }

    pub fn encode_update(&mut self) -> Vec<u8> {
        self.doc.encode_update_v1()
    }

    pub fn apply_update(&mut self, data: &[u8]) -> bool {
        self.doc.apply_update(data)
    }

    pub fn encode_state_vector(&self) -> Vec<u8> {
        self.doc.encode_state_vector_v1()
    }

    pub fn encode_diff(&self, sv: &[u8]) -> Vec<u8> {
        self.doc.encode_diff_v1(sv)
    }

    pub fn can_undo(&self) -> bool {
        self.doc.can_undo()
    }

    pub fn can_redo(&self) -> bool {
        self.doc.can_redo()
    }

    pub fn undo(&mut self) -> Option<String> {
        self.doc.undo().map(|op| op.json())
    }

    pub fn redo(&mut self) -> Option<String> {
        self.doc.redo().map(|op| op.json())
    }

    pub fn get_blocks_json(&self) -> String {
        self.doc.to_json()
    }

    pub fn get_block_json(&self, id: &str) -> Option<String> {
        self.doc.get_block(id.to_string())
            .and_then(|b| serde_json::to_string(&b).ok())
    }

    pub fn to_json(&self) -> String {
        self.doc.to_json()
    }

    pub fn from_json(json: &str, doc_id: &str, user_id: &str, username: &str) -> Option<SyncFacade> {
        WasmYrsBoard::from_json(
            json.to_string(),
            doc_id.to_string(),
            user_id.to_string(),
            username.to_string(),
        ).ok().map(|doc| SyncFacade { doc })
    }

    pub fn site_id(&self) -> String {
        self.doc.site_id()
    }

    pub fn document_id(&self) -> String {
        self.doc.document_id()
    }
}

// ==========================================
// ExportFacade 导出统一入口
// ==========================================

#[wasm_bindgen]
pub struct ExportFacade {
    svg_export: SVGExport,
    pdf_export: PDFExport,
}

#[wasm_bindgen]
impl ExportFacade {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        Self {
            svg_export: SVGExport::new(),
            pdf_export: PDFExport::new(),
        }
    }

    pub fn export_svg(&self, layers_json: &str, viewport_json: &str) -> String {
        let layers: LayerTree = match serde_json::from_str(layers_json) {
            Ok(l) => l,
            Err(_) => return String::new(),
        };
        let viewport: Viewport = match serde_json::from_str(viewport_json) {
            Ok(v) => v,
            Err(_) => return String::new(),
        };
        self.svg_export.to_svg_string(&layers, &viewport)
    }

    pub fn add_artboard(&mut self, name: &str, x: f64, y: f64, w: f64, h: f64) -> String {
        let artboard = Artboard::new(name, x, y, w, h);
        let id = artboard.id();
        self.pdf_export.add_artboard(&artboard);
        id
    }

    pub fn remove_artboard(&mut self, id: &str) -> bool {
        self.pdf_export.remove_artboard(id)
    }

    pub fn set_pdf_meta(&mut self, title: &str, author: &str, subject: &str) {
        self.pdf_export.set_title(title);
        self.pdf_export.set_author(author);
        self.pdf_export.set_subject(subject);
    }

    pub fn get_artboard_ids(&self) -> Vec<JsValue> {
        self.pdf_export.get_artboard_ids()
    }

    pub fn generate_page_descriptions(&self, layers_json: &str) -> Vec<JsValue> {
        let layers: LayerTree = match serde_json::from_str(layers_json) {
            Ok(l) => l,
            Err(_) => return Vec::new(),
        };
        self.pdf_export.generate_page_descriptions(&layers)
    }

    pub fn compute_content_bounds(&self, layers_json: &str) -> String {
        let layers: LayerTree = match serde_json::from_str(layers_json) {
            Ok(l) => l,
            Err(_) => return String::new(),
        };
        let mut all_bounds: Vec<Rect> = Vec::new();
        for id_js in layers.get_all_layers_sorted() {
            if let Some(id_str) = id_js.as_string() {
                if let (Some(bounds), Some(transform)) = (
                    layers.get_layer_bounds(&id_str),
                    layers.get_layer_transform(&id_str),
                ) {
                    let world_bounds_poly = transform.transform_rect(&bounds);
                    all_bounds.push(world_bounds_poly.bounding_box());
                }
            }
        }
        match geometry::merge_rects(&all_bounds) {
            Some(r) => serde_json::json!({"x": r.x, "y": r.y, "width": r.width, "height": r.height}).to_string(),
            None => String::new(),
        }
    }
}

impl Default for ExportFacade {
    fn default() -> Self {
        Self::new()
    }
}

// ==========================================
// 工具函数
// ==========================================

#[wasm_bindgen]
pub fn lerp(a: f64, b: f64, t: f64) -> f64 {
    a + (b - a) * t.clamp(0.0, 1.0)
}

#[wasm_bindgen]
pub fn clamp(value: f64, min: f64, max: f64) -> f64 {
    value.clamp(min, max)
}

#[wasm_bindgen]
pub fn smoothstep(edge0: f64, edge1: f64, x: f64) -> f64 {
    let t = ((x - edge0) / (edge1 - edge0)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

#[wasm_bindgen(start)]
pub fn init() {
    console_error_panic_hook::set_once();
}
