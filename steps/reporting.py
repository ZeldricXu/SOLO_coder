import logging
import json
from pathlib import Path
from typing import List, Dict, Any
from datetime import datetime

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import cm, mm
from reportlab.platypus import (
    SimpleDocTemplate,
    Paragraph,
    Spacer,
    Table,
    TableStyle,
    PageBreak,
    KeepTogether,
)
from reportlab.lib.enums import TA_CENTER, TA_LEFT

from pipeline.executor import BaseStepExecutor, StepResult, register_executor, StepExecutionError
from config.settings import settings
from config.pipeline_config import PipelineStepType
from storage.repository import (
    SampleRepository,
    VariantRepository,
    QCMetricRepository,
    TaskRepository,
)
from db.models import ACMGClassification, SampleStatus

logger = logging.getLogger(__name__)


@register_executor(PipelineStepType.REPORT_GENERATION)
class ReportGenerationExecutor(BaseStepExecutor):
    def execute(
        self,
        step_id: str,
        params: Dict[str, Any],
        input_files: List[str],
    ) -> StepResult:
        start_time = datetime.now()
        sample_id = params.get("sample_id", step_id.split("_")[0])
        task_id = params.get("task_id", step_id.rsplit("_", 1)[0])

        try:
            sample = SampleRepository.get_by_id(sample_id)
            if not sample:
                raise StepExecutionError(f"Sample {sample_id} not found")

            task = TaskRepository.get_by_id(task_id)
            if not task:
                logger.warning(f"Task {task_id} not found")

            pdf_report = self.work_dir / f"{sample_id}_report.pdf"
            json_report = self.work_dir / f"{sample_id}_report.json"

            report_data = self._generate_report_data(sample, task)

            with open(json_report, "w") as f:
                json.dump(report_data, f, indent=2, default=str)

            self._generate_pdf_report(report_data, pdf_report)

            report_object = self._archive_report(sample_id, pdf_report, json_report)

            SampleRepository.update_status(sample_id, SampleStatus.REPORTED)
            SampleRepository.update_fastq_paths(sample_id, report_object or "", report_object or "")

            output_files = [str(pdf_report), str(json_report)]

            TaskRepository.add_output_files(task_id, output_files)
            TaskRepository.update_result_summary(task_id, {
                "report_generated": True,
                "report_path": str(pdf_report),
                "report_json": str(json_report),
                "pathogenic_variants": len(report_data["pathogenic_variants"]),
                "secondary_findings": len(report_data["secondary_findings"]),
                "total_variants": report_data["summary"]["total_variants"],
            })

            metrics = {
                "pathogenic_variants": len(report_data["pathogenic_variants"]),
                "likely_pathogenic_variants": len(report_data["likely_pathogenic_variants"]),
                "vus": len(report_data["vus"]),
                "secondary_findings": len(report_data["secondary_findings"]),
                "report_generated": True,
            }

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
            logger.exception("Report generation failed")
            return StepResult(
                success=False,
                step_id=step_id,
                error_message=str(e),
                stdout="",
                stderr="",
                duration_seconds=(datetime.now() - start_time).total_seconds(),
            )

    def _generate_report_data(self, sample, task) -> Dict[str, Any]:
        sample_db_id = sample.id

        pathogenic_variants = VariantRepository.get_pathogenic_variants(sample_db_id)
        secondary_findings = VariantRepository.get_secondary_findings(sample_db_id)
        qc_metrics = QCMetricRepository.get_by_sample(sample_db_id)
        task_steps = TaskRepository.get_steps(task.task_id) if task else []

        p_variants = [
            v for v in pathogenic_variants
            if v.acmg_classification == ACMGClassification.PATHOGENIC
        ]
        lp_variants = [
            v for v in pathogenic_variants
            if v.acmg_classification == ACMGClassification.LIKELY_PATHOGENIC
        ]
        vus_variants = VariantRepository.get_by_sample(
            sample_db_id,
            classification=ACMGClassification.UNCERTAIN_SIGNIFICANCE,
        )

        qc_summary = {}
        for qc in qc_metrics:
            if qc.metrics_json:
                qc_summary[qc.step_type] = qc.metrics_json

        latest_qc = qc_metrics[-1] if qc_metrics else None

        total_variants = sample.total_variants or len(pathogenic_variants) + len(vus_variants)

        return {
            "report_id": f"REP-{sample.sample_id}-{datetime.now().strftime('%Y%m%d%H%M%S')}",
            "report_date": datetime.now().isoformat(),
            "pipeline_version": task.pipeline_version if task else "1.0.0",
            "reference_genome": task.reference_genome if task else "hg38",
            "sample": {
                "sample_id": sample.sample_id,
                "patient_id": sample.patient_id,
                "sample_type": sample.sample_type.value if sample.sample_type else "Unknown",
                "sequencing_platform": sample.sequencing_platform,
                "library_id": sample.library_id,
                "read_length": sample.read_length,
                "phenotype_hpo": sample.phenotype_hpo,
                "clinical_diagnosis": sample.clinical_diagnosis,
                "referring_physician": sample.referring_physician,
                "institution": sample.institution,
                "received_at": sample.received_at.isoformat() if sample.received_at else None,
                "analysis_started_at": sample.analysis_started_at.isoformat() if sample.analysis_started_at else None,
                "analysis_completed_at": sample.analysis_completed_at.isoformat() if sample.analysis_completed_at else None,
            },
            "summary": {
                "total_variants": total_variants,
                "pathogenic": len(p_variants),
                "likely_pathogenic": len(lp_variants),
                "vus": len(vus_variants),
                "likely_benign": 0,
                "benign": 0,
                "secondary_findings": len(secondary_findings),
                "pipeline_steps": [
                    {
                        "step_id": step.step_id,
                        "step_name": step.step_name,
                        "status": step.status.value,
                        "duration_seconds": step.duration_seconds,
                        "retry_count": step.retry_count,
                    }
                    for step in task_steps
                ],
            },
            "qc_metrics": self._format_qc_metrics(qc_summary, latest_qc),
            "pathogenic_variants": [
                self._format_variant(v) for v in p_variants
            ],
            "likely_pathogenic_variants": [
                self._format_variant(v) for v in lp_variants
            ],
            "vus": [
                self._format_variant(v) for v in vus_variants[:50]
            ],
            "secondary_findings": [
                self._format_variant(v) for v in secondary_findings
            ],
            "acmg_secondary_finding_genes": VariantRepository.get_secondary_finding_genes(),
            "disclaimer": (
                "This report is for clinical research use only. "
                "All findings must be confirmed by CLIA-certified diagnostic testing "
                "before any clinical decisions are made. "
                "This analysis was performed using an automated bioinformatics pipeline. "
                "The results should be interpreted by a qualified clinical geneticist."
            ),
        }

    def _format_variant(self, variant) -> Dict[str, Any]:
        return {
            "variant_id": variant.variant_id,
            "chromosome": variant.chromosome,
            "position": variant.position,
            "ref": variant.ref,
            "alt": variant.alt,
            "variant_type": variant.variant_type,
            "genotype": variant.genotype,
            "genotype_quality": variant.genotype_quality,
            "depth": variant.depth,
            "allele_depth": variant.allele_depth,
            "allele_frequency": variant.allele_frequency,
            "gene": variant.gene,
            "transcript": variant.transcript,
            "hgvsc": variant.hgvsc,
            "hgvsp": variant.hgvsp,
            "consequence": variant.consequence,
            "impact": variant.impact,
            "population_frequencies": {
                "gnomAD_AF": variant.gnomad_af,
                "1000G_AF": variant.thousandg_af,
                "ExAC_AF": variant.exac_af,
            },
            "in_silico_predictions": {
                "CADD": variant.cadd_score,
                "REVEL": variant.revel_score,
                "SIFT": variant.sift_score,
                "PolyPhen2": variant.polyphen2_score,
            },
            "clinvar": {
                "clinvar_id": variant.clinvar_id,
                "clinical_significance": variant.clinvar_clinsig,
                "review_status": variant.clinvar_review_status,
            },
            "acmg_classification": variant.acmg_classification.value if variant.acmg_classification else None,
            "acmg_criteria": variant.acmg_criteria,
            "acmg_score": variant.acmg_score,
            "is_secondary_finding": variant.is_secondary_finding,
        }

    def _format_qc_metrics(
        self,
        qc_summary: Dict[str, Any],
        latest_qc,
    ) -> Dict[str, Any]:
        fastp_metrics = qc_summary.get("fastp", {})
        alignment_metrics = qc_summary.get("alignment", {})
        variant_calling_metrics = qc_summary.get("variant_calling", {})

        return {
            "sequencing": {
                "total_reads_raw": fastp_metrics.get("total_reads_before", 0),
                "total_reads_after_filtering": fastp_metrics.get("total_reads_after", 0),
                "reads_pass_rate": fastp_metrics.get("reads_pass_rate", 0),
                "total_bases_raw": fastp_metrics.get("total_bases_before", 0),
                "total_bases_after_filtering": fastp_metrics.get("total_bases_after", 0),
                "bases_pass_rate": fastp_metrics.get("bases_pass_rate", 0),
                "q20_rate_before": fastp_metrics.get("q20_rate_before", 0),
                "q20_rate_after": fastp_metrics.get("q20_rate_after", 0),
                "q30_rate_before": fastp_metrics.get("q30_rate_before", 0),
                "q30_rate_after": fastp_metrics.get("q30_rate_after", 0),
                "gc_content": fastp_metrics.get("gc_content_after", 0),
                "adapter_content": fastp_metrics.get("adapter_content", 0),
                "duplication_rate": fastp_metrics.get("duplication_rate", 0),
            },
            "alignment": {
                "total_reads": alignment_metrics.get("total_reads", 0),
                "mapped_reads": alignment_metrics.get("mapped_reads", 0),
                "mapping_rate": alignment_metrics.get("mapping_rate", 0),
                "properly_paired": alignment_metrics.get("properly_paired", 0),
                "proper_pair_rate": alignment_metrics.get("proper_pair_rate", 0),
                "mean_insert_size": latest_qc.mean_insert_size if latest_qc else None,
                "singletons": alignment_metrics.get("singletons", 0),
                "singleton_rate": alignment_metrics.get("singleton_rate", 0),
            },
            "variant_calling": {
                "total_variants": variant_calling_metrics.get("haplotype_caller_total_variants", 0),
                "snvs": variant_calling_metrics.get("haplotype_caller_snvs", 0),
                "indels": variant_calling_metrics.get("haplotype_caller_indels", 0),
                "ti_tv_ratio": variant_calling_metrics.get("haplotype_caller_ti_tv_ratio", 0),
                "het_hom_ratio": variant_calling_metrics.get("haplotype_caller_het_hom_ratio", 0),
                "heterozygous": variant_calling_metrics.get("haplotype_caller_heterozygous", 0),
                "homozygous": variant_calling_metrics.get("haplotype_caller_homozygous", 0),
            },
            "coverage": {
                "mean_coverage": latest_qc.mean_coverage if latest_qc else None,
                "coverage_1x": latest_qc.coverage_1x if latest_qc else None,
                "coverage_10x": latest_qc.coverage_10x if latest_qc else None,
                "coverage_20x": latest_qc.coverage_20x if latest_qc else None,
                "coverage_30x": latest_qc.coverage_30x if latest_qc else None,
                "on_target_rate": latest_qc.on_target_rate if latest_qc else None,
            },
        }

    def _generate_pdf_report(self, report_data: Dict[str, Any], pdf_path: Path) -> None:
        doc = SimpleDocTemplate(
            str(pdf_path),
            pagesize=landscape(A4),
            rightMargin=2 * cm,
            leftMargin=2 * cm,
            topMargin=2 * cm,
            bottomMargin=2 * cm,
            title=f"Genomic Analysis Report - {report_data['sample']['sample_id']}",
            author="Genome Analysis Pipeline",
        )

        styles = getSampleStyleSheet()
        title_style = ParagraphStyle(
            "CustomTitle",
            parent=styles["Heading1"],
            fontSize=20,
            textColor=colors.HexColor("#1a5276"),
            alignment=TA_CENTER,
            spaceAfter=6,
        )
        subtitle_style = ParagraphStyle(
            "CustomSubtitle",
            parent=styles["Heading2"],
            fontSize=14,
            textColor=colors.HexColor("#2874a6"),
            alignment=TA_CENTER,
            spaceAfter=12,
        )
        section_style = ParagraphStyle(
            "SectionHeader",
            parent=styles["Heading2"],
            fontSize=14,
            textColor=colors.white,
            backColor=colors.HexColor("#2874a6"),
            borderPadding=(6, 10, 6, 10),
            spaceAfter=10,
        )
        normal_style = styles["Normal"]
        normal_style.fontSize = 9

        story = []

        story.append(Paragraph("Clinical Genomic Analysis Report", title_style))
        story.append(Paragraph(
            f"Sample: {report_data['sample']['sample_id']} | "
            f"Report Date: {report_data['report_date'][:10]}",
            subtitle_style,
        ))
        story.append(Spacer(1, 0.3 * cm))

        story.append(Paragraph("Patient and Sample Information", section_style))
        sample_info = [
            ["Sample ID", report_data["sample"]["sample_id"]],
            ["Patient ID", report_data["sample"]["patient_id"] or "N/A"],
            ["Sample Type", report_data["sample"]["sample_type"]],
            ["Sequencing Platform", report_data["sample"]["sequencing_platform"] or "N/A"],
            ["Library ID", report_data["sample"]["library_id"] or "N/A"],
            ["Read Length", f"{report_data['sample']['read_length']} bp" if report_data["sample"]["read_length"] else "N/A"],
            ["Clinical Diagnosis", report_data["sample"]["clinical_diagnosis"] or "N/A"],
            ["Referring Physician", report_data["sample"]["referring_physician"] or "N/A"],
            ["Institution", report_data["sample"]["institution"] or "N/A"],
            ["Phenotype (HPO)", ", ".join(report_data["sample"]["phenotype_hpo"]) if report_data["sample"]["phenotype_hpo"] else "N/A"],
            ["Received Date", report_data["sample"]["received_at"][:10] if report_data["sample"]["received_at"] else "N/A"],
        ]
        sample_table = Table(sample_info, colWidths=[5 * cm, 10 * cm])
        sample_table.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (0, -1), colors.HexColor("#ebf5fb")),
            ("FONTSIZE", (0, 0), (-1, -1), 9),
            ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("LEFTPADDING", (0, 0), (-1, -1), 6),
            ("RIGHTPADDING", (0, 0), (-1, -1), 6),
            ("TOPPADDING", (0, 0), (-1, -1), 4),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ]))
        story.append(sample_table)
        story.append(Spacer(1, 0.5 * cm))

        story.append(Paragraph("Analysis Summary", section_style))
        summary_data = [
            ["Total Variants Analyzed", str(report_data["summary"]["total_variants"])],
            ["Pathogenic", str(report_data["summary"]["pathogenic"])],
            ["Likely Pathogenic", str(report_data["summary"]["likely_pathogenic"])],
            ["Variant of Uncertain Significance (VUS)", str(report_data["summary"]["vus"])],
            ["Secondary Findings", str(report_data["summary"]["secondary_findings"])],
            ["Pipeline Version", report_data["pipeline_version"]],
            ["Reference Genome", report_data["reference_genome"]],
        ]
        summary_table = Table(summary_data, colWidths=[8 * cm, 6 * cm])
        summary_table.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (0, -1), colors.HexColor("#f4ecf7")),
            ("FONTSIZE", (0, 0), (-1, -1), 9),
            ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("LEFTPADDING", (0, 0), (-1, -1), 6),
            ("RIGHTPADDING", (0, 0), (-1, -1), 6),
            ("TOPPADDING", (0, 0), (-1, -1), 4),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ]))
        story.append(summary_table)
        story.append(Spacer(1, 0.5 * cm))

        story.append(Paragraph("Quality Control Metrics", section_style))
        qc = report_data["qc_metrics"]

        qc_sequencing_data = [
            ["Metric", "Before Filtering", "After Filtering"],
            ["Total Reads",
             f"{qc['sequencing']['total_reads_raw']:,}",
             f"{qc['sequencing']['total_reads_after_filtering']:,}"],
            ["Reads Pass Rate",
             "N/A",
             f"{qc['sequencing']['reads_pass_rate'] * 100:.1f}%"],
            ["Q20 Rate",
             f"{qc['sequencing']['q20_rate_before'] * 100:.1f}%",
             f"{qc['sequencing']['q20_rate_after'] * 100:.1f}%"],
            ["Q30 Rate",
             f"{qc['sequencing']['q30_rate_before'] * 100:.1f}%",
             f"{qc['sequencing']['q30_rate_after'] * 100:.1f}%"],
            ["GC Content",
             "N/A",
             f"{qc['sequencing']['gc_content'] * 100:.1f}%"],
        ]
        qc_seq_table = Table(qc_sequencing_data, colWidths=[5 * cm, 4.5 * cm, 4.5 * cm])
        qc_seq_table.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#2874a6")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
            ("FONTSIZE", (0, 0), (-1, -1), 8),
            ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
            ("ALIGN", (1, 0), (-1, -1), "CENTER"),
            ("LEFTPADDING", (0, 0), (-1, -1), 4),
            ("RIGHTPADDING", (0, 0), (-1, -1), 4),
            ("TOPPADDING", (0, 0), (-1, -1), 3),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
        ]))
        story.append(Paragraph("<b>Sequencing QC</b>", normal_style))
        story.append(qc_seq_table)
        story.append(Spacer(1, 0.3 * cm))

        qc_alignment_data = [
            ["Metric", "Value"],
            ["Mapping Rate", f"{qc['alignment']['mapping_rate'] * 100:.1f}%"],
            ["Properly Paired Rate", f"{qc['alignment']['proper_pair_rate'] * 100:.1f}%"],
            ["Singleton Rate", f"{qc['alignment']['singleton_rate'] * 100:.2f}%"],
        ]
        qc_align_table = Table(qc_alignment_data, colWidths=[5 * cm, 4 * cm])
        qc_align_table.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1e8449")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
            ("FONTSIZE", (0, 0), (-1, -1), 8),
            ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
            ("ALIGN", (1, 0), (-1, -1), "CENTER"),
            ("LEFTPADDING", (0, 0), (-1, -1), 4),
            ("RIGHTPADDING", (0, 0), (-1, -1), 4),
            ("TOPPADDING", (0, 0), (-1, -1), 3),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
        ]))
        story.append(Paragraph("<b>Alignment QC</b>", normal_style))
        story.append(qc_align_table)
        story.append(Spacer(1, 0.3 * cm))

        qc_variant_data = [
            ["Metric", "Value"],
            ["Total Variants", f"{qc['variant_calling']['total_variants']:,}"],
            ["SNVs", f"{qc['variant_calling']['snvs']:,}"],
            ["Indels", f"{qc['variant_calling']['indels']:,}"],
            ["Ti/Tv Ratio", f"{qc['variant_calling']['ti_tv_ratio']:.2f}"],
            ["Het/Hom Ratio", f"{qc['variant_calling']['het_hom_ratio']:.2f}"],
        ]
        qc_var_table = Table(qc_variant_data, colWidths=[5 * cm, 4 * cm])
        qc_var_table.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#d35400")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
            ("FONTSIZE", (0, 0), (-1, -1), 8),
            ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
            ("ALIGN", (1, 0), (-1, -1), "CENTER"),
            ("LEFTPADDING", (0, 0), (-1, -1), 4),
            ("RIGHTPADDING", (0, 0), (-1, -1), 4),
            ("TOPPADDING", (0, 0), (-1, -1), 3),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
        ]))
        story.append(Paragraph("<b>Variant Calling QC</b>", normal_style))
        story.append(qc_var_table)

        story.append(PageBreak())

        if report_data["pathogenic_variants"]:
            story.append(Paragraph("Pathogenic Variants", section_style))
            self._add_variant_table(story, report_data["pathogenic_variants"], normal_style)
            story.append(Spacer(1, 0.5 * cm))

        if report_data["likely_pathogenic_variants"]:
            story.append(Paragraph("Likely Pathogenic Variants", section_style))
            self._add_variant_table(story, report_data["likely_pathogenic_variants"], normal_style)
            story.append(Spacer(1, 0.5 * cm))

        if report_data["secondary_findings"]:
            story.append(Paragraph("Secondary Findings (ACMG SF v3.0)", section_style))
            self._add_variant_table(story, report_data["secondary_findings"], normal_style)
            story.append(Spacer(1, 0.5 * cm))

        if report_data["vus"]:
            story.append(Paragraph("Variants of Uncertain Significance (VUS) - Top 50", section_style))
            self._add_variant_table(story, report_data["vus"], normal_style)

        story.append(PageBreak())
        story.append(Paragraph("Disclaimer", section_style))
        story.append(Paragraph(report_data["disclaimer"], normal_style))

        doc.build(story)
        logger.info(f"PDF report generated: {pdf_path}")

    def _add_variant_table(
        self,
        story: List,
        variants: List[Dict[str, Any]],
        normal_style,
    ) -> None:
        if not variants:
            story.append(Paragraph("No variants found in this category.", normal_style))
            return

        header = [
            "Gene", "Variant", "HGVS c.", "HGVS p.", "Consequence",
            "Genotype", "AF", "CADD", "gnomAD AF", "ACMG",
        ]

        table_data = [header]
        for var in variants[:20]:
            hgvsc = var.get("hgvsc", "")
            if hgvsc and ":" in hgvsc:
                hgvsc = hgvsc.split(":")[-1]

            hgvsp = var.get("hgvsp", "")
            if hgvsp and ":" in hgvsp:
                hgvsp = hgvsp.split(":")[-1]

            af = var.get("allele_frequency", 0)
            af_str = f"{af * 100:.1f}%" if af else "N/A"

            cadd = var.get("in_silico_predictions", {}).get("CADD", None)
            cadd_str = f"{cadd:.1f}" if cadd else "N/A"

            gnomad = var.get("population_frequencies", {}).get("gnomAD_AF", None)
            gnomad_str = f"{gnomad * 100:.4f}%" if gnomad else "N/A"

            acmg = var.get("acmg_classification", "N/A")

            table_data.append([
                var.get("gene", "N/A"),
                f"{var['chromosome']}:{var['position']}{var['ref']}>{var['alt']}",
                hgvsc,
                hgvsp,
                var.get("consequence", "").replace("_", " ")[:20],
                var.get("genotype", "N/A"),
                af_str,
                cadd_str,
                gnomad_str,
                acmg,
            ])

        col_widths = [2 * cm, 3 * cm, 2.5 * cm, 2.5 * cm, 3 * cm, 1.5 * cm, 1.2 * cm, 1.2 * cm, 2 * cm, 1.5 * cm]
        variant_table = Table(table_data, colWidths=col_widths, repeatRows=1)

        def get_acmg_color(acmg):
            color_map = {
                "P": colors.HexColor("#c0392b"),
                "LP": colors.HexColor("#e67e22"),
                "VUS": colors.HexColor("#f39c12"),
                "LB": colors.HexColor("#27ae60"),
                "B": colors.HexColor("#2ecc71"),
            }
            return color_map.get(acmg, colors.black)

        table_style = [
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#5d6d7e")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
            ("FONTSIZE", (0, 0), (-1, -1), 7),
            ("GRID", (0, 0), (-1, -1), 0.3, colors.grey),
            ("ALIGN", (0, 0), (-1, -1), "CENTER"),
            ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
            ("LEFTPADDING", (0, 0), (-1, -1), 3),
            ("RIGHTPADDING", (0, 0), (-1, -1), 3),
            ("TOPPADDING", (0, 0), (-1, -1), 2),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 2),
        ]

        for i, row in enumerate(table_data[1:], start=1):
            acmg = row[-1]
            table_style.append(("TEXTCOLOR", (-1, i), (-1, i), get_acmg_color(acmg)))
            if acmg in ["P", "LP"]:
                table_style.append(("BACKGROUND", (-1, i), (-1, i), colors.HexColor("#fdecea")))

        variant_table.setStyle(TableStyle(table_style))
        story.append(variant_table)

        if len(variants) > 20:
            story.append(Spacer(1, 0.2 * cm))
            story.append(Paragraph(
                f"<i>Showing top 20 of {len(variants)} variants. See JSON report for full list.</i>",
                normal_style,
            ))

    def _archive_report(
        self,
        sample_id: str,
        pdf_path: Path,
        json_path: Path,
    ) -> str:
        try:
            from storage.minio_client import get_minio_client
            from config.settings import settings

            minio_client = get_minio_client()
            pdf_object = minio_client.upload_report(sample_id, str(pdf_path))
            json_object = minio_client.upload_report(sample_id, str(json_path))

            return pdf_object or ""
        except Exception as e:
            logger.warning(f"Failed to archive report to MinIO: {e}")
            return ""
