import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import logging
import json
from typing import List, Dict, Any, Optional, Set, Tuple
from collections import defaultdict

from db.models import (
    InheritanceMode,
    FamilyRole,
    ACMGClassification,
    VariantType,
)
from pipeline.executor import (
    BaseStepExecutor,
    StepResult,
    register_executor,
)

logger = logging.getLogger(__name__)


class InheritanceFilter:

    X_CHROMOSOME = "chrX"
    Y_CHROMOSOME = "chrY"
    MITO_CHROMOSOME = "chrM"

    @staticmethod
    def filter_autosomal_dominant_de_novo(
        proband_variants: List[Dict[str, Any]],
        mother_variants: List[Dict[str, Any]],
        father_variants: List[Dict[str, Any]],
    ) -> List[Dict[str, Any]]:
        parent_positions = set()
        for v in mother_variants + father_variants:
            key = (v.get("chromosome"), v.get("position"), v.get("ref"), v.get("alt"))
            parent_positions.add(key)

        de_novo = []
        for v in proband_variants:
            key = (v.get("chromosome"), v.get("position"), v.get("ref"), v.get("alt"))
            if key not in parent_positions:
                chrom = v.get("chromosome", "")
                if chrom not in (InheritanceFilter.X_CHROMOSOME,
                                 InheritanceFilter.Y_CHROMOSOME,
                                 InheritanceFilter.MITO_CHROMOSOME):
                    v_copy = dict(v)
                    v_copy["inheritance_mode"] = InheritanceMode.DE_NOVO.value
                    v_copy["segregation_info"] = {
                        "mode": "autosomal_dominant_de_novo",
                        "present_in_mother": False,
                        "present_in_father": False,
                    }
                    de_novo.append(v_copy)

        return de_novo

    @staticmethod
    def filter_autosomal_recessive_homozygous(
        proband_variants: List[Dict[str, Any]],
        mother_variants: List[Dict[str, Any]],
        father_variants: List[Dict[str, Any]],
    ) -> List[Dict[str, Any]]:
        mother_keys = set()
        for v in mother_variants:
            key = (v.get("chromosome"), v.get("position"), v.get("ref"), v.get("alt"))
            mother_keys.add(key)

        father_keys = set()
        for v in father_variants:
            key = (v.get("chromosome"), v.get("position"), v.get("ref"), v.get("alt"))
            father_keys.add(key)

        homozygous_recessive = []
        for v in proband_variants:
            genotype = v.get("genotype", "")
            if genotype != "1/1":
                continue

            chrom = v.get("chromosome", "")
            if chrom in (InheritanceFilter.X_CHROMOSOME,
                         InheritanceFilter.Y_CHROMOSOME,
                         InheritanceFilter.MITO_CHROMOSOME):
                continue

            key = (v.get("chromosome"), v.get("position"), v.get("ref"), v.get("alt"))
            in_mother = key in mother_keys
            in_father = key in father_keys

            if in_mother and in_father:
                v_copy = dict(v)
                v_copy["inheritance_mode"] = InheritanceMode.AUTOSOMAL_RECESSIVE.value
                v_copy["segregation_info"] = {
                    "mode": "autosomal_recessive_homozygous",
                    "present_in_mother": in_mother,
                    "present_in_father": in_father,
                    "genotype_proband": genotype,
                }
                homozygous_recessive.append(v_copy)

        return homozygous_recessive

    @staticmethod
    def filter_compound_heterozygous(
        proband_variants: List[Dict[str, Any]],
        mother_variants: List[Dict[str, Any]],
        father_variants: List[Dict[str, Any]],
    ) -> List[Dict[str, Any]]:
        mother_keys = set()
        for v in mother_variants:
            key = (v.get("chromosome"), v.get("position"), v.get("ref"), v.get("alt"))
            mother_keys.add(key)

        father_keys = set()
        for v in father_variants:
            key = (v.get("chromosome"), v.get("position"), v.get("ref"), v.get("alt"))
            father_keys.add(key)

        proband_het_by_gene: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
        for v in proband_variants:
            genotype = v.get("genotype", "")
            if genotype != "0/1":
                continue
            gene = v.get("gene", "")
            if not gene:
                continue
            chrom = v.get("chromosome", "")
            if chrom in (InheritanceFilter.X_CHROMOSOME,
                         InheritanceFilter.Y_CHROMOSOME,
                         InheritanceFilter.MITO_CHROMOSOME):
                continue
            proband_het_by_gene[gene].append(v)

        compound_het_pairs = []
        for gene, variants in proband_het_by_gene.items():
            if len(variants) < 2:
                continue

            for i in range(len(variants)):
                for j in range(i + 1, len(variants)):
                    v1 = variants[i]
                    v2 = variants[j]

                    key1 = (v1.get("chromosome"), v1.get("position"), v1.get("ref"), v1.get("alt"))
                    key2 = (v2.get("chromosome"), v2.get("position"), v2.get("ref"), v2.get("alt"))

                    v1_in_mother = key1 in mother_keys
                    v1_in_father = key1 in father_keys
                    v2_in_mother = key2 in mother_keys
                    v2_in_father = key2 in father_keys

                    trans = ((v1_in_mother and not v1_in_father) and
                             (v2_in_father and not v2_in_mother))
                    trans_rev = ((v1_in_father and not v1_in_mother) and
                                 (v2_in_mother and not v2_in_father))

                    if trans or trans_rev:
                        v1_copy = dict(v1)
                        v1_copy["inheritance_mode"] = InheritanceMode.COMPOUND_HETEROZYGOUS.value
                        v1_copy["segregation_info"] = {
                            "mode": "compound_heterozygous",
                            "gene": gene,
                            "partner_variant": f"{v2.get('chromosome')}:{v2.get('position')}{v2.get('ref')}>{v2.get('alt')}",
                            "present_in_mother": v1_in_mother,
                            "present_in_father": v1_in_father,
                        }
                        v2_copy = dict(v2)
                        v2_copy["inheritance_mode"] = InheritanceMode.COMPOUND_HETEROZYGOUS.value
                        v2_copy["segregation_info"] = {
                            "mode": "compound_heterozygous",
                            "gene": gene,
                            "partner_variant": f"{v1.get('chromosome')}:{v1.get('position')}{v1.get('ref')}>{v1.get('alt')}",
                            "present_in_mother": v2_in_mother,
                            "present_in_father": v2_in_father,
                        }
                        compound_het_pairs.append(v1_copy)
                        compound_het_pairs.append(v2_copy)

        return compound_het_pairs

    @staticmethod
    def filter_x_linked(
        proband_variants: List[Dict[str, Any]],
        mother_variants: List[Dict[str, Any]],
        father_variants: List[Dict[str, Any]],
        proband_role: FamilyRole = FamilyRole.PROBAND,
    ) -> List[Dict[str, Any]]:
        x_linked = []

        mother_keys = set()
        for v in mother_variants:
            key = (v.get("chromosome"), v.get("position"), v.get("ref"), v.get("alt"))
            mother_keys.add(key)

        for v in proband_variants:
            chrom = v.get("chromosome", "")
            if chrom != InheritanceFilter.X_CHROMOSOME:
                continue

            key = (v.get("chromosome"), v.get("position"), v.get("ref"), v.get("alt"))
            in_mother = key in mother_keys

            if proband_role == FamilyRole.PROBAND:
                genotype = v.get("genotype", "")
                if genotype == "1/1" or genotype == "0/1":
                    v_copy = dict(v)
                    v_copy["inheritance_mode"] = InheritanceMode.X_LINKED.value
                    v_copy["segregation_info"] = {
                        "mode": "x_linked",
                        "proband_role": proband_role.value,
                        "present_in_mother": in_mother,
                        "genotype_proband": genotype,
                    }
                    x_linked.append(v_copy)

        return x_linked

    @staticmethod
    def run_trio_analysis(
        proband_variants: List[Dict[str, Any]],
        mother_variants: List[Dict[str, Any]],
        father_variants: List[Dict[str, Any]],
        proband_role: FamilyRole = FamilyRole.PROBAND,
    ) -> Dict[str, List[Dict[str, Any]]]:
        results = {}

        results["autosomal_dominant_de_novo"] = InheritanceFilter.filter_autosomal_dominant_de_novo(
            proband_variants, mother_variants, father_variants,
        )
        results["autosomal_recessive_homozygous"] = InheritanceFilter.filter_autosomal_recessive_homozygous(
            proband_variants, mother_variants, father_variants,
        )
        results["compound_heterozygous"] = InheritanceFilter.filter_compound_heterozygous(
            proband_variants, mother_variants, father_variants,
        )
        results["x_linked"] = InheritanceFilter.filter_x_linked(
            proband_variants, mother_variants, father_variants, proband_role,
        )

        return results


@register_executor("family_analysis")
class FamilyAnalysisExecutor(BaseStepExecutor):

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        family_id = params.get("family_id", "")
        proband_id = params.get("proband_id", "")
        mother_id = params.get("mother_id", "")
        father_id = params.get("father_id", "")

        try:
            proband_variants = self._load_variants(proband_id)
            mother_variants = self._load_variants(mother_id) if mother_id else []
            father_variants = self._load_variants(father_id) if father_id else []

            inheritance_results = InheritanceFilter.run_trio_analysis(
                proband_variants, mother_variants, father_variants,
            )

            summary = {
                "family_id": family_id,
                "proband_id": proband_id,
                "mother_id": mother_id,
                "father_id": father_id,
            }
            for mode, variants in inheritance_results.items():
                summary[f"{mode}_count"] = len(variants)

            output_file = self.work_dir / f"family_{family_id}_inheritance.json"
            output_data = {"summary": summary, "results": inheritance_results}
            with open(output_file, "w") as f:
                json.dump(output_data, f, indent=2, default=str)

            total_candidates = sum(len(v) for v in inheritance_results.values())
            metrics = {
                "total_inheritance_candidates": total_candidates,
                **{f"{k}_count": len(v) for k, v in inheritance_results.items()},
            }

            return StepResult(
                success=True, step_id=step_id,
                output_files=[str(output_file)],
                metrics=metrics,
            )

        except Exception as e:
            logger.exception(f"Family analysis failed for family {family_id}")
            return StepResult(success=False, step_id=step_id, error_message=str(e))

    def _load_variants(self, sample_id: str) -> List[Dict[str, Any]]:
        variants_file = self.work_dir / f"{sample_id}_variants.json"
        if variants_file.exists():
            with open(variants_file) as f:
                return json.load(f)
        return []
