from datetime import datetime
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field, ConfigDict

from .models import SBOMFormat, VulnerabilitySeverity, DependencyStatus


class SBOMBase(BaseModel):
    name: str
    version: str = "1.0.0"
    format: SBOMFormat = SBOMFormat.CYCLONEDX
    content: str
    project_name: Optional[str] = None
    project_version: Optional[str] = None
    namespace: str = "default"
    attributes: Dict[str, Any] = Field(default_factory=dict)


class SBOMCreate(SBOMBase):
    pass


class SBOMUpdate(BaseModel):
    name: Optional[str] = None
    version: Optional[str] = None
    content: Optional[str] = None
    project_name: Optional[str] = None
    project_version: Optional[str] = None
    namespace: Optional[str] = None
    attributes: Optional[Dict[str, Any]] = None
    status: Optional[str] = None


class SBOMResponse(SBOMBase):
    id: str
    status: str
    content_hash: str
    total_dependencies: int
    total_vulnerabilities: int
    critical_count: int
    high_count: int
    medium_count: int
    low_count: int
    last_scanned_at: Optional[datetime] = None
    scan_status: str
    created_at: datetime
    updated_at: datetime

    model_config = ConfigDict(from_attributes=True)


class DependencyBase(BaseModel):
    sbom_id: str
    name: str
    version: str
    package_manager: Optional[str] = None
    ecosystem: Optional[str] = None
    purl: Optional[str] = None
    cpe: Optional[str] = None
    license: Optional[str] = None
    homepage: Optional[str] = None
    description: Optional[str] = None
    scope: str = "runtime"
    direct: bool = True
    dependencies: List[str] = Field(default_factory=list)


class DependencyResponse(DependencyBase):
    id: str
    vulnerabilities: List[Dict[str, Any]] = Field(default_factory=list)
    status: str
    latest_version: Optional[str] = None
    recommended_version: Optional[str] = None
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)


class VulnerabilityBase(BaseModel):
    cve_id: str
    title: str
    description: Optional[str] = None
    severity: VulnerabilitySeverity = VulnerabilitySeverity.MEDIUM
    cvss_score: Optional[float] = None
    cvss_vector: Optional[str] = None
    cwe_ids: List[str] = Field(default_factory=list)
    references: List[Dict[str, Any]] = Field(default_factory=list)
    published_date: Optional[datetime] = None
    last_modified_date: Optional[datetime] = None
    affected_packages: List[Dict[str, Any]] = Field(default_factory=list)
    fixed_versions: List[str] = Field(default_factory=list)
    exploit_available: bool = False
    exploit_maturity: Optional[str] = None


class VulnerabilityCreate(VulnerabilityBase):
    pass


class VulnerabilityResponse(VulnerabilityBase):
    id: str
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)


class VulnerabilityMatchBase(BaseModel):
    sbom_id: str
    dependency_id: str
    vulnerability_id: str
    dependency_name: str
    dependency_version: str
    cve_id: str
    severity: VulnerabilitySeverity
    cvss_score: Optional[float] = None
    match_type: str = "exact"
    match_confidence: float = 1.0
    affected_version_range: Optional[str] = None
    fixed_version: Optional[str] = None
    recommended_fix: Dict[str, Any] = Field(default_factory=dict)
    status: str = "active"
    notes: Optional[str] = None


class VulnerabilityMatchResponse(VulnerabilityMatchBase):
    id: str
    detected_at: datetime

    model_config = ConfigDict(from_attributes=True)


class ScanTaskBase(BaseModel):
    sbom_id: Optional[str] = None
    scan_type: str = "full"
    parameters: Dict[str, Any] = Field(default_factory=dict)
    triggered_by: Optional[str] = None


class ScanTaskCreate(ScanTaskBase):
    pass


class ScanTaskResponse(ScanTaskBase):
    id: str
    status: str
    progress: float
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    error_message: Optional[str] = None
    result_summary: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime
    updated_at: datetime

    model_config = ConfigDict(from_attributes=True)


class CVERemediationBase(BaseModel):
    cve_id: str
    dependency_id: str
    sbom_id: str
    package_name: str
    current_version: str
    recommended_version: Optional[str] = None
    recommended_action: str = "upgrade"
    upgrade_type: str = "patch"
    breaking_changes: List[Dict[str, Any]] = Field(default_factory=list)
    alternatives: List[Dict[str, Any]] = Field(default_factory=list)
    effort_estimate: str = "low"
    risk_assessment: str = "medium"
    additional_notes: Optional[str] = None
    status: str = "recommended"


class CVERemediationResponse(CVERemediationBase):
    id: str
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)


class SBOMUploadResponse(BaseModel):
    sbom_id: str
    format: SBOMFormat
    dependencies_found: int
    scan_task_id: Optional[str] = None
    message: str


class ScanResult(BaseModel):
    scan_id: str
    sbom_id: str
    total_dependencies: int
    vulnerable_dependencies: int
    total_vulnerabilities: int
    by_severity: Dict[str, int]
    remediations_available: int
    duration_ms: int
    timestamp: datetime


class UploadSBOMRequest(BaseModel):
    content: str
    format: SBOMFormat = SBOMFormat.CYCLONEDX
    name: Optional[str] = None
    project_name: Optional[str] = None
    project_version: Optional[str] = None
    namespace: str = "default"
    auto_scan: bool = True


class CVESearchRequest(BaseModel):
    keyword: Optional[str] = None
    severity: Optional[VulnerabilitySeverity] = None
    cvss_min: Optional[float] = None
    cvss_max: Optional[float] = None
    package_name: Optional[str] = None
    has_fix: Optional[bool] = None


class GenerateSBOMRequest(BaseModel):
    project_path: str
    package_manager: str
    project_name: Optional[str] = None
    format: SBOMFormat = SBOMFormat.CYCLONEDX
    include_dev: bool = False


class GenerateSBOMResponse(BaseModel):
    sbom_id: str
    format: SBOMFormat
    dependencies_count: int
    content: str


class RemediationSummary(BaseModel):
    sbom_id: str
    total_vulnerabilities: int
    remediable: int
    critical_to_fix: int
    high_to_fix: int
    estimated_effort: str
    priority_actions: List[Dict[str, Any]]


class DependencyTreeResponse(BaseModel):
    dependency_id: str
    name: str
    version: str
    children: List["DependencyTreeResponse"] = Field(default_factory=list)
    vulnerabilities: List[Dict[str, Any]] = Field(default_factory=list)


class ImportCVERequest(BaseModel):
    source: str = "nvd"
    cve_ids: Optional[List[str]] = None
    start_date: Optional[datetime] = None
    end_date: Optional[datetime] = None
    max_results: int = 1000


class ImportCVEResponse(BaseModel):
    imported: int
    updated: int
    skipped: int
    errors: List[str] = Field(default_factory=list)
    duration_ms: int


DependencyTreeResponse.model_rebuild()
