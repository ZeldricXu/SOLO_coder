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
    """Standard germline variant calling pipeline DAG definition."""

    @staticmethod
    def get_single_sample_pipeline(sample_id: str, with_vardict: bool = False) -> List[PipelineStep]:
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

        steps.append(PipelineStep(
            step_id=f"{sample_id}_report_generation",
            step_type=PipelineStepType.REPORT_GENERATION,
            name="Clinical Report Generation",
            description="Generate PDF report and structured JSON",
            inputs=[f"{sample_id}_acmg.vcf.gz"],
            outputs=[f"{sample_id}_report.pdf", f"{sample_id}_report.json"],
            dependencies=[f"{sample_id}_acmg_classification"],
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

    @staticmethod
    def get_cohort_genotyping_pipeline(cohort_id: int, gvcf_paths: List[str]) -> List[PipelineStep]:
        cohort_str = str(cohort_id)
        steps = []

        steps.append(PipelineStep(
            step_id=f"cohort_{cohort_str}_genotype_gvcfs",
            step_type=PipelineStepType.GENOTYPE_GVCFS,
            name="Joint Genotyping with GenotypeGVCFs",
            description="Multi-sample joint genotyping for cohort",
            inputs=gvcf_paths,
            outputs=[f"cohort_{cohort_str}_joint.vcf.gz", f"cohort_{cohort_str}_joint.vcf.gz.tbi"],
            max_retries=3,
        ))

        steps.append(PipelineStep(
            step_id=f"cohort_{cohort_str}_vep_annotation",
            step_type=PipelineStepType.VEP_ANNOTATION,
            name="VEP Variant Annotation (Cohort)",
            description="Ensembl VEP functional annotation for cohort",
            inputs=[f"cohort_{cohort_str}_joint.vcf.gz"],
            outputs=[f"cohort_{cohort_str}_joint_vep.vcf.gz"],
            dependencies=[f"cohort_{cohort_str}_genotype_gvcfs"],
            max_retries=2,
        ))

        steps.append(PipelineStep(
            step_id=f"cohort_{cohort_str}_dbnsfp_annotation",
            step_type=PipelineStepType.DBNSFP_ANNOTATION,
            name="dbNSFP Annotation (Cohort)",
            description="Population frequency and pathogenicity predictions",
            inputs=[f"cohort_{cohort_str}_joint_vep.vcf.gz"],
            outputs=[f"cohort_{cohort_str}_joint_dbnsfp.vcf.gz"],
            dependencies=[f"cohort_{cohort_str}_vep_annotation"],
            max_retries=2,
        ))

        steps.append(PipelineStep(
            step_id=f"cohort_{cohort_str}_clinvar_annotation",
            step_type=PipelineStepType.CLINVAR_ANNOTATION,
            name="ClinVar Annotation (Cohort)",
            description="ClinVar clinical significance annotation",
            inputs=[f"cohort_{cohort_str}_joint_dbnsfp.vcf.gz"],
            outputs=[f"cohort_{cohort_str}_joint_clinvar.vcf.gz"],
            dependencies=[f"cohort_{cohort_str}_dbnsfp_annotation"],
            max_retries=1,
        ))

        steps.append(PipelineStep(
            step_id=f"cohort_{cohort_str}_acmg_classification",
            step_type=PipelineStepType.ACMG_CLASSIFICATION,
            name="ACMG Classification (Cohort)",
            description="Automated ACMG/AMP classification for cohort",
            inputs=[f"cohort_{cohort_str}_joint_clinvar.vcf.gz"],
            outputs=[f"cohort_{cohort_str}_joint_acmg.vcf.gz"],
            dependencies=[f"cohort_{cohort_str}_clinvar_annotation"],
            max_retries=2,
        ))

        return steps
