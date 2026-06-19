import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import logging
import json
from typing import List, Dict, Any, Optional, Tuple
from dataclasses import dataclass, field
from pathlib import Path

from pipeline.executor import (
    BaseStepExecutor,
    StepResult,
    StepExecutionError,
    register_executor,
)
from config.settings import settings
from db.models import VariantType, ACMGClassification

logger = logging.getLogger(__name__)


@register_executor("star_fusion")
class StarFusionExecutor(BaseStepExecutor):

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id", "unknown")
        r1 = params.get("r1_fastq", "")
        r2 = params.get("r2_fastq", "")
        output_dir = str(self.work_dir / f"{sample_id}_star_fusion")

        cmd = [
            settings.tools.get("star_fusion", "STAR-Fusion"),
            "--genome_lib_dir", settings.annotation.get("star_fusion_genome_lib", "/data/STAR-Fusion/genome_lib_build_dir/GRCh38_gencode_v33_CTAT_lib_Feb2020"),
            "--left_fq", r1,
            "--right_fq", r2,
            "--output_dir", output_dir,
            "--CPU", str(params.get("threads", 8)),
            "--FusionInspector", "validate",
        ]

        try:
            rc, stdout, stderr = self._run_command(cmd, timeout=86400)
            if rc != 0:
                return StepResult(
                    success=False, step_id=step_id,
                    error_message=f"STAR-Fusion failed (rc={rc}): {stderr[:500]}",
                    stdout=stdout, stderr=stderr,
                )

            fusion_file = Path(output_dir) / "star-fusion.fusion_candidates.final.abridged.txt"
            if not fusion_file.exists():
                return StepResult(
                    success=False, step_id=step_id,
                    error_message="STAR-Fusion output file not found",
                )

            fusions = self._parse_star_fusion_output(fusion_file, sample_id)

            metrics = {
                "total_fusions": len(fusions),
                "high_confidence_fusions": sum(
                    1 for f in fusions if f.get("ffpm", 0) >= 0.1
                ),
            }

            return StepResult(
                success=True, step_id=step_id,
                output_files=[str(fusion_file)],
                metrics=metrics,
            )

        except StepExecutionError as e:
            return StepResult(success=False, step_id=step_id, error_message=str(e))

    def _parse_star_fusion_output(self, fusion_file: Path, sample_id: str) -> List[Dict[str, Any]]:
        fusions = []
        with open(fusion_file, "r") as f:
            header = None
            for line in f:
                line = line.strip()
                if line.startswith("#"):
                    continue
                if header is None:
                    header = line.split("\t")
                    continue

                fields = line.split("\t")
                if len(fields) < len(header):
                    continue

                record = dict(zip(header, fields))

                left_gene = record.get("LeftGene", "").split("^")[0] if record.get("LeftGene") else ""
                right_gene = record.get("RightGene", "").split("^")[0] if record.get("RightGene") else ""

                left_bp = record.get("LeftBreakpoint", "")
                right_bp = record.get("RightBreakpoint", "")

                ffpm = 0.0
                try:
                    ffpm = float(record.get("FFPM", "0"))
                except (ValueError, TypeError):
                    pass

                junction_reads = 0
                spanning_reads = 0
                try:
                    junction_reads = int(record.get("JunctionReads", "0").split(",")[-1]) if record.get("JunctionReads") else 0
                except (ValueError, IndexError):
                    pass
                try:
                    spanning_reads = int(record.get("SpanningFrags", "0").split(",")[-1]) if record.get("SpanningFrags") else 0
                except (ValueError, IndexError):
                    pass

                fusions.append({
                    "sample_id": sample_id,
                    "variant_type": VariantType.FUSION.value,
                    "gene": left_gene,
                    "fusion_partner_gene": right_gene,
                    "fusion_breakpoint_5prime": left_bp,
                    "fusion_breakpoint_3prime": right_bp,
                    "fusion_fusion_type": "in-frame" if "INFRAME" in record.get("PROT_FUSION_TYPE", "") else "out-of-frame",
                    "fusion_frame": record.get("PROT_FUSION_TYPE", ""),
                    "fusion_junction_reads": junction_reads,
                    "fusion_spanning_reads": spanning_reads,
                    "ffpm": ffpm,
                    "allele_frequency": ffpm,
                })

        return fusions


@register_executor("arriba")
class ArribaExecutor(BaseStepExecutor):

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id", "unknown")
        bam_file = params.get("bam_file", "")
        output_prefix = str(self.work_dir / f"{sample_id}_arriba")

        cmd = [
            settings.tools.get("arriba", "arriba"),
            "-x", bam_file,
            "-o", f"{output_prefix}_fusions.tsv",
            "-O", f"{output_prefix}_discarded.tsv",
            "-a", settings.annotation.get("arriba_assembly", "/data/arriba/hg38.blacklist.txt"),
            "-g", settings.reference.hg38_gtf if hasattr(settings.reference, "hg38_gtf") else "/data/reference/hg38/gencode.v33.annotation.gtf",
            "-b", settings.annotation.get("arriba_blacklist", "/data/arriba/hg38.blacklist.txt"),
            "-T",
        ]

        try:
            rc, stdout, stderr = self._run_command(cmd, timeout=86400)
            if rc != 0:
                return StepResult(
                    success=False, step_id=step_id,
                    error_message=f"Arriba failed (rc={rc}): {stderr[:500]}",
                )

            fusions_file = Path(f"{output_prefix}_fusions.tsv")
            if not fusions_file.exists():
                return StepResult(
                    success=False, step_id=step_id,
                    error_message="Arriba output file not found",
                )

            fusions = self._parse_arriba_output(fusions_file, sample_id)
            metrics = {"total_fusions": len(fusions)}

            return StepResult(
                success=True, step_id=step_id,
                output_files=[str(fusions_file)],
                metrics=metrics,
            )

        except StepExecutionError as e:
            return StepResult(success=False, step_id=step_id, error_message=str(e))

    def _parse_arriba_output(self, fusion_file: Path, sample_id: str) -> List[Dict[str, Any]]:
        fusions = []
        with open(fusion_file, "r") as f:
            header = None
            for line in f:
                line = line.strip()
                if line.startswith("#"):
                    continue
                if header is None:
                    header = line.split("\t")
                    continue

                fields = line.split("\t")
                record = dict(zip(header, fields))

                gene1 = record.get("#gene1", "")
                gene2 = record.get("gene2", "")
                breakpoint1 = record.get("breakpoint1", "")
                breakpoint2 = record.get("breakpoint2", "")
                reading_frame = record.get("reading_frame", "")

                junction_reads = 0
                spanning_reads = 0
                try:
                    split_reads1 = int(record.get("split_reads1", 0))
                    split_reads2 = int(record.get("split_reads2", 0))
                    discordant_mates = int(record.get("discordant_mates", 0))
                    junction_reads = split_reads1 + split_reads2
                    spanning_reads = discordant_mates
                except (ValueError, TypeError):
                    pass

                fusions.append({
                    "sample_id": sample_id,
                    "variant_type": VariantType.FUSION.value,
                    "gene": gene1,
                    "fusion_partner_gene": gene2,
                    "fusion_breakpoint_5prime": breakpoint1,
                    "fusion_breakpoint_3prime": breakpoint2,
                    "fusion_fusion_type": reading_frame,
                    "fusion_frame": reading_frame,
                    "fusion_junction_reads": junction_reads,
                    "fusion_spanning_reads": spanning_reads,
                })

        return fusions


@register_executor("manta")
class MantaExecutor(BaseStepExecutor):

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id", "unknown")
        bam_file = params.get("bam_file", "")
        output_dir = str(self.work_dir / f"{sample_id}_manta")

        config_cmd = [
            settings.tools.get("manta", "configManta.py"),
            "--bam", bam_file,
            "--referenceFasta", settings.reference.hg38_fasta,
            "--runDir", output_dir,
        ]

        try:
            rc, stdout, stderr = self._run_command(config_cmd, timeout=3600)
            if rc != 0:
                return StepResult(
                    success=False, step_id=step_id,
                    error_message=f"Manta config failed (rc={rc}): {stderr[:500]}",
                )

            run_cmd = [f"{output_dir}/runWorkflow.py", "-m", "local", "-j", str(params.get("threads", 8))]
            rc, stdout, stderr = self._run_command(run_cmd, timeout=86400)
            if rc != 0:
                return StepResult(
                    success=False, step_id=step_id,
                    error_message=f"Manta workflow failed (rc={rc}): {stderr[:500]}",
                )

            sv_vcf = Path(output_dir) / "results" / "variants" / "diploidSV.vcf.gz"
            if not sv_vcf.exists():
                return StepResult(
                    success=False, step_id=step_id,
                    error_message="Manta output VCF not found",
                )

            svs = self._parse_manta_vcf(sv_vcf, sample_id)
            fusions = self._extract_fusion_candidates(svs)

            metrics = {
                "total_svs": len(svs),
                "fusion_candidates": len(fusions),
                "deletions": sum(1 for s in svs if s.get("sv_event_type") == "DEL"),
                "duplications": sum(1 for s in svs if s.get("sv_event_type") == "DUP"),
                "inversions": sum(1 for s in svs if s.get("sv_event_type") == "INV"),
                "translocations": sum(1 for s in svs if s.get("sv_event_type") == "BND"),
            }

            return StepResult(
                success=True, step_id=step_id,
                output_files=[str(sv_vcf)],
                metrics=metrics,
            )

        except StepExecutionError as e:
            return StepResult(success=False, step_id=step_id, error_message=str(e))

    def _parse_manta_vcf(self, vcf_path: Path, sample_id: str) -> List[Dict[str, Any]]:
        svs = []
        try:
            import gzip
            opener = gzip.open if str(vcf_path).endswith(".gz") else open
            with opener(vcf_path, "rt") as f:
                for line in f:
                    if line.startswith("#"):
                        continue
                    fields = line.strip().split("\t")
                    if len(fields) < 8:
                        continue

                    chrom = fields[0]
                    pos = int(fields[1])
                    info_str = fields[7]

                    info = {}
                    for item in info_str.split(";"):
                        if "=" in item:
                            k, v = item.split("=", 1)
                            info[k] = v
                        else:
                            info[item] = True

                    sv_type = info.get("SVTYPE", "UNKNOWN")
                    end_pos = int(info.get("END", pos))
                    sv_len = int(info.get("SVLEN", end_pos - pos)) if "SVLEN" in info else end_pos - pos

                    mate_id = info.get("MATEID", "")
                    ci_pos = info.get("CIPOS", "0,0")
                    ci_end = info.get("CIEND", "0,0")

                    svs.append({
                        "sample_id": sample_id,
                        "variant_type": VariantType.SV.value if sv_type != "BND" else VariantType.BND.value,
                        "chromosome": chrom,
                        "position": pos,
                        "end_position": end_pos,
                        "sv_event_type": sv_type,
                        "sv_length": abs(sv_len),
                        "sv_ci_pos_left": ci_pos,
                        "sv_ci_pos_right": ci_end,
                        "ref": fields[3],
                        "alt": fields[4],
                        "mate_id": mate_id,
                    })
        except Exception as e:
            logger.error(f"Failed to parse Manta VCF: {e}")

        return svs

    def _extract_fusion_candidates(self, svs: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        CLINICALLY_RELEVANT_FUSION_GENES = {
            "ALK", "ROS1", "RET", "NTRK1", "NTRK2", "NTRK3",
            "FGFR1", "FGFR2", "FGFR3", "FGFR4",
            "BRAF", "RAF1", "MET", "EGFR", "ERBB2",
            "PAX3", "PAX7", "EWSR1", "FLI1", "TMPRSS2", "ETV6",
        }

        fusions = []
        for sv in svs:
            gene = sv.get("gene", "")
            if gene in CLINICALLY_RELEVANT_FUSION_GENES:
                fusions.append(sv)

        return fusions


@dataclass
class DrugRecommendation:
    drug_name: str
    source: str
    evidence_level: str
    cancer_type: str = ""
    interaction_type: str = ""
    pmid: str = ""


class FusionDrugRecommender:

    CGI_FUSION_DB = {
        "ALK": [
            {"drug": "Crizotinib", "evidence": "FDA-approved", "cancer": "NSCLC", "interaction": "responsive"},
            {"drug": "Alectinib", "evidence": "FDA-approved", "cancer": "NSCLC", "interaction": "responsive"},
            {"drug": "Brigatinib", "evidence": "FDA-approved", "cancer": "NSCLC", "interaction": "responsive"},
            {"drug": "Lorlatinib", "evidence": "FDA-approved", "cancer": "NSCLC", "interaction": "responsive"},
            {"drug": "Ceritinib", "evidence": "FDA-approved", "cancer": "NSCLC", "interaction": "responsive"},
        ],
        "ROS1": [
            {"drug": "Crizotinib", "evidence": "FDA-approved", "cancer": "NSCLC", "interaction": "responsive"},
            {"drug": "Entrectinib", "evidence": "FDA-approved", "cancer": "NSCLC", "interaction": "responsive"},
            {"drug": "Lorlatinib", "evidence": "Clinical trial", "cancer": "NSCLC", "interaction": "responsive"},
        ],
        "RET": [
            {"drug": "Selpercatinib", "evidence": "FDA-approved", "cancer": "NSCLC", "interaction": "responsive"},
            {"drug": "Pralsetinib", "evidence": "FDA-approved", "cancer": "NSCLC", "interaction": "responsive"},
            {"drug": "Cabozantinib", "evidence": "FDA-approved", "cancer": "Thyroid", "interaction": "responsive"},
        ],
        "NTRK1": [
            {"drug": "Larotrectinib", "evidence": "FDA-approved", "cancer": "Solid tumors", "interaction": "responsive"},
            {"drug": "Entrectinib", "evidence": "FDA-approved", "cancer": "Solid tumors", "interaction": "responsive"},
        ],
        "NTRK2": [
            {"drug": "Larotrectinib", "evidence": "FDA-approved", "cancer": "Solid tumors", "interaction": "responsive"},
            {"drug": "Entrectinib", "evidence": "FDA-approved", "cancer": "Solid tumors", "interaction": "responsive"},
        ],
        "NTRK3": [
            {"drug": "Larotrectinib", "evidence": "FDA-approved", "cancer": "Solid tumors", "interaction": "responsive"},
            {"drug": "Entrectinib", "evidence": "FDA-approved", "cancer": "Solid tumors", "interaction": "responsive"},
        ],
        "FGFR2": [
            {"drug": "Pemigatinib", "evidence": "FDA-approved", "cancer": "Cholangiocarcinoma", "interaction": "responsive"},
            {"drug": "Futibatinib", "evidence": "FDA-approved", "cancer": "Cholangiocarcinoma", "interaction": "responsive"},
            {"drug": "Infigratinib", "evidence": "FDA-approved", "cancer": "Cholangiocarcinoma", "interaction": "responsive"},
        ],
        "FGFR3": [
            {"drug": "Erdafitinib", "evidence": "FDA-approved", "cancer": "Bladder cancer", "interaction": "responsive"},
        ],
        "BRAF": [
            {"drug": "Dabrafenib + Trametinib", "evidence": "FDA-approved", "cancer": "Melanoma/NSCLC", "interaction": "responsive"},
            {"drug": "Vemurafenib + Cobimetinib", "evidence": "FDA-approved", "cancer": "Melanoma", "interaction": "responsive"},
        ],
    }

    CIVIC_FUSION_DB = {
        "BCR-ABL1": [
            {"drug": "Imatinib", "evidence": "FDA-approved", "cancer": "CML", "interaction": "responsive"},
            {"drug": "Dasatinib", "evidence": "FDA-approved", "cancer": "CML", "interaction": "responsive"},
            {"drug": "Nilotinib", "evidence": "FDA-approved", "cancer": "CML", "interaction": "responsive"},
            {"drug": "Bosutinib", "evidence": "FDA-approved", "cancer": "CML", "interaction": "responsive"},
            {"drug": "Ponatinib", "evidence": "FDA-approved", "cancer": "CML", "interaction": "responsive"},
        ],
        "TMPRSS2-ERG": [
            {"drug": "Enzalutamide", "evidence": "Clinical trial", "cancer": "Prostate", "interaction": "predicted responsive"},
        ],
        "EML4-ALK": [
            {"drug": "Crizotinib", "evidence": "FDA-approved", "cancer": "NSCLC", "interaction": "responsive"},
            {"drug": "Alectinib", "evidence": "FDA-approved", "cancer": "NSCLC", "interaction": "responsive"},
        ],
    }

    @classmethod
    def get_recommendations(cls, gene5prime: str, gene3prime: str) -> List[Dict[str, Any]]:
        recommendations = []

        for gene in [gene5prime, gene3prime]:
            if gene in cls.CGI_FUSION_DB:
                for entry in cls.CGI_FUSION_DB[gene]:
                    recommendations.append({
                        "drug_name": entry["drug"],
                        "source": "CGI",
                        "evidence_level": entry["evidence"],
                        "cancer_type": entry["cancer"],
                        "interaction_type": entry["interaction"],
                        "target_gene": gene,
                    })

        fusion_key = f"{gene5prime}-{gene3prime}"
        fusion_key_rev = f"{gene3prime}-{gene5prime}"
        for key in [fusion_key, fusion_key_rev]:
            if key in cls.CIVIC_FUSION_DB:
                for entry in cls.CIVIC_FUSION_DB[key]:
                    recommendations.append({
                        "drug_name": entry["drug"],
                        "source": "CIViC",
                        "evidence_level": entry["evidence"],
                        "cancer_type": entry["cancer"],
                        "interaction_type": entry["interaction"],
                        "target_fusion": key,
                    })

        seen = set()
        unique = []
        for r in recommendations:
            key = (r["drug_name"], r["source"])
            if key not in seen:
                seen.add(key)
                unique.append(r)

        return unique


@register_executor("fusion_drug_annotation")
class FusionDrugAnnotationExecutor(BaseStepExecutor):

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id", "unknown")
        fusions_json = params.get("fusions_json", "")

        try:
            if fusions_json and Path(fusions_json).exists():
                with open(fusions_json) as f:
                    fusions = json.load(f)
            else:
                fusions = []

            annotated_fusions = []
            total_drugs = 0
            for fusion in fusions:
                gene5 = fusion.get("gene", "")
                gene3 = fusion.get("fusion_partner_gene", "")
                drugs = FusionDrugRecommender.get_recommendations(gene5, gene3)
                fusion["targeted_drugs"] = drugs
                total_drugs += len(drugs)
                annotated_fusions.append(fusion)

            output_file = self.work_dir / f"{sample_id}_fusion_drugs.json"
            with open(output_file, "w") as f:
                json.dump(annotated_fusions, f, indent=2, default=str)

            return StepResult(
                success=True, step_id=step_id,
                output_files=[str(output_file)],
                metrics={
                    "total_fusions": len(annotated_fusions),
                    "fusions_with_drugs": sum(1 for f in annotated_fusions if f.get("targeted_drugs")),
                    "total_drug_recommendations": total_drugs,
                },
            )

        except Exception as e:
            return StepResult(success=False, step_id=step_id, error_message=str(e))
