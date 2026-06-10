use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashMap, VecDeque};
use uuid::Uuid;
use wasm_bindgen::prelude::*;

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

    pub fn invert(&self, document: &CRDTDocument) -> Option<Operation> {
        match self {
            Operation::Insert {
                block_id,
                timestamp,
                vector_clock,
                ..
            } => {
                let block = document.blocks.get(block_id)?;
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
                let block = document.blocks.get(block_id)?;
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
                let block = document.blocks.get(block_id)?;
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
                let block = document.blocks.get(block_id)?;
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

pub struct CRDTDocument {
    document_id: String,
    blocks: BTreeMap<Uuid, Block>,
    block_positions: BTreeMap<Position, Uuid>,
    operation_log: OperationLog,
    presence: PresenceManager,
    site_info: SiteInfo,
    operation_counter: u32,
    applied_operations: BTreeMap<LamportTimestamp, bool>,
}

impl CRDTDocument {
    pub fn new(document_id: String, site_info: SiteInfo) -> Self {
        Self {
            document_id,
            blocks: BTreeMap::new(),
            block_positions: BTreeMap::new(),
            operation_log: OperationLog::new(10000),
            presence: PresenceManager::new(30000),
            site_info,
            operation_counter: 0,
            applied_operations: BTreeMap::new(),
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

    pub fn blocks(&self) -> Vec<&Block> {
        self.blocks.values().filter(|b| !b.is_deleted).collect()
    }

    pub fn get_block(&self, block_id: &Uuid) -> Option<&Block> {
        self.blocks.get(block_id).filter(|b| !b.is_deleted)
    }

    pub fn get_blocks_sorted(&self) -> Vec<&Block> {
        let mut positions: Vec<&Position> = self.block_positions.keys().collect();
        positions.sort();
        positions
            .into_iter()
            .filter_map(|pos| {
                self.block_positions
                    .get(pos)
                    .and_then(|id| self.blocks.get(id))
                    .filter(|b| !b.is_deleted)
            })
            .collect()
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

    pub fn apply_operation(&mut self, operation: Operation) -> Result<bool, String> {
        if self.has_applied(operation.timestamp()) {
            return Ok(false);
        }

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
                if self.blocks.contains_key(block_id) {
                    return Ok(false);
                }

                let mut block = Block::new(
                    *block_id,
                    position.clone(),
                    content.clone(),
                    block_type.clone(),
                    timestamp.clone(),
                );
                block.parent_id = *parent_id;

                self.block_positions.insert(position.clone(), *block_id);
                self.blocks.insert(*block_id, block);

                if let Some(pid) = parent_id {
                    if let Some(parent) = self.blocks.get_mut(pid) {
                        parent.children.push(*block_id);
                    }
                }
                true
            }
            Operation::Delete { block_id, timestamp, .. } => {
                if let Some(block) = self.blocks.get_mut(block_id) {
                    if !block.is_deleted
                        || timestamp.cmp(&block.updated_at) == std::cmp::Ordering::Greater
                    {
                        block.is_deleted = true;
                        block.updated_at = timestamp.clone();
                        return Ok(true);
                    }
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
                if let Some(block) = self.blocks.get_mut(block_id) {
                    if timestamp.cmp(&block.updated_at) == std::cmp::Ordering::Greater
                        || timestamp.cmp(&block.updated_at) == std::cmp::Ordering::Equal
                    {
                        if let Some(new_content) = content {
                            block.content = new_content.clone();
                        }
                        if let Some(new_props) = properties {
                            block.properties.extend(new_props.clone());
                        }
                        block.updated_at = timestamp.clone();
                        return Ok(true);
                    }
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
                if let Some(block) = self.blocks.get_mut(block_id) {
                    if timestamp.cmp(&block.updated_at) == std::cmp::Ordering::Greater
                        || timestamp.cmp(&block.updated_at) == std::cmp::Ordering::Equal
                    {
                        self.block_positions.remove(&block.position);
                        block.position = new_position.clone();
                        block.parent_id = *new_parent_id;
                        block.updated_at = timestamp.clone();
                        self.block_positions.insert(new_position.clone(), *block_id);
                        return Ok(true);
                    }
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
            .and_then(|id| self.blocks.get(&id).map(|b| b.position().clone()))
            .unwrap_or_else(Position::root);

        let right_pos = position_after
            .and_then(|id| self.blocks.get(&id).map(|b| b.position().clone()))
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
            .and_then(|id| self.blocks.get(&id).map(|b| b.position().clone()))
            .unwrap_or_else(Position::root);

        let right_pos = position_after
            .and_then(|id| self.blocks.get(&id).map(|b| b.position().clone()))
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
        let op = self.operation_log.pop_undo()?;
        let inverse = op.invert(self)?;
        self.operation_log.push_redo(op);
        let _ = self.apply_operation(inverse.clone());
        Some(inverse)
    }

    pub fn redo(&mut self) -> Option<Operation> {
        let op = self.operation_log.pop_redo()?;
        let inverse = op.invert(self)?;
        self.operation_log.push_undo(op);
        let _ = self.apply_operation(inverse.clone());
        Some(inverse)
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

    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        let blocks: Vec<&Block> = self.blocks.values().collect();
        serde_json::to_string(&blocks)
    }

    pub fn from_json(
        json: &str,
        document_id: String,
        site_info: SiteInfo,
    ) -> Result<Self, serde_json::Error> {
        let blocks: Vec<Block> = serde_json::from_str(json)?;
        let mut doc = Self::new(document_id, site_info);

        for block in blocks {
            doc.block_positions
                .insert(block.position().clone(), block.id());
            doc.blocks.insert(block.id(), block);
        }

        Ok(doc)
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

#[wasm_bindgen]
pub struct WasmCRDTDocument {
    inner: CRDTDocument,
}

#[wasm_bindgen]
impl WasmCRDTDocument {
    #[wasm_bindgen(constructor)]
    pub fn new(document_id: String, user_id: String, username: String) -> Self {
        let site_info = SiteInfo::new(user_id, username);
        Self {
            inner: CRDTDocument::new(document_id, site_info),
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
            .map(|b| {
                JsValue::from(WasmBlock {
                    id: b.id().to_string(),
                    position: serde_json::to_string(b.position()).unwrap_or_default(),
                    content: b.content().to_string(),
                    type_: b.block_type().to_string(),
                    properties: serde_json::to_string(&b.properties).unwrap_or_default(),
                    parent_id: b.parent_id.map(|id| id.to_string()),
                })
            })
            .collect()
    }

    pub fn get_block(&self, block_id: String) -> Option<WasmBlock> {
        let uuid = Uuid::parse_str(&block_id).ok()?;
        let block = self.inner.get_block(&uuid)?;
        Some(WasmBlock {
            id: block.id().to_string(),
            position: serde_json::to_string(block.position()).unwrap_or_default(),
            content: block.content().to_string(),
            type_: block.block_type().to_string(),
            properties: serde_json::to_string(&block.properties).unwrap_or_default(),
            parent_id: block.parent_id.map(|id| id.to_string()),
        })
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

    pub fn from_json(json: String, document_id: String, user_id: String, username: String) -> Result<WasmCRDTDocument, JsValue> {
        let site_info = SiteInfo::new(user_id, username);
        CRDTDocument::from_json(&json, document_id, site_info)
            .map(|doc| WasmCRDTDocument { inner: doc })
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
        let site1 = Uuid::new_v4();
        let site2 = Uuid::new_v4();

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
        let mut doc = CRDTDocument::new("doc1".to_string(), site_info);

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
        let mut doc = CRDTDocument::new("doc1".to_string(), site_info);

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

        let mut doc1 = CRDTDocument::new("doc1".to_string(), user1);
        let mut doc2 = CRDTDocument::new("doc1".to_string(), user2);

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
}
