"""
Mesh quality checking and validation module.

Provides comprehensive mesh quality checks including:
- Negative volume detection
- Overlapping cell detection
- Face non-orthogonality checks
- Aspect ratio checks
- Skewness checks
"""

import numpy as np
from dataclasses import dataclass, field
from typing import List, Tuple, Optional
from enum import Enum


class MeshQualityIssue(Enum):
    """Types of mesh quality issues."""
    NEGATIVE_VOLUME = "negative_volume"
    OVERLAPPING_CELLS = "overlapping_cells"
    HIGH_NONORTHOGONALITY = "high_nonorthogonality"
    HIGH_ASPECT_RATIO = "high_aspect_ratio"
    HIGH_SKEWNESS = "high_skewness"
    INVALID_FACE = "invalid_face"
    ZERO_VOLUME = "zero_volume"


@dataclass
class QualityIssue:
    """Represents a specific mesh quality issue."""
    issue_type: MeshQualityIssue
    cell_ids: List[int] = field(default_factory=list)
    message: str = ""
    values: np.ndarray = field(default_factory=lambda: np.array([]))
    
    def __bool__(self):
        return len(self.cell_ids) > 0
    
    def __len__(self):
        return len(self.cell_ids)


@dataclass
class MeshQualityReport:
    """Complete mesh quality report."""
    n_cells: int = 0
    n_faces: int = 0
    n_boundary_faces: int = 0
    min_volume: float = 0.0
    max_volume: float = 0.0
    avg_volume: float = 0.0
    min_orthogonality: float = 0.0
    max_aspect_ratio: float = 0.0
    max_skewness: float = 0.0
    issues: List[QualityIssue] = field(default_factory=list)
    
    @property
    def is_valid(self) -> bool:
        """Check if mesh is valid (no critical issues)."""
        critical = [MeshQualityIssue.NEGATIVE_VOLUME, 
                   MeshQualityIssue.OVERLAPPING_CELLS,
                   MeshQualityIssue.ZERO_VOLUME]
        return not any(issue.issue_type in critical and issue 
                       for issue in self.issues)
    
    def has_issue(self, issue_type: MeshQualityIssue) -> bool:
        """Check if a specific issue type exists."""
        for issue in self.issues:
            if issue.issue_type == issue_type and issue:
                return True
        return False
    
    def get_issue(self, issue_type: MeshQualityIssue) -> Optional[QualityIssue]:
        """Get a specific issue by type."""
        for issue in self.issues:
            if issue.issue_type == issue_type:
                return issue
        return None
    
    def summary(self) -> str:
        """Generate a human-readable summary."""
        lines = [
            f"Mesh Quality Report:",
            f"  Cells: {self.n_cells}",
            f"  Faces: {self.n_faces}",
            f"  Boundary faces: {self.n_boundary_faces}",
            f"  Volume range: [{self.min_volume:.6e}, {self.max_volume:.6e}",
            f"  Average volume: {self.avg_volume:.6e}",
            f"  Min orthogonality: {self.min_orthogonality:.4f}",
            f"  Max aspect ratio: {self.max_aspect_ratio:.4f}",
            f"  Max skewness: {self.max_skewness:.4f}",
            "",
            "  Issues:",
        ]
        for issue in self.issues:
            if issue:
                lines.append(f"    {issue.issue_type.value}: {len(issue)} cells: {issue.message}")
        if not any(issue for issue in self.issues):
            lines.append("    None")
        return "\n".join(lines)


def check_volumes(mesh, tol_zero: float = 1e-12) -> QualityIssue:
    """
    Check for negative or zero volume cells.
    
    Uses Jacobian determinant for signed volume to detect negative volumes,
    and cell_volumes for zero volume detection.
    
    Parameters:
    -----------
    mesh : Mesh
        The mesh to check
    tol_zero : float
        Tolerance for zero volume detection
    
    Returns:
    --------
    issue : QualityIssue
        Issue containing problematic cell IDs
    """
    volumes = mesh.cell_volumes
    negative_cells = []
    zero_cells = []
    
    for cid in range(mesh.n_cells):
        if hasattr(mesh, 'compute_jacobian'):
            signed_vol = mesh.compute_jacobian(cid)
        else:
            signed_vol = volumes[cid]
        
        if signed_vol < -tol_zero:
            negative_cells.append(cid)
        elif abs(volumes[cid]) < tol_zero:
            zero_cells.append(cid)
    
    if negative_cells:
        return QualityIssue(
            MeshQualityIssue.NEGATIVE_VOLUME,
            negative_cells,
            f"Negative volume cells detected: {len(negative_cells)} cells",
            np.array([volumes[cid] for cid in negative_cells])
        )
    elif zero_cells:
        return QualityIssue(
            MeshQualityIssue.ZERO_VOLUME,
            zero_cells,
            f"Zero volume cells detected: {len(zero_cells)} cells",
            np.array([volumes[cid] for cid in zero_cells])
        )
    
    return QualityIssue(MeshQualityIssue.NEGATIVE_VOLUME, [], "No volume issues")


def check_overlapping_cells(mesh, tolerance: float = 1e-8) -> QualityIssue:
    """
    Check for overlapping cells using cell center distances.
    
    Two cells are considered overlapping if their centers are closer than
    the minimum cell dimension multiplied by tolerance.
    
    Parameters:
    -----------
    mesh : Mesh
        The mesh to check
    tolerance : float
        Relative tolerance for overlap detection
    
    Returns:
    --------
    issue : QualityIssue
        Issue containing overlapping cell IDs
    """
    centers = mesh.cell_centers
    volumes = mesh.cell_volumes
    n = len(centers)
    
    if n < 2:
        return QualityIssue(MeshQualityIssue.OVERLAPPING_CELLS, [], "No overlapping cells")
    
    min_cell_size = np.sqrt(np.min(volumes)) ** (1.0 / mesh.ndim)
    threshold = min_cell_size * tolerance
    
    overlapping = set()
    
    for i in range(n):
        for j in range(i + 1, n):
            dist = np.linalg.norm(centers[i] - centers[j])
            if dist < threshold:
                overlapping.add(i)
                overlapping.add(j)
    
    if overlapping:
        overlapping_list = sorted(list(overlapping))
        return QualityIssue(
            MeshQualityIssue.OVERLAPPING_CELLS,
            overlapping_list,
            f"Overlapping cells detected: {len(overlapping_list)} cells")
    
    return QualityIssue(
        MeshQualityIssue.OVERLAPPING_CELLS,
        [],
        "No overlapping cells")


def check_nonorthogonality(mesh, max_nonortho: float = 70.0) -> QualityIssue:
    """
    Check for non-orthogonality of internal faces.
    
    Non-orthogonality is the angle (in degrees) between the face normal
    and the vector connecting owner and neighbor cell centers.
    
    Parameters:
    -----------
    mesh : Mesh
        The mesh to check
    max_nonortho : float
        Maximum allowed non-orthogonality in degrees
    
    Returns:
    --------
    issue : QualityIssue
        Issue containing faces with high non-orthogonality
    """
    bad_faces = []
    nonortho_values = []
    
    for fid in range(mesh.n_faces):
        c1 = mesh.owner[fid]
        c2 = mesh.neighbour[fid]
        if c2 < 0:
            continue
        d = mesh.cell_centers[c2] - mesh.cell_centers[c1]
        d_norm = np.linalg.norm(d)
        if d_norm < 1e-15:
            continue
        normal = mesh.face_normals[fid]
        cos_theta = np.dot(d, normal) / (d_norm * np.linalg.norm(normal))
        cos_theta = max(-1.0, min(1.0, cos_theta))
        angle = np.arccos(abs(cos_theta))
        angle_deg = 90.0 - np.degrees(angle)
        if angle_deg > max_nonortho:
            bad_faces.append(fid)
            nonortho_values.append(angle_deg)
    
    if bad_faces:
        return QualityIssue(
            MeshQualityIssue.HIGH_NONORTHOGONALITY,
            bad_faces,
            f"High non-orthogonality faces: {len(bad_faces)} faces (max {max(nonortho_values):.2f}°)",
            np.array(nonortho_values))
    
    return QualityIssue(
        MeshQualityIssue.HIGH_NONORTHOGONALITY,
        [],
        "No high non-orthogonality issues")


def check_aspect_ratio(mesh, max_ar: float = 100.0) -> QualityIssue:
    """
    Check cell aspect ratios.
    
    Aspect ratio is defined as the ratio of maximum to minimum
    face length scale of a cell.
    
    Parameters:
    -----------
    mesh : Mesh
        The mesh to check
    max_ar : float
        Maximum allowed aspect ratio
    
    Returns:
    --------
    issue : QualityIssue
        Issue containing cells with high aspect ratio
    """
    bad_cells = []
    ar_values = []
    
    for cid, cell in enumerate(mesh.cells):
        points = mesh.points[cell]
        dists = []
        n = len(cell)
        for i in range(n):
            for j in range(i + 1, n):
                dists.append(np.linalg.norm(points[i] - points[j]))
        if dists:
            ar = max(dists) / max(min(dists), 1e-15)
            if ar > max_ar:
                bad_cells.append(cid)
                ar_values.append(ar)
    
    if bad_cells:
        return QualityIssue(
            MeshQualityIssue.HIGH_ASPECT_RATIO,
            bad_cells,
            f"High aspect ratio cells: {len(bad_cells)} cells (max {max(ar_values):.2f})",
            np.array(ar_values))
    
    return QualityIssue(
        MeshQualityIssue.HIGH_ASPECT_RATIO,
        [],
        "No high aspect ratio issues")


def check_skewness(mesh, max_skew: float = 0.8) -> QualityIssue:
    """
    Check cell skewness.
    
    Skewness is defined as the distance between the face center
    and the midpoint of the line connecting cell centers, normalized
    by the face-to-center distance.
    
    Parameters:
    -----------
    mesh : Mesh
        The mesh to check
    max_skew : float
        Maximum allowed skewness
    
    Returns:
    --------
    issue : QualityIssue
        Issue containing cells with high skewness
    """
    bad_faces = []
    skew_values = []
    
    for fid in range(mesh.n_faces):
        c1 = mesh.owner[fid]
        c2 = mesh.neighbour[fid]
        if c2 < 0:
            continue
        
        face_center = mesh.face_centers[fid]
        centers_mid = 0.5 * (mesh.cell_centers[c1] + mesh.cell_centers[c2])
        d = mesh.cell_centers[c2] - mesh.cell_centers[c1]
        d_norm = np.linalg.norm(d)
        if d_norm < 1e-15:
            continue
        skewness = np.linalg.norm(face_center - centers_mid) / d_norm
        if skewness > max_skew:
            bad_faces.append(fid)
            skew_values.append(skewness)
    
    if bad_faces:
        return QualityIssue(
            MeshQualityIssue.HIGH_SKEWNESS,
            bad_faces,
            f"High skewness faces: {len(bad_faces)} faces (max {max(skew_values):.4f})",
            np.array(skew_values))
    
    return QualityIssue(
        MeshQualityIssue.HIGH_SKEWNESS,
        [],
        "No high skewness issues")


def check_mesh_quality(mesh, 
                        check_overlap: bool = True,
                        check_volume: bool = True,
                        check_orthogonality: bool = True,
                        check_ar: bool = True,
                        check_skew: bool = True) -> MeshQualityReport:
    """
    Perform comprehensive mesh quality checking.
    
    Parameters:
    -----------
    mesh : Mesh
        The mesh to check
    check_overlap : bool
        Whether to check for overlapping cells
    check_volume : bool
        Whether to check for negative/zero volumes
    check_orthogonality : bool
        Whether to check non-orthogonality
    check_ar : bool
        Whether to check aspect ratio
    check_skew : bool
        Whether to check skewness
    
    Returns:
    --------
    report : MeshQualityReport
        Complete quality report
    """
    all_ar = []
    all_skew = []
    all_ortho = []
    
    for cid, cell in enumerate(mesh.cells):
        points = mesh.points[cell]
        dists = []
        n = len(cell)
        for i in range(n):
            for j in range(i + 1, n):
                dists.append(np.linalg.norm(points[i] - points[j]))
        if dists:
            ar = max(dists) / max(min(dists), 1e-15)
            all_ar.append(ar)
    
    if hasattr(mesh, 'face_areas') and hasattr(mesh, 'face_normals'):
        for fid in range(mesh.n_faces):
            if mesh.neighbour[fid] >= 0:
                c1, c2 = mesh.owner[fid], mesh.neighbour[fid]
                d = mesh.cell_centers[c2] - mesh.cell_centers[c1]
                d_norm = np.linalg.norm(d)
                if d_norm > 1e-15:
                    n = mesh.face_normals[fid]
                    cos_theta = abs(np.dot(d, n)) / d_norm
                    ortho = np.degrees(np.arccos(max(-1, min(1, cos_theta))))
                    all_ortho.append(ortho)
    
    for cid in range(mesh.n_cells):
        cc = mesh.cell_centers[cid]
        for fid in range(mesh.n_faces):
            if mesh.owner[fid] == cid or mesh.neighbour[fid] == cid:
                fc = mesh.face_centers[fid]
                face_nodes = mesh.faces[fid] if hasattr(mesh, 'faces') else mesh.cells[cid]
                break
        
        max_skew = 0.0
        for fid in range(mesh.n_faces):
            if mesh.owner[fid] == cid:
                fc = mesh.face_centers[fid]
                d = fc - cc
                d_norm = np.linalg.norm(d)
                if d_norm > 1e-15:
                    n = mesh.face_normals[fid]
                    if mesh.owner[fid] != cid:
                        n = -n
                    cos_theta = np.dot(d, n) / d_norm
                    skew = 90.0 - np.degrees(np.arccos(max(-1, min(1, cos_theta))))
                    max_skew = max(max_skew, skew)
        all_skew.append(max_skew)
    
    report = MeshQualityReport(
        n_cells=mesh.n_cells,
        n_faces=mesh.n_faces,
        n_boundary_faces=len(mesh.boundary_faces),
        min_volume=float(np.min(mesh.cell_volumes)),
        max_volume=float(np.max(mesh.cell_volumes)),
        avg_volume=float(np.mean(mesh.cell_volumes)),
        min_orthogonality=float(np.min(all_ortho)) if all_ortho else 0.0,
        max_aspect_ratio=float(np.max(all_ar)) if all_ar else 0.0,
        max_skewness=float(np.max(all_skew)) if all_skew else 0.0
    )
    
    if check_volume:
        vol_issue = check_volumes(mesh)
        if vol_issue:
            report.issues.append(vol_issue)
            if vol_issue.issue_type == MeshQualityIssue.NEGATIVE_VOLUME:
                return report
    
    if check_overlap:
        overlap_issue = check_overlapping_cells(mesh)
        if overlap_issue:
            report.issues.append(overlap_issue)
    
    if check_orthogonality:
        ortho_issue = check_nonorthogonality(mesh)
        if ortho_issue:
            report.issues.append(ortho_issue)
    
    if check_ar:
        ar_issue = check_aspect_ratio(mesh)
        if ar_issue:
            report.issues.append(ar_issue)
    
    if check_skew:
        skew_issue = check_skewness(mesh)
        if skew_issue:
            report.issues.append(skew_issue)
    
    return report


def validate_mesh(mesh) -> Tuple[bool, str]:
    """
    Validate mesh for computation.
    
    This performs critical checks only (volumes and overlaps.
    
    Parameters:
    -----------
    mesh : Mesh
        The mesh to validate
    
    Returns:
    --------
    is_valid : bool
        Whether the mesh is valid for computation
    message : str
        Error message if invalid
    """
    report = check_mesh_quality(mesh,
                                  check_overlap=True,
                                  check_volume=True,
                                  check_orthogonality=False,
                                  check_ar=False,
                                  check_skew=False)
    
    if not report.is_valid:
        issues = []
        for issue in report.issues:
            if issue:
                issues.append(f"{issue.issue_type.value}: {len(issue)} cells")
        return False, f"Mesh validation failed: " + "; ".join(issues)
    
    return True, "Mesh is valid"
