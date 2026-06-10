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
