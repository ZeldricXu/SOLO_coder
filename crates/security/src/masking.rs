use std::collections::HashMap;

use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Key, Nonce,
};
use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use common::error::AppError;
use rand::RngCore;
use regex::Regex;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone)]
pub struct Aes256GcmKey(pub [u8; 32]);

impl Aes256GcmKey {
    pub fn from_bytes(bytes: &[u8]) -> Result<Self, AppError> {
        if bytes.len() != 32 {
            return Err(AppError::Internal(
                "AES-256-GCM key must be 32 bytes".to_string(),
            ));
        }
        let mut key = [0u8; 32];
        key.copy_from_slice(bytes);
        Ok(Self(key))
    }

    pub fn from_base64(encoded: &str) -> Result<Self, AppError> {
        let bytes = BASE64
            .decode(encoded)
            .map_err(|e| AppError::Internal(format!("base64 decode failed: {}", e)))?;
        Self::from_bytes(&bytes)
    }

    pub fn generate() -> Self {
        let mut key = [0u8; 32];
        rand::thread_rng().fill_bytes(&mut key);
        Self(key)
    }

    pub fn to_base64(&self) -> String {
        BASE64.encode(&self.0)
    }

    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }
}

pub fn mask_phone(phone: &str) -> String {
    if phone.is_empty() {
        return phone.to_string();
    }
    let digits: String = phone.chars().filter(|c| c.is_ascii_digit()).collect();
    if digits.len() < 7 {
        return "*".repeat(phone.len());
    }
    if digits.len() == 11 {
        format!("{}****{}", &digits[..3], &digits[7..])
    } else {
        let prefix_len = 3;
        let suffix_len = 4;
        if digits.len() <= prefix_len + suffix_len {
            return "*".repeat(phone.len());
        }
        let mask_len = digits.len() - prefix_len - suffix_len;
        format!(
            "{}{}{}",
            &digits[..prefix_len],
            "*".repeat(mask_len),
            &digits[digits.len() - suffix_len..]
        )
    }
}

pub fn mask_id_card(id_card: &str) -> String {
    if id_card.is_empty() {
        return id_card.to_string();
    }
    let chars: Vec<char> = id_card.chars().collect();
    if chars.len() < 10 {
        return "*".repeat(chars.len());
    }
    if chars.len() == 18 {
        let prefix: String = chars[..6].iter().collect();
        let suffix: String = chars[14..].iter().collect();
        format!("{}{}{}", prefix, "*".repeat(8), suffix)
    } else if chars.len() == 15 {
        let prefix: String = chars[..6].iter().collect();
        let suffix: String = chars[11..].iter().collect();
        format!("{}{}{}", prefix, "*".repeat(5), suffix)
    } else {
        let prefix_len = 6;
        let suffix_len = 4;
        if chars.len() <= prefix_len + suffix_len {
            return "*".repeat(chars.len());
        }
        let mask_len = chars.len() - prefix_len - suffix_len;
        let prefix: String = chars[..prefix_len].iter().collect();
        let suffix: String = chars[chars.len() - suffix_len..].iter().collect();
        format!("{}{}{}", prefix, "*".repeat(mask_len), suffix)
    }
}

pub fn mask_email(email: &str) -> String {
    if email.is_empty() {
        return email.to_string();
    }
    if let Some(at_pos) = email.find('@') {
        let username = &email[..at_pos];
        let domain = &email[at_pos..];
        if username.is_empty() {
            return email.to_string();
        }
        if username.len() == 1 {
            format!("*{}", domain)
        } else if username.len() == 2 {
            format!("{}*{}", &username[..1], domain)
        } else {
            let first_char = &username[..1];
            let last_char = &username[username.len() - 1..];
            let mask_len = username.len() - 2;
            format!("{}{}{}{}", first_char, "*".repeat(mask_len), last_char, domain)
        }
    } else {
        email.to_string()
    }
}

pub fn mask_credit_card(card: &str) -> String {
    if card.is_empty() {
        return card.to_string();
    }
    let digits: String = card.chars().filter(|c| c.is_ascii_digit()).collect();
    if digits.len() < 8 {
        return "*".repeat(card.len());
    }
    let prefix_len = 4;
    let suffix_len = 4;
    let mask_len = digits.len() - prefix_len - suffix_len;
    format!(
        "{}{}{}",
        &digits[..prefix_len],
        "*".repeat(mask_len),
        &digits[digits.len() - suffix_len..]
    )
}

pub fn encrypt_field(value: &str, key: &Aes256GcmKey) -> Result<String, AppError> {
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key.0));

    let mut nonce_bytes = [0u8; 12];
    rand::thread_rng().fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);

    let ciphertext = cipher
        .encrypt(nonce, value.as_bytes())
        .map_err(|e| AppError::Internal(format!("encryption failed: {}", e)))?;

    let mut combined = Vec::with_capacity(nonce_bytes.len() + ciphertext.len());
    combined.extend_from_slice(&nonce_bytes);
    combined.extend_from_slice(&ciphertext);

    Ok(BASE64.encode(combined))
}

pub fn decrypt_field(ciphertext: &str, key: &Aes256GcmKey) -> Result<String, AppError> {
    let combined = BASE64
        .decode(ciphertext)
        .map_err(|e| AppError::Internal(format!("base64 decode failed: {}", e)))?;

    if combined.len() < 12 {
        return Err(AppError::Internal(
            "invalid ciphertext: too short".to_string(),
        ));
    }

    let (nonce_bytes, ciphertext_bytes) = combined.split_at(12);
    let nonce = Nonce::from_slice(nonce_bytes);

    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key.0));

    let plaintext = cipher
        .decrypt(nonce, ciphertext_bytes)
        .map_err(|e| AppError::Internal(format!("decryption failed: {}", e)))?;

    String::from_utf8(plaintext)
        .map_err(|e| AppError::Internal(format!("utf8 decode failed: {}", e)))
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum MaskRule {
    PhoneMask,
    IdCardMask,
    EmailMask,
    CreditCardMask,
    CustomMask {
        pattern: String,
        replacement: String,
    },
}

impl MaskRule {
    pub fn apply(&self, value: &str) -> String {
        match self {
            MaskRule::PhoneMask => mask_phone(value),
            MaskRule::IdCardMask => mask_id_card(value),
            MaskRule::EmailMask => mask_email(value),
            MaskRule::CreditCardMask => mask_credit_card(value),
            MaskRule::CustomMask { pattern, replacement } => {
                if let Ok(re) = Regex::new(pattern) {
                    re.replace_all(value, replacement.as_str()).to_string()
                } else {
                    value.to_string()
                }
            }
        }
    }
}

#[derive(Debug, Clone)]
pub struct DataMasker {
    rules: HashMap<String, Vec<MaskRule>>,
}

impl Default for DataMasker {
    fn default() -> Self {
        Self::new()
    }
}

impl DataMasker {
    pub fn new() -> Self {
        let mut rules: HashMap<String, Vec<MaskRule>> = HashMap::new();

        rules.insert("phone".to_string(), vec![MaskRule::PhoneMask]);
        rules.insert("mobile".to_string(), vec![MaskRule::PhoneMask]);
        rules.insert("phone_number".to_string(), vec![MaskRule::PhoneMask]);
        rules.insert("mobile_phone".to_string(), vec![MaskRule::PhoneMask]);
        rules.insert("id_card".to_string(), vec![MaskRule::IdCardMask]);
        rules.insert("idcard".to_string(), vec![MaskRule::IdCardMask]);
        rules.insert("id_number".to_string(), vec![MaskRule::IdCardMask]);
        rules.insert("identity_card".to_string(), vec![MaskRule::IdCardMask]);
        rules.insert("email".to_string(), vec![MaskRule::EmailMask]);
        rules.insert("email_address".to_string(), vec![MaskRule::EmailMask]);
        rules.insert("card_no".to_string(), vec![MaskRule::CreditCardMask]);
        rules.insert("card_number".to_string(), vec![MaskRule::CreditCardMask]);
        rules.insert("credit_card".to_string(), vec![MaskRule::CreditCardMask]);
        rules.insert("bank_card".to_string(), vec![MaskRule::CreditCardMask]);

        Self { rules }
    }

    pub fn add_rule(&mut self, field: &str, rule: MaskRule) -> &mut Self {
        self.rules
            .entry(field.to_string())
            .or_default()
            .push(rule);
        self
    }

    pub fn mask_value(&self, field: &str, value: &str) -> String {
        if let Some(field_rules) = self.rules.get(field) {
            let mut result = value.to_string();
            for rule in field_rules {
                result = rule.apply(&result);
            }
            result
        } else {
            let lower_field = field.to_lowercase();
            for (rule_field, field_rules) in &self.rules {
                if lower_field.contains(rule_field) {
                    let mut result = value.to_string();
                    for rule in field_rules {
                        result = rule.apply(&result);
                    }
                    return result;
                }
            }
            value.to_string()
        }
    }

    pub fn mask_json(&self, input: &serde_json::Value) -> serde_json::Value {
        match input {
            serde_json::Value::Object(obj) => {
                let mut masked = serde_json::Map::new();
                for (key, value) in obj {
                    let masked_value = match value {
                        serde_json::Value::String(s) => {
                            serde_json::Value::String(self.mask_value(key, s))
                        }
                        other => self.mask_json(other),
                    };
                    masked.insert(key.clone(), masked_value);
                }
                serde_json::Value::Object(masked)
            }
            serde_json::Value::Array(arr) => {
                serde_json::Value::Array(arr.iter().map(|v| self.mask_json(v)).collect())
            }
            other => other.clone(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_mask_phone_11_digits() {
        assert_eq!(mask_phone("13812341234"), "138****1234");
    }

    #[test]
    fn test_mask_phone_short() {
        assert_eq!(mask_phone("123456"), "******");
    }

    #[test]
    fn test_mask_phone_with_format() {
        assert_eq!(mask_phone("138-1234-1234"), "138****1234");
    }

    #[test]
    fn test_mask_id_card_18_digits() {
        assert_eq!(
            mask_id_card("110101199001011234"),
            "110101********1234"
        );
    }

    #[test]
    fn test_mask_id_card_15_digits() {
        assert_eq!(mask_id_card("110101900101123"), "110101*****123");
    }

    #[test]
    fn test_mask_email() {
        assert_eq!(mask_email("test@example.com"), "t**t@example.com");
        assert_eq!(mask_email("a@example.com"), "*@example.com");
        assert_eq!(mask_email("ab@example.com"), "a*@example.com");
    }

    #[test]
    fn test_encrypt_decrypt_roundtrip() {
        let key = Aes256GcmKey::generate();
        let plaintext = "13812341234";
        let encrypted = encrypt_field(plaintext, &key).unwrap();
        let decrypted = decrypt_field(&encrypted, &key).unwrap();
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn test_encrypt_decrypt_id_card() {
        let key = Aes256GcmKey::generate();
        let plaintext = "110101199001011234";
        let encrypted = encrypt_field(plaintext, &key).unwrap();
        assert_ne!(encrypted, plaintext);
        let decrypted = decrypt_field(&encrypted, &key).unwrap();
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn test_data_masker_phone_field() {
        let masker = DataMasker::new();
        assert_eq!(masker.mask_value("phone", "13812341234"), "138****1234");
    }

    #[test]
    fn test_data_masker_fuzzy_match() {
        let masker = DataMasker::new();
        assert_eq!(
            masker.mask_value("user_phone_number", "13812341234"),
            "138****1234"
        );
    }
}
