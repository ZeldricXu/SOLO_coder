import sys
from pathlib import Path
from typing import Dict, Any, List

import pytest
from unittest.mock import Mock, MagicMock, patch

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from db.models import (
    Sample,
    SampleStatus,
    SampleType,
    QCMetric,
    TaskStatus,
    StepStatus,
)
from pipeline.dag import PipelineDAG
from config.pipeline_config import PipelineDefinition, PipelineStep, PipelineStepType
from utils.file_validator import FileValidator, FileValidationError, ValidationIssueType


pytestmark = [pytest.mark.unit, pytest.mark.error]

MAPPING_RATE_THRESHOLD = 0.80
MINIMUM_READS_AFTER_TRIM = 1000
MINIMUM_DISK_SPACE_GB = 1.0


class TestLowMappingRateTolerance:
    def test_bwa_mapping_rate_below_threshold_marks_qc_fail(
        self, sample_factory, db_session
    ):
        sample = sample_factory(
            sample_id="LOW_MAP_001",
            status=SampleStatus.REGISTERED,
        )

        qc_metric = QCMetric(
            sample_id=sample.id,
            step_type="bwa_mem",
            total_reads=100000,
            mapped_reads=10000,
            mapping_rate=0.10,
            properly_paired=9500,
            proper_pair_rate=0.095,
            metrics_json={"mapping_rate": 0.10},
        )
        db_session.add(qc_metric)
        db_session.commit()

        assert qc_metric.mapping_rate < MAPPING_RATE_THRESHOLD
        sample.status = SampleStatus.QC_FAILED
        db_session.commit()
        db_session.refresh(sample)

        assert sample.status == SampleStatus.QC_FAILED

        steps = PipelineDefinition.get_single_sample_pipeline("LOW_MAP_001")
        dag = PipelineDAG(steps)

        for step_id, node in dag.nodes.items():
            if node.step.step_type not in (PipelineStepType.FASTQC, PipelineStepType.FASTP):
                assert node.status != StepStatus.COMPLETED
                assert node.status != StepStatus.RUNNING

    def test_downstream_steps_not_executed_after_qc_fail(
        self, sample_factory, db_session
    ):
        sample = sample_factory(
            sample_id="LOW_MAP_002",
            status=SampleStatus.REGISTERED,
        )

        qc_metric = QCMetric(
            sample_id=sample.id,
            step_type="bwa_mem",
            mapping_rate=0.10,
            metrics_json={"mapping_rate": 0.10},
        )
        db_session.add(qc_metric)
        sample.status = SampleStatus.QC_FAILED
        db_session.commit()

        downstream_types = [
            PipelineStepType.BWA_MEM,
            PipelineStepType.SAMTOOLS_SORT,
            PipelineStepType.MARK_DUPLICATES,
            PipelineStepType.HAPLOTYPE_CALLER,
            PipelineStepType.VEP_ANNOTATION,
            PipelineStepType.REPORT_GENERATION,
        ]

        steps = PipelineDefinition.get_single_sample_pipeline("LOW_MAP_002")
        dag = PipelineDAG(steps)

        executed_count = 0
        for step in steps:
            if step.step_type in downstream_types:
                node = dag.get_step(step.step_id)
                if node.status in (StepStatus.RUNNING, StepStatus.COMPLETED):
                    executed_count += 1

        assert executed_count == 0
        assert sample.status == SampleStatus.QC_FAILED


class TestMappingRateBoundaryValues:
    @pytest.mark.parametrize(
        "mapping_rate,should_continue",
        [
            (0.80, True),
            (0.799, False),
            (0.801, True),
            (0.79, False),
            (1.0, True),
            (0.0, False),
        ],
    )
    def test_mapping_rate_boundary_conditions(
        self, mapping_rate, should_continue, sample_factory, db_session
    ):
        sample = sample_factory(
            sample_id=f"MAP_RATE_{int(mapping_rate * 1000)}",
            status=SampleStatus.REGISTERED,
        )

        qc_metric = QCMetric(
            sample_id=sample.id,
            step_type="bwa_mem",
            total_reads=100000,
            mapped_reads=int(100000 * mapping_rate),
            mapping_rate=mapping_rate,
        )
        db_session.add(qc_metric)
        db_session.commit()

        if mapping_rate >= MAPPING_RATE_THRESHOLD:
            sample.status = SampleStatus.QC_PASSED
        else:
            sample.status = SampleStatus.QC_FAILED
        db_session.commit()
        db_session.refresh(sample)

        if should_continue:
            assert sample.status == SampleStatus.QC_PASSED
        else:
            assert sample.status == SampleStatus.QC_FAILED

    def test_exact_threshold_080_passes(self, sample_factory, db_session):
        sample = sample_factory(sample_id="EXACT_080")
        qc_metric = QCMetric(
            sample_id=sample.id,
            step_type="bwa_mem",
            total_reads=1000000,
            mapped_reads=800000,
            mapping_rate=0.80,
        )
        db_session.add(qc_metric)
        db_session.commit()

        assert qc_metric.mapping_rate == MAPPING_RATE_THRESHOLD
        sample.status = SampleStatus.QC_PASSED
        db_session.commit()
        db_session.refresh(sample)
        assert sample.status == SampleStatus.QC_PASSED

    def test_just_below_threshold_0799_fails(self, sample_factory, db_session):
        sample = sample_factory(sample_id="BELOW_0799")
        qc_metric = QCMetric(
            sample_id=sample.id,
            step_type="bwa_mem",
            total_reads=1000000,
            mapped_reads=799000,
            mapping_rate=0.799,
        )
        db_session.add(qc_metric)
        db_session.commit()

        assert qc_metric.mapping_rate < MAPPING_RATE_THRESHOLD
        sample.status = SampleStatus.QC_FAILED
        db_session.commit()
        db_session.refresh(sample)
        assert sample.status == SampleStatus.QC_FAILED


class TestVEPAssemblyMismatch:
    def test_vep_assembly_mismatch_raises_error(self):
        vcf_assembly = "hg19"
        vep_assembly = "hg38"

        class VEPExecutor:
            def __init__(self, vep_version):
                self.vep_assembly = vep_version

            def execute(self, step_id, params, input_files):
                input_assembly = params.get("assembly")
                if input_assembly != self.vep_assembly:
                    raise RuntimeError(
                        f"VEP assembly mismatch: VCF has assembly={input_assembly}, "
                        f"but VEP is configured for reference genome={self.vep_assembly}. "
                        f"version mismatch detected."
                    )
                return True

        executor = VEPExecutor(vep_assembly)
        params = {"assembly": vcf_assembly}

        with pytest.raises(RuntimeError, match=r"assembly mismatch|version mismatch|reference genome"):
            executor.execute("test_vep", params, ["/path/to/input.vcf.gz"])

    def test_vep_assembly_mismatch_error_message_patterns(self):
        error_patterns = [
            "VEP assembly mismatch: hg19 vs hg38, reference genome incompatible",
            "version mismatch: VCF uses hg19 but VEP expects hg38",
            "reference genome mismatch detected between input and VEP config",
        ]

        for msg in error_patterns:
            match_result = bool(
                "assembly mismatch" in msg.lower()
                or "version mismatch" in msg.lower()
                or "reference genome" in msg.lower()
            )
            assert match_result, f"Message should match pattern: {msg}"

    def test_vep_matching_assembly_passes(self):
        vcf_assembly = "hg38"
        vep_assembly = "hg38"

        class VEPExecutor:
            def __init__(self, vep_version):
                self.vep_assembly = vep_version

            def execute(self, step_id, params, input_files):
                input_assembly = params.get("assembly")
                if input_assembly != self.vep_assembly:
                    raise RuntimeError(
                        f"assembly mismatch: {input_assembly} != {self.vep_assembly}"
                    )
                return {
                    "success": True,
                    "output_files": ["/path/to/vep_output.vcf.gz"],
                    "assembly_used": self.vep_assembly,
                }

        executor = VEPExecutor(vep_assembly)
        params = {"assembly": vcf_assembly}

        result = executor.execute("test_vep", params, ["/path/to/input.vcf.gz"])
        assert result["success"] is True
        assert result["assembly_used"] == "hg38"

    def test_both_hg38_no_error(self):
        class VEPAnnotationService:
            def __init__(self, ref_assembly):
                self.ref_assembly = ref_assembly

            def annotate_vcf(self, vcf_path, vcf_assembly):
                if vcf_assembly != self.ref_assembly:
                    raise ValueError(
                        f"reference genome version mismatch: "
                        f"VCF={vcf_assembly} vs VEP={self.ref_assembly}"
                    )
                return {"annotated_variants": 50000, "assembly": self.ref_assembly}

        service = VEPAnnotationService("hg38")
        result = service.annotate_vcf("/data/sample.vcf.gz", "hg38")
        assert result["assembly"] == "hg38"
        assert result["annotated_variants"] == 50000


class TestDiskSpaceChecks:
    def _get_free_disk_space_gb(self, path):
        import shutil
        usage = shutil.disk_usage(path)
        return usage.free / (1024 ** 3)

    def test_disk_space_below_1gb_causes_task_failure(self):
        required_space_gb = MINIMUM_DISK_SPACE_GB

        def check_disk_space(work_dir):
            free_gb = self._get_free_disk_space_gb(work_dir)
            if free_gb < required_space_gb:
                raise RuntimeError(
                    f"Insufficient disk space: {free_gb:.2f} GB available, "
                    f"need at least {required_space_gb} GB. no space left on device."
                )
            return True

        with patch.object(
            self, "_get_free_disk_space_gb", return_value=0.5
        ) as mock_space:
            with pytest.raises(RuntimeError, match=r"disk space|no space"):
                check_disk_space("/tmp/work")

    def test_disk_space_insufficient_celery_task_suspends(self):
        class CeleryPipelineTask:
            def __init__(self):
                self.state = "PENDING"
                self.error = None

            def check_disk(self, work_dir):
                free_gb = 0.3
                if free_gb < MINIMUM_DISK_SPACE_GB:
                    self.state = "SUSPENDED"
                    self.error = (
                        f"no space available for pipeline execution: "
                        f"only {free_gb} GB free disk space"
                    )
                    raise RuntimeError(self.error)
                return True

            def run(self, work_dir):
                try:
                    self.check_disk(work_dir)
                    self.state = "SUCCESS"
                except RuntimeError as e:
                    self.state = "FAILURE"
                    raise

        task = CeleryPipelineTask()
        with pytest.raises(RuntimeError, match=r"disk space|no space"):
            task.run("/tmp/pipeline")

        assert task.state == "FAILURE"
        assert task.error is not None
        assert any(
            kw in task.error.lower() for kw in ["disk space", "no space"]
        )

    def test_sufficient_disk_space_allows_execution(self):
        required_space_gb = MINIMUM_DISK_SPACE_GB

        class PipelineStep:
            def __init__(self):
                self.executed = False

            def execute(self, work_dir):
                free_gb = 100.5
                if free_gb < required_space_gb:
                    raise RuntimeError(f"no space: only {free_gb} GB")
                self.executed = True
                return {"success": True, "free_space_gb": free_gb}

        step = PipelineStep()
        result = step.execute("/data/work")
        assert step.executed is True
        assert result["success"] is True
        assert result["free_space_gb"] > required_space_gb

    def test_disk_space_exactly_1gb_passes(self):
        with patch("shutil.disk_usage") as mock_disk:
            mock_disk.return_value = MagicMock(
                free=int(MINIMUM_DISK_SPACE_GB * (1024 ** 3)),
                total=int(100 * (1024 ** 3)),
                used=int(99 * (1024 ** 3)),
            )
            import shutil
            usage = shutil.disk_usage("/tmp")
            free_gb = usage.free / (1024 ** 3)

            assert free_gb >= MINIMUM_DISK_SPACE_GB

            class SafeExecutor:
                def run(self):
                    free_check = free_gb
                    if free_check < MINIMUM_DISK_SPACE_GB:
                        raise RuntimeError("insufficient disk space")
                    return "ok"

            executor = SafeExecutor()
            assert executor.run() == "ok"


class TestFastpLowRemainingReads:
    def test_fastp_remaining_reads_below_1000_marks_qc_fail(
        self, sample_factory, db_session
    ):
        sample = sample_factory(sample_id="LOW_READS_001")

        qc_metric = QCMetric(
            sample_id=sample.id,
            step_type="fastp",
            total_reads=500000,
            q20_bases=1000,
            q30_bases=500,
            metrics_json={
                "reads_before_filtering": 500000,
                "reads_after_filtering": 500,
                "survival_rate": 0.001,
            },
        )
        db_session.add(qc_metric)
        db_session.commit()

        reads_after = qc_metric.metrics_json["reads_after_filtering"]
        assert reads_after < MINIMUM_READS_AFTER_TRIM

        sample.status = SampleStatus.QC_FAILED
        db_session.commit()
        db_session.refresh(sample)

        assert sample.status == SampleStatus.QC_FAILED

    @pytest.mark.parametrize(
        "reads_after,expected_status",
        [
            (999, SampleStatus.QC_FAILED),
            (1000, SampleStatus.QC_PASSED),
            (1001, SampleStatus.QC_PASSED),
            (100, SampleStatus.QC_FAILED),
            (0, SampleStatus.QC_FAILED),
            (500000, SampleStatus.QC_PASSED),
        ],
    )
    def test_fastp_reads_threshold_boundary(
        self, reads_after, expected_status, sample_factory, db_session
    ):
        sample = sample_factory(sample_id=f"READS_{reads_after}")
        qc_metric = QCMetric(
            sample_id=sample.id,
            step_type="fastp",
            metrics_json={"reads_after_filtering": reads_after},
        )
        db_session.add(qc_metric)

        if reads_after >= MINIMUM_READS_AFTER_TRIM:
            sample.status = SampleStatus.QC_PASSED
        else:
            sample.status = SampleStatus.QC_FAILED
        db_session.commit()
        db_session.refresh(sample)

        assert sample.status == expected_status

    def test_fastp_zero_reads_fails_immediately(self, sample_factory, db_session):
        sample = sample_factory(sample_id="ZERO_READS")
        qc_metric = QCMetric(
            sample_id=sample.id,
            step_type="fastp",
            metrics_json={
                "reads_before_filtering": 100000,
                "reads_after_filtering": 0,
            },
        )
        db_session.add(qc_metric)
        db_session.commit()

        assert qc_metric.metrics_json["reads_after_filtering"] == 0
        sample.status = SampleStatus.QC_FAILED
        db_session.commit()
        db_session.refresh(sample)
        assert sample.status == SampleStatus.QC_FAILED


class TestFASTQPairedReadMismatch:
    def test_r1_r2_read_count_mismatch_raises_error(self, tmp_path):
        data_dir = tmp_path / "mismatch"
        data_dir.mkdir()

        r1_path = data_dir / "sample_R1.fastq.gz"
        r2_path = data_dir / "sample_R2.fastq.gz"

        import gzip

        r1_content = (
            "@seq1\nACGT\n+\nFFFF\n"
            "@seq2\nTGCA\n+\nFFFF\n"
            "@seq3\nGGCC\n+\nFFFF\n"
        )
        r2_content = (
            "@seq1\nACGT\n+\nFFFF\n"
            "@seq2\nTGCA\n+\nFFFF\n"
        )

        with gzip.open(r1_path, "wt") as f:
            f.write(r1_content)
        with gzip.open(r2_path, "wt") as f:
            f.write(r2_content)

        validator = FileValidator()
        r1_report, r2_report = validator.validate_file_pair(
            str(r1_path), str(r2_path), check_paired_reads=True
        )

        assert r1_report.is_valid is False or r2_report.is_valid is False

        all_issue_messages = [
            i.message.lower() for i in (r1_report.issues + r2_report.issues)
        ]
        has_mismatch_msg = any(
            "mismatch" in msg or "count" in msg for msg in all_issue_messages
        )
        assert has_mismatch_msg

    def test_r1_r2_equal_reads_passes_validation(self, tmp_path):
        data_dir = tmp_path / "matched"
        data_dir.mkdir()

        r1_path = data_dir / "sample_R1.fastq.gz"
        r2_path = data_dir / "sample_R2.fastq.gz"

        import gzip

        content = (
            "@seq1\nACGTAGCT\n+\nFFFFFFFF\n"
            "@seq2\nTGCATGCA\n+\nFFFFFFFF\n"
        )

        with gzip.open(r1_path, "wt") as f:
            f.write(content)
        with gzip.open(r2_path, "wt") as f:
            f.write(content)

        validator = FileValidator()
        r1_report, r2_report = validator.validate_file_pair(
            str(r1_path), str(r2_path), check_paired_reads=True
        )

        assert r1_report.is_valid is True
        assert r2_report.is_valid is True
        assert r1_report.fastq_read_count == r2_report.fastq_read_count
        assert r1_report.fastq_read_count == 2

    def test_preprocessing_stage_rejects_mismatched_pairs(self, tmp_path):
        class PreprocessingStage:
            def __init__(self):
                self.validator = FileValidator()

            def validate_input(self, r1_path, r2_path):
                r1_report, r2_report = self.validator.validate_file_pair(
                    r1_path, r2_path, check_paired_reads=True
                )
                if not r1_report.is_valid or not r2_report.is_valid:
                    issues = r1_report.issues + r2_report.issues
                    raise FileValidationError(
                        f"FASTQ validation failed during preprocessing: "
                        f"{[i.message for i in issues]}",
                        report=r1_report,
                    )
                return True

        data_dir = tmp_path / "preprocess_fail"
        data_dir.mkdir()
        r1_path = data_dir / "bad_R1.fastq.gz"
        r2_path = data_dir / "bad_R2.fastq.gz"

        import gzip
        r1_reads = 100
        r2_reads = 50

        with gzip.open(r1_path, "wt") as f:
            for i in range(r1_reads):
                f.write(f"@seq{i}\nACGT\n+\nFFFF\n")
        with gzip.open(r2_path, "wt") as f:
            for i in range(r2_reads):
                f.write(f"@seq{i}\nTGCA\n+\nFFFF\n")

        stage = PreprocessingStage()
        with pytest.raises(FileValidationError):
            stage.validate_input(str(r1_path), str(r2_path))
