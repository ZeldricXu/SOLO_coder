use rand::Rng;
use sha2::{Digest, Sha256};

pub fn generate_random_string(length: usize) -> String {
    const CHARSET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ\
                            abcdefghijklmnopqrstuvwxyz\
                            0123456789";
    let mut rng = rand::thread_rng();
    (0..length)
        .map(|_| {
            let idx = rng.gen_range(0..CHARSET.len());
            CHARSET[idx] as char
        })
        .collect()
}

pub fn generate_webhook_secret() -> String {
    generate_random_string(32)
}

pub fn sha256_hash(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    let result = hasher.finalize();
    hex::encode(result)
}

pub fn verify_hmac_signature(secret: &str, payload: &[u8], signature: &str) -> bool {
    use hmac::{Hmac, Mac};
    use sha2::Sha256;

    type HmacSha256 = Hmac<Sha256>;
    
    let mut mac = HmacSha256::new_from_slice(secret.as_bytes())
        .expect("HMAC can take key of any size");
    mac.update(payload);
    
    let expected = mac.finalize().into_bytes();
    let expected_hex = hex::encode(expected);
    
    let signature = signature.trim_start_matches("sha256=").to_lowercase();
    
    constant_time_eq(&expected_hex, &signature)
}

fn constant_time_eq(a: &str, b: &str) -> bool {
    if a.len() != b.len() {
        return false;
    }
    
    let a_bytes = a.as_bytes();
    let b_bytes = b.as_bytes();
    
    let mut result = 0u8;
    for i in 0..a_bytes.len() {
        result |= a_bytes[i] ^ b_bytes[i];
    }
    
    result == 0
}

pub fn generate_csrf_token() -> String {
    generate_random_string(32)
}
