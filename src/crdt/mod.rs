use std::collections::{BTreeMap, HashMap};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use uuid::Uuid;

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
pub enum OpType {
    Insert(InsertOp),
    Delete(DeleteOp),
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

    pub fn yata_id(&self) -> YataId {
        match &self.op_type {
            OpType::Insert(ins) => ins.id,
            OpType::Delete(del) => del.id,
        }
    }
}

#[derive(Debug, Clone)]
struct Item {
    id: YataId,
    content: char,
    left: Option<YataId>,
    right: Option<YataId>,
    deleted: bool,
}

impl Item {
    fn new(id: YataId, content: char, left: Option<YataId>, right: Option<YataId>) -> Self {
        Self {
            id,
            content,
            left,
            right,
            deleted: false,
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
}

impl YataDocument {
    pub fn new(document_id: Uuid, client_id: u64) -> Self {
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
        }
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
        Ok(op)
    }

    pub fn delete_local(&mut self, position: usize) -> CrdtResult<Op> {
        let id = self
            .find_position(position + 1)?
            .ok_or_else(|| CrdtError::Invalid("Cannot delete end marker".into()))?;

        let op = Op::delete(self.document_id, self.client_id, id);
        self.apply_delete(id)?;
        self.cache_valid = false;
        Ok(op)
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

        match &op.op_type {
            OpType::Insert(ins) => {
                let left = ins.left_origin.to_option().unwrap_or(self.start_id);
                let right = ins.right_origin.to_option().unwrap_or(self.end_id);
                self.apply_insert(ins.id, left, right, ins.content)?;
            }
            OpType::Delete(del) => {
                self.apply_delete(del.id)?;
            }
        }

        self.ops_count += 1;
        self.cache_valid = false;
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

    pub fn snapshot(&self) -> CrdtSnapshot {
        let items: Vec<SnapshotItem> = self.items.values()
            .map(|item| SnapshotItem {
                id: item.id,
                content: item.content,
                left: item.left,
                right: item.right,
                deleted: item.deleted,
            })
            .collect();

        CrdtSnapshot {
            document_id: self.document_id,
            created_at: chrono::Utc::now(),
            vector_clock: self.vector_clock.clone(),
            items,
            ops_count: self.ops_count,
        }
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
                },
            );
        }

        let start_id = YataId::root();
        let end_id = YataId::new(0, 1);

        let local_clock = snapshot.vector_clock.values().copied().max().unwrap_or(0);

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
}
