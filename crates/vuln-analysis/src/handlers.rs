use anyhow::Result;
use chrono::Utc;
use rusqlite::Connection;
use std::sync::Arc;
use std::time::Instant;
use uuid::Uuid;

use crate::event_bus::{EventPublisher, SbomUploadedPayload, VulnerabilityDetectedPayload, CriticalVulnerabilityPayload, FixRecommendationPayload, AnalysisCompletedPayload};
use crate::models::{
    CveVulnerability, PackageType, SbomDocument, AnalysisReport, Severity,
};
use crate::sbom::SbomParser;
use crate::vulnerability::VulnerabilityDatabase;
use crate::recommendation::RecommendationEngine;

pub struct VulnAnalysisService {
    db: VulnerabilityDatabase,
    publisher: EventPublisher,
}

impl VulnAnalysisService {
    pub fn new(db: VulnerabilityDatabase, publisher: EventPublisher) -> Self {
        Self { db, publisher }
    }

    pub fn parse_sbom(input: &str) -> Result<SbomDocument> {
        SbomParser::parse(input)
    }

    pub fn analyze_sbom(&self, sbom: &SbomDocument, uploaded_by: &str) -> Result<AnalysisReport> {
        let start = Instant::now();
        let total_packages = sbom.packages.len();

        self.publisher.publish_sbom_uploaded(SbomUploadedPayload {
            sbom_id: Uuid::new_v4(),
            sbom_name: sbom.name.clone(),
            package_count: total_packages,
            uploaded_by: uploaded_by.to_string(),
        });

        let matches = self.db.match_vulnerabilities(sbom);
        let vulnerable_packages = matches.iter().map(|m| m.package.name.clone()).collect::<std::collections::HashSet<_>>().len();

        for m in &matches {
            self.publisher.publish_vulnerability_detected(VulnerabilityDetectedPayload {
                sbom_id: Uuid::new_v4(),
                cve_id: m.vulnerability.cve_id.clone(),
                package_name: m.package.name.clone(),
                package_version: m.package.version.clone(),
                severity: m.vulnerability.severity.clone(),
                cvss_score: m.vulnerability.cvss_score,
            });

            if m.vulnerability.severity == Severity::Critical {
                self.publisher.publish_critical_vulnerability(CriticalVulnerabilityPayload {
                    sbom_id: Uuid::new_v4(),
                    cve_id: m.vulnerability.cve_id.clone(),
                    package_name: m.package.name.clone(),
                    cvss_score: m.vulnerability.cvss_score,
                    description: m.vulnerability.description.clone(),
                });
            }
        }

        let recommendations = RecommendationEngine::generate_recommendations(&matches);
        let risk_score = RecommendationEngine::calculate_risk_score(&matches);

        for rec in &recommendations {
            self.publisher.publish_fix_recommendation(FixRecommendationPayload {
                sbom_id: Uuid::new_v4(),
                package_name: rec.package_name.clone(),
                current_version: rec.current_version.clone(),
                recommended_version: rec.recommended_version.clone(),
                confidence: rec.confidence,
            });
        }

        let duration_ms = start.elapsed().as_millis() as u64;
        let report = AnalysisReport {
            id: Uuid::new_v4(),
            sbom_name: sbom.name.clone(),
            analyzed_at: Utc::now(),
            total_packages,
            vulnerable_packages,
            matches,
            recommendations,
            risk_score,
        };

        self.publisher.publish_analysis_completed(AnalysisCompletedPayload {
            analysis_id: report.id,
            sbom_name: report.sbom_name.clone(),
            total_packages: report.total_packages,
            vulnerable_packages: report.vulnerable_packages,
            risk_score: report.risk_score,
            duration_ms,
        });

        Ok(report)
    }

    pub fn search_vulnerabilities(&self, name: &str, package_type: PackageType) -> Result<Vec<CveVulnerability>> {
        self.db.search_by_package(name, package_type)
    }

    pub fn database(&self) -> &VulnerabilityDatabase {
        &self.db
    }

    pub fn publisher(&self) -> &EventPublisher {
        &self.publisher
    }
}

pub fn parse_sbom(input: &str) -> Result<SbomDocument> {
    SbomParser::parse(input)
}

pub fn analyze_sbom(sbom: &SbomDocument, db: &VulnerabilityDatabase) -> Result<AnalysisReport> {
    let total_packages = sbom.packages.len();
    let matches = db.match_vulnerabilities(sbom);
    let vulnerable_packages = matches.iter().map(|m| m.package.name.clone()).collect::<std::collections::HashSet<_>>().len();
    let recommendations = RecommendationEngine::generate_recommendations(&matches);
    let risk_score = RecommendationEngine::calculate_risk_score(&matches);
    Ok(AnalysisReport {
        id: Uuid::new_v4(),
        sbom_name: sbom.name.clone(),
        analyzed_at: Utc::now(),
        total_packages,
        vulnerable_packages,
        matches,
        recommendations,
        risk_score,
    })
}

pub fn search_vulnerabilities(db: &VulnerabilityDatabase, name: &str, package_type: PackageType) -> Result<Vec<CveVulnerability>> {
    db.search_by_package(name, package_type)
}

pub fn get_recommendations(matches: &[crate::models::VulnerabilityMatch]) -> Vec<crate::models::FixRecommendation> {
    RecommendationEngine::generate_recommendations(matches)
}

pub fn create_db_connection(path: &str) -> Result<VulnerabilityDatabase> {
    let conn = if path == ":memory:" {
        Connection::open_in_memory()?
    } else {
        Connection::open(path)?
    };
    let db = VulnerabilityDatabase::new(conn);
    db.init_schema()?;
    Ok(db)
}

pub fn create_service_with_event_bus(db: VulnerabilityDatabase, bus: Arc<crate::event_bus::EventBus>, source: &str) -> VulnAnalysisService {
    let publisher = EventPublisher::new(bus, source);
    VulnAnalysisService::new(db, publisher)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::event_bus::EventBus;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[test]
    fn test_service_publishes_events() {
        let bus = Arc::new(EventBus::new());
        let counter = Arc::new(AtomicUsize::new(0));
        let counter_clone = counter.clone();

        bus.subscribe(crate::event_bus::EventType::AnalysisCompleted, move |_event| {
            counter_clone.fetch_add(1, Ordering::SeqCst);
        });

        let conn = Connection::open_in_memory().unwrap();
        let db = VulnerabilityDatabase::new(conn);
        let _ = db.init_schema();

        let service = create_service_with_event_bus(db, bus.clone(), "test");

        let sbom = SbomDocument {
            format: crate::models::SbomFormat::Spdx,
            name: "test".to_string(),
            version: "1.0".to_string(),
            packages: vec![],
        };

        let _ = service.analyze_sbom(&sbom, "test_user");

        assert_eq!(counter.load(Ordering::SeqCst), 1);
    }
}
