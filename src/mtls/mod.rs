use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;
use std::sync::{Arc, Mutex};
use chrono::{DateTime, Utc, Duration};


#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum CertificateStatus {
    Active,
    Expired,
    Revoked,
    Pending,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Certificate {
    pub id: String,
    pub common_name: String,
    pub serial_number: String,
    pub status: CertificateStatus,
    pub pem_data: String,
    pub private_key_pem: Option<String>,
    pub issuer_id: Option<String>,
    pub valid_from: DateTime<Utc>,
    pub valid_to: DateTime<Utc>,
    pub created_at: DateTime<Utc>,
    pub revoked_at: Option<DateTime<Utc>>,
    pub subject_alt_names: Vec<String>,
    pub key_type: String,
    pub key_size: u32,
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RotationPolicy {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub auto_rotate: bool,
    pub rotate_before_days: i64,
    pub notification_before_days: i64,
    pub key_algorithm: String,
    pub key_size: u32,
    pub validity_days: i64,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RevocationListEntry {
    pub serial_number: String,
    pub revoked_at: DateTime<Utc>,
    pub reason: String,
    pub certificate_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CertificateAuthority {
    pub id: String,
    pub name: String,
    pub certificate: Certificate,
    pub is_root: bool,
    pub parent_ca_id: Option<String>,
    pub max_path_length: i32,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone)]
pub struct MtlsManager {
    certificates: Arc<Mutex<HashMap<String, Certificate>>>,
    cas: Arc<Mutex<HashMap<String, CertificateAuthority>>>,
    rotation_policies: Arc<Mutex<HashMap<String, RotationPolicy>>>,
    revocation_list: Arc<Mutex<Vec<RevocationListEntry>>>,
}

impl MtlsManager {
    pub fn new() -> Self {
        Self {
            certificates: Arc::new(Mutex::new(HashMap::new())),
            cas: Arc::new(Mutex::new(HashMap::new())),
            rotation_policies: Arc::new(Mutex::new(HashMap::new())),
            revocation_list: Arc::new(Mutex::new(Vec::new())),
        }
    }

    pub fn create_ca(&self, name: &str, common_name: &str, is_root: bool, parent_ca_id: Option<String>) -> CertificateAuthority {
        let id = Uuid::new_v4().to_string();
        let now = Utc::now();
        let valid_to = now + Duration::days(3650);

        let ca_cert = Certificate {
            id: Uuid::new_v4().to_string(),
            common_name: common_name.to_string(),
            serial_number: Self::generate_serial_number(),
            status: CertificateStatus::Active,
            pem_data: format!("-----BEGIN CERTIFICATE-----\nCA_CERT_DATA_{}\n-----END CERTIFICATE-----", id),
            private_key_pem: Some(format!("-----BEGIN PRIVATE KEY-----\nCA_PRIVATE_KEY_{}\n-----END PRIVATE KEY-----", id)),
            issuer_id: if is_root { None } else { parent_ca_id.clone() },
            valid_from: now,
            valid_to,
            created_at: now,
            revoked_at: None,
            subject_alt_names: vec![],
            key_type: "RSA".to_string(),
            key_size: 4096,
            labels: HashMap::new(),
        };

        let ca = CertificateAuthority {
            id,
            name: name.to_string(),
            certificate: ca_cert,
            is_root,
            parent_ca_id,
            max_path_length: if is_root { 2 } else { 0 },
            created_at: now,
        };

        let mut cas = self.cas.lock().unwrap();
        cas.insert(ca.id.clone(), ca.clone());

        let mut certs = self.certificates.lock().unwrap();
        certs.insert(ca.certificate.id.clone(), ca.certificate.clone());

        ca
    }

    pub fn issue_certificate(&self, ca_id: &str, common_name: &str, subject_alt_names: Vec<String>, policy_id: Option<&str>) -> Option<Certificate> {
        let cas = self.cas.lock().unwrap();
        let ca = cas.get(ca_id)?;

        let policy = policy_id.and_then(|pid| {
            let policies = self.rotation_policies.lock().unwrap();
            policies.get(pid).cloned()
        });

        let validity_days = policy.as_ref().map(|p| p.validity_days).unwrap_or(365);
        let key_size = policy.as_ref().map(|p| p.key_size).unwrap_or(2048);
        let key_algorithm = policy.as_ref().map(|p| p.key_algorithm.clone()).unwrap_or_else(|| "RSA".to_string());

        let id = Uuid::new_v4().to_string();
        let now = Utc::now();
        let valid_to = now + Duration::days(validity_days);

        let cert = Certificate {
            id: id.clone(),
            common_name: common_name.to_string(),
            serial_number: Self::generate_serial_number(),
            status: CertificateStatus::Active,
            pem_data: format!("-----BEGIN CERTIFICATE-----\nCERT_DATA_{}\n-----END CERTIFICATE-----", id),
            private_key_pem: Some(format!("-----BEGIN PRIVATE KEY-----\nPRIVATE_KEY_{}\n-----END PRIVATE KEY-----", id)),
            issuer_id: Some(ca.certificate.id.clone()),
            valid_from: now,
            valid_to,
            created_at: now,
            revoked_at: None,
            subject_alt_names,
            key_type: key_algorithm,
            key_size,
            labels: HashMap::new(),
        };

        let mut certs = self.certificates.lock().unwrap();
        certs.insert(id, cert.clone());

        Some(cert)
    }

    pub fn get_certificate(&self, cert_id: &str) -> Option<Certificate> {
        let certs = self.certificates.lock().unwrap();
        certs.get(cert_id).cloned()
    }

    pub fn get_certificate_by_serial(&self, serial_number: &str) -> Option<Certificate> {
        let certs = self.certificates.lock().unwrap();
        certs.values().find(|c| c.serial_number == serial_number).cloned()
    }

    pub fn list_certificates(&self) -> Vec<Certificate> {
        let certs = self.certificates.lock().unwrap();
        certs.values().cloned().collect()
    }

    pub fn revoke_certificate(&self, cert_id: &str, reason: &str) -> Option<Certificate> {
        let mut certs = self.certificates.lock().unwrap();
        let cert = certs.get_mut(cert_id)?;
        
        if cert.status == CertificateStatus::Active {
            cert.status = CertificateStatus::Revoked;
            cert.revoked_at = Some(Utc::now());

            let mut crl = self.revocation_list.lock().unwrap();
            crl.push(RevocationListEntry {
                serial_number: cert.serial_number.clone(),
                revoked_at: cert.revoked_at.unwrap(),
                reason: reason.to_string(),
                certificate_id: cert.id.clone(),
            });

            return Some(cert.clone());
        }
        None
    }

    pub fn is_revoked(&self, serial_number: &str) -> bool {
        let crl = self.revocation_list.lock().unwrap();
        crl.iter().any(|e| e.serial_number == serial_number)
    }

    pub fn get_revocation_list(&self) -> Vec<RevocationListEntry> {
        let crl = self.revocation_list.lock().unwrap();
        crl.clone()
    }

    pub fn create_rotation_policy(
        &self,
        name: &str,
        auto_rotate: bool,
        rotate_before_days: i64,
        notification_before_days: i64,
        validity_days: i64,
    ) -> RotationPolicy {
        let id = Uuid::new_v4().to_string();
        let now = Utc::now();

        let policy = RotationPolicy {
            id: id.clone(),
            name: name.to_string(),
            enabled: true,
            auto_rotate,
            rotate_before_days,
            notification_before_days,
            key_algorithm: "RSA".to_string(),
            key_size: 2048,
            validity_days,
            created_at: now,
            updated_at: now,
        };

        let mut policies = self.rotation_policies.lock().unwrap();
        policies.insert(id, policy.clone());
        policy
    }

    pub fn get_rotation_policy(&self, policy_id: &str) -> Option<RotationPolicy> {
        let policies = self.rotation_policies.lock().unwrap();
        policies.get(policy_id).cloned()
    }

    pub fn list_rotation_policies(&self) -> Vec<RotationPolicy> {
        let policies = self.rotation_policies.lock().unwrap();
        policies.values().cloned().collect()
    }

    pub fn check_and_rotate(&self, cert_id: &str, ca_id: &str) -> Option<Certificate> {
        let (cert_clone, policy_clone) = {
            let certs = self.certificates.lock().unwrap();
            let cert = certs.get(cert_id)?.clone();

            let policies = self.rotation_policies.lock().unwrap();
            let policy = policies.values().find(|_| true)?.clone();
            (cert, policy)
        };

        let now = Utc::now();
        let days_until_expiry = (cert_clone.valid_to - now).num_days();

        if policy_clone.enabled && policy_clone.auto_rotate && days_until_expiry <= policy_clone.rotate_before_days {
            let new_cert = self.issue_certificate(
                ca_id,
                &cert_clone.common_name,
                cert_clone.subject_alt_names.clone(),
                Some(&policy_clone.id),
            );

            if let Some(ref new) = new_cert {
                let mut certs = self.certificates.lock().unwrap();
                if let Some(old) = certs.get_mut(cert_id) {
                    old.status = CertificateStatus::Expired;
                }
                certs.insert(new.id.clone(), new.clone());
            }

            return new_cert;
        }

        None
    }

    pub fn get_expiring_certificates(&self, days_threshold: i64) -> Vec<Certificate> {
        let certs = self.certificates.lock().unwrap();
        let now = Utc::now();
        certs.values()
            .filter(|c| c.status == CertificateStatus::Active)
            .filter(|c| {
                let days_until_expiry = (c.valid_to - now).num_days();
                days_until_expiry <= days_threshold
            })
            .cloned()
            .collect()
    }

    pub fn validate_certificate_chain(&self, cert_id: &str, trusted_ca_ids: &[String]) -> bool {
        let certs = self.certificates.lock().unwrap();
        let _cas = self.cas.lock().unwrap();
        let crl = self.revocation_list.lock().unwrap();

        let mut current_cert_id = Some(cert_id.to_string());

        while let Some(cid) = current_cert_id {
            let cert = match certs.get(&cid) {
                Some(c) => c,
                None => return false,
            };

            if cert.status != CertificateStatus::Active {
                return false;
            }

            if crl.iter().any(|e| e.serial_number == cert.serial_number) {
                return false;
            }

            let now = Utc::now();
            if now < cert.valid_from || now > cert.valid_to {
                return false;
            }

            if trusted_ca_ids.contains(&cid) {
                return true;
            }

            current_cert_id = cert.issuer_id.clone();
        }

        false
    }

    pub fn list_cas(&self) -> Vec<CertificateAuthority> {
        let cas = self.cas.lock().unwrap();
        cas.values().cloned().collect()
    }

    pub fn get_ca(&self, ca_id: &str) -> Option<CertificateAuthority> {
        let cas = self.cas.lock().unwrap();
        cas.get(ca_id).cloned()
    }

    fn generate_serial_number() -> String {
        use rand::Rng;
        let mut rng = rand::thread_rng();
        let bytes: Vec<u8> = (0..20).map(|_| rng.gen()).collect();
        bytes.iter().map(|b| format!("{:02X}", b)).collect()
    }
}

impl Default for MtlsManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_create_ca() {
        let manager = MtlsManager::new();
        let ca = manager.create_ca("Root CA", "root.example.com", true, None);
        
        assert!(ca.is_root);
        assert_eq!(ca.name, "Root CA");
        assert_eq!(ca.certificate.common_name, "root.example.com");
    }

    #[test]
    fn test_issue_certificate() {
        let manager = MtlsManager::new();
        let ca = manager.create_ca("Root CA", "root.example.com", true, None);
        
        let cert = manager.issue_certificate(
            &ca.id,
            "service.example.com",
            vec!["service.example.com".to_string(), "api.example.com".to_string()],
            None,
        );
        
        assert!(cert.is_some());
        let cert = cert.unwrap();
        assert_eq!(cert.common_name, "service.example.com");
        assert_eq!(cert.subject_alt_names.len(), 2);
        assert_eq!(cert.status, CertificateStatus::Active);
    }

    #[test]
    fn test_revoke_certificate() {
        let manager = MtlsManager::new();
        let ca = manager.create_ca("Root CA", "root.example.com", true, None);
        let cert = manager.issue_certificate(&ca.id, "service.example.com", vec![], None).unwrap();
        
        let revoked = manager.revoke_certificate(&cert.id, "Key compromise");
        assert!(revoked.is_some());
        assert_eq!(revoked.unwrap().status, CertificateStatus::Revoked);
        
        assert!(manager.is_revoked(&cert.serial_number));
    }

    #[test]
    fn test_rotation_policy() {
        let manager = MtlsManager::new();
        let policy = manager.create_rotation_policy("standard", true, 30, 45, 365);
        
        assert_eq!(policy.name, "standard");
        assert!(policy.auto_rotate);
        assert_eq!(policy.rotate_before_days, 30);
        assert_eq!(policy.validity_days, 365);
    }

    #[test]
    fn test_get_expiring_certificates() {
        let manager = MtlsManager::new();
        let ca = manager.create_ca("Root CA", "root.example.com", true, None);
        
        let _cert = manager.issue_certificate(&ca.id, "service.example.com", vec![], None);
        
        let expiring = manager.get_expiring_certificates(3650);
        assert!(!expiring.is_empty());
    }

    #[test]
    fn test_validate_certificate_chain() {
        let manager = MtlsManager::new();
        let root_ca = manager.create_ca("Root CA", "root.example.com", true, None);
        let cert = manager.issue_certificate(&root_ca.id, "service.example.com", vec![], None).unwrap();
        
        assert!(manager.validate_certificate_chain(&cert.id, &[root_ca.certificate.id.clone()]));
        assert!(!manager.validate_certificate_chain(&cert.id, &["unknown-ca".to_string()]));
    }
}
