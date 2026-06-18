import logging
import json
import re
from pathlib import Path
from typing import List, Dict, Any
from datetime import datetime

from pipeline.executor import BaseStepExecutor, StepResult, register_executor, StepExecutionError
from config.settings import settings
from config.pipeline_config import PipelineStepType
from storage.repository import SampleRepository, QCMetricRepository

logger = logging.getLogger(__name__)


@register_executor(PipelineStepType.BWA_MEM)
class BWAMEMExecutor(BaseStepExecutor):
    def execute(
        self,
        step_id: str,
        params: Dict[str, Any],
        input_files: List[str],
    ) -> StepResult:
        start_time = datetime.now()
        sample_id = params.get("sample_id", step_id.split("_")[0])
        chromosome = params.get("chromosome")

        if len(input_files) < 2:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message="BWA-MEM requires at least 2 input files (R1 and R2)",
            )

        r1_path, r2_path = input_files[0], input_files[1]

        output_suffix = f"_{chromosome}" if chromosome else ""
        sam_output = self.work_dir / f"{sample_id}{output_suffix}.sam"

        cmd = [
            settings.tools.bwa,
            "mem",
            "-t", "8",
            "-R", f"@RG\\tID:{sample_id}\\tSM:{sample_id}\\tLB:lib1\\tPL:ILLUMINA\\tPU:unit1",
            "-M",
            settings.reference.hg38_bwa_index,
            r1_path,
            r2_path,
        ]

        if chromosome:
            cmd.extend(["-r", chromosome])

        try:
            returncode, stdout, stderr = self._run_command(cmd)

            with open(sam_output, "w") as f:
                f.write(stdout)

            if returncode != 0:
                raise StepExecutionError(
                    f"BWA-MEM failed with exit code {returncode}",
                    return_code=returncode,
                    stdout=stdout[:1000],
                    stderr=stderr,
                )

            if not sam_output.exists() or sam_output.stat().st_size == 0:
                raise StepExecutionError(
                    "BWA-MEM produced empty output",
                    return_code=returncode,
                    stdout=stdout[:1000],
                    stderr=stderr,
                )

            metrics = self._parse_bwa_metrics(stderr)

            output_files = [str(sam_output)]

            duration = (datetime.now() - start_time).total_seconds()

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=output_files,
                metrics=metrics,
                stdout=stdout[:1000],
                stderr=stderr,
                duration_seconds=duration,
            )

        except StepExecutionError as e:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message=str(e),
                stdout=e.stdout,
                stderr=e.stderr,
                duration_seconds=(datetime.now() - start_time).total_seconds(),
            )

    def _parse_bwa_metrics(self, stderr: str) -> Dict[str, Any]:
        metrics = {}

        read_match = re.search(r"Processed (\d+) reads", stderr)
        if read_match:
            metrics["processed_reads"] = int(read_match.group(1))

        mem_match = re.search(r"Peak RAM usage: (\d+\.?\d*)", stderr)
        if mem_match:
            metrics["peak_ram_gb"] = float(mem_match.group(1))

        time_match = re.search(r"Total time: (\d+\.?\d*) sec", stderr)
        if time_match:
            metrics["total_time_sec"] = float(time_match.group(1))

        return metrics


@register_executor(PipelineStepType.SAMTOOLS_SORT)
class SamtoolsSortExecutor(BaseStepExecutor):
    def execute(
        self,
        step_id: str,
        params: Dict[str, Any],
        input_files: List[str],
    ) -> StepResult:
        start_time = datetime.now()
        sample_id = params.get("sample_id", step_id.split("_")[0])

        if not input_files:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message="Samtools sort requires input SAM file",
            )

        sam_input = input_files[0]
        input_path = Path(sam_input)
        base_name = input_path.stem.replace(".sam", "")
        sorted_bam = self.work_dir / f"{base_name}.sorted.bam"

        cmd = [
            settings.tools.samtools,
            "sort",
            "-@", "8",
            "-m", "2G",
            "-o", str(sorted_bam),
            "-O", "bam",
            sam_input,
        ]

        try:
            returncode, stdout, stderr = self._run_command(cmd)

            if returncode != 0:
                raise StepExecutionError(
                    f"Samtools sort failed with exit code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not self._check_files_exist([str(sorted_bam)]):
                raise StepExecutionError(
                    "Samtools sort did not produce output BAM file",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            metrics = {"sorted_bam_size_mb": sorted_bam.stat().st_size / (1024 * 1024)}

            output_files = [str(sorted_bam)]

            duration = (datetime.now() - start_time).total_seconds()

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=output_files,
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
                duration_seconds=duration,
            )

        except StepExecutionError as e:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message=str(e),
                stdout=e.stdout,
                stderr=e.stderr,
                duration_seconds=(datetime.now() - start_time).total_seconds(),
            )


@register_executor(PipelineStepType.SAMTOOLS_INDEX)
class SamtoolsIndexExecutor(BaseStepExecutor):
    def execute(
        self,
        step_id: str,
        params: Dict[str, Any],
        input_files: List[str],
    ) -> StepResult:
        start_time = datetime.now()

        if not input_files:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message="Samtools index requires input BAM file",
            )

        bam_input = input_files[0]
        bai_output = f"{bam_input}.bai"

        cmd = [
            settings.tools.samtools,
            "index",
            "-@", "4",
            bam_input,
        ]

        try:
            returncode, stdout, stderr = self._run_command(cmd)

            if returncode != 0:
                raise StepExecutionError(
                    f"Samtools index failed with exit code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not self._check_files_exist([bai_output]):
                raise StepExecutionError(
                    "Samtools index did not produce BAI file",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            output_files = [bai_output]

            duration = (datetime.now() - start_time).total_seconds()

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=output_files,
                metrics={"indexed": True},
                stdout=stdout,
                stderr=stderr,
                duration_seconds=duration,
            )

        except StepExecutionError as e:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message=str(e),
                stdout=e.stdout,
                stderr=e.stderr,
                duration_seconds=(datetime.now() - start_time).total_seconds(),
            )


@register_executor(PipelineStepType.MARK_DUPLICATES)
class MarkDuplicatesExecutor(BaseStepExecutor):
    def execute(
        self,
        step_id: str,
        params: Dict[str, Any],
        input_files: List[str],
    ) -> StepResult:
        start_time = datetime.now()
        sample_id = params.get("sample_id", step_id.split("_")[0])

        if not input_files:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message="MarkDuplicates requires input BAM file",
            )

        bam_input = input_files[0]
        input_path = Path(bam_input)
        base_name = input_path.stem.replace(".sorted", "")
        dedup_bam = self.work_dir / f"{base_name}.dedup.bam"
        metrics_file = self.work_dir / f"{base_name}_duplicate_metrics.txt"

        cmd = [
            "java", "-Xmx16G", "-jar", settings.tools.picard,
            "MarkDuplicates",
            f"I={bam_input}",
            f"O={dedup_bam}",
            f"M={metrics_file}",
            "ASSUME_SORTED=true",
            "REMOVE_DUPLICATES=false",
            "CREATE_INDEX=true",
            "VALIDATION_STRINGENCY=LENIENT",
        ]

        try:
            returncode, stdout, stderr = self._run_command(cmd)

            if returncode != 0:
                raise StepExecutionError(
                    f"Picard MarkDuplicates failed with exit code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not self._check_files_exist([str(dedup_bam), str(metrics_file)]):
                raise StepExecutionError(
                    "MarkDuplicates did not produce expected output files",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            metrics = self._parse_duplicate_metrics(metrics_file)

            sample = SampleRepository.get_by_id(sample_id)
            if sample:
                QCMetricRepository.create(sample.id, "mark_duplicates", metrics)

            output_files = [str(dedup_bam), str(metrics_file)]

            duration = (datetime.now() - start_time).total_seconds()

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=output_files,
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
                duration_seconds=duration,
            )

        except StepExecutionError as e:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message=str(e),
                stdout=e.stdout,
                stderr=e.stderr,
                duration_seconds=(datetime.now() - start_time).total_seconds(),
            )

    def _parse_duplicate_metrics(self, metrics_file: Path) -> Dict[str, Any]:
        metrics = {}
        in_metrics_section = False

        with open(metrics_file, "r") as f:
            for line in f:
                line = line.strip()
                if line.startswith("## METRICS CLASS"):
                    in_metrics_section = True
                    continue
                if in_metrics_section and line.startswith("LIBRARY"):
                    continue
                if in_metrics_section and line and not line.startswith("##"):
                    parts = line.split("\t")
                    if len(parts) >= 7:
                        try:
                            metrics["unpaired_reads_examined"] = int(parts[1])
                            metrics["read_pairs_examined"] = int(parts[2])
                            metrics["secondary_or_supplementary_reads"] = int(parts[3])
                            metrics["unmapped_reads"] = int(parts[4])
                            metrics["duplicate_mapped_reads"] = int(parts[5])
                            metrics["duplicate_mapped_read_pairs"] = int(parts[6])
                            metrics["optical_duplicate_read_pairs"] = int(parts[7]) if len(parts) > 7 else 0
                            total_reads = metrics["unpaired_reads_examined"] + 2 * metrics["read_pairs_examined"]
                            duplicate_reads = metrics["duplicate_mapped_reads"] + 2 * metrics["duplicate_mapped_read_pairs"]
                            if total_reads > 0:
                                metrics["duplication_rate"] = duplicate_reads / total_reads
                        except (ValueError, IndexError):
                            pass
                    break

        return metrics


@register_executor(PipelineStepType.BASE_RECALIBRATOR)
class BaseRecalibratorExecutor(BaseStepExecutor):
    def execute(
        self,
        step_id: str,
        params: Dict[str, Any],
        input_files: List[str],
    ) -> StepResult:
        start_time = datetime.now()
        sample_id = params.get("sample_id", step_id.split("_")[0])
        chromosome = params.get("chromosome")

        if not input_files:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message="BaseRecalibrator requires input BAM file",
            )

        bam_input = input_files[0]
        input_path = Path(bam_input)
        base_name = input_path.stem.replace(".dedup", "")

        output_suffix = f"_{chromosome}" if chromosome else ""
        recal_table = self.work_dir / f"{base_name}{output_suffix}_recal_data.table"

        cmd = [
            settings.tools.gatk,
            "--java-options", "-Xmx16G",
            "BaseRecalibrator",
            "-R", settings.reference.hg38_fasta,
            "-I", bam_input,
            "--known-sites", settings.reference.known_sites_snp,
            "--known-sites", settings.reference.known_sites_indel,
            "--known-sites", settings.reference.known_sites_1000g,
            "-O", str(recal_table),
        ]

        if chromosome:
            cmd.extend(["-L", chromosome])

        try:
            returncode, stdout, stderr = self._run_command(cmd)

            if returncode != 0:
                raise StepExecutionError(
                    f"GATK BaseRecalibrator failed with exit code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not self._check_files_exist([str(recal_table)]):
                raise StepExecutionError(
                    "BaseRecalibrator did not produce recalibration table",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            output_files = [str(recal_table)]

            duration = (datetime.now() - start_time).total_seconds()

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=output_files,
                metrics={"recalibration_generated": True},
                stdout=stdout,
                stderr=stderr,
                duration_seconds=duration,
            )

        except StepExecutionError as e:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message=str(e),
                stdout=e.stdout,
                stderr=e.stderr,
                duration_seconds=(datetime.now() - start_time).total_seconds(),
            )


@register_executor(PipelineStepType.APPLY_BQSR)
class ApplyBQSRExecutor(BaseStepExecutor):
    def execute(
        self,
        step_id: str,
        params: Dict[str, Any],
        input_files: List[str],
    ) -> StepResult:
        start_time = datetime.now()
        sample_id = params.get("sample_id", step_id.split("_")[0])
        chromosome = params.get("chromosome")

        if len(input_files) < 2:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message="ApplyBQSR requires BAM and recalibration table",
            )

        bam_input = input_files[0]
        recal_table = input_files[1]

        input_path = Path(bam_input)
        base_name = input_path.stem.replace(".dedup", "")

        output_suffix = f"_{chromosome}" if chromosome else ""
        recal_bam = self.work_dir / f"{base_name}{output_suffix}.recal.bam"
        recal_bai = f"{recal_bam}.bai"

        cmd = [
            settings.tools.gatk,
            "--java-options", "-Xmx16G",
            "ApplyBQSR",
            "-R", settings.reference.hg38_fasta,
            "-I", bam_input,
            "--bqsr-recal-file", recal_table,
            "-O", str(recal_bam),
            "--create-output-bam-index", "true",
        ]

        if chromosome:
            cmd.extend(["-L", chromosome])

        try:
            returncode, stdout, stderr = self._run_command(cmd)

            if returncode != 0:
                raise StepExecutionError(
                    f"GATK ApplyBQSR failed with exit code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not self._check_files_exist([str(recal_bam), recal_bai]):
                raise StepExecutionError(
                    "ApplyBQSR did not produce expected output files",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            metrics = self._parse_alignment_metrics(recal_bam, sample_id)

            output_files = [str(recal_bam), recal_bai]

            duration = (datetime.now() - start_time).total_seconds()

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=output_files,
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
                duration_seconds=duration,
            )

        except StepExecutionError as e:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message=str(e),
                stdout=e.stdout,
                stderr=e.stderr,
                duration_seconds=(datetime.now() - start_time).total_seconds(),
            )

    def _parse_alignment_metrics(self, bam_file: Path, sample_id: str) -> Dict[str, Any]:
        metrics = {}

        flagstat_cmd = [
            settings.tools.samtools,
            "flagstat",
            str(bam_file),
        ]

        try:
            returncode, stdout, stderr = self._run_command(flagstat_cmd)
            if returncode == 0:
                for line in stdout.split("\n"):
                    line = line.strip()
                    if "in total" in line:
                        match = re.search(r"(\d+) \+ (\d+) in total", line)
                        if match:
                            metrics["total_reads"] = int(match.group(1)) + int(match.group(2))
                    elif "mapped" in line and "%" in line:
                        match = re.search(r"(\d+) \+ (\d+) mapped \(([0-9.]+)%", line)
                        if match:
                            metrics["mapped_reads"] = int(match.group(1))
                            metrics["mapping_rate"] = float(match.group(3)) / 100
                    elif "properly paired" in line and "%" in line:
                        match = re.search(r"(\d+) \+ (\d+) properly paired \(([0-9.]+)%", line)
                        if match:
                            metrics["properly_paired"] = int(match.group(1))
                            metrics["proper_pair_rate"] = float(match.group(3)) / 100
                    elif "with itself and mate mapped" in line:
                        match = re.search(r"(\d+) \+ (\d+) with itself and mate mapped", line)
                        if match:
                            metrics["paired_mapped"] = int(match.group(1))
                    elif "singletons" in line and "%" in line:
                        match = re.search(r"(\d+) \+ (\d+) singletons \(([0-9.]+)%", line)
                        if match:
                            metrics["singletons"] = int(match.group(1))
                            metrics["singleton_rate"] = float(match.group(3)) / 100
        except Exception as e:
            logger.warning(f"Error parsing alignment metrics: {e}")

        idxstats_cmd = [
            settings.tools.samtools,
            "idxstats",
            str(bam_file),
        ]

        try:
            returncode, stdout, stderr = self._run_command(idxstats_cmd)
            if returncode == 0:
                total_mapped = 0
                for line in stdout.split("\n"):
                    parts = line.strip().split("\t")
                    if len(parts) == 4 and parts[0] != "*":
                        try:
                            total_mapped += int(parts[2])
                        except ValueError:
                            pass
                if total_mapped > 0 and metrics.get("total_reads", 0) > 0:
                    metrics["mapped_reads"] = total_mapped
                    metrics["mapping_rate"] = total_mapped / metrics["total_reads"]
        except Exception as e:
            logger.warning(f"Error parsing idxstats: {e}")

        sample = SampleRepository.get_by_id(sample_id)
        if sample:
            QCMetricRepository.create(sample.id, "alignment", metrics)
            current_qc = sample.qc_metrics or {}
            current_qc.update(metrics)
            SampleRepository.update_qc_metrics(sample_id, current_qc)

        return metrics
