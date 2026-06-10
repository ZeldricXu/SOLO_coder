use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashMap, VecDeque};
use uuid::Uuid;
use wasm_bindgen::prelude::*;
use yrs::updates::decoder::Decode;
use yrs::updates::encoder::Encode;
use yrs::{Any, Doc, In, Map, MapPrelim, MapRef, Out, ReadTxn, StateVector, Transact, Update, WriteTxn};

pub type SiteId = Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[wasm_bindgen]
pub struct LamportTimestamp {
    counter: u64,
    site_id: SiteId,
}

impl LamportTimestamp {
    pub fn new(counter: u64, site_id: SiteId) -> Self {
        Self { counter, site_id }
    }

    pub fn tick(&mut self) {
        self.counter += 1;
    }

    pub fn merge(&mut self, other: &Self) {
        self.counter = self.counter.max(other.counter) + 1;
    }
}

impl PartialOrd for LamportTimestamp {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for LamportTimestamp {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        self.counter
            .cmp(&other.counter)
            .then(self.site_id.cmp(&other.site_id))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct VectorClock {
    clocks: BTreeMap<SiteId, u64>,
}

impl VectorClock {
    pub fn new() -> Self {
        Self {
            clocks: BTreeMap::new(),
        }
    }

    pub fn increment(&mut self, site_id: SiteId) {
        *self.clocks.entry(site_id).or_insert(0) += 1;
    }

    pub fn get(&self, site_id: &SiteId) -> u64 {
        *self.clocks.get(site_id).unwrap_or(&0)
    }

    pub fn merge(&mut self, other: &Self) {
        for (site_id, &clock) in &other.clocks {
            let current = self.clocks.entry(*site_id).or_insert(0);
            *current = (*current).max(clock);
        }
    }

    pub fn happens_before(&self, other: &Self) -> bool {
        let mut less_or_equal = true;
        let mut strictly_less = false;

        for (site_id, &clock) in &self.clocks {
            let other_clock = other.clocks.get(site_id).unwrap_or(&0);
            if clock > *other_clock {
                return false;
            }
            if clock < *other_clock {
                strictly_less = true;
            }
        }

        for site_id in other.clocks.keys() {
            if !self.clocks.contains_key(site_id) {
                strictly_less = true;
            }
        }

        less_or_equal && strictly_less
    }

    pub fn concurrent(&self, other: &Self) -> bool {
        !self.happens_before(other) && !other.happens_before(self)
    }
}

impl Default for VectorClock {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[wasm_bindgen]
pub struct SiteInfo {
    site_id: SiteId,
    user_id: String,
    username: String,
    lamport_clock: u64,
    vector_clock: VectorClock,
    last_seen: u64,
}

#[wasm_bindgen]
impl SiteInfo {
    #[wasm_bindgen(constructor)]
    pub fn new(user_id: String, username: String) -> Self {
        Self {
            site_id: Uuid::new_v4(),
            user_id,
            username,
            lamport_clock: 0,
            vector_clock: VectorClock::new(),
            last_seen: 0,
        }
    }

    #[wasm_bindgen(getter)]
    pub fn site_id(&self) -> String {
        self.site_id.to_string()
    }

    #[wasm_bindgen(getter)]
    pub fn user_id(&self) -> String {
        self.user_id.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn username(&self) -> String {
        self.username.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn lamport_clock(&self) -> u64 {
        self.lamport_clock
    }
}

impl SiteInfo {
    pub fn site_id_uuid(&self) -> SiteId {
        self.site_id
    }

    pub fn tick_lamport(&mut self) -> LamportTimestamp {
        self.lamport_clock += 1;
        self.vector_clock.increment(self.site_id);
        LamportTimestamp::new(self.lamport_clock, self.site_id)
    }

    pub fn observe_lamport(&mut self, other: &LamportTimestamp) {
        self.lamport_clock = self.lamport_clock.max(other.counter);
    }

    pub fn vector_clock(&self) -> &VectorClock {
        &self.vector_clock
    }

    pub fn merge_vector_clock(&mut self, other: &VectorClock) {
        self.vector_clock.merge(other);
    }

    pub fn update_last_seen(&mut self, timestamp: u64) {
        self.last_seen = timestamp;
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub struct PositionComponent {
    value: u32,
    site_id: SiteId,
    counter: u32,
}

impl PositionComponent {
    pub fn new(value: u32, site_id: SiteId, counter: u32) -> Self {
        Self {
            value,
            site_id,
            counter,
        }
    }
}

impl PartialOrd for PositionComponent {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for PositionComponent {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        self.value
            .cmp(&other.value)
            .then(self.site_id.cmp(&other.site_id))
            .then(self.counter.cmp(&other.counter))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub struct Position {
    path: Vec<PositionComponent>,
}

impl Position {
    pub fn new(path: Vec<PositionComponent>) -> Self {
        Self { path }
    }

    pub fn root() -> Self {
        Self { path: Vec::new() }
    }

    pub fn is_root(&self) -> bool {
        self.path.is_empty()
    }

    pub fn path(&self) -> &[PositionComponent] {
        &self.path
    }

    pub fn between(left: &Self, right: &Self, site_id: SiteId, counter: u32) -> Self {
        let mut path = Vec::new();
        let mut i = 0;
        let max_depth = 32;

        loop {
            if i >= max_depth {
                break;
            }

            let left_comp = left.path.get(i);
            let right_comp = right.path.get(i);

            let left_val = left_comp.map(|c| c.value).unwrap_or(0);
            let right_val = right_comp.map(|c| c.value).unwrap_or(u32::MAX);

            if right_val - left_val > 1 {
                let new_val = left_val + 1 + (uuid_to_u32(site_id, counter, i) % (right_val - left_val - 1));
                path.push(PositionComponent::new(new_val, site_id, counter));
                break;
            } else {
                match (left_comp, right_comp) {
                    (Some(lc), Some(rc)) if lc == rc => {
                        path.push(lc.clone());
                    }
                    (Some(lc), _) => {
                        path.push(lc.clone());
                    }
                    (_, Some(rc)) => {
                        path.push(rc.clone());
                    }
                    _ => {
                        let new_val = 1 + (uuid_to_u32(site_id, counter, i) % (u32::MAX - 1));
                        path.push(PositionComponent::new(new_val, site_id, counter));
                        break;
                    }
                }
            }
            i += 1;
        }

        Self { path }
    }
}

fn uuid_to_u32(site_id: SiteId, counter: u32, depth: usize) -> u32 {
    let bytes = site_id.as_bytes();
    let mut hash = counter.wrapping_mul(31) as u64;
    hash = hash.wrapping_add(depth as u64);
    for (i, &b) in bytes.iter().enumerate() {
        hash = hash.wrapping_mul(31).wrapping_add(b as u64);
        hash ^= (i as u64).wrapping_shl(8);
    }
    (hash & 0xFFFFFFFF) as u32
}

impl PartialOrd for Position {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for Position {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        let min_len = self.path.len().min(other.path.len());
        for i in 0..min_len {
            match self.path[i].cmp(&other.path[i]) {
                std::cmp::Ordering::Equal => continue,
                ord => return ord,
            }
        }
        self.path.len().cmp(&other.path.len())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Block {
    id: Uuid,
    position: Position,
    content: String,
    type_: String,
    properties: HashMap<String, serde_json::Value>,
    parent_id: Option<Uuid>,
    children: Vec<Uuid>,
    created_at: LamportTimestamp,
    updated_at: LamportTimestamp,
    is_deleted: bool,
}

impl Block {
    pub fn new(
        id: Uuid,
        position: Position,
        content: String,
        type_: String,
        created_at: LamportTimestamp,
    ) -> Self {
        Self {
            id,
            position,
            content,
            type_,
            properties: HashMap::new(),
            parent_id: None,
            children: Vec::new(),
            created_at: created_at.clone(),
            updated_at: created_at,
            is_deleted: false,
        }
    }

    pub fn id(&self) -> Uuid {
        self.id
    }

    pub fn position(&self) -> &Position {
        &self.position
    }

    pub fn content(&self) -> &str {
        &self.content
    }

    pub fn block_type(&self) -> &str {
        &self.type_
    }

    pub fn is_deleted(&self) -> bool {
        self.is_deleted
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", content = "data")]
pub enum Operation {
    Insert {
        block_id: Uuid,
        position: Position,
        content: String,
        block_type: String,
        parent_id: Option<Uuid>,
        timestamp: LamportTimestamp,
        vector_clock: VectorClock,
    },
    Delete {
        block_id: Uuid,
        timestamp: LamportTimestamp,
        vector_clock: VectorClock,
    },
    Update {
        block_id: Uuid,
        content: Option<String>,
        properties: Option<HashMap<String, serde_json::Value>>,
        timestamp: LamportTimestamp,
        vector_clock: VectorClock,
    },
    Move {
        block_id: Uuid,
        new_position: Position,
        new_parent_id: Option<Uuid>,
        timestamp: LamportTimestamp,
        vector_clock: VectorClock,
    },
}

impl Operation {
    pub fn timestamp(&self) -> &LamportTimestamp {
        match self {
            Operation::Insert { timestamp, .. }
            | Operation::Delete { timestamp, .. }
            | Operation::Update { timestamp, .. }
            | Operation::Move { timestamp, .. } => timestamp,
        }
    }

    pub fn vector_clock(&self) -> &VectorClock {
        match self {
            Operation::Insert { vector_clock, .. }
            | Operation::Delete { vector_clock, .. }
            | Operation::Update { vector_clock, .. }
            | Operation::Move { vector_clock, .. } => vector_clock,
        }
    }

    pub fn block_id(&self) -> Uuid {
        match self {
            Operation::Insert { block_id, .. }
            | Operation::Delete { block_id, .. }
            | Operation::Update { block_id, .. }
            | Operation::Move { block_id, .. } => *block_id,
        }
    }

    pub fn invert(&self, document: &YrsBoard) -> Option<Operation> {
        match self {
            Operation::Insert {
                block_id,
                timestamp,
                vector_clock,
                ..
            } => {
                Some(Operation::Delete {
                    block_id: *block_id,
                    timestamp: timestamp.clone(),
                    vector_clock: vector_clock.clone(),
                })
            }
            Operation::Delete {
                block_id,
                timestamp,
                vector_clock,
            } => {
                let block = document.get_block_including_deleted(block_id)?;
                Some(Operation::Insert {
                    block_id: *block_id,
                    position: block.position.clone(),
                    content: block.content.clone(),
                    block_type: block.type_.clone(),
                    parent_id: block.parent_id,
                    timestamp: timestamp.clone(),
                    vector_clock: vector_clock.clone(),
                })
            }
            Operation::Update {
                block_id,
                content,
                properties,
                timestamp,
                vector_clock,
            } => {
                let block = document.get_block(block_id)?;
                let old_content = if content.is_some() {
                    Some(block.content.clone())
                } else {
                    None
                };
                let old_properties = if properties.is_some() {
                    Some(block.properties.clone())
                } else {
                    None
                };
                Some(Operation::Update {
                    block_id: *block_id,
                    content: old_content,
                    properties: old_properties,
                    timestamp: timestamp.clone(),
                    vector_clock: vector_clock.clone(),
                })
            }
            Operation::Move {
                block_id,
                new_position,
                new_parent_id,
                timestamp,
                vector_clock,
            } => {
                let block = document.get_block(block_id)?;
                Some(Operation::Move {
                    block_id: *block_id,
                    new_position: block.position.clone(),
                    new_parent_id: block.parent_id,
                    timestamp: timestamp.clone(),
                    vector_clock: vector_clock.clone(),
                })
            }
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OperationLogEntry {
    operation: Operation,
    applied: bool,
    applied_at: u64,
}

pub struct OperationLog {
    entries: VecDeque<OperationLogEntry>,
    undo_stack: Vec<Operation>,
    redo_stack: Vec<Operation>,
    max_size: usize,
}

impl OperationLog {
    pub fn new(max_size: usize) -> Self {
        Self {
            entries: VecDeque::new(),
            undo_stack: Vec::new(),
            redo_stack: Vec::new(),
            max_size,
        }
    }

    pub fn push(&mut self, operation: Operation) {
        let entry = OperationLogEntry {
            operation,
            applied: true,
            applied_at: 0,
        };
        self.entries.push_back(entry);
        if self.entries.len() > self.max_size {
            self.entries.pop_front();
        }
    }

    pub fn record_for_undo(&mut self, operation: Operation) {
        self.undo_stack.push(operation);
        self.redo_stack.clear();
    }

    pub fn pop_undo(&mut self) -> Option<Operation> {
        self.undo_stack.pop()
    }

    pub fn pop_redo(&mut self) -> Option<Operation> {
        self.redo_stack.pop()
    }

    pub fn push_redo(&mut self, operation: Operation) {
        self.redo_stack.push(operation);
    }

    pub fn push_undo(&mut self, operation: Operation) {
        self.undo_stack.push(operation);
    }

    pub fn can_undo(&self) -> bool {
        !self.undo_stack.is_empty()
    }

    pub fn can_redo(&self) -> bool {
        !self.redo_stack.is_empty()
    }

    pub fn entries(&self) -> &VecDeque<OperationLogEntry> {
        &self.entries
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct CursorPosition {
    site_id: SiteId,
    block_id: Option<Uuid>,
    offset: u32,
    selection_start: Option<u32>,
    selection_end: Option<u32>,
    updated_at: LamportTimestamp,
}

impl CursorPosition {
    pub fn new(
        site_id: SiteId,
        block_id: Option<Uuid>,
        offset: u32,
        selection_start: Option<u32>,
        selection_end: Option<u32>,
        updated_at: LamportTimestamp,
    ) -> Self {
        Self {
            site_id,
            block_id,
            offset,
            selection_start,
            selection_end,
            updated_at,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PresenceState {
    site_id: SiteId,
    user_id: String,
    username: String,
    is_online: bool,
    cursor: Option<CursorPosition>,
    color: String,
    last_active: u64,
}

impl PresenceState {
    pub fn new(site_id: SiteId, user_id: String, username: String, color: String) -> Self {
        Self {
            site_id,
            user_id,
            username,
            is_online: true,
            cursor: None,
            color,
            last_active: 0,
        }
    }
}

pub struct PresenceManager {
    users: HashMap<SiteId, PresenceState>,
    timeout_ms: u64,
}

impl PresenceManager {
    pub fn new(timeout_ms: u64) -> Self {
        Self {
            users: HashMap::new(),
            timeout_ms,
        }
    }

    pub fn add_user(&mut self, state: PresenceState) {
        self.users.insert(state.site_id, state);
    }

    pub fn remove_user(&mut self, site_id: &SiteId) {
        self.users.remove(site_id);
    }

    pub fn update_cursor(&mut self, cursor: CursorPosition) {
        if let Some(user) = self.users.get_mut(&cursor.site_id) {
            user.cursor = Some(cursor);
            user.last_active = 0;
        }
    }

    pub fn set_online(&mut self, site_id: &SiteId, online: bool) {
        if let Some(user) = self.users.get_mut(site_id) {
            user.is_online = online;
        }
    }

    pub fn get_online_users(&self) -> Vec<&PresenceState> {
        self.users.values().filter(|u| u.is_online).collect()
    }

    pub fn get_user(&self, site_id: &SiteId) -> Option<&PresenceState> {
        self.users.get(site_id)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum WebSocketMessage {
    Operation {
        operation: Operation,
        document_id: String,
    },
    CursorUpdate {
        cursor: CursorPosition,
        document_id: String,
    },
    PresenceJoin {
        presence: PresenceState,
        document_id: String,
    },
    PresenceLeave {
        site_id: SiteId,
        document_id: String,
    },
    SyncRequest {
        site_id: SiteId,
        document_id: String,
        vector_clock: VectorClock,
    },
    SyncResponse {
        site_id: SiteId,
        document_id: String,
        operations: Vec<Operation>,
        blocks: Vec<Block>,
    },
    Heartbeat {
        site_id: SiteId,
        timestamp: u64,
    },
    Ack {
        operation_id: String,
        site_id: SiteId,
    },
}

impl WebSocketMessage {
    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }

    pub fn from_json(json: &str) -> Result<Self, serde_json::Error> {
        serde_json::from_str(json)
    }

    pub fn to_bytes(&self) -> Result<Vec<u8>, serde_json::Error> {
        serde_json::to_vec(self)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, serde_json::Error> {
        serde_json::from_slice(bytes)
    }
}

fn out_to_string(out: Out, txn: &impl ReadTxn) -> String {
    match out {
        Out::Any(any) => any.to_string(),
        other => other.to_string(txn),
    }
}

fn block_from_map<T: ReadTxn>(id_str: &str, map: MapRef, txn: &T) -> Option<Block> {
    let id = Uuid::parse_str(id_str).ok()?;
    
    let position_json = out_to_string(map.get(txn, "position")?, txn);
    let position: Position = serde_json::from_str(&position_json).ok()?;
    
    let mut content = out_to_string(map.get(txn, "content")?, txn);
    let type_ = out_to_string(map.get(txn, "type")?, txn);
    
    let properties_json = out_to_string(map.get(txn, "properties")?, txn);
    let properties: HashMap<String, serde_json::Value> = 
        serde_json::from_str(&properties_json).unwrap_or_default();
    
    let parent_id = map.get(txn, "parent_id").map(|v| {
        let s = out_to_string(v, txn);
        if !s.is_empty() && s != "null" { Uuid::parse_str(&s).ok() } else { None }
    }).flatten();
    
    let children_json = out_to_string(map.get(txn, "children")?, txn);
    let children: Vec<Uuid> = serde_json::from_str(&children_json).unwrap_or_default();
    
    let created_at_json = out_to_string(map.get(txn, "created_at")?, txn);
    let created_at: LamportTimestamp = serde_json::from_str(&created_at_json).ok()?;
    
    let updated_at_json = out_to_string(map.get(txn, "updated_at")?, txn);
    let updated_at: LamportTimestamp = serde_json::from_str(&updated_at_json).ok()?;
    
    let is_deleted = map.get(txn, "is_deleted")
        .map(|v| out_to_string(v, txn) == "true")
        .unwrap_or(false);
    
    if type_.starts_with("shape:") {
        let mut content_props: HashMap<String, serde_json::Value> =
            serde_json::from_str(&content).unwrap_or_default();
        
        let attr_keys = ["x", "y", "width", "height", "points"];
        for key in &attr_keys {
            let attr_key = format!("attr_{}", key);
            if let Some(val) = map.get(txn, &attr_key) {
                let s = out_to_string(val, txn);
                if let Ok(n) = s.parse::<f64>() {
                    content_props.insert(key.to_string(), serde_json::json!(n));
                } else if let Ok(arr) = serde_json::from_str::<Vec<(f64, f64)>>(&s) {
                    content_props.insert(key.to_string(), serde_json::json!(arr));
                }
            }
        }
        
        content = serde_json::to_string(&content_props).unwrap_or(content);
    }

    Some(Block {
        id,
        position,
        content,
        type_,
        properties,
        parent_id,
        children,
        created_at,
        updated_at,
        is_deleted,
    })
}

fn block_to_map_input(block: &Block) -> MapPrelim {
    let mut map: HashMap<String, In> = HashMap::new();
    map.insert("id".to_string(), In::Any(Any::from(block.id.to_string())));
    map.insert("position".to_string(), In::Any(Any::from(serde_json::to_string(&block.position).unwrap_or_default())));
    map.insert("content".to_string(), In::Any(Any::from(block.content.clone())));
    map.insert("type".to_string(), In::Any(Any::from(block.type_.clone())));
    map.insert("properties".to_string(), In::Any(Any::from(serde_json::to_string(&block.properties).unwrap_or_default())));
    map.insert("parent_id".to_string(), In::Any(Any::from(block.parent_id.map(|p| p.to_string()).unwrap_or_default())));
    map.insert("children".to_string(), In::Any(Any::from(serde_json::to_string(&block.children).unwrap_or_default())));
    map.insert("created_at".to_string(), In::Any(Any::from(serde_json::to_string(&block.created_at).unwrap_or_default())));
    map.insert("updated_at".to_string(), In::Any(Any::from(serde_json::to_string(&block.updated_at).unwrap_or_default())));
    map.insert("is_deleted".to_string(), In::Any(Any::from(block.is_deleted.to_string())));
    
    MapPrelim::from_iter(map)
}

pub struct YrsBoard {
    document_id: String,
    doc: Doc,
    site_info: SiteInfo,
    operation_log: OperationLog,
    presence: PresenceManager,
    operation_counter: u32,
    applied_operations: BTreeMap<LamportTimestamp, bool>,
    last_synced_sv: Option<StateVector>,
}

impl YrsBoard {
    pub fn new(document_id: String, site_info: SiteInfo) -> Self {
        let doc = Doc::with_client_id(site_info.site_id_uuid().as_u128() as u64);
        
        Self {
            document_id,
            doc,
            site_info,
            operation_log: OperationLog::new(10000),
            presence: PresenceManager::new(30000),
            operation_counter: 0,
            applied_operations: BTreeMap::new(),
            last_synced_sv: None,
        }
    }

    pub fn document_id(&self) -> &str {
        &self.document_id
    }

    pub fn site_info(&self) -> &SiteInfo {
        &self.site_info
    }

    pub fn site_info_mut(&mut self) -> &mut SiteInfo {
        &mut self.site_info
    }

    fn next_operation_counter(&mut self) -> u32 {
        self.operation_counter += 1;
        self.operation_counter
    }

    fn has_applied(&self, timestamp: &LamportTimestamp) -> bool {
        self.applied_operations.contains_key(timestamp)
    }

    fn mark_applied(&mut self, timestamp: LamportTimestamp) {
        self.applied_operations.insert(timestamp, true);
    }

    fn iter_blocks<T: ReadTxn>(txn: &T, blocks_map: MapRef, include_deleted: bool) -> Vec<(String, Block)> {
        let mut result = Vec::new();
        for (key, value) in blocks_map.iter(txn) {
            let key_str = key.to_string();
            if let Out::YMap(map_ref) = value {
                if let Some(block) = block_from_map(&key_str, map_ref, txn) {
                    if include_deleted || !block.is_deleted {
                        result.push((key_str, block));
                    }
                }
            }
        }
        result
    }

    pub fn blocks(&self) -> Vec<Block> {
        let txn = self.doc.transact();
        if let Some(blocks_map) = txn.get_map("blocks") {
            Self::iter_blocks(&txn, blocks_map, false)
                .into_iter()
                .map(|(_, b)| b)
                .collect()
        } else {
            Vec::new()
        }
    }

    pub fn get_block(&self, block_id: &Uuid) -> Option<Block> {
        let txn = self.doc.transact();
        let blocks_map = txn.get_map("blocks")?;
        let key = block_id.to_string();
        let value = blocks_map.get(&txn, &key)?;
        if let Out::YMap(map_ref) = value {
            let block = block_from_map(&key, map_ref, &txn)?;
            if block.is_deleted { None } else { Some(block) }
        } else {
            None
        }
    }

    pub fn get_block_including_deleted(&self, block_id: &Uuid) -> Option<Block> {
        let txn = self.doc.transact();
        let blocks_map = txn.get_map("blocks")?;
        let key = block_id.to_string();
        let value = blocks_map.get(&txn, &key)?;
        if let Out::YMap(map_ref) = value {
            block_from_map(&key, map_ref, &txn)
        } else {
            None
        }
    }

    fn get_block_positions(&self) -> BTreeMap<Position, Uuid> {
        let txn = self.doc.transact();
        let mut result = BTreeMap::new();
        if let Some(blocks_map) = txn.get_map("blocks") {
            for (_, block) in Self::iter_blocks(&txn, blocks_map, false) {
                result.insert(block.position, block.id);
            }
        }
        result
    }

    pub fn get_blocks_sorted(&self) -> Vec<Block> {
        let positions = self.get_block_positions();
        let mut result = Vec::new();
        for pos in positions.keys() {
            if let Some(id) = positions.get(pos) {
                if let Some(block) = self.get_block(id) {
                    result.push(block);
                }
            }
        }
        result
    }

    pub fn apply_operation(&mut self, operation: Operation) -> Result<bool, String> {
        if self.has_applied(operation.timestamp()) {
            return Ok(false);
        }
        self.apply_operation_internal(operation)
    }

    fn apply_operation_internal(&mut self, operation: Operation) -> Result<bool, String> {
        self.site_info.observe_lamport(operation.timestamp());
        self.site_info.merge_vector_clock(operation.vector_clock());

        let success = match &operation {
            Operation::Insert {
                block_id,
                position,
                content,
                block_type,
                parent_id,
                timestamp,
                ..
            } => {
                let existing = self.get_block_including_deleted(block_id);
                if let Some(mut block) = existing {
                    if block.is_deleted {
                        block.is_deleted = false;
                        block.position = position.clone();
                        block.content = content.clone();
                        block.type_ = block_type.clone();
                        block.parent_id = *parent_id;
                        if timestamp.cmp(&block.updated_at) != std::cmp::Ordering::Less {
                            block.updated_at = timestamp.clone();
                        }
                        
                        let mut txn = self.doc.transact_mut();
                        let blocks_map = txn.get_or_insert_map("blocks");
                        let key = block_id.to_string();
                        let block_map = match blocks_map.get(&txn, &key) {
                            Some(Out::YMap(m)) => m,
                            _ => return Err("Block map not found".to_string()),
                        };
                        block_map.insert(&mut txn, "is_deleted", In::Any(Any::from("false")));
                        block_map.insert(&mut txn, "position", In::Any(Any::from(serde_json::to_string(&position).unwrap_or_default())));
                        block_map.insert(&mut txn, "content", In::Any(Any::from(content.clone())));
                        block_map.insert(&mut txn, "type", In::Any(Any::from(block_type.clone())));
                        block_map.insert(&mut txn, "parent_id", In::Any(Any::from(parent_id.map(|p| p.to_string()).unwrap_or_default())));
                        block_map.insert(&mut txn, "updated_at", In::Any(Any::from(serde_json::to_string(&block.updated_at).unwrap_or_default())));
                        return Ok(true);
                    } else {
                        return Ok(false);
                    }
                }

                let mut block = Block::new(
                    *block_id,
                    position.clone(),
                    content.clone(),
                    block_type.clone(),
                    timestamp.clone(),
                );
                block.parent_id = *parent_id;

                let mut txn = self.doc.transact_mut();
                let blocks_map = txn.get_or_insert_map("blocks");
                let key = block_id.to_string();
                if blocks_map.get(&txn, &key).is_some() {
                    return Ok(false);
                }
                blocks_map.insert(&mut txn, key, block_to_map_input(&block));
                true
            }
            Operation::Delete { block_id, timestamp, .. } => {
                let existing = self.get_block_including_deleted(block_id);
                if let Some(mut block) = existing {
                    block.is_deleted = true;
                    block.updated_at = timestamp.clone();

                    let mut txn = self.doc.transact_mut();
                    let blocks_map = txn.get_or_insert_map("blocks");
                    let key = block_id.to_string();
                    let block_map = match blocks_map.get(&txn, &key) {
                        Some(Out::YMap(m)) => m,
                        _ => return Err("Block map not found".to_string()),
                    };
                    block_map.insert(&mut txn, "is_deleted", In::Any(Any::from("true")));
                    block_map.insert(
                        &mut txn,
                        "updated_at",
                        In::Any(Any::from(serde_json::to_string(&block.updated_at).unwrap_or_default())),
                    );
                    return Ok(true);
                }
                false
            }
            Operation::Update {
                block_id,
                content,
                properties,
                timestamp,
                ..
            } => {
                let existing = self.get_block_including_deleted(block_id);
                if let Some(mut block) = existing {
                    if let Some(new_content) = content {
                        block.content = new_content.clone();
                    }
                    if let Some(new_props) = &properties {
                        block.properties.extend(new_props.clone());
                    }
                    block.updated_at = timestamp.clone();

                    let mut txn = self.doc.transact_mut();
                    let blocks_map = txn.get_or_insert_map("blocks");
                    let key = block_id.to_string();
                    let block_map = match blocks_map.get(&txn, &key) {
                        Some(Out::YMap(m)) => m,
                        _ => return Err("Block map not found".to_string()),
                    };
                    
                    if content.is_some() {
                        block_map.insert(&mut txn, "content", In::Any(Any::from(block.content.clone())));
                        
                        if block.type_.starts_with("shape:") {
                            let fields_to_write: HashMap<String, serde_json::Value> =
                                if let Some(updated) = &properties {
                                    updated.clone()
                                } else {
                                    serde_json::from_str(&block.content).unwrap_or_default()
                                };
                            
                            for (k, v) in &fields_to_write {
                                let attr_key = format!("attr_{}", k);
                                if let Some(num) = v.as_f64() {
                                    block_map.insert(
                                        &mut txn,
                                        attr_key.as_str(),
                                        In::Any(Any::from(num.to_string())),
                                    );
                                } else if let Some(s) = v.as_str() {
                                    block_map.insert(
                                        &mut txn,
                                        attr_key.as_str(),
                                        In::Any(Any::from(s.to_string())),
                                    );
                                } else if let Ok(v_str) = serde_json::to_string(v) {
                                    block_map.insert(
                                        &mut txn,
                                        attr_key.as_str(),
                                        In::Any(Any::from(v_str)),
                                    );
                                }
                            }
                        }
                    }
                    if properties.is_some() {
                        block_map.insert(
                            &mut txn,
                            "properties",
                            In::Any(Any::from(serde_json::to_string(&block.properties).unwrap_or_default())),
                        );
                    }
                    block_map.insert(
                        &mut txn,
                        "updated_at",
                        In::Any(Any::from(serde_json::to_string(&block.updated_at).unwrap_or_default())),
                    );
                    return Ok(true);
                }
                false
            }
            Operation::Move {
                block_id,
                new_position,
                new_parent_id,
                timestamp,
                ..
            } => {
                let existing = self.get_block_including_deleted(block_id);
                if let Some(mut block) = existing {
                    block.position = new_position.clone();
                    block.parent_id = *new_parent_id;
                    block.updated_at = timestamp.clone();

                    let mut txn = self.doc.transact_mut();
                    let blocks_map = txn.get_or_insert_map("blocks");
                    let key = block_id.to_string();
                    let block_map = match blocks_map.get(&txn, &key) {
                        Some(Out::YMap(m)) => m,
                        _ => return Err("Block map not found".to_string()),
                    };
                    
                    block_map.insert(
                        &mut txn,
                        "position",
                        In::Any(Any::from(serde_json::to_string(&block.position).unwrap_or_default())),
                    );
                    block_map.insert(
                        &mut txn,
                        "parent_id",
                        In::Any(Any::from(block.parent_id.map(|p| p.to_string()).unwrap_or_default())),
                    );
                    block_map.insert(
                        &mut txn,
                        "updated_at",
                        In::Any(Any::from(serde_json::to_string(&block.updated_at).unwrap_or_default())),
                    );
                    return Ok(true);
                }
                false
            }
        };

        if success {
            self.mark_applied(operation.timestamp().clone());
            self.operation_log.push(operation);
        }

        Ok(success)
    }

    pub fn create_insert_operation(
        &mut self,
        position_before: Option<Uuid>,
        position_after: Option<Uuid>,
        content: String,
        block_type: String,
        parent_id: Option<Uuid>,
    ) -> Operation {
        let timestamp = self.site_info.tick_lamport();
        let counter = self.next_operation_counter();

        let left_pos = position_before
            .and_then(|id| self.get_block(&id).map(|b| b.position().clone()))
            .unwrap_or_else(Position::root);

        let right_pos = position_after
            .and_then(|id| self.get_block(&id).map(|b| b.position().clone()))
            .unwrap_or_else(|| {
                Position::new(vec![PositionComponent::new(
                    u32::MAX,
                    self.site_info.site_id_uuid(),
                    0,
                )])
            });

        let position =
            Position::between(&left_pos, &right_pos, self.site_info.site_id_uuid(), counter);
        let block_id = Uuid::new_v4();

        Operation::Insert {
            block_id,
            position,
            content,
            block_type,
            parent_id,
            timestamp,
            vector_clock: self.site_info.vector_clock().clone(),
        }
    }

    pub fn create_delete_operation(&mut self, block_id: Uuid) -> Operation {
        let timestamp = self.site_info.tick_lamport();
        Operation::Delete {
            block_id,
            timestamp,
            vector_clock: self.site_info.vector_clock().clone(),
        }
    }

    pub fn create_update_operation(
        &mut self,
        block_id: Uuid,
        content: Option<String>,
        properties: Option<HashMap<String, serde_json::Value>>,
    ) -> Operation {
        let timestamp = self.site_info.tick_lamport();
        Operation::Update {
            block_id,
            content,
            properties,
            timestamp,
            vector_clock: self.site_info.vector_clock().clone(),
        }
    }

    pub fn create_move_operation(
        &mut self,
        block_id: Uuid,
        position_before: Option<Uuid>,
        position_after: Option<Uuid>,
        new_parent_id: Option<Uuid>,
    ) -> Operation {
        let timestamp = self.site_info.tick_lamport();
        let counter = self.next_operation_counter();

        let left_pos = position_before
            .and_then(|id| self.get_block(&id).map(|b| b.position().clone()))
            .unwrap_or_else(Position::root);

        let right_pos = position_after
            .and_then(|id| self.get_block(&id).map(|b| b.position().clone()))
            .unwrap_or_else(|| {
                Position::new(vec![PositionComponent::new(
                    u32::MAX,
                    self.site_info.site_id_uuid(),
                    0,
                )])
            });

        let new_position =
            Position::between(&left_pos, &right_pos, self.site_info.site_id_uuid(), counter);

        Operation::Move {
            block_id,
            new_position,
            new_parent_id,
            timestamp,
            vector_clock: self.site_info.vector_clock().clone(),
        }
    }

    pub fn undo(&mut self) -> Option<Operation> {
        let undo_op = self.operation_log.pop_undo()?;
        let redo_op = undo_op.invert(self)?;
        self.operation_log.push_redo(redo_op);
        let _ = self.apply_operation_internal(undo_op.clone());
        Some(undo_op)
    }

    pub fn redo(&mut self) -> Option<Operation> {
        let redo_op = self.operation_log.pop_redo()?;
        let undo_op = redo_op.invert(self)?;
        self.operation_log.push_undo(undo_op);
        let _ = self.apply_operation_internal(redo_op.clone());
        Some(redo_op)
    }

    pub fn can_undo(&self) -> bool {
        self.operation_log.can_undo()
    }

    pub fn can_redo(&self) -> bool {
        self.operation_log.can_redo()
    }

    pub fn record_for_undo(&mut self, operation: Operation) {
        self.operation_log.record_for_undo(operation);
    }

    pub fn presence(&self) -> &PresenceManager {
        &self.presence
    }

    pub fn presence_mut(&mut self) -> &mut PresenceManager {
        &mut self.presence
    }

    pub fn encode_update_v1(&mut self) -> Vec<u8> {
        let txn = self.doc.transact();
        let current_sv = txn.state_vector();
        let empty_sv = StateVector::default();
        let diff = txn.encode_diff_v1(&empty_sv);
        drop(txn);
        self.last_synced_sv = Some(current_sv);
        diff
    }

    pub fn encode_state_vector_v1(&self) -> Vec<u8> {
        let txn = self.doc.transact();
        txn.state_vector().encode_v1()
    }

    pub fn encode_diff_v1(&self, state_vector: &[u8]) -> Vec<u8> {
        let txn = self.doc.transact();
        match StateVector::decode_v1(state_vector) {
            Ok(sv) => txn.encode_diff_v1(&sv),
            Err(_) => Vec::new(),
        }
    }

    pub fn apply_update(&mut self, update: &[u8]) -> Result<(), String> {
        let mut txn = self.doc.transact_mut();
        match Update::decode_v1(update) {
            Ok(u) => txn.apply_update(u),
            Err(e) => return Err(format!("Failed to decode update: {}", e)),
        }
        let current_sv = txn.state_vector();
        drop(txn);
        
        // 手动合并 attr_* 字段：确保并发场景下的 attr_* 都被正确同步
        // 1. 创建临时 Doc，解码 update
        let temp_doc = Doc::new();
        {
            let mut temp_txn = temp_doc.transact_mut();
            if let Ok(u) = Update::decode_v1(update) {
                let _ = temp_txn.apply_update(u);
            }
        }
        // 2. 遍历临时 Doc 中的所有 block，提取 attr_* 字段
        let temp_txn = temp_doc.transact();
        if let Some(temp_blocks) = temp_txn.get_map("blocks") {
            let mut attrs_to_merge: Vec<(String, String, String)> = Vec::new();
            for (block_id_str, val) in temp_blocks.iter(&temp_txn) {
                if let Out::YMap(temp_block_map) = val {
                    for (attr_key_str, attr_val) in temp_block_map.iter(&temp_txn) {
                        let k = attr_key_str.to_string();
                        if k.starts_with("attr_") {
                            let v = out_to_string(attr_val, &temp_txn);
                            attrs_to_merge.push((block_id_str.to_string(), k, v));
                        }
                    }
                }
            }
            drop(temp_txn);
            
            // 3. 将提取的 attr_* 字段写入 self 的 Doc
            if !attrs_to_merge.is_empty() {
                let mut self_txn = self.doc.transact_mut();
                let self_blocks = self_txn.get_or_insert_map("blocks");
                for (block_id_str, attr_key, attr_val) in attrs_to_merge {
                    if let Some(Out::YMap(self_block_map)) = self_blocks.get(&self_txn, &block_id_str) {
                        // 只在当前不存在这个 attr 时才写入（LWW：让 yrs 自己处理，
                        // 但如果 yrs 没有处理，就手动确保至少写入一次）
                        if self_block_map.get(&self_txn, &attr_key).is_none() {
                            self_block_map.insert(
                                &mut self_txn,
                                attr_key.as_str(),
                                In::Any(Any::from(attr_val)),
                            );
                        }
                    }
                }
            }
        }
        
        self.last_synced_sv = Some(current_sv);
        Ok(())
    }

    pub fn encode_sync_step1(&self) -> Vec<u8> {
        self.encode_state_vector_v1()
    }

    pub fn handle_sync_message(&mut self, message: &[u8]) -> Option<Vec<u8>> {
        if let Ok(update) = Update::decode_v1(message) {
            let update_bytes = update.encode_v1();
            let _ = self.apply_update(&update_bytes);
            None
        } else if let Ok(sv) = StateVector::decode_v1(message) {
            Some(self.encode_diff_v1(&sv.encode_v1()))
        } else {
            None
        }
    }

    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        let blocks: Vec<Block> = self.blocks();
        serde_json::to_string(&blocks)
    }

    pub fn from_json(
        json: &str,
        document_id: String,
        site_info: SiteInfo,
    ) -> Result<Self, serde_json::Error> {
        let blocks: Vec<Block> = serde_json::from_str(json)?;
        let mut board = Self::new(document_id, site_info);

        {
            let mut txn = board.doc.transact_mut();
            let blocks_map = txn.get_or_insert_map("blocks");
            for block in &blocks {
                let key = block.id.to_string();
                blocks_map.insert(&mut txn, key, block_to_map_input(block));
            }
        }

        Ok(board)
    }

    pub fn add_shape(&mut self, shape_type: String, x: f64, y: f64, width: f64, height: f64, properties: Option<HashMap<String, serde_json::Value>>) -> Result<Uuid, String> {
        let timestamp = self.site_info.tick_lamport();
        let counter = self.next_operation_counter();
        let block_id = Uuid::new_v4();

        let left_pos = Position::root();
        let right_pos = Position::new(vec![PositionComponent::new(
            u32::MAX,
            self.site_info.site_id_uuid(),
            0,
        )]);
        let position = Position::between(&left_pos, &right_pos, self.site_info.site_id_uuid(), counter);

        let mut props: HashMap<String, serde_json::Value> = properties.unwrap_or_default();
        props.insert("x".to_string(), serde_json::json!(x));
        props.insert("y".to_string(), serde_json::json!(y));
        props.insert("width".to_string(), serde_json::json!(width));
        props.insert("height".to_string(), serde_json::json!(height));

        let content = serde_json::to_string(&props).unwrap_or_default();

        let operation = Operation::Insert {
            block_id,
            position,
            content,
            block_type: format!("shape:{}", shape_type),
            parent_id: None,
            timestamp: timestamp.clone(),
            vector_clock: self.site_info.vector_clock().clone(),
        };

        let inverse = operation.invert(self);
        self.apply_operation(operation.clone())
            .map_err(|e| e.to_string())?;
        if let Some(inv) = inverse {
            self.record_for_undo(inv);
        }

        Ok(block_id)
    }

    pub fn update_shape(&mut self, block_id: Uuid, x: Option<f64>, y: Option<f64>, width: Option<f64>, height: Option<f64>, extra_properties: Option<HashMap<String, serde_json::Value>>) -> Result<(), String> {
        let existing = self.get_block(&block_id).ok_or("Shape not found")?;
        
        let mut props: HashMap<String, serde_json::Value> = 
            serde_json::from_str(&existing.content).unwrap_or_default();
        
        let mut updated_fields: HashMap<String, serde_json::Value> = HashMap::new();
        
        if let Some(v) = x { 
            props.insert("x".to_string(), serde_json::json!(v)); 
            updated_fields.insert("x".to_string(), serde_json::json!(v));
        }
        if let Some(v) = y { 
            props.insert("y".to_string(), serde_json::json!(v)); 
            updated_fields.insert("y".to_string(), serde_json::json!(v));
        }
        if let Some(v) = width { 
            props.insert("width".to_string(), serde_json::json!(v)); 
            updated_fields.insert("width".to_string(), serde_json::json!(v));
        }
        if let Some(v) = height { 
            props.insert("height".to_string(), serde_json::json!(v)); 
            updated_fields.insert("height".to_string(), serde_json::json!(v));
        }
        if let Some(extra) = extra_properties {
            for (k, v) in extra {
                props.insert(k.clone(), v.clone());
                updated_fields.insert(k, v);
            }
        }

        let new_content = serde_json::to_string(&props).unwrap_or_default();

        let operation = self.create_update_operation(
            block_id,
            Some(new_content),
            Some(updated_fields),
        );

        let inverse = operation.invert(self);
        self.apply_operation(operation)
            .map_err(|e| e.to_string())?;
        if let Some(inv) = inverse {
            self.record_for_undo(inv);
        }

        Ok(())
    }

    pub fn move_shape(&mut self, block_id: Uuid, position_before: Option<Uuid>, position_after: Option<Uuid>) -> Result<(), String> {
        let operation = self.create_move_operation(block_id, position_before, position_after, None);
        let inverse = operation.invert(self);
        self.apply_operation(operation)
            .map_err(|e| e.to_string())?;
        if let Some(inv) = inverse {
            self.record_for_undo(inv);
        }
        Ok(())
    }

    pub fn add_stroke(&mut self, points: Vec<(f64, f64)>, properties: Option<HashMap<String, serde_json::Value>>) -> Result<Uuid, String> {
        let timestamp = self.site_info.tick_lamport();
        let counter = self.next_operation_counter();
        let block_id = Uuid::new_v4();

        let left_pos = Position::root();
        let right_pos = Position::new(vec![PositionComponent::new(
            u32::MAX,
            self.site_info.site_id_uuid(),
            0,
        )]);
        let position = Position::between(&left_pos, &right_pos, self.site_info.site_id_uuid(), counter);

        let mut props: HashMap<String, serde_json::Value> = properties.unwrap_or_default();
        props.insert("points".to_string(), serde_json::json!(points));

        let content = serde_json::to_string(&props).unwrap_or_default();

        let operation = Operation::Insert {
            block_id,
            position,
            content,
            block_type: "stroke".to_string(),
            parent_id: None,
            timestamp: timestamp.clone(),
            vector_clock: self.site_info.vector_clock().clone(),
        };

        let inverse = operation.invert(self);
        self.apply_operation(operation.clone())
            .map_err(|e| e.to_string())?;
        if let Some(inv) = inverse {
            self.record_for_undo(inv);
        }

        Ok(block_id)
    }

    pub fn add_text(&mut self, text: String, x: f64, y: f64, properties: Option<HashMap<String, serde_json::Value>>) -> Result<Uuid, String> {
        let timestamp = self.site_info.tick_lamport();
        let counter = self.next_operation_counter();
        let block_id = Uuid::new_v4();

        let left_pos = Position::root();
        let right_pos = Position::new(vec![PositionComponent::new(
            u32::MAX,
            self.site_info.site_id_uuid(),
            0,
        )]);
        let position = Position::between(&left_pos, &right_pos, self.site_info.site_id_uuid(), counter);

        let mut props: HashMap<String, serde_json::Value> = properties.unwrap_or_default();
        props.insert("x".to_string(), serde_json::json!(x));
        props.insert("y".to_string(), serde_json::json!(y));

        let operation = Operation::Insert {
            block_id,
            position,
            content: text,
            block_type: "text".to_string(),
            parent_id: None,
            timestamp: timestamp.clone(),
            vector_clock: self.site_info.vector_clock().clone(),
        };

        let inverse = operation.invert(self);
        let _ = self.apply_operation(operation.clone());
        if let Some(inv) = inverse {
            self.record_for_undo(inv);
        }

        {
            let mut txn = self.doc.transact_mut();
            let blocks_map = txn.get_or_insert_map("blocks");
            let key = block_id.to_string();
            if let Some(Out::YMap(block_map)) = blocks_map.get(&txn, &key) {
                block_map.insert(
                    &mut txn,
                    "properties",
                    In::Any(Any::from(serde_json::to_string(&props).unwrap_or_default())),
                );
            }
        }

        Ok(block_id)
    }

    pub fn update_text(&mut self, block_id: Uuid, new_text: String) -> Result<(), String> {
        let operation = self.create_update_operation(
            block_id,
            Some(new_text),
            None,
        );
        let inverse = operation.invert(self);
        self.apply_operation(operation)
            .map_err(|e| e.to_string())?;
        if let Some(inv) = inverse {
            self.record_for_undo(inv);
        }
        Ok(())
    }

    pub fn delete_element(&mut self, block_id: Uuid) -> Result<(), String> {
        let operation = self.create_delete_operation(block_id);
        let inverse = operation.invert(self);
        self.apply_operation(operation)
            .map_err(|e| e.to_string())?;
        if let Some(inv) = inverse {
            self.record_for_undo(inv);
        }
        Ok(())
    }

    pub fn doc(&self) -> &Doc {
        &self.doc
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WasmBlock {
    id: String,
    position: String,
    content: String,
    type_: String,
    properties: String,
    parent_id: Option<String>,
}

#[wasm_bindgen]
impl WasmBlock {
    #[wasm_bindgen(getter)]
    pub fn id(&self) -> String {
        self.id.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn content(&self) -> String {
        self.content.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn type_(&self) -> String {
        self.type_.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn position(&self) -> String {
        self.position.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn properties(&self) -> String {
        self.properties.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn parent_id(&self) -> Option<String> {
        self.parent_id.clone()
    }
}

#[wasm_bindgen]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WasmOperation {
    json: String,
}

#[wasm_bindgen]
impl WasmOperation {
    #[wasm_bindgen(getter)]
    pub fn json(&self) -> String {
        self.json.clone()
    }

    #[wasm_bindgen(method)]
    pub fn type_(&self) -> String {
        if let Ok(val) = serde_json::from_str::<serde_json::Value>(&self.json) {
            val.get("type").and_then(|v| v.as_str()).unwrap_or("").to_string()
        } else {
            String::new()
        }
    }
}

fn block_to_wasm(block: &Block) -> WasmBlock {
    WasmBlock {
        id: block.id().to_string(),
        position: serde_json::to_string(block.position()).unwrap_or_default(),
        content: block.content().to_string(),
        type_: block.block_type().to_string(),
        properties: serde_json::to_string(&block.properties).unwrap_or_default(),
        parent_id: block.parent_id.map(|id| id.to_string()),
    }
}

#[wasm_bindgen]
pub struct WasmYrsBoard {
    inner: YrsBoard,
}

#[wasm_bindgen]
impl WasmYrsBoard {
    #[wasm_bindgen(constructor)]
    pub fn new(document_id: String, user_id: String, username: String) -> Self {
        let site_info = SiteInfo::new(user_id, username);
        Self {
            inner: YrsBoard::new(document_id, site_info),
        }
    }

    #[wasm_bindgen(getter)]
    pub fn document_id(&self) -> String {
        self.inner.document_id().to_string()
    }

    #[wasm_bindgen(getter)]
    pub fn site_id(&self) -> String {
        self.inner.site_info().site_id_uuid().to_string()
    }

    #[wasm_bindgen(getter)]
    pub fn user_id(&self) -> String {
        self.inner.site_info().user_id()
    }

    #[wasm_bindgen(getter)]
    pub fn username(&self) -> String {
        self.inner.site_info().username()
    }

    pub fn get_blocks(&self) -> js_sys::Array {
        self.inner
            .get_blocks_sorted()
            .into_iter()
            .map(|b| JsValue::from(block_to_wasm(&b)))
            .collect()
    }

    pub fn get_block(&self, block_id: String) -> Option<WasmBlock> {
        let uuid = Uuid::parse_str(&block_id).ok()?;
        let block = self.inner.get_block(&uuid)?;
        Some(block_to_wasm(&block))
    }

    pub fn insert_block(
        &mut self,
        before_id: Option<String>,
        after_id: Option<String>,
        content: String,
        block_type: String,
        parent_id: Option<String>,
    ) -> WasmOperation {
        let before_uuid = before_id.and_then(|s| Uuid::parse_str(&s).ok());
        let after_uuid = after_id.and_then(|s| Uuid::parse_str(&s).ok());
        let parent_uuid = parent_id.and_then(|s| Uuid::parse_str(&s).ok());

        let op = self.inner.create_insert_operation(
            before_uuid,
            after_uuid,
            content,
            block_type,
            parent_uuid,
        );

        let op_json = serde_json::to_string(&op).unwrap_or_default();
        let inverse = op.invert(&self.inner);
        let _ = self.inner.apply_operation(op.clone());
        if let Some(inv) = inverse {
            self.inner.record_for_undo(inv);
        }

        WasmOperation { json: op_json }
    }

    pub fn delete_block(&mut self, block_id: String) -> Option<WasmOperation> {
        let uuid = Uuid::parse_str(&block_id).ok()?;
        let op = self.inner.create_delete_operation(uuid);
        let op_json = serde_json::to_string(&op).unwrap_or_default();
        let inverse = op.invert(&self.inner);
        let _ = self.inner.apply_operation(op.clone());
        if let Some(inv) = inverse {
            self.inner.record_for_undo(inv);
        }
        Some(WasmOperation { json: op_json })
    }

    pub fn update_block(
        &mut self,
        block_id: String,
        content: Option<String>,
        properties_json: Option<String>,
    ) -> Option<WasmOperation> {
        let uuid = Uuid::parse_str(&block_id).ok()?;
        let properties = if let Some(json_str) = properties_json {
            serde_json::from_str(&json_str).ok()
        } else {
            None
        };
        let op = self.inner.create_update_operation(uuid, content, properties);
        let op_json = serde_json::to_string(&op).unwrap_or_default();
        let inverse = op.invert(&self.inner);
        let _ = self.inner.apply_operation(op.clone());
        if let Some(inv) = inverse {
            self.inner.record_for_undo(inv);
        }
        Some(WasmOperation { json: op_json })
    }

    pub fn move_block(
        &mut self,
        block_id: String,
        before_id: Option<String>,
        after_id: Option<String>,
        new_parent_id: Option<String>,
    ) -> Option<WasmOperation> {
        let block_uuid = Uuid::parse_str(&block_id).ok()?;
        let before_uuid = before_id.and_then(|s| Uuid::parse_str(&s).ok());
        let after_uuid = after_id.and_then(|s| Uuid::parse_str(&s).ok());
        let parent_uuid = new_parent_id.and_then(|s| Uuid::parse_str(&s).ok());

        let op = self
            .inner
            .create_move_operation(block_uuid, before_uuid, after_uuid, parent_uuid);
        let op_json = serde_json::to_string(&op).unwrap_or_default();
        let inverse = op.invert(&self.inner);
        let _ = self.inner.apply_operation(op.clone());
        if let Some(inv) = inverse {
            self.inner.record_for_undo(inv);
        }
        Some(WasmOperation { json: op_json })
    }

    pub fn apply_remote_operation(&mut self, operation_json: String) -> bool {
        match serde_json::from_str::<Operation>(&operation_json) {
            Ok(op) => self.inner.apply_operation(op).unwrap_or(false),
            Err(_) => false,
        }
    }

    pub fn can_undo(&self) -> bool {
        self.inner.can_undo()
    }

    pub fn can_redo(&self) -> bool {
        self.inner.can_redo()
    }

    pub fn undo(&mut self) -> Option<WasmOperation> {
        let op = self.inner.undo()?;
        Some(WasmOperation {
            json: serde_json::to_string(&op).unwrap_or_default(),
        })
    }

    pub fn redo(&mut self) -> Option<WasmOperation> {
        let op = self.inner.redo()?;
        Some(WasmOperation {
            json: serde_json::to_string(&op).unwrap_or_default(),
        })
    }

    pub fn to_json(&self) -> String {
        self.inner.to_json().unwrap_or_default()
    }

    pub fn from_json(json: String, document_id: String, user_id: String, username: String) -> Result<WasmYrsBoard, JsValue> {
        let site_info = SiteInfo::new(user_id, username);
        YrsBoard::from_json(&json, document_id, site_info)
            .map(|board| WasmYrsBoard { inner: board })
            .map_err(|e| JsValue::from_str(&e.to_string()))
    }

    pub fn serialize_operation(&self, operation: &WasmOperation) -> String {
        operation.json.clone()
    }

    pub fn deserialize_operation(&self, json: String) -> Option<WasmOperation> {
        serde_json::from_str::<Operation>(&json)
            .ok()
            .map(|_| WasmOperation { json })
    }

    pub fn encode_update_v1(&mut self) -> Vec<u8> {
        self.inner.encode_update_v1()
    }

    pub fn encode_state_vector_v1(&self) -> Vec<u8> {
        self.inner.encode_state_vector_v1()
    }

    pub fn encode_diff_v1(&self, state_vector: &[u8]) -> Vec<u8> {
        self.inner.encode_diff_v1(state_vector)
    }

    pub fn apply_update(&mut self, update: &[u8]) -> bool {
        self.inner.apply_update(update).is_ok()
    }

    pub fn encode_sync_step1(&self) -> Vec<u8> {
        self.inner.encode_sync_step1()
    }

    pub fn handle_sync_message(&mut self, message: &[u8]) -> Option<Vec<u8>> {
        self.inner.handle_sync_message(message)
    }

    pub fn add_shape(&mut self, shape_type: String, x: f64, y: f64, width: f64, height: f64, properties_json: Option<String>) -> Result<String, JsValue> {
        let properties = if let Some(json_str) = properties_json {
            serde_json::from_str(&json_str).ok()
        } else {
            None
        };
        self.inner
            .add_shape(shape_type, x, y, width, height, properties)
            .map(|id| id.to_string())
            .map_err(|e| JsValue::from_str(&e))
    }

    pub fn update_shape(&mut self, block_id: String, x: Option<f64>, y: Option<f64>, width: Option<f64>, height: Option<f64>, properties_json: Option<String>) -> Result<(), JsValue> {
        let uuid = Uuid::parse_str(&block_id).map_err(|e| JsValue::from_str(&e.to_string()))?;
        let extra_properties = if let Some(json_str) = properties_json {
            serde_json::from_str(&json_str).ok()
        } else {
            None
        };
        self.inner
            .update_shape(uuid, x, y, width, height, extra_properties)
            .map_err(|e| JsValue::from_str(&e))
    }

    pub fn move_shape(&mut self, block_id: String, before_id: Option<String>, after_id: Option<String>) -> Result<(), JsValue> {
        let uuid = Uuid::parse_str(&block_id).map_err(|e| JsValue::from_str(&e.to_string()))?;
        let before_uuid = before_id.and_then(|s| Uuid::parse_str(&s).ok());
        let after_uuid = after_id.and_then(|s| Uuid::parse_str(&s).ok());
        self.inner
            .move_shape(uuid, before_uuid, after_uuid)
            .map_err(|e| JsValue::from_str(&e))
    }

    pub fn add_stroke(&mut self, points_json: String, properties_json: Option<String>) -> Result<String, JsValue> {
        let points: Vec<(f64, f64)> = serde_json::from_str(&points_json)
            .map_err(|e| JsValue::from_str(&e.to_string()))?;
        let properties = if let Some(json_str) = properties_json {
            serde_json::from_str(&json_str).ok()
        } else {
            None
        };
        self.inner
            .add_stroke(points, properties)
            .map(|id| id.to_string())
            .map_err(|e| JsValue::from_str(&e))
    }

    pub fn add_text(&mut self, text: String, x: f64, y: f64, properties_json: Option<String>) -> Result<String, JsValue> {
        let properties = if let Some(json_str) = properties_json {
            serde_json::from_str(&json_str).ok()
        } else {
            None
        };
        self.inner
            .add_text(text, x, y, properties)
            .map(|id| id.to_string())
            .map_err(|e| JsValue::from_str(&e))
    }

    pub fn update_text(&mut self, block_id: String, new_text: String) -> Result<(), JsValue> {
        let uuid = Uuid::parse_str(&block_id).map_err(|e| JsValue::from_str(&e.to_string()))?;
        self.inner
            .update_text(uuid, new_text)
            .map_err(|e| JsValue::from_str(&e))
    }

    pub fn delete_element(&mut self, block_id: String) -> Result<(), JsValue> {
        let uuid = Uuid::parse_str(&block_id).map_err(|e| JsValue::from_str(&e.to_string()))?;
        self.inner
            .delete_element(uuid)
            .map_err(|e| JsValue::from_str(&e))
    }
}

#[wasm_bindgen]
pub struct WasmWebSocketMessage {
    inner: WebSocketMessage,
}

#[wasm_bindgen]
impl WasmWebSocketMessage {
    pub fn from_json(json: String) -> Result<WasmWebSocketMessage, JsValue> {
        WebSocketMessage::from_json(&json)
            .map(|m| WasmWebSocketMessage { inner: m })
            .map_err(|e| JsValue::from_str(&e.to_string()))
    }

    pub fn to_json(&self) -> String {
        self.inner.to_json().unwrap_or_default()
    }

    pub fn message_type(&self) -> String {
        match &self.inner {
            WebSocketMessage::Operation { .. } => "operation".to_string(),
            WebSocketMessage::CursorUpdate { .. } => "cursor_update".to_string(),
            WebSocketMessage::PresenceJoin { .. } => "presence_join".to_string(),
            WebSocketMessage::PresenceLeave { .. } => "presence_leave".to_string(),
            WebSocketMessage::SyncRequest { .. } => "sync_request".to_string(),
            WebSocketMessage::SyncResponse { .. } => "sync_response".to_string(),
            WebSocketMessage::Heartbeat { .. } => "heartbeat".to_string(),
            WebSocketMessage::Ack { .. } => "ack".to_string(),
        }
    }

    pub fn create_operation(operation_json: String, document_id: String) -> Result<WasmWebSocketMessage, JsValue> {
        let operation = serde_json::from_str::<Operation>(&operation_json)
            .map_err(|e| JsValue::from_str(&e.to_string()))?;
        Ok(WasmWebSocketMessage {
            inner: WebSocketMessage::Operation {
                operation,
                document_id,
            },
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_lamport_timestamp_ordering() {
        let site1 = Uuid::from_u128(1);
        let site2 = Uuid::from_u128(2);

        let t1 = LamportTimestamp::new(1, site1);
        let t2 = LamportTimestamp::new(2, site1);
        let t3 = LamportTimestamp::new(2, site2);

        assert!(t1 < t2);
        assert!(t2 < t3);
    }

    #[test]
    fn test_position_between() {
        let site1 = Uuid::new_v4();
        let left = Position::root();
        let right = Position::new(vec![PositionComponent::new(u32::MAX, site1, 0)]);

        let mid = Position::between(&left, &right, site1, 1);
        assert!(left < mid);
        assert!(mid < right);
    }

    #[test]
    fn test_vector_clock() {
        let site1 = Uuid::new_v4();
        let site2 = Uuid::new_v4();

        let mut vc1 = VectorClock::new();
        vc1.increment(site1);
        vc1.increment(site1);

        let mut vc2 = VectorClock::new();
        vc2.increment(site2);

        assert!(vc1.concurrent(&vc2));

        vc1.merge(&vc2);
        assert!(vc2.happens_before(&vc1));
    }

    #[test]
    fn test_insert_and_get_blocks() {
        let site_info = SiteInfo::new("user1".to_string(), "Alice".to_string());
        let mut doc = YrsBoard::new("doc1".to_string(), site_info);

        let op = doc.create_insert_operation(
            None,
            None,
            "Hello World".to_string(),
            "paragraph".to_string(),
            None,
        );
        let _ = doc.apply_operation(op);

        assert_eq!(doc.blocks().len(), 1);
        assert_eq!(doc.blocks()[0].content(), "Hello World");
    }

    #[test]
    fn test_undo_redo() {
        let site_info = SiteInfo::new("user1".to_string(), "Alice".to_string());
        let mut doc = YrsBoard::new("doc1".to_string(), site_info);

        let op = doc.create_insert_operation(
            None,
            None,
            "Hello".to_string(),
            "paragraph".to_string(),
            None,
        );
        let inverse = op.invert(&doc).unwrap();
        let _ = doc.apply_operation(op);
        doc.record_for_undo(inverse);

        assert_eq!(doc.blocks().len(), 1);
        assert!(doc.can_undo());

        let _ = doc.undo();
        assert_eq!(doc.blocks().len(), 0);
        assert!(doc.can_redo());

        let _ = doc.redo();
        assert_eq!(doc.blocks().len(), 1);
    }

    #[test]
    fn test_conflict_resolution() {
        let site1 = Uuid::new_v4();
        let site2 = Uuid::new_v4();

        let user1 = SiteInfo {
            site_id: site1,
            user_id: "u1".to_string(),
            username: "Alice".to_string(),
            lamport_clock: 0,
            vector_clock: VectorClock::new(),
            last_seen: 0,
        };

        let user2 = SiteInfo {
            site_id: site2,
            user_id: "u2".to_string(),
            username: "Bob".to_string(),
            lamport_clock: 0,
            vector_clock: VectorClock::new(),
            last_seen: 0,
        };

        let mut doc1 = YrsBoard::new("doc1".to_string(), user1);
        let mut doc2 = YrsBoard::new("doc1".to_string(), user2);

        let op1 = doc1.create_insert_operation(
            None,
            None,
            "First".to_string(),
            "paragraph".to_string(),
            None,
        );
        let op2 = doc2.create_insert_operation(
            None,
            None,
            "Second".to_string(),
            "paragraph".to_string(),
            None,
        );

        let _ = doc1.apply_operation(op1.clone());
        let _ = doc1.apply_operation(op2.clone());
        let _ = doc2.apply_operation(op2.clone());
        let _ = doc2.apply_operation(op1.clone());

        let blocks1 = doc1.get_blocks_sorted();
        let blocks2 = doc2.get_blocks_sorted();

        assert_eq!(blocks1.len(), blocks2.len());
        for (a, b) in blocks1.iter().zip(blocks2.iter()) {
            assert_eq!(a.id(), b.id());
            assert_eq!(a.content(), b.content());
        }
    }

    #[test]
    fn test_websocket_message_serialization() {
        let site_id = Uuid::new_v4();
        let presence = PresenceState::new(
            site_id,
            "user1".to_string(),
            "Alice".to_string(),
            "#ff0000".to_string(),
        );

        let msg = WebSocketMessage::PresenceJoin {
            presence,
            document_id: "doc1".to_string(),
        };

        let json = msg.to_json().unwrap();
        let parsed = WebSocketMessage::from_json(&json).unwrap();
        match parsed {
            WebSocketMessage::PresenceJoin { document_id, .. } => {
                assert_eq!(document_id, "doc1");
            }
            _ => panic!("Unexpected message type"),
        }
    }

    #[test]
    fn test_yrs_sync_basic() {
        let site_info1 = SiteInfo::new("user1".to_string(), "Alice".to_string());
        let site_info2 = SiteInfo::new("user2".to_string(), "Bob".to_string());
        let mut board1 = YrsBoard::new("doc1".to_string(), site_info1);
        let mut board2 = YrsBoard::new("doc1".to_string(), site_info2);

        let op = board1.create_insert_operation(
            None,
            None,
            "Sync Test".to_string(),
            "paragraph".to_string(),
            None,
        );
        let _ = board1.apply_operation(op);
        assert_eq!(board1.blocks().len(), 1);

        let update = board1.encode_update_v1();
        assert!(!update.is_empty());

        let result = board2.apply_update(&update);
        assert!(result.is_ok());
        assert_eq!(board2.blocks().len(), 1);
        assert_eq!(board2.blocks()[0].content(), "Sync Test");
    }

    #[test]
    fn test_yrs_sync_step() {
        let site_info1 = SiteInfo::new("user1".to_string(), "Alice".to_string());
        let site_info2 = SiteInfo::new("user2".to_string(), "Bob".to_string());
        let mut board1 = YrsBoard::new("doc1".to_string(), site_info1);
        let mut board2 = YrsBoard::new("doc1".to_string(), site_info2);

        let op = board1.create_insert_operation(
            None,
            None,
            "Step Sync".to_string(),
            "paragraph".to_string(),
            None,
        );
        let _ = board1.apply_operation(op);

        let step1 = board2.encode_sync_step1();
        let response = board1.handle_sync_message(&step1);
        assert!(response.is_some());

        let step2 = response.unwrap();
        let final_response = board2.handle_sync_message(&step2);
        assert!(final_response.is_none());

        assert_eq!(board2.blocks().len(), 1);
        assert_eq!(board2.blocks()[0].content(), "Step Sync");
    }

    #[test]
    fn test_add_shape() {
        let site_info = SiteInfo::new("user1".to_string(), "Alice".to_string());
        let mut board = YrsBoard::new("doc1".to_string(), site_info);

        let result = board.add_shape("rectangle".to_string(), 10.0, 20.0, 100.0, 80.0, None);
        assert!(result.is_ok());
        
        let shape_id = result.unwrap();
        let block = board.get_block(&shape_id).unwrap();
        assert_eq!(block.block_type(), "shape:rectangle");
        
        let content: HashMap<String, serde_json::Value> = serde_json::from_str(block.content()).unwrap();
        assert_eq!(content.get("x").unwrap().as_f64().unwrap(), 10.0);
        assert_eq!(content.get("y").unwrap().as_f64().unwrap(), 20.0);
        assert_eq!(content.get("width").unwrap().as_f64().unwrap(), 100.0);
        assert_eq!(content.get("height").unwrap().as_f64().unwrap(), 80.0);
    }

    #[test]
    fn test_update_shape() {
        let site_info = SiteInfo::new("user1".to_string(), "Alice".to_string());
        let mut board = YrsBoard::new("doc1".to_string(), site_info);

        let shape_id = board.add_shape("circle".to_string(), 0.0, 0.0, 50.0, 50.0, None).unwrap();
        
        let result = board.update_shape(shape_id, Some(100.0), Some(200.0), None, None, None);
        assert!(result.is_ok());

        let block = board.get_block(&shape_id).unwrap();
        let content: HashMap<String, serde_json::Value> = serde_json::from_str(block.content()).unwrap();
        assert_eq!(content.get("x").unwrap().as_f64().unwrap(), 100.0);
        assert_eq!(content.get("y").unwrap().as_f64().unwrap(), 200.0);
        assert_eq!(content.get("width").unwrap().as_f64().unwrap(), 50.0);
    }

    #[test]
    fn test_add_text() {
        let site_info = SiteInfo::new("user1".to_string(), "Alice".to_string());
        let mut board = YrsBoard::new("doc1".to_string(), site_info);

        let result = board.add_text("Hello World".to_string(), 50.0, 60.0, None);
        assert!(result.is_ok());

        let text_id = result.unwrap();
        let block = board.get_block(&text_id).unwrap();
        assert_eq!(block.block_type(), "text");
        assert_eq!(block.content(), "Hello World");
    }

    #[test]
    fn test_update_text() {
        let site_info = SiteInfo::new("user1".to_string(), "Alice".to_string());
        let mut board = YrsBoard::new("doc1".to_string(), site_info);

        let text_id = board.add_text("Original".to_string(), 0.0, 0.0, None).unwrap();
        
        let result = board.update_text(text_id, "Updated".to_string());
        assert!(result.is_ok());

        let block = board.get_block(&text_id).unwrap();
        assert_eq!(block.content(), "Updated");
    }

    #[test]
    fn test_delete_element() {
        let site_info = SiteInfo::new("user1".to_string(), "Alice".to_string());
        let mut board = YrsBoard::new("doc1".to_string(), site_info);

        let shape_id = board.add_shape("rectangle".to_string(), 0.0, 0.0, 100.0, 100.0, None).unwrap();
        assert_eq!(board.blocks().len(), 1);

        let result = board.delete_element(shape_id);
        assert!(result.is_ok());
        assert_eq!(board.blocks().len(), 0);
    }

    #[test]
    fn test_concurrent_shape_update() {
        let site1 = Uuid::new_v4();
        let site2 = Uuid::new_v4();

        let user1 = SiteInfo {
            site_id: site1,
            user_id: "u1".to_string(),
            username: "Alice".to_string(),
            lamport_clock: 0,
            vector_clock: VectorClock::new(),
            last_seen: 0,
        };
        let user2 = SiteInfo {
            site_id: site2,
            user_id: "u2".to_string(),
            username: "Bob".to_string(),
            lamport_clock: 0,
            vector_clock: VectorClock::new(),
            last_seen: 0,
        };

        let mut board1 = YrsBoard::new("doc1".to_string(), user1);
        let mut board2 = YrsBoard::new("doc1".to_string(), user2);

        let shape_id = board1.add_shape("rectangle".to_string(), 0.0, 0.0, 100.0, 100.0, None).unwrap();
        let initial_update = board1.encode_update_v1();
        board2.apply_update(&initial_update).unwrap();

        let _ = board1.update_shape(shape_id, Some(50.0), None, None, None, None);
        let _ = board2.update_shape(shape_id, None, Some(75.0), None, None, None);

        let update1 = board1.encode_update_v1();
        let update2 = board2.encode_update_v1();

        board1.apply_update(&update2).unwrap();
        board2.apply_update(&update1).unwrap();

        let block1 = board1.get_block(&shape_id).unwrap();
        let block2 = board2.get_block(&shape_id).unwrap();
        let content1: HashMap<String, serde_json::Value> = serde_json::from_str(block1.content()).unwrap();
        let content2: HashMap<String, serde_json::Value> = serde_json::from_str(block2.content()).unwrap();

        assert_eq!(content1.get("x").unwrap().as_f64().unwrap(), 50.0);
        assert_eq!(content1.get("y").unwrap().as_f64().unwrap(), 75.0);
        assert_eq!(content2.get("x").unwrap().as_f64().unwrap(), 50.0);
        assert_eq!(content2.get("y").unwrap().as_f64().unwrap(), 75.0);
    }

    #[test]
    fn test_add_stroke() {
        let site_info = SiteInfo::new("user1".to_string(), "Alice".to_string());
        let mut board = YrsBoard::new("doc1".to_string(), site_info);

        let points = vec![(0.0, 0.0), (10.0, 10.0), (20.0, 20.0)];
        let result = board.add_stroke(points, None);
        assert!(result.is_ok());

        let stroke_id = result.unwrap();
        let block = board.get_block(&stroke_id).unwrap();
        assert_eq!(block.block_type(), "stroke");
    }
}