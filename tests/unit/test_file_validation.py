import sys
import os
import gzip
import hashlib
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from utils.file_validator import (
    FileValidator,
    FileIntegrityReport,
    FileValidationError,
    ValidationIssue,
    ValidationIssueType,
)


pytestmark = [pytest.mark.unit, pytest.mark.file_validation]


@pytest.fixture
def validator():
    return FileValidator()


def _issue_types(report: FileIntegrityReport):
    return {i.issue_type for i in report.issues}


class TestFileExistence:
    def test_file_not_found_returns_file_not_found_error(self, validator, tmp_path):
        nonexistent = tmp_path / "does_not_exist.txt"
        report = validator.validate_single_file(str(nonexistent))
        assert report.is_valid is False
        assert ValidationIssueType.FILE_NOT_FOUND in _issue_types(report)


class TestFileSizeValidation:
    def test_file_truncated_100_bytes_expected_1000(self, validator, tmp_path):
        f = tmp_path / "truncated.bin"
        f.write_bytes(b"\x00" * 100)
        report = validator.validate_single_file(str(f), expected_size=1000)
        assert report.is_valid is False
        assert ValidationIssueType.FILE_TRUNCATED in _issue_types(report)

    def test_empty_file_returns_file_empty_error(self, validator, tmp_path):
        f = tmp_path / "empty.bin"
        f.write_bytes(b"")
        report = validator.validate_single_file(str(f))
        assert report.is_valid is False
        assert ValidationIssueType.FILE_EMPTY in _issue_types(report)

    def test_size_within_5_percent_tolerance_passes(self, validator, tmp_path):
        expected_size = 1000
        actual_size = int(expected_size * 0.97)
        f = tmp_path / "within_tol.bin"
        f.write_bytes(b"\x00" * actual_size)
        report = validator.validate_single_file(str(f), expected_size=expected_size)
        assert report.is_valid is True
        assert len(report.issues) == 0


class TestMD5Validation:
    def test_md5_mismatch_detected(self, validator, tmp_path):
        f = tmp_path / "md5_test.bin"
        content = b"hello world md5 test content"
        f.write_bytes(content)
        wrong_md5 = "0" * 32
        report = validator.validate_single_file(str(f), expected_md5=wrong_md5)
        assert report.is_valid is False
        assert ValidationIssueType.MD5_MISMATCH in _issue_types(report)

    def test_correct_md5_passes(self, validator, tmp_path):
        f = tmp_path / "md5_ok.bin"
        content = b"hello world correct md5"
        f.write_bytes(content)
        correct_md5 = hashlib.md5(content).hexdigest()
        report = validator.validate_single_file(str(f), expected_md5=correct_md5)
        assert report.is_valid is True
        assert report.md5_hash == correct_md5


class TestGzipValidation:
    def test_invalid_gzip_detected(self, validator, tmp_path):
        f = tmp_path / "broken.gz"
        f.write_bytes(b"\x1f\x8b\x08\x00incomplete")
        report = validator.validate_single_file(str(f))
        assert report.is_valid is False
        assert ValidationIssueType.INVALID_GZIP in _issue_types(report)


class TestFastQStructureValidation:
    def test_valid_fastq_structure(self, validator, tmp_path):
        f = tmp_path / "valid.fastq"
        content = (
            "@read1\n"
            "ACGTACGT\n"
            "+\n"
            "FFFFFFFF\n"
            "@read2\n"
            "TGCA\n"
            "+\n"
            "BBBB\n"
        )
        f.write_text(content)
        report = validator.validate_single_file(str(f))
        assert report.is_valid is True
        assert report.fastq_read_count == 2
        assert report.fastq_total_bases == 12

    def test_fastq_missing_at_prefix(self, validator, tmp_path):
        f = tmp_path / "no_at.fastq"
        content = (
            "read1\n"
            "ACGT\n"
            "+\n"
            "FFFF\n"
        )
        f.write_text(content)
        report = validator.validate_single_file(str(f))
        assert report.is_valid is False
        assert ValidationIssueType.INVALID_FASTQ in _issue_types(report)

    def test_fastq_missing_plus_prefix(self, validator, tmp_path):
        f = tmp_path / "no_plus.fastq"
        content = (
            "@read1\n"
            "ACGT\n"
            "minus\n"
            "FFFF\n"
        )
        f.write_text(content)
        report = validator.validate_single_file(str(f))
        assert report.is_valid is False
        assert ValidationIssueType.INVALID_FASTQ in _issue_types(report)

    def test_fastq_seq_qual_length_mismatch(self, validator, tmp_path):
        f = tmp_path / "len_mismatch.fastq"
        content = (
            "@read1\n"
            "ACGTACGT\n"
            "+\n"
            "FFFF\n"
        )
        f.write_text(content)
        report = validator.validate_single_file(str(f))
        assert report.is_valid is False
        assert ValidationIssueType.INVALID_FASTQ in _issue_types(report)

    def test_fastq_truncated_incomplete_tail(self, validator, tmp_path):
        f = tmp_path / "truncated.fastq"
        content = (
            "@read1\n"
            "ACGT\n"
            "+\n"
            "FFFF\n"
            "@read2\n"
            "TGCA\n"
        )
        f.write_text(content)
        report = validator.validate_single_file(str(f))
        assert report.is_valid is False
        assert ValidationIssueType.FILE_TRUNCATED in _issue_types(report)


class TestPairedEndValidation:
    def test_paired_end_read_count_mismatch(self, validator, tmp_path):
        data_dir = tmp_path / "pair_mismatch"
        data_dir.mkdir()
        r1 = data_dir / "sample_R1.fastq.gz"
        r2 = data_dir / "sample_R2.fastq.gz"

        r1_content = (
            "@r1.1\nACGT\n+\nFFFF\n"
            "@r1.2\nTGCA\n+\nBBBB\n"
            "@r1.3\nAAAA\n+\nCCCC\n"
        )
        r2_content = (
            "@r2.1\nTTTT\n+\nDDDD\n"
            "@r2.2\nGGGG\n+\nEEEE\n"
        )
        with gzip.open(r1, "wt") as f:
            f.write(r1_content)
        with gzip.open(r2, "wt") as f:
            f.write(r2_content)

        r1_report, r2_report = validator.validate_file_pair(str(r1), str(r2))
        assert r1_report.is_valid is False or r2_report.is_valid is False
        assert ValidationIssueType.INVALID_FASTQ in _issue_types(r1_report) or \
               ValidationIssueType.INVALID_FASTQ in _issue_types(r2_report)

    def test_valid_paired_fastq_gz_pass(self, validator, tmp_path):
        data_dir = tmp_path / "pair_valid"
        data_dir.mkdir()
        r1 = data_dir / "sample_R1.fastq.gz"
        r2 = data_dir / "sample_R2.fastq.gz"

        r1_content = (
            "@r1.1\nACGTACGT\n+\nFFFFFFFF\n"
            "@r1.2\nTGCATGCA\n+\nBBBBBBBB\n"
        )
        r2_content = (
            "@r2.1\nTTTTTTTT\n+\nDDDDDDDD\n"
            "@r2.2\nGGGGGGGG\n+\nEEEEEEEE\n"
        )
        with gzip.open(r1, "wt") as f:
            f.write(r1_content)
        with gzip.open(r2, "wt") as f:
            f.write(r2_content)

        r1_report, r2_report = validator.validate_file_pair(str(r1), str(r2))
        assert r1_report.is_valid is True
        assert r2_report.is_valid is True
        assert r1_report.fastq_read_count == 2
        assert r2_report.fastq_read_count == 2


class TestFileIntegrityReport:
    def test_to_dict_serialization(self):
        report = FileIntegrityReport(
            file_path="/tmp/test.txt",
            is_valid=False,
            file_size=100,
            expected_size=200,
            md5_hash="abc123",
            expected_md5="def456",
            fastq_read_count=5,
            fastq_total_bases=500,
        )
        report.add_issue(ValidationIssue(
            issue_type=ValidationIssueType.FILE_TRUNCATED,
            message="File truncated",
            file_path="/tmp/test.txt",
            expected=200,
            actual=100,
        ))
        d = report.to_dict()
        assert isinstance(d, dict)
        assert d["file_path"] == "/tmp/test.txt"
        assert d["is_valid"] is False
        assert d["file_size"] == 100
        assert d["expected_size"] == 200
        assert d["md5_hash"] == "abc123"
        assert d["expected_md5"] == "def456"
        assert d["fastq_read_count"] == 5
        assert d["fastq_total_bases"] == 500
        assert isinstance(d["issues"], list)
        assert len(d["issues"]) == 1
        issue_d = d["issues"][0]
        assert issue_d["issue_type"] == "file_truncated"
        assert issue_d["message"] == "File truncated"
        assert issue_d["expected"] == "200"
        assert issue_d["actual"] == "100"


class TestFileValidationError:
    def test_file_validation_error_with_report(self):
        report = FileIntegrityReport(file_path="/tmp/err.txt", is_valid=False)
        err = FileValidationError("Something went wrong", report=report)
        assert str(err) == "Something went wrong"
        assert err.report is report
        assert err.report.file_path == "/tmp/err.txt"

    def test_file_validation_error_without_report(self):
        err = FileValidationError("Simple error")
        assert str(err) == "Simple error"
        assert err.report is None
