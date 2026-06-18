import logging
import json
import re
import gzip
from pathlib import Path
from typing import List, Dict, Any
from datetime import datetime

from pipeline.executor import BaseStepExecutor, StepResult, register_executor, StepExecutionError
from config.settings import settings
from config.pipeline_config import PipelineStepType
from storage.repository import SampleRepository, VariantRepository, QCMetricRepository

logger = logging.getLogger(__name__)


@register_executor(PipelineStepType.HAPLOTYPE_CALLER)
class HaplotypeCallerExecutor(BaseStepExecutor):
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
                error_message="HaplotypeCaller requires input BAM file",
            )

        bam_input = input_files[0]
        input_path = Path(bam_input)
        base_name = input_path.stem.replace(".recal", "")

        output_suffix = f"_{chromosome}" if chromosome else ""
        gvcf_output = self.work_dir / f"{base_name}{output_suffix}.g.vcf.gz"
        gvcf_index = f"{gvcf_output}.tbi"

        cmd = [
            settings.tools.gatk,
            "--java-options", "-Xmx16G",
            "HaplotypeCaller",
            "-R", settings.reference.hg38_fasta,
            "-I", bam_input,
            "-O", str(gvcf_output),
            "-ERC", "GVCF",
            "--sample-ploidy", "2",
            "--dont-use-soft-clipped-bases",
            "--standard-min-confidence-threshold-for-calling", "10",
        ]

        if chromosome:
            cmd.extend(["-L", chromosome])

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=36000)

            if returncode != 0:
                raise StepExecutionError(
                    f"GATK HaplotypeCaller failed with exit code {returncode}",
                    return_code=returncode,
                    stdout=stdout[:1000],
                    stderr=stderr,
                )

            if not self._check_files_exist([str(gvcf_output), gvcf_index]):
                raise StepExecutionError(
                    "HaplotypeCaller did not produce expected output files",
                    return_code=returncode,
                    stdout=stdout[:1000],
                    stderr=stderr,
                )

            metrics = self._parse_variant_metrics(gvcf_output, sample_id, "haplotype_caller")

            output_files = [str(gvcf_output), gvcf_index]

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

    def _parse_variant_metrics(
        self,
        vcf_file: Path,
        sample_id: str,
        caller: str,
    ) -> Dict[str, Any]:
        metrics = {
            f"{caller}_total_variants": 0,
            f"{caller}_snvs": 0,
            f"{caller}_indels": 0,
            f"{caller}_insertions": 0,
            f"{caller}_deletions": 0,
            f"{caller}_heterozygous": 0,
            f"{caller}_homozygous": 0,
            f"{caller}_ti": 0,
            f"{caller}_tv": 0,
        }

        try:
            with gzip.open(vcf_file, "rt") if str(vcf_file).endswith(".gz") else open(vcf_file, "r") as f:
                for line in f:
                    if line.startswith("#"):
                        continue

                    parts = line.strip().split("\t")
                    if len(parts) < 8:
                        continue

                    ref = parts[3]
                    alt = parts[4]
                    filters = parts[6]

                    if filters not in ["PASS", ".", ""]:
                        continue

                    metrics[f"{caller}_total_variants"] += 1

                    if len(ref) == 1 and len(alt) == 1:
                        metrics[f"{caller}_snvs"] += 1
                        if self._is_transition(ref, alt):
                            metrics[f"{caller}_ti"] += 1
                        else:
                            metrics[f"{caller}_tv"] += 1
                    else:
                        metrics[f"{caller}_indels"] += 1
                        if len(alt) > len(ref):
                            metrics[f"{caller}_insertions"] += 1
                        else:
                            metrics[f"{caller}_deletions"] += 1

                    if len(parts) >= 10:
                        gt = parts[9].split(":")[0]
                        if gt in ["0/1", "0|1", "1/0", "1|0"]:
                            metrics[f"{caller}_heterozygous"] += 1
                        elif gt in ["1/1", "1|1"]:
                            metrics[f"{caller}_homozygous"] += 1

            if metrics[f"{caller}_tv"] > 0:
                metrics[f"{caller}_ti_tv_ratio"] = metrics[f"{caller}_ti"] / metrics[f"{caller}_tv"]
            else:
                metrics[f"{caller}_ti_tv_ratio"] = 0

            if metrics[f"{caller}_homozygous"] > 0:
                metrics[f"{caller}_het_hom_ratio"] = metrics[f"{caller}_heterozygous"] / metrics[f"{caller}_homozygous"]
            else:
                metrics[f"{caller}_het_hom_ratio"] = 0

            sample = SampleRepository.get_by_id(sample_id)
            if sample and caller == "haplotype_caller":
                QCMetricRepository.create(sample.id, "variant_calling", metrics)
                current_qc = sample.qc_metrics or {}
                current_qc.update(metrics)
                SampleRepository.update_qc_metrics(sample_id, current_qc)

        except Exception as e:
            logger.warning(f"Error parsing variant metrics: {e}")

        return metrics

    def _is_transition(self, ref: str, alt: str) -> bool:
        transitions = {("A", "G"), ("G", "A"), ("C", "T"), ("T", "C")}
        return (ref.upper(), alt.upper()) in transitions


@register_executor(PipelineStepType.GENOTYPE_GVCFS)
class GenotypeGVCFsExecutor(BaseStepExecutor):
    def execute(
        self,
        step_id: str,
        params: Dict[str, Any],
        input_files: List[str],
    ) -> StepResult:
        start_time = datetime.now()
        cohort_id = params.get("cohort_id", step_id.split("_")[0])

        if not input_files:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message="GenotypeGVCFs requires input gVCF files",
            )

        vcf_output = self.work_dir / f"{cohort_id}_joint.vcf.gz"
        vcf_index = f"{vcf_output}.tbi"

        cmd = [
            settings.tools.gatk,
            "--java-options", "-Xmx24G",
            "GenotypeGVCFs",
            "-R", settings.reference.hg38_fasta,
            "-O", str(vcf_output),
        ]

        for gvcf in input_files:
            cmd.extend(["--variant", gvcf])

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=72000)

            if returncode != 0:
                raise StepExecutionError(
                    f"GATK GenotypeGVCFs failed with exit code {returncode}",
                    return_code=returncode,
                    stdout=stdout[:1000],
                    stderr=stderr,
                )

            if not self._check_files_exist([str(vcf_output), vcf_index]):
                raise StepExecutionError(
                    "GenotypeGVCFs did not produce expected output files",
                    return_code=returncode,
                    stdout=stdout[:1000],
                    stderr=stderr,
                )

            metrics = {
                "joint_genotyping_complete": True,
                "input_gvcfs": len(input_files),
            }

            output_files = [str(vcf_output), vcf_index]

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


@register_executor(PipelineStepType.VARDICT)
class VarDictExecutor(BaseStepExecutor):
    def execute(
        self,
        step_id: str,
        params: Dict[str, Any],
        input_files: List[str],
    ) -> StepResult:
        start_time = datetime.now()
        sample_id = params.get("sample_id", step_id.split("_")[0])
        chromosome = params.get("chromosome")
        af_threshold = params.get("af_threshold", 0.01)

        if not input_files:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message="VarDict requires input BAM file",
            )

        bam_input = input_files[0]
        input_path = Path(bam_input)
        base_name = input_path.stem.replace(".recal", "")

        output_suffix = f"_{chromosome}" if chromosome else ""
        vcf_output = self.work_dir / f"{base_name}{output_suffix}_vardict.vcf"
        vcf_gz = f"{vcf_output}.gz"
        vcf_index = f"{vcf_gz}.tbi"

        bed_file = params.get("bed_file")
        target_region = chromosome if chromosome else settings.reference.hg38_fasta.replace(".fasta", ".bed")

        cmd = [
            settings.tools.vardict,
            "-G", settings.reference.hg38_fasta,
            "-f", str(af_threshold),
            "-N", sample_id,
            "-b", bam_input,
            "-c", "1",
            "-S", "2",
            "-E", "3",
            "-g", "4",
            "-z", "1",
            "-F", "0x500",
        ]

        if bed_file:
            cmd.extend([target_region])
        elif chromosome:
            cmd.extend([chromosome])

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=36000)

            if returncode != 0:
                raise StepExecutionError(
                    f"VarDict failed with exit code {returncode}",
                    return_code=returncode,
                    stdout=stdout[:1000],
                    stderr=stderr,
                )

            with open(vcf_output, "w") as f:
                f.write(stdout)

            bgzip_cmd = ["bgzip", "-c", str(vcf_output)]
            bgzip_rc, bgzip_stdout, bgzip_stderr = self._run_command(bgzip_cmd)

            if bgzip_rc == 0:
                with open(vcf_gz, "wb") as f:
                    f.write(bgzip_stdout.encode("latin-1"))

                tabix_cmd = ["tabix", "-p", "vcf", vcf_gz]
                tabix_rc, _, tabix_stderr = self._run_command(tabix_cmd)

                if tabix_rc != 0:
                    logger.warning(f"Tabix indexing failed: {tabix_stderr}")

            metrics = self._parse_variant_metrics(Path(vcf_output), sample_id, "vardict")

            output_files = [vcf_gz]
            if Path(vcf_index).exists():
                output_files.append(vcf_index)

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
