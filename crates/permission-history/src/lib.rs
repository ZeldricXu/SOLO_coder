use wasm_bindgen::prelude::*;
use serde::{Serialize, Deserialize};
use uuid::Uuid;
use rustc_hash::{FxHashMap, FxHashSet};
use std::collections::VecDeque;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[wasm_bindgen]
pub enum Role {
    ReadOnly = 0,
    Commenter = 1,
    Editor = 2,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[wasm_bindgen]
pub enum OperationType {
    Read = 0,
    Comment = 1,
    Edit = 2,
    Delete = 3,
    Share = 4,
    ManagePermissions = 5,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PermissionMatrix {
    permissions: FxHashMap<Role, FxHashSet<OperationType>>,
}

impl PermissionMatrix {
    pub fn new() -> Self {
        let mut permissions = FxHashMap::default();

        let mut read_only_ops = FxHashSet::default();
        read_only_ops.insert(OperationType::Read);
        permissions.insert(Role::ReadOnly, read_only_ops);

        let mut commenter_ops = FxHashSet::default();
        commenter_ops.insert(OperationType::Read);
        commenter_ops.insert(OperationType::Comment);
        permissions.insert(Role::Commenter, commenter_ops);

        let mut editor_ops = FxHashSet::default();
        editor_ops.insert(OperationType::Read);
        editor_ops.insert(OperationType::Comment);
        editor_ops.insert(OperationType::Edit);
        editor_ops.insert(OperationType::Delete);
        editor_ops.insert(OperationType::Share);
        editor_ops.insert(OperationType::ManagePermissions);
        permissions.insert(Role::Editor, editor_ops);

        Self { permissions }
    }

    pub fn can_perform(&self, role: Role, operation: OperationType) -> bool {
        self.permissions
            .get(&role)
            .map(|ops| ops.contains(&operation))
            .unwrap_or(false)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[wasm_bindgen]
pub struct PermissionChecker {
    matrix: PermissionMatrix,
    user_roles: FxHashMap<String, Role>,
}

#[wasm_bindgen]
impl PermissionChecker {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        Self {
            matrix: PermissionMatrix::new(),
            user_roles: FxHashMap::default(),
        }
    }

    #[wasm_bindgen(js_name = setUserRole)]
    pub fn set_user_role(&mut self, user_id: String, role: Role) {
        self.user_roles.insert(user_id, role);
    }

    #[wasm_bindgen(js_name = getUserRole)]
    pub fn get_user_role(&self, user_id: String) -> Option<u8> {
        self.user_roles.get(&user_id).copied().map(|r| r as u8)
    }

    #[wasm_bindgen(js_name = hasPermission)]
    pub fn has_permission(&self, user_id: String, operation: u8) -> bool {
        let op = match operation {
            0 => OperationType::Read,
            1 => OperationType::Comment,
            2 => OperationType::Edit,
            3 => OperationType::Delete,
            4 => OperationType::Share,
            5 => OperationType::ManagePermissions,
            _ => return false,
        };
        self.user_roles
            .get(&user_id)
            .map(|role| self.matrix.can_perform(*role, op))
            .unwrap_or(false)
    }

    #[wasm_bindgen(js_name = removeUser)]
    pub fn remove_user(&mut self, user_id: String) -> bool {
        self.user_roles.remove(&user_id).is_some()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[wasm_bindgen]
pub struct OperationSnapshot {
    id: String,
    user_id: String,
    timestamp: u64,
    operation_type: u8,
    description: String,
    before_state: Option<String>,
    after_state: Option<String>,
}

#[wasm_bindgen]
impl OperationSnapshot {
    #[wasm_bindgen(constructor)]
    pub fn new(
        user_id: String,
        operation_type: u8,
        description: String,
        before_state: Option<String>,
        after_state: Option<String>,
        timestamp: u64,
    ) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            user_id,
            timestamp,
            operation_type,
            description,
            before_state,
            after_state,
        }
    }

    #[wasm_bindgen(getter)]
    pub fn id(&self) -> String {
        self.id.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn user_id(&self) -> String {
        self.user_id.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn timestamp(&self) -> u64 {
        self.timestamp
    }

    #[wasm_bindgen(getter, js_name = operationType)]
    pub fn operation_type(&self) -> u8 {
        self.operation_type
    }

    #[wasm_bindgen(getter)]
    pub fn description(&self) -> String {
        self.description.clone()
    }

    #[wasm_bindgen(getter, js_name = beforeState)]
    pub fn before_state(&self) -> Option<String> {
        self.before_state.clone()
    }

    #[wasm_bindgen(getter, js_name = afterState)]
    pub fn after_state(&self) -> Option<String> {
        self.after_state.clone()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionNodeInternal {
    id: String,
    snapshot: OperationSnapshot,
    parent_id: Option<String>,
    children_ids: Vec<String>,
    branch_name: Option<String>,
    depth: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[wasm_bindgen]
pub struct VersionNode {
    #[wasm_bindgen(skip)]
    pub internal: VersionNodeInternal,
}

#[wasm_bindgen]
impl VersionNode {
    #[wasm_bindgen(getter)]
    pub fn id(&self) -> String {
        self.internal.id.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn snapshot(&self) -> OperationSnapshot {
        self.internal.snapshot.clone()
    }

    #[wasm_bindgen(getter, js_name = parentId)]
    pub fn parent_id(&self) -> Option<String> {
        self.internal.parent_id.clone()
    }

    #[wasm_bindgen(getter, js_name = childrenIds)]
    pub fn children_ids(&self) -> Vec<JsValue> {
        self.internal
            .children_ids
            .iter()
            .map(|id| JsValue::from_str(id))
            .collect()
    }

    #[wasm_bindgen(getter, js_name = branchName)]
    pub fn branch_name(&self) -> Option<String> {
        self.internal.branch_name.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn depth(&self) -> u32 {
        self.internal.depth
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[wasm_bindgen]
pub struct VersionTree {
    nodes: FxHashMap<String, VersionNodeInternal>,
    root_id: Option<String>,
    current_id: Option<String>,
    branches: FxHashMap<String, String>,
}

#[wasm_bindgen]
impl VersionTree {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        Self {
            nodes: FxHashMap::default(),
            root_id: None,
            current_id: None,
            branches: FxHashMap::default(),
        }
    }

    #[wasm_bindgen(js_name = createRoot)]
    pub fn create_root(&mut self, snapshot: OperationSnapshot) -> String {
        let node_id = snapshot.id.clone();
        let node = VersionNodeInternal {
            id: node_id.clone(),
            snapshot,
            parent_id: None,
            children_ids: Vec::new(),
            branch_name: Some("main".to_string()),
            depth: 0,
        };
        self.nodes.insert(node_id.clone(), node);
        self.root_id = Some(node_id.clone());
        self.current_id = Some(node_id.clone());
        self.branches.insert("main".to_string(), node_id.clone());
        node_id
    }

    #[wasm_bindgen(js_name = addVersion)]
    pub fn add_version(&mut self, parent_id: String, snapshot: OperationSnapshot) -> Option<String> {
        let parent_depth = {
            let parent = self.nodes.get(&parent_id)?;
            parent.depth
        };

        let branch_name = self
            .nodes
            .get(&parent_id)
            .and_then(|p| p.branch_name.clone());

        let node_id = snapshot.id.clone();
        let node = VersionNodeInternal {
            id: node_id.clone(),
            snapshot,
            parent_id: Some(parent_id.clone()),
            children_ids: Vec::new(),
            branch_name,
            depth: parent_depth + 1,
        };

        self.nodes.insert(node_id.clone(), node);

        if let Some(parent) = self.nodes.get_mut(&parent_id) {
            parent.children_ids.push(node_id.clone());
        }

        self.current_id = Some(node_id.clone());
        Some(node_id)
    }

    #[wasm_bindgen(js_name = createBranch)]
    pub fn create_branch(
        &mut self,
        branch_name: String,
        from_version_id: String,
        snapshot: OperationSnapshot,
    ) -> Option<String> {
        if self.branches.contains_key(&branch_name) {
            return None;
        }

        let from_depth = self.nodes.get(&from_version_id)?.depth;
        let node_id = snapshot.id.clone();
        let node = VersionNodeInternal {
            id: node_id.clone(),
            snapshot,
            parent_id: Some(from_version_id),
            children_ids: Vec::new(),
            branch_name: Some(branch_name.clone()),
            depth: from_depth + 1,
        };

        self.nodes.insert(node_id.clone(), node);
        self.branches.insert(branch_name, node_id.clone());
        self.current_id = Some(node_id.clone());
        Some(node_id)
    }

    #[wasm_bindgen(js_name = mergeBranches)]
    pub fn merge_branches(
        &mut self,
        target_id: String,
        source_id: String,
        merge_snapshot: OperationSnapshot,
    ) -> Option<String> {
        if !self.nodes.contains_key(&target_id) || !self.nodes.contains_key(&source_id) {
            return None;
        }

        let target_depth = self.nodes.get(&target_id)?.depth;
        let node_id = merge_snapshot.id.clone();

        let target_branch = self
            .nodes
            .get(&target_id)
            .and_then(|n| n.branch_name.clone());

        let merge_node = VersionNodeInternal {
            id: node_id.clone(),
            snapshot: merge_snapshot,
            parent_id: Some(target_id.clone()),
            children_ids: Vec::new(),
            branch_name: target_branch,
            depth: target_depth + 1,
        };

        self.nodes.insert(node_id.clone(), merge_node);

        if let Some(target) = self.nodes.get_mut(&target_id) {
            target.children_ids.push(node_id.clone());
        }
        if let Some(source) = self.nodes.get_mut(&source_id) {
            source.children_ids.push(node_id.clone());
        }

        self.current_id = Some(node_id.clone());
        Some(node_id)
    }

    #[wasm_bindgen(js_name = getVersion)]
    pub fn get_version(&self, version_id: String) -> Option<VersionNode> {
        self.nodes.get(&version_id).map(|n| VersionNode {
            internal: n.clone(),
        })
    }

    #[wasm_bindgen(js_name = getCurrentVersion)]
    pub fn get_current_version(&self) -> Option<VersionNode> {
        self.current_id
            .as_ref()
            .and_then(|id| self.nodes.get(id))
            .map(|n| VersionNode {
                internal: n.clone(),
            })
    }

    #[wasm_bindgen(js_name = rollbackTo)]
    pub fn rollback_to(&mut self, version_id: String) -> bool {
        if self.nodes.contains_key(&version_id) {
            self.current_id = Some(version_id);
            true
        } else {
            false
        }
    }

    #[wasm_bindgen(js_name = getVersionPath)]
    pub fn get_version_path(&self, from_id: String, to_id: String) -> Option<Vec<JsValue>> {
        let mut from_ancestors = FxHashSet::default();
        let mut current = Some(from_id.clone());
        while let Some(id) = current {
            from_ancestors.insert(id.clone());
            current = self.nodes.get(&id).and_then(|n| n.parent_id.clone());
        }

        let mut to_path = Vec::new();
        let mut current = Some(to_id.clone());
        let mut lca = None;
        while let Some(id) = current {
            if from_ancestors.contains(&id) {
                lca = Some(id);
                break;
            }
            to_path.push(id.clone());
            current = self.nodes.get(&id).and_then(|n| n.parent_id.clone());
        }

        let lca = lca?;
        let mut result = Vec::new();

        let mut current = Some(from_id);
        while let Some(id) = current {
            if id == lca {
                result.push(JsValue::from_str(&id));
                break;
            }
            result.push(JsValue::from_str(&id));
            current = self.nodes.get(&id).and_then(|n| n.parent_id.clone());
        }

        to_path.reverse();
        for id in to_path {
            result.push(JsValue::from_str(&id));
        }

        Some(result)
    }

    #[wasm_bindgen(js_name = getBranchVersions)]
    pub fn get_branch_versions(&self, branch_name: String) -> Vec<JsValue> {
        let mut versions = Vec::new();
        for node in self.nodes.values() {
            if node.branch_name.as_deref() == Some(&branch_name) {
                versions.push(JsValue::from_str(&node.id));
            }
        }
        versions.sort_by(|a, b| {
            let a_node = self.nodes.get(&a.as_string().unwrap()).unwrap();
            let b_node = self.nodes.get(&b.as_string().unwrap()).unwrap();
            a_node.depth.cmp(&b_node.depth)
        });
        versions
    }

    #[wasm_bindgen(js_name = listBranches)]
    pub fn list_branches(&self) -> Vec<JsValue> {
        self.branches
            .keys()
            .map(|name| JsValue::from_str(name))
            .collect()
    }

    #[wasm_bindgen(js_name = versionCount)]
    pub fn version_count(&self) -> u32 {
        self.nodes.len() as u32
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[wasm_bindgen]
pub struct SelectionRange {
    start: u32,
    end: u32,
    start_container: Option<String>,
    end_container: Option<String>,
}

#[wasm_bindgen]
impl SelectionRange {
    #[wasm_bindgen(constructor)]
    pub fn new(
        start: u32,
        end: u32,
        start_container: Option<String>,
        end_container: Option<String>,
    ) -> Self {
        Self {
            start,
            end,
            start_container,
            end_container,
        }
    }

    #[wasm_bindgen(getter)]
    pub fn start(&self) -> u32 {
        self.start
    }

    #[wasm_bindgen(getter)]
    pub fn end(&self) -> u32 {
        self.end
    }

    #[wasm_bindgen(getter, js_name = startContainer)]
    pub fn start_container(&self) -> Option<String> {
        self.start_container.clone()
    }

    #[wasm_bindgen(getter, js_name = endContainer)]
    pub fn end_container(&self) -> Option<String> {
        self.end_container.clone()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[wasm_bindgen]
pub struct Anchor {
    id: String,
    element_id: String,
    range: SelectionRange,
    version_id: Option<String>,
    is_resolved: bool,
}

#[wasm_bindgen]
impl Anchor {
    #[wasm_bindgen(constructor)]
    pub fn new(
        element_id: String,
        range: SelectionRange,
        version_id: Option<String>,
    ) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            element_id,
            range,
            version_id,
            is_resolved: false,
        }
    }

    #[wasm_bindgen(getter)]
    pub fn id(&self) -> String {
        self.id.clone()
    }

    #[wasm_bindgen(getter, js_name = elementId)]
    pub fn element_id(&self) -> String {
        self.element_id.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn range(&self) -> SelectionRange {
        self.range.clone()
    }

    #[wasm_bindgen(getter, js_name = versionId)]
    pub fn version_id(&self) -> Option<String> {
        self.version_id.clone()
    }

    #[wasm_bindgen(getter, js_name = isResolved)]
    pub fn is_resolved(&self) -> bool {
        self.is_resolved
    }

    #[wasm_bindgen(js_name = setResolved)]
    pub fn set_resolved(&mut self, resolved: bool) {
        self.is_resolved = resolved;
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[wasm_bindgen]
pub struct Comment {
    id: String,
    author_id: String,
    content: String,
    timestamp: u64,
    anchor_id: String,
    parent_comment_id: Option<String>,
    is_deleted: bool,
}

#[wasm_bindgen]
impl Comment {
    #[wasm_bindgen(constructor)]
    pub fn new(
        author_id: String,
        content: String,
        anchor_id: String,
        parent_comment_id: Option<String>,
        timestamp: u64,
    ) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            author_id,
            content,
            timestamp,
            anchor_id,
            parent_comment_id,
            is_deleted: false,
        }
    }

    #[wasm_bindgen(getter)]
    pub fn id(&self) -> String {
        self.id.clone()
    }

    #[wasm_bindgen(getter, js_name = authorId)]
    pub fn author_id(&self) -> String {
        self.author_id.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn content(&self) -> String {
        self.content.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn timestamp(&self) -> u64 {
        self.timestamp
    }

    #[wasm_bindgen(getter, js_name = anchorId)]
    pub fn anchor_id(&self) -> String {
        self.anchor_id.clone()
    }

    #[wasm_bindgen(getter, js_name = parentCommentId)]
    pub fn parent_comment_id(&self) -> Option<String> {
        self.parent_comment_id.clone()
    }

    #[wasm_bindgen(getter, js_name = isDeleted)]
    pub fn is_deleted(&self) -> bool {
        self.is_deleted
    }

    #[wasm_bindgen(js_name = updateContent)]
    pub fn update_content(&mut self, new_content: String) {
        self.content = new_content;
    }

    #[wasm_bindgen(js_name = softDelete)]
    pub fn soft_delete(&mut self) {
        self.is_deleted = true;
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[wasm_bindgen]
pub struct CommentThread {
    id: String,
    anchor: Anchor,
    comment_ids: Vec<String>,
    is_resolved: bool,
    resolved_by: Option<String>,
    resolved_at: Option<u64>,
    created_at: u64,
}

#[wasm_bindgen]
impl CommentThread {
    #[wasm_bindgen(constructor)]
    pub fn new(anchor: Anchor, created_at: u64) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            anchor,
            comment_ids: Vec::new(),
            is_resolved: false,
            resolved_by: None,
            resolved_at: None,
            created_at,
        }
    }

    #[wasm_bindgen(getter)]
    pub fn id(&self) -> String {
        self.id.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn anchor(&self) -> Anchor {
        self.anchor.clone()
    }

    #[wasm_bindgen(getter, js_name = commentIds)]
    pub fn comment_ids(&self) -> Vec<JsValue> {
        self.comment_ids
            .iter()
            .map(|id| JsValue::from_str(id))
            .collect()
    }

    #[wasm_bindgen(getter, js_name = isResolved)]
    pub fn is_resolved(&self) -> bool {
        self.is_resolved
    }

    #[wasm_bindgen(getter, js_name = resolvedBy)]
    pub fn resolved_by(&self) -> Option<String> {
        self.resolved_by.clone()
    }

    #[wasm_bindgen(getter, js_name = resolvedAt)]
    pub fn resolved_at(&self) -> Option<u64> {
        self.resolved_at
    }

    #[wasm_bindgen(getter, js_name = createdAt)]
    pub fn created_at(&self) -> u64 {
        self.created_at
    }

    #[wasm_bindgen(js_name = addComment)]
    pub fn add_comment(&mut self, comment_id: String) {
        self.comment_ids.push(comment_id);
    }

    #[wasm_bindgen(js_name = resolveThread)]
    pub fn resolve_thread(&mut self, resolver_id: String, timestamp: u64) {
        self.is_resolved = true;
        self.resolved_by = Some(resolver_id);
        self.resolved_at = Some(timestamp);
        self.anchor.set_resolved(true);
    }

    #[wasm_bindgen(js_name = reopenThread)]
    pub fn reopen_thread(&mut self) {
        self.is_resolved = false;
        self.resolved_by = None;
        self.resolved_at = None;
        self.anchor.set_resolved(false);
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[wasm_bindgen]
pub struct CommentManager {
    comments: FxHashMap<String, Comment>,
    threads: FxHashMap<String, CommentThread>,
    anchor_to_thread: FxHashMap<String, String>,
}

#[wasm_bindgen]
impl CommentManager {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        Self {
            comments: FxHashMap::default(),
            threads: FxHashMap::default(),
            anchor_to_thread: FxHashMap::default(),
        }
    }

    #[wasm_bindgen(js_name = createThread)]
    pub fn create_thread(&mut self, anchor: Anchor, created_at: u64) -> String {
        let thread = CommentThread::new(anchor.clone(), created_at);
        let thread_id = thread.id.clone();
        self.anchor_to_thread
            .insert(anchor.id.clone(), thread_id.clone());
        self.threads.insert(thread_id.clone(), thread);
        thread_id
    }

    #[wasm_bindgen(js_name = addComment)]
    pub fn add_comment(
        &mut self,
        thread_id: String,
        author_id: String,
        content: String,
        parent_comment_id: Option<String>,
        timestamp: u64,
    ) -> Option<String> {
        let thread = self.threads.get(&thread_id)?;
        let anchor_id = thread.anchor.id.clone();

        let comment = Comment::new(author_id, content, anchor_id, parent_comment_id, timestamp);
        let comment_id = comment.id.clone();
        self.comments.insert(comment_id.clone(), comment);

        let thread = self.threads.get_mut(&thread_id)?;
        thread.add_comment(comment_id.clone());
        Some(comment_id)
    }

    #[wasm_bindgen(js_name = getComment)]
    pub fn get_comment(&self, comment_id: String) -> Option<Comment> {
        self.comments.get(&comment_id).cloned()
    }

    #[wasm_bindgen(js_name = getThread)]
    pub fn get_thread(&self, thread_id: String) -> Option<CommentThread> {
        self.threads.get(&thread_id).cloned()
    }

    #[wasm_bindgen(js_name = getThreadByAnchor)]
    pub fn get_thread_by_anchor(&self, anchor_id: String) -> Option<CommentThread> {
        let thread_id = self.anchor_to_thread.get(&anchor_id)?;
        self.threads.get(thread_id).cloned()
    }

    #[wasm_bindgen(js_name = getThreadComments)]
    pub fn get_thread_comments(&self, thread_id: String) -> Vec<Comment> {
        self.threads
            .get(&thread_id)
            .map(|thread| {
                thread
                    .comment_ids
                    .iter()
                    .filter_map(|id| self.comments.get(id).cloned())
                    .collect()
            })
            .unwrap_or_default()
    }

    #[wasm_bindgen(js_name = getReplies)]
    pub fn get_replies(&self, parent_comment_id: String) -> Vec<Comment> {
        self.comments
            .values()
            .filter(|c| c.parent_comment_id.as_deref() == Some(&parent_comment_id))
            .cloned()
            .collect()
    }

    #[wasm_bindgen(js_name = updateComment)]
    pub fn update_comment(&mut self, comment_id: String, new_content: String) -> bool {
        if let Some(comment) = self.comments.get_mut(&comment_id) {
            comment.update_content(new_content);
            true
        } else {
            false
        }
    }

    #[wasm_bindgen(js_name = deleteComment)]
    pub fn delete_comment(&mut self, comment_id: String) -> bool {
        if let Some(comment) = self.comments.get_mut(&comment_id) {
            comment.soft_delete();
            true
        } else {
            false
        }
    }

    #[wasm_bindgen(js_name = resolveThread)]
    pub fn resolve_thread(
        &mut self,
        thread_id: String,
        resolver_id: String,
        timestamp: u64,
    ) -> bool {
        if let Some(thread) = self.threads.get_mut(&thread_id) {
            thread.resolve_thread(resolver_id, timestamp);
            true
        } else {
            false
        }
    }

    #[wasm_bindgen(js_name = reopenThread)]
    pub fn reopen_thread(&mut self, thread_id: String) -> bool {
        if let Some(thread) = self.threads.get_mut(&thread_id) {
            thread.reopen_thread();
            true
        } else {
            false
        }
    }

    #[wasm_bindgen(js_name = getElementThreads)]
    pub fn get_element_threads(&self, element_id: String) -> Vec<CommentThread> {
        self.threads
            .values()
            .filter(|t| t.anchor.element_id == element_id)
            .cloned()
            .collect()
    }

    #[wasm_bindgen(js_name = listThreads)]
    pub fn list_threads(&self, include_resolved: bool) -> Vec<CommentThread> {
        self.threads
            .values()
            .filter(|t| include_resolved || !t.is_resolved)
            .cloned()
            .collect()
    }

    #[wasm_bindgen(js_name = commentCount)]
    pub fn comment_count(&self) -> u32 {
        self.comments.len() as u32
    }

    #[wasm_bindgen(js_name = threadCount)]
    pub fn thread_count(&self) -> u32 {
        self.threads.len() as u32
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[wasm_bindgen]
pub struct User {
    id: String,
    name: String,
    email: Option<String>,
    avatar_url: Option<String>,
    created_at: u64,
    last_active: u64,
    metadata: Option<String>,
}

#[wasm_bindgen]
impl User {
    #[wasm_bindgen(constructor)]
    pub fn new(
        id: Option<String>,
        name: String,
        email: Option<String>,
        avatar_url: Option<String>,
        timestamp: u64,
    ) -> Self {
        Self {
            id: id.unwrap_or_else(|| Uuid::new_v4().to_string()),
            name,
            email,
            avatar_url,
            created_at: timestamp,
            last_active: timestamp,
            metadata: None,
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

    #[wasm_bindgen(getter)]
    pub fn email(&self) -> Option<String> {
        self.email.clone()
    }

    #[wasm_bindgen(getter, js_name = avatarUrl)]
    pub fn avatar_url(&self) -> Option<String> {
        self.avatar_url.clone()
    }

    #[wasm_bindgen(getter, js_name = createdAt)]
    pub fn created_at(&self) -> u64 {
        self.created_at
    }

    #[wasm_bindgen(getter, js_name = lastActive)]
    pub fn last_active(&self) -> u64 {
        self.last_active
    }

    #[wasm_bindgen(getter)]
    pub fn metadata(&self) -> Option<String> {
        self.metadata.clone()
    }

    #[wasm_bindgen(js_name = setName)]
    pub fn set_name(&mut self, name: String) {
        self.name = name;
    }

    #[wasm_bindgen(js_name = setEmail)]
    pub fn set_email(&mut self, email: Option<String>) {
        self.email = email;
    }

    #[wasm_bindgen(js_name = setAvatarUrl)]
    pub fn set_avatar_url(&mut self, url: Option<String>) {
        self.avatar_url = url;
    }

    #[wasm_bindgen(js_name = setMetadata)]
    pub fn set_metadata(&mut self, metadata: Option<String>) {
        self.metadata = metadata;
    }

    #[wasm_bindgen(js_name = updateLastActive)]
    pub fn update_last_active(&mut self, timestamp: u64) {
        self.last_active = timestamp;
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[wasm_bindgen]
pub struct Session {
    id: String,
    user_id: String,
    created_at: u64,
    expires_at: u64,
    is_active: bool,
    ip_address: Option<String>,
    user_agent: Option<String>,
}

#[wasm_bindgen]
impl Session {
    #[wasm_bindgen(constructor)]
    pub fn new(
        user_id: String,
        created_at: u64,
        expires_at: u64,
        ip_address: Option<String>,
        user_agent: Option<String>,
    ) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            user_id,
            created_at,
            expires_at,
            is_active: true,
            ip_address,
            user_agent,
        }
    }

    #[wasm_bindgen(getter)]
    pub fn id(&self) -> String {
        self.id.clone()
    }

    #[wasm_bindgen(getter, js_name = userId)]
    pub fn user_id(&self) -> String {
        self.user_id.clone()
    }

    #[wasm_bindgen(getter, js_name = createdAt)]
    pub fn created_at(&self) -> u64 {
        self.created_at
    }

    #[wasm_bindgen(getter, js_name = expiresAt)]
    pub fn expires_at(&self) -> u64 {
        self.expires_at
    }

    #[wasm_bindgen(getter, js_name = isActive)]
    pub fn is_active(&self) -> bool {
        self.is_active
    }

    #[wasm_bindgen(getter, js_name = ipAddress)]
    pub fn ip_address(&self) -> Option<String> {
        self.ip_address.clone()
    }

    #[wasm_bindgen(getter, js_name = userAgent)]
    pub fn user_agent(&self) -> Option<String> {
        self.user_agent.clone()
    }

    #[wasm_bindgen(js_name = isExpired)]
    pub fn is_expired(&self, current_time: u64) -> bool {
        current_time > self.expires_at
    }

    #[wasm_bindgen(js_name = isSessionValid)]
    pub fn is_session_valid(&self, current_time: u64) -> bool {
        self.is_active && !self.is_expired(current_time)
    }

    #[wasm_bindgen]
    pub fn invalidate(&mut self) {
        self.is_active = false;
    }

    #[wasm_bindgen(js_name = extendExpiry)]
    pub fn extend_expiry(&mut self, new_expires_at: u64) {
        self.expires_at = new_expires_at;
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[wasm_bindgen]
pub struct UserSessionManager {
    users: FxHashMap<String, User>,
    sessions: FxHashMap<String, Session>,
    user_sessions: FxHashMap<String, Vec<String>>,
}

#[wasm_bindgen]
impl UserSessionManager {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        Self {
            users: FxHashMap::default(),
            sessions: FxHashMap::default(),
            user_sessions: FxHashMap::default(),
        }
    }

    #[wasm_bindgen(js_name = createUser)]
    pub fn create_user(&mut self, user: User) -> String {
        let id = user.id.clone();
        self.users.insert(id.clone(), user);
        id
    }

    #[wasm_bindgen(js_name = getUser)]
    pub fn get_user(&self, user_id: String) -> Option<User> {
        self.users.get(&user_id).cloned()
    }

    #[wasm_bindgen(js_name = updateUser)]
    pub fn update_user(&mut self, user: User) -> bool {
        if self.users.contains_key(&user.id) {
            self.users.insert(user.id.clone(), user);
            true
        } else {
            false
        }
    }

    #[wasm_bindgen(js_name = deleteUser)]
    pub fn delete_user(&mut self, user_id: String) -> bool {
        if let Some(session_ids) = self.user_sessions.remove(&user_id) {
            for session_id in session_ids {
                self.sessions.remove(&session_id);
            }
        }
        self.users.remove(&user_id).is_some()
    }

    #[wasm_bindgen(js_name = createSession)]
    pub fn create_session(&mut self, session: Session) -> Option<String> {
        if !self.users.contains_key(&session.user_id) {
            return None;
        }
        let id = session.id.clone();
        let user_id = session.user_id.clone();
        self.sessions.insert(id.clone(), session);
        self.user_sessions
            .entry(user_id)
            .or_default()
            .push(id.clone());
        Some(id)
    }

    #[wasm_bindgen(js_name = getSession)]
    pub fn get_session(&self, session_id: String) -> Option<Session> {
        self.sessions.get(&session_id).cloned()
    }

    #[wasm_bindgen(js_name = getUserBySession)]
    pub fn get_user_by_session(&self, session_id: String, current_time: u64) -> Option<User> {
        let session = self.sessions.get(&session_id)?;
        if !session.is_session_valid(current_time) {
            return None;
        }
        self.users.get(&session.user_id).cloned()
    }

    #[wasm_bindgen(js_name = getUserSessions)]
    pub fn get_user_sessions(&self, user_id: String) -> Vec<Session> {
        self.user_sessions
            .get(&user_id)
            .map(|ids| {
                ids.iter()
                    .filter_map(|id| self.sessions.get(id).cloned())
                    .collect()
            })
            .unwrap_or_default()
    }

    #[wasm_bindgen(js_name = invalidateSession)]
    pub fn invalidate_session(&mut self, session_id: String) -> bool {
        if let Some(session) = self.sessions.get_mut(&session_id) {
            session.invalidate();
            true
        } else {
            false
        }
    }

    #[wasm_bindgen(js_name = invalidateAllUserSessions)]
    pub fn invalidate_all_user_sessions(&mut self, user_id: String) -> u32 {
        let mut count = 0;
        if let Some(session_ids) = self.user_sessions.get(&user_id) {
            for session_id in session_ids {
                if let Some(session) = self.sessions.get_mut(session_id) {
                    session.invalidate();
                    count += 1;
                }
            }
        }
        count
    }

    #[wasm_bindgen(js_name = cleanupExpiredSessions)]
    pub fn cleanup_expired_sessions(&mut self, current_time: u64) -> u32 {
        let mut count = 0;
        let expired_ids: Vec<String> = self
            .sessions
            .iter()
            .filter(|(_, s)| s.is_expired(current_time))
            .map(|(id, _)| id.clone())
            .collect();

        for expired_id in &expired_ids {
            if let Some(session) = self.sessions.remove(expired_id) {
                if let Some(user_sessions) = self.user_sessions.get_mut(&session.user_id) {
                    user_sessions.retain(|id| id != expired_id);
                }
                count += 1;
            }
        }
        count
    }

    #[wasm_bindgen(js_name = updateUserActivity)]
    pub fn update_user_activity(&mut self, user_id: String, timestamp: u64) -> bool {
        if let Some(user) = self.users.get_mut(&user_id) {
            user.update_last_active(timestamp);
            true
        } else {
            false
        }
    }

    #[wasm_bindgen(js_name = validateSession)]
    pub fn validate_session(&self, session_id: String, current_time: u64) -> Option<String> {
        let session = self.sessions.get(&session_id)?;
        if session.is_session_valid(current_time) {
            Some(session.user_id.clone())
        } else {
            None
        }
    }

    #[wasm_bindgen(js_name = userCount)]
    pub fn user_count(&self) -> u32 {
        self.users.len() as u32
    }

    #[wasm_bindgen(js_name = activeSessionCount)]
    pub fn active_session_count(&self, current_time: u64) -> u32 {
        self.sessions
            .values()
            .filter(|s| s.is_session_valid(current_time))
            .count() as u32
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[wasm_bindgen]
pub struct AuditLogEntry {
    id: String,
    user_id: Option<String>,
    session_id: Option<String>,
    timestamp: u64,
    action: String,
    target_type: Option<String>,
    target_id: Option<String>,
    metadata: Option<String>,
    ip_address: Option<String>,
    success: bool,
    error_message: Option<String>,
}

#[wasm_bindgen]
impl AuditLogEntry {
    #[wasm_bindgen(constructor)]
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        user_id: Option<String>,
        session_id: Option<String>,
        timestamp: u64,
        action: String,
        target_type: Option<String>,
        target_id: Option<String>,
        metadata: Option<String>,
        ip_address: Option<String>,
        success: bool,
        error_message: Option<String>,
    ) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            user_id,
            session_id,
            timestamp,
            action,
            target_type,
            target_id,
            metadata,
            ip_address,
            success,
            error_message,
        }
    }

    #[wasm_bindgen(getter)]
    pub fn id(&self) -> String {
        self.id.clone()
    }

    #[wasm_bindgen(getter, js_name = userId)]
    pub fn user_id(&self) -> Option<String> {
        self.user_id.clone()
    }

    #[wasm_bindgen(getter, js_name = sessionId)]
    pub fn session_id(&self) -> Option<String> {
        self.session_id.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn timestamp(&self) -> u64 {
        self.timestamp
    }

    #[wasm_bindgen(getter)]
    pub fn action(&self) -> String {
        self.action.clone()
    }

    #[wasm_bindgen(getter, js_name = targetType)]
    pub fn target_type(&self) -> Option<String> {
        self.target_type.clone()
    }

    #[wasm_bindgen(getter, js_name = targetId)]
    pub fn target_id(&self) -> Option<String> {
        self.target_id.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn metadata(&self) -> Option<String> {
        self.metadata.clone()
    }

    #[wasm_bindgen(getter, js_name = ipAddress)]
    pub fn ip_address(&self) -> Option<String> {
        self.ip_address.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn success(&self) -> bool {
        self.success
    }

    #[wasm_bindgen(getter, js_name = errorMessage)]
    pub fn error_message(&self) -> Option<String> {
        self.error_message.clone()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[wasm_bindgen]
pub struct AuditLog {
    entries: VecDeque<AuditLogEntry>,
    max_entries: u32,
    user_index: FxHashMap<String, Vec<String>>,
    action_index: FxHashMap<String, Vec<String>>,
    target_index: FxHashMap<String, Vec<String>>,
}

#[wasm_bindgen]
impl AuditLog {
    #[wasm_bindgen(constructor)]
    pub fn new(max_entries: Option<u32>) -> Self {
        Self {
            entries: VecDeque::new(),
            max_entries: max_entries.unwrap_or(10000),
            user_index: FxHashMap::default(),
            action_index: FxHashMap::default(),
            target_index: FxHashMap::default(),
        }
    }

    #[wasm_bindgen]
    pub fn log(&mut self, entry: AuditLogEntry) {
        let entry_id = entry.id.clone();

        if let Some(user_id) = &entry.user_id {
            self.user_index
                .entry(user_id.clone())
                .or_default()
                .push(entry_id.clone());
        }

        self.action_index
            .entry(entry.action.clone())
            .or_default()
            .push(entry_id.clone());

        if let Some(target_id) = &entry.target_id {
            self.target_index
                .entry(target_id.clone())
                .or_default()
                .push(entry_id.clone());
        }

        self.entries.push_front(entry);

        while self.entries.len() > self.max_entries as usize {
            if let Some(removed) = self.entries.pop_back() {
                if let Some(user_id) = &removed.user_id {
                    if let Some(ids) = self.user_index.get_mut(user_id) {
                        ids.retain(|id| id != &removed.id);
                    }
                }
                if let Some(ids) = self.action_index.get_mut(&removed.action) {
                    ids.retain(|id| id != &removed.id);
                }
                if let Some(target_id) = &removed.target_id {
                    if let Some(ids) = self.target_index.get_mut(target_id) {
                        ids.retain(|id| id != &removed.id);
                    }
                }
            }
        }
    }

    #[wasm_bindgen(js_name = logAction)]
    pub fn log_action(
        &mut self,
        user_id: Option<String>,
        session_id: Option<String>,
        timestamp: u64,
        action: String,
        target_type: Option<String>,
        target_id: Option<String>,
        metadata: Option<String>,
        ip_address: Option<String>,
    ) {
        let entry = AuditLogEntry::new(
            user_id,
            session_id,
            timestamp,
            action,
            target_type,
            target_id,
            metadata,
            ip_address,
            true,
            None,
        );
        self.log(entry);
    }

    #[wasm_bindgen(js_name = logFailure)]
    pub fn log_failure(
        &mut self,
        user_id: Option<String>,
        session_id: Option<String>,
        timestamp: u64,
        action: String,
        target_type: Option<String>,
        target_id: Option<String>,
        error_message: String,
        ip_address: Option<String>,
    ) {
        let entry = AuditLogEntry::new(
            user_id,
            session_id,
            timestamp,
            action,
            target_type,
            target_id,
            None,
            ip_address,
            false,
            Some(error_message),
        );
        self.log(entry);
    }

    #[wasm_bindgen(js_name = getRecentEntries)]
    pub fn get_recent_entries(&self, limit: u32) -> Vec<AuditLogEntry> {
        self.entries
            .iter()
            .take(limit as usize)
            .cloned()
            .collect()
    }

    #[wasm_bindgen(js_name = getEntriesByUser)]
    pub fn get_entries_by_user(&self, user_id: String, limit: u32) -> Vec<AuditLogEntry> {
        let ids = self.user_index.get(&user_id).cloned().unwrap_or_default();
        let mut result: Vec<AuditLogEntry> = ids
            .iter()
            .filter_map(|id| {
                self.entries
                    .iter()
                    .find(|e| e.id == *id)
                    .cloned()
            })
            .collect();
        result.sort_by(|a, b| b.timestamp.cmp(&a.timestamp));
        result.into_iter().take(limit as usize).collect()
    }

    #[wasm_bindgen(js_name = getEntriesByAction)]
    pub fn get_entries_by_action(&self, action: String, limit: u32) -> Vec<AuditLogEntry> {
        let ids = self.action_index.get(&action).cloned().unwrap_or_default();
        let mut result: Vec<AuditLogEntry> = ids
            .iter()
            .filter_map(|id| {
                self.entries
                    .iter()
                    .find(|e| e.id == *id)
                    .cloned()
            })
            .collect();
        result.sort_by(|a, b| b.timestamp.cmp(&a.timestamp));
        result.into_iter().take(limit as usize).collect()
    }

    #[wasm_bindgen(js_name = getEntriesByTarget)]
    pub fn get_entries_by_target(&self, target_id: String, limit: u32) -> Vec<AuditLogEntry> {
        let ids = self.target_index.get(&target_id).cloned().unwrap_or_default();
        let mut result: Vec<AuditLogEntry> = ids
            .iter()
            .filter_map(|id| {
                self.entries
                    .iter()
                    .find(|e| e.id == *id)
                    .cloned()
            })
            .collect();
        result.sort_by(|a, b| b.timestamp.cmp(&a.timestamp));
        result.into_iter().take(limit as usize).collect()
    }

    #[wasm_bindgen(js_name = getEntriesInTimeRange)]
    pub fn get_entries_in_time_range(
        &self,
        start_time: u64,
        end_time: u64,
        limit: u32,
    ) -> Vec<AuditLogEntry> {
        self.entries
            .iter()
            .filter(|e| e.timestamp >= start_time && e.timestamp <= end_time)
            .take(limit as usize)
            .cloned()
            .collect()
    }

    #[wasm_bindgen(js_name = getFailedEntries)]
    pub fn get_failed_entries(&self, limit: u32) -> Vec<AuditLogEntry> {
        self.entries
            .iter()
            .filter(|e| !e.success)
            .take(limit as usize)
            .cloned()
            .collect()
    }

    #[wasm_bindgen(js_name = entryCount)]
    pub fn entry_count(&self) -> u32 {
        self.entries.len() as u32
    }

    #[wasm_bindgen(js_name = clear)]
    pub fn clear(&mut self) {
        self.entries.clear();
        self.user_index.clear();
        self.action_index.clear();
        self.target_index.clear();
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[wasm_bindgen]
pub struct PermissionHistorySystem {
    permissions: PermissionChecker,
    history: VersionTree,
    comments: CommentManager,
    users: UserSessionManager,
    audit_log: AuditLog,
}

#[wasm_bindgen]
impl PermissionHistorySystem {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Self {
        Self {
            permissions: PermissionChecker::new(),
            history: VersionTree::new(),
            comments: CommentManager::new(),
            users: UserSessionManager::new(),
            audit_log: AuditLog::new(None),
        }
    }

    #[wasm_bindgen(getter)]
    pub fn permissions(&self) -> PermissionChecker {
        self.permissions.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn history(&self) -> VersionTree {
        self.history.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn comments(&self) -> CommentManager {
        self.comments.clone()
    }

    #[wasm_bindgen(getter, js_name = userManager)]
    pub fn user_manager(&self) -> UserSessionManager {
        self.users.clone()
    }

    #[wasm_bindgen(getter, js_name = auditLog)]
    pub fn audit_log(&self) -> AuditLog {
        self.audit_log.clone()
    }

    #[wasm_bindgen(js_name = toJson)]
    pub fn to_json(&self) -> Result<String, JsValue> {
        serde_json::to_string(self).map_err(|e| JsValue::from_str(&e.to_string()))
    }

    #[wasm_bindgen(js_name = fromJson)]
    pub fn from_json(json: String) -> Result<PermissionHistorySystem, JsValue> {
        serde_json::from_str(&json).map_err(|e| JsValue::from_str(&e.to_string()))
    }
}
