use std::collections::{BTreeMap, HashMap};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use tokio::sync::broadcast;
use uuid::Uuid;

pub use broadcast::Receiver as EventReceiver;
pub use broadcast::Sender as EventSender;

const EVENT_CHANNEL_CAPACITY: usize = 1024;

#[derive(Error, Debug)]
pub enum CrdtError {
    #[error("Invalid operation: {0}")]
    Invalid(String),

    #[error("ID not found: client={0} clock={1}")]
    IdNotFound(u64, u32),

    #[error("Document is corrupted")]
    Corrupted,

    #[error("Clock violation: local={0} remote={1}")]
    ClockViolation(u32, u32),

    #[error("Client not found: {0}")]
    ClientNotFound(u64),

    #[error("Snapshot error: {0}")]
    Snapshot(String),

    #[error("Decode error: {0}")]
    Decode(String),
}

pub type CrdtResult<T> = Result<T, CrdtError>;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub struct YataId {
    pub client: u64,
    pub clock: u32,
}

impl YataId {
    pub fn new(client: u64, clock: u32) -> Self {
        Self { client, clock }
    }

    pub fn root() -> Self {
        Self { client: 0, clock: 0 }
    }

    pub fn is_root(&self) -> bool {
        self.client == 0 && self.clock == 0
    }
}

impl std::fmt::Display for YataId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "({},{})", self.client, self.clock)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum Origin {
    None,
    Id(YataId),
}

impl From<YataId> for Origin {
    fn from(id: YataId) -> Self {
        if id.is_root() {
            Origin::None
        } else {
            Origin::Id(id)
        }
    }
}

impl From<Option<YataId>> for Origin {
    fn from(opt: Option<YataId>) -> Self {
        match opt {
            Some(id) if id.is_root() => Origin::None,
            Some(id) => Origin::Id(id),
            None => Origin::None,
        }
    }
}

impl Origin {
    pub fn to_option(&self) -> Option<YataId> {
        match self {
            Origin::None => None,
            Origin::Id(id) => Some(*id),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct ClientClock {
    pub client: u64,
    pub clock: u32,
}

impl ClientClock {
    pub fn new(client: u64, clock: u32) -> Self {
        Self { client, clock }
    }

    pub fn inc(&mut self) {
        self.clock = self.clock.checked_add(1).expect("Clock overflow");
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", content = "value")]
pub enum AttributeValue {
    String(String),
    Number(f64),
    Bool(bool),
    Null,
}

impl AttributeValue {
    pub fn as_str(&self) -> Option<&str> {
        match self {
            AttributeValue::String(s) => Some(s),
            _ => None,
        }
    }

    pub fn as_bool(&self) -> Option<bool> {
        match self {
            AttributeValue::Bool(b) => Some(*b),
            _ => None,
        }
    }

    pub fn as_number(&self) -> Option<f64> {
        match self {
            AttributeValue::Number(n) => Some(*n),
            _ => None,
        }
    }
}

impl From<String> for AttributeValue {
    fn from(s: String) -> Self {
        AttributeValue::String(s)
    }
}

impl From<&str> for AttributeValue {
    fn from(s: &str) -> Self {
        AttributeValue::String(s.to_string())
    }
}

impl From<bool> for AttributeValue {
    fn from(b: bool) -> Self {
        AttributeValue::Bool(b)
    }
}

impl From<f64> for AttributeValue {
    fn from(n: f64) -> Self {
        AttributeValue::Number(n)
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AttributeRecord {
    pub value: AttributeValue,
    pub timestamp: i64,
    pub client_id: u64,
}

impl AttributeRecord {
    pub fn new(value: AttributeValue, timestamp: i64, client_id: u64) -> Self {
        Self {
            value,
            timestamp,
            client_id,
        }
    }

    pub fn compare_lww(&self, other: &AttributeRecord) -> std::cmp::Ordering {
        other
            .timestamp
            .cmp(&self.timestamp)
            .then_with(|| other.client_id.cmp(&self.client_id))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InsertOp {
    pub id: YataId,
    pub left_origin: Origin,
    pub right_origin: Origin,
    pub content: char,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeleteOp {
    pub id: YataId,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FormatOp {
    pub range_start: YataId,
    pub range_end: YataId,
    pub key: String,
    pub value: AttributeValue,
    pub timestamp: i64,
    pub format_id: YataId,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum OpType {
    Insert(InsertOp),
    Delete(DeleteOp),
    Format(FormatOp),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Op {
    pub op_type: OpType,
    pub document_id: Uuid,
    pub client_id: u64,
    pub timestamp: i64,
}

impl Op {
    pub fn insert(
        document_id: Uuid,
        client_id: u64,
        id: YataId,
        left_origin: Origin,
        right_origin: Origin,
        content: char,
    ) -> Self {
        Self {
            op_type: OpType::Insert(InsertOp {
                id,
                left_origin,
                right_origin,
                content,
            }),
            document_id,
            client_id,
            timestamp: chrono::Utc::now().timestamp_millis(),
        }
    }

    pub fn delete(document_id: Uuid, client_id: u64, id: YataId) -> Self {
        Self {
            op_type: OpType::Delete(DeleteOp { id }),
            document_id,
            client_id,
            timestamp: chrono::Utc::now().timestamp_millis(),
        }
    }

    pub fn format(
        document_id: Uuid,
        client_id: u64,
        format_id: YataId,
        range_start: YataId,
        range_end: YataId,
        key: String,
        value: AttributeValue,
        timestamp: i64,
    ) -> Self {
        Self {
            op_type: OpType::Format(FormatOp {
                range_start,
                range_end,
                key,
                value,
                timestamp,
                format_id,
            }),
            document_id,
            client_id,
            timestamp,
        }
    }

    pub fn yata_id(&self) -> YataId {
        match &self.op_type {
            OpType::Insert(ins) => ins.id,
            OpType::Delete(del) => del.id,
            OpType::Format(fmt) => fmt.format_id,
        }
    }

    pub fn dedup_key(&self) -> (u64, u32) {
        let id = self.yata_id();
        (id.client, id.clock)
    }

    pub fn op_type_str(&self) -> &'static str {
        match &self.op_type {
            OpType::Insert(_) => "insert",
            OpType::Delete(_) => "delete",
            OpType::Format(_) => "format",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum CrdtEvent {
    OpApplied {
        op: Op,
        content_length: usize,
        ops_count: u64,
    },
    ContentChanged {
        new_length: usize,
    },
    AttributeChanged {
        key: String,
        value: AttributeValue,
        timestamp: i64,
        client_id: u64,
    },
    SnapshotTaken {
        ops_count: u64,
    },
    TombstonesPruned {
        count: usize,
        remaining_tombstones: usize,
    },
}

#[derive(Debug, Clone)]
struct Item {
    id: YataId,
    content: char,
    left: Option<YataId>,
    right: Option<YataId>,
    deleted: bool,
    pruned: bool,
    attributes: BTreeMap<String, AttributeRecord>,
}

impl Item {
    fn new(id: YataId, content: char, left: Option<YataId>, right: Option<YataId>) -> Self {
        Self {
            id,
            content,
            left,
            right,
            deleted: false,
            pruned: false,
            attributes: BTreeMap::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CrdtSnapshot {
    pub document_id: Uuid,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub vector_clock: BTreeMap<u64, u32>,
    pub items: Vec<SnapshotItem>,
    pub ops_count: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SnapshotItem {
    pub id: YataId,
    pub content: char,
    pub left: Option<YataId>,
    pub right: Option<YataId>,
    pub deleted: bool,
    #[serde(default)]
    pub pruned: bool,
    #[serde(default)]
    pub attributes: BTreeMap<String, AttributeRecord>,
}

#[derive(Debug)]
pub struct YataDocument {
    document_id: Uuid,
    client_id: u64,
    local_clock: u32,
    items: BTreeMap<YataId, Item>,
    vector_clock: BTreeMap<u64, u32>,
    start_id: YataId,
    end_id: YataId,
    start_content: Vec<YataId>,
    ops_count: u64,
    cache_valid: bool,
    content_cache: String,
    event_tx: EventSender<CrdtEvent>,
}

impl YataDocument {
    pub fn new(document_id: Uuid, client_id: u64) -> Self {
        let (event_tx, _) = broadcast::channel(EVENT_CHANNEL_CAPACITY);
        Self::new_with_channel(document_id, client_id, event_tx)
    }

    fn new_with_channel(
        document_id: Uuid,
        client_id: u64,
        event_tx: EventSender<CrdtEvent>,
    ) -> Self {
        let mut items = BTreeMap::new();

        let start_id = YataId::root();
        let end_id = YataId::new(0, 1);

        items.insert(
            start_id,
            Item::new(start_id, '\0', None, Some(end_id)),
        );
        items.insert(
            end_id,
            Item::new(end_id, '\0', Some(start_id), None),
        );

        let mut vector_clock = BTreeMap::new();
        vector_clock.insert(0, 2);

        Self {
            document_id,
            client_id,
            local_clock: 0,
            items,
            vector_clock,
            start_id,
            end_id,
            start_content: vec![],
            ops_count: 0,
            cache_valid: false,
            content_cache: String::new(),
            event_tx,
        }
    }

    pub fn event_sender(&self) -> EventSender<CrdtEvent> {
        self.event_tx.clone()
    }

    pub fn subscribe(&self) -> EventReceiver<CrdtEvent> {
        self.event_tx.subscribe()
    }

    fn emit_event(&self, event: CrdtEvent) {
        let _ = self.event_tx.send(event);
    }

    pub fn new_with_client(document_id: Uuid, client_id: u64) -> Self {
        Self::new(document_id, client_id)
    }

    pub fn document_id(&self) -> Uuid {
        self.document_id
    }

    pub fn client_id(&self) -> u64 {
        self.client_id
    }

    pub fn set_client_id(&mut self, client_id: u64) {
        self.client_id = client_id;
    }

    pub fn ops_count(&self) -> u64 {
        self.ops_count
    }

    pub fn vector_clock(&self) -> &BTreeMap<u64, u32> {
        &self.vector_clock
    }

    fn next_id(&mut self) -> YataId {
        self.local_clock += 1;
        let id = YataId::new(self.client_id, self.local_clock);
        self.vector_clock
            .entry(self.client_id)
            .and_modify(|c| *c = (*c).max(self.local_clock))
            .or_insert(self.local_clock);
        id
    }

    pub fn insert_local(&mut self, position: usize, content: char) -> CrdtResult<Op> {
        let left_id = self.find_position(position)?.unwrap_or(self.start_id);

        let left_item = self.items.get(&left_id).ok_or(CrdtError::IdNotFound(left_id.client, left_id.clock))?;
        let right_id = left_item.right;

        let id = self.next_id();
        let left_origin: Origin = left_id.into();
        let right_origin: Origin = right_id.into();

        let op = Op::insert(
            self.document_id,
            self.client_id,
            id,
            left_origin,
            right_origin,
            content,
        );

        self.apply_insert(id, left_id, right_id.unwrap_or(self.end_id), content)?;
        self.cache_valid = false;

        self.emit_event(CrdtEvent::OpApplied {
            op: op.clone(),
            content_length: self.content_length(),
            ops_count: self.ops_count,
        });
        self.emit_event(CrdtEvent::ContentChanged {
            new_length: self.content_length(),
        });

        Ok(op)
    }

    pub fn delete_local(&mut self, position: usize) -> CrdtResult<Op> {
        let id = self
            .find_position(position)?
            .ok_or_else(|| CrdtError::Invalid("Cannot delete end marker".into()))?;

        let op = Op::delete(self.document_id, self.client_id, id);
        self.apply_delete(id)?;
        self.cache_valid = false;

        self.emit_event(CrdtEvent::OpApplied {
            op: op.clone(),
            content_length: self.content_length(),
            ops_count: self.ops_count,
        });
        self.emit_event(CrdtEvent::ContentChanged {
            new_length: self.content_length(),
        });

        Ok(op)
    }

    pub fn format_local(
        &mut self,
        start_position: usize,
        end_position: usize,
        key: String,
        value: AttributeValue,
    ) -> CrdtResult<Op> {
        if start_position >= end_position {
            return Err(CrdtError::Invalid("Empty format range".into()));
        }
        if end_position > self.content_length() {
            return Err(CrdtError::Invalid("End position out of bounds".into()));
        }

        let range_start = self
            .find_position(start_position)?
            .ok_or_else(|| CrdtError::Invalid("Invalid start position".into()))?;
        let range_end = self
            .find_position(end_position)?
            .ok_or_else(|| CrdtError::Invalid("Invalid end position".into()))?;

        let format_id = self.next_id();
        let timestamp = chrono::Utc::now().timestamp_millis();

        let fmt_op = FormatOp {
            range_start,
            range_end,
            key: key.clone(),
            value: value.clone(),
            timestamp,
            format_id,
        };

        self.apply_format(&fmt_op)?;

        let op = Op::format(
            self.document_id,
            self.client_id,
            format_id,
            range_start,
            range_end,
            key,
            value.clone(),
            timestamp,
        );

        self.cache_valid = false;

        self.emit_event(CrdtEvent::OpApplied {
            op: op.clone(),
            content_length: self.content_length(),
            ops_count: self.ops_count,
        });
        self.emit_event(CrdtEvent::AttributeChanged {
            key: fmt_op.key.clone(),
            value,
            timestamp,
            client_id: self.client_id,
        });

        Ok(op)
    }

    pub fn get_attribute_at(&self, position: usize, key: &str) -> Option<&AttributeRecord> {
        let id = self.find_position(position + 1).ok()??;
        let item = self.items.get(&id)?;
        item.attributes.get(key)
    }

    pub fn get_all_attributes_at(&self, position: usize) -> Option<&BTreeMap<String, AttributeRecord>> {
        let id = self.find_position(position + 1).ok()??;
        let item = self.items.get(&id)?;
        Some(&item.attributes)
    }

    pub fn format_runs(&self, key: &str) -> Vec<(usize, usize, Option<&AttributeRecord>)> {
        let mut runs = Vec::new();
        let mut current_pos = 0usize;
        let mut current_val: Option<&AttributeRecord> = None;
        let mut run_start = 0usize;

        let mut current = Some(self.start_id);
        while let Some(id) = current {
            if let Some(item) = self.items.get(&id) {
                if id != self.start_id && id != self.end_id && !item.deleted {
                    let val = item.attributes.get(key);
                    if val != current_val {
                        if current_pos > run_start {
                            runs.push((run_start, current_pos, current_val));
                        }
                        run_start = current_pos;
                        current_val = val;
                    }
                    current_pos += 1;
                }
                current = item.right;
            } else {
                break;
            }
        }

        if current_pos > run_start {
            runs.push((run_start, current_pos, current_val));
        }
        runs
    }

    pub fn apply_op(&mut self, op: &Op) -> CrdtResult<()> {
        if op.document_id != self.document_id {
            return Err(CrdtError::Invalid(format!(
                "Document ID mismatch"
            )));
        }

        let remote_clock = self.vector_clock
            .get(&op.client_id)
            .copied()
            .unwrap_or(0);

        if op.yata_id().clock > remote_clock {
            self.vector_clock.insert(op.client_id, op.yata_id().clock);
        }

        let content_changed = matches!(&op.op_type, OpType::Insert(_) | OpType::Delete(_));
        let attr_changed = if let OpType::Format(fmt) = &op.op_type {
            Some((fmt.key.clone(), fmt.value.clone(), fmt.timestamp))
        } else {
            None
        };

        match &op.op_type {
            OpType::Insert(ins) => {
                let left = ins.left_origin.to_option().unwrap_or(self.start_id);
                let right = ins.right_origin.to_option().unwrap_or(self.end_id);
                self.apply_insert(ins.id, left, right, ins.content)?;
            }
            OpType::Delete(del) => {
                self.apply_delete(del.id)?;
            }
            OpType::Format(fmt) => {
                self.apply_format(fmt)?;
            }
        }

        self.ops_count += 1;
        self.cache_valid = false;

        self.emit_event(CrdtEvent::OpApplied {
            op: op.clone(),
            content_length: self.content_length(),
            ops_count: self.ops_count,
        });

        if content_changed {
            self.emit_event(CrdtEvent::ContentChanged {
                new_length: self.content_length(),
            });
        }
        if let Some((key, value, ts)) = attr_changed {
            self.emit_event(CrdtEvent::AttributeChanged {
                key,
                value,
                timestamp: ts,
                client_id: op.client_id,
            });
        }

        Ok(())
    }

    fn apply_insert(
        &mut self,
        id: YataId,
        left_id: YataId,
        right_id: YataId,
        content: char,
    ) -> CrdtResult<()> {
        if self.items.contains_key(&id) {
            return Ok(());
        }

        let mut scanning_left = left_id;
        let mut scanning_right = right_id;

        loop {
            let left_item = self.items.get(&scanning_left)
                .ok_or(CrdtError::IdNotFound(scanning_left.client, scanning_left.clock))?;
            let right = left_item.right;

            match right {
                None => break,
                Some(r) if r == scanning_right => break,
                Some(r) => {
                    if r < id {
                        scanning_left = r;
                    } else {
                        scanning_right = r;
                    }
                }
            }
        }

        let new_item = Item::new(id, content, Some(scanning_left), Some(scanning_right));
        self.items.insert(id, new_item);

        if let Some(left_item) = self.items.get_mut(&scanning_left) {
            left_item.right = Some(id);
        }
        if let Some(right_item) = self.items.get_mut(&scanning_right) {
            right_item.left = Some(id);
        }

        self.ops_count += 1;
        Ok(())
    }

    fn apply_delete(&mut self, id: YataId) -> CrdtResult<()> {
        if let Some(item) = self.items.get_mut(&id) {
            item.deleted = true;
            self.ops_count += 1;
        }
        Ok(())
    }

    fn apply_format(&mut self, fmt: &FormatOp) -> CrdtResult<()> {
        if !self.items.contains_key(&fmt.range_start) {
            return Err(CrdtError::IdNotFound(fmt.range_start.client, fmt.range_start.clock));
        }
        if !self.items.contains_key(&fmt.range_end) {
            return Err(CrdtError::IdNotFound(fmt.range_end.client, fmt.range_end.clock));
        }

        let new_record = AttributeRecord::new(
            fmt.value.clone(),
            fmt.timestamp,
            fmt.format_id.client,
        );

        let mut current = Some(fmt.range_start);
        while let Some(id) = current {
            if id == fmt.range_end {
                break;
            }

            if let Some(item) = self.items.get_mut(&id) {
                if id != self.start_id && id != self.end_id && !item.deleted {
                    let should_apply = match item.attributes.get(&fmt.key) {
                        None => true,
                        Some(existing) => {
                            new_record.compare_lww(existing).is_lt()
                        }
                    };
                    if should_apply {
                        item.attributes.insert(fmt.key.clone(), new_record.clone());
                    }
                }
                current = item.right;
            } else {
                break;
            }
        }

        self.ops_count += 1;
        Ok(())
    }

    fn find_position(&self, position: usize) -> CrdtResult<Option<YataId>> {
        let mut count = 0usize;
        let mut current = Some(self.start_id);

        while let Some(id) = current {
            let item = self.items.get(&id)
                .ok_or(CrdtError::IdNotFound(id.client, id.clock))?;

            if id != self.start_id && id != self.end_id && !item.deleted {
                if count == position {
                    return Ok(Some(id));
                }
                count += 1;
            }
            current = item.right;
        }

        if position == count {
            let mut last_id = self.start_id;
            let mut current = Some(self.start_id);
            while let Some(id) = current {
                if id != self.end_id {
                    last_id = id;
                }
                let item = self.items.get(&id)
                    .ok_or(CrdtError::IdNotFound(id.client, id.clock))?;
                current = item.right;
            }
            return Ok(Some(last_id));
        }

        Err(CrdtError::Invalid(format!("Position {} out of bounds (len {})", position, count)))
    }

    pub fn get_content(&mut self) -> &str {
        if !self.cache_valid {
            self.rebuild_content_cache();
        }
        &self.content_cache
    }

    fn rebuild_content_cache(&mut self) {
        let mut content = String::new();
        let mut current = Some(self.start_id);

        while let Some(id) = current {
            if let Some(item) = self.items.get(&id) {
                if id != self.start_id && id != self.end_id && !item.deleted {
                    content.push(item.content);
                }
                current = item.right;
            } else {
                break;
            }
        }

        self.content_cache = content;
        self.cache_valid = true;
    }

    pub fn content_length(&self) -> usize {
        self.items.values()
            .filter(|i| i.id != self.start_id && i.id != self.end_id && !i.deleted)
            .count()
    }

    pub fn tombstone_count(&self) -> usize {
        self.items.values()
            .filter(|i| i.id != self.start_id && i.id != self.end_id && i.deleted)
            .count()
    }

    pub fn pruned_count(&self) -> usize {
        self.items.values()
            .filter(|i| i.id != self.start_id && i.id != self.end_id && i.pruned)
            .count()
    }

    pub fn prune_tombstones(&mut self) -> usize {
        let mut count = 0usize;
        for item in self.items.values_mut() {
            if item.id != self.start_id && item.id != self.end_id && item.deleted && !item.pruned {
                item.attributes.clear();
                item.pruned = true;
                count += 1;
            }
        }
        if count > 0 {
            let remaining = self.tombstone_count();
            self.emit_event(CrdtEvent::TombstonesPruned {
                count,
                remaining_tombstones: remaining,
            });
        }
        count
    }

    pub fn snapshot(&self) -> CrdtSnapshot {
        let items: Vec<SnapshotItem> = self.items.values()
            .map(|item| SnapshotItem {
                id: item.id,
                content: item.content,
                left: item.left,
                right: item.right,
                deleted: item.deleted,
                pruned: item.pruned,
                attributes: item.attributes.clone(),
            })
            .collect();

        let snap = CrdtSnapshot {
            document_id: self.document_id,
            created_at: chrono::Utc::now(),
            vector_clock: self.vector_clock.clone(),
            items,
            ops_count: self.ops_count,
        };

        self.emit_event(CrdtEvent::SnapshotTaken {
            ops_count: self.ops_count,
        });

        snap
    }

    pub fn from_snapshot(snapshot: CrdtSnapshot) -> CrdtResult<Self> {
        let mut items = BTreeMap::new();

        for si in snapshot.items {
            items.insert(
                si.id,
                Item {
                    id: si.id,
                    content: si.content,
                    left: si.left,
                    right: si.right,
                    deleted: si.deleted,
                    pruned: si.pruned,
                    attributes: si.attributes,
                },
            );
        }

        let start_id = YataId::root();
        let end_id = YataId::new(0, 1);

        let local_clock = snapshot.vector_clock.values().copied().max().unwrap_or(0);
        let (event_tx, _) = broadcast::channel(EVENT_CHANNEL_CAPACITY);

        Ok(Self {
            document_id: snapshot.document_id,
            client_id: 0,
            local_clock,
            items,
            vector_clock: snapshot.vector_clock,
            start_id,
            end_id,
            start_content: vec![],
            ops_count: snapshot.ops_count,
            cache_valid: false,
            content_cache: String::new(),
            event_tx,
        })
    }

    pub fn serialize(&self) -> CrdtResult<Vec<u8>> {
        let snapshot = self.snapshot();
        bincode::serialize(&snapshot)
            .map_err(|e| CrdtError::Snapshot(e.to_string()))
    }

    pub fn deserialize(data: &[u8]) -> CrdtResult<Self> {
        let snapshot: CrdtSnapshot = bincode::deserialize(data)
            .map_err(|e| CrdtError::Decode(e.to_string()))?;
        Self::from_snapshot(snapshot)
    }

    pub fn missing_ops(&self, remote_clock: &BTreeMap<u64, u32>) -> Vec<(u64, u32)> {
        let mut missing: Vec<(u64, std::ops::RangeInclusive<u32>)> = Vec::new();
        for (client, clock) in &self.vector_clock {
            let remote = remote_clock.get(client).copied().unwrap_or(0);
            if *clock > remote {
                missing.push((*client, remote + 1..=*clock));
            }
        }
        missing.iter().flat_map(|(c, r)| r.clone().map(move |clk| (*c, clk))).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_simple_insert() {
        let doc_id = Uuid::new_v4();
        let mut doc = YataDocument::new(doc_id, 1);

        doc.insert_local(0, 'H').unwrap();
        doc.insert_local(1, 'i').unwrap();

        assert_eq!(doc.get_content(), "Hi");
    }

    #[test]
    fn test_multiple_inserts() {
        let doc_id = Uuid::new_v4();
        let mut doc = YataDocument::new(doc_id, 1);

        for c in "Hello World".chars() {
            doc.insert_local(doc.content_length(), c).unwrap();
        }

        assert_eq!(doc.get_content(), "Hello World");
    }

    #[test]
    fn test_delete() {
        let doc_id = Uuid::new_v4();
        let mut doc = YataDocument::new(doc_id, 1);

        for c in "Hello World".chars() {
            doc.insert_local(doc.content_length(), c).unwrap();
        }

        doc.delete_local(5).unwrap();
        assert_eq!(doc.get_content(), "HelloWorld");
    }

    #[test]
    fn test_concurrent_inserts_two_clients() {
        let doc_id = Uuid::new_v4();
        let mut doc_a = YataDocument::new(doc_id, 1);
        let mut doc_b = YataDocument::new(doc_id, 2);

        let ops_a: Vec<Op> = "Hello".chars()
            .enumerate()
            .map(|(i, c)| doc_a.insert_local(i, c).unwrap())
            .collect();

        let ops_b: Vec<Op> = "World".chars()
            .enumerate()
            .map(|(i, c)| doc_b.insert_local(i, c).unwrap())
            .collect();

        for op in &ops_a {
            doc_b.apply_op(op).unwrap();
        }

        for op in &ops_b {
            doc_a.apply_op(op).unwrap();
        }

        let content_a = doc_a.get_content().clone();
        let content_b = doc_b.get_content().clone();

        assert_eq!(content_a, content_b);
    }

    #[test]
    fn test_snapshot_restore() {
        let doc_id = Uuid::new_v4();
        let mut doc = YataDocument::new(doc_id, 1);

        for c in "Snapshot Test".chars() {
            doc.insert_local(doc.content_length(), c).unwrap();
        }

        let snapshot = doc.snapshot();
        let restored = YataDocument::from_snapshot(snapshot).unwrap();

        assert_eq!(restored.content_length(), doc.content_length());
    }

    #[test]
    fn test_snapshot_backward_compatibility() {
        let doc_id = Uuid::new_v4();
        let mut doc = YataDocument::new(doc_id, 1);

        for c in "Hello".chars() {
            doc.insert_local(doc.content_length(), c).unwrap();
        }

        let snapshot = doc.snapshot();
        let json = serde_json::to_string(&snapshot.items).unwrap();

        let items_no_attrs: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap();
        let items_no_attrs: Vec<serde_json::Value> = items_no_attrs
            .into_iter()
            .map(|mut item| {
                item.as_object_mut().unwrap().remove("attributes");
                item
            })
            .collect();

        let json_no_attrs = serde_json::to_string(&items_no_attrs).unwrap();
        let items: Vec<SnapshotItem> = serde_json::from_str(&json_no_attrs).unwrap();

        for item in &items {
            assert!(item.attributes.is_empty());
        }

        let mut restored = YataDocument::from_snapshot(CrdtSnapshot {
            document_id: doc_id,
            created_at: chrono::Utc::now(),
            vector_clock: BTreeMap::new(),
            items,
            ops_count: 5,
        }).unwrap();

        assert_eq!(restored.get_content(), "Hello");
    }

    #[test]
    fn test_format_basic() {
        let doc_id = Uuid::new_v4();
        let mut doc = YataDocument::new(doc_id, 1);

        for c in "Hello".chars() {
            doc.insert_local(doc.content_length(), c).unwrap();
        }

        doc.format_local(0, 5, "bold".into(), AttributeValue::Bool(true)).unwrap();

        let attr = doc.get_attribute_at(2, "bold").unwrap();
        assert_eq!(attr.value, AttributeValue::Bool(true));
        assert_eq!(attr.client_id, 1);
    }

    #[test]
    fn test_format_lww_converges() {
        let doc_id = Uuid::new_v4();
        let mut doc_a = YataDocument::new(doc_id, 1);
        let mut doc_b = YataDocument::new(doc_id, 2);

        let ops_a: Vec<Op> = "Hello".chars()
            .enumerate()
            .map(|(i, c)| doc_a.insert_local(i, c).unwrap())
            .collect();

        for op in &ops_a {
            doc_b.apply_op(op).unwrap();
        }

        assert_eq!(doc_a.get_content(), doc_b.get_content());
        assert_eq!(doc_a.content_length(), 5);

        let fmt_a = doc_a.format_local(
            0,
            5,
            "bold".into(),
            AttributeValue::Bool(true),
        ).unwrap();

        let fmt_b = doc_b.format_local(
            0,
            5,
            "bold".into(),
            AttributeValue::Bool(false),
        ).unwrap();

        doc_a.apply_op(&fmt_b).unwrap();
        doc_b.apply_op(&fmt_a).unwrap();

        let attr_a = doc_a.get_attribute_at(2, "bold").unwrap();
        let attr_b = doc_b.get_attribute_at(2, "bold").unwrap();
        assert_eq!(attr_a.value, attr_b.value);
    }

    #[tokio::test]
    async fn test_event_broadcast() {
        let doc_id = Uuid::new_v4();
        let mut doc = YataDocument::new(doc_id, 1);
        let mut rx = doc.subscribe();

        doc.insert_local(0, 'H').unwrap();

        let event = tokio::time::timeout(
            std::time::Duration::from_secs(1),
            rx.recv(),
        ).await.unwrap().unwrap();

        match event {
            CrdtEvent::OpApplied { op, .. } => {
                assert_eq!(op.client_id, 1);
                assert!(matches!(op.op_type, OpType::Insert(_)));
            }
            _ => panic!("Expected OpApplied event"),
        }
    }

    #[tokio::test]
    async fn test_multiple_subscribers() {
        let doc_id = Uuid::new_v4();
        let mut doc = YataDocument::new(doc_id, 1);
        let mut rx1 = doc.subscribe();
        let mut rx2 = doc.subscribe();

        doc.insert_local(0, 'X').unwrap();

        let e1 = tokio::time::timeout(
            std::time::Duration::from_secs(1),
            rx1.recv(),
        ).await.unwrap().unwrap();
        let e2 = tokio::time::timeout(
            std::time::Duration::from_secs(1),
            rx2.recv(),
        ).await.unwrap().unwrap();

        assert!(matches!(e1, CrdtEvent::OpApplied { .. }));
        assert!(matches!(e2, CrdtEvent::OpApplied { .. }));
    }

    #[test]
    fn test_tombstone_pruning() {
        let doc_id = Uuid::new_v4();
        let mut doc = YataDocument::new(doc_id, 1);

        for c in "Hello World".chars() {
            doc.insert_local(doc.content_length(), c).unwrap();
        }

        assert_eq!(doc.get_content(), "Hello World");
        assert_eq!(doc.tombstone_count(), 0);
        assert_eq!(doc.pruned_count(), 0);

        doc.delete_local(5).unwrap();
        assert_eq!(doc.get_content(), "HelloWorld");
        assert_eq!(doc.tombstone_count(), 1);
        assert_eq!(doc.pruned_count(), 0);

        let pruned = doc.prune_tombstones();
        assert_eq!(pruned, 1);
        assert_eq!(doc.tombstone_count(), 1);
        assert_eq!(doc.pruned_count(), 1);
        assert_eq!(doc.get_content(), "HelloWorld");

        doc.insert_local(doc.content_length(), '!').unwrap();
        assert_eq!(doc.get_content(), "HelloWorld!");
        assert_eq!(doc.tombstone_count(), 1);

        doc.delete_local(0).unwrap();
        assert_eq!(doc.tombstone_count(), 2);
        assert_eq!(doc.pruned_count(), 1);

        let pruned2 = doc.prune_tombstones();
        assert_eq!(pruned2, 1);
        assert_eq!(doc.tombstone_count(), 2);
        assert_eq!(doc.pruned_count(), 2);
        assert_eq!(doc.get_content(), "elloWorld!");
    }

    #[test]
    fn test_pruning_does_not_break_inserts() {
        let doc_id = Uuid::new_v4();
        let mut doc_a = YataDocument::new(doc_id, 1);
        let mut doc_b = YataDocument::new(doc_id, 2);

        let insert_ops: Vec<Op> = "ABC".chars()
            .enumerate()
            .map(|(i, c)| doc_a.insert_local(i, c).unwrap())
            .collect();

        for op in &insert_ops {
            doc_b.apply_op(op).unwrap();
        }

        assert_eq!(doc_a.get_content(), doc_b.get_content());
        assert_eq!(doc_a.get_content(), "ABC");

        let delete_op = doc_a.delete_local(1).unwrap();
        assert_eq!(doc_a.get_content(), "AC");
        assert_eq!(doc_a.tombstone_count(), 1);

        let pruned = doc_a.prune_tombstones();
        assert_eq!(pruned, 1);
        assert_eq!(doc_a.pruned_count(), 1);
        assert_eq!(doc_a.get_content(), "AC");

        doc_b.apply_op(&delete_op).unwrap();
        assert_eq!(doc_b.get_content(), "AC");

        let insert_after_pruned = doc_a.insert_local(doc_a.content_length(), 'X').unwrap();
        assert_eq!(doc_a.get_content(), "ACX");

        doc_b.apply_op(&insert_after_pruned).unwrap();
        assert_eq!(doc_b.get_content(), "ACX");
        assert_eq!(doc_a.get_content(), doc_b.get_content());

        let snapshot = doc_a.snapshot();
        let restored = YataDocument::from_snapshot(snapshot).unwrap();
        assert_eq!(restored.content_length(), doc_a.content_length());
        assert_eq!(restored.pruned_count(), 1);

        let mut restored_mut = restored;
        assert_eq!(restored_mut.get_content(), "ACX");

        let insert_on_pruned_doc = doc_a.insert_local(1, 'Y').unwrap();
        let mut doc_a_content_after = doc_a.get_content().clone();

        let mut doc_c = YataDocument::new(doc_id, 3);
        for op in &insert_ops {
            doc_c.apply_op(op).unwrap();
        }
        doc_c.apply_op(&delete_op).unwrap();
        doc_c.apply_op(&insert_after_pruned).unwrap();
        doc_c.apply_op(&insert_on_pruned_doc).unwrap();
        assert_eq!(doc_c.get_content(), doc_a_content_after);
    }

    #[test]
    fn test_snapshot_pruned_backward_compatibility() {
        let doc_id = Uuid::new_v4();
        let mut doc = YataDocument::new(doc_id, 1);

        for c in "Hello".chars() {
            doc.insert_local(doc.content_length(), c).unwrap();
        }

        doc.delete_local(2).unwrap();
        doc.prune_tombstones();

        let snapshot = doc.snapshot();
        let json = serde_json::to_string(&snapshot.items).unwrap();

        let items_no_pruned: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap();
        let items_no_pruned: Vec<serde_json::Value> = items_no_pruned
            .into_iter()
            .map(|mut item| {
                item.as_object_mut().unwrap().remove("pruned");
                item
            })
            .collect();

        let json_no_pruned = serde_json::to_string(&items_no_pruned).unwrap();
        let items: Vec<SnapshotItem> = serde_json::from_str(&json_no_pruned).unwrap();

        for item in &items {
            assert!(!item.pruned);
        }

        let restored = YataDocument::from_snapshot(CrdtSnapshot {
            document_id: doc_id,
            created_at: chrono::Utc::now(),
            vector_clock: BTreeMap::new(),
            items,
            ops_count: 6,
        }).unwrap();

        assert_eq!(restored.pruned_count(), 0);
    }

    #[tokio::test]
    async fn test_prune_event_broadcast() {
        let doc_id = Uuid::new_v4();
        let mut doc = YataDocument::new(doc_id, 1);
        let mut rx = doc.subscribe();

        for c in "AB".chars() {
            doc.insert_local(doc.content_length(), c).unwrap();
        }
        doc.delete_local(0).unwrap();

        while let Ok(_) = rx.try_recv() {}

        let pruned = doc.prune_tombstones();
        assert_eq!(pruned, 1);

        let event = tokio::time::timeout(
            std::time::Duration::from_secs(1),
            rx.recv(),
        ).await.unwrap().unwrap();

        match event {
            CrdtEvent::TombstonesPruned { count, remaining_tombstones } => {
                assert_eq!(count, 1);
                assert_eq!(remaining_tombstones, 1);
            }
            _ => panic!("Expected TombstonesPruned event"),
        }
    }
}
