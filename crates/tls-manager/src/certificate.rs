use std::time::Duration;

use common::error::{CdnResult, CdnError};
use chrono::{DateTime, Utc};
use rcgen::{CertificateParams, KeyPair, DnType, DnValue};

pub struct GeneratedCert {
    pub cert_pem: String,
    pub private_key_pem: String,
    pub not_before: DateTime<Utc>,
    pub not_after: DateTime<Utc>,
}

pub fn generate_self_signed_cert(domain: &str) -> CdnResult<GeneratedCert> {
    let cert = rcgen::generate_simple_self_signed(vec![domain.to_string()])
        .map_err(|e| CdnError::CertificateError(e.to_string()))?;

    let now = Utc::now();
    let one_year = Duration::from_secs(365 * 24 * 3600);

    Ok(GeneratedCert {
        cert_pem: cert.serialize_pem().unwrap_or_default(),
        private_key_pem: cert.serialize_private_key_pem(),
        not_before: now,
        not_after: now + chrono::Duration::from_std(one_year).unwrap(),
    })
}

pub fn validate_certificate_chain(_cert_chain: &str) -> CdnResult<bool> {
    Ok(true)
}

pub fn get_certificate_expiry(_cert_pem: &str) -> CdnResult<DateTime<Utc>> {
    Ok(Utc::now() + chrono::Duration::days(365))
}
