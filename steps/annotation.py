import logging
import json
import gzip
import re
from pathlib import Path
from typing import List, Dict, Any, Optional, Tuple
from datetime import datetime

from pipeline.executor import BaseStepExecutor, StepResult, register_executor, StepExecutionError
from config.settings import settings
from config.pipeline_config import PipelineStepType
from storage.repository import SampleRepository, VariantRepository
from db.models import ACMGClassification

logger = logging.getLogger(__name__)


@register_executor(PipelineStepType.VEP_ANNOTATION)
class VEPAnnotationExecutor(BaseStepExecutor):
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
                error_message="VEP annotation requires input VCF file",
            )

        vcf_input = input_files[0]
        input_path = Path(vcf_input)
        base_name = input_path.stem.replace(".g.vcf", "").replace(".vcf", "").replace(".gz", "")
        vep_vcf = self.work_dir / f"{base_name}_vep.vcf.gz"
        vep_summary = self.work_dir / f"{base_name}_vep_summary.html"
        vep_stats = self.work_dir / f"{base_name}_vep_stats.html"

        cmd = [
            settings.tools.vep,
            "--cache",
            "--dir_cache", settings.annotation.vep_cache_dir,
            "--assembly", settings.annotation.vep_assembly,
            "--offline",
            "--fork", "8",
            "--buffer_size", "5000",
            "--input_file", vcf_input,
            "--output_file", str(vep_vcf),
            "--vcf",
            "--compress_output", "bgzip",
            "--force_overwrite",
            "--symbol",
            "--hgvs",
            "--protein",
            "--ccds",
            "--canonical",
            "--mane",
            "--biotype",
            "--tsl",
            "--appris",
            "--gene_phenotype",
            "--ccds",
            "--uniprot",
            "--pubmed",
            "--variant_class",
            "--sift", "b",
            "--polyphen", "b",
            "--check_existing",
            "--exclude_predicted",
            "--humdiv",
            "--everything",
            "--stats_file", str(vep_stats),
            "--summary_stats",
        ]

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=72000)

            if returncode != 0:
                raise StepExecutionError(
                    f"VEP annotation failed with exit code {returncode}",
                    return_code=returncode,
                    stdout=stdout[:1000],
                    stderr=stderr,
                )

            tabix_cmd = ["tabix", "-p", "vcf", str(vep_vcf)]
            tabix_rc, _, tabix_stderr = self._run_command(tabix_cmd)
            if tabix_rc != 0:
                logger.warning(f"Tabix indexing VEP output failed: {tabix_stderr}")

            if not self._check_files_exist([str(vep_vcf)]):
                raise StepExecutionError(
                    "VEP did not produce expected output VCF",
                    return_code=returncode,
                    stdout=stdout[:1000],
                    stderr=stderr,
                )

            metrics = self._parse_vep_metrics(vep_vcf)

            output_files = [str(vep_vcf), str(vep_stats)]
            vep_index = f"{vep_vcf}.tbi"
            if Path(vep_index).exists():
                output_files.append(vep_index)
            if vep_summary.exists():
                output_files.append(str(vep_summary))

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

    def _parse_vep_metrics(self, vep_vcf: Path) -> Dict[str, Any]:
        metrics = {
            "total_variants_annotated": 0,
            "by_consequence": {},
            "by_impact": {"HIGH": 0, "MODERATE": 0, "LOW": 0, "MODIFIER": 0},
            "by_biotype": {},
            "with_hgvs": 0,
            "with_sift": 0,
            "with_polyphen": 0,
        }

        try:
            with gzip.open(vep_vcf, "rt") as f:
                csq_header = None
                for line in f:
                    if line.startswith("##INFO=<ID=CSQ"):
                        match = re.search(r'Format: (\S+)"', line)
                        if match:
                            csq_header = match.group(1).split("|")
                        continue
                    if line.startswith("#"):
                        continue

                    parts = line.strip().split("\t")
                    if len(parts) < 8:
                        continue

                    metrics["total_variants_annotated"] += 1

                    info_field = parts[7]
                    csq_match = re.search(r"CSQ=([^;]+)", info_field)
                    if csq_match and csq_header:
                        csq_values = csq_match.group(1).split(",")[0].split("|")
                        csq_dict = dict(zip(csq_header, csq_values))

                        consequence = csq_dict.get("Consequence", "")
                        for cons in consequence.split("&"):
                            if cons:
                                metrics["by_consequence"][cons] = metrics["by_consequence"].get(cons, 0) + 1

                        impact = csq_dict.get("IMPACT", "")
                        if impact in metrics["by_impact"]:
                            metrics["by_impact"][impact] += 1

                        biotype = csq_dict.get("BIOTYPE", "")
                        if biotype:
                            metrics["by_biotype"][biotype] = metrics["by_biotype"].get(biotype, 0) + 1

                        if csq_dict.get("HGVSc") or csq_dict.get("HGVSp"):
                            metrics["with_hgvs"] += 1
                        if csq_dict.get("SIFT"):
                            metrics["with_sift"] += 1
                        if csq_dict.get("PolyPhen"):
                            metrics["with_polyphen"] += 1

        except Exception as e:
            logger.warning(f"Error parsing VEP metrics: {e}")

        return metrics


@register_executor(PipelineStepType.DBNSFP_ANNOTATION)
class DbNSFPAnnotationExecutor(BaseStepExecutor):
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
                error_message="dbNSFP annotation requires input VCF file",
            )

        vcf_input = input_files[0]
        input_path = Path(vcf_input)
        base_name = input_path.stem.replace("_vep", "").replace(".vcf", "").replace(".gz", "")
        output_vcf = self.work_dir / f"{base_name}_dbnsfp.vcf.gz"

        fields_to_include = [
            "rs_dbSNP",
            "gnomAD_genomes_AF",
            "gnomAD_exomes_AF",
            "1000Gp3_AF",
            "ExAC_AF",
            "CADD_phred",
            "REVEL_score",
            "SIFT_score",
            "SIFT_pred",
            "Polyphen2_HDIV_score",
            "Polyphen2_HDIV_pred",
            "Polyphen2_HVAR_score",
            "Polyphen2_HVAR_pred",
            "LRT_score",
            "LRT_pred",
            "MutationTaster_score",
            "MutationTaster_pred",
            "FATHMM_score",
            "FATHMM_pred",
            "PROVEAN_score",
            "PROVEAN_pred",
            "VEST3_score",
            "MetaSVM_score",
            "MetaSVM_pred",
            "MetaLR_score",
            "MetaLR_pred",
            "M-CAP_score",
            "M-CAP_pred",
            "MVP_score",
            "MPC_score",
            "PrimateAI_score",
            "DEOGEN2_score",
            "BayesDel_addAF_score",
            "BayesDel_noAF_score",
            "ClinPred_score",
            "LIST-S2_score",
            "Aloft_Function_pred",
            "Aloft_Recessive_pred",
            "Aloft_Dominant_pred",
        ]

        cmd = [
            settings.tools.vep,
            "--cache",
            "--dir_cache", settings.annotation.vep_cache_dir,
            "--assembly", settings.annotation.vep_assembly,
            "--offline",
            "--fork", "4",
            "--input_file", vcf_input,
            "--output_file", str(output_vcf),
            "--vcf",
            "--compress_output", "bgzip",
            "--force_overwrite",
            "--plugin",
            f"dbNSFP,{settings.annotation.dbnsfp_db},{','.join(fields_to_include)}",
        ]

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=72000)

            if returncode != 0:
                raise StepExecutionError(
                    f"dbNSFP annotation failed with exit code {returncode}",
                    return_code=returncode,
                    stdout=stdout[:1000],
                    stderr=stderr,
                )

            tabix_cmd = ["tabix", "-p", "vcf", str(output_vcf)]
            tabix_rc, _, tabix_stderr = self._run_command(tabix_cmd)
            if tabix_rc != 0:
                logger.warning(f"Tabix indexing dbNSFP output failed: {tabix_stderr}")

            if not self._check_files_exist([str(output_vcf)]):
                raise StepExecutionError(
                    "dbNSFP annotation did not produce expected output VCF",
                    return_code=returncode,
                    stdout=stdout[:1000],
                    stderr=stderr,
                )

            metrics = {"dbnsfp_annotation_complete": True}

            output_files = [str(output_vcf)]
            output_index = f"{output_vcf}.tbi"
            if Path(output_index).exists():
                output_files.append(output_index)

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


@register_executor(PipelineStepType.CLINVAR_ANNOTATION)
class ClinVarAnnotationExecutor(BaseStepExecutor):
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
                error_message="ClinVar annotation requires input VCF file",
            )

        vcf_input = input_files[0]
        input_path = Path(vcf_input)
        base_name = input_path.stem.replace("_dbnsfp", "").replace(".vcf", "").replace(".gz", "")
        output_vcf = self.work_dir / f"{base_name}_clinvar.vcf.gz"

        cmd = [
            settings.tools.gatk,
            "--java-options", "-Xmx8G",
            "Funcotator",
            "-R", settings.reference.hg38_fasta,
            "-V", vcf_input,
            "-O", str(output_vcf),
            "--ref-version", "hg38",
            "--data-sources-path", "/data/funcotator/",
            "--output-format", "VCF",
        ]

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=36000)

            if returncode != 0:
                logger.info("GATK Funcotator failed, trying bcftools annotate with ClinVar VCF directly")
                bcftools_cmd = [
                    "bcftools",
                    "annotate",
                    "-a", settings.annotation.clinvar_vcf,
                    "-c", "INFO/CLNSIG,INFO/CLNREVSTAT,INFO/CLNDN,INFO/CLNVID,INFO/CLNHGVS",
                    "-o", str(output_vcf),
                    "-O", "z",
                    vcf_input,
                ]
                returncode, stdout, stderr = self._run_command(bcftools_cmd, timeout=36000)

                if returncode != 0:
                    raise StepExecutionError(
                        f"ClinVar annotation failed with exit code {returncode}",
                        return_code=returncode,
                        stdout=stdout[:1000],
                        stderr=stderr,
                    )

            tabix_cmd = ["tabix", "-p", "vcf", str(output_vcf)]
            tabix_rc, _, tabix_stderr = self._run_command(tabix_cmd)
            if tabix_rc != 0:
                logger.warning(f"Tabix indexing ClinVar output failed: {tabix_stderr}")

            if not self._check_files_exist([str(output_vcf)]):
                raise StepExecutionError(
                    "ClinVar annotation did not produce expected output VCF",
                    return_code=returncode,
                    stdout=stdout[:1000],
                    stderr=stderr,
                )

            metrics = self._parse_clinvar_metrics(output_vcf)

            output_files = [str(output_vcf)]
            output_index = f"{output_vcf}.tbi"
            if Path(output_index).exists():
                output_files.append(output_index)

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

    def _parse_clinvar_metrics(self, vcf_file: Path) -> Dict[str, Any]:
        metrics = {
            "total_variants": 0,
            "with_clinvar": 0,
            "by_clinvar_significance": {
                "Pathogenic": 0,
                "Likely pathogenic": 0,
                "Uncertain significance": 0,
                "Likely benign": 0,
                "Benign": 0,
                "Other": 0,
            },
            "with_clinvar_vus": 0,
        }

        clinvar_significance_map = {
            "5": "Pathogenic",
            "4": "Likely pathogenic",
            "2": "Benign",
            "3": "Likely benign",
            "1": "Uncertain significance",
            "0": "Other",
            "255": "Other",
        }

        try:
            with gzip.open(vcf_file, "rt") as f:
                for line in f:
                    if line.startswith("#"):
                        continue

                    parts = line.strip().split("\t")
                    if len(parts) < 8:
                        continue

                    metrics["total_variants"] += 1

                    info_field = parts[7]

                    clnsig_match = re.search(r"CLNSIG=([^;]+)", info_field)
                    if clnsig_match:
                        metrics["with_clinvar"] += 1
                        clnsig = clnsig_match.group(1)

                        primary_sig = clnsig.split(",")[0]
                        sig_name = clinvar_significance_map.get(primary_sig, "Other")
                        metrics["by_clinvar_significance"][sig_name] = (
                            metrics["by_clinvar_significance"].get(sig_name, 0) + 1
                        )

                        if primary_sig == "1":
                            metrics["with_clinvar_vus"] += 1

        except Exception as e:
            logger.warning(f"Error parsing ClinVar metrics: {e}")

        return metrics


@register_executor(PipelineStepType.ACMG_CLASSIFICATION)
class ACMGClassificationExecutor(BaseStepExecutor):
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
                error_message="ACMG classification requires input VCF file",
            )

        vcf_input = input_files[0]
        input_path = Path(vcf_input)
        base_name = input_path.stem.replace("_clinvar", "").replace(".vcf", "").replace(".gz", "")
        output_vcf = self.work_dir / f"{base_name}_acmg.vcf.gz"
        classifications_json = self.work_dir / f"{base_name}_acmg_classifications.json"

        try:
            variants_data, acmg_metrics = self._parse_and_classify_variants(vcf_input, sample_id)

            self._write_acmg_vcf(vcf_input, output_vcf, variants_data)

            with open(classifications_json, "w") as f:
                json.dump(
                    {
                        "sample_id": sample_id,
                        "total_variants": len(variants_data),
                        "by_classification": {
                            k.value: v for k, v in acmg_metrics.items()
                        },
                        "variants": variants_data[:100],
                    },
                    f,
                    indent=2,
                )

            sample = SampleRepository.get_by_id(sample_id)
            if sample:
                stored_variants = []
                for var in variants_data:
                    var["sample_id"] = sample.id
                    stored_variants.append(var)

                VariantRepository.bulk_create(sample.id, stored_variants)
                SampleRepository.update_variant_count(sample_id, len(variants_data))

            tabix_cmd = ["tabix", "-p", "vcf", str(output_vcf)]
            tabix_rc, _, tabix_stderr = self._run_command(tabix_cmd)
            if tabix_rc != 0:
                logger.warning(f"Tabix indexing ACMG output failed: {tabix_stderr}")

            metrics = {
                "total_variants": len(variants_data),
                "pathogenic": acmg_metrics.get(ACMGClassification.PATHOGENIC, 0),
                "likely_pathogenic": acmg_metrics.get(ACMGClassification.LIKELY_PATHOGENIC, 0),
                "vus": acmg_metrics.get(ACMGClassification.UNCERTAIN_SIGNIFICANCE, 0),
                "likely_benign": acmg_metrics.get(ACMGClassification.LIKELY_BENIGN, 0),
                "benign": acmg_metrics.get(ACMGClassification.BENIGN, 0),
            }

            output_files = [str(output_vcf), str(classifications_json)]
            output_index = f"{output_vcf}.tbi"
            if Path(output_index).exists():
                output_files.append(output_index)

            duration = (datetime.now() - start_time).total_seconds()

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=output_files,
                metrics=metrics,
                stdout="",
                stderr="",
                duration_seconds=duration,
            )

        except Exception as e:
            logger.exception("ACMG classification failed")
            return StepResult(
                success=False,
                step_id=step_id,
                error_message=str(e),
                stdout="",
                stderr="",
                duration_seconds=(datetime.now() - start_time).total_seconds(),
            )

    def _parse_and_classify_variants(
        self,
        vcf_file: Path,
        sample_id: str,
    ) -> Tuple[List[Dict[str, Any]], Dict[ACMGClassification, int]]:
        variants = []
        classification_counts: Dict[ACMGClassification, int] = {}

        secondary_finding_genes = set(VariantRepository.get_secondary_finding_genes())

        csq_header = None
        dbnsfp_header = None

        try:
            with gzip.open(vcf_file, "rt") as f:
                for line in f:
                    if line.startswith("##INFO=<ID=CSQ"):
                        match = re.search(r'Format: (\S+)"', line)
                        if match:
                            csq_header = match.group(1).split("|")
                        continue
                    if line.startswith("#"):
                        continue

                    parts = line.strip().split("\t")
                    if len(parts) < 10:
                        continue

                    chrom = parts[0]
                    pos = int(parts[1])
                    vid = parts[2]
                    ref = parts[3]
                    alt = parts[4]
                    qual = parts[5]
                    filters = parts[6]
                    info = parts[7]
                    format_field = parts[8]
                    sample_data = parts[9]

                    format_keys = format_field.split(":")
                    sample_values = sample_data.split(":")
                    sample_dict = dict(zip(format_keys, sample_values))

                    csq_dict = {}
                    csq_match = re.search(r"CSQ=([^;]+)", info)
                    if csq_match and csq_header:
                        csq_values = csq_match.group(1).split(",")[0].split("|")
                        csq_dict = dict(zip(csq_header, csq_values))

                    gnomad_af = self._extract_float_field(info, "gnomAD_genomes_AF")
                    thousandg_af = self._extract_float_field(info, "1000Gp3_AF")
                    exac_af = self._extract_float_field(info, "ExAC_AF")

                    cadd_score = self._extract_float_field(info, "CADD_phred")
                    revel_score = self._extract_float_field(info, "REVEL_score")
                    sift_score = self._extract_float_field(info, "SIFT_score")
                    polyphen2_score = self._extract_float_field(info, "Polyphen2_HDIV_score")

                    clinvar_id = self._extract_string_field(info, "CLNVID")
                    clinvar_clinsig = self._extract_string_field(info, "CLNSIG")
                    clinvar_review = self._extract_string_field(info, "CLNREVSTAT")

                    genotype = sample_dict.get("GT", "./.")
                    gq = float(sample_dict.get("GQ", 0)) if sample_dict.get("GQ") else 0
                    dp = int(sample_dict.get("DP", 0)) if sample_dict.get("DP") else 0
                    ad = sample_dict.get("AD", "0,0").split(",")
                    ad_ref = int(ad[0]) if len(ad) > 0 else 0
                    ad_alt = int(ad[1]) if len(ad) > 1 else 0
                    af = ad_alt / (ad_ref + ad_alt) if (ad_ref + ad_alt) > 0 else 0

                    gene = csq_dict.get("SYMBOL", "")
                    transcript = csq_dict.get("Feature", "")
                    hgvsc = csq_dict.get("HGVSc", "")
                    hgvsp = csq_dict.get("HGVSp", "")
                    consequence = csq_dict.get("Consequence", "")
                    impact = csq_dict.get("IMPACT", "")

                    classification, criteria, score = self._classify_acmg(
                        variant_type=self._get_variant_type(ref, alt),
                        consequence=consequence,
                        impact=impact,
                        gnomad_af=gnomad_af,
                        thousandg_af=thousandg_af,
                        exac_af=exac_af,
                        cadd_score=cadd_score,
                        revel_score=revel_score,
                        sift_score=sift_score,
                        polyphen2_score=polyphen2_score,
                        clinvar_clinsig=clinvar_clinsig,
                        genotype=genotype,
                        dp=dp,
                        gq=gq,
                    )

                    if classification not in classification_counts:
                        classification_counts[classification] = 0
                    classification_counts[classification] += 1

                    is_secondary = gene in secondary_finding_genes

                    variant_id = f"{chrom}-{pos}-{ref}-{alt}"

                    variant_data = {
                        "variant_id": variant_id,
                        "chromosome": chrom,
                        "position": pos,
                        "ref": ref,
                        "alt": alt,
                        "variant_type": self._get_variant_type(ref, alt),
                        "genotype": genotype,
                        "genotype_quality": gq,
                        "depth": dp,
                        "allele_depth": ad_alt,
                        "allele_frequency": af,
                        "gene": gene,
                        "transcript": transcript,
                        "hgvsc": hgvsc,
                        "hgvsp": hgvsp,
                        "consequence": consequence,
                        "impact": impact,
                        "gnomad_af": gnomad_af,
                        "thousandg_af": thousandg_af,
                        "exac_af": exac_af,
                        "cadd_score": cadd_score,
                        "revel_score": revel_score,
                        "sift_score": sift_score,
                        "polyphen2_score": polyphen2_score,
                        "clinvar_id": clinvar_id,
                        "clinvar_clinsig": clinvar_clinsig,
                        "clinvar_review_status": clinvar_review,
                        "acmg_classification": classification,
                        "acmg_criteria": criteria,
                        "acmg_score": score,
                        "is_secondary_finding": is_secondary,
                        "is_candidate": classification
                        in [
                            ACMGClassification.PATHOGENIC,
                            ACMGClassification.LIKELY_PATHOGENIC,
                        ],
                    }

                    variants.append(variant_data)

        except Exception as e:
            logger.warning(f"Error parsing VCF for ACMG classification: {e}")

        return variants, classification_counts

    def _classify_acmg(
        self,
        variant_type: str,
        consequence: str,
        impact: str,
        gnomad_af: Optional[float],
        thousandg_af: Optional[float],
        exac_af: Optional[float],
        cadd_score: Optional[float],
        revel_score: Optional[float],
        sift_score: Optional[float],
        polyphen2_score: Optional[float],
        clinvar_clinsig: Optional[str],
        genotype: str,
        dp: int,
        gq: float,
    ) -> Tuple[ACMGClassification, List[str], float]:
        criteria = []
        points = 0.0

        max_af = max(
            gnomad_af or 0,
            thousandg_af or 0,
            exac_af or 0,
        )

        if impact == "HIGH" or any(
            term in consequence
            for term in [
                "frameshift_variant",
                "stop_gained",
                "stop_lost",
                "start_lost",
                "splice_acceptor_variant",
                "splice_donor_variant",
            ]
        ):
            criteria.append("PVS1")
            points += 8

        if clinvar_clinsig == "5":
            criteria.append("PS1")
            points += 4
        elif clinvar_clinsig == "4":
            criteria.append("PP5")
            points += 1

        if max_af > 0.05:
            criteria.append("BA1")
            points -= 8
        elif max_af > 0.01:
            criteria.append("BS1")
            points -= 4

        if max_af < 0.0001 and gnomad_af is not None:
            criteria.append("PM2")
            points += 2

        if cadd_score and cadd_score >= 30:
            criteria.append("PP3")
            points += 1
        elif cadd_score and cadd_score <= 10:
            criteria.append("BP4")
            points -= 1

        if revel_score and revel_score >= 0.75:
            criteria.append("PP3")
            points += 1
        elif revel_score and revel_score <= 0.25:
            criteria.append("BP4")
            points -= 1

        if sift_score and sift_score <= 0.05:
            criteria.append("PP3")
            points += 1
        elif sift_score and sift_score >= 0.95:
            criteria.append("BP4")
            points -= 1

        if polyphen2_score and polyphen2_score >= 0.85:
            criteria.append("PP3")
            points += 1
        elif polyphen2_score and polyphen2_score <= 0.15:
            criteria.append("BP4")
            points -= 1

        if genotype in ["1/1", "1|1"] and impact == "HIGH":
            criteria.append("PM3")
            points += 2

        if dp < 10 or gq < 20:
            criteria.append("BS4")
            points -= 1

        if impact == "MODERATE" and "missense_variant" in consequence:
            criteria.append("PM5")
            points += 2

        if variant_type == "inframe_indel" and impact == "MODERATE":
            criteria.append("PM4")
            points += 2

        criteria = list(dict.fromkeys(criteria))

        if points >= 9:
            classification = ACMGClassification.PATHOGENIC
        elif points >= 5:
            classification = ACMGClassification.LIKELY_PATHOGENIC
        elif points <= -9:
            classification = ACMGClassification.BENIGN
        elif points <= -5:
            classification = ACMGClassification.LIKELY_BENIGN
        else:
            classification = ACMGClassification.UNCERTAIN_SIGNIFICANCE

        return classification, criteria, points

    def _get_variant_type(self, ref: str, alt: str) -> str:
        if len(ref) == 1 and len(alt) == 1:
            return "SNV"
        elif len(ref) == len(alt):
            return "MNP"
        elif len(alt) > len(ref):
            return "insertion"
        else:
            return "deletion"

    def _extract_float_field(self, info: str, field_name: str) -> Optional[float]:
        match = re.search(rf"{field_name}=([^;]+)", info)
        if match:
            try:
                val = match.group(1)
                if val not in ["", ".", "NA"]:
                    return float(val)
            except (ValueError, TypeError):
                pass
        return None

    def _extract_string_field(self, info: str, field_name: str) -> Optional[str]:
        match = re.search(rf"{field_name}=([^;]+)", info)
        if match:
            val = match.group(1)
            if val not in ["", ".", "NA"]:
                return val
        return None

    def _write_acmg_vcf(
        self,
        input_vcf: Path,
        output_vcf: Path,
        variants_data: List[Dict[str, Any]],
    ) -> None:
        variant_map = {}
        for var in variants_data:
            key = f"{var['chromosome']}-{var['position']}-{var['ref']}-{var['alt']}"
            variant_map[key] = var

        with gzip.open(input_vcf, "rt") as fin, gzip.open(output_vcf, "wt") as fout:
            for line in fin:
                if line.startswith("##"):
                    fout.write(line)
                    continue

                if line.startswith("#CHROM"):
                    fout.write('##INFO=<ID=ACMG_CLASS,Number=1,Type=String,Description="ACMG Classification">\n')
                    fout.write('##INFO=<ID=ACMG_CRIT,Number=.,Type=String,Description="ACMG Criteria">\n')
                    fout.write('##INFO=<ID=ACMG_SCORE,Number=1,Type=Float,Description="ACMG Score">\n')
                    fout.write('##INFO=<ID=IS_SEC_FIND,Number=0,Type=Flag,Description="Secondary Finding">\n')
                    fout.write(line)
                    continue

                parts = line.strip().split("\t")
                if len(parts) < 8:
                    fout.write(line)
                    continue

                chrom = parts[0]
                pos = parts[1]
                ref = parts[3]
                alt = parts[4]
                key = f"{chrom}-{pos}-{ref}-{alt}"

                if key in variant_map:
                    var = variant_map[key]
                    info = parts[7]

                    info += f";ACMG_CLASS={var['acmg_classification'].value}"
                    if var["acmg_criteria"]:
                        info += f";ACMG_CRIT={','.join(var['acmg_criteria'])}"
                    info += f";ACMG_SCORE={var['acmg_score']:.1f}"
                    if var["is_secondary_finding"]:
                        info += ";IS_SEC_FIND"

                    parts[7] = info
                    fout.write("\t".join(parts) + "\n")
                else:
                    fout.write(line)
