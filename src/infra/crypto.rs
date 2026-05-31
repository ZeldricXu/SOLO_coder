use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};
use rand::RngCore;
use rsa::{
    pkcs8::{DecodePrivateKey, DecodePublicKey, EncodePrivateKey, EncodePublicKey},
    Pkcs1v15Encrypt, RsaPrivateKey, RsaPublicKey,
};
use sha2::{Digest, Sha256, Sha512};
use hmac::{Hmac, Mac};
use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};

use crate::infra::error::{AppError, AppResult};

type HmacSha256 = Hmac<Sha256>;

pub struct CryptoService;

impl CryptoService {
    pub fn new() -> Self {
        Self
    }

    pub fn sha256_hash(data: &[u8]) -> Vec<u8> {
        let mut hasher = Sha256::new();
        hasher.update(data);
        hasher.finalize().to_vec()
    }

    pub fn sha512_hash(data: &[u8]) -> Vec<u8> {
        let mut hasher = Sha512::new();
        hasher.update(data);
        hasher.finalize().to_vec()
    }

    pub fn sha256_hex(data: &[u8]) -> String {
        hex::encode(Self::sha256_hash(data))
    }

    pub fn hmac_sha256(key: &[u8], data: &[u8]) -> AppResult<Vec<u8>> {
        let mut mac = HmacSha256::new_from_slice(key)
            .map_err(|e| AppError::CryptoError(format!("HMAC init error: {}", e)))?;
        mac.update(data);
        Ok(mac.finalize().into_bytes().to_vec())
    }

    pub fn hmac_sha256_verify(key: &[u8], data: &[u8], signature: &[u8]) -> AppResult<bool> {
        let mut mac = HmacSha256::new_from_slice(key)
            .map_err(|e| AppError::CryptoError(format!("HMAC init error: {}", e)))?;
        mac.update(data);
        Ok(mac.verify_slice(signature).is_ok())
    }

    pub fn generate_aes_key() -> Vec<u8> {
        let mut key = vec![0u8; 32];
        rand::thread_rng().fill_bytes(&mut key);
        key
    }

    pub fn aes_encrypt(key: &[u8], plaintext: &[u8]) -> AppResult<Vec<u8>> {
        let cipher = Aes256Gcm::new_from_slice(key)
            .map_err(|e| AppError::CryptoError(format!("AES init error: {}", e)))?;

        let mut nonce_bytes = [0u8; 12];
        rand::thread_rng().fill_bytes(&mut nonce_bytes);
        let nonce = Nonce::from_slice(&nonce_bytes);

        let ciphertext = cipher
            .encrypt(nonce, plaintext)
            .map_err(|e| AppError::CryptoError(format!("AES encrypt error: {}", e)))?;

        let mut result = Vec::with_capacity(nonce_bytes.len() + ciphertext.len());
        result.extend_from_slice(&nonce_bytes);
        result.extend_from_slice(&ciphertext);
        Ok(result)
    }

    pub fn aes_decrypt(key: &[u8], ciphertext: &[u8]) -> AppResult<Vec<u8>> {
        if ciphertext.len() < 12 {
            return Err(AppError::CryptoError("Ciphertext too short".into()));
        }

        let cipher = Aes256Gcm::new_from_slice(key)
            .map_err(|e| AppError::CryptoError(format!("AES init error: {}", e)))?;

        let (nonce_bytes, encrypted_data) = ciphertext.split_at(12);
        let nonce = Nonce::from_slice(nonce_bytes);

        cipher
            .decrypt(nonce, encrypted_data)
            .map_err(|e| AppError::CryptoError(format!("AES decrypt error: {}", e)))
    }

    pub fn generate_rsa_keypair(bits: usize) -> AppResult<(RsaPrivateKey, RsaPublicKey)> {
        let mut rng = rand::thread_rng();
        let private_key = RsaPrivateKey::new(&mut rng, bits)
            .map_err(|e| AppError::CryptoError(format!("RSA key gen error: {}", e)))?;
        let public_key = private_key.to_public_key();
        Ok((private_key, public_key))
    }

    pub fn rsa_encrypt(public_key: &RsaPublicKey, plaintext: &[u8]) -> AppResult<Vec<u8>> {
        let mut rng = rand::thread_rng();
        public_key
            .encrypt(&mut rng, Pkcs1v15Encrypt, plaintext)
            .map_err(|e| AppError::CryptoError(format!("RSA encrypt error: {}", e)))
    }

    pub fn rsa_decrypt(private_key: &RsaPrivateKey, ciphertext: &[u8]) -> AppResult<Vec<u8>> {
        private_key
            .decrypt(Pkcs1v15Encrypt, ciphertext)
            .map_err(|e| AppError::CryptoError(format!("RSA decrypt error: {}", e)))
    }

    pub fn rsa_sign(private_key: &RsaPrivateKey, data: &[u8]) -> AppResult<Vec<u8>> {
        use rsa::signature::{Signer, SignatureEncoding};
        let signing_key = rsa::pkcs1v15::SigningKey::<Sha256>::new(private_key.clone());
        let signature = signing_key.sign(data);
        Ok(signature.to_vec())
    }

    pub fn rsa_verify(public_key: &RsaPublicKey, data: &[u8], signature: &[u8]) -> AppResult<bool> {
        use rsa::signature::{Verifier, SignatureEncoding};
        let verifying_key = rsa::pkcs1v15::VerifyingKey::<Sha256>::new(public_key.clone());
        let sig = rsa::pkcs1v15::Signature::try_from(signature)
            .map_err(|e| AppError::CryptoError(format!("Invalid signature format: {}", e)))?;
        Ok(verifying_key.verify(data, &sig).is_ok())
    }

    pub fn random_bytes(len: usize) -> Vec<u8> {
        let mut bytes = vec![0u8; len];
        rand::thread_rng().fill_bytes(&mut bytes);
        bytes
    }

    pub fn base64_encode(data: &[u8]) -> String {
        BASE64.encode(data)
    }

    pub fn base64_decode(data: &str) -> AppResult<Vec<u8>> {
        BASE64.decode(data)
            .map_err(|e| AppError::CryptoError(format!("Base64 decode error: {}", e)))
    }

    pub fn rsa_public_key_to_pem(public_key: &RsaPublicKey) -> AppResult<String> {
        public_key
            .to_public_key_pem(rsa::pkcs8::LineEnding::LF)
            .map_err(|e| AppError::CryptoError(format!("PEM encode error: {}", e)))
    }

    pub fn rsa_public_key_from_pem(pem: &str) -> AppResult<RsaPublicKey> {
        RsaPublicKey::from_public_key_pem(pem)
            .map_err(|e| AppError::CryptoError(format!("PEM decode error: {}", e)))
    }

    pub fn rsa_private_key_to_pem(private_key: &RsaPrivateKey) -> AppResult<String> {
        private_key
            .to_pkcs8_pem(rsa::pkcs8::LineEnding::LF)
            .map_err(|e| AppError::CryptoError(format!("PEM encode error: {}", e)))
            .map(|s| s.to_string())
    }

    pub fn rsa_private_key_from_pem(pem: &str) -> AppResult<RsaPrivateKey> {
        RsaPrivateKey::from_pkcs8_pem(pem)
            .map_err(|e| AppError::CryptoError(format!("PEM decode error: {}", e)))
    }
}
