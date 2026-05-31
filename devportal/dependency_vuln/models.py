from datetime import datetime, timezone
from sqlalchemy import Column, String, Integer, DateTime, JSON, Float, Boolean, ForeignKey, Text, Enum
import enum

from ..core.database import Base
from ..core.models import CoreEntity, generate_id


class SBOMFormat(str, enum.Enum):
    SPDX = "spdx"
    CYCLONEDX = "cyclonedx"
    SWID = "swid"
    CUSTOM = "custom"


class VulnerabilitySeverity(str, enum.Enum):
    NONE = "none"
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class DependencyStatus(str, enum.Enum):
    VULNERABLE = "vulnerable"
    OUTDATED = "outdated"
    LATEST = "latest"
    UNKNOWN = "unknown"


class SBOM(CoreEntity):
    __tablename__ = "sboms"

    type = Column(String, nullable=False, default="sbom")
    name = Column(String, nullable=False, index=True)
    version = Column(String, nullable=False, default="1.0.0")
    format = Column(String, nullable=False, default=SBOMFormat.CYCLONEDX)
    content = Column(Text, nullable=False)
    content_hash = Column(String, nullable=False, index=True)
    project_name = Column(String, index=True)
    project_version = Column(String)
    namespace = Column(String, default="default", index=True)
    total_dependencies = Column(Integer, default=0)
    total_vulnerabilities = Column(Integer, default=0)
    critical_count = Column(Integer, default=0)
    high_count = Column(Integer, default=0)
    medium_count = Column(Integer, default=0)
    low_count = Column(Integer, default=0)
    last_scanned_at = Column(DateTime, nullable=True)
    scan_status = Column(String, default="pending")


class Dependency(Base):
    __tablename__ = "dependencies"

    id = Column(String, primary_key=True, default=lambda: generate_id("dep"))
    sbom_id = Column(String, ForeignKey("sboms.id"), nullable=False, index=True)
    name = Column(String, nullable=False, index=True)
    version = Column(String, nullable=False)
    package_manager = Column(String, index=True)
    ecosystem = Column(String, index=True)
    purl = Column(String, index=True)
    cpe = Column(String, nullable=True, index=True)
    license = Column(String, nullable=True)
    homepage = Column(String, nullable=True)
    description = Column(Text, nullable=True)
    scope = Column(String, default="runtime")
    direct = Column(Boolean, default=True)
    dependencies = Column(JSON, default=list)
    vulnerabilities = Column(JSON, default=list)
    status = Column(String, default=DependencyStatus.UNKNOWN)
    latest_version = Column(String, nullable=True)
    recommended_version = Column(String, nullable=True)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))


class Vulnerability(Base):
    __tablename__ = "vulnerabilities"

    id = Column(String, primary_key=True, default=lambda: generate_id("vuln"))
    cve_id = Column(String, nullable=False, unique=True, index=True)
    title = Column(String, nullable=False)
    description = Column(Text, nullable=True)
    severity = Column(String, nullable=False, default=VulnerabilitySeverity.MEDIUM)
    cvss_score = Column(Float, nullable=True)
    cvss_vector = Column(String, nullable=True)
    cwe_ids = Column(JSON, default=list)
    references = Column(JSON, default=list)
    published_date = Column(DateTime, nullable=True)
    last_modified_date = Column(DateTime, nullable=True)
    affected_packages = Column(JSON, default=list)
    fixed_versions = Column(JSON, default=list)
    exploit_available = Column(Boolean, default=False)
    exploit_maturity = Column(String, nullable=True)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))


class VulnerabilityMatch(Base):
    __tablename__ = "vulnerability_matches"

    id = Column(String, primary_key=True, default=lambda: generate_id("match"))
    sbom_id = Column(String, ForeignKey("sboms.id"), nullable=False, index=True)
    dependency_id = Column(String, ForeignKey("dependencies.id"), nullable=False, index=True)
    vulnerability_id = Column(String, ForeignKey("vulnerabilities.id"), nullable=False, index=True)
    dependency_name = Column(String, nullable=False)
    dependency_version = Column(String, nullable=False)
    cve_id = Column(String, nullable=False, index=True)
    severity = Column(String, nullable=False)
    cvss_score = Column(Float, nullable=True)
    match_type = Column(String, default="exact")
    match_confidence = Column(Float, default=1.0)
    affected_version_range = Column(String, nullable=True)
    fixed_version = Column(String, nullable=True)
    recommended_fix = Column(JSON, default=dict)
    detected_at = Column(DateTime, default=lambda: datetime.now(timezone.utc), index=True)
    status = Column(String, default="active")
    notes = Column(Text, nullable=True)


class ScanTask(CoreEntity):
    __tablename__ = "scan_tasks"

    type = Column(String, nullable=False, default="scan_task")
    sbom_id = Column(String, ForeignKey("sboms.id"), nullable=True, index=True)
    scan_type = Column(String, nullable=False, default="full")
    status = Column(String, default="pending")
    progress = Column(Float, default=0.0)
    started_at = Column(DateTime, nullable=True)
    completed_at = Column(DateTime, nullable=True)
    error_message = Column(Text, nullable=True)
    parameters = Column(JSON, default=dict)
    result_summary = Column(JSON, default=dict)
    triggered_by = Column(String, nullable=True)


class CVERemediation(Base):
    __tablename__ = "cve_remediations"

    id = Column(String, primary_key=True, default=lambda: generate_id("rem"))
    cve_id = Column(String, ForeignKey("vulnerabilities.id"), nullable=False, index=True)
    dependency_id = Column(String, ForeignKey("dependencies.id"), nullable=False, index=True)
    sbom_id = Column(String, ForeignKey("sboms.id"), nullable=False, index=True)
    package_name = Column(String, nullable=False)
    current_version = Column(String, nullable=False)
    recommended_version = Column(String, nullable=True)
    recommended_action = Column(String, default="upgrade")
    upgrade_type = Column(String, default="patch")
    breaking_changes = Column(JSON, default=list)
    alternatives = Column(JSON, default=list)
    effort_estimate = Column(String, default="low")
    risk_assessment = Column(String, default="medium")
    additional_notes = Column(Text, nullable=True)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))
    status = Column(String, default="recommended")
