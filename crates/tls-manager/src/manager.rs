use std::sync::Arc;
use tokio::sync::RwLock;
use std::collections::HashMap;
use std::time::Duration;

use common::error::{CdnResult, CdnError};
use common::models::{TlsCertificate, CertificateStatus, AlertType, AlertSeverity};
use common::db::Database;
use common::config::TlsConfig;
use common::utils::{generate_id, encrypt_string, decrypt_string};

use crate::certificate::generate_self_signed_cert;

pub struct TlsManager {
    db: Database,
    config: TlsConfig,
    certificates: Arc<RwLock<HashMap<String, TlsCertificate>>>,
}

impl TlsManager {
    pub fn new(db: Database, config: TlsConfig) -> Self {
        TlsManager {
            db,
            config,
            certificates: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn generate_certificate(&self, domain: &str, auto_renew: bool) -> CdnResult<TlsCertificate> {
        let cert = generate_self_signed_cert(domain)?;
        
        let private_key_encrypted = encrypt_string(&cert.private_key_pem, &self.config.encryption_key)?;
        
        let tls_cert = TlsCertificate {
            id: generate_id(),
            domain: domain.to_string(),
            certificate_pem: cert.cert_pem,
            private_key_encrypted,
            issuer: "Self-Signed".to_string(),
            not_before: cert.not_before,
            not_after: cert.not_after,
            auto_renew,
            status: CertificateStatus::Active,
            created_at: chrono::Utc::now(),
        };

        self.db.create_tls_certificate(&tls_cert).await?;

        let mut certs = self.certificates.write().await;
        certs.insert(domain.to_string(), tls_cert.clone());

        Ok(tls_cert)
    }

    pub async fn get_certificate(&self, domain: &str) -> Option<TlsCertificate> {
        let certs = self.certificates.read().await;
        certs.get(domain).cloned()
    }

    pub async fn get_decrypted_private_key(&self, domain: &str) -> CdnResult<String> {
        let certs = self.certificates.read().await;
        let cert = certs.get(domain)
            .ok_or_else(|| CdnError::CertificateError(format!("Certificate not found for {}", domain)))?;

        decrypt_string(&cert.private_key_encrypted, &self.config.encryption_key)
    }

    pub async fn renew_certificate(&self, domain: &str) -> CdnResult<TlsCertificate> {
        self.generate_certificate(domain, true).await
    }

    pub async fn check_expiring_certificates(&self) -> CdnResult<Vec<TlsCertificate>> {
        let expiring = self.db.get_expiring_certificates(self.config.certificate_renew_days_before as i64).await?;
        
        Ok(expiring)
    }

    pub async fn start_renewal_checker(&self) -> CdnResult<()> {
        let this = self.clone();
        
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(86400));
            
            loop {
                interval.tick().await;
                
                match this.check_expiring_certificates().await {
                    Ok(expiring) => {
                        for cert in expiring {
                            tracing::warn!("Certificate for {} expiring soon", cert.domain);

                            if cert.auto_renew {
                                let _ = this.renew_certificate(&cert.domain).await;
                            }
                        }
                    }
                    Err(e) => {
                        tracing::error!("Failed to check expiring certificates: {}", e);
                    }
                }
            }
        });

        Ok(())
    }

    pub async fn load_certificates(&self) -> CdnResult<()> {
        Ok(())
    }
}

impl Clone for TlsManager {
    fn clone(&self) -> Self {
        TlsManager {
            db: self.db.clone(),
            config: self.config.clone(),
            certificates: self.certificates.clone(),
        }
    }
}
