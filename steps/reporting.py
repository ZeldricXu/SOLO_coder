import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import logging
import json
from typing import List, Dict, Any, Optional
from pathlib import Path
from datetime import datetime

from pipeline.executor import (
    BaseStepExecutor,
    StepResult,
    register_executor,
)
from db.models import (
    ACMGClassification,
    VariantType,
    InheritanceMode,
    FamilyRole,
)

logger = logging.getLogger(__name__)


ACMG_DISPLAY = {
    ACMGClassification.PATHOGENIC: ("Pathogenic", "#FF0000", "#FFFFFF"),
    ACMGClassification.LIKELY_PATHOGENIC: ("Likely Pathogenic", "#FF8C00", "#FFFFFF"),
    ACMGClassification.UNCERTAIN_SIGNIFICANCE: ("VUS", "#FFD700", "#000000"),
    ACMGClassification.LIKELY_BENIGN: ("Likely Benign", "#90EE90", "#000000"),
    ACMGClassification.BENIGN: ("Benign", "#006400", "#FFFFFF"),
}

INHERITANCE_DISPLAY = {
    InheritanceMode.AUTOSOMAL_DOMINANT: ("Autosomal Dominant", "#2196F3"),
    InheritanceMode.AUTOSOMAL_RECESSIVE: ("Autosomal Recessive", "#4CAF50"),
    InheritanceMode.X_LINKED: ("X-Linked", "#9C27B0"),
    InheritanceMode.DE_NOVO: ("De Novo", "#F44336"),
    InheritanceMode.COMPOUND_HETEROZYGOUS: ("Compound Het", "#FF9800"),
    InheritanceMode.MITOCHONDRIAL: ("Mitochondrial", "#607D8B"),
}

FUSION_CLINICALLY_RELEVANT = {
    "ALK", "ROS1", "RET", "NTRK1", "NTRK2", "NTRK3",
    "FGFR1", "FGFR2", "FGFR3", "FGFR4",
    "BRAF", "MET", "EGFR", "ERBB2",
    "EWSR1", "FLI1", "TMPRSS2", "ETV6", "PAX3", "PAX7",
}


@register_executor("report_generation")
class ReportGenerationExecutor(BaseStepExecutor):

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id", "unknown")
        family_id = params.get("family_id", None)

        try:
            report_data = self._assemble_report_data(sample_id, params, family_id)

            json_path = self.work_dir / f"{sample_id}_report.json"
            with open(json_path, "w") as f:
                json.dump(report_data, f, indent=2, default=str)

            pdf_path = self.work_dir / f"{sample_id}_report.pdf"
            self._generate_pdf_report(report_data, pdf_path)

            return StepResult(
                success=True, step_id=step_id,
                output_files=[str(pdf_path), str(json_path)],
                metrics={
                    "total_variants": report_data.get("variant_summary", {}).get("total", 0),
                    "pathogenic_variants": report_data.get("variant_summary", {}).get("pathogenic", 0),
                    "fusions_reported": len(report_data.get("fusions", [])),
                    "report_pages": report_data.get("report_metadata", {}).get("total_pages", 1),
                },
            )

        except Exception as e:
            logger.exception(f"Report generation failed for {sample_id}")
            return StepResult(success=False, step_id=step_id, error_message=str(e))

    def _assemble_report_data(
        self, sample_id: str, params: Dict[str, Any], family_id: Optional[str]
    ) -> Dict[str, Any]:
        variants_json = params.get("variants_json", "")
        fusions_json = params.get("fusions_json", "")
        inheritance_json = params.get("inheritance_json", "")
        visualizations_json = params.get("visualizations_json", "")

        variants = self._load_json(variants_json)
        fusions = self._load_json(fusions_json)
        inheritance = self._load_json(inheritance_json)
        visualizations = self._load_json(visualizations_json)

        classified_variants = self._classify_variants(variants)

        family_info = None
        if family_id and inheritance:
            family_info = self._build_family_info(family_id, inheritance, params)

        report_data = {
            "report_metadata": {
                "report_id": f"RPT-{sample_id}-{datetime.now().strftime('%Y%m%d%H%M%S')}",
                "generated_at": datetime.now().isoformat(),
                "pipeline_version": "2.0.0",
                "reference_genome": "GRCh38/hg38",
                "sample_id": sample_id,
                "family_id": family_id,
            },
            "sample_info": {
                "sample_id": sample_id,
                "sample_type": params.get("sample_type", "WES"),
                "patient_id": params.get("patient_id", ""),
                "clinical_diagnosis": params.get("clinical_diagnosis", ""),
                "referring_physician": params.get("referring_physician", ""),
                "institution": params.get("institution", ""),
            },
            "qc_summary": params.get("qc_metrics", {}),
            "variant_summary": {
                "total": len(variants),
                "pathogenic": sum(1 for v in variants if v.get("acmg_classification") == "P"),
                "likely_pathogenic": sum(1 for v in variants if v.get("acmg_classification") == "LP"),
                "vus": sum(1 for v in variants if v.get("acmg_classification") == "VUS"),
                "likely_benign": sum(1 for v in variants if v.get("acmg_classification") == "LB"),
                "benign": sum(1 for v in variants if v.get("acmg_classification") == "B"),
            },
            "positive_variants": classified_variants["positive"],
            "secondary_findings": classified_variants["secondary"],
            "fusions": self._format_fusions_for_report(fusions),
            "family_analysis": family_info,
            "visualizations": visualizations if isinstance(visualizations, list) else [],
            "disclaimer": (
                "This report is generated by an automated bioinformatics pipeline and is intended "
                "for research and clinical decision support purposes only. Results should be "
                "validated by qualified laboratory personnel before clinical use. Classification "
                "of variants follows ACMG/AMP guidelines (Richards et al., 2015) as implemented "
                "by the automated classification algorithm."
            ),
        }

        return report_data

    def _load_json(self, path: str) -> Any:
        if not path:
            return None
        try:
            p = Path(path)
            if p.exists():
                with open(p) as f:
                    return json.load(f)
        except Exception as e:
            logger.warning(f"Failed to load JSON from {path}: {e}")
        return None

    def _classify_variants(self, variants: Optional[List[Dict]]) -> Dict[str, List[Dict]]:
        positive = []
        secondary = []

        if not variants:
            return {"positive": positive, "secondary": secondary}

        for v in variants:
            acmg = v.get("acmg_classification", "")
            if acmg in ("P", "LP"):
                positive.append(v)
            if v.get("is_secondary_finding") and acmg in ("P", "LP"):
                secondary.append(v)

        return {"positive": positive, "secondary": secondary}

    def _format_fusions_for_report(self, fusions: Any) -> List[Dict[str, Any]]:
        if not fusions:
            return []

        if isinstance(fusions, dict):
            if "results" in fusions:
                fusion_list = []
                for mode_variants in fusions["results"].values():
                    fusion_list.extend(
                        v for v in mode_variants
                        if v.get("variant_type") == "FUSION"
                    )
                return fusion_list
            return []

        if isinstance(fusions, list):
            return [
                f for f in fusions
                if isinstance(f, dict) and f.get("variant_type") in ("FUSION", "SV", "BND")
            ]

        return []

    def _build_family_info(
        self, family_id: str, inheritance: Dict, params: Dict[str, Any]
    ) -> Dict[str, Any]:
        results = inheritance.get("results", {})
        summary = inheritance.get("summary", {})

        grouped_variants = {}
        for mode, variants in results.items():
            mode_display = INHERITANCE_DISPLAY.get(
                InheritanceMode(mode.replace("_count", "").replace("autosomal_dominant_", "de_novo")),
                (mode, "#999999"),
            )
            grouped_variants[mode] = {
                "display_name": mode_display[0],
                "color": mode_display[1],
                "variants": variants,
                "count": len(variants),
            }

        family_members = []
        for role_key in ["proband_id", "mother_id", "father_id"]:
            if role_key in params:
                role = role_key.replace("_id", "").replace("proband", "Proband")
                family_members.append({
                    "sample_id": params[role_key],
                    "role": role,
                })

        return {
            "family_id": family_id,
            "members": family_members,
            "grouped_variants": grouped_variants,
            "summary": summary,
        }

    def _generate_pdf_report(self, report_data: Dict[str, Any], pdf_path: Path) -> None:
        try:
            from reportlab.lib.pagesizes import A4, landscape
            from reportlab.lib import colors
            from reportlab.lib.units import inch, cm
            from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
            from reportlab.platypus import (
                SimpleDocTemplate, Table, TableStyle, Paragraph,
                Spacer, Image, PageBreak, HRFlowable,
            )
            from reportlab.lib.enums import TA_CENTER, TA_LEFT, TA_RIGHT
        except ImportError:
            logger.warning("ReportLab not available, generating text report instead")
            self._generate_text_report(report_data, pdf_path)
            return

        doc = SimpleDocTemplate(
            str(pdf_path),
            pagesize=landscape(A4),
            leftMargin=1.5 * cm,
            rightMargin=1.5 * cm,
            topMargin=2 * cm,
            bottomMargin=2 * cm,
        )

        styles = getSampleStyleSheet()
        title_style = ParagraphStyle(
            "ReportTitle", parent=styles["Title"],
            fontSize=18, spaceAfter=12, alignment=TA_CENTER,
        )
        heading_style = ParagraphStyle(
            "SectionHeading", parent=styles["Heading2"],
            fontSize=14, spaceAfter=8, spaceBefore=12,
        )
        sub_heading_style = ParagraphStyle(
            "SubHeading", parent=styles["Heading3"],
            fontSize=11, spaceAfter=6, spaceBefore=8,
        )
        body_style = styles["Normal"]
        small_style = ParagraphStyle("Small", parent=body_style, fontSize=8)

        story = []

        story.append(Paragraph("Genomic Variant Analysis Report", title_style))
        story.append(Spacer(1, 0.3 * cm))
        story.append(HRFlowable(width="100%", thickness=2, color=colors.HexColor("#2196F3")))
        story.append(Spacer(1, 0.3 * cm))

        meta = report_data.get("report_metadata", {})
        story.append(Paragraph(f"Report ID: {meta.get('report_id', 'N/A')}", small_style))
        story.append(Paragraph(f"Generated: {meta.get('generated_at', 'N/A')}", small_style))
        story.append(Paragraph(f"Pipeline Version: {meta.get('pipeline_version', 'N/A')}", small_style))
        story.append(Spacer(1, 0.5 * cm))

        story.append(Paragraph("Sample Information", heading_style))
        sample_info = report_data.get("sample_info", {})
        sample_table_data = [
            ["Field", "Value"],
            ["Sample ID", sample_info.get("sample_id", "N/A")],
            ["Sample Type", sample_info.get("sample_type", "N/A")],
            ["Patient ID", sample_info.get("patient_id", "N/A")],
            ["Diagnosis", sample_info.get("clinical_diagnosis", "N/A")],
            ["Referring Physician", sample_info.get("referring_physician", "N/A")],
            ["Institution", sample_info.get("institution", "N/A")],
        ]
        sample_table = Table(sample_table_data, colWidths=[4 * cm, 18 * cm])
        sample_table.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#2196F3")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
            ("FONTSIZE", (0, 0), (-1, -1), 9),
            ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F5F5F5")]),
        ]))
        story.append(sample_table)
        story.append(Spacer(1, 0.5 * cm))

        vs = report_data.get("variant_summary", {})
        story.append(Paragraph("Variant Summary", heading_style))
        summary_data = [
            ["Category", "Count"],
            ["Pathogenic (P)", str(vs.get("pathogenic", 0))],
            ["Likely Pathogenic (LP)", str(vs.get("likely_pathogenic", 0))],
            ["VUS", str(vs.get("vus", 0))],
            ["Likely Benign (LB)", str(vs.get("likely_benign", 0))],
            ["Benign (B)", str(vs.get("benign", 0))],
            ["Total", str(vs.get("total", 0))],
        ]
        summary_table = Table(summary_data, colWidths=[8 * cm, 4 * cm])
        summary_table.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#4CAF50")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
            ("FONTSIZE", (0, 0), (-1, -1), 9),
            ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
            ("BACKGROUND", (0, 1), (-1, 1), colors.HexColor("#FFCCCC")),
            ("BACKGROUND", (0, 2), (-1, 2), colors.HexColor("#FFE0B2")),
        ]))
        story.append(summary_table)
        story.append(Spacer(1, 0.5 * cm))

        positive = report_data.get("positive_variants", [])
        if positive:
            story.append(Paragraph("Positive Findings (Pathogenic/Likely Pathogenic)", heading_style))
            self._add_variant_table(story, positive, styles)

        fusions = report_data.get("fusions", [])
        if fusions:
            story.append(PageBreak())
            story.append(Paragraph("Fusion Gene Findings", heading_style))
            self._add_fusion_table(story, fusions, styles)

        family = report_data.get("family_analysis")
        if family:
            story.append(PageBreak())
            story.append(Paragraph("Family Analysis (Trio)", heading_style))

            members = family.get("members", [])
            if members:
                member_data = [["Role", "Sample ID"]]
                for m in members:
                    member_data.append([m.get("role", ""), m.get("sample_id", "")])
                member_table = Table(member_data, colWidths=[6 * cm, 10 * cm])
                member_table.setStyle(TableStyle([
                    ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#9C27B0")),
                    ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
                    ("FONTSIZE", (0, 0), (-1, -1), 9),
                    ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
                ]))
                story.append(member_table)
                story.append(Spacer(1, 0.5 * cm))

            grouped = family.get("grouped_variants", {})
            for mode, info in grouped.items():
                display_name = info.get("display_name", mode)
                color_hex = info.get("color", "#999999")
                story.append(Paragraph(
                    f"{display_name} Variants ({info.get('count', 0)})",
                    sub_heading_style,
                ))
                variants = info.get("variants", [])
                if variants:
                    self._add_variant_table(story, variants, styles)
                else:
                    story.append(Paragraph("No variants found for this inheritance mode.", body_style))
                story.append(Spacer(1, 0.3 * cm))

        viz_list = report_data.get("visualizations", [])
        if viz_list:
            story.append(PageBreak())
            story.append(Paragraph("Variant Visualizations (Pileup Images)", heading_style))
            for viz in viz_list[:10]:
                img_path = viz.get("image_path", "")
                if img_path and Path(img_path).exists():
                    try:
                        img = Image(img_path, width=20 * cm, height=10 * cm)
                        story.append(img)
                        gene = viz.get("gene", "")
                        chrom = viz.get("chromosome", "")
                        pos = viz.get("position", "")
                        story.append(Paragraph(
                            f"{gene} {chrom}:{pos}",
                            ParagraphStyle("Caption", parent=small_style, alignment=TA_CENTER),
                        ))
                        story.append(Spacer(1, 0.3 * cm))
                    except Exception as e:
                        logger.warning(f"Failed to add image {img_path}: {e}")

        story.append(PageBreak())
        story.append(Paragraph("Disclaimer", heading_style))
        story.append(Paragraph(report_data.get("disclaimer", ""), body_style))

        doc.build(story)
        logger.info(f"Generated PDF report: {pdf_path}")

    def _add_variant_table(
        self, story: list, variants: List[Dict], styles
    ) -> None:
        header = ["Gene", "Variant", "Genotype", "ACMG", "gnomAD AF", "ClinVar", "Inheritance"]
        table_data = [header]

        for v in variants[:50]:
            gene = v.get("gene", "-")
            chrom = v.get("chromosome", "")
            pos = v.get("position", "")
            ref = v.get("ref", "")
            alt = v.get("alt", "")
            variant_str = f"{chrom}:{pos}{ref}>{alt}" if chrom else "-"
            gt = v.get("genotype", "-")
            acmg = v.get("acmg_classification", "-")

            gnomad_af = v.get("gnomad_af")
            af_str = f"{gnomad_af:.6f}" if gnomad_af is not None else "-"
            clinvar = v.get("clinvar_clinsig", "-")
            inheritance = v.get("inheritance_mode", "-")

            table_data.append([gene, variant_str, gt, acmg, af_str, clinvar, inheritance])

        col_widths = [2.5 * cm, 8 * cm, 2.5 * cm, 2 * cm, 3 * cm, 4 * cm, 3 * cm]
        table = Table(table_data, colWidths=col_widths, repeatRows=1)

        style_commands = [
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#37474F")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
            ("FONTSIZE", (0, 0), (-1, -1), 7),
            ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#FAFAFA")]),
        ]

        for i, row in enumerate(table_data[1:], start=1):
            acmg = row[3]
            if acmg == "P":
                style_commands.append(("BACKGROUND", (3, i), (3, i), colors.HexColor("#FF0000")))
                style_commands.append(("TEXTCOLOR", (3, i), (3, i), colors.white))
            elif acmg == "LP":
                style_commands.append(("BACKGROUND", (3, i), (3, i), colors.HexColor("#FF8C00")))
                style_commands.append(("TEXTCOLOR", (3, i), (3, i), colors.white))
            elif acmg == "VUS":
                style_commands.append(("BACKGROUND", (3, i), (3, i), colors.HexColor("#FFD700")))

        table.setStyle(TableStyle(style_commands))
        story.append(table)
        story.append(Spacer(1, 0.5 * cm))

    def _add_fusion_table(
        self, story: list, fusions: List[Dict], styles
    ) -> None:
        header = ["5' Gene", "3' Gene", "Type", "Junction Reads", "Spanning Reads", "Frame", "Targeted Drugs"]
        table_data = [header]

        for f in fusions[:30]:
            gene5 = f.get("gene", "-")
            gene3 = f.get("fusion_partner_gene", "-")
            ftype = f.get("fusion_fusion_type", "-")
            junction = str(f.get("fusion_junction_reads", "-"))
            spanning = str(f.get("fusion_spanning_reads", "-"))
            frame = f.get("fusion_frame", "-")

            drugs = f.get("targeted_drugs", [])
            if drugs:
                drug_names = ", ".join(d.get("drug_name", "") for d in drugs[:3])
                if len(drugs) > 3:
                    drug_names += f" (+{len(drugs)-3} more)"
            else:
                drug_names = "None"

            table_data.append([gene5, gene3, ftype, junction, spanning, frame, drug_names])

        col_widths = [3 * cm, 3 * cm, 3 * cm, 3 * cm, 3 * cm, 2.5 * cm, 7 * cm]
        table = Table(table_data, colWidths=col_widths, repeatRows=1)
        table.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#E91E63")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
            ("FONTSIZE", (0, 0), (-1, -1), 7),
            ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#FFF3E0")]),
        ]))
        story.append(table)
        story.append(Spacer(1, 0.5 * cm))

        for f in fusions[:10]:
            drugs = f.get("targeted_drugs", [])
            if drugs:
                gene5 = f.get("gene", "")
                gene3 = f.get("fusion_partner_gene", "")
                story.append(Paragraph(
                    f"Drug Recommendations for {gene5}-{gene3}:",
                    ParagraphStyle("DrugHeading", parent=styles.get("Normal", None) or styles["Normal"],
                                   fontSize=9, spaceBefore=6, fontName="Helvetica-Bold"),
                ))
                drug_header = ["Drug", "Source", "Evidence Level", "Cancer Type", "Interaction"]
                drug_data = [drug_header]
                for d in drugs:
                    drug_data.append([
                        d.get("drug_name", "-"),
                        d.get("source", "-"),
                        d.get("evidence_level", "-"),
                        d.get("cancer_type", "-"),
                        d.get("interaction_type", "-"),
                    ])
                drug_table = Table(drug_data, colWidths=[4 * cm, 2.5 * cm, 3 * cm, 4 * cm, 3 * cm])
                drug_table.setStyle(TableStyle([
                    ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#7B1FA2")),
                    ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
                    ("FONTSIZE", (0, 0), (-1, -1), 7),
                    ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
                ]))
                story.append(drug_table)
                story.append(Spacer(1, 0.3 * cm))

    def _generate_text_report(self, report_data: Dict[str, Any], output_path: Path) -> None:
        with open(output_path.with_suffix(".txt"), "w") as f:
            f.write("=" * 80 + "\n")
            f.write("GENOMIC VARIANT ANALYSIS REPORT\n")
            f.write("=" * 80 + "\n\n")

            meta = report_data.get("report_metadata", {})
            f.write(f"Report ID: {meta.get('report_id', 'N/A')}\n")
            f.write(f"Generated: {meta.get('generated_at', 'N/A')}\n\n")

            sample = report_data.get("sample_info", {})
            f.write(f"Sample: {sample.get('sample_id', 'N/A')}\n")
            f.write(f"Diagnosis: {sample.get('clinical_diagnosis', 'N/A')}\n\n")

            vs = report_data.get("variant_summary", {})
            f.write("Variant Summary:\n")
            for key in ["pathogenic", "likely_pathogenic", "vus", "likely_benign", "benign", "total"]:
                f.write(f"  {key}: {vs.get(key, 0)}\n")
            f.write("\n")

            for v in report_data.get("positive_variants", []):
                f.write(f"  {v.get('gene', '')} {v.get('chromosome', '')}:{v.get('position', '')} "
                        f"{v.get('ref', '')}>{v.get('alt', '')} [{v.get('acmg_classification', '')}]\n")

            fusions = report_data.get("fusions", [])
            if fusions:
                f.write("\nFusion Genes:\n")
                for fu in fusions:
                    f.write(f"  {fu.get('gene', '')}-{fu.get('fusion_partner_gene', '')} "
                            f"({fu.get('fusion_fusion_type', '')})\n")
                    for d in fu.get("targeted_drugs", []):
                        f.write(f"    -> {d.get('drug_name', '')} [{d.get('source', '')}: {d.get('evidence_level', '')}]\n")
