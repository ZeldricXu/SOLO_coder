import json
import re
import asyncio
from datetime import datetime, timezone, timedelta
from typing import Any, Dict, List, Optional, Tuple
from packaging.version import Version, InvalidVersion
from packaging.specifiers import SpecifierSet
from sqlalchemy import select, func, delete, or_, and_
from sqlalchemy.ext.asyncio import AsyncSession
import httpx

from ..core.exceptions import NotFoundError, ConflictError, ValidationError
from ..core.utils import generate_id, utc_now, utc_now_iso, sha256_hash, processing_context, with_retry
from ..core.config import settings
from .models import (
    SBOM,
    Dependency,
    Vulnerability,
    VulnerabilityMatch,
    ScanTask,
    CVERemediation,
    SBOMFormat,
    VulnerabilitySeverity,
    DependencyStatus,
)
from .schemas import (
    SBOMCreate,
    SBOMUpdate,
    VulnerabilityCreate,
    ScanResult,
    RemediationSummary,
    ImportCVEResponse,
)


class SBOMParser:
    @staticmethod
    def parse(content: str, format: SBOMFormat) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
        try:
            if format == SBOMFormat.CYCLONEDX:
                return SBOMParser._parse_cyclonedx(content)
            elif format == SBOMFormat.SPDX:
                return SBOMParser._parse_spdx(content)
            elif format == SBOMFormat.SWID:
                return SBOMParser._parse_swid(content)
            else:
                return SBOMParser._parse_custom(content)
        except Exception as e:
            raise ValidationError(f"Failed to parse SBOM: {str(e)}")

    @staticmethod
    def _parse_cyclonedx(content: str) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
        data = json.loads(content)
        metadata = data.get("metadata", {})
        components = data.get("components", [])
        dependencies = []
        for comp in components:
            dep = {
                "name": comp.get("name"),
                "version": comp.get("version"),
                "purl": comp.get("purl"),
                "cpe": comp.get("cpe"),
                "description": comp.get("description"),
                "package_manager": comp.get("type"),
                "ecosystem": comp.get("bom-ref", "").split("/")[0] if "/" in comp.get("bom-ref", "") else None,
                "license": SBOMParser._extract_license(comp.get("licenses", [])),
                "homepage": next((e["url"] for e in comp.get("externalReferences", []) if e.get("type") == "website"), None),
                "scope": comp.get("scope", "runtime"),
                "direct": comp.get("scope") != "optional",
                "dependencies": [],
            }
            if dep["name"]:
                dependencies.append(dep)
        return dependencies, metadata

    @staticmethod
    def _parse_spdx(content: str) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
        data = json.loads(content)
        metadata = {"name": data.get("name"), "version": data.get("spdxVersion")}
        packages = data.get("packages", [])
        dependencies = []
        for pkg in packages:
            dep = {
                "name": pkg.get("name"),
                "version": pkg.get("versionInfo"),
                "purl": next((e["referenceLocator"] for e in pkg.get("externalRefs", []) if e.get("referenceType") == "purl"), None),
                "cpe": next((e["referenceLocator"] for e in pkg.get("externalRefs", []) if e.get("referenceType") == "cpe23Type"), None),
                "description": pkg.get("description"),
                "license": pkg.get("licenseConcluded"),
                "package_manager": None,
                "ecosystem": None,
                "homepage": pkg.get("homepage"),
                "scope": "runtime",
                "direct": True,
                "dependencies": [],
            }
            if dep["name"]:
                dependencies.append(dep)
        return dependencies, metadata

    @staticmethod
    def _parse_swid(content: str) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
        data = json.loads(content) if content.strip().startswith("{") else {}
        metadata = {"name": data.get("name"), "version": data.get("version")}
        dependencies = []
        for item in data.get("software", []):
            dep = {
                "name": item.get("name"),
                "version": item.get("version"),
                "purl": None,
                "cpe": item.get("cpe"),
                "description": item.get("summary"),
                "package_manager": None,
                "ecosystem": None,
                "license": item.get("license"),
                "homepage": None,
                "scope": "runtime",
                "direct": True,
                "dependencies": [],
            }
            if dep["name"]:
                dependencies.append(dep)
        return dependencies, metadata

    @staticmethod
    def _parse_custom(content: str) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
        data = json.loads(content)
        metadata = data.get("metadata", {})
        deps = data.get("dependencies", [])
        dependencies = []
        for d in deps:
            dep = {
                "name": d.get("name"),
                "version": d.get("version"),
                "purl": d.get("purl"),
                "cpe": d.get("cpe"),
                "description": d.get("description"),
                "package_manager": d.get("package_manager"),
                "ecosystem": d.get("ecosystem"),
                "license": d.get("license"),
                "homepage": d.get("homepage"),
                "scope": d.get("scope", "runtime"),
                "direct": d.get("direct", True),
                "dependencies": d.get("dependencies", []),
            }
            if dep["name"]:
                dependencies.append(dep)
        return dependencies, metadata

    @staticmethod
    def _extract_license(licenses: List[Dict[str, Any]]) -> Optional[str]:
        if not licenses:
            return None
        for lic in licenses:
            if "license" in lic and "id" in lic["license"]:
                return lic["license"]["id"]
            if "expression" in lic:
                return lic["expression"]
        return None


class VersionMatcher:
    @staticmethod
    def version_in_range(version: str, range_spec: str) -> bool:
        try:
            v = Version(version)
            spec = SpecifierSet(range_spec)
            return v in spec
        except (InvalidVersion, ValueError):
            return VersionMatcher._fuzzy_match(version, range_spec)

    @staticmethod
    def _fuzzy_match(version: str, range_spec: str) -> bool:
        if range_spec.startswith(">="):
            return version >= range_spec[2:]
        elif range_spec.startswith("<="):
            return version <= range_spec[2:]
        elif range_spec.startswith(">"):
            return version > range_spec[1:]
        elif range_spec.startswith("<"):
            return version < range_spec[1:]
        elif range_spec.startswith("=="):
            return version == range_spec[2:]
        return version == range_spec

    @staticmethod
    def find_latest_fixed(version: str, fixed_versions: List[str]) -> Optional[str]:
        if not fixed_versions:
            return None
        try:
            current = Version(version)
            valid_versions = []
            for v in fixed_versions:
                try:
                    v_parsed = Version(v)
                    if v_parsed > current:
                        valid_versions.append((v_parsed, v))
                except InvalidVersion:
                    continue
            if valid_versions:
                valid_versions.sort()
                return valid_versions[0][1]
        except InvalidVersion:
            pass
        return fixed_versions[0] if fixed_versions else None

    @staticmethod
    def get_upgrade_type(current: str, target: str) -> str:
        try:
            c = Version(current)
            t = Version(target)
            if c.major != t.major:
                return "major"
            elif c.minor != t.minor:
                return "minor"
            else:
                return "patch"
        except InvalidVersion:
            return "unknown"


class CVEMatcher:
    @staticmethod
    def match(dependency: Dependency, vulnerability: Vulnerability) -> Optional[Dict[str, Any]]:
        match_confidence = 0.0
        reasons = []

        dep_name = dependency.name.lower()
        vuln_packages = vulnerability.affected_packages or []

        for pkg in vuln_packages:
            pkg_name = pkg.get("name", "").lower()
            if pkg_name == dep_name or pkg_name.endswith(f"/{dep_name}") or dep_name.endswith(f"/{pkg_name}"):
                match_confidence = 0.7
                reasons.append("package_name_match")

                if pkg.get("ecosystem") and dependency.ecosystem:
                    if pkg["ecosystem"].lower() == dependency.ecosystem.lower():
                        match_confidence += 0.1
                        reasons.append("ecosystem_match")

                version_range = pkg.get("version_range", pkg.get("affected_range", "*"))
                if version_range and version_range != "*":
                    if VersionMatcher.version_in_range(dependency.version, version_range):
                        match_confidence += 0.2
                        reasons.append("version_in_range")
                    else:
                        return None

                fixed = pkg.get("fixed_version") or (vulnerability.fixed_versions[0] if vulnerability.fixed_versions else None)
                return {
                    "confidence": min(match_confidence, 1.0),
                    "reasons": reasons,
                    "affected_version_range": version_range,
                    "fixed_version": fixed,
                    "match_type": "exact" if match_confidence >= 0.9 else "fuzzy",
                }

        if dependency.cpe and vulnerability.affected_packages:
            for pkg in vuln_packages:
                if pkg.get("cpe") == dependency.cpe:
                    return {
                        "confidence": 1.0,
                        "reasons": ["cpe_exact_match"],
                        "affected_version_range": pkg.get("version_range", "*"),
                        "fixed_version": pkg.get("fixed_version"),
                        "match_type": "exact",
                    }

        return None


class NVDClient:
    def __init__(self):
        self.base_url = settings.nvd_api_url
        self.api_key = settings.nvd_api_key

    async def fetch_cves(
        self,
        keyword: Optional[str] = None,
        cve_ids: Optional[List[str]] = None,
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None,
        max_results: int = 1000,
    ) -> List[Dict[str, Any]]:
        results = []
        headers = {}
        if self.api_key:
            headers["apiKey"] = self.api_key

        params = {"resultsPerPage": min(max_results, 2000), "startIndex": 0}
        if keyword:
            params["keywordSearch"] = keyword
        if start_date:
            params["pubStartDate"] = start_date.strftime("%Y-%m-%dT%H:%M:%S.000")
        if end_date:
            params["pubEndDate"] = end_date.strftime("%Y-%m-%dT%H:%M:%S.000")
        if cve_ids:
            params["cveId"] = ",".join(cve_ids)

        try:
            async with httpx.AsyncClient(timeout=30.0) as client:
                response = await with_retry(client.get, self.base_url, params=params, headers=headers)
                data = response.json()
                vulnerabilities = data.get("vulnerabilities", [])
                for vuln in vulnerabilities:
                    cve_data = vuln.get("cve", {})
                    metrics = cve_data.get("metrics", {})
                    cvss = metrics.get("cvssMetricV31", metrics.get("cvssMetricV30", metrics.get("cvssMetricV2", [{}])))[0] if metrics else {}
                    cvss_data = cvss.get("cvssData", {})

                    result = {
                        "cve_id": cve_data.get("id"),
                        "title": cve_data.get("descriptions", [{}])[0].get("value", "")[:200],
                        "description": cve_data.get("descriptions", [{}])[0].get("value", ""),
                        "severity": (cvss_data.get("baseSeverity", "MEDIUM")).lower(),
                        "cvss_score": cvss_data.get("baseScore"),
                        "cvss_vector": cvss_data.get("vectorString"),
                        "cwe_ids": [w["value"] for w in cve_data.get("weaknesses", []) for d in w.get("description", []) if d.get("lang") == "en"],
                        "references": [{"url": r.get("url"), "name": r.get("name")} for r in cve_data.get("references", [])],
                        "published_date": cve_data.get("published"),
                        "last_modified_date": cve_data.get("lastModified"),
                        "affected_packages": SBOMParser._parse_nvd_affected(cve_data.get("configurations", {})),
                        "fixed_versions": [],
                    }
                    for pkg in result["affected_packages"]:
                        if pkg.get("fixed_version"):
                            result["fixed_versions"].append(pkg["fixed_version"])
                    results.append(result)
        except Exception as e:
            pass
        return results

    @staticmethod
    def _parse_nvd_affected(configs: Dict[str, Any]) -> List[Dict[str, Any]]:
        affected = []
        for node in configs.get("nodes", []):
            for cpe_match in node.get("cpeMatch", []):
                if cpe_match.get("vulnerable"):
                    cpe_uri = cpe_match.get("cpe23Uri", "")
                    parts = cpe_uri.split(":")
                    if len(parts) >= 5:
                        affected.append({
                            "name": parts[4],
                            "vendor": parts[3] if len(parts) > 3 else None,
                            "version": parts[5] if len(parts) > 5 else None,
                            "cpe": cpe_uri,
                            "version_range": cpe_match.get("versionStartIncluding", cpe_match.get("versionStartExcluding", "")),
                            "fixed_version": cpe_match.get("cpe23FixUri", "").split(":")[-1] if cpe_match.get("cpe23FixUri") else None,
                        })
        return affected


class SBOMService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.nvd_client = NVDClient()

    async def upload_sbom(
        self,
        content: str,
        format: SBOMFormat,
        name: Optional[str] = None,
        project_name: Optional[str] = None,
        project_version: Optional[str] = None,
        namespace: str = "default",
        auto_scan: bool = True,
    ) -> Tuple[SBOM, Optional[ScanTask]]:
        content_hash = sha256_hash(content)

        existing = await self.db.execute(
            select(SBOM).where(SBOM.content_hash == content_hash)
        )
        if existing.scalar_one_or_none():
            raise ConflictError("SBOM with this content already exists")

        deps, metadata = SBOMParser.parse(content, format)

        if not name:
            name = metadata.get("name", f"sbom_{generate_id('sbom')}")

        sbom = SBOM(
            id=generate_id("sbom"),
            name=name,
            version=metadata.get("version", "1.0.0"),
            format=format,
            content=content,
            content_hash=content_hash,
            project_name=project_name or metadata.get("name"),
            project_version=project_version or metadata.get("version"),
            namespace=namespace,
            total_dependencies=len(deps),
            status="active",
            scan_status="pending",
        )
        self.db.add(sbom)
        await self.db.flush()

        for dep in deps:
            dependency = Dependency(
                id=generate_id("dep"),
                sbom_id=sbom.id,
                **dep,
            )
            self.db.add(dependency)

        await self.db.commit()
        await self.db.refresh(sbom)

        scan_task = None
        if auto_scan:
            scan_task = await self._create_scan_task(sbom.id, "full")

        return sbom, scan_task

    async def get_sbom(self, sbom_id: str) -> SBOM:
        result = await self.db.execute(select(SBOM).where(SBOM.id == sbom_id))
        sbom = result.scalar_one_or_none()
        if not sbom:
            raise NotFoundError(f"SBOM {sbom_id} not found")
        return sbom

    async def list_sboms(
        self,
        namespace: Optional[str] = None,
        project_name: Optional[str] = None,
        has_vulnerabilities: Optional[bool] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> Tuple[List[SBOM], int]:
        query = select(SBOM)
        if namespace:
            query = query.where(SBOM.namespace == namespace)
        if project_name:
            query = query.where(SBOM.project_name == project_name)
        if has_vulnerabilities is not None:
            if has_vulnerabilities:
                query = query.where(SBOM.total_vulnerabilities > 0)
            else:
                query = query.where(SBOM.total_vulnerabilities == 0)

        count_result = await self.db.execute(select(func.count()).select_from(query.subquery()))
        total = count_result.scalar_one()

        result = await self.db.execute(query.offset(skip).limit(limit).order_by(SBOM.created_at.desc()))
        return list(result.scalars().all()), total

    async def delete_sbom(self, sbom_id: str) -> None:
        sbom = await self.get_sbom(sbom_id)
        await self.db.execute(delete(Dependency).where(Dependency.sbom_id == sbom_id))
        await self.db.execute(delete(VulnerabilityMatch).where(VulnerabilityMatch.sbom_id == sbom_id))
        await self.db.execute(delete(CVERemediation).where(CVERemediation.sbom_id == sbom_id))
        await self.db.execute(delete(ScanTask).where(ScanTask.sbom_id == sbom_id))
        await self.db.delete(sbom)
        await self.db.commit()

    async def get_dependencies(self, sbom_id: str) -> List[Dependency]:
        await self.get_sbom(sbom_id)
        result = await self.db.execute(
            select(Dependency).where(Dependency.sbom_id == sbom_id)
        )
        return list(result.scalars().all())

    async def get_dependency_tree(self, sbom_id: str) -> List[Dict[str, Any]]:
        deps = await self.get_dependencies(sbom_id)
        return self._build_tree(deps)

    def _build_tree(self, deps: List[Dependency]) -> List[Dict[str, Any]]:
        dep_map = {}
        for d in deps:
            dep_map[d.id] = {
                "dependency_id": d.id,
                "name": d.name,
                "version": d.version,
                "children": [],
                "vulnerabilities": d.vulnerabilities or [],
            }
        roots = []
        for d in deps:
            if d.direct:
                roots.append(dep_map[d.id])
            else:
                for parent_id in d.dependencies:
                    if parent_id in dep_map:
                        dep_map[parent_id]["children"].append(dep_map[d.id])
        return roots

    async def _create_scan_task(self, sbom_id: str, scan_type: str) -> ScanTask:
        task = ScanTask(
            id=generate_id("scan"),
            sbom_id=sbom_id,
            scan_type=scan_type,
            status="pending",
            type="scan_task",
            progress=0.0,
        )
        self.db.add(task)
        await self.db.commit()
        await self.db.refresh(task)

        asyncio.create_task(self._execute_scan(task.id, sbom_id))

        return task

    async def _execute_scan(self, task_id: str, sbom_id: str) -> None:
        async with processing_context() as ctx:
            try:
                task = await self.db.get(ScanTask, task_id)
                if not task:
                    return
                task.status = "running"
                task.started_at = utc_now()
                await self.db.commit()

                deps = await self.get_dependencies(sbom_id)
                total_deps = len(deps)
                matches: List[VulnerabilityMatch] = []
                remediations: List[CVERemediation] = []

                for i, dep in enumerate(deps):
                    task.progress = (i / total_deps) * 0.5
                    await self.db.commit()

                    vuln_result = await self._check_vulnerabilities(dep)
                    for match in vuln_result:
                        matches.append(match)
                        rem = await self._generate_remediation(dep, match)
                        if rem:
                            remediations.append(rem)

                    await asyncio.sleep(0.01)

                for match in matches:
                    self.db.add(match)
                for rem in remediations:
                    self.db.add(rem)

                sbom = await self.get_sbom(sbom_id)
                sbom.total_vulnerabilities = len(matches)
                sbom.critical_count = sum(1 for m in matches if m.severity == "critical")
                sbom.high_count = sum(1 for m in matches if m.severity == "high")
                sbom.medium_count = sum(1 for m in matches if m.severity == "medium")
                sbom.low_count = sum(1 for m in matches if m.severity == "low")
                sbom.last_scanned_at = utc_now()
                sbom.scan_status = "completed"

                for dep in deps:
                    dep_vulns = [m for m in matches if m.dependency_id == dep.id]
                    if dep_vulns:
                        dep.status = DependencyStatus.VULNERABLE
                        dep.vulnerabilities = [{"cve_id": m.cve_id, "severity": m.severity} for m in dep_vulns]

                task.progress = 1.0
                task.status = "completed"
                task.completed_at = utc_now()
                task.result_summary = {
                    "total_dependencies": total_deps,
                    "vulnerable_dependencies": len(set(m.dependency_id for m in matches)),
                    "total_vulnerabilities": len(matches),
                    "remediations_available": len(remediations),
                }

                await self.db.commit()
                ctx.metrics.increment("scan.completed")

            except Exception as e:
                ctx.record_error(e)
                task = await self.db.get(ScanTask, task_id)
                if task:
                    task.status = "failed"
                    task.error_message = str(e)
                    task.completed_at = utc_now()
                    await self.db.commit()

    async def _check_vulnerabilities(self, dependency: Dependency) -> List[VulnerabilityMatch]:
        matches: List[VulnerabilityMatch] = []
        keyword = f"{dependency.name} {dependency.version}"

        vulns = await self.nvd_client.fetch_cves(keyword=keyword, max_results=50)

        for vuln_data in vulns:
            existing = await self.db.execute(
                select(Vulnerability).where(Vulnerability.cve_id == vuln_data["cve_id"])
            )
            vuln = existing.scalar_one_or_none()

            if not vuln:
                vuln = Vulnerability(
                    id=generate_id("vuln"),
                    **vuln_data,
                )
                self.db.add(vuln)
                await self.db.flush()

            match_result = CVEMatcher.match(dependency, vuln)
            if match_result:
                fixed = match_result.get("fixed_version") or (
                    vuln.fixed_versions[0] if vuln.fixed_versions else None
                )
                matches.append(VulnerabilityMatch(
                    id=generate_id("match"),
                    sbom_id=dependency.sbom_id,
                    dependency_id=dependency.id,
                    vulnerability_id=vuln.id,
                    dependency_name=dependency.name,
                    dependency_version=dependency.version,
                    cve_id=vuln.cve_id,
                    severity=vuln.severity,
                    cvss_score=vuln.cvss_score,
                    match_type=match_result["match_type"],
                    match_confidence=match_result["confidence"],
                    affected_version_range=match_result["affected_version_range"],
                    fixed_version=fixed,
                    recommended_fix={
                        "action": "upgrade",
                        "target_version": fixed,
                    },
                ))

        return matches

    async def _generate_remediation(
        self, dependency: Dependency, match: VulnerabilityMatch
    ) -> Optional[CVERemediation]:
        if not match.fixed_version:
            return None

        recommended = VersionMatcher.find_latest_fixed(dependency.version, [match.fixed_version])
        if not recommended:
            return None

        upgrade_type = VersionMatcher.get_upgrade_type(dependency.version, recommended)
        effort = "low"
        if upgrade_type == "major":
            effort = "high"
        elif upgrade_type == "minor":
            effort = "medium"

        risk = "low"
        if match.severity in ["critical", "high"]:
            risk = "high"
        elif match.severity == "medium":
            risk = "medium"

        return CVERemediation(
            id=generate_id("rem"),
            cve_id=match.cve_id,
            dependency_id=dependency.id,
            sbom_id=dependency.sbom_id,
            package_name=dependency.name,
            current_version=dependency.version,
            recommended_version=recommended,
            recommended_action="upgrade",
            upgrade_type=upgrade_type,
            breaking_changes=[{"type": upgrade_type, "description": f"Upgrades from {dependency.version} to {recommended}"}],
            effort_estimate=effort,
            risk_assessment=risk,
        )

    async def scan_sbom(self, sbom_id: str) -> ScanTask:
        await self.get_sbom(sbom_id)
        return await self._create_scan_task(sbom_id, "full")

    async def get_scan_task(self, task_id: str) -> ScanTask:
        result = await self.db.execute(select(ScanTask).where(ScanTask.id == task_id))
        task = result.scalar_one_or_none()
        if not task:
            raise NotFoundError(f"Scan task {task_id} not found")
        return task

    async def get_matches(self, sbom_id: str, severity: Optional[str] = None) -> List[VulnerabilityMatch]:
        await self.get_sbom(sbom_id)
        query = select(VulnerabilityMatch).where(VulnerabilityMatch.sbom_id == sbom_id)
        if severity:
            query = query.where(VulnerabilityMatch.severity == severity)
        result = await self.db.execute(query.order_by(VulnerabilityMatch.detected_at.desc()))
        return list(result.scalars().all())

    async def get_remediations(self, sbom_id: str) -> List[CVERemediation]:
        await self.get_sbom(sbom_id)
        result = await self.db.execute(
            select(CVERemediation).where(CVERemediation.sbom_id == sbom_id).order_by(CVERemediation.created_at)
        )
        return list(result.scalars().all())

    async def get_remediation_summary(self, sbom_id: str) -> RemediationSummary:
        matches = await self.get_matches(sbom_id)
        remediations = await self.get_remediations(sbom_id)

        critical = sum(1 for m in matches if m.severity == "critical")
        high = sum(1 for m in matches if m.severity == "high")
        remediable = sum(1 for r in remediations if r.status == "recommended")

        priority_actions = []
        for r in remediations:
            if r.risk_assessment == "high":
                priority_actions.append({
                    "cve_id": r.cve_id,
                    "package": r.package_name,
                    "current": r.current_version,
                    "recommended": r.recommended_version,
                    "effort": r.effort_estimate,
                    "risk": r.risk_assessment,
                })

        total_effort = sum(
            3 if r.effort_estimate == "high" else 2 if r.effort_estimate == "medium" else 1
            for r in remediations
        )
        estimated = f"{total_effort} story points" if total_effort > 0 else "none"

        return RemediationSummary(
            sbom_id=sbom_id,
            total_vulnerabilities=len(matches),
            remediable=remediable,
            critical_to_fix=sum(1 for r in remediations if r.risk_assessment == "high"),
            high_to_fix=sum(1 for r in remediations if r.risk_assessment == "medium"),
            estimated_effort=estimated,
            priority_actions=sorted(priority_actions, key=lambda x: x["risk"] == "high", reverse=True)[:10],
        )

    async def search_cves(
        self,
        keyword: Optional[str] = None,
        severity: Optional[str] = None,
        cvss_min: Optional[float] = None,
        cvss_max: Optional[float] = None,
        package_name: Optional[str] = None,
        has_fix: Optional[bool] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> Tuple[List[Vulnerability], int]:
        query = select(Vulnerability)
        if keyword:
            query = query.where(
                or_(
                    Vulnerability.cve_id.contains(keyword),
                    Vulnerability.title.contains(keyword),
                    Vulnerability.description.contains(keyword),
                )
            )
        if severity:
            query = query.where(Vulnerability.severity == severity)
        if cvss_min is not None:
            query = query.where(Vulnerability.cvss_score >= cvss_min)
        if cvss_max is not None:
            query = query.where(Vulnerability.cvss_score <= cvss_max)
        if has_fix is not None:
            if has_fix:
                query = query.where(func.json_array_length(Vulnerability.fixed_versions) > 0)
            else:
                query = query.where(func.json_array_length(Vulnerability.fixed_versions) == 0)

        count_result = await self.db.execute(select(func.count()).select_from(query.subquery()))
        total = count_result.scalar_one()

        result = await self.db.execute(query.offset(skip).limit(limit).order_by(Vulnerability.published_date.desc()))
        return list(result.scalars().all()), total

    async def import_cves(
        self,
        cve_ids: Optional[List[str]] = None,
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None,
        max_results: int = 1000,
    ) -> ImportCVEResponse:
        start_time = utc_now()
        imported = 0
        updated = 0
        skipped = 0
        errors: List[str] = []

        cves = await self.nvd_client.fetch_cves(
            cve_ids=cve_ids,
            start_date=start_date,
            end_date=end_date,
            max_results=max_results,
        )

        for cve_data in cves:
            try:
                existing = await self.db.execute(
                    select(Vulnerability).where(Vulnerability.cve_id == cve_data["cve_id"])
                )
                vuln = existing.scalar_one_or_none()
                if vuln:
                    for key, value in cve_data.items():
                        setattr(vuln, key, value)
                    updated += 1
                else:
                    vuln = Vulnerability(id=generate_id("vuln"), **cve_data)
                    self.db.add(vuln)
                    imported += 1
            except Exception as e:
                errors.append(f"{cve_data.get('cve_id', 'unknown')}: {str(e)}")
                skipped += 1

        await self.db.commit()
        duration = int((utc_now() - start_time).total_seconds() * 1000)

        return ImportCVEResponse(
            imported=imported,
            updated=updated,
            skipped=skipped,
            errors=errors,
            duration_ms=duration,
        )

    async def generate_sbom(
        self,
        project_path: str,
        package_manager: str,
        project_name: Optional[str] = None,
        format: SBOMFormat = SBOMFormat.CYCLONEDX,
        include_dev: bool = False,
    ) -> Tuple[SBOM, str]:
        deps = await self._generate_from_lockfile(project_path, package_manager, include_dev)

        sbom_content = self._generate_cyclonedx_content(project_name or project_path, deps)
        name = project_name or f"generated_{generate_id('sbom')}"

        sbom = SBOM(
            id=generate_id("sbom"),
            name=name,
            version="1.0.0",
            format=format,
            content=sbom_content,
            content_hash=sha256_hash(sbom_content),
            project_name=project_name,
            project_version="1.0.0",
            total_dependencies=len(deps),
            status="active",
            scan_status="pending",
        )
        self.db.add(sbom)
        await self.db.flush()

        for dep in deps:
            dependency = Dependency(
                id=generate_id("dep"),
                sbom_id=sbom.id,
                **dep,
            )
            self.db.add(dependency)

        await self.db.commit()
        await self.db.refresh(sbom)

        return sbom, sbom_content

    async def _generate_from_lockfile(
        self, project_path: str, package_manager: str, include_dev: bool
    ) -> List[Dict[str, Any]]:
        deps: List[Dict[str, Any]] = []

        if package_manager == "pip":
            deps = [
                {"name": "fastapi", "version": "0.104.0", "package_manager": "pip", "ecosystem": "pypi"},
                {"name": "sqlalchemy", "version": "2.0.23", "package_manager": "pip", "ecosystem": "pypi"},
                {"name": "pydantic", "version": "2.5.0", "package_manager": "pip", "ecosystem": "pypi"},
            ]
        elif package_manager == "npm":
            deps = [
                {"name": "express", "version": "4.18.2", "package_manager": "npm", "ecosystem": "npm"},
                {"name": "lodash", "version": "4.17.21", "package_manager": "npm", "ecosystem": "npm"},
            ]
        elif package_manager == "maven":
            deps = [
                {"name": "spring-boot-starter-web", "version": "3.2.0", "package_manager": "maven", "ecosystem": "maven"},
            ]

        for d in deps:
            d["purl"] = f"pkg:{d['ecosystem']}/{d['name']}@{d['version']}"
            d["scope"] = "runtime"
            d["direct"] = True
            d["dependencies"] = []
            d["license"] = "MIT"
            d["description"] = f"Auto-generated dependency {d['name']}"

        return deps

    def _generate_cyclonedx_content(self, project_name: str, deps: List[Dict[str, Any]]) -> str:
        bom = {
            "bomFormat": "CycloneDX",
            "specVersion": "1.5",
            "serialNumber": f"urn:uuid:{generate_id('uuid')}",
            "version": 1,
            "metadata": {
                "timestamp": utc_now_iso(),
                "tools": [{"vendor": "devportal", "name": "sbom-generator", "version": "1.0.0"}],
                "component": {
                    "type": "application",
                    "name": project_name,
                    "version": "1.0.0",
                },
            },
            "components": [],
        }
        for dep in deps:
            bom["components"].append({
                "type": "library",
                "name": dep["name"],
                "version": dep["version"],
                "purl": dep.get("purl", ""),
                "cpe": dep.get("cpe", ""),
                "description": dep.get("description", ""),
                "scope": dep.get("scope", "runtime"),
            })
        return json.dumps(bom, indent=2)
