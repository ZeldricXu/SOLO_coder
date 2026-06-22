use std::cell::RefCell;
use std::collections::HashMap;
use std::fmt;
use std::hash::{Hash, Hasher};
use std::ops::Deref;
use std::ptr;
use std::sync::Arc;

const ARENA_BLOCK_SIZE: usize = 64 * 1024;
const MAX_INTERN_LEN: usize = 64;
const MAX_INTERN_ENTRIES: usize = 1024 * 8;

#[derive(Clone)]
pub struct InternedString {
    ptr: *const u8,
    len: usize,
    hash: u64,
    #[allow(dead_code)]
    arena: Option<Arc<StringArena>>,
}

unsafe impl Send for InternedString {}
unsafe impl Sync for InternedString {}

impl InternedString {
    #[inline]
    pub fn as_str(&self) -> &str {
        unsafe {
            let slice = std::slice::from_raw_parts(self.ptr, self.len);
            std::str::from_utf8_unchecked(slice)
        }
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.len
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }
}

impl Deref for InternedString {
    type Target = str;

    #[inline]
    fn deref(&self) -> &str {
        self.as_str()
    }
}

impl PartialEq for InternedString {
    #[inline]
    fn eq(&self, other: &Self) -> bool {
        if self.ptr == other.ptr {
            return true;
        }
        if self.len != other.len || self.hash != other.hash {
            return false;
        }
        self.as_str() == other.as_str()
    }
}

impl Eq for InternedString {}

impl Hash for InternedString {
    #[inline]
    fn hash<H: Hasher>(&self, state: &mut H) {
        state.write_u64(self.hash);
    }
}

impl fmt::Debug for InternedString {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Debug::fmt(self.as_str(), f)
    }
}

impl fmt::Display for InternedString {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Display::fmt(self.as_str(), f)
    }
}

impl PartialOrd for InternedString {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        self.as_str().partial_cmp(other.as_str())
    }
}

impl Ord for InternedString {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        self.as_str().cmp(other.as_str())
    }
}

struct StringArena {
    blocks: RefCell<Vec<Vec<u8>>>,
    current_offset: RefCell<usize>,
}

impl StringArena {
    fn new() -> Self {
        let initial_block = Vec::with_capacity(ARENA_BLOCK_SIZE);
        Self {
            blocks: RefCell::new(vec![initial_block]),
            current_offset: RefCell::new(0),
        }
    }

    fn alloc_str(&self, s: &str) -> *const u8 {
        let bytes = s.as_bytes();
        let len = bytes.len();

        let mut offset = self.current_offset.borrow_mut();
        let mut blocks = self.blocks.borrow_mut();
        let current_block = blocks.last_mut().unwrap();

        if *offset + len > current_block.capacity() {
            blocks.push(Vec::with_capacity(ARENA_BLOCK_SIZE.max(len)));
            *offset = 0;
        }

        let current_block = blocks.last_mut().unwrap();
        let start = *offset;

        unsafe {
            let dst = current_block.as_mut_ptr().add(start);
            ptr::copy_nonoverlapping(bytes.as_ptr(), dst, len);
            current_block.set_len(start + len);
        }

        *offset += len;

        unsafe { current_block.as_ptr().add(start) }
    }
}

unsafe impl Send for StringArena {}
unsafe impl Sync for StringArena {}

struct InternerInner {
    map: HashMap<u64, InternedString>,
    arena: Arc<StringArena>,
    count: usize,
}

pub struct StringInterner {
    inner: RefCell<InternerInner>,
}

thread_local! {
    static CURRENT_INTERNER: RefCell<Option<Arc<StringInterner>>> = RefCell::new(None);
}

impl StringInterner {
    pub fn new() -> Arc<Self> {
        let arena = Arc::new(StringArena::new());
        let interner = Arc::new(Self {
            inner: RefCell::new(InternerInner {
                map: HashMap::with_capacity(MAX_INTERN_ENTRIES),
                arena: arena.clone(),
                count: 0,
            }),
        });
        interner
    }

    pub fn install(self: &Arc<Self>) {
        CURRENT_INTERNER.with(|cell| {
            *cell.borrow_mut() = Some(self.clone());
        });
    }

    pub fn uninstall() {
        CURRENT_INTERNER.with(|cell| {
            *cell.borrow_mut() = None;
        });
    }

    pub fn with_current<F, R>(f: F) -> Option<R>
    where
        F: FnOnce(&Arc<StringInterner>) -> R,
    {
        CURRENT_INTERNER.with(|cell| cell.borrow().as_ref().map(f))
    }

    #[inline]
    fn pre_hash(s: &str) -> u64 {
        use std::collections::hash_map::DefaultHasher;
        let mut hasher = DefaultHasher::new();
        s.hash(&mut hasher);
        hasher.finish()
    }

    #[inline]
    pub fn intern(self: &Arc<Self>, s: &str) -> Option<InternedString> {
        if s.len() > MAX_INTERN_LEN {
            return None;
        }

        let hash = Self::pre_hash(s);

        let mut inner = self.inner.borrow_mut();

        if let Some(existing) = inner.map.get(&hash) {
            if existing.as_str() == s {
                return Some(existing.clone());
            }
        }

        if inner.count >= MAX_INTERN_ENTRIES {
            return None;
        }

        let ptr = inner.arena.alloc_str(s);
        let interned = InternedString {
            ptr,
            len: s.len(),
            hash,
            arena: Some(inner.arena.clone()),
        };

        inner.map.insert(hash, interned.clone());
        inner.count += 1;

        Some(interned)
    }

    pub fn intern_fast(s: &str) -> Option<InternedString> {
        Self::with_current(|interner| interner.intern(s)).flatten()
    }

    pub fn len(&self) -> usize {
        self.inner.borrow().count
    }

    pub fn is_empty(&self) -> bool {
        self.inner.borrow().count == 0
    }

    pub fn clear(&self) {
        let mut inner = self.inner.borrow_mut();
        inner.map.clear();
        inner.count = 0;
    }
}

#[inline]
pub fn should_intern_field(field: &str) -> bool {
    matches!(
        field,
        "service"
            | "app"
            | "application"
            | "service_name"
            | "level"
            | "loglevel"
            | "severity"
            | "log_level"
            | "status"
            | "method"
            | "protocol"
            | "host"
            | "hostname"
            | "env"
            | "environment"
            | "cluster"
            | "dc"
            | "datacenter"
    )
}

#[inline]
pub fn should_intern_value(value: &str) -> bool {
    if value.is_empty() {
        return false;
    }
    if value.len() > MAX_INTERN_LEN {
        return false;
    }
    if value.len() < 2 {
        return false;
    }

    let byte = value.as_bytes();
    for &b in byte {
        if !(b.is_ascii_alphanumeric() || b == b'_' || b == b'-' || b == b'.' || b == b'/') {
            return false;
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_intern_basic() {
        let interner = StringInterner::new();
        interner.install();

        let s1 = interner.intern("api-gateway").unwrap();
        let s2 = interner.intern("api-gateway").unwrap();

        assert_eq!(s1.as_str(), "api-gateway");
        assert_eq!(s1, s2);
        assert!(ptr::eq(s1.ptr, s2.ptr));

        StringInterner::uninstall();
    }

    #[test]
    fn test_intern_hash_collision() {
        let interner = StringInterner::new();
        interner.install();

        let s1 = interner.intern("INFO").unwrap();
        let s2 = interner.intern("ERROR").unwrap();

        assert_eq!(s1.as_str(), "INFO");
        assert_eq!(s2.as_str(), "ERROR");
        assert_ne!(s1, s2);

        StringInterner::uninstall();
    }

    #[test]
    fn test_should_intern_value() {
        assert!(should_intern_value("INFO"));
        assert!(should_intern_value("api-gateway"));
        assert!(should_intern_value("200"));
        assert!(!should_intern_value(""));
        assert!(!should_intern_value("x"));
        assert!(!should_intern_value("abc123xyz789abc123xyz789abc123xyz789abc123xyz789abc123xyz789abc123xyz789"));
        assert!(!should_intern_value("trace-id-1234567890abcdef"));
        assert!(!should_intern_value("hello world"));
    }

    #[test]
    fn test_with_current() {
        let interner = StringInterner::new();
        interner.install();

        let result = StringInterner::with_current(|i| i.intern("test").unwrap().as_str().to_string());
        assert_eq!(result, Some("test".to_string()));

        StringInterner::uninstall();

        let result = StringInterner::with_current(|_| "should not be called");
        assert!(result.is_none());
    }
}
