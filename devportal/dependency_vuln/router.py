from typing import Any, Dict, List, Optional
from fastapi import APIRouter, Depends, Query, UploadFile, File
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.database import get_db
from ..core.schemas import APIResponse, PaginatedResponse
from ..core.dependencies import get_current_user, PermissionChecker
from ..core.models import User
from .models import SBOMFormat, VulnerabilitySeverity
from .schemas import (
    SBOMCreate,
    SBOMUpdate,
    SBOMResponse,
    DependencyResponse,
    VulnerabilityResponse,
    VulnerabilityMatchResponse,
    ScanTaskResponse,
    CVERemediationResponse,
    UploadSBOMRequest,
    SBOMUploadResponse,
    ScanResult,
    CVESearchRequest,
    GenerateSBOMRequest,
    GenerateSBOMResponse,
    RemediationSummary,
    DependencyTreeResponse,
    ImportCVERequest,
    ImportCVEResponse,
)
from .services import SBOMService

router = APIRouter(prefix="/dependency-vuln", tags=["Dependency Vulnerability Analysis"])


@router.post("/sboms/upload", response_model=APIResponse[SBOMUploadResponse], status_code=201)
async def upload_sbom(
    request: UploadSBOMRequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["dependency_vuln:create"])),
):
    service = SBOMService(db)
    sbom, scan_task = await service.upload_sbom(
        request.content,
        request.format,
        request.name,
        request.project_name,
        request.project_version,
        request.namespace,
        request.auto_scan,
    )
    return APIResponse(
        code=201,
        data=SBOMUploadResponse(
            sbom_id=sbom.id,
            format=sbom.format,
            dependencies_found=sbom.total_dependencies,
            scan_task_id=scan_task.id if scan_task else None,
            message="SBOM uploaded successfully" if not scan_task else "SBOM uploaded and scan started",
        ),
    )


@router.post("/sboms/upload-file", response_model=APIResponse[SBOMUploadResponse], status_code=201)
async def upload_sbom_file(
    file: UploadFile = File(...),
    format: SBOMFormat = SBOMFormat.CYCLONEDX,
    name: Optional[str] = None,
    project_name: Optional[str] = None,
    project_version: Optional[str] = None,
    namespace: str = "default",
    auto_scan: bool = True,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["dependency_vuln:create"])),
):
    content = (await file.read()).decode("utf-8")
    service = SBOMService(db)
    sbom, scan_task = await service.upload_sbom(
        content, format, name, project_name, project_version, namespace, auto_scan
    )
    return APIResponse(
        code=201,
        data=SBOMUploadResponse(
            sbom_id=sbom.id,
            format=sbom.format,
            dependencies_found=sbom.total_dependencies,
            scan_task_id=scan_task.id if scan_task else None,
            message="SBOM uploaded successfully" if not scan_task else "SBOM uploaded and scan started",
        ),
    )


@router.post("/sboms/generate", response_model=APIResponse[GenerateSBOMResponse], status_code=201)
async def generate_sbom(
    request: GenerateSBOMRequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["dependency_vuln:create"])),
):
    service = SBOMService(db)
    sbom, content = await service.generate_sbom(
        request.project_path,
        request.package_manager,
        request.project_name,
        request.format,
        request.include_dev,
    )
    return APIResponse(
        code=201,
        data=GenerateSBOMResponse(
            sbom_id=sbom.id,
            format=sbom.format,
            dependencies_count=sbom.total_dependencies,
            content=content,
        ),
    )


@router.get("/sboms", response_model=PaginatedResponse[SBOMResponse])
async def list_sboms(
    namespace: Optional[str] = None,
    project_name: Optional[str] = None,
    has_vulnerabilities: Optional[bool] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["dependency_vuln:read"])),
):
    service = SBOMService(db)
    skip = (page - 1) * page_size
    sboms, total = await service.list_sboms(namespace, project_name, has_vulnerabilities, skip, page_size)
    return PaginatedResponse(
        code=200, data=sboms, total=total, page=page, page_size=page_size
    )


@router.get("/sboms/{sbom_id}", response_model=APIResponse[SBOMResponse])
async def get_sbom(
    sbom_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["dependency_vuln:read"])),
):
    service = SBOMService(db)
    sbom = await service.get_sbom(sbom_id)
    return APIResponse(code=200, data=sbom)


@router.delete("/sboms/{sbom_id}", response_model=APIResponse[Dict[str, Any]])
async def delete_sbom(
    sbom_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["dependency_vuln:delete"])),
):
    service = SBOMService(db)
    await service.delete_sbom(sbom_id)
    return APIResponse(code=200, data={"id": sbom_id, "deleted": True})


@router.get("/sboms/{sbom_id}/dependencies", response_model=APIResponse[List[DependencyResponse]])
async def get_sbom_dependencies(
    sbom_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["dependency_vuln:read"])),
):
    service = SBOMService(db)
    deps = await service.get_dependencies(sbom_id)
    return APIResponse(code=200, data=deps)


@router.get("/sboms/{sbom_id}/dependency-tree", response_model=APIResponse[List[DependencyTreeResponse]])
async def get_sbom_dependency_tree(
    sbom_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["dependency_vuln:read"])),
):
    service = SBOMService(db)
    tree = await service.get_dependency_tree(sbom_id)
    return APIResponse(code=200, data=[DependencyTreeResponse(**t) for t in tree])


@router.post("/sboms/{sbom_id}/scan", response_model=APIResponse[ScanTaskResponse])
async def scan_sbom(
    sbom_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["dependency_vuln:scan"])),
):
    service = SBOMService(db)
    task = await service.scan_sbom(sbom_id)
    return APIResponse(code=200, data=task)


@router.get("/scan-tasks/{task_id}", response_model=APIResponse[ScanTaskResponse])
async def get_scan_task(
    task_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["dependency_vuln:read"])),
):
    service = SBOMService(db)
    task = await service.get_scan_task(task_id)
    return APIResponse(code=200, data=task)


@router.get("/sboms/{sbom_id}/vulnerabilities", response_model=APIResponse[List[VulnerabilityMatchResponse]])
async def get_sbom_vulnerabilities(
    sbom_id: str,
    severity: Optional[VulnerabilitySeverity] = None,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["dependency_vuln:read"])),
):
    service = SBOMService(db)
    matches = await service.get_matches(sbom_id, severity)
    return APIResponse(code=200, data=matches)


@router.get("/sboms/{sbom_id}/remediations", response_model=APIResponse[List[CVERemediationResponse]])
async def get_sbom_remediations(
    sbom_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["dependency_vuln:read"])),
):
    service = SBOMService(db)
    remediations = await service.get_remediations(sbom_id)
    return APIResponse(code=200, data=remediations)


@router.get("/sboms/{sbom_id}/remediation-summary", response_model=APIResponse[RemediationSummary])
async def get_remediation_summary(
    sbom_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["dependency_vuln:read"])),
):
    service = SBOMService(db)
    summary = await service.get_remediation_summary(sbom_id)
    return APIResponse(code=200, data=summary)


@router.get("/cves", response_model=PaginatedResponse[VulnerabilityResponse])
async def search_cves(
    keyword: Optional[str] = None,
    severity: Optional[VulnerabilitySeverity] = None,
    cvss_min: Optional[float] = None,
    cvss_max: Optional[float] = None,
    has_fix: Optional[bool] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["dependency_vuln:read"])),
):
    service = SBOMService(db)
    skip = (page - 1) * page_size
    vulns, total = await service.search_cves(
        keyword, severity, cvss_min, cvss_max, None, has_fix, skip, page_size
    )
    return PaginatedResponse(
        code=200, data=vulns, total=total, page=page, page_size=page_size
    )


@router.get("/cves/{cve_id}", response_model=APIResponse[VulnerabilityResponse])
async def get_cve(
    cve_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["dependency_vuln:read"])),
):
    from sqlalchemy import select
    from .models import Vulnerability
    service = SBOMService(db)
    result = await db.execute(select(Vulnerability).where(Vulnerability.cve_id == cve_id))
    vuln = result.scalar_one_or_none()
    if not vuln:
        from ..core.exceptions import NotFoundError
        raise NotFoundError(f"CVE {cve_id} not found")
    return APIResponse(code=200, data=vuln)


@router.post("/cves/import", response_model=APIResponse[ImportCVEResponse])
async def import_cves(
    request: ImportCVERequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["dependency_vuln:manage"])),
):
    service = SBOMService(db)
    result = await service.import_cves(
        request.cve_ids, request.start_date, request.end_date, request.max_results
    )
    return APIResponse(code=200, data=result)
