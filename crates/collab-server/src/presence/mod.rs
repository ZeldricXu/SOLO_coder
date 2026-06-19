use std::collections::HashMap;

use dashmap::{DashMap, DashSet};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct CursorPosition {
    pub line: u32,
    pub column: u32,
    pub index: usize,
    pub updated_at: i64,
}

impl CursorPosition {
    pub fn new(line: u32, column: u32, index: usize) -> Self {
        Self {
            line,
            column,
            index,
            updated_at: chrono::Utc::now().timestamp_millis(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SelectionRange {
    pub start: CursorPosition,
    pub end: CursorPosition,
    pub direction: SelectionDirection,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum SelectionDirection {
    Forward,
    Backward,
    None,
}

impl SelectionRange {
    pub fn new(start: CursorPosition, end: CursorPosition) -> Self {
        let direction = if start.index < end.index {
            SelectionDirection::Forward
        } else if start.index > end.index {
            SelectionDirection::Backward
        } else {
            SelectionDirection::None
        };
        Self {
            start,
            end,
            direction,
            updated_at: chrono::Utc::now().timestamp_millis(),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.start.index == self.end.index
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum PresenceUpdate {
    Joined {
        user_id: String,
        display_name: Option<String>,
        color: String,
        avatar: Option<String>,
        session_id: Uuid,
    },
    Left {
        user_id: String,
        session_id: Uuid,
    },
    Typing {
        user_id: String,
        is_typing: bool,
    },
    Idle {
        user_id: String,
        is_idle: bool,
    },
}

#[derive(Debug, Clone)]
pub struct UserPresence {
    pub user_id: String,
    pub display_name: Option<String>,
    pub color: String,
    pub avatar: Option<String>,
    pub session_ids: DashSet<Uuid>,
    pub cursor: Option<CursorPosition>,
    pub selection: Option<SelectionRange>,
    pub is_typing: bool,
    pub is_idle: bool,
    pub last_seen: i64,
}

impl UserPresence {
    fn new(user_id: String, display_name: Option<String>, color: String, avatar: Option<String>) -> Self {
        Self {
            user_id,
            display_name,
            color,
            avatar,
            session_ids: DashSet::new(),
            cursor: None,
            selection: None,
            is_typing: false,
            is_idle: false,
            last_seen: chrono::Utc::now().timestamp_millis(),
        }
    }

    fn touch(&mut self) {
        self.last_seen = chrono::Utc::now().timestamp_millis();
    }
}

#[derive(Debug, Clone, Default)]
pub struct DocumentPresence {
    pub document_id: Uuid,
    pub users: DashMap<String, UserPresence>,
    pub last_activity: i64,
}

impl DocumentPresence {
    fn new(document_id: Uuid) -> Self {
        Self {
            document_id,
            users: DashMap::new(),
            last_activity: chrono::Utc::now().timestamp_millis(),
        }
    }

    fn user_joined(
        &self,
        user_id: String,
        display_name: Option<String>,
        color: String,
        avatar: Option<String>,
        session_id: Uuid,
    ) -> bool {
        let is_new = !self.users.contains_key(&user_id);
        let mut user = self.users.entry(user_id.clone())
            .or_insert_with(|| UserPresence::new(user_id, display_name, color, avatar));
        user.session_ids.insert(session_id);
        user.touch();
        drop(user);
        is_new
    }

    fn user_left(&self, user_id: &str, session_id: &Uuid) -> bool {
        if let Some(mut user) = self.users.get_mut(user_id) {
            user.session_ids.remove(session_id);
            if user.session_ids.is_empty() {
                drop(user);
                self.users.remove(user_id);
                return true;
            }
        }
        false
    }

    fn update_cursor(&self, user_id: &str, cursor: CursorPosition) -> bool {
        if let Some(mut user) = self.users.get_mut(user_id) {
            user.cursor = Some(cursor);
            user.touch();
            true
        } else {
            false
        }
    }

    fn update_selection(&self, user_id: &str, selection: SelectionRange) -> bool {
        if let Some(mut user) = self.users.get_mut(user_id) {
            user.selection = Some(selection);
            user.touch();
            true
        } else {
            false
        }
    }

    fn set_typing(&self, user_id: &str, is_typing: bool) -> bool {
        if let Some(mut user) = self.users.get_mut(user_id) {
            user.is_typing = is_typing;
            user.touch();
            true
        } else {
            false
        }
    }

    fn set_idle(&self, user_id: &str, is_idle: bool) -> bool {
        if let Some(mut user) = self.users.get_mut(user_id) {
            user.is_idle = is_idle;
            user.touch();
            true
        } else {
            false
        }
    }

    fn online_users(&self) -> Vec<String> {
        self.users.iter().map(|u| u.key().clone()).collect()
    }

    fn cleanup_stale(&self, timeout_ms: i64) -> Vec<String> {
        let now = chrono::Utc::now().timestamp_millis();
        let mut stale = Vec::new();
        self.users.retain(|user_id, user| {
            if now - user.last_seen > timeout_ms {
                stale.push(user_id.clone());
                false
            } else {
                true
            }
        });
        stale
    }
}

#[derive(Debug, Clone)]
pub struct PresenceTracker {
    documents: DashMap<Uuid, DocumentPresence>,
    user_documents: DashMap<String, DashSet<Uuid>>,
    idle_timeout_ms: i64,
}

impl PresenceTracker {
    pub fn new() -> Self {
        Self {
            documents: DashMap::new(),
            user_documents: DashMap::new(),
            idle_timeout_ms: 5 * 60 * 1000,
        }
    }

    pub fn user_joined(
        &self,
        document_id: Uuid,
        user_id: String,
        display_name: Option<String>,
        color: String,
        avatar: Option<String>,
        session_id: Uuid,
    ) -> bool {
        let doc = self.documents.entry(document_id)
            .or_insert_with(|| DocumentPresence::new(document_id));
        let is_new = doc.user_joined(user_id.clone(), display_name, color, avatar, session_id);

        self.user_documents
            .entry(user_id.clone())
            .or_insert_with(DashSet::new)
            .insert(document_id);

        is_new
    }

    pub fn user_left(&self, document_id: Uuid, user_id: &str, session_id: &Uuid) -> bool {
        let mut fully_left = false;
        if let Some(doc) = self.documents.get(&document_id) {
            fully_left = doc.user_left(user_id, session_id);
        }

        if let Some(docs) = self.user_documents.get(user_id) {
            if !self.is_user_active_anywhere(user_id) {
                drop(docs);
                self.user_documents.remove(user_id);
            }
        }

        fully_left
    }

    fn is_user_active_anywhere(&self, user_id: &str) -> bool {
        if let Some(docs) = self.user_documents.get(user_id) {
            for doc_id in docs.iter() {
                if let Some(doc) = self.documents.get(doc_id.key()) {
                    if doc.users.contains_key(user_id) {
                        return true;
                    }
                }
            }
        }
        false
    }

    pub fn update_cursor(&self, document_id: Uuid, user_id: &str, cursor: CursorPosition) -> bool {
        if let Some(doc) = self.documents.get(&document_id) {
            doc.update_cursor(user_id, cursor)
        } else {
            false
        }
    }

    pub fn update_selection(&self, document_id: Uuid, user_id: &str, selection: SelectionRange) -> bool {
        if let Some(doc) = self.documents.get(&document_id) {
            doc.update_selection(user_id, selection)
        } else {
            false
        }
    }

    pub fn set_typing(&self, document_id: Uuid, user_id: &str, is_typing: bool) -> bool {
        if let Some(doc) = self.documents.get(&document_id) {
            doc.set_typing(user_id, is_typing)
        } else {
            false
        }
    }

    pub fn set_idle(&self, document_id: Uuid, user_id: &str, is_idle: bool) -> bool {
        if let Some(doc) = self.documents.get(&document_id) {
            doc.set_idle(user_id, is_idle)
        } else {
            false
        }
    }

    pub fn online_users(&self, document_id: &Uuid) -> Vec<String> {
        if let Some(doc) = self.documents.get(document_id) {
            doc.online_users()
        } else {
            Vec::new()
        }
    }

    pub fn get_user_cursors(&self, document_id: &Uuid) -> HashMap<String, CursorPosition> {
        let mut cursors = HashMap::new();
        if let Some(doc) = self.documents.get(document_id) {
            for entry in doc.users.iter() {
                if let Some(cursor) = &entry.cursor {
                    cursors.insert(entry.key().clone(), cursor.clone());
                }
            }
        }
        cursors
    }

    pub fn get_user_selections(&self, document_id: &Uuid) -> HashMap<String, SelectionRange> {
        let mut selections = HashMap::new();
        if let Some(doc) = self.documents.get(document_id) {
            for entry in doc.users.iter() {
                if let Some(sel) = &entry.selection {
                    selections.insert(entry.key().clone(), sel.clone());
                }
            }
        }
        selections
    }

    pub fn document_presence(&self, document_id: &Uuid) -> Option<DocumentPresence> {
        self.documents.get(document_id).map(|d| d.clone())
    }

    pub fn cleanup_stale(&self) -> usize {
        let mut removed = 0;
        let now = chrono::Utc::now().timestamp_millis();
        for doc in self.documents.iter() {
            removed += doc.cleanup_stale(self.idle_timeout_ms).len();
        }
        removed
    }

    pub fn active_documents_count(&self) -> usize {
        self.documents.len()
    }

    pub fn total_online_users(&self) -> usize {
        self.user_documents.len()
    }
}

impl Default for PresenceTracker {
    fn default() -> Self {
        Self::new()
    }
}
