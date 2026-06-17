import re
import logging
from typing import List, Dict, Any
from pathlib import Path

from pipeline.executor import BaseStepExecutor, StepResult, StepExecutionError, register_executor
from config.pipeline_config import PipelineStepType
from config.settings import settings

logger = logging.getLogger(__name__)


@register_executor(PipelineStepType.BWA_MEM)
class BWA_MEMExecutor(BaseStepExecutor):
    """BWA-MEM sequence alignment executor with chromosome-level parallelism support."""

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id")
        chromosome = params.get("chromosome")

        if len(input_files) < 2:
            raise StepExecutionError("BWA-MEM requires paired-end input files")

        r1_in, r2_in = input_files[0], input_files[1]
        output_sam = str(self.work_dir / f"{sample_id}.sam")

        cmd = [
            settings.tools.bwa,
            "mem",
            "-t", str(params.get("threads", 8)),
            "-M",
            "-R", f"@RG\\tID:{sample_id}\\tSM:{sample_id}\\tLB:{sample_id}\\tPL:ILLUMINA",
        ]

        if chromosome:
            output_sam = str(self.work_dir / f"{sample_id}_{chromosome}.sam")
            cmd.extend(["-r", chromosome])

        reference = params.get("reference", settings.reference.hg38_bwa_index)
        cmd.extend([reference, r1_in, r2_in, "-o", output_sam])

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=params.get("timeout", 14400))

            if returncode != 0:
                raise StepExecutionError(
                    f"BWA-MEM failed with code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not Path(output_sam).exists():
                raise StepExecutionError("BWA-MEM output SAM file missing")

            metrics = self._parse_bwa_metrics(stderr, sample_id)
            metrics_file = self._save_metrics(step_id, metrics)

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=[output_sam, metrics_file],
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
            )

        except StepExecutionError:
            raise
        except Exception as e:
            raise StepExecutionError(f"BWA-MEM execution error: {e}")

    def _parse_bwa_metrics(self, stderr: str, sample_id: str) -> Dict[str, Any]:
        """Parse BWA-MEM log for alignment metrics."""
        metrics = {
            "sample_id": sample_id,
            "total_reads": 0,
            "mapped_reads": 0,
            "mapping_rate": 0.0,
            "properly_paired": 0,
            "proper_pair_rate": 0.0,
            "singletons": 0,
            "mate_other_chr": 0,
        }

        try:
            total_match = re.search(r"(\d+)\+(\d+) paired in", stderr)
            if total_match:
                metrics["total_reads"] = int(total_match.group(1)) + int(total_match.group(2))

            mapped_match = re.search(r"(\d+)\+(\d+) mapped \(([\d.]+)%", stderr)
            if mapped_match:
                metrics["mapped_reads"] = int(mapped_match.group(1)) + int(mapped_match.group(2))
                metrics["mapping_rate"] = float(mapped_match.group(3))

            properly_paired_match = re.search(r"(\d+)\+(\d+) properly paired \(([\d.]+)%", stderr)
            if properly_paired_match:
                metrics["properly_paired"] = int(properly_paired_match.group(1))
                metrics["proper_pair_rate"] = float(properly_paired_match.group(3))

            singleton_match = re.search(r"(\d+)\+(\d+) singletons", stderr)
            if singleton_match:
                metrics["singletons"] = int(singleton_match.group(1))

            mate_match = re.search(r"(\d+)\+(\d+) with mate mapped to a different chr", stderr)
            if mate_match:
                metrics["mate_other_chr"] = int(mate_match.group(1))

        except Exception as e:
            logger.warning(f"Failed to parse BWA metrics: {e}")

        return metrics


@register_executor(PipelineStepType.SAMTOOLS_SORT)
class SamtoolsSortExecutor(BaseStepExecutor):
    """SAMtools coordinate sorting executor."""

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id")

        if not input_files:
            raise StepExecutionError("No input file for SAMtools sort")

        input_sam = input_files[0]
        output_bam = str(self.work_dir / f"{sample_id}.sorted.bam")

        cmd = [
            settings.tools.samtools,
            "sort",
            "-@", str(params.get("threads", 8)),
            "-o", output_bam,
            "-O", "BAM",
            "-m", params.get("memory", "4G"),
            input_sam,
        ]

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=params.get("timeout", 7200))

            if returncode != 0:
                raise StepExecutionError(
                    f"SAMtools sort failed with code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not Path(output_bam).exists():
                raise StepExecutionError("Sorted BAM file missing")

            metrics = {
                "sample_id": sample_id,
                "output_bam": output_bam,
                "size_bytes": Path(output_bam).stat().st_size,
            }

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=[output_bam],
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
            )

        except StepExecutionError:
            raise
        except Exception as e:
            raise StepExecutionError(f"SAMtools sort execution error: {e}")


@register_executor(PipelineStepType.SAMTOOLS_INDEX)
class SamtoolsIndexExecutor(BaseStepExecutor):
    """SAMtools BAM indexing executor."""

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id")

        if not input_files:
            raise StepExecutionError("No input BAM file for indexing")

        input_bam = input_files[0]
        output_bai = input_bam + ".bai"

        cmd = [
            settings.tools.samtools,
            "index",
            "-@", str(params.get("threads", 4)),
            input_bam,
        ]

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=params.get("timeout", 3600))

            if returncode != 0:
                raise StepExecutionError(
                    f"SAMtools index failed with code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not Path(output_bai).exists():
                raise StepExecutionError("BAM index file missing")

            metrics = {
                "sample_id": sample_id,
                "bam_file": input_bam,
                "index_file": output_bai,
            }

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=[output_bai],
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
            )

        except StepExecutionError:
            raise
        except Exception as e:
            raise StepExecutionError(f"SAMtools index execution error: {e}")


@register_executor(PipelineStepType.MARK_DUPLICATES)
class MarkDuplicatesExecutor(BaseStepExecutor):
    """Picard MarkDuplicates executor."""

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id")

        if not input_files:
            raise StepExecutionError("No input BAM file for MarkDuplicates")

        input_bam = input_files[0]
        output_bam = str(self.work_dir / f"{sample_id}.dedup.bam")
        metrics_file = str(self.work_dir / f"{sample_id}_duplicate_metrics.txt")

        cmd = [
            settings.tools.picard,
            "MarkDuplicates",
            f"INPUT={input_bam}",
            f"OUTPUT={output_bam}",
            f"METRICS_FILE={metrics_file}",
            f"ASSUME_SORTED={params.get('assume_sorted', 'true')}",
            f"REMOVE_DUPLICATES={params.get('remove_duplicates', 'false')}",
            f"CREATE_INDEX={params.get('create_index', 'true')}",
            f"VALIDATION_STRINGENCY={params.get('validation_stringency', 'SILENT')}",
            f"MAX_RECORDS_IN_RAM={params.get('max_records_in_ram', '150000')}",
        ]

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=params.get("timeout", 10800))

            if returncode != 0:
                raise StepExecutionError(
                    f"Picard MarkDuplicates failed with code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not self._check_files_exist([output_bam, metrics_file]):
                raise StepExecutionError("MarkDuplicates output files missing")

            metrics = self._parse_duplicate_metrics(metrics_file, sample_id)

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=[output_bam, metrics_file],
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
            )

        except StepExecutionError:
            raise
        except Exception as e:
            raise StepExecutionError(f"MarkDuplicates execution error: {e}")

    def _parse_duplicate_metrics(self, metrics_file: str, sample_id: str) -> Dict[str, Any]:
        """Parse Picard duplicate metrics file."""
        metrics = {
            "sample_id": sample_id,
            "total_reads": 0,
            "duplicate_reads": 0,
            "duplication_rate": 0.0,
            "estimated_library_size": 0,
        }

        try:
            with open(metrics_file, "r") as f:
                content = f.read()

            lines = content.split("\n")
            in_metrics = False
            header = None

            for line in lines:
                if line.startswith("## METRICS CLASS"):
                    in_metrics = True
                    continue
                if in_metrics and line.startswith("LIBRARY"):
                    header = line.split("\t")
                    continue
                if in_metrics and header and line.strip():
                    values = line.split("\t")
                    data = dict(zip(header, values))

                    metrics["total_reads"] = int(data.get("READ_PAIRS_EXAMINED", 0)) * 2
                    metrics["duplicate_reads"] = int(data.get("UNPAIRED_READS_EXAMINED", 0)) + \
                        int(data.get("READ_PAIR_DUPLICATES", 0)) * 2
                    metrics["duplication_rate"] = float(data.get("PERCENT_DUPLICATION", 0)) * 100
                    metrics["estimated_library_size"] = int(data.get("ESTIMATED_LIBRARY_SIZE", 0))
                    break

        except Exception as e:
            logger.warning(f"Failed to parse duplicate metrics: {e}")

        return metrics


@register_executor(PipelineStepType.BASE_RECALIBRATOR)
class BaseRecalibratorExecutor(BaseStepExecutor):
    """GATK BaseRecalibrator executor."""

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id")

        if not input_files:
            raise StepExecutionError("No input BAM file for BaseRecalibrator")

        input_bam = input_files[0]
        output_table = str(self.work_dir / f"{sample_id}_recal_data.table")
        reference = params.get("reference", settings.reference.hg38_fasta)

        cmd = [
            settings.tools.gatk,
            "BaseRecalibrator",
            "-R", reference,
            "-I", input_bam,
            "-O", output_table,
            "--known-sites", settings.reference.known_sites_snp,
            "--known-sites", settings.reference.known_sites_indel,
            "--known-sites", settings.reference.known_sites_1000g,
        ]

        if params.get("intervals"):
            cmd.extend(["-L", params.get("intervals")])

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=params.get("timeout", 14400))

            if returncode != 0:
                raise StepExecutionError(
                    f"GATK BaseRecalibrator failed with code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not Path(output_table).exists():
                raise StepExecutionError("Recalibration table missing")

            metrics = {
                "sample_id": sample_id,
                "recalibration_table": output_table,
            }

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=[output_table],
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
            )

        except StepExecutionError:
            raise
        except Exception as e:
            raise StepExecutionError(f"BaseRecalibrator execution error: {e}")


@register_executor(PipelineStepType.APPLY_BQSR)
class ApplyBQSRExecutor(BaseStepExecutor):
    """GATK ApplyBQSR executor."""

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id")

        if len(input_files) < 2:
            raise StepExecutionError("ApplyBQSR requires BAM and recalibration table")

        input_bam, recal_table = input_files[0], input_files[1]
        output_bam = str(self.work_dir / f"{sample_id}.recal.bam")
        output_bai = output_bam + ".bai"
        reference = params.get("reference", settings.reference.hg38_fasta)

        cmd = [
            settings.tools.gatk,
            "ApplyBQSR",
            "-R", reference,
            "-I", input_bam,
            "--bqsr-recal-file", recal_table,
            "-O", output_bam,
            "--create-output-bam-index", "true",
            "--emit-original-quals", "false",
        ]

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=params.get("timeout", 10800))

            if returncode != 0:
                raise StepExecutionError(
                    f"GATK ApplyBQSR failed with code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not self._check_files_exist([output_bam, output_bai]):
                raise StepExecutionError("ApplyBQSR output files missing")

            metrics = {
                "sample_id": sample_id,
                "recalibrated_bam": output_bam,
                "size_bytes": Path(output_bam).stat().st_size,
            }

            metrics_file = self._save_metrics(step_id, metrics)

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=[output_bam, output_bai, metrics_file],
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
            )

        except StepExecutionError:
            raise
        except Exception as e:
            raise StepExecutionError(f"ApplyBQSR execution error: {e}")
