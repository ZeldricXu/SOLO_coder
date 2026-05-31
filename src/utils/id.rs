use sha2::{Sha256, Digest};
use uuid::Uuid;

pub fn generate_id(prefix: &str) -> String {
    format!("{}_{}", prefix, Uuid::new_v4().simple())
}

pub fn hash_content(content: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(content);
    let result = hasher.finalize();
    format!("{:x}", result)
}

pub fn short_id() -> String {
    Uuid::new_v4().simple().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_generate_id() {
        let id = generate_id("doc");
        assert!(id.starts_with("doc_"));
        assert_eq!(id.len(), 4 + 32);
    }

    #[test]
    fn test_hash_content() {
        let hash1 = hash_content(b"test content");
        let hash2 = hash_content(b"test content");
        let hash3 = hash_content(b"different content");
        
        assert_eq!(hash1, hash2);
        assert_ne!(hash1, hash3);
        assert_eq!(hash1.len(), 64);
    }

    #[test]
    fn test_short_id() {
        let id = short_id();
        assert_eq!(id.len(), 32);
    }
}
