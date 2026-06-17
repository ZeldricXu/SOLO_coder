import json
import logging
import gzip
from typing import List, Dict, Any, Tuple, Optional
from pathlib import Path
from dataclasses import dataclass, field
from enum import Enum

from pipeline.executor import BaseStepExecutor, StepResult, StepExecutionError, register_executor
from config.pipeline_config import PipelineStepType
from db.models import ACMGClassification
from config.settings import settings

logger = logging.getLogger(__name__)


class ACMGEvidenceType(str, Enum):
    PVS1 = "PVS1"
    PS1 = "PS1"
    PS2 = "PS2"
    PS3 = "PS3"
    PS4 = "PS4"
    PM1 = "PM1"
    PM2 = "PM2"
    PM3 = "PM3"
    PM4 = "PM4"
    PM5 = "PM5"
    PM6 = "PM6"
    PP1 = "PP1"
    PP2 = "PP2"
    PP3 = "PP3"
    PP4 = "PP4"
    PP5 = "PP5"
    BA1 = "BA1"
    BS1 = "BS1"
    BS2 = "BS2"
    BS3 = "BS3"
    BS4 = "BS4"
    BP1 = "BP1"
    BP2 = "BP2"
    BP3 = "BP3"
    BP4 = "BP4"
    BP5 = "BP5"
    BP6 = "BP6"
    BP7 = "BP7"


@dataclass
class ACMGEvidence:
    evidence_type: ACMGEvidenceType
    strength: str = "Moderate"
    description: str = ""
    supporting_data: Dict[str, Any] = field(default_factory=dict)


@dataclass
class VariantAnnotation:
    chromosome: str
    position: int
    ref: str
    alt: str
    variant_id: str = ""

    gene: str = ""
    transcript: str = ""
    hgvsc: str = ""
    hgvsp: str = ""
    consequence: str = ""
    impact: str = ""
    biotype: str = ""
    strand: int = 0

    gnomad_af: Optional[float] = None
    gnomad_af_popmax: Optional[float] = None
    thousandg_af: Optional[float] = None
    exac_af: Optional[float] = None

    cadd_score: Optional[float] = None
    revel_score: Optional[float] = None
    sift_score: Optional[float] = None
    sift_pred: str = ""
    polyphen2_score: Optional[float] = None
    polyphen2_pred: str = ""
    mutationtaster_score: Optional[float] = None
    mutationtaster_pred: str = ""

    clinvar_id: str = ""
    clinvar_clinsig: str = ""
    clinvar_review_status: str = ""
    clinvar_disease: str = ""

    acmg_classification: Optional[ACMGClassification] = None
    acmg_score: float = 0.0
    acmg_evidence: List[ACMGEvidence] = field(default_factory=list)
    acmg_criteria: List[str] = field(default_factory=list)

    genotype: str = ""
    allele_depth: int = 0
    total_depth: int = 0
    allele_frequency: float = 0.0
    genotype_quality: float = 0.0

    def to_dict(self) -> Dict[str, Any]:
        return {
            "variant_id": self.variant_id,
            "chromosome": self.chromosome,
            "position": self.position,
            "ref": self.ref,
            "alt": self.alt,
            "gene": self.gene,
            "transcript": self.transcript,
            "hgvsc": self.hgvsc,
            "hgvsp": self.hgvsp,
            "consequence": self.consequence,
            "impact": self.impact,
            "biotype": self.biotype,
            "population_frequencies": {
                "gnomad_af": self.gnomad_af,
                "gnomad_af_popmax": self.gnomad_af_popmax,
                "1000g_af": self.thousandg_af,
                "exac_af": self.exac_af,
            },
            "in_silico_predictions": {
                "cadd_score": self.cadd_score,
                "revel_score": self.revel_score,
                "sift_score": self.sift_score,
                "sift_prediction": self.sift_pred,
                "polyphen2_score": self.polyphen2_score,
                "polyphen2_prediction": self.polyphen2_pred,
                "mutationtaster_score": self.mutationtaster_score,
                "mutationtaster_prediction": self.mutationtaster_pred,
            },
            "clinvar": {
                "clinvar_id": self.clinvar_id,
                "clinical_significance": self.clinvar_clinsig,
                "review_status": self.clinvar_review_status,
                "disease": self.clinvar_disease,
            },
            "acmg": {
                "classification": self.acmg_classification.value if self.acmg_classification else None,
                "score": self.acmg_score,
                "criteria": self.acmg_criteria,
                "evidence": [
                    {
                        "type": ev.evidence_type.value,
                        "strength": ev.strength,
                        "description": ev.description,
                    }
                    for ev in self.acmg_evidence
                ],
            },
            "genotype": {
                "call": self.genotype,
                "allele_depth": self.allele_depth,
                "total_depth": self.total_depth,
                "allele_frequency": self.allele_frequency,
                "quality": self.genotype_quality,
            },
        }


class ACMGClassifier:
    """ACMG/AMP variant classification based on Richards et al. 2015 guidelines."""

    HIGH_IMPACT_CONSEQUENCES = {
        "frameshift_variant",
        "stop_gained",
        "stop_lost",
        "start_lost",
        "splice_acceptor_variant",
        "splice_donor_variant",
        "transcript_ablation",
    }

    MODERATE_IMPACT_CONSEQUENCES = {
        "missense_variant",
        "inframe_insertion",
        "inframe_deletion",
        "protein_altering_variant",
    }

    PATHOGENIC_CLINSIG = {
        "Pathogenic",
        "Pathogenic/Likely pathogenic",
        "Likely pathogenic",
    }

    BENIGN_CLINSIG = {
        "Benign",
        "Benign/Likely benign",
        "Likely benign",
    }

    @classmethod
    def classify(cls, variant: VariantAnnotation) -> Tuple[ACMGClassification, List[ACMGEvidence], float]:
        """Classify a variant according to ACMG guidelines."""
        evidence: List[ACMGEvidence] = []

        cls._evaluate_pvs1(variant, evidence)
        cls._evaluate_ps_evidence(variant, evidence)
        cls._evaluate_pm_evidence(variant, evidence)
        cls._evaluate_pp_evidence(variant, evidence)
        cls._evaluate_ba1(variant, evidence)
        cls._evaluate_bs_evidence(variant, evidence)
        cls._evaluate_bp_evidence(variant, evidence)

        classification, score = cls._combine_evidence(evidence)
        variant.acmg_classification = classification
        variant.acmg_score = score
        variant.acmg_evidence = evidence
        variant.acmg_criteria = [ev.evidence_type.value for ev in evidence]

        return classification, evidence, score

    @classmethod
    def _evaluate_pvs1(cls, variant: VariantAnnotation, evidence: List[ACMGEvidence]) -> None:
        if variant.consequence in cls.HIGH_IMPACT_CONSEQUENCES:
            if variant.impact == "HIGH":
                evidence.append(ACMGEvidence(
                    evidence_type=ACMGEvidenceType.PVS1,
                    strength="Very Strong",
                    description=f"Null variant in {variant.gene} ({variant.consequence})",
                ))

    @classmethod
    def _evaluate_ps_evidence(cls, variant: VariantAnnotation, evidence: List[ACMGEvidence]) -> None:
        if variant.hgvsp and variant.hgvsp != "":
            aa_change = variant.hgvsp.split(":")[-1] if ":" in variant.hgvsp else variant.hgvsp
            if variant.consequence == "missense_variant":
                evidence.append(ACMGEvidence(
                    evidence_type=ACMGEvidenceType.PS1,
                    strength="Strong",
                    description=f"Same amino acid change as known pathogenic variant: {aa_change}",
                ))

        if variant.clinvar_clinsig in cls.PATHOGENIC_CLINSIG:
            if "practice guideline" in variant.clinvar_review_status.lower() or \
               "expert panel" in variant.clinvar_review_status.lower():
                evidence.append(ACMGEvidence(
                    evidence_type=ACMGEvidenceType.PS4,
                    strength="Strong",
                    description=f"ClinVar classification: {variant.clinvar_clinsig} (review: {variant.clinvar_review_status})",
                ))

    @classmethod
    def _evaluate_pm_evidence(cls, variant: VariantAnnotation, evidence: List[ACMGEvidence]) -> None:
        if variant.gnomad_af is not None and variant.gnomad_af < 0.0001:
            evidence.append(ACMGEvidence(
                evidence_type=ACMGEvidenceType.PM2,
                strength="Moderate",
                description=f"Absent from population controls (gnomAD AF = {variant.gnomad_af})",
            ))

        if variant.consequence in cls.MODERATE_IMPACT_CONSEQUENCES:
            if variant.consequence == "missense_variant" and variant.gene:
                evidence.append(ACMGEvidence(
                    evidence_type=ACMGEvidenceType.PM5,
                    strength="Moderate",
                    description=f"Novel missense change at amino acid residue in {variant.gene}",
                ))

        if variant.consequence in {"inframe_insertion", "inframe_deletion"}:
            evidence.append(ACMGEvidence(
                evidence_type=ACMGEvidenceType.PM4,
                strength="Moderate",
                description=f"Inframe indel in {variant.gene} ({variant.consequence})",
            ))

    @classmethod
    def _evaluate_pp_evidence(cls, variant: VariantAnnotation, evidence: List[ACMGEvidence]) -> None:
        damaging_predictions = 0
        total_predictions = 0

        if variant.sift_score is not None:
            total_predictions += 1
            if variant.sift_score <= 0.05:
                damaging_predictions += 1

        if variant.polyphen2_score is not None:
            total_predictions += 1
            if variant.polyphen2_score >= 0.9:
                damaging_predictions += 1

        if variant.cadd_score is not None:
            total_predictions += 1
            if variant.cadd_score >= 20:
                damaging_predictions += 1

        if variant.revel_score is not None:
            total_predictions += 1
            if variant.revel_score >= 0.75:
                damaging_predictions += 1

        if total_predictions >= 2 and damaging_predictions / total_predictions >= 0.75:
            evidence.append(ACMGEvidence(
                evidence_type=ACMGEvidenceType.PP3,
                strength="Supporting",
                description=f"Multiple in silico predictions support pathogenicity "
                           f"({damaging_predictions}/{total_predictions} predictive of damage)",
            ))

        if variant.clinvar_clinsig in cls.PATHOGENIC_CLINSIG:
            evidence.append(ACMGEvidence(
                evidence_type=ACMGEvidenceType.PP5,
                strength="Supporting",
                description=f"Reported as pathogenic in ClinVar: {variant.clinvar_clinsig}",
            ))

    @classmethod
    def _evaluate_ba1(cls, variant: VariantAnnotation, evidence: List[ACMGEvidence]) -> None:
        if variant.gnomad_af is not None and variant.gnomad_af >= 0.05:
            evidence.append(ACMGEvidence(
                evidence_type=ACMGEvidenceType.BA1,
                strength="Stand-Alone",
                description=f"Allele frequency > 5% in general population (gnomAD AF = {variant.gnomad_af})",
            ))

    @classmethod
    def _evaluate_bs_evidence(cls, variant: VariantAnnotation, evidence: List[ACMGEvidence]) -> None:
        if variant.gnomad_af is not None and variant.gnomad_af >= 0.01:
            evidence.append(ACMGEvidence(
                evidence_type=ACMGEvidenceType.BS1,
                strength="Strong",
                description=f"Allele frequency too high for disorder (gnomAD AF = {variant.gnomad_af})",
            ))

        if variant.clinvar_clinsig in cls.BENIGN_CLINSIG:
            if "practice guideline" in variant.clinvar_review_status.lower() or \
               "expert panel" in variant.clinvar_review_status.lower():
                evidence.append(ACMGEvidence(
                    evidence_type=ACMGEvidenceType.BS4,
                    strength="Strong",
                    description=f"ClinVar classification: {variant.clinvar_clinsig} (review: {variant.clinvar_review_status})",
                ))

    @classmethod
    def _evaluate_bp_evidence(cls, variant: VariantAnnotation, evidence: List[ACMGEvidence]) -> None:
        if variant.consequence == "synonymous_variant":
            evidence.append(ACMGEvidence(
                evidence_type=ACMGEvidenceType.BP7,
                strength="Supporting",
                description="Synonymous variant with no predicted splice effect",
            ))

        if variant.sift_score is not None and variant.sift_score > 0.05:
            if variant.polyphen2_score is not None and variant.polyphen2_score < 0.5:
                evidence.append(ACMGEvidence(
                    evidence_type=ACMGEvidenceType.BP4,
                    strength="Supporting",
                    description="Multiple in silico predictions suggest benign effect",
                ))

        if variant.clinvar_clinsig in cls.BENIGN_CLINSIG:
            evidence.append(ACMGEvidence(
                evidence_type=ACMGEvidenceType.BP6,
                strength="Supporting",
                description=f"Reported as benign in ClinVar: {variant.clinvar_clinsig}",
            ))

    @classmethod
    def _combine_evidence(cls, evidence: List[ACMGEvidence]) -> Tuple[ACMGClassification, float]:
        """Combine evidence items to reach classification."""
        pathogenic_very_strong = sum(1 for e in evidence if e.evidence_type == ACMGEvidenceType.PVS1)
        pathogenic_strong = sum(1 for e in evidence if e.evidence_type.value.startswith("PS"))
        pathogenic_moderate = sum(1 for e in evidence if e.evidence_type.value.startswith("PM"))
        pathogenic_supporting = sum(1 for e in evidence if e.evidence_type.value.startswith("PP"))

        benign_standalone = sum(1 for e in evidence if e.evidence_type == ACMGEvidenceType.BA1)
        benign_strong = sum(1 for e in evidence if e.evidence_type.value.startswith("BS"))
        benign_supporting = sum(1 for e in evidence if e.evidence_type.value.startswith("BP"))

        if benign_standalone > 0:
            return ACMGClassification.BENIGN, -1.0

        p_score = (pathogenic_very_strong * 8 + pathogenic_strong * 4 +
                   pathogenic_moderate * 2 + pathogenic_supporting * 1)
        b_score = (benign_standalone * 8 + benign_strong * 4 + benign_supporting * 1)

        net_score = p_score - b_score

        if net_score >= 9:
            return ACMGClassification.PATHOGENIC, net_score
        elif net_score >= 5:
            return ACMGClassification.LIKELY_PATHOGENIC, net_score
        elif net_score <= -5:
            return ACMGClassification.LIKELY_BENIGN, net_score
        elif net_score <= -9:
            return ACMGClassification.BENIGN, net_score
        else:
            return ACMGClassification.UNCERTAIN_SIGNIFICANCE, net_score


@register_executor(PipelineStepType.VEP_ANNOTATION)
class VEPAnnotationExecutor(BaseStepExecutor):
    """Ensembl VEP variant annotation executor."""

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id") or params.get("cohort_id")

        if not input_files:
            raise StepExecutionError("No input VCF file for VEP annotation")

        input_vcf = input_files[0]
        output_vcf = str(self.work_dir / f"{sample_id}_vep.vcf.gz")
        output_html = str(self.work_dir / f"{sample_id}_vep_summary.html")
        output_stats = str(self.work_dir / f"{sample_id}_vep_stats.txt")

        cache_dir = params.get("vep_cache_dir", settings.annotation.vep_cache_dir)
        assembly = params.get("assembly", settings.annotation.vep_assembly)

        cmd = [
            settings.tools.vep,
            "-i", input_vcf,
            "-o", output_vcf,
            "--cache",
            "--dir_cache", cache_dir,
            "--assembly", assembly,
            "--species", "homo_sapiens",
            "--vcf",
            "--compress_output", "bgzip",
            "--fork", str(params.get("threads", 8)),
            "--force_overwrite",
        ]

        plugins = params.get("plugins", [])
        for plugin in plugins:
            cmd.extend(["--plugin", plugin])

        cmd.extend([
            "--symbol",
            "--hgvs",
            "--hgvsc",
            "--hgvsp",
            "--protein",
            "--ccds",
            "--uniprot",
            "--canonical",
            "--mane",
            "--tsl",
            "--appris",
            "--biotype",
            "--numbers",
            "--domains",
            "--regulatory",
            "--cell_type",
            "--distance", "5000",
            "--variant_class",
            "--sift", "b",
            "--polyphen", "b",
            "--gmaf",
            "--af",
            "--af_1kg",
            "--af_gnomad",
            "--max_af",
            "--pubmed",
            "--check_existing",
            "--check_alleles",
        ])

        custom_annotations = params.get("custom", [])
        for custom in custom_annotations:
            cmd.extend(["--custom", custom])

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=params.get("timeout", 28800))

            if returncode != 0:
                raise StepExecutionError(
                    f"VEP annotation failed with code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not Path(output_vcf).exists():
                raise StepExecutionError("VEP output VCF missing")

            tabix_cmd = ["tabix", "-p", "vcf", output_vcf]
            self._run_command(tabix_cmd, timeout=300)

            stats_cmd = [
                settings.tools.vep,
                "-i", input_vcf,
                "-o", output_stats,
                "--cache",
                "--dir_cache", cache_dir,
                "--assembly", assembly,
                "--species", "homo_sapiens",
                "--stats_file", output_html,
                "--stats_text", output_stats,
                "--no_output",
                "--force_overwrite",
            ]
            self._run_command(stats_cmd, timeout=3600)

            metrics = self._parse_vep_metrics(output_vcf, sample_id)
            metrics_file = self._save_metrics(step_id, metrics)

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=[output_vcf, output_vcf + ".tbi", output_html, output_stats, metrics_file],
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
            )

        except StepExecutionError:
            raise
        except Exception as e:
            raise StepExecutionError(f"VEP annotation execution error: {e}")

    def _parse_vep_metrics(self, vcf_file: str, sample_id: str) -> Dict[str, Any]:
        """Parse VEP-annotated VCF for metrics."""
        metrics = {
            "sample_id": sample_id,
            "total_variants": 0,
            "annotated_variants": 0,
            "by_impact": {"HIGH": 0, "MODERATE": 0, "LOW": 0, "MODIFIER": 0},
            "by_consequence": {},
            "by_gene": {},
            "coding_variants": 0,
            "missense_variants": 0,
            "truncating_variants": 0,
            "splice_variants": 0,
        }

        try:
            with gzip.open(vcf_file, "rt") as f:
                for line in f:
                    if line.startswith("##INFO=<ID=CSQ"):
                        csq_header = line
                        continue
                    if line.startswith("#"):
                        continue

                    parts = line.strip().split("\t")
                    if len(parts) < 8:
                        continue

                    metrics["total_variants"] += 1
                    info = parts[7]

                    csq_match = info.find("CSQ=")
                    if csq_match >= 0:
                        metrics["annotated_variants"] += 1
                        csq_data = info[csq_match + 4:].split(";")[0]
                        transcripts = csq_data.split(",")

                        for transcript in transcripts:
                            fields = transcript.split("|")
                            if len(fields) > 1:
                                consequence = fields[1] if len(fields) > 1 else ""
                                impact = fields[2] if len(fields) > 2 else ""
                                gene = fields[3] if len(fields) > 3 else ""

                                if impact in metrics["by_impact"]:
                                    metrics["by_impact"][impact] += 1

                                if consequence:
                                    for cons in consequence.split("&"):
                                        metrics["by_consequence"][cons] = metrics["by_consequence"].get(cons, 0) + 1

                                if gene and gene not in metrics["by_gene"]:
                                    metrics["by_gene"][gene] = 0
                                if gene:
                                    metrics["by_gene"][gene] += 1

                                if "missense" in consequence:
                                    metrics["missense_variants"] += 1
                                if any(term in consequence for term in ["stop_gained", "frameshift", "stop_lost", "start_lost"]):
                                    metrics["truncating_variants"] += 1
                                if "splice" in consequence:
                                    metrics["splice_variants"] += 1
                                if "missense" in consequence or "stop" in consequence or "frameshift" in consequence:
                                    metrics["coding_variants"] += 1
                                break

        except Exception as e:
            logger.warning(f"Failed to parse VEP metrics: {e}")

        return metrics


@register_executor(PipelineStepType.DBNSFP_ANNOTATION)
class DbNSFPAnnotationExecutor(BaseStepExecutor):
    """dbNSFP database annotation executor for population frequencies and in silico predictions."""

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id") or params.get("cohort_id")

        if not input_files:
            raise StepExecutionError("No input VCF file for dbNSFP annotation")

        input_vcf = input_files[0]
        output_vcf = str(self.work_dir / f"{sample_id}_dbnsfp.vcf.gz")
        dbnsfp_db = params.get("dbnsfp_db", settings.annotation.dbnsfp_db)

        fields_to_include = [
            "gnomAD_genomes_AF",
            "gnomAD_genomes_POPMAX_AF",
            "1000Gp3_AF",
            "ExAC_AF",
            "CADD_phred",
            "REVEL_score",
            "SIFT_score",
            "SIFT_pred",
            "Polyphen2_HDIV_score",
            "Polyphen2_HDIV_pred",
            "MutationTaster_score",
            "MutationTaster_pred",
        ]

        cmd = [
            settings.tools.gatk,
            "VariantAnnotator",
            "-R", params.get("reference", settings.reference.hg38_fasta),
            "-V", input_vcf,
            "-O", output_vcf,
            "--annotation", "dbNSFP",
            "--dbsnp", settings.reference.known_sites_snp,
        ]

        for field in fields_to_include:
            cmd.extend(["--dbnsfp-field", field])

        cmd.extend([
            "--dbnsfp", dbnsfp_db,
        ])

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=params.get("timeout", 14400))

            if returncode != 0:
                raise StepExecutionError(
                    f"dbNSFP annotation failed with code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not Path(output_vcf).exists():
                raise StepExecutionError("dbNSFP output VCF missing")

            tabix_cmd = ["tabix", "-p", "vcf", output_vcf]
            self._run_command(tabix_cmd, timeout=300)

            metrics = self._parse_dbnsfp_metrics(output_vcf, sample_id)
            metrics_file = self._save_metrics(step_id, metrics)

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=[output_vcf, output_vcf + ".tbi", metrics_file],
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
            )

        except StepExecutionError:
            raise
        except Exception as e:
            raise StepExecutionError(f"dbNSFP annotation execution error: {e}")

    def _parse_dbnsfp_metrics(self, vcf_file: str, sample_id: str) -> Dict[str, Any]:
        """Parse dbNSFP-annotated VCF for metrics."""
        metrics = {
            "sample_id": sample_id,
            "total_variants": 0,
            "with_gnomad_af": 0,
            "with_1000g_af": 0,
            "with_cadd": 0,
            "with_revel": 0,
            "with_sift": 0,
            "with_polyphen": 0,
            "rare_variants": 0,
            "ultra_rare_variants": 0,
            "damaging_predictions": 0,
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
                    info = parts[7]

                    info_dict = {}
                    for item in info.split(";"):
                        if "=" in item:
                            key, value = item.split("=", 1)
                            info_dict[key] = value

                    gnomad_af = None
                    if "gnomAD_genomes_AF" in info_dict:
                        try:
                            gnomad_af = float(info_dict["gnomAD_genomes_AF"])
                            metrics["with_gnomad_af"] += 1
                            if gnomad_af < 0.01:
                                metrics["rare_variants"] += 1
                            if gnomad_af < 0.0001:
                                metrics["ultra_rare_variants"] += 1
                        except ValueError:
                            pass

                    if "1000Gp3_AF" in info_dict:
                        metrics["with_1000g_af"] += 1

                    damaging = 0
                    total = 0

                    if "CADD_phred" in info_dict:
                        try:
                            metrics["with_cadd"] += 1
                            total += 1
                            if float(info_dict["CADD_phred"]) >= 20:
                                damaging += 1
                        except ValueError:
                            pass

                    if "REVEL_score" in info_dict:
                        try:
                            metrics["with_revel"] += 1
                            total += 1
                            if float(info_dict["REVEL_score"]) >= 0.75:
                                damaging += 1
                        except ValueError:
                            pass

                    if "SIFT_pred" in info_dict:
                        metrics["with_sift"] += 1
                        total += 1
                        if info_dict["SIFT_pred"] in ("D", "deleterious"):
                            damaging += 1

                    if "Polyphen2_HDIV_pred" in info_dict:
                        metrics["with_polyphen"] += 1
                        total += 1
                        if info_dict["Polyphen2_HDIV_pred"] in ("D", "P", "probably_damaging", "possibly_damaging"):
                            damaging += 1

                    if total >= 2 and damaging / total >= 0.75:
                        metrics["damaging_predictions"] += 1

        except Exception as e:
            logger.warning(f"Failed to parse dbNSFP metrics: {e}")

        return metrics


@register_executor(PipelineStepType.CLINVAR_ANNOTATION)
class ClinVarAnnotationExecutor(BaseStepExecutor):
    """ClinVar clinical significance annotation executor."""

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id") or params.get("cohort_id")

        if not input_files:
            raise StepExecutionError("No input VCF file for ClinVar annotation")

        input_vcf = input_files[0]
        output_vcf = str(self.work_dir / f"{sample_id}_clinvar.vcf.gz")
        clinvar_vcf = params.get("clinvar_vcf", settings.annotation.clinvar_vcf)

        cmd = [
            settings.tools.gatk,
            "VariantAnnotator",
            "-R", params.get("reference", settings.reference.hg38_fasta),
            "-V", input_vcf,
            "-O", output_vcf,
            "--resource:clinvar", clinvar_vcf,
            "--expression", "CLNSIG=clinvar.CLNSIG",
            "--expression", "CLNREVSTAT=clinvar.CLNREVSTAT",
            "--expression", "CLNACC=clinvar.CLNACC",
            "--expression", "CLNDISDB=clinvar.CLNDISDB",
            "--expression", "CLNDN=clinvar.CLNDN",
        ]

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=params.get("timeout", 7200))

            if returncode != 0:
                raise StepExecutionError(
                    f"ClinVar annotation failed with code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not Path(output_vcf).exists():
                raise StepExecutionError("ClinVar output VCF missing")

            tabix_cmd = ["tabix", "-p", "vcf", output_vcf]
            self._run_command(tabix_cmd, timeout=300)

            metrics = self._parse_clinvar_metrics(output_vcf, sample_id)
            metrics_file = self._save_metrics(step_id, metrics)

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=[output_vcf, output_vcf + ".tbi", metrics_file],
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
            )

        except StepExecutionError:
            raise
        except Exception as e:
            raise StepExecutionError(f"ClinVar annotation execution error: {e}")

    def _parse_clinvar_metrics(self, vcf_file: str, sample_id: str) -> Dict[str, Any]:
        """Parse ClinVar-annotated VCF for metrics."""
        metrics = {
            "sample_id": sample_id,
            "total_variants": 0,
            "in_clinvar": 0,
            "by_clinsig": {
                "Pathogenic": 0,
                "Likely pathogenic": 0,
                "Uncertain significance": 0,
                "Likely benign": 0,
                "Benign": 0,
                "Other": 0,
            },
            "by_review_status": {},
            "with_disease_association": 0,
        }

        clinsig_map = {
            "5": "Pathogenic",
            "4": "Likely pathogenic",
            "3": "Uncertain significance",
            "2": "Likely benign",
            "1": "Benign",
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
                    info = parts[7]

                    if "CLNSIG=" in info:
                        metrics["in_clinvar"] += 1

                        info_dict = {}
                        for item in info.split(";"):
                            if "=" in item:
                                key, value = item.split("=", 1)
                                info_dict[key] = value

                        clnsig = info_dict.get("CLNSIG", "")
                        if clnsig in clinsig_map:
                            metrics["by_clinsig"][clinsig_map[clnsig]] += 1
                        else:
                            metrics["by_clinsig"]["Other"] += 1

                        if "CLNREVSTAT" in info_dict:
                            revstat = info_dict["CLNREVSTAT"]
                            metrics["by_review_status"][revstat] = metrics["by_review_status"].get(revstat, 0) + 1

                        if "CLNDN" in info_dict and info_dict["CLNDN"]:
                            metrics["with_disease_association"] += 1

        except Exception as e:
            logger.warning(f"Failed to parse ClinVar metrics: {e}")

        return metrics


@register_executor(PipelineStepType.ACMG_CLASSIFICATION)
class ACMGClassificationExecutor(BaseStepExecutor):
    """ACMG/AMP automated variant classification executor."""

    SECONDARY_FINDING_GENES = {
        "BRCA1", "BRCA2", "PALB2", "TP53", "PTEN", "STK11", "CDKN2A",
        "MLH1", "MSH2", "MSH6", "PMS2", "EPCAM", "APC", "MUTYH",
        "MYH11", "ACTA2", "MYLK", "COL3A1", "FBN1", "TGFBR1", "TGFBR2",
        "SMAD3", "SMAD4", "ACTC1", "MYH7", "MYBPC3", "TNNT2", "TNNI3",
        "TPM1", "MYL3", "MYL2", "ACTN2", "CSRP3", "ANK2", "KCNQ1",
        "KCNH2", "SCN5A", "RYR2", "CASQ2", "CALM1", "KCNE1", "KCNE2",
        "APOB", "LDLR", "PCSK9",
    }

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id") or params.get("cohort_id")
        phenotype_hpo = params.get("phenotype_hpo", [])
        include_sf = params.get("include_secondary_findings", True)

        if not input_files:
            raise StepExecutionError("No input VCF file for ACMG classification")

        input_vcf = input_files[0]
        output_vcf = str(self.work_dir / f"{sample_id}_acmg.vcf.gz")
        output_json = str(self.work_dir / f"{sample_id}_acmg_classifications.json")

        try:
            annotated_variants = self._parse_annotated_vcf(input_vcf, sample_id)

            classifications = []
            for variant in annotated_variants:
                ACMGClassifier.classify(variant)
                if include_sf and variant.gene in self.SECONDARY_FINDING_GENES:
                    variant.is_secondary_finding = True
                classifications.append(variant)

            self._write_annotated_vcf(input_vcf, output_vcf, classifications)
            self._write_classification_json(output_json, classifications, sample_id)

            tabix_cmd = ["tabix", "-p", "vcf", output_vcf]
            self._run_command(tabix_cmd, timeout=300)

            metrics = self._compute_classification_metrics(classifications, sample_id)
            metrics_file = self._save_metrics(step_id, metrics)

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=[output_vcf, output_vcf + ".tbi", output_json, metrics_file],
                metrics=metrics,
            )

        except StepExecutionError:
            raise
        except Exception as e:
            logger.exception(f"ACMG classification error: {e}")
            raise StepExecutionError(f"ACMG classification execution error: {e}")

    def _parse_annotated_vcf(self, vcf_file: str, sample_id: str) -> List[VariantAnnotation]:
        """Parse fully annotated VCF into VariantAnnotation objects."""
        variants = []

        try:
            sample_idx = None
            csq_fields = []

            with gzip.open(vcf_file, "rt") as f:
                for line in f:
                    if line.startswith("##INFO=<ID=CSQ"):
                        format_match = line.find("Format: ")
                        if format_match >= 0:
                            format_str = line[format_match + 8:].strip().rstrip('">')
                            csq_fields = [f.strip() for f in format_str.split("|")]
                        continue

                    if line.startswith("#CHROM"):
                        headers = line.strip().split("\t")
                        if len(headers) > 9:
                            sample_name = headers[9]
                        continue

                    if line.startswith("#"):
                        continue

                    parts = line.strip().split("\t")
                    if len(parts) < 8:
                        continue

                    chrom = parts[0]
                    pos = int(parts[1])
                    var_id = parts[2]
                    ref = parts[3]
                    alt = parts[4]
                    qual = float(parts[5]) if parts[5] != "." else 0.0
                    info = parts[7]

                    variant = VariantAnnotation(
                        chromosome=chrom,
                        position=pos,
                        ref=ref,
                        alt=alt,
                        variant_id=var_id or f"{chrom}-{pos}-{ref}-{alt}",
                        genotype_quality=qual,
                    )

                    info_dict = {}
                    for item in info.split(";"):
                        if "=" in item:
                            key, value = item.split("=", 1)
                            info_dict[key] = value

                    if "CSQ" in info_dict and csq_fields:
                        csq_data = info_dict["CSQ"].split(",")[0]
                        csq_values = csq_data.split("|")
                        csq_dict = dict(zip(csq_fields, csq_values))

                        variant.gene = csq_dict.get("SYMBOL", "")
                        variant.transcript = csq_dict.get("Feature", "")
                        variant.hgvsc = csq_dict.get("HGVSc", "")
                        variant.hgvsp = csq_dict.get("HGVSp", "")
                        variant.consequence = csq_dict.get("Consequence", "").split("&")[0]
                        variant.impact = csq_dict.get("IMPACT", "")
                        variant.biotype = csq_dict.get("BIOTYPE", "")
                        variant.strand = int(csq_dict.get("STRAND", "0"))

                        try:
                            af_val = csq_dict.get("gnomAD_AF", "") or csq_dict.get("AF", "")
                            if af_val:
                                variant.gnomad_af = float(af_val)
                        except ValueError:
                            pass

                        try:
                            sift_val = csq_dict.get("SIFT", "")
                            if sift_val:
                                sift_parts = sift_val.split("(")
                                if len(sift_parts) > 1:
                                    variant.sift_pred = sift_parts[0]
                                    try:
                                        variant.sift_score = float(sift_parts[1].rstrip(")"))
                                    except ValueError:
                                        pass
                        except Exception:
                            pass

                        try:
                            pp_val = csq_dict.get("PolyPhen", "")
                            if pp_val:
                                pp_parts = pp_val.split("(")
                                if len(pp_parts) > 1:
                                    variant.polyphen2_pred = pp_parts[0]
                                    try:
                                        variant.polyphen2_score = float(pp_parts[1].rstrip(")"))
                                    except ValueError:
                                        pass
                        except Exception:
                            pass

                    if "gnomAD_genomes_AF" in info_dict:
                        try:
                            variant.gnomad_af = float(info_dict["gnomAD_genomes_AF"])
                        except ValueError:
                            pass
                    if "gnomAD_genomes_POPMAX_AF" in info_dict:
                        try:
                            variant.gnomad_af_popmax = float(info_dict["gnomAD_genomes_POPMAX_AF"])
                        except ValueError:
                            pass
                    if "1000Gp3_AF" in info_dict:
                        try:
                            variant.thousandg_af = float(info_dict["1000Gp3_AF"])
                        except ValueError:
                            pass
                    if "CADD_phred" in info_dict:
                        try:
                            variant.cadd_score = float(info_dict["CADD_phred"])
                        except ValueError:
                            pass
                    if "REVEL_score" in info_dict:
                        try:
                            variant.revel_score = float(info_dict["REVEL_score"])
                        except ValueError:
                            pass

                    if "CLNSIG" in info_dict:
                        variant.clinvar_clinsig = info_dict["CLNSIG"]
                    if "CLNREVSTAT" in info_dict:
                        variant.clinvar_review_status = info_dict["CLNREVSTAT"]
                    if "CLNACC" in info_dict:
                        variant.clinvar_id = info_dict["CLNACC"]
                    if "CLNDN" in info_dict:
                        variant.clinvar_disease = info_dict["CLNDN"]

                    if len(parts) > 9:
                        format_fields = parts[8].split(":")
                        sample_data = parts[9].split(":")
                        sample_dict = dict(zip(format_fields, sample_data))

                        variant.genotype = sample_dict.get("GT", "")
                        if "AD" in sample_dict:
                            ad_parts = sample_dict["AD"].split(",")
                            if len(ad_parts) >= 2:
                                try:
                                    variant.allele_depth = int(ad_parts[1])
                                    variant.total_depth = int(ad_parts[0]) + int(ad_parts[1])
                                    if variant.total_depth > 0:
                                        variant.allele_frequency = variant.allele_depth / variant.total_depth
                                except ValueError:
                                    pass
                        if "DP" in sample_dict:
                            try:
                                variant.total_depth = int(sample_dict["DP"])
                            except ValueError:
                                pass
                        if "GQ" in sample_dict:
                            try:
                                variant.genotype_quality = float(sample_dict["GQ"])
                            except ValueError:
                                pass

                    variants.append(variant)

        except Exception as e:
            logger.warning(f"Error parsing annotated VCF: {e}")
            raise

        return variants

    def _write_annotated_vcf(self, input_vcf: str, output_vcf: str, variants: List[VariantAnnotation]) -> None:
        """Write VCF with ACMG annotations added."""
        variant_map = {
            f"{v.chromosome}-{v.position}-{v.ref}-{v.alt}": v
            for v in variants
        }

        with gzip.open(input_vcf, "rt") as fin:
            with gzip.open(output_vcf, "wt") as fout:
                for line in fin:
                    if line.startswith("##"):
                        fout.write(line)
                        continue

                    if line.startswith("#CHROM"):
                        fout.write('##INFO=<ID=ACMG_CLASS,Number=1,Type=String,Description="ACMG classification">\n')
                        fout.write('##INFO=<ID=ACMG_SCORE,Number=1,Type=Float,Description="ACMG score">\n')
                        fout.write('##INFO=<ID=ACMG_CRITERIA,Number=.,Type=String,Description="ACMG criteria met">\n')
                        fout.write('##INFO=<ID=IS_SECONDARY_FINDING,Number=0,Type=Flag,Description="Secondary finding gene">\n')
                        fout.write(line)
                        continue

                    parts = line.strip().split("\t")
                    if len(parts) < 8:
                        fout.write(line)
                        continue

                    key = f"{parts[0]}-{parts[1]}-{parts[3]}-{parts[4]}"
                    variant = variant_map.get(key)

                    if variant and variant.acmg_classification:
                        acmg_info = [
                            f"ACMG_CLASS={variant.acmg_classification.value}",
                            f"ACMG_SCORE={variant.acmg_score}",
                        ]
                        if variant.acmg_criteria:
                            acmg_info.append(f"ACMG_CRITERIA={','.join(variant.acmg_criteria)}")
                        if getattr(variant, 'is_secondary_finding', False):
                            acmg_info.append("IS_SECONDARY_FINDING")

                        parts[7] = parts[7] + ";" + ";".join(acmg_info)
                        fout.write("\t".join(parts) + "\n")
                    else:
                        fout.write(line)

    def _write_classification_json(self, output_file: str, variants: List[VariantAnnotation], sample_id: str) -> None:
        """Write detailed classifications to JSON file."""
        output = {
            "sample_id": sample_id,
            "total_variants": len(variants),
            "classification_summary": {
                "P": sum(1 for v in variants if v.acmg_classification == ACMGClassification.PATHOGENIC),
                "LP": sum(1 for v in variants if v.acmg_classification == ACMGClassification.LIKELY_PATHOGENIC),
                "VUS": sum(1 for v in variants if v.acmg_classification == ACMGClassification.UNCERTAIN_SIGNIFICANCE),
                "LB": sum(1 for v in variants if v.acmg_classification == ACMGClassification.LIKELY_BENIGN),
                "B": sum(1 for v in variants if v.acmg_classification == ACMGClassification.BENIGN),
            },
            "secondary_findings": [
                v.to_dict() for v in variants
                if getattr(v, 'is_secondary_finding', False)
                and v.acmg_classification in (ACMGClassification.PATHOGENIC, ACMGClassification.LIKELY_PATHOGENIC)
            ],
            "pathogenic_variants": [
                v.to_dict() for v in variants
                if v.acmg_classification in (ACMGClassification.PATHOGENIC, ACMGClassification.LIKELY_PATHOGENIC)
            ],
            "all_classifications": [v.to_dict() for v in variants],
        }

        with open(output_file, "w") as f:
            json.dump(output, f, indent=2)

    def _compute_classification_metrics(self, variants: List[VariantAnnotation], sample_id: str) -> Dict[str, Any]:
        """Compute classification summary metrics."""
        metrics = {
            "sample_id": sample_id,
            "total_variants": len(variants),
            "by_classification": {
                "P": sum(1 for v in variants if v.acmg_classification == ACMGClassification.PATHOGENIC),
                "LP": sum(1 for v in variants if v.acmg_classification == ACMGClassification.LIKELY_PATHOGENIC),
                "VUS": sum(1 for v in variants if v.acmg_classification == ACMGClassification.UNCERTAIN_SIGNIFICANCE),
                "LB": sum(1 for v in variants if v.acmg_classification == ACMGClassification.LIKELY_BENIGN),
                "B": sum(1 for v in variants if v.acmg_classification == ACMGClassification.BENIGN),
            },
            "secondary_findings": sum(
                1 for v in variants
                if getattr(v, 'is_secondary_finding', False)
                and v.acmg_classification in (ACMGClassification.PATHOGENIC, ACMGClassification.LIKELY_PATHOGENIC)
            ),
            "candidate_genes": list(set(
                v.gene for v in variants
                if v.acmg_classification in (ACMGClassification.PATHOGENIC, ACMGClassification.LIKELY_PATHOGENIC)
                and v.gene
            )),
        }

        return metrics
