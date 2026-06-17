import re
import logging
import gzip
from typing import List, Dict, Any, Tuple
from pathlib import Path

from pipeline.executor import BaseStepExecutor, StepResult, StepExecutionError, register_executor
from config.pipeline_config import PipelineStepType
from config.settings import settings

logger = logging.getLogger(__name__)


@register_executor(PipelineStepType.HAPLOTYPE_CALLER)
class HaplotypeCallerExecutor(BaseStepExecutor):
    """GATK HaplotypeCaller executor for single-sample gVCF generation."""

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id")
        chromosome = params.get("chromosome")

        if not input_files:
            raise StepExecutionError("No input BAM file for HaplotypeCaller")

        input_bam = input_files[0]
        output_gvcf = str(self.work_dir / f"{sample_id}.g.vcf.gz")
        output_tbi = output_gvcf + ".tbi"
        reference = params.get("reference", settings.reference.hg38_fasta)

        if chromosome:
            output_gvcf = str(self.work_dir / f"{sample_id}_{chromosome}.g.vcf.gz")
            output_tbi = output_gvcf + ".tbi"

        cmd = [
            settings.tools.gatk,
            "HaplotypeCaller",
            "-R", reference,
            "-I", input_bam,
            "-O", output_gvcf,
            "-ERC", "GVCF",
            "--emit-ref-confidence", "GVCF",
            "-ploidy", str(params.get("ploidy", 2)),
            "--sample-ploidy", str(params.get("ploidy", 2)),
        ]

        if chromosome:
            cmd.extend(["-L", chromosome])

        if params.get("intervals"):
            cmd.extend(["-L", params.get("intervals")])

        dbsnp = params.get("dbsnp", settings.reference.known_sites_snp)
        if dbsnp:
            cmd.extend(["--dbsnp", dbsnp])

        cmd.extend([
            "--min-base-quality-score", str(params.get("min_base_quality", 10)),
            "--min-pruning", str(params.get("min_pruning", 2)),
            "--standard-min-confidence-threshold-for-calling", str(params.get("call_confidence", 10)),
        ])

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=params.get("timeout", 28800))

            if returncode != 0:
                raise StepExecutionError(
                    f"GATK HaplotypeCaller failed with code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not self._check_files_exist([output_gvcf, output_tbi]):
                raise StepExecutionError("HaplotypeCaller output files missing")

            metrics = self._parse_vcf_metrics(output_gvcf, sample_id)
            metrics_file = self._save_metrics(step_id, metrics)

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=[output_gvcf, output_tbi, metrics_file],
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
            )

        except StepExecutionError:
            raise
        except Exception as e:
            raise StepExecutionError(f"HaplotypeCaller execution error: {e}")

    def _parse_vcf_metrics(self, vcf_file: str, sample_id: str) -> Dict[str, Any]:
        """Parse VCF file for basic variant metrics."""
        metrics = {
            "sample_id": sample_id,
            "total_variants": 0,
            "snps": 0,
            "indels": 0,
            "insertions": 0,
            "deletions": 0,
            "transitions": 0,
            "transversions": 0,
            "ti_tv_ratio": 0.0,
            "heterozygous": 0,
            "homozygous": 0,
            "het_hom_ratio": 0.0,
        }

        try:
            with gzip.open(vcf_file, "rt") as f:
                for line in f:
                    if line.startswith("#"):
                        continue

                    parts = line.strip().split("\t")
                    if len(parts) < 8:
                        continue

                    ref = parts[3]
                    alt = parts[4]
                    metrics["total_variants"] += 1

                    if len(ref) == 1 and len(alt) == 1:
                        metrics["snps"] += 1
                        if self._is_transition(ref, alt):
                            metrics["transitions"] += 1
                        else:
                            metrics["transversions"] += 1
                    else:
                        metrics["indels"] += 1
                        if len(alt) > len(ref):
                            metrics["insertions"] += 1
                        else:
                            metrics["deletions"] += 1

                    if len(parts) >= 10:
                        gt = parts[9].split(":")[0]
                        if gt in ("0/1", "0|1", "1/0", "1|0"):
                            metrics["heterozygous"] += 1
                        elif gt in ("1/1", "1|1"):
                            metrics["homozygous"] += 1

            if metrics["transversions"] > 0:
                metrics["ti_tv_ratio"] = metrics["transitions"] / metrics["transversions"]
            if metrics["homozygous"] > 0:
                metrics["het_hom_ratio"] = metrics["heterozygous"] / metrics["homozygous"]

        except Exception as e:
            logger.warning(f"Failed to parse VCF metrics: {e}")

        return metrics

    def _is_transition(self, ref: str, alt: str) -> bool:
        """Check if a SNP is a transition (purine-purine or pyrimidine-pyrimidine)."""
        transitions = {("A", "G"), ("G", "A"), ("C", "T"), ("T", "C")}
        return (ref.upper(), alt.upper()) in transitions


@register_executor(PipelineStepType.GENOTYPE_GVCFS)
class GenotypeGVCFsExecutor(BaseStepExecutor):
    """GATK GenotypeGVCFs executor for multi-sample joint genotyping."""

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        cohort_id = params.get("cohort_id") or params.get("sample_id")

        if not input_files:
            raise StepExecutionError("No input gVCF files for GenotypeGVCFs")

        output_vcf = str(self.work_dir / f"{cohort_id}_joint.vcf.gz")
        output_tbi = output_vcf + ".tbi"
        reference = params.get("reference", settings.reference.hg38_fasta)

        variant_inputs = []
        for gvcf in input_files:
            variant_inputs.extend(["--variant", gvcf])

        cmd = [
            settings.tools.gatk,
            "GenotypeGVCFs",
            "-R", reference,
            "-O", output_vcf,
        ]
        cmd.extend(variant_inputs)

        dbsnp = params.get("dbsnp", settings.reference.known_sites_snp)
        if dbsnp:
            cmd.extend(["--dbsnp", dbsnp])

        if params.get("intervals"):
            cmd.extend(["-L", params.get("intervals")])

        cmd.extend([
            "--standard-min-confidence-threshold-for-calling", str(params.get("call_confidence", 10)),
            "--include-non-variant-sites", str(params.get("include_non_variant", "false")).lower(),
        ])

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=params.get("timeout", 43200))

            if returncode != 0:
                raise StepExecutionError(
                    f"GATK GenotypeGVCFs failed with code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not self._check_files_exist([output_vcf, output_tbi]):
                raise StepExecutionError("GenotypeGVCFs output files missing")

            metrics = self._parse_vcf_metrics(output_vcf, cohort_id)
            metrics["sample_count"] = len(input_files)
            metrics_file = self._save_metrics(step_id, metrics)

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=[output_vcf, output_tbi, metrics_file],
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
            )

        except StepExecutionError:
            raise
        except Exception as e:
            raise StepExecutionError(f"GenotypeGVCFs execution error: {e}")

    def _parse_vcf_metrics(self, vcf_file: str, cohort_id: str) -> Dict[str, Any]:
        """Parse joint VCF for cohort-level metrics."""
        metrics = {
            "cohort_id": cohort_id,
            "total_variants": 0,
            "snps": 0,
            "indels": 0,
            "transitions": 0,
            "transversions": 0,
            "ti_tv_ratio": 0.0,
            "multiallelic": 0,
            "singletons": 0,
        }

        try:
            sample_names = []
            with gzip.open(vcf_file, "rt") as f:
                for line in f:
                    if line.startswith("#CHROM"):
                        sample_names = line.strip().split("\t")[9:]
                        continue
                    if line.startswith("#"):
                        continue

                    parts = line.strip().split("\t")
                    if len(parts) < 8:
                        continue

                    ref = parts[3]
                    alt = parts[4]
                    metrics["total_variants"] += 1

                    if "," in alt:
                        metrics["multiallelic"] += 1

                    if len(ref) == 1 and all(len(a) == 1 for a in alt.split(",")):
                        metrics["snps"] += 1
                        for a in alt.split(","):
                            if self._is_transition(ref, a):
                                metrics["transitions"] += 1
                            else:
                                metrics["transversions"] += 1
                    else:
                        metrics["indels"] += 1

                    if len(parts) > 9:
                        ac = 0
                        an = 0
                        for sample_gt in parts[9:]:
                            gt = sample_gt.split(":")[0]
                            alt_count = gt.count("1") + gt.count("2")
                            ac += alt_count
                            an += 2 if "/" in gt or "|" in gt else 0

                        if ac == 1:
                            metrics["singletons"] += 1

            if metrics["transversions"] > 0:
                metrics["ti_tv_ratio"] = metrics["transitions"] / metrics["transversions"]
            metrics["samples"] = sample_names

        except Exception as e:
            logger.warning(f"Failed to parse joint VCF metrics: {e}")

        return metrics

    def _is_transition(self, ref: str, alt: str) -> bool:
        transitions = {("A", "G"), ("G", "A"), ("C", "T"), ("T", "C")}
        return (ref.upper(), alt.upper()) in transitions


@register_executor(PipelineStepType.VARDICT)
class VarDictExecutor(BaseStepExecutor):
    """VarDict executor for sensitive low-frequency variant calling."""

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id")
        chromosome = params.get("chromosome")

        if not input_files:
            raise StepExecutionError("No input BAM file for VarDict")

        input_bam = input_files[0]
        output_vcf = str(self.work_dir / f"{sample_id}_vardict.vcf.gz")
        reference = params.get("reference", settings.reference.hg38_fasta)
        bed_file = params.get("bed_file")

        if chromosome:
            output_vcf = str(self.work_dir / f"{sample_id}_vardict_{chromosome}.vcf.gz")

        sample_name = params.get("sample_name", sample_id)
        af_threshold = params.get("allele_frequency_threshold", 0.01)

        regions = []
        if chromosome:
            regions = [chromosome]
        elif bed_file:
            regions = [bed_file]
        else:
            regions = [f"chr{i}" for i in range(1, 23)] + ["chrX", "chrY"]

        all_variants = []
        for region in regions:
            cmd = [
                settings.tools.vardict,
                "-G", reference,
                "-f", str(af_threshold),
                "-N", sample_name,
                "-b", input_bam,
                "-z", "1",
                "-c", "1",
                "-S", "2",
                "-E", "3",
                "-g", "4",
                region,
            ]

            try:
                returncode, stdout, stderr = self._run_command(cmd, timeout=params.get("timeout", 14400))

                if returncode != 0:
                    logger.warning(f"VarDict failed for region {region}: {stderr}")
                    continue

                lines = stdout.strip().split("\n")
                if lines:
                    all_variants.extend(lines)

            except Exception as e:
                logger.warning(f"VarDict execution error for region {region}: {e}")

        if not all_variants:
            logger.warning("No variants called by VarDict")

        vcf_header = self._get_vcf_header(reference, sample_name)
        self._write_vcf(output_vcf, vcf_header, all_variants)

        metrics = self._parse_vardict_metrics(output_vcf, sample_id)
        metrics_file = self._save_metrics(step_id, metrics)

        return StepResult(
            success=True,
            step_id=step_id,
            output_files=[output_vcf, metrics_file],
            metrics=metrics,
        )

    def _get_vcf_header(self, reference: str, sample_name: str) -> List[str]:
        """Generate VCF header for VarDict output."""
        return [
            "##fileformat=VCFv4.2",
            f"##reference={reference}",
            '##INFO=<ID=DP,Number=1,Type=Integer,Description="Total read depth">',
            '##INFO=<ID=VD,Number=1,Type=Integer,Description="Variant read depth">',
            '##INFO=<ID=AF,Number=1,Type=Float,Description="Variant allele frequency">',
            '##INFO=<ID=BIAS,Number=1,Type=String,Description="Strand bias">',
            '##INFO=<ID=PMEAN,Number=1,Type=Float,Description="Mean position in reads">',
            '##INFO=<ID=QUAL,Number=1,Type=Float,Description="Mean base quality">',
            '##FILTER=<ID=LowAF,Description="Allele frequency below threshold">',
            '##FILTER=<ID=LowDepth,Description="Total depth below threshold">',
            '##FORMAT=<ID=GT,Number=1,Type=String,Description="Genotype">',
            '##FORMAT=<ID=DP,Number=1,Type=Integer,Description="Read depth">',
            '##FORMAT=<ID=AD,Number=R,Type=Integer,Description="Allelic depths">',
            '##FORMAT=<ID=AF,Number=1,Type=Float,Description="Allele frequency">',
            f"#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\t{sample_name}",
        ]

    def _write_vcf(self, output_file: str, header: List[str], variants: List[str]) -> None:
        """Write VCF file from VarDict output."""
        import subprocess

        with open(output_file.replace(".gz", ""), "w") as f:
            f.write("\n".join(header) + "\n")
            for variant in variants:
                if not variant.startswith("#"):
                    f.write(variant + "\n")

        subprocess.run(
            ["bgzip", "-f", output_file.replace(".gz", "")],
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["tabix", "-p", "vcf", output_file],
            check=True,
            capture_output=True,
        )

    def _parse_vardict_metrics(self, vcf_file: str, sample_id: str) -> Dict[str, Any]:
        """Parse VarDict VCF for metrics."""
        metrics = {
            "sample_id": sample_id,
            "total_variants": 0,
            "snps": 0,
            "indels": 0,
            "low_frequency_variants": 0,
            "mean_allele_frequency": 0.0,
            "mean_depth": 0,
        }

        af_values = []
        dp_values = []

        try:
            with gzip.open(vcf_file, "rt") as f:
                for line in f:
                    if line.startswith("#"):
                        continue

                    parts = line.strip().split("\t")
                    if len(parts) < 8:
                        continue

                    metrics["total_variants"] += 1
                    ref, alt = parts[3], parts[4]

                    if len(ref) == 1 and len(alt) == 1:
                        metrics["snps"] += 1
                    else:
                        metrics["indels"] += 1

                    info = parts[7]
                    info_dict = dict(item.split("=") for item in info.split(";") if "=" in item)

                    af = float(info_dict.get("AF", 0))
                    dp = int(info_dict.get("DP", 0))

                    af_values.append(af)
                    dp_values.append(dp)

                    if af < 0.05:
                        metrics["low_frequency_variants"] += 1

            if af_values:
                metrics["mean_allele_frequency"] = sum(af_values) / len(af_values)
            if dp_values:
                metrics["mean_depth"] = sum(dp_values) / len(dp_values)

        except Exception as e:
            logger.warning(f"Failed to parse VarDict metrics: {e}")

        return metrics
