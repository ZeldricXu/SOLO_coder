use serde::{Deserialize, Serialize};
use std::cell::{Cell, RefCell};
use std::collections::VecDeque;
use uuid::Uuid;
use wasm_bindgen::prelude::*;

pub use rustc_hash::{FxHashMap, FxHashSet};

// ─────────────────────────────────────────────────────────────
// 1. Resource ID & Reference Counting
// ─────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct ResourceId(pub Uuid);

impl ResourceId {
    pub fn new() -> Self {
        Self(Uuid::new_v4())
    }

    pub fn from_uuid(uuid: Uuid) -> Self {
        Self(uuid)
    }

    pub fn to_uuid(&self) -> Uuid {
        self.0
    }

    pub fn from_string(s: &str) -> Option<Self> {
        Uuid::parse_str(s).ok().map(Self)
    }

    pub fn to_string(&self) -> String {
        self.0.to_string()
    }
}

impl Default for ResourceId {
    fn default() -> Self {
        Self::new()
    }
}

impl std::fmt::Display for ResourceId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RefCounter {
    count: Cell<u32>,
}

impl RefCounter {
    pub fn new() -> Self {
        Self {
            count: Cell::new(1),
        }
    }

    pub fn increment(&self) {
        self.count.set(self.count.get() + 1);
    }

    pub fn decrement(&self) -> u32 {
        let current = self.count.get();
        if current > 0 {
            self.count.set(current - 1);
            current - 1
        } else {
            0
        }
    }

    pub fn count(&self) -> u32 {
        self.count.get()
    }

    pub fn is_zero(&self) -> bool {
        self.count.get() == 0
    }
}

impl Default for RefCounter {
    fn default() -> Self {
        Self::new()
    }
}

// ─────────────────────────────────────────────────────────────
// 2. Resource Types & Metadata
// ─────────────────────────────────────────────────────────────

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ResourceFormat {
    Png,
    Jpeg,
    Svg,
    Gif,
    Webp,
    Bmp,
    Pdf,
    Unknown,
}

impl ResourceFormat {
    pub fn from_mime(mime: &str) -> Self {
        match mime.to_lowercase().as_str() {
            "image/png" => ResourceFormat::Png,
            "image/jpeg" | "image/jpg" => ResourceFormat::Jpeg,
            "image/svg+xml" | "image/svg" => ResourceFormat::Svg,
            "image/gif" => ResourceFormat::Gif,
            "image/webp" => ResourceFormat::Webp,
            "image/bmp" => ResourceFormat::Bmp,
            "application/pdf" => ResourceFormat::Pdf,
            _ => ResourceFormat::Unknown,
        }
    }

    pub fn from_extension(ext: &str) -> Self {
        match ext.to_lowercase().as_str() {
            "png" => ResourceFormat::Png,
            "jpg" | "jpeg" => ResourceFormat::Jpeg,
            "svg" => ResourceFormat::Svg,
            "gif" => ResourceFormat::Gif,
            "webp" => ResourceFormat::Webp,
            "bmp" => ResourceFormat::Bmp,
            "pdf" => ResourceFormat::Pdf,
            _ => ResourceFormat::Unknown,
        }
    }

    pub fn to_mime(&self) -> &'static str {
        match self {
            ResourceFormat::Png => "image/png",
            ResourceFormat::Jpeg => "image/jpeg",
            ResourceFormat::Svg => "image/svg+xml",
            ResourceFormat::Gif => "image/gif",
            ResourceFormat::Webp => "image/webp",
            ResourceFormat::Bmp => "image/bmp",
            ResourceFormat::Pdf => "application/pdf",
            ResourceFormat::Unknown => "application/octet-stream",
        }
    }

    pub fn to_extension(&self) -> &'static str {
        match self {
            ResourceFormat::Png => "png",
            ResourceFormat::Jpeg => "jpg",
            ResourceFormat::Svg => "svg",
            ResourceFormat::Gif => "gif",
            ResourceFormat::Webp => "webp",
            ResourceFormat::Bmp => "bmp",
            ResourceFormat::Pdf => "pdf",
            ResourceFormat::Unknown => "bin",
        }
    }

    pub fn is_raster(&self) -> bool {
        matches!(
            self,
            ResourceFormat::Png
                | ResourceFormat::Jpeg
                | ResourceFormat::Gif
                | ResourceFormat::Webp
                | ResourceFormat::Bmp
        )
    }

    pub fn is_vector(&self) -> bool {
        matches!(self, ResourceFormat::Svg)
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum LoadState {
    Pending,
    Loading,
    Loaded,
    Failed,
    Cached,
    Unloaded,
}

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct ImageDimensions {
    pub width: u32,
    pub height: u32,
}

impl ImageDimensions {
    pub fn new(width: u32, height: u32) -> Self {
        Self { width, height }
    }

    pub fn aspect_ratio(&self) -> f64 {
        if self.height == 0 {
            return 0.0;
        }
        self.width as f64 / self.height as f64
    }

    pub fn is_empty(&self) -> bool {
        self.width == 0 || self.height == 0
    }

    pub fn area(&self) -> u64 {
        self.width as u64 * self.height as u64
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceMetadata {
    pub id: ResourceId,
    pub name: String,
    pub format: ResourceFormat,
    pub dimensions: Option<ImageDimensions>,
    pub size_bytes: u64,
    pub created_at: u64,
    pub modified_at: u64,
    pub load_state: LoadState,
    pub tags: Vec<String>,
    pub checksum: Option<String>,
}

impl ResourceMetadata {
    pub fn new(name: String, format: ResourceFormat) -> Self {
        let now = js_sys::Date::now() as u64;
        Self {
            id: ResourceId::new(),
            name,
            format,
            dimensions: None,
            size_bytes: 0,
            created_at: now,
            modified_at: now,
            load_state: LoadState::Pending,
            tags: Vec::new(),
            checksum: None,
        }
    }

    pub fn estimate_memory_bytes(&self) -> u64 {
        if let Some(dims) = self.dimensions {
            match self.format {
                ResourceFormat::Svg => self.size_bytes.max(1024),
                ResourceFormat::Png | ResourceFormat::Jpeg | ResourceFormat::Webp | ResourceFormat::Gif | ResourceFormat::Bmp => {
                    dims.area() * 4
                }
                _ => self.size_bytes,
            }
        } else {
            self.size_bytes
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 3. Resource Data
// ─────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ResourceData {
    RasterImage(RasterImageData),
    VectorImage(VectorImageData),
    Binary(Vec<u8>),
    Placeholder,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RasterImageData {
    pub pixels: Vec<u8>,
    pub dimensions: ImageDimensions,
    pub format: ResourceFormat,
}

impl RasterImageData {
    pub fn new(pixels: Vec<u8>, dimensions: ImageDimensions, format: ResourceFormat) -> Self {
        Self {
            pixels,
            dimensions,
            format,
        }
    }

    pub fn memory_usage(&self) -> u64 {
        self.pixels.len() as u64
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VectorImageData {
    pub svg_content: String,
    pub viewport: Option<ImageDimensions>,
}

impl VectorImageData {
    pub fn new(svg_content: String) -> Self {
        Self {
            svg_content,
            viewport: None,
        }
    }

    pub fn memory_usage(&self) -> u64 {
        self.svg_content.len() as u64
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Resource {
    pub metadata: ResourceMetadata,
    pub data: ResourceData,
    pub ref_count: RefCounter,
    pub last_accessed: Cell<u64>,
}

impl Resource {
    pub fn new(metadata: ResourceMetadata, data: ResourceData) -> Self {
        Self {
            metadata,
            data,
            ref_count: RefCounter::new(),
            last_accessed: Cell::new(js_sys::Date::now() as u64),
        }
    }

    pub fn id(&self) -> ResourceId {
        self.metadata.id
    }

    pub fn touch(&self) {
        self.last_accessed.set(js_sys::Date::now() as u64);
    }

    pub fn memory_usage(&self) -> u64 {
        let base = std::mem::size_of::<Self>() as u64;
        let data_size = match &self.data {
            ResourceData::RasterImage(r) => r.memory_usage(),
            ResourceData::VectorImage(v) => v.memory_usage(),
            ResourceData::Binary(b) => b.len() as u64,
            ResourceData::Placeholder => 0,
        };
        base + data_size + self.metadata.name.len() as u64
    }

    pub fn can_evict(&self) -> bool {
        self.ref_count.is_zero()
            && matches!(
                self.metadata.load_state,
                LoadState::Loaded | LoadState::Cached
            )
    }
}

// ─────────────────────────────────────────────────────────────
// 4. LRU Cache
// ─────────────────────────────────────────────────────────────

pub struct LruCache {
    capacity: usize,
    max_memory_bytes: u64,
    map: FxHashMap<ResourceId, Resource>,
    order: VecDeque<ResourceId>,
    current_memory: u64,
    hits: u64,
    misses: u64,
    evictions: u64,
}

impl LruCache {
    pub fn new(capacity: usize) -> Self {
        Self {
            capacity,
            max_memory_bytes: u64::MAX,
            map: FxHashMap::default(),
            order: VecDeque::new(),
            current_memory: 0,
            hits: 0,
            misses: 0,
            evictions: 0,
        }
    }

    pub fn with_memory_limit(capacity: usize, max_memory_bytes: u64) -> Self {
        Self {
            capacity,
            max_memory_bytes,
            map: FxHashMap::default(),
            order: VecDeque::new(),
            current_memory: 0,
            hits: 0,
            misses: 0,
            evictions: 0,
        }
    }

    pub fn get(&mut self, id: &ResourceId) -> Option<&Resource> {
        if self.map.contains_key(id) {
            self.hits += 1;
            self.promote(id);
            let resource = self.map.get(id).unwrap();
            resource.touch();
            Some(resource)
        } else {
            self.misses += 1;
            None
        }
    }

    pub fn insert(&mut self, resource: Resource) -> Option<Resource> {
        let id = resource.id();
        let mem_usage = resource.memory_usage();
        let mut evicted = None;

        if self.map.contains_key(&id) {
            if let Some(existing) = self.map.remove(&id) {
                self.current_memory = self.current_memory.saturating_sub(existing.memory_usage());
                self.remove_from_order(&id);
            }
        }

        self.evict_if_needed(mem_usage);

        if self.map.len() >= self.capacity {
            evicted = self.evict_lru();
        }

        self.current_memory += mem_usage;
        self.order.push_back(id);
        self.map.insert(id, resource);
        evicted
    }

    pub fn remove(&mut self, id: &ResourceId) -> Option<Resource> {
        if let Some(resource) = self.map.remove(id) {
            self.current_memory = self.current_memory.saturating_sub(resource.memory_usage());
            self.remove_from_order(id);
            Some(resource)
        } else {
            None
        }
    }

    pub fn contains(&self, id: &ResourceId) -> bool {
        self.map.contains_key(id)
    }

    pub fn len(&self) -> usize {
        self.map.len()
    }

    pub fn is_empty(&self) -> bool {
        self.map.is_empty()
    }

    pub fn capacity(&self) -> usize {
        self.capacity
    }

    pub fn set_capacity(&mut self, capacity: usize) {
        self.capacity = capacity;
        while self.map.len() > self.capacity {
            self.evict_lru();
        }
    }

    pub fn memory_usage(&self) -> u64 {
        self.current_memory
    }

    pub fn max_memory(&self) -> u64 {
        self.max_memory_bytes
    }

    pub fn set_max_memory(&mut self, max_bytes: u64) {
        self.max_memory_bytes = max_bytes;
        self.evict_if_needed(0);
    }

    pub fn hit_rate(&self) -> f64 {
        let total = self.hits + self.misses;
        if total == 0 {
            0.0
        } else {
            self.hits as f64 / total as f64
        }
    }

    pub fn hits(&self) -> u64 {
        self.hits
    }

    pub fn misses(&self) -> u64 {
        self.misses
    }

    pub fn evictions(&self) -> u64 {
        self.evictions
    }

    pub fn clear(&mut self) {
        self.map.clear();
        self.order.clear();
        self.current_memory = 0;
    }

    pub fn iter(&self) -> impl Iterator<Item = (&ResourceId, &Resource)> {
        self.map.iter()
    }

    fn promote(&mut self, id: &ResourceId) {
        self.remove_from_order(id);
        self.order.push_back(*id);
    }

    fn remove_from_order(&mut self, id: &ResourceId) {
        if let Some(pos) = self.order.iter().position(|x| x == id) {
            self.order.remove(pos);
        }
    }

    fn evict_lru(&mut self) -> Option<Resource> {
        let id = self.order.pop_front()?;
        let resource = self.map.remove(&id)?;
        self.current_memory = self.current_memory.saturating_sub(resource.memory_usage());
        self.evictions += 1;
        Some(resource)
    }

    fn evict_if_needed(&mut self, incoming_bytes: u64) {
        while !self.map.is_empty()
            && (self.current_memory + incoming_bytes > self.max_memory_bytes
                || self.map.len() >= self.capacity)
        {
            if self.evict_lru().is_none() {
                break;
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 5. Lazy Loading Scheduler
// ─────────────────────────────────────────────────────────────

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum LoadPriority {
    Critical = 0,
    High = 1,
    Normal = 2,
    Low = 3,
    Background = 4,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoadTask {
    pub resource_id: ResourceId,
    pub priority: LoadPriority,
    pub viewport_visible: bool,
    pub retries: u32,
    pub created_at: u64,
    pub deadline: Option<u64>,
}

impl LoadTask {
    pub fn new(resource_id: ResourceId, priority: LoadPriority) -> Self {
        Self {
            resource_id,
            priority,
            viewport_visible: false,
            retries: 0,
            created_at: js_sys::Date::now() as u64,
            deadline: None,
        }
    }

    pub fn with_deadline(resource_id: ResourceId, priority: LoadPriority, deadline_ms: u64) -> Self {
        let now = js_sys::Date::now() as u64;
        Self {
            resource_id,
            priority,
            viewport_visible: false,
            retries: 0,
            created_at: now,
            deadline: Some(now + deadline_ms),
        }
    }

    pub fn is_expired(&self) -> bool {
        if let Some(deadline) = self.deadline {
            js_sys::Date::now() as u64 > deadline
        } else {
            false
        }
    }

    pub fn effective_priority(&self) -> u8 {
        let base = self.priority as u8;
        if self.viewport_visible {
            base.saturating_sub(1)
        } else {
            base
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct Rect {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
}

impl Rect {
    pub fn new(x: f64, y: f64, width: f64, height: f64) -> Self {
        Self {
            x,
            y,
            width,
            height,
        }
    }

    pub fn intersects(&self, other: &Rect) -> bool {
        self.x < other.x + other.width
            && self.x + self.width > other.x
            && self.y < other.y + other.height
            && self.y + self.height > other.y
    }

    pub fn is_empty(&self) -> bool {
        self.width <= 0.0 || self.height <= 0.0
    }
}

pub struct LazyLoadScheduler {
    queue: RefCell<Vec<LoadTask>>,
    viewport: Cell<Rect>,
    max_concurrent: usize,
    loading: RefCell<FxHashSet<ResourceId>>,
    completed: RefCell<FxHashSet<ResourceId>>,
    failed: RefCell<FxHashMap<ResourceId, String>>,
}

impl LazyLoadScheduler {
    pub fn new(max_concurrent: usize) -> Self {
        Self {
            queue: RefCell::new(Vec::new()),
            viewport: Cell::new(Rect::new(0.0, 0.0, 0.0, 0.0)),
            max_concurrent,
            loading: RefCell::new(FxHashSet::default()),
            completed: RefCell::new(FxHashSet::default()),
            failed: RefCell::new(FxHashMap::default()),
        }
    }

    pub fn set_viewport(&self, viewport: Rect) {
        self.viewport.set(viewport);
        self.update_visibility();
    }

    pub fn viewport(&self) -> Rect {
        self.viewport.get()
    }

    pub fn schedule(&self, task: LoadTask) {
        let id = task.resource_id;
        if self.completed.borrow().contains(&id) || self.loading.borrow().contains(&id) {
            return;
        }
        let mut queue = self.queue.borrow_mut();
        if !queue.iter().any(|t| t.resource_id == id) {
            queue.push(task);
            self.sort_queue(&mut queue);
        }
    }

    pub fn cancel(&self, id: &ResourceId) -> bool {
        let mut queue = self.queue.borrow_mut();
        let before = queue.len();
        queue.retain(|t| t.resource_id != *id);
        queue.len() < before
    }

    pub fn is_visible_in_viewport(&self, resource_rect: &Rect) -> bool {
        let vp = self.viewport.get();
        if vp.is_empty() {
            return true;
        }
        resource_rect.intersects(&vp)
    }

    pub fn poll_next(&self) -> Option<LoadTask> {
        if self.loading.borrow().len() >= self.max_concurrent {
            return None;
        }

        let mut queue = self.queue.borrow_mut();
        queue.retain(|t| !t.is_expired());

        if queue.is_empty() {
            return None;
        }

        let task = queue.remove(0);
        self.loading.borrow_mut().insert(task.resource_id);
        Some(task)
    }

    pub fn mark_completed(&self, id: &ResourceId) {
        self.loading.borrow_mut().remove(id);
        self.completed.borrow_mut().insert(*id);
        self.failed.borrow_mut().remove(id);
    }

    pub fn mark_failed(&self, id: &ResourceId, error: String) {
        self.loading.borrow_mut().remove(id);
        self.failed.borrow_mut().insert(*id, error);
    }

    pub fn retry(&self, id: &ResourceId) -> bool {
        let error = self.failed.borrow_mut().remove(id);
        if let Some(_) = error {
            let mut queue = self.queue.borrow_mut();
            if let Some(task) = queue.iter_mut().find(|t| t.resource_id == *id) {
                task.retries += 1;
                return true;
            }
        }
        false
    }

    pub fn pending_count(&self) -> usize {
        self.queue.borrow().len()
    }

    pub fn loading_count(&self) -> usize {
        self.loading.borrow().len()
    }

    pub fn completed_count(&self) -> usize {
        self.completed.borrow().len()
    }

    pub fn failed_count(&self) -> usize {
        self.failed.borrow().len()
    }

    pub fn is_loading(&self, id: &ResourceId) -> bool {
        self.loading.borrow().contains(id)
    }

    pub fn is_completed(&self, id: &ResourceId) -> bool {
        self.completed.borrow().contains(id)
    }

    pub fn is_failed(&self, id: &ResourceId) -> bool {
        self.failed.borrow().contains_key(id)
    }

    pub fn clear(&self) {
        self.queue.borrow_mut().clear();
        self.loading.borrow_mut().clear();
        self.completed.borrow_mut().clear();
        self.failed.borrow_mut().clear();
    }

    fn update_visibility(&self) {
        let vp = self.viewport.get();
        let mut queue = self.queue.borrow_mut();
        for task in queue.iter_mut() {
            task.viewport_visible = !vp.is_empty();
        }
        self.sort_queue(&mut queue);
    }

    fn sort_queue(&self, queue: &mut Vec<LoadTask>) {
        queue.sort_by(|a, b| {
            a.effective_priority()
                .cmp(&b.effective_priority())
                .then_with(|| a.created_at.cmp(&b.created_at))
        });
    }
}

// ─────────────────────────────────────────────────────────────
// 6. Offline Draft Storage (IndexedDB interface)
// ─────────────────────────────────────────────────────────────

#[wasm_bindgen]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum StorageOperation {
    Put,
    Get,
    Delete,
    Clear,
    Count,
    Query,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StorageRequest {
    pub operation: StorageOperation,
    pub store_name: String,
    pub key: Option<String>,
    pub value: Option<Vec<u8>>,
    pub index_name: Option<String>,
    pub query_range: Option<StorageQueryRange>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StorageQueryRange {
    pub lower: Option<String>,
    pub upper: Option<String>,
    pub lower_open: bool,
    pub upper_open: bool,
}

impl StorageQueryRange {
    pub fn new() -> Self {
        Self {
            lower: None,
            upper: None,
            lower_open: false,
            upper_open: false,
        }
    }

    pub fn only(key: String) -> Self {
        Self {
            lower: Some(key.clone()),
            upper: Some(key),
            lower_open: false,
            upper_open: false,
        }
    }

    pub fn lower_bound(key: String, open: bool) -> Self {
        Self {
            lower: Some(key),
            upper: None,
            lower_open: open,
            upper_open: false,
        }
    }

    pub fn upper_bound(key: String, open: bool) -> Self {
        Self {
            lower: None,
            upper: Some(key),
            lower_open: false,
            upper_open: open,
        }
    }

    pub fn between(lower: String, upper: String, lower_open: bool, upper_open: bool) -> Self {
        Self {
            lower: Some(lower),
            upper: Some(upper),
            lower_open,
            upper_open,
        }
    }
}

impl Default for StorageQueryRange {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StorageResponse {
    pub success: bool,
    pub key: Option<String>,
    pub value: Option<Vec<u8>>,
    pub count: Option<u32>,
    pub error: Option<String>,
}

impl StorageResponse {
    pub fn success() -> Self {
        Self {
            success: true,
            key: None,
            value: None,
            count: None,
            error: None,
        }
    }

    pub fn with_value(value: Vec<u8>) -> Self {
        Self {
            success: true,
            key: None,
            value: Some(value),
            count: None,
            error: None,
        }
    }

    pub fn with_key_value(key: String, value: Vec<u8>) -> Self {
        Self {
            success: true,
            key: Some(key),
            value: Some(value),
            count: None,
            error: None,
        }
    }

    pub fn with_count(count: u32) -> Self {
        Self {
            success: true,
            key: None,
            value: None,
            count: Some(count),
            error: None,
        }
    }

    pub fn error(message: String) -> Self {
        Self {
            success: false,
            key: None,
            value: None,
            count: None,
            error: Some(message),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DraftMetadata {
    pub draft_id: String,
    pub resource_id: ResourceId,
    pub title: String,
    pub created_at: u64,
    pub modified_at: u64,
    pub size_bytes: u64,
    pub synced: bool,
    pub version: u32,
}

impl DraftMetadata {
    pub fn new(resource_id: ResourceId, title: String) -> Self {
        let now = js_sys::Date::now() as u64;
        Self {
            draft_id: format!("draft-{}", Uuid::new_v4()),
            resource_id,
            title,
            created_at: now,
            modified_at: now,
            size_bytes: 0,
            synced: false,
            version: 1,
        }
    }
}

pub trait DraftStorage {
    fn save_draft(&self, metadata: &DraftMetadata, data: &[u8]) -> Result<(), String>;
    fn load_draft(&self, draft_id: &str) -> Result<(DraftMetadata, Vec<u8>), String>;
    fn delete_draft(&self, draft_id: &str) -> Result<(), String>;
    fn list_drafts(&self) -> Result<Vec<DraftMetadata>, String>;
    fn get_draft_count(&self) -> Result<u32, String>;
    fn clear_all_drafts(&self) -> Result<(), String>;
    fn mark_synced(&self, draft_id: &str) -> Result<(), String>;
}

// ─────────────────────────────────────────────────────────────
// 7. Export Formats
// ─────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PngExportOptions {
    pub scale: f64,
    pub transparent: bool,
    pub background_color: Option<[u8; 4]>,
    pub compression_level: u8,
    pub dimensions: Option<ImageDimensions>,
}

impl Default for PngExportOptions {
    fn default() -> Self {
        Self {
            scale: 1.0,
            transparent: true,
            background_color: None,
            compression_level: 6,
            dimensions: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SvgExportOptions {
    pub pretty_print: bool,
    pub embed_fonts: bool,
    pub embed_images: bool,
    pub viewbox: Option<Rect>,
    pub namespace_declarations: bool,
}

impl Default for SvgExportOptions {
    fn default() -> Self {
        Self {
            pretty_print: true,
            embed_fonts: false,
            embed_images: true,
            viewbox: None,
            namespace_declarations: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PdfPage {
    pub width: f64,
    pub height: f64,
    pub content: Vec<u8>,
    pub resources: Vec<ResourceId>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PdfExportOptions {
    pub title: String,
    pub author: String,
    pub subject: String,
    pub keywords: Vec<String>,
    pub creator: String,
    pub page_width: f64,
    pub page_height: f64,
    pub margin_left: f64,
    pub margin_right: f64,
    pub margin_top: f64,
    pub margin_bottom: f64,
    pub compress: bool,
    pub embed_fonts: bool,
    pub dpi: u32,
}

impl Default for PdfExportOptions {
    fn default() -> Self {
        Self {
            title: String::new(),
            author: String::new(),
            subject: String::new(),
            keywords: Vec::new(),
            creator: "ResourceManager".to_string(),
            page_width: 595.28,
            page_height: 841.89,
            margin_left: 36.0,
            margin_right: 36.0,
            margin_top: 36.0,
            margin_bottom: 36.0,
            compress: true,
            embed_fonts: true,
            dpi: 300,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PdfDocument {
    pub options: PdfExportOptions,
    pub pages: Vec<PdfPage>,
    pub creation_date: u64,
    pub modification_date: u64,
}

impl PdfDocument {
    pub fn new(options: PdfExportOptions) -> Self {
        let now = js_sys::Date::now() as u64;
        Self {
            options,
            pages: Vec::new(),
            creation_date: now,
            modification_date: now,
        }
    }

    pub fn add_page(&mut self, page: PdfPage) {
        self.pages.push(page);
        self.modification_date = js_sys::Date::now() as u64;
    }

    pub fn page_count(&self) -> usize {
        self.pages.len()
    }

    pub fn total_bytes(&self) -> u64 {
        self.pages.iter().map(|p| p.content.len() as u64).sum()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExportResult {
    pub format: ResourceFormat,
    pub data: Vec<u8>,
    pub size_bytes: u64,
    pub dimensions: Option<ImageDimensions>,
    pub success: bool,
    pub error: Option<String>,
}

impl ExportResult {
    pub fn success(format: ResourceFormat, data: Vec<u8>, dimensions: Option<ImageDimensions>) -> Self {
        let size = data.len() as u64;
        Self {
            format,
            data,
            size_bytes: size,
            dimensions,
            success: true,
            error: None,
        }
    }

    pub fn failure(format: ResourceFormat, error: String) -> Self {
        Self {
            format,
            data: Vec::new(),
            size_bytes: 0,
            dimensions: None,
            success: false,
            error: Some(error),
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 8. Memory Monitor & Auto Cleanup
// ─────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct MemoryStats {
    pub used_bytes: u64,
    pub peak_bytes: u64,
    pub limit_bytes: u64,
    pub cached_count: usize,
    pub resource_count: usize,
    pub last_cleanup: u64,
}

impl MemoryStats {
    pub fn new(limit_bytes: u64) -> Self {
        Self {
            used_bytes: 0,
            peak_bytes: 0,
            limit_bytes,
            cached_count: 0,
            resource_count: 0,
            last_cleanup: 0,
        }
    }

    pub fn usage_ratio(&self) -> f64 {
        if self.limit_bytes == 0 {
            0.0
        } else {
            self.used_bytes as f64 / self.limit_bytes as f64
        }
    }

    pub fn is_critical(&self) -> bool {
        self.usage_ratio() > 0.9
    }

    pub fn is_high(&self) -> bool {
        self.usage_ratio() > 0.75
    }
}

pub struct MemoryMonitor {
    stats: RefCell<MemoryStats>,
    auto_cleanup_threshold: f64,
    cleanup_cooldown_ms: u64,
}

impl MemoryMonitor {
    pub fn new(limit_bytes: u64) -> Self {
        Self {
            stats: RefCell::new(MemoryStats::new(limit_bytes)),
            auto_cleanup_threshold: 0.8,
            cleanup_cooldown_ms: 5000,
        }
    }

    pub fn with_threshold(limit_bytes: u64, threshold: f64) -> Self {
        Self {
            stats: RefCell::new(MemoryStats::new(limit_bytes)),
            auto_cleanup_threshold: threshold.clamp(0.1, 1.0),
            cleanup_cooldown_ms: 5000,
        }
    }

    pub fn record_allocation(&self, bytes: u64) {
        let mut stats = self.stats.borrow_mut();
        stats.used_bytes = stats.used_bytes.saturating_add(bytes);
        stats.peak_bytes = stats.peak_bytes.max(stats.used_bytes);
    }

    pub fn record_deallocation(&self, bytes: u64) {
        let mut stats = self.stats.borrow_mut();
        stats.used_bytes = stats.used_bytes.saturating_sub(bytes);
    }

    pub fn update_cached_count(&self, count: usize) {
        self.stats.borrow_mut().cached_count = count;
    }

    pub fn update_resource_count(&self, count: usize) {
        self.stats.borrow_mut().resource_count = count;
    }

    pub fn stats(&self) -> MemoryStats {
        self.stats.borrow().clone()
    }

    pub fn needs_cleanup(&self) -> bool {
        let stats = self.stats.borrow();
        let now = js_sys::Date::now() as u64;
        stats.usage_ratio() > self.auto_cleanup_threshold
            && now - stats.last_cleanup > self.cleanup_cooldown_ms
    }

    pub fn mark_cleanup_performed(&self) {
        self.stats.borrow_mut().last_cleanup = js_sys::Date::now() as u64;
    }

    pub fn set_limit(&self, limit_bytes: u64) {
        self.stats.borrow_mut().limit_bytes = limit_bytes;
    }

    pub fn set_auto_cleanup_threshold(&mut self, threshold: f64) {
        self.auto_cleanup_threshold = threshold.clamp(0.1, 1.0);
    }

    pub fn auto_cleanup_threshold(&self) -> f64 {
        self.auto_cleanup_threshold
    }

    pub fn estimate_cleanup_amount(&self) -> u64 {
        let stats = self.stats.borrow();
        let target = (stats.limit_bytes as f64 * 0.6) as u64;
        stats.used_bytes.saturating_sub(target)
    }
}

// ─────────────────────────────────────────────────────────────
// 9. Resource Manager (Main Facade)
// ─────────────────────────────────────────────────────────────

#[wasm_bindgen]
pub struct ResourceManager {
    cache: RefCell<LruCache>,
    scheduler: LazyLoadScheduler,
    memory_monitor: MemoryMonitor,
    resources: RefCell<FxHashMap<ResourceId, ResourceMetadata>>,
}

#[wasm_bindgen]
impl ResourceManager {
    #[wasm_bindgen(constructor)]
    pub fn new(cache_capacity: usize, memory_limit_bytes: f64) -> ResourceManager {
        ResourceManager {
            cache: RefCell::new(LruCache::with_memory_limit(
                cache_capacity,
                memory_limit_bytes as u64,
            )),
            scheduler: LazyLoadScheduler::new(4),
            memory_monitor: MemoryMonitor::new(memory_limit_bytes as u64),
            resources: RefCell::new(FxHashMap::default()),
        }
    }

    pub fn create_resource_id(&self) -> JsValue {
        let id = ResourceId::new();
        serde_wasm_bindgen::to_value(&id).unwrap_or(JsValue::NULL)
    }

    pub fn register_resource(&self, name: &str, format: &str) -> JsValue {
        let fmt = ResourceFormat::from_extension(format);
        let metadata = ResourceMetadata::new(name.to_string(), fmt);
        let id = metadata.id;
        self.resources.borrow_mut().insert(id, metadata);
        self.memory_monitor.update_resource_count(self.resources.borrow().len());
        serde_wasm_bindgen::to_value(&id).unwrap_or(JsValue::NULL)
    }

    pub fn unregister_resource(&self, id_js: &JsValue) -> bool {
        if let Ok(id) = serde_wasm_bindgen::from_value::<ResourceId>(id_js.clone()) {
            self.resources.borrow_mut().remove(&id);
            self.cache.borrow_mut().remove(&id);
            self.memory_monitor.update_resource_count(self.resources.borrow().len());
            true
        } else {
            false
        }
    }

    pub fn get_resource_metadata(&self, id_js: &JsValue) -> JsValue {
        if let Ok(id) = serde_wasm_bindgen::from_value::<ResourceId>(id_js.clone()) {
            if let Some(meta) = self.resources.borrow().get(&id) {
                return serde_wasm_bindgen::to_value(meta).unwrap_or(JsValue::NULL);
            }
        }
        JsValue::NULL
    }

    pub fn update_resource_dimensions(
        &self,
        id_js: &JsValue,
        width: u32,
        height: u32,
    ) -> bool {
        if let Ok(id) = serde_wasm_bindgen::from_value::<ResourceId>(id_js.clone()) {
            if let Some(meta) = self.resources.borrow_mut().get_mut(&id) {
                meta.dimensions = Some(ImageDimensions::new(width, height));
                meta.modified_at = js_sys::Date::now() as u64;
                return true;
            }
        }
        false
    }

    pub fn cache_resource(&self, id_js: &JsValue, data: Vec<u8>) -> bool {
        if let Ok(id) = serde_wasm_bindgen::from_value::<ResourceId>(id_js.clone()) {
            let meta = match self.resources.borrow().get(&id) {
                Some(m) => m.clone(),
                None => return false,
            };

            let resource_data = if meta.format.is_raster() {
                let dims = meta.dimensions.unwrap_or(ImageDimensions::new(0, 0));
                ResourceData::RasterImage(RasterImageData::new(data, dims, meta.format))
            } else if meta.format.is_vector() {
                let svg_str = String::from_utf8_lossy(&data).to_string();
                ResourceData::VectorImage(VectorImageData::new(svg_str))
            } else {
                ResourceData::Binary(data)
            };

            let resource = Resource::new(meta.clone(), resource_data);
            self.memory_monitor.record_allocation(resource.memory_usage());
            self.cache.borrow_mut().insert(resource);
            self.memory_monitor
                .update_cached_count(self.cache.borrow().len());

            if let Some(m) = self.resources.borrow_mut().get_mut(&id) {
                m.load_state = LoadState::Cached;
            }

            self.auto_cleanup_if_needed();
            return true;
        }
        false
    }

    pub fn get_cached_data(&self, id_js: &JsValue) -> Option<Vec<u8>> {
        if let Ok(id) = serde_wasm_bindgen::from_value::<ResourceId>(id_js.clone()) {
            let mut cache = self.cache.borrow_mut();
            if let Some(resource) = cache.get(&id) {
                let data = match &resource.data {
                    ResourceData::RasterImage(r) => r.pixels.clone(),
                    ResourceData::VectorImage(v) => v.svg_content.as_bytes().to_vec(),
                    ResourceData::Binary(b) => b.clone(),
                    ResourceData::Placeholder => Vec::new(),
                };
                return Some(data);
            }
        }
        None
    }

    pub fn is_cached(&self, id_js: &JsValue) -> bool {
        if let Ok(id) = serde_wasm_bindgen::from_value::<ResourceId>(id_js.clone()) {
            return self.cache.borrow().contains(&id);
        }
        false
    }

    pub fn evict_from_cache(&self, id_js: &JsValue) -> bool {
        if let Ok(id) = serde_wasm_bindgen::from_value::<ResourceId>(id_js.clone()) {
            let mut cache = self.cache.borrow_mut();
            if let Some(resource) = cache.remove(&id) {
                self.memory_monitor
                    .record_deallocation(resource.memory_usage());
                self.memory_monitor.update_cached_count(cache.len());
                return true;
            }
        }
        false
    }

    pub fn clear_cache(&self) {
        let mem = self.cache.borrow().memory_usage();
        self.cache.borrow_mut().clear();
        self.memory_monitor.record_deallocation(mem);
        self.memory_monitor.update_cached_count(0);
    }

    pub fn cache_len(&self) -> usize {
        self.cache.borrow().len()
    }

    pub fn cache_capacity(&self) -> usize {
        self.cache.borrow().capacity()
    }

    pub fn set_cache_capacity(&self, capacity: usize) {
        self.cache.borrow_mut().set_capacity(capacity);
    }

    pub fn memory_used(&self) -> f64 {
        self.memory_monitor.stats().used_bytes as f64
    }

    pub fn memory_peak(&self) -> f64 {
        self.memory_monitor.stats().peak_bytes as f64
    }

    pub fn memory_limit(&self) -> f64 {
        self.memory_monitor.stats().limit_bytes as f64
    }

    pub fn set_memory_limit(&self, limit: f64) {
        self.memory_monitor.set_limit(limit as u64);
        self.cache.borrow_mut().set_max_memory(limit as u64);
    }

    pub fn cache_hit_rate(&self) -> f64 {
        self.cache.borrow().hit_rate()
    }

    pub fn schedule_load(
        &self,
        id_js: &JsValue,
        priority: u8,
        deadline_ms: f64,
    ) -> bool {
        if let Ok(id) = serde_wasm_bindgen::from_value::<ResourceId>(id_js.clone()) {
            let prio = match priority {
                0 => LoadPriority::Critical,
                1 => LoadPriority::High,
                2 => LoadPriority::Normal,
                3 => LoadPriority::Low,
                _ => LoadPriority::Background,
            };
            let task = if deadline_ms > 0.0 {
                LoadTask::with_deadline(id, prio, deadline_ms as u64)
            } else {
                LoadTask::new(id, prio)
            };
            self.scheduler.schedule(task);
            return true;
        }
        false
    }

    pub fn cancel_load(&self, id_js: &JsValue) -> bool {
        if let Ok(id) = serde_wasm_bindgen::from_value::<ResourceId>(id_js.clone()) {
            return self.scheduler.cancel(&id);
        }
        false
    }

    pub fn pending_load_count(&self) -> usize {
        self.scheduler.pending_count()
    }

    pub fn loading_count(&self) -> usize {
        self.scheduler.loading_count()
    }

    pub fn set_viewport(&self, x: f64, y: f64, w: f64, h: f64) {
        self.scheduler.set_viewport(Rect::new(x, y, w, h));
    }

    pub fn is_visible_in_viewport(&self, x: f64, y: f64, w: f64, h: f64) -> bool {
        let rect = Rect::new(x, y, w, h);
        self.scheduler.is_visible_in_viewport(&rect)
    }

    pub fn perform_cleanup(&self) -> usize {
        let target_bytes = self.memory_monitor.estimate_cleanup_amount();
        let mut freed: u64 = 0;
        let mut evicted = 0;
        let mut cache = self.cache.borrow_mut();

        while freed < target_bytes && !cache.is_empty() {
            if let Some(resource) = cache.evict_lru() {
                freed += resource.memory_usage();
                evicted += 1;
            } else {
                break;
            }
        }

        self.memory_monitor.record_deallocation(freed);
        self.memory_monitor.update_cached_count(cache.len());
        self.memory_monitor.mark_cleanup_performed();
        evicted
    }

    pub fn get_memory_stats(&self) -> JsValue {
        let stats = self.memory_monitor.stats();
        serde_wasm_bindgen::to_value(&stats).unwrap_or(JsValue::NULL)
    }

    pub fn increment_ref(&self, id_js: &JsValue) -> bool {
        if let Ok(id) = serde_wasm_bindgen::from_value::<ResourceId>(id_js.clone()) {
            let mut cache = self.cache.borrow_mut();
            if let Some(resource) = cache.get(&id) {
                resource.ref_count.increment();
                return true;
            }
        }
        false
    }

    pub fn decrement_ref(&self, id_js: &JsValue) -> u32 {
        if let Ok(id) = serde_wasm_bindgen::from_value::<ResourceId>(id_js.clone()) {
            let mut cache = self.cache.borrow_mut();
            if let Some(resource) = cache.get(&id) {
                return resource.ref_count.decrement();
            }
        }
        0
    }

    pub fn get_ref_count(&self, id_js: &JsValue) -> u32 {
        if let Ok(id) = serde_wasm_bindgen::from_value::<ResourceId>(id_js.clone()) {
            let cache = self.cache.borrow();
            if let Some(resource) = cache.map.get(&id) {
                return resource.ref_count.count();
            }
        }
        0
    }

    pub fn create_png_export_options(
        &self,
        scale: f64,
        transparent: bool,
        compression: u8,
    ) -> JsValue {
        let opts = PngExportOptions {
            scale,
            transparent,
            compression_level: compression,
            ..Default::default()
        };
        serde_wasm_bindgen::to_value(&opts).unwrap_or(JsValue::NULL)
    }

    pub fn create_svg_export_options(
        &self,
        pretty_print: bool,
        embed_fonts: bool,
        embed_images: bool,
    ) -> JsValue {
        let opts = SvgExportOptions {
            pretty_print,
            embed_fonts,
            embed_images,
            ..Default::default()
        };
        serde_wasm_bindgen::to_value(&opts).unwrap_or(JsValue::NULL)
    }

    pub fn create_pdf_export_options(
        &self,
        title: &str,
        author: &str,
        page_width: f64,
        page_height: f64,
    ) -> JsValue {
        let opts = PdfExportOptions {
            title: title.to_string(),
            author: author.to_string(),
            page_width,
            page_height,
            ..Default::default()
        };
        serde_wasm_bindgen::to_value(&opts).unwrap_or(JsValue::NULL)
    }

    pub fn create_storage_request(
        &self,
        operation: u8,
        store_name: &str,
        key: Option<String>,
        value: Option<Vec<u8>>,
    ) -> JsValue {
        let op = match operation {
            0 => StorageOperation::Put,
            1 => StorageOperation::Get,
            2 => StorageOperation::Delete,
            3 => StorageOperation::Clear,
            4 => StorageOperation::Count,
            _ => StorageOperation::Query,
        };
        let req = StorageRequest {
            operation: op,
            store_name: store_name.to_string(),
            key,
            value,
            index_name: None,
            query_range: None,
        };
        serde_wasm_bindgen::to_value(&req).unwrap_or(JsValue::NULL)
    }
}

impl ResourceManager {
    fn auto_cleanup_if_needed(&self) {
        if self.memory_monitor.needs_cleanup() {
            self.perform_cleanup();
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 10. SVG Export - 图层化 SVG 导出
// ─────────────────────────────────────────────────────────────

#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExportLayerInfo {
    id: String,
    name: String,
    layer_type: String,
    visible: bool,
    opacity: f64,
    x: f64,
    y: f64,
    width: f64,
    height: f64,
    path_data: String,
    text_content: String,
    font_family: String,
    font_size: f64,
    fill_color: String,
    stroke_color: String,
    stroke_width: f64,
    transform_matrix: Vec<f64>,
}

#[wasm_bindgen]
impl ExportLayerInfo {
    #[wasm_bindgen(constructor)]
    pub fn new(id: &str, name: &str, layer_type: &str) -> Self {
        Self {
            id: id.to_string(),
            name: name.to_string(),
            layer_type: layer_type.to_string(),
            visible: true,
            opacity: 1.0,
            x: 0.0,
            y: 0.0,
            width: 0.0,
            height: 0.0,
            path_data: String::new(),
            text_content: String::new(),
            font_family: "sans-serif".to_string(),
            font_size: 14.0,
            fill_color: "#000000".to_string(),
            stroke_color: "#333333".to_string(),
            stroke_width: 1.0,
            transform_matrix: vec![1.0, 0.0, 0.0, 1.0, 0.0, 0.0],
        }
    }

    #[wasm_bindgen(getter)] pub fn id(&self) -> String { self.id.clone() }
    #[wasm_bindgen(setter)] pub fn set_id(&mut self, v: String) { self.id = v; }
    #[wasm_bindgen(getter)] pub fn name(&self) -> String { self.name.clone() }
    #[wasm_bindgen(setter)] pub fn set_name(&mut self, v: String) { self.name = v; }
    #[wasm_bindgen(getter)] pub fn layer_type(&self) -> String { self.layer_type.clone() }
    #[wasm_bindgen(setter)] pub fn set_layer_type(&mut self, v: String) { self.layer_type = v; }
    #[wasm_bindgen(getter)] pub fn visible(&self) -> bool { self.visible }
    #[wasm_bindgen(setter)] pub fn set_visible(&mut self, v: bool) { self.visible = v; }
    #[wasm_bindgen(getter)] pub fn opacity(&self) -> f64 { self.opacity }
    #[wasm_bindgen(setter)] pub fn set_opacity(&mut self, v: f64) { self.opacity = v; }
    #[wasm_bindgen(getter)] pub fn x(&self) -> f64 { self.x }
    #[wasm_bindgen(setter)] pub fn set_x(&mut self, v: f64) { self.x = v; }
    #[wasm_bindgen(getter)] pub fn y(&self) -> f64 { self.y }
    #[wasm_bindgen(setter)] pub fn set_y(&mut self, v: f64) { self.y = v; }
    #[wasm_bindgen(getter)] pub fn width(&self) -> f64 { self.width }
    #[wasm_bindgen(setter)] pub fn set_width(&mut self, v: f64) { self.width = v; }
    #[wasm_bindgen(getter)] pub fn height(&self) -> f64 { self.height }
    #[wasm_bindgen(setter)] pub fn set_height(&mut self, v: f64) { self.height = v; }
    #[wasm_bindgen(getter)] pub fn path_data(&self) -> String { self.path_data.clone() }
    #[wasm_bindgen(setter)] pub fn set_path_data(&mut self, v: String) { self.path_data = v; }
    #[wasm_bindgen(getter)] pub fn text_content(&self) -> String { self.text_content.clone() }
    #[wasm_bindgen(setter)] pub fn set_text_content(&mut self, v: String) { self.text_content = v; }
    #[wasm_bindgen(getter)] pub fn font_family(&self) -> String { self.font_family.clone() }
    #[wasm_bindgen(setter)] pub fn set_font_family(&mut self, v: String) { self.font_family = v; }
    #[wasm_bindgen(getter)] pub fn font_size(&self) -> f64 { self.font_size }
    #[wasm_bindgen(setter)] pub fn set_font_size(&mut self, v: f64) { self.font_size = v; }
    #[wasm_bindgen(getter)] pub fn fill_color(&self) -> String { self.fill_color.clone() }
    #[wasm_bindgen(setter)] pub fn set_fill_color(&mut self, v: String) { self.fill_color = v; }
    #[wasm_bindgen(getter)] pub fn stroke_color(&self) -> String { self.stroke_color.clone() }
    #[wasm_bindgen(setter)] pub fn set_stroke_color(&mut self, v: String) { self.stroke_color = v; }
    #[wasm_bindgen(getter)] pub fn stroke_width(&self) -> f64 { self.stroke_width }
    #[wasm_bindgen(setter)] pub fn set_stroke_width(&mut self, v: f64) { self.stroke_width = v; }
    #[wasm_bindgen(getter)] pub fn transform_matrix(&self) -> Vec<f64> { self.transform_matrix.clone() }
    #[wasm_bindgen(setter)] pub fn set_transform_matrix(&mut self, v: Vec<f64>) { self.transform_matrix = v; }
}

#[wasm_bindgen]
#[derive(Debug, Clone)]
pub struct SVGExporter;

#[wasm_bindgen]
impl SVGExporter {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self { Self }

    pub fn export_layers(&self, layers: Vec<JsValue>, viewbox_x: f64, viewbox_y: f64, viewbox_w: f64, viewbox_h: f64) -> String {
        let parsed_layers: Vec<ExportLayerInfo> = layers.iter()
            .filter_map(|v| serde_wasm_bindgen::from_value::<ExportLayerInfo>(v.clone()).ok())
            .collect();
        self.render_svg(&parsed_layers, viewbox_x, viewbox_y, viewbox_w, viewbox_h, true)
    }

    pub fn export_simple(&self, viewbox_x: f64, viewbox_y: f64, viewbox_w: f64, viewbox_h: f64) -> String {
        self.render_svg(&[], viewbox_x, viewbox_y, viewbox_w, viewbox_h, false)
    }

    fn render_svg(&self, layers: &[ExportLayerInfo], vb_x: f64, vb_y: f64, vb_w: f64, vb_h: f64, with_layers: bool) -> String {
        let mut svg = String::new();
        svg.push_str("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.push_str(&format!(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" \
             xmlns:xlink=\"http://www.w3.org/1999/xlink\" \
             viewBox=\"{} {} {} {}\" \
             width=\"{}\" height=\"{}\" version=\"1.1\">\n",
            vb_x, vb_y, vb_w, vb_h, vb_w, vb_h
        ));

        svg.push_str("  <metadata>\n");
        svg.push_str("    <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n");
        svg.push_str("      <cc:Work xmlns:cc=\"http://creativecommons.org/ns#\">\n");
        svg.push_str(&format!("        <dc:format xmlns:dc=\"http://purl.org/dc/elements/1.1/\">image/svg+xml</dc:format>\n"));
        svg.push_str("      </cc:Work>\n");
        svg.push_str("    </rdf:RDF>\n");
        svg.push_str("  </metadata>\n");

        svg.push_str("  <defs>\n");
        svg.push_str("    <style type=\"text/css\"><![CDATA[\n");
        svg.push_str("      .layer-group { display: inline; }\n");
        svg.push_str("      .shape-path { fill: none; stroke: #333; stroke-width: 2; }\n");
        svg.push_str("      .stroke-path { fill: none; stroke: #000; stroke-linecap: round; stroke-linejoin: round; }\n");
        svg.push_str("      .text-element { font-family: sans-serif; }\n");
        svg.push_str("      .image-placeholder { fill: #e0e0e0; stroke: #999; stroke-width: 1; }\n");
        svg.push_str("      .arrow-path { fill: none; stroke: #333; stroke-width: 2; }\n");
        svg.push_str("    ]]></style>\n");
        svg.push_str("  </defs>\n");

        if with_layers && !layers.is_empty() {
            svg.push_str(&format!("  <g id=\"canvas-root\" transform=\"translate(0,0)\">\n"));

            let mut current_group_depth: Vec<String> = Vec::new();

            for layer in layers {
                if !layer.visible {
                    continue;
                }

                while current_group_depth.last().map_or(false, |g| g != "root") {
                    svg.push_str("    </g>\n");
                    current_group_depth.pop();
                }

                let indent = "    ";
                let escaped_name = Self::escape_xml(&layer.name);
                let transform_str = Self::format_transform(&layer.transform_matrix);
                let opacity_str = if (layer.opacity - 1.0).abs() > 1e-6 {
                    format!(" opacity=\"{:.3}\"", layer.opacity)
                } else {
                    String::new()
                };

                match layer.layer_type.as_str() {
                    "group" => {
                        svg.push_str(&format!(
                            "{}<g id=\"layer-{}\" class=\"layer-group\" data-name=\"{}\"{}{}>\n",
                            indent, layer.id, escaped_name, transform_str, opacity_str
                        ));
                        current_group_depth.push(layer.id.clone());
                    }
                    "text" => {
                        svg.push_str(&format!(
                            "{}<g id=\"layer-{}\" class=\"layer text-layer\" data-name=\"{}\"{}{}>\n",
                            indent, layer.id, escaped_name, transform_str, opacity_str
                        ));
                        let text_content = if layer.text_content.is_empty() { "Text" } else { &layer.text_content };
                        let escaped_text = Self::escape_xml(text_content);
                        svg.push_str(&format!(
                            "{}  <text x=\"{}\" y=\"{}\" font-family=\"{}\" font-size=\"{}\" fill=\"{}\" class=\"text-element\">{}</text>\n",
                            indent, layer.x, layer.y + layer.font_size,
                            layer.font_family, layer.font_size, layer.fill_color, escaped_text
                        ));
                        svg.push_str(&format!("{}</g>\n", indent));
                    }
                    "richtext" => {
                        svg.push_str(&format!(
                            "{}<g id=\"layer-{}\" class=\"layer richtext-layer\" data-name=\"{}\"{}{}>\n",
                            indent, layer.id, escaped_name, transform_str, opacity_str
                        ));
                        let text_content = if layer.text_content.is_empty() { "<div>RichText</div>" } else { &layer.text_content };
                        svg.push_str(&format!(
                            "{}  <foreignObject x=\"{}\" y=\"{}\" width=\"{}\" height=\"{}\">\n",
                            indent, layer.x, layer.y, layer.width.max(100.0), layer.height.max(50.0)
                        ));
                        svg.push_str(&format!(
                            "{}    <div xmlns=\"http://www.w3.org/1999/xhtml\" style=\"font-family:{};font-size:{}px;color:{};\">\n",
                            indent, layer.font_family, layer.font_size, layer.fill_color
                        ));
                        svg.push_str(&format!("{}      {}\n", indent, text_content));
                        svg.push_str(&format!("{}    </div>\n", indent));
                        svg.push_str(&format!("{}  </foreignObject>\n", indent));
                        svg.push_str(&format!("{}</g>\n", indent));
                    }
                    "image" => {
                        svg.push_str(&format!(
                            "{}<g id=\"layer-{}\" class=\"layer image-layer\" data-name=\"{}\"{}{}>\n",
                            indent, layer.id, escaped_name, transform_str, opacity_str
                        ));
                        svg.push_str(&format!(
                            "{}  <rect x=\"{}\" y=\"{}\" width=\"{}\" height=\"{}\" class=\"image-placeholder\"/>\n",
                            indent, layer.x, layer.y, layer.width, layer.height
                        ));
                        svg.push_str(&format!(
                            "{}  <text x=\"{}\" y=\"{}\" font-size=\"12\" fill=\"#666\" text-anchor=\"middle\">Image</text>\n",
                            indent, layer.x + layer.width / 2.0, layer.y + layer.height / 2.0
                        ));
                        svg.push_str(&format!("{}</g>\n", indent));
                    }
                    _ => {
                        let class = match layer.layer_type.as_str() {
                            "shape" => "shape-path",
                            "stroke" => "stroke-path",
                            "arrow" => "arrow-path",
                            _ => "shape-path",
                        };
                        let d_attr = if layer.path_data.is_empty() {
                            format!("M {} {} L {} {} L {} {} L {} {} Z",
                                layer.x, layer.y,
                                layer.x + layer.width, layer.y,
                                layer.x + layer.width, layer.y + layer.height,
                                layer.x, layer.y + layer.height
                            )
                        } else {
                            layer.path_data.clone()
                        };
                        svg.push_str(&format!(
                            "{}<g id=\"layer-{}\" class=\"layer {}-layer\" data-name=\"{}\"{}{}>\n",
                            indent, layer.id, layer.layer_type, escaped_name, transform_str, opacity_str
                        ));
                        svg.push_str(&format!(
                            "{}  <path d=\"{}\" fill=\"{}\" stroke=\"{}\" stroke-width=\"{}\" class=\"{}\"/>\n",
                            indent, d_attr, layer.fill_color, layer.stroke_color, layer.stroke_width, class
                        ));
                        svg.push_str(&format!("{}</g>\n", indent));
                    }
                }
            }

            while !current_group_depth.is_empty() {
                svg.push_str("    </g>\n");
                current_group_depth.pop();
            }

            svg.push_str("  </g>\n");
        }

        svg.push_str("</svg>\n");
        svg
    }

    fn format_transform(m: &[f64]) -> String {
        if m.len() >= 6 {
            let identity = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0];
            let is_identity = m.iter().zip(identity.iter()).all(|(a, b)| (a - b).abs() < 1e-6);
            if !is_identity {
                return format!(
                    " transform=\"matrix({:.6} {:.6} {:.6} {:.6} {:.6} {:.6})\"",
                    m[0], m[1], m[2], m[3], m[4], m[5]
                );
            }
        }
        String::new()
    }

    fn escape_xml(s: &str) -> String {
        s.replace('&', "&amp;")
            .replace('<', "&lt;")
            .replace('>', "&gt;")
            .replace('"', "&quot;")
            .replace('\'', "&apos;")
    }
}

impl Default for SVGExporter {
    fn default() -> Self { Self::new() }
}

// ─────────────────────────────────────────────────────────────
// 11. PDF Export - printpdf 库多页画板导出
// ─────────────────────────────────────────────────────────────

#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ArtboardConfig {
    name: String,
    width_mm: f64,
    height_mm: f64,
    layer_ids: Vec<String>,
}

#[wasm_bindgen]
impl ArtboardConfig {
    #[wasm_bindgen(constructor)]
    pub fn new(name: &str, width_mm: f64, height_mm: f64) -> Self {
        Self {
            name: name.to_string(),
            width_mm,
            height_mm,
            layer_ids: Vec::new(),
        }
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
    pub fn width_mm(&self) -> f64 {
        self.width_mm
    }

    #[wasm_bindgen(setter)]
    pub fn set_width_mm(&mut self, width_mm: f64) {
        self.width_mm = width_mm;
    }

    #[wasm_bindgen(getter)]
    pub fn height_mm(&self) -> f64 {
        self.height_mm
    }

    #[wasm_bindgen(setter)]
    pub fn set_height_mm(&mut self, height_mm: f64) {
        self.height_mm = height_mm;
    }

    pub fn add_layer(&mut self, layer_id: &str) {
        self.layer_ids.push(layer_id.to_string());
    }

    pub fn get_layer_ids(&self) -> Vec<JsValue> {
        self.layer_ids.iter().map(|id| JsValue::from_str(id)).collect()
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PdfPathCommand {
    op: String,
    x: f64,
    y: f64,
    x1: f64,
    y1: f64,
    x2: f64,
    y2: f64,
}

#[wasm_bindgen]
impl PdfPathCommand {
    #[wasm_bindgen(constructor)]
    pub fn new(op: &str, x: f64, y: f64) -> Self {
        Self { op: op.to_string(), x, y, x1: 0.0, y1: 0.0, x2: 0.0, y2: 0.0 }
    }

    pub fn with_cp(op: &str, x: f64, y: f64, x1: f64, y1: f64, x2: f64, y2: f64) -> Self {
        Self { op: op.to_string(), x, y, x1, y1, x2, y2 }
    }

    #[wasm_bindgen(getter)]
    pub fn op(&self) -> String {
        self.op.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn x(&self) -> f64 { self.x }

    #[wasm_bindgen(getter)]
    pub fn y(&self) -> f64 { self.y }

    #[wasm_bindgen(getter)]
    pub fn x1(&self) -> f64 { self.x1 }

    #[wasm_bindgen(getter)]
    pub fn y1(&self) -> f64 { self.y1 }

    #[wasm_bindgen(getter)]
    pub fn x2(&self) -> f64 { self.x2 }

    #[wasm_bindgen(getter)]
    pub fn y2(&self) -> f64 { self.y2 }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PdfLayerData {
    id: String,
    name: String,
    layer_type: String,
    visible: bool,
    opacity: f64,
    paths: Vec<PdfPathCommand>,
    fill_rgba: [u8; 4],
    stroke_rgba: [u8; 4],
    stroke_width: f64,
    text: String,
    font_size: f64,
    x: f64,
    y: f64,
    width: f64,
    height: f64,
}

#[wasm_bindgen]
impl PdfLayerData {
    #[wasm_bindgen(constructor)]
    pub fn new(id: &str, name: &str, layer_type: &str) -> Self {
        Self {
            id: id.to_string(),
            name: name.to_string(),
            layer_type: layer_type.to_string(),
            visible: true,
            opacity: 1.0,
            paths: Vec::new(),
            fill_rgba: [0, 0, 0, 0],
            stroke_rgba: [0, 0, 0, 255],
            stroke_width: 1.0,
            text: String::new(),
            font_size: 12.0,
            x: 0.0,
            y: 0.0,
            width: 0.0,
            height: 0.0,
        }
    }

    #[wasm_bindgen(getter)]
    pub fn id(&self) -> String { self.id.clone() }

    #[wasm_bindgen(getter)]
    pub fn name(&self) -> String { self.name.clone() }

    #[wasm_bindgen(setter)]
    pub fn set_name(&mut self, name: &str) { self.name = name.to_string(); }

    #[wasm_bindgen(getter)]
    pub fn layer_type(&self) -> String { self.layer_type.clone() }

    #[wasm_bindgen(getter)]
    pub fn visible(&self) -> bool { self.visible }

    #[wasm_bindgen(setter)]
    pub fn set_visible(&mut self, v: bool) { self.visible = v; }

    #[wasm_bindgen(getter)]
    pub fn opacity(&self) -> f64 { self.opacity }

    #[wasm_bindgen(setter)]
    pub fn set_opacity(&mut self, v: f64) { self.opacity = v; }

    #[wasm_bindgen(getter)]
    pub fn fill_rgba(&self) -> Vec<u8> { self.fill_rgba.to_vec() }

    #[wasm_bindgen(setter)]
    pub fn set_fill_rgba(&mut self, v: Vec<u8>) {
        if v.len() >= 4 {
            self.fill_rgba = [v[0], v[1], v[2], v[3]];
        }
    }

    #[wasm_bindgen(getter)]
    pub fn stroke_rgba(&self) -> Vec<u8> { self.stroke_rgba.to_vec() }

    #[wasm_bindgen(setter)]
    pub fn set_stroke_rgba(&mut self, v: Vec<u8>) {
        if v.len() >= 4 {
            self.stroke_rgba = [v[0], v[1], v[2], v[3]];
        }
    }

    #[wasm_bindgen(getter)]
    pub fn stroke_width(&self) -> f64 { self.stroke_width }

    #[wasm_bindgen(setter)]
    pub fn set_stroke_width(&mut self, v: f64) { self.stroke_width = v; }

    #[wasm_bindgen(getter)]
    pub fn text(&self) -> String { self.text.clone() }

    #[wasm_bindgen(setter)]
    pub fn set_text(&mut self, t: &str) { self.text = t.to_string(); }

    #[wasm_bindgen(getter)]
    pub fn font_size(&self) -> f64 { self.font_size }

    #[wasm_bindgen(setter)]
    pub fn set_font_size(&mut self, v: f64) { self.font_size = v; }

    #[wasm_bindgen(getter)]
    pub fn x(&self) -> f64 { self.x }

    #[wasm_bindgen(setter)]
    pub fn set_x(&mut self, v: f64) { self.x = v; }

    #[wasm_bindgen(getter)]
    pub fn y(&self) -> f64 { self.y }

    #[wasm_bindgen(setter)]
    pub fn set_y(&mut self, v: f64) { self.y = v; }

    #[wasm_bindgen(getter)]
    pub fn width(&self) -> f64 { self.width }

    #[wasm_bindgen(setter)]
    pub fn set_width(&mut self, v: f64) { self.width = v; }

    #[wasm_bindgen(getter)]
    pub fn height(&self) -> f64 { self.height }

    #[wasm_bindgen(setter)]
    pub fn set_height(&mut self, v: f64) { self.height = v; }

    pub fn add_move_to(&mut self, x: f64, y: f64) {
        self.paths.push(PdfPathCommand::new("m", x, y));
    }

    pub fn add_line_to(&mut self, x: f64, y: f64) {
        self.paths.push(PdfPathCommand::new("l", x, y));
    }

    pub fn add_cubic_to(&mut self, x1: f64, y1: f64, x2: f64, y2: f64, x: f64, y: f64) {
        self.paths.push(PdfPathCommand::with_cp("c", x, y, x1, y1, x2, y2));
    }

    pub fn add_close(&mut self) {
        self.paths.push(PdfPathCommand::new("h", 0.0, 0.0));
    }

    pub fn path_count(&self) -> usize {
        self.paths.len()
    }

    pub fn get_path(&self, idx: usize) -> JsValue {
        self.paths.get(idx)
            .and_then(|p| serde_wasm_bindgen::to_value(p).ok())
            .unwrap_or(JsValue::NULL)
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone)]
pub struct PdfExporter;

#[wasm_bindgen]
impl PdfExporter {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self { Self }

    pub fn export_pdf_bytes(
        &self,
        title: &str,
        author: &str,
        subject: &str,
        artboards: Vec<JsValue>,
        layers: Vec<JsValue>,
    ) -> Option<Vec<u8>> {
        let artboard_configs: Vec<ArtboardConfig> = artboards.iter()
            .filter_map(|v| serde_wasm_bindgen::from_value::<ArtboardConfig>(v.clone()).ok())
            .collect();
        let all_layers: Vec<PdfLayerData> = layers.iter()
            .filter_map(|v| serde_wasm_bindgen::from_value::<PdfLayerData>(v.clone()).ok())
            .collect();

        if artboard_configs.is_empty() {
            return None;
        }

        let doc_title = if title.is_empty() { "Untitled" } else { title };
        let (doc, page1, layer1) = printpdf::PdfDocument::new(
            doc_title,
            printpdf::Mm(artboard_configs[0].width_mm as f32),
            printpdf::Mm(artboard_configs[0].height_mm as f32),
            &artboard_configs[0].name,
        );

        let pages: Vec<(printpdf::PdfPageIndex, printpdf::PdfLayerIndex)> = if artboard_configs.len() > 1 {
            let mut additional = Vec::new();
            for ab in artboard_configs.iter().skip(1) {
                let (p, l) = doc.add_page(
                    printpdf::Mm(ab.width_mm as f32),
                    printpdf::Mm(ab.height_mm as f32),
                    &ab.name,
                );
                additional.push((p, l));
            }
            let mut all_pages = vec![(page1, layer1)];
            all_pages.extend(additional);
            all_pages
        } else {
            vec![(page1, layer1)]
        };

        if !author.is_empty() {
            let _ = author;
        }
        if !subject.is_empty() {
            let _ = subject;
        }

        for (ab_idx, ab) in artboard_configs.iter().enumerate() {
            if ab_idx >= pages.len() {
                break;
            }
            let (page_idx, layer_idx) = pages[ab_idx];
            let current_layer = doc.get_page(page_idx).get_layer(layer_idx);

            let artboard_layers: Vec<&PdfLayerData> = if ab.layer_ids.is_empty() {
                all_layers.iter().collect()
            } else {
                all_layers.iter()
                    .filter(|l| ab.layer_ids.contains(&l.id))
                    .collect()
            };

            for layer in artboard_layers {
                if !layer.visible {
                    continue;
                }

                let opacity = (layer.opacity as f32).clamp(0.0, 1.0);
                let page_h_mm = ab.height_mm;

                match layer.layer_type.as_str() {
                    "text" | "richtext" => {
                        let y_mm = page_h_mm - (layer.y + layer.font_size) / 2.834_646;
                        let x_mm = layer.x / 2.834_646;
                        let font_size = (layer.font_size as f32).clamp(1.0, 512.0);

                        let sr = layer.stroke_rgba;
                        current_layer.set_fill_color(printpdf::Color::Rgb(
                            printpdf::Rgb::new(
                                sr[0] as f32 / 255.0,
                                sr[1] as f32 / 255.0,
                                sr[2] as f32 / 255.0,
                                None,
                            )
                        ));
                        current_layer.set_outline_thickness(0.0);

                        let text_to_render = if layer.text.is_empty() {
                            format!("[{}]", layer.name)
                        } else {
                            layer.text.clone()
                        };

                        let basic_font = if layer.layer_type == "richtext" {
                            printpdf::BuiltinFont::HelveticaBold
                        } else {
                            printpdf::BuiltinFont::Helvetica
                        };

                        let font = doc.add_builtin_font(basic_font).ok();

                        if let Some(font_ref) = font {
                            current_layer.use_text(
                                text_to_render.clone(),
                                font_size,
                                printpdf::Mm(x_mm as f32),
                                printpdf::Mm(y_mm as f32),
                                &font_ref,
                            );
                        } else {
                            current_layer.set_fill_color(printpdf::Color::Rgb(
                                printpdf::Rgb::new(0.0, 0.0, 0.0, None)
                            ));
                            let line_y = y_mm;
                            let x_end = x_mm + text_to_render.len() as f64 * font_size as f64 * 0.5;
                            let p1 = printpdf::Point::new(printpdf::Mm(x_mm as f32), printpdf::Mm(line_y as f32));
                            let p2 = printpdf::Point::new(printpdf::Mm(x_end as f32), printpdf::Mm(line_y as f32));
                            let ring = vec![(p1, false), (p2, false)];
                            let polygon = printpdf::Polygon {
                                rings: vec![ring],
                                mode: printpdf::path::PaintMode::Stroke,
                                winding_order: printpdf::path::WindingOrder::EvenOdd,
                            };
                            current_layer.add_polygon(polygon);
                        }
                    }
                    _ => {
                        if !layer.paths.is_empty() {
                            let has_fill = layer.fill_rgba[3] > 0;
                            let has_stroke = layer.stroke_rgba[3] > 0 && layer.stroke_width > 0.0;

                            let rings = Self::convert_paths(&layer.paths, page_h_mm);

                            if has_stroke {
                                current_layer.set_outline_thickness(layer.stroke_width as f32 / 2.834_646);
                                let sr = layer.stroke_rgba;
                                current_layer.set_outline_color(printpdf::Color::Rgb(
                                    printpdf::Rgb::new(
                                        sr[0] as f32 / 255.0,
                                        sr[1] as f32 / 255.0,
                                        sr[2] as f32 / 255.0,
                                        None,
                                    )
                                ));
                            }

                            if has_fill {
                                let fr = layer.fill_rgba;
                                current_layer.set_fill_color(printpdf::Color::Rgb(
                                    printpdf::Rgb::new(
                                        fr[0] as f32 / 255.0,
                                        fr[1] as f32 / 255.0,
                                        fr[2] as f32 / 255.0,
                                        None,
                                    )
                                ));
                            }

                            let _ = opacity;

                            match (has_fill, has_stroke) {
                                (false, false) => {}
                                _ => {
                                    let pdf_mode = match (has_fill, has_stroke) {
                                        (true, true) => printpdf::path::PaintMode::FillStroke,
                                        (true, false) => printpdf::path::PaintMode::Fill,
                                        (false, true) => printpdf::path::PaintMode::Stroke,
                                        (false, false) => unreachable!(),
                                    };

                                    let polygon = printpdf::Polygon {
                                        rings,
                                        mode: pdf_mode,
                                        winding_order: printpdf::path::WindingOrder::EvenOdd,
                                    };
                                    current_layer.add_polygon(polygon);
                                }
                            }
                        } else {
                            let x1_mm = layer.x / 2.834_646;
                            let y1_mm = page_h_mm - (layer.y + layer.height) / 2.834_646;
                            let x2_mm = (layer.x + layer.width) / 2.834_646;
                            let y2_mm = page_h_mm - layer.y / 2.834_646;

                            current_layer.set_outline_thickness(0.5);
                            current_layer.set_outline_color(printpdf::Color::Rgb(
                                printpdf::Rgb::new(0.5, 0.5, 0.5, None)
                            ));

                            let p1 = printpdf::Point::new(printpdf::Mm(x1_mm as f32), printpdf::Mm(y1_mm as f32));
                            let p2 = printpdf::Point::new(printpdf::Mm(x2_mm as f32), printpdf::Mm(y1_mm as f32));
                            let p3 = printpdf::Point::new(printpdf::Mm(x2_mm as f32), printpdf::Mm(y2_mm as f32));
                            let p4 = printpdf::Point::new(printpdf::Mm(x1_mm as f32), printpdf::Mm(y2_mm as f32));
                            let ring = vec![(p1, false), (p2, false), (p3, false), (p4, false), (p1, false)];

                            let polygon = printpdf::Polygon {
                                rings: vec![ring],
                                mode: printpdf::path::PaintMode::Stroke,
                                winding_order: printpdf::path::WindingOrder::EvenOdd,
                            };
                            current_layer.add_polygon(polygon);
                        }
                    }
                }
            }
        }

        doc.save_to_bytes().ok()
    }

    pub fn create_standard_artboards(&self) -> Vec<JsValue> {
        let standards = vec![
            ("A4 Portrait", 210.0, 297.0),
            ("A4 Landscape", 297.0, 210.0),
            ("A3 Portrait", 297.0, 420.0),
            ("A3 Landscape", 420.0, 297.0),
            ("Letter Portrait", 215.9, 279.4),
            ("Letter Landscape", 279.4, 215.9),
            ("Slide 16:9", 338.67, 190.5),
            ("Slide 4:3", 254.0, 190.5),
        ];

        standards.into_iter()
            .map(|(name, w, h)| {
                let ab = ArtboardConfig::new(name, w, h);
                serde_wasm_bindgen::to_value(&ab).unwrap_or(JsValue::NULL)
            })
            .collect()
    }

    fn convert_paths(commands: &[PdfPathCommand], page_h_mm: f64) -> Vec<Vec<(printpdf::Point, bool)>> {
        let mut rings: Vec<Vec<(printpdf::Point, bool)>> = Vec::new();
        let mut current_ring: Vec<(printpdf::Point, bool)> = Vec::new();

        for cmd in commands {
            match cmd.op.as_str() {
                "m" => {
                    if !current_ring.is_empty() {
                        rings.push(std::mem::take(&mut current_ring));
                    }
                    let (px, py) = Self::to_pdf_point(cmd.x, cmd.y, page_h_mm);
                    current_ring.push((printpdf::Point::new(printpdf::Mm(px), printpdf::Mm(py)), false));
                }
                "l" => {
                    let (px, py) = Self::to_pdf_point(cmd.x, cmd.y, page_h_mm);
                    current_ring.push((printpdf::Point::new(printpdf::Mm(px), printpdf::Mm(py)), false));
                }
                "c" => {
                    let (px, py) = Self::to_pdf_point(cmd.x, cmd.y, page_h_mm);
                    let (cx1, cy1) = Self::to_pdf_point(cmd.x1, cmd.y1, page_h_mm);
                    let (cx2, cy2) = Self::to_pdf_point(cmd.x2, cmd.y2, page_h_mm);
                    if let Some(last) = current_ring.last().map(|(p, _)| *p) {
                        let steps = 8u32;
                        for i in 1..=steps {
                            let t = i as f64 / steps as f64;
                            let (ix, iy) = Self::bezier_cubic(
                                last.x.0 as f64, last.y.0 as f64,
                                cx1 as f64, cy1 as f64,
                                cx2 as f64, cy2 as f64,
                                px as f64, py as f64, t,
                            );
                            current_ring.push((
                                printpdf::Point::new(printpdf::Mm(ix as f32), printpdf::Mm(iy as f32)),
                                false,
                            ));
                        }
                    }
                }
                "h" => {
                    if !current_ring.is_empty() {
                        let first = current_ring[0].0;
                        current_ring.push((first, true));
                        rings.push(std::mem::take(&mut current_ring));
                    }
                }
                _ => {}
            }
        }

        if !current_ring.is_empty() {
            rings.push(current_ring);
        }

        rings
    }

    fn to_pdf_point(x: f64, y: f64, page_h_mm: f64) -> (f32, f32) {
        let px_mm = (x / 2.834_646) as f32;
        let py_mm = ((page_h_mm * 2.834_646 - y) / 2.834_646) as f32;
        (px_mm, py_mm)
    }

    fn bezier_cubic(
        x0: f64, y0: f64,
        x1: f64, y1: f64,
        x2: f64, y2: f64,
        x3: f64, y3: f64,
        t: f64,
    ) -> (f64, f64) {
        let mt = 1.0 - t;
        let mt2 = mt * mt;
        let mt3 = mt2 * mt;
        let t2 = t * t;
        let t3 = t2 * t;
        let x = mt3 * x0 + 3.0 * mt2 * t * x1 + 3.0 * mt * t2 * x2 + t3 * x3;
        let y = mt3 * y0 + 3.0 * mt2 * t * y1 + 3.0 * mt * t2 * y2 + t3 * y3;
        (x, y)
    }
}

impl Default for PdfExporter {
    fn default() -> Self { Self::new() }
}
