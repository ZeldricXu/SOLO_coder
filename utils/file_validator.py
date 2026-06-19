import os
import zlib
import hashlib
import gzip
import logging
from pathlib import Path
from dataclasses import dataclass, field
from typing import List, Optional, Dict, Any, Tuple
from enum import Enum


logger = logging.getLogger(__name__)


class ValidationIssueType(str, Enum):
    FILE_NOT_FOUND = "file_not_found"
    FILE_EMPTY = "file_empty"
    FILE_TRUNCATED = "file_truncated"
    SIZE_MISMATCH = "size_mismatch"
    MD5_MISMATCH = "md5_mismatch"
    INVALID_FASTQ = "invalid_fastq"
    INVALID_GZIP = "invalid_gzip"
    PERMISSION_DENIED = "permission_denied"
    UNKNOWN_ERROR = "unknown_error"


@dataclass
class ValidationIssue:
    issue_type: ValidationIssueType
    message: str
    file_path: Optional[str] = None
    expected: Optional[Any] = None
    actual: Optional[Any] = None


@dataclass
class FileIntegrityReport:
    file_path: str
    is_valid: bool = True
    file_size: int = 0
    expected_size: Optional[int] = None
    md5_hash: Optional[str] = None
    expected_md5: Optional[str] = None
    issues: List[ValidationIssue] = field(default_factory=list)
    fastq_read_count: int = 0
    fastq_total_bases: int = 0

    def add_issue(self, issue: ValidationIssue) -> None:
        self.issues.append(issue)
        self.is_valid = False

    @property
    def errors(self) -> List[ValidationIssue]:
        return self.issues

    def to_dict(self) -> Dict[str, Any]:
        return {
            "file_path": self.file_path,
            "is_valid": self.is_valid,
            "file_size": self.file_size,
            "expected_size": self.expected_size,
            "md5_hash": self.md5_hash,
            "expected_md5": self.expected_md5,
            "issues": [
                {
                    "issue_type": i.issue_type.value,
                    "message": i.message,
                    "file_path": i.file_path,
                    "expected": str(i.expected) if i.expected is not None else None,
                    "actual": str(i.actual) if i.actual is not None else None,
                }
                for i in self.issues
            ],
            "fastq_read_count": self.fastq_read_count,
            "fastq_total_bases": self.fastq_total_bases,
        }


class FileValidationError(Exception):
    def __init__(self, message: str, report: Optional[FileIntegrityReport] = None):
        super().__init__(message)
        self.report = report


class FileValidator:
    CHUNK_SIZE = 8192 * 1024

    def __init__(self, work_dir: Optional[str] = None):
        self.work_dir = Path(work_dir) if work_dir else None

    def _resolve_path(self, file_path: str) -> Path:
        path = Path(file_path)
        if not path.is_absolute() and self.work_dir:
            path = self.work_dir / path
        return path

    def calculate_md5(self, file_path: str) -> str:
        path = self._resolve_path(file_path)
        md5 = hashlib.md5()
        with open(path, "rb") as f:
            while chunk := f.read(self.CHUNK_SIZE):
                md5.update(chunk)
        return md5.hexdigest()

    def validate_file_exists(self, file_path: str, report: FileIntegrityReport) -> bool:
        path = self._resolve_path(file_path)
        if not path.exists():
            report.add_issue(ValidationIssue(
                issue_type=ValidationIssueType.FILE_NOT_FOUND,
                message=f"File not found: {file_path}",
                file_path=file_path,
            ))
            return False
        return True

    def validate_file_not_empty(self, file_path: str, report: FileIntegrityReport) -> bool:
        path = self._resolve_path(file_path)
        try:
            size = path.stat().st_size
            report.file_size = size
            if size == 0:
                report.add_issue(ValidationIssue(
                    issue_type=ValidationIssueType.FILE_EMPTY,
                    message=f"File is empty: {file_path}",
                    file_path=file_path,
                    expected=">0",
                    actual=0,
                ))
                return False
            return True
        except PermissionError:
            report.add_issue(ValidationIssue(
                issue_type=ValidationIssueType.PERMISSION_DENIED,
                message=f"Permission denied when accessing: {file_path}",
                file_path=file_path,
            ))
            return False

    def validate_file_size(
        self,
        file_path: str,
        expected_size: int,
        report: FileIntegrityReport,
        tolerance_ratio: float = 0.05,
    ) -> bool:
        path = self._resolve_path(file_path)
        actual_size = path.stat().st_size
        report.file_size = actual_size
        report.expected_size = expected_size

        if actual_size == 0:
            report.add_issue(ValidationIssue(
                issue_type=ValidationIssueType.FILE_EMPTY,
                message=f"File is empty: {file_path}",
                file_path=file_path,
                expected=expected_size,
                actual=0,
            ))
            return False

        min_allowed = expected_size * (1 - tolerance_ratio)
        max_allowed = expected_size * (1 + tolerance_ratio)

        if actual_size < min_allowed:
            report.add_issue(ValidationIssue(
                issue_type=ValidationIssueType.FILE_TRUNCATED,
                message=(
                    f"File appears truncated: {file_path}. "
                    f"Expected ~{expected_size} bytes, got {actual_size} bytes"
                ),
                file_path=file_path,
                expected=expected_size,
                actual=actual_size,
            ))
            return False

        if actual_size > max_allowed:
            report.add_issue(ValidationIssue(
                issue_type=ValidationIssueType.SIZE_MISMATCH,
                message=(
                    f"File size larger than expected: {file_path}. "
                    f"Expected ~{expected_size} bytes, got {actual_size} bytes"
                ),
                file_path=file_path,
                expected=expected_size,
                actual=actual_size,
            ))
            return False

        return True

    def validate_md5(
        self,
        file_path: str,
        expected_md5: str,
        report: FileIntegrityReport,
    ) -> bool:
        actual_md5 = self.calculate_md5(file_path)
        report.md5_hash = actual_md5
        report.expected_md5 = expected_md5

        if actual_md5.lower() != expected_md5.lower():
            report.add_issue(ValidationIssue(
                issue_type=ValidationIssueType.MD5_MISMATCH,
                message=(
                    f"MD5 hash mismatch: {file_path}. "
                    f"Expected {expected_md5}, got {actual_md5}"
                ),
                file_path=file_path,
                expected=expected_md5,
                actual=actual_md5,
            ))
            return False
        return True

    def validate_gzip_integrity(self, file_path: str, report: FileIntegrityReport) -> bool:
        path = self._resolve_path(file_path)
        if path.suffix.lower() not in (".gz", ".gzip"):
            return True
        try:
            with gzip.open(path, "rb") as f:
                while chunk := f.read(self.CHUNK_SIZE):
                    pass
            return True
        except (OSError, EOFError, gzip.BadGzipFile, zlib.error) as e:
            report.add_issue(ValidationIssue(
                issue_type=ValidationIssueType.INVALID_GZIP,
                message=f"Invalid gzip file {file_path}: {str(e)}",
                file_path=file_path,
                actual=str(e),
            ))
            return False

    def validate_fastq_structure(self, file_path: str, report: FileIntegrityReport) -> bool:
        path = self._resolve_path(file_path)
        is_gzipped = path.suffix.lower() in (".gz", ".gzip")
        open_func = gzip.open if is_gzipped else open
        read_count = 0
        total_bases = 0

        try:
            with open_func(path, "rt") as f:
                line_num = 0
                read_lines = []
                for line in f:
                    line = line.rstrip("\n")
                    line_num += 1
                    read_lines.append(line)

                    if len(read_lines) == 4:
                        header, seq, plus, qual = read_lines
                        read_count += 1
                        total_bases += len(seq)

                        if not header.startswith("@"):
                            report.add_issue(ValidationIssue(
                                issue_type=ValidationIssueType.INVALID_FASTQ,
                                message=(
                                    f"Invalid FASTQ header at read {read_count}: "
                                    f"expected '@' prefix, got '{header[0]}'"
                                ),
                                file_path=file_path,
                                actual=f"line {line_num - 3}",
                            ))
                            return False

                        if not plus.startswith("+"):
                            report.add_issue(ValidationIssue(
                                issue_type=ValidationIssueType.INVALID_FASTQ,
                                message=(
                                    f"Invalid FASTQ separator at read {read_count}: "
                                    f"expected '+' prefix"
                                ),
                                file_path=file_path,
                                actual=f"line {line_num - 1}",
                            ))
                            return False

                        if len(seq) != len(qual):
                            report.add_issue(ValidationIssue(
                                issue_type=ValidationIssueType.INVALID_FASTQ,
                                message=(
                                    f"FASTQ read {read_count}: sequence length ({len(seq)}) "
                                    f"!= quality length ({len(qual)})"
                                ),
                                file_path=file_path,
                                expected=len(seq),
                                actual=len(qual),
                            ))
                            return False

                        read_lines = []

                if len(read_lines) > 0:
                    report.add_issue(ValidationIssue(
                        issue_type=ValidationIssueType.FILE_TRUNCATED,
                        message=f"FASTQ file truncated: incomplete final read (found {len(read_lines)}/4 lines)",
                        file_path=file_path,
                        expected=4,
                        actual=len(read_lines),
                    ))
                    return False

            report.fastq_read_count = read_count
            report.fastq_total_bases = total_bases
            return True

        except (OSError, EOFError, gzip.BadGzipFile, UnicodeDecodeError, zlib.error) as e:
            report.add_issue(ValidationIssue(
                issue_type=ValidationIssueType.INVALID_FASTQ,
                message=f"Error reading FASTQ {file_path}: {str(e)}",
                file_path=file_path,
                actual=str(e),
            ))
            return False

    def validate_single_file(
        self,
        file_path: str,
        expected_size: Optional[int] = None,
        expected_md5: Optional[str] = None,
        check_fastq: bool = False,
        size_tolerance_ratio: float = 0.05,
    ) -> FileIntegrityReport:
        resolved = self._resolve_path(file_path)
        report = FileIntegrityReport(file_path=str(resolved))

        if not self.validate_file_exists(file_path, report):
            return report

        if expected_size is not None:
            self.validate_file_size(file_path, expected_size, report, size_tolerance_ratio)
        else:
            self.validate_file_not_empty(file_path, report)

        if report.file_size > 0 and report.is_valid:
            resolved_path = self._resolve_path(file_path)
            is_gz = resolved_path.suffix.lower() in (".gz", ".gzip")
            if is_gz:
                self.validate_gzip_integrity(file_path, report)

            if not report.is_valid:
                return report

            if check_fastq or any(
                str(resolved_path).endswith(suffix)
                for suffix in (".fastq", ".fq", ".fastq.gz", ".fq.gz")
            ):
                self.validate_fastq_structure(file_path, report)

            if expected_md5 is not None and report.is_valid:
                self.validate_md5(file_path, expected_md5, report)

        return report

    def validate_file_pair(
        self,
        r1_path: str,
        r2_path: str,
        expected_size_r1: Optional[int] = None,
        expected_size_r2: Optional[int] = None,
        expected_md5_r1: Optional[str] = None,
        expected_md5_r2: Optional[str] = None,
        check_paired_reads: bool = True,
    ) -> Tuple[FileIntegrityReport, FileIntegrityReport]:
        r1_report = self.validate_single_file(
            r1_path,
            expected_size=expected_size_r1,
            expected_md5=expected_md5_r1,
            check_fastq=True,
        )
        r2_report = self.validate_single_file(
            r2_path,
            expected_size=expected_size_r2,
            expected_md5=expected_md5_r2,
            check_fastq=True,
        )

        if check_paired_reads and r1_report.is_valid and r2_report.is_valid:
            if r1_report.fastq_read_count != r2_report.fastq_read_count:
                msg = (
                    f"Paired-end read count mismatch: "
                    f"R1 has {r1_report.fastq_read_count} reads, "
                    f"R2 has {r2_report.fastq_read_count} reads"
                )
                r1_report.add_issue(ValidationIssue(
                    issue_type=ValidationIssueType.INVALID_FASTQ,
                    message=msg + " (check R2)",
                    file_path=r1_path,
                    expected=r2_report.fastq_read_count,
                    actual=r1_report.fastq_read_count,
                ))
                r2_report.add_issue(ValidationIssue(
                    issue_type=ValidationIssueType.INVALID_FASTQ,
                    message=msg + " (check R1)",
                    file_path=r2_path,
                    expected=r1_report.fastq_read_count,
                    actual=r2_report.fastq_read_count,
                ))

        return r1_report, r2_report


def validate_fastq_pair(
    r1_path: str,
    r2_path: str,
    expected_size_r1: Optional[int] = None,
    expected_size_r2: Optional[int] = None,
    expected_md5_r1: Optional[str] = None,
    expected_md5_r2: Optional[str] = None,
) -> Tuple[FileIntegrityReport, FileIntegrityReport]:
    validator = FileValidator()
    return validator.validate_file_pair(
        r1_path, r2_path,
        expected_size_r1, expected_size_r2,
        expected_md5_r1, expected_md5_r2,
    )
