from dataclasses import dataclass, field
from enum import Enum
from typing import List, Dict, Any, Optional


class StepStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    SKIPPED = "skipped"
    RETRYING = "retrying"


class PipelineStepType(str, Enum):
    FASTQC = "fastqc"
    FASTP = "fastp"
    BWA_MEM = "bwa_mem"
    SAMTOOLS_SORT = "samtools_sort"
    SAMTOOLS_INDEX = "samtools_index"
    MARK_DUPLICATES = "mark_duplicates"
    BASE_RECALIBRATOR = "base_recalibrator"
    APPLY_BQSR = "apply_bqsr"
    HAPLOTYPE_CALLER = "haplotype_caller"
    GENOTYPE_GVCFS = "genotype_gvcfs"
    VARDICT = "vardict"
    VEP_ANNOTATION = "vep_annotation"
    DBNSFP_ANNOTATION = "dbnsfp_annotation"
    CLINVAR_ANNOTATION = "clinvar_annotation"
    ACMG_CLASSIFICATION = "acmg_classification"
    STAR_FUSION = "star_fusion"
    ARRIBA = "arriba"
    MANTA = "manta"
    FUSION_DRUG_ANNOTATION = "fusion_drug_annotation"
    FAMILY_ANALYSIS = "family_analysis"
    VARIANT_VISUALIZATION = "variant_visualization"
    REPORT_GENERATION = "report_generation"


@dataclass
class PipelineStep:
    step_id: str
    step_type: PipelineStepType
    name: str
    description: str = ""
    inputs: List[str] = field(default_factory=list)
    outputs: List[str] = field(default_factory=list)
    dependencies: List[str] = field(default_factory=list)
    params: Dict[str, Any] = field(default_factory=dict)
    max_retries: int = 3
    parallel_group: Optional[str] = None
    is_parallel: bool = False


@dataclass
class ChromosomeSplitConfig:
    enabled: bool = True
    chromosomes: List[str] = field(default_factory=lambda: [f"chr{i}" for i in range(1, 23)] + ["chrX", "chrY"])
    merge_step: str = "merge_vcfs"


class PipelineDefinition:

    @staticmethod
    def get_single_sample_pipeline(
        sample_id: str,
        with_vardict: bool = False,
        with_fusion: bool = False,
        fusion_caller: str = "star_fusion",
        with_manta: bool = False,
        with_visualization: bool = True,
    ) -> List[PipelineStep]:
        steps = []

        steps.append(PipelineStep(
            step_id=f"{sample_id}_fastqc",
            step_type=PipelineStepType.FASTQC,
            name="FastQC Quality Control",
            description="Raw sequencing data quality assessment",
            inputs=[f"{sample_id}_R1.fastq.gz", f"{sample_id}_R2.fastq.gz"],
            outputs=[f"{sample_id}_fastqc_report.html", f"{sample_id}_fastqc_data.json"],
            max_retries=2,
        ))

        steps.append(PipelineStep(
            step_id=f"{sample_id}_fastp",
            step_type=PipelineStepType.FASTP,
            name="Adapter Trimming and Quality Filtering",
            description="Remove adapters and low-quality bases",
            inputs=[f"{sample_id}_R1.fastq.gz", f"{sample_id}_R2.fastq.gz"],
            outputs=[
                f"{sample_id}_clean_R1.fastq.gz",
                f"{sample_id}_clean_R2.fastq.gz",
                f"{sample_id}_fastp_report.html",
                f"{sample_id}_fastp_report.json",
            ],
            dependencies=[f"{sample_id}_fastqc"],
            max_retries=2,
        ))

        steps.append(PipelineStep(
            step_id=f"{sample_id}_bwa_mem",
            step_type=PipelineStepType.BWA_MEM,
            name="BWA-MEM Alignment",
            description="Align clean reads to hg38 reference genome",
            inputs=[f"{sample_id}_clean_R1.fastq.gz", f"{sample_id}_clean_R2.fastq.gz"],
            outputs=[f"{sample_id}.sam"],
            dependencies=[f"{sample_id}_fastp"],
            is_parallel=True,
            parallel_group="by_chromosome",
            max_retries=3,
        ))

        steps.append(PipelineStep(
            step_id=f"{sample_id}_samtools_sort",
            step_type=PipelineStepType.SAMTOOLS_SORT,
            name="SAMtools Sort",
            description="Sort alignments by coordinate",
            inputs=[f"{sample_id}.sam"],
            outputs=[f"{sample_id}.sorted.bam"],
            dependencies=[f"{sample_id}_bwa_mem"],
            max_retries=2,
        ))

        steps.append(PipelineStep(
            step_id=f"{sample_id}_samtools_index",
            step_type=PipelineStepType.SAMTOOLS_INDEX,
            name="SAMtools Index",
            description="Index sorted BAM file",
            inputs=[f"{sample_id}.sorted.bam"],
            outputs=[f"{sample_id}.sorted.bam.bai"],
            dependencies=[f"{sample_id}_samtools_sort"],
            max_retries=1,
        ))

        steps.append(PipelineStep(
            step_id=f"{sample_id}_mark_duplicates",
            step_type=PipelineStepType.MARK_DUPLICATES,
            name="Picard Mark Duplicates",
            description="Mark PCR and optical duplicates",
            inputs=[f"{sample_id}.sorted.bam"],
            outputs=[f"{sample_id}.dedup.bam", f"{sample_id}_duplicate_metrics.txt"],
            dependencies=[f"{sample_id}_samtools_sort"],
            max_retries=2,
        ))

        steps.append(PipelineStep(
            step_id=f"{sample_id}_base_recalibrator",
            step_type=PipelineStepType.BASE_RECALIBRATOR,
            name="GATK BaseRecalibrator",
            description="Base quality score recalibration table",
            inputs=[f"{sample_id}.dedup.bam"],
            outputs=[f"{sample_id}_recal_data.table"],
            dependencies=[f"{sample_id}_mark_duplicates"],
            max_retries=2,
        ))

        steps.append(PipelineStep(
            step_id=f"{sample_id}_apply_bqsr",
            step_type=PipelineStepType.APPLY_BQSR,
            name="GATK ApplyBQSR",
            description="Apply base quality recalibration",
            inputs=[f"{sample_id}.dedup.bam", f"{sample_id}_recal_data.table"],
            outputs=[f"{sample_id}.recal.bam", f"{sample_id}.recal.bai"],
            dependencies=[f"{sample_id}_base_recalibrator"],
            max_retries=2,
        ))

        steps.append(PipelineStep(
            step_id=f"{sample_id}_haplotype_caller",
            step_type=PipelineStepType.HAPLOTYPE_CALLER,
            name="GATK HaplotypeCaller",
            description="Single-sample gVCF generation",
            inputs=[f"{sample_id}.recal.bam"],
            outputs=[f"{sample_id}.g.vcf.gz", f"{sample_id}.g.vcf.gz.tbi"],
            dependencies=[f"{sample_id}_apply_bqsr"],
            is_parallel=True,
            parallel_group="by_chromosome",
            max_retries=3,
        ))

        if with_vardict:
            steps.append(PipelineStep(
                step_id=f"{sample_id}_vardict",
                step_type=PipelineStepType.VARDICT,
                name="VarDict Variant Calling",
                description="Sensitive variant calling for low-frequency variants",
                inputs=[f"{sample_id}.recal.bam"],
                outputs=[f"{sample_id}_vardict.vcf.gz"],
                dependencies=[f"{sample_id}_apply_bqsr"],
                is_parallel=True,
                parallel_group="by_chromosome",
                max_retries=3,
            ))

        if with_fusion:
            fusion_step_type = PipelineStepType.STAR_FUSION if fusion_caller == "star_fusion" else PipelineStepType.ARRIBA
            fusion_step_name = "STAR-Fusion" if fusion_caller == "star_fusion" else "Arriba"
            steps.append(PipelineStep(
                step_id=f"{sample_id}_{fusion_caller}",
                step_type=fusion_step_type,
                name=f"{fusion_step_name} Fusion Detection",
                description="RNA-seq fusion gene detection",
                inputs=[f"{sample_id}_clean_R1.fastq.gz", f"{sample_id}_clean_R2.fastq.gz"],
                outputs=[f"{sample_id}_{fusion_caller}_fusions.tsv"],
                dependencies=[f"{sample_id}_fastp"],
                max_retries=2,
            ))

            steps.append(PipelineStep(
                step_id=f"{sample_id}_fusion_drug_annotation",
                step_type=PipelineStepType.FUSION_DRUG_ANNOTATION,
                name="Fusion Gene Drug Annotation",
                description="Targeted drug recommendations from CGI/CIViC",
                inputs=[f"{sample_id}_{fusion_caller}_fusions.tsv"],
                outputs=[f"{sample_id}_fusion_drugs.json"],
                dependencies=[f"{sample_id}_{fusion_caller}"],
                max_retries=1,
            ))

        if with_manta:
            steps.append(PipelineStep(
                step_id=f"{sample_id}_manta",
                step_type=PipelineStepType.MANTA,
                name="Manta Structural Variant Calling",
                description="DNA-level structural variant and fusion prediction",
                inputs=[f"{sample_id}.recal.bam"],
                outputs=[f"{sample_id}_manta_sv.vcf.gz"],
                dependencies=[f"{sample_id}_apply_bqsr"],
                max_retries=3,
            ))

        steps.append(PipelineStep(
            step_id=f"{sample_id}_vep_annotation",
            step_type=PipelineStepType.VEP_ANNOTATION,
            name="VEP Variant Annotation",
            description="Ensembl VEP functional annotation",
            inputs=[f"{sample_id}.g.vcf.gz"],
            outputs=[f"{sample_id}_vep.vcf.gz", f"{sample_id}_vep_summary.html"],
            dependencies=[f"{sample_id}_haplotype_caller"],
            max_retries=2,
        ))

        steps.append(PipelineStep(
            step_id=f"{sample_id}_dbnsfp_annotation",
            step_type=PipelineStepType.DBNSFP_ANNOTATION,
            name="dbNSFP Annotation",
            description="Population frequency and pathogenicity prediction",
            inputs=[f"{sample_id}_vep.vcf.gz"],
            outputs=[f"{sample_id}_dbnsfp.vcf.gz"],
            dependencies=[f"{sample_id}_vep_annotation"],
            max_retries=2,
        ))

        steps.append(PipelineStep(
            step_id=f"{sample_id}_clinvar_annotation",
            step_type=PipelineStepType.CLINVAR_ANNOTATION,
            name="ClinVar Annotation",
            description="Clinical significance from ClinVar",
            inputs=[f"{sample_id}_dbnsfp.vcf.gz"],
            outputs=[f"{sample_id}_clinvar.vcf.gz"],
            dependencies=[f"{sample_id}_dbnsfp_annotation"],
            max_retries=1,
        ))

        steps.append(PipelineStep(
            step_id=f"{sample_id}_acmg_classification",
            step_type=PipelineStepType.ACMG_CLASSIFICATION,
            name="ACMG Pathogenicity Classification",
            description="Automated ACMG/AMP variant classification",
            inputs=[f"{sample_id}_clinvar.vcf.gz"],
            outputs=[f"{sample_id}_acmg.vcf.gz", f"{sample_id}_acmg_classifications.json"],
            dependencies=[f"{sample_id}_clinvar_annotation"],
            max_retries=2,
        ))

        if with_visualization:
            steps.append(PipelineStep(
                step_id=f"{sample_id}_variant_visualization",
                step_type=PipelineStepType.VARIANT_VISUALIZATION,
                name="Variant Visualization",
                description="Generate genome browser pileup images for candidate variants",
                inputs=[f"{sample_id}.recal.bam", f"{sample_id}_acmg_classifications.json"],
                outputs=[f"{sample_id}_visualizations.json"],
                dependencies=[f"{sample_id}_acmg_classification", f"{sample_id}_apply_bqsr"],
                max_retries=2,
            ))

        report_deps = [f"{sample_id}_acmg_classification"]
        if with_fusion:
            report_deps.append(f"{sample_id}_fusion_drug_annotation")
        if with_manta:
            report_deps.append(f"{sample_id}_manta")
        if with_visualization:
            report_deps.append(f"{sample_id}_variant_visualization")

        steps.append(PipelineStep(
            step_id=f"{sample_id}_report_generation",
            step_type=PipelineStepType.REPORT_GENERATION,
            name="Clinical Report Generation",
            description="Generate PDF report and structured JSON",
            inputs=[f"{sample_id}_acmg.vcf.gz"],
            outputs=[f"{sample_id}_report.pdf", f"{sample_id}_report.json"],
            dependencies=report_deps,
            max_retries=2,
        ))

        return steps

    @staticmethod
    def get_trio_pipeline(
        proband_id: str,
        mother_id: str,
        father_id: str,
        family_id: str,
        with_vardict: bool = False,
        with_visualization: bool = True,
    ) -> List[PipelineStep]:
        steps = []

        for sid, label in [(proband_id, "proband"), (mother_id, "mother"), (father_id, "father")]:
            steps.extend(PipelineDefinition.get_single_sample_pipeline(
                sample_id=sid,
                with_vardict=with_vardict,
                with_fusion=False,
                with_manta=False,
                with_visualization=False,
            ))

        proband_hc_dep = f"{proband_id}_haplotype_caller"
        mother_hc_dep = f"{mother_id}_haplotype_caller"
        father_hc_dep = f"{father_id}_haplotype_caller"

        steps.append(PipelineStep(
            step_id=f"family_{family_id}_genotype_gvcfs",
            step_type=PipelineStepType.GENOTYPE_GVCFS,
            name="Joint Genotyping (Trio)",
            description="Multi-sample joint genotyping for trio family",
            inputs=[f"{proband_id}.g.vcf.gz", f"{mother_id}.g.vcf.gz", f"{father_id}.g.vcf.gz"],
            outputs=[f"family_{family_id}_joint.vcf.gz"],
            dependencies=[proband_hc_dep, mother_hc_dep, father_hc_dep],
            max_retries=3,
        ))

        steps.append(PipelineStep(
            step_id=f"family_{family_id}_vep_annotation",
            step_type=PipelineStepType.VEP_ANNOTATION,
            name="VEP Annotation (Trio)",
            description="Ensembl VEP functional annotation for trio",
            inputs=[f"family_{family_id}_joint.vcf.gz"],
            outputs=[f"family_{family_id}_vep.vcf.gz"],
            dependencies=[f"family_{family_id}_genotype_gvcfs"],
            max_retries=2,
        ))

        steps.append(PipelineStep(
            step_id=f"family_{family_id}_dbnsfp_annotation",
            step_type=PipelineStepType.DBNSFP_ANNOTATION,
            name="dbNSFP Annotation (Trio)",
            description="Population frequency and pathogenicity predictions",
            inputs=[f"family_{family_id}_vep.vcf.gz"],
            outputs=[f"family_{family_id}_dbnsfp.vcf.gz"],
            dependencies=[f"family_{family_id}_vep_annotation"],
            max_retries=2,
        ))

        steps.append(PipelineStep(
            step_id=f"family_{family_id}_clinvar_annotation",
            step_type=PipelineStepType.CLINVAR_ANNOTATION,
            name="ClinVar Annotation (Trio)",
            description="ClinVar clinical significance annotation",
            inputs=[f"family_{family_id}_dbnsfp.vcf.gz"],
            outputs=[f"family_{family_id}_clinvar.vcf.gz"],
            dependencies=[f"family_{family_id}_dbnsfp_annotation"],
            max_retries=1,
        ))

        steps.append(PipelineStep(
            step_id=f"family_{family_id}_acmg_classification",
            step_type=PipelineStepType.ACMG_CLASSIFICATION,
            name="ACMG Classification (Trio)",
            description="Automated ACMG/AMP classification for trio",
            inputs=[f"family_{family_id}_clinvar.vcf.gz"],
            outputs=[f"family_{family_id}_acmg.vcf.gz", f"family_{family_id}_acmg.json"],
            dependencies=[f"family_{family_id}_clinvar_annotation"],
            max_retries=2,
        ))

        steps.append(PipelineStep(
            step_id=f"family_{family_id}_family_analysis",
            step_type=PipelineStepType.FAMILY_ANALYSIS,
            name="Family Inheritance Analysis",
            description="Trio inheritance mode filtering (AD/AR/XL/compound het)",
            inputs=[f"family_{family_id}_acmg.json"],
            outputs=[f"family_{family_id}_inheritance.json"],
            dependencies=[f"family_{family_id}_acmg_classification"],
            params={
                "family_id": family_id,
                "proband_id": proband_id,
                "mother_id": mother_id,
                "father_id": father_id,
            },
            max_retries=2,
        ))

        if with_visualization:
            steps.append(PipelineStep(
                step_id=f"family_{family_id}_variant_visualization",
                step_type=PipelineStepType.VARIANT_VISUALIZATION,
                name="Variant Visualization (Trio)",
                description="Generate pileup images for candidate variants",
                inputs=[f"{proband_id}.recal.bam", f"family_{family_id}_inheritance.json"],
                outputs=[f"family_{family_id}_visualizations.json"],
                dependencies=[f"family_{family_id}_family_analysis", f"{proband_id}_apply_bqsr"],
                max_retries=2,
                params={"sample_id": proband_id},
            ))

        report_deps = [f"family_{family_id}_family_analysis"]
        if with_visualization:
            report_deps.append(f"family_{family_id}_variant_visualization")

        steps.append(PipelineStep(
            step_id=f"family_{family_id}_report_generation",
            step_type=PipelineStepType.REPORT_GENERATION,
            name="Clinical Report Generation (Trio)",
            description="Generate PDF report with family-based variant interpretation",
            inputs=[f"family_{family_id}_inheritance.json"],
            outputs=[f"family_{family_id}_report.pdf", f"family_{family_id}_report.json"],
            dependencies=report_deps,
            max_retries=2,
        ))

        return steps

    @staticmethod
    def get_joint_genotyping_pipeline(cohort_id: str, sample_ids: List[str]) -> List[PipelineStep]:
        steps = []

        gvcf_inputs = [f"{sid}.g.vcf.gz" for sid in sample_ids]
        hc_deps = [f"{sid}_haplotype_caller" for sid in sample_ids]

        steps.append(PipelineStep(
            step_id=f"{cohort_id}_genotype_gvcfs",
            step_type=PipelineStepType.GENOTYPE_GVCFS,
            name="Joint Genotyping with GenotypeGVCFs",
            description="Multi-sample joint genotyping",
            inputs=gvcf_inputs,
            outputs=[f"{cohort_id}_joint.vcf.gz", f"{cohort_id}_joint.vcf.gz.tbi"],
            dependencies=hc_deps,
            max_retries=3,
        ))

        steps.append(PipelineStep(
            step_id=f"{cohort_id}_vep_annotation",
            step_type=PipelineStepType.VEP_ANNOTATION,
            name="VEP Variant Annotation (Joint)",
            description="Ensembl VEP functional annotation for cohort",
            inputs=[f"{cohort_id}_joint.vcf.gz"],
            outputs=[f"{cohort_id}_joint_vep.vcf.gz"],
            dependencies=[f"{cohort_id}_genotype_gvcfs"],
            max_retries=2,
        ))

        steps.append(PipelineStep(
            step_id=f"{cohort_id}_dbnsfp_annotation",
            step_type=PipelineStepType.DBNSFP_ANNOTATION,
            name="dbNSFP Annotation (Joint)",
            description="Population frequency and pathogenicity predictions",
            inputs=[f"{cohort_id}_joint_vep.vcf.gz"],
            outputs=[f"{cohort_id}_joint_dbnsfp.vcf.gz"],
            dependencies=[f"{cohort_id}_vep_annotation"],
            max_retries=2,
        ))

        steps.append(PipelineStep(
            step_id=f"{cohort_id}_clinvar_annotation",
            step_type=PipelineStepType.CLINVAR_ANNOTATION,
            name="ClinVar Annotation (Joint)",
            description="ClinVar clinical significance annotation",
            inputs=[f"{cohort_id}_joint_dbnsfp.vcf.gz"],
            outputs=[f"{cohort_id}_joint_clinvar.vcf.gz"],
            dependencies=[f"{cohort_id}_dbnsfp_annotation"],
            max_retries=1,
        ))

        steps.append(PipelineStep(
            step_id=f"{cohort_id}_acmg_classification",
            step_type=PipelineStepType.ACMG_CLASSIFICATION,
            name="ACMG Classification (Joint)",
            description="Automated ACMG/AMP classification",
            inputs=[f"{cohort_id}_joint_clinvar.vcf.gz"],
            outputs=[f"{cohort_id}_joint_acmg.vcf.gz"],
            dependencies=[f"{cohort_id}_clinvar_annotation"],
            max_retries=2,
        ))

        return steps
