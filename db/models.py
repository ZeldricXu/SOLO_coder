from sqlalchemy import Column, Integer, String, Float, DateTime, Text, Boolean, ForeignKey, JSON, Enum as SQLEnum, UniqueConstraint
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func
import enum

from db.database import Base


class SampleType(str, enum.Enum):
    WES = "WES"
    WGS = "WGS"
    PANEL = "PANEL"
    cfDNA = "cfDNA"
    RNA_SEQ = "RNA_SEQ"


class SampleStatus(str, enum.Enum):
    REGISTERED = "registered"
    QC_FAILED = "qc_failed"
    QC_PASSED = "qc_passed"
    ANALYZING = "analyzing"
    ANALYZED = "analyzed"
    REPORTED = "reported"
    ARCHIVED = "archived"


class TaskStatus(str, enum.Enum):
    PENDING = "pending"
    QUEUED = "queued"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class StepStatus(str, enum.Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    SKIPPED = "skipped"
    RETRYING = "retrying"


class ACMGClassification(str, enum.Enum):
    PATHOGENIC = "P"
    LIKELY_PATHOGENIC = "LP"
    UNCERTAIN_SIGNIFICANCE = "VUS"
    LIKELY_BENIGN = "LB"
    BENIGN = "B"


class VariantType(str, enum.Enum):
    SNV = "SNV"
    INDEL = "Indel"
    SV = "SV"
    FUSION = "FUSION"
    CNV = "CNV"
    BND = "BND"


class FamilyRole(str, enum.Enum):
    PROBAND = "proband"
    MOTHER = "mother"
    FATHER = "father"
    SIBLING = "sibling"
    OTHER = "other"


class InheritanceMode(str, enum.Enum):
    AUTOSOMAL_DOMINANT = "AD"
    AUTOSOMAL_RECESSIVE = "AR"
    X_LINKED = "XL"
    MITOCHONDRIAL = "MT"
    DE_NOVO = "de_novo"
    COMPOUND_HETEROZYGOUS = "compound_het"


class Sample(Base):
    __tablename__ = "samples"

    id = Column(Integer, primary_key=True, index=True)
    sample_id = Column(String(64), unique=True, index=True, nullable=False)
    patient_id = Column(String(64), index=True)
    sample_type = Column(SQLEnum(SampleType), nullable=False)
    library_id = Column(String(64))
    sequencing_platform = Column(String(64))
    paired_end = Column(Boolean, default=True)
    read_length = Column(Integer)

    phenotype_hpo = Column(JSON, default=list)
    clinical_diagnosis = Column(Text)
    referring_physician = Column(String(128))
    institution = Column(String(128))

    fastq_r1_path = Column(String(512))
    fastq_r2_path = Column(String(512))
    fastq_md5_r1 = Column(String(32))
    fastq_md5_r2 = Column(String(32))

    family_id = Column(Integer, ForeignKey("families.id"), nullable=True)
    family_role = Column(SQLEnum(FamilyRole), nullable=True)

    status = Column(SQLEnum(SampleStatus), default=SampleStatus.REGISTERED)
    qc_metrics = Column(JSON, default=dict)
    total_variants = Column(Integer, default=0)
    report_path = Column(String(512))

    received_at = Column(DateTime(timezone=True), server_default=func.now())
    analysis_started_at = Column(DateTime(timezone=True))
    analysis_completed_at = Column(DateTime(timezone=True))
    archived_at = Column(DateTime(timezone=True))

    tasks = relationship("AnalysisTask", back_populates="sample")
    variants = relationship("Variant", back_populates="sample")

    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), onupdate=func.now())


class Family(Base):
    __tablename__ = "families"

    id = Column(Integer, primary_key=True, index=True)
    family_id = Column(String(64), unique=True, index=True, nullable=False)
    family_name = Column(String(128))
    phenotype_description = Column(Text)
    suspected_inheritance = Column(SQLEnum(InheritanceMode), nullable=True)
    hpo_terms = Column(JSON, default=list)
    notes = Column(Text)

    members = relationship("Sample", backref="family", foreign_keys="Sample.family_id")

    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), onupdate=func.now())


class AnalysisTask(Base):
    __tablename__ = "analysis_tasks"

    id = Column(Integer, primary_key=True, index=True)
    task_id = Column(String(64), unique=True, index=True, nullable=False)
    task_name = Column(String(128))
    sample_id = Column(Integer, ForeignKey("samples.id"), nullable=False)
    pipeline_version = Column(String(32))
    reference_genome = Column(String(32), default="hg38")

    status = Column(SQLEnum(TaskStatus), default=TaskStatus.PENDING)
    current_step = Column(String(64))
    progress_percent = Column(Float, default=0.0)
    error_message = Column(Text)

    input_files = Column(JSON, default=list)
    output_files = Column(JSON, default=list)
    result_summary = Column(JSON, default=dict)

    priority = Column(Integer, default=0)
    celery_task_id = Column(String(64))

    started_at = Column(DateTime(timezone=True))
    completed_at = Column(DateTime(timezone=True))
    failed_at = Column(DateTime(timezone=True))

    sample = relationship("Sample", back_populates="tasks")
    steps = relationship("TaskStep", back_populates="task", cascade="all, delete-orphan")

    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), onupdate=func.now())


class TaskStep(Base):
    __tablename__ = "task_steps"

    id = Column(Integer, primary_key=True, index=True)
    task_id = Column(Integer, ForeignKey("analysis_tasks.id"), nullable=False)
    step_id = Column(String(64), nullable=False)
    step_name = Column(String(128))
    step_type = Column(String(64))

    status = Column(SQLEnum(StepStatus), default=StepStatus.PENDING)
    retry_count = Column(Integer, default=0)
    max_retries = Column(Integer, default=3)

    input_files = Column(JSON, default=list)
    output_files = Column(JSON, default=list)
    parameters = Column(JSON, default=dict)

    std_out = Column(Text)
    std_err = Column(Text)
    metrics = Column(JSON, default=dict)
    error_message = Column(Text)

    started_at = Column(DateTime(timezone=True))
    completed_at = Column(DateTime(timezone=True))
    duration_seconds = Column(Float)

    task = relationship("AnalysisTask", back_populates="steps")

    __table_args__ = (
        UniqueConstraint("task_id", "step_id", name="uix_task_step"),
    )

    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), onupdate=func.now())


class Variant(Base):
    __tablename__ = "variants"

    id = Column(Integer, primary_key=True, index=True)
    sample_id = Column(Integer, ForeignKey("samples.id"), nullable=False)
    variant_id = Column(String(128), index=True)

    chromosome = Column(String(16), index=True)
    position = Column(Integer, index=True)
    end_position = Column(Integer, nullable=True)
    ref = Column(String(256))
    alt = Column(String(256))

    variant_type = Column(SQLEnum(VariantType), default=VariantType.SNV)
    genotype = Column(String(16))
    genotype_quality = Column(Float)
    depth = Column(Integer)
    allele_depth = Column(Integer)
    allele_frequency = Column(Float)

    gene = Column(String(64), index=True)
    transcript = Column(String(64))
    hgvsc = Column(String(128))
    hgvsp = Column(String(128))
    consequence = Column(String(64))
    impact = Column(String(16))

    gnomad_af = Column(Float)
    thousandg_af = Column(Float)
    exac_af = Column(Float)

    cadd_score = Column(Float)
    revel_score = Column(Float)
    sift_score = Column(Float)
    polyphen2_score = Column(Float)

    clinvar_id = Column(String(32))
    clinvar_clinsig = Column(String(64))
    clinvar_review_status = Column(String(64))

    acmg_classification = Column(SQLEnum(ACMGClassification))
    acmg_criteria = Column(JSON, default=list)
    acmg_score = Column(Float)

    is_secondary_finding = Column(Boolean, default=False)
    is_candidate = Column(Boolean, default=False)

    inheritance_mode = Column(SQLEnum(InheritanceMode), nullable=True)
    segregation_info = Column(JSON, default=dict)

    fusion_partner_gene = Column(String(64), nullable=True)
    fusion_breakpoint_5prime = Column(String(128), nullable=True)
    fusion_breakpoint_3prime = Column(String(128), nullable=True)
    fusion_fusion_type = Column(String(32), nullable=True)
    fusion_frame = Column(String(16), nullable=True)
    fusion_junction_reads = Column(Integer, nullable=True)
    fusion_spanning_reads = Column(Integer, nullable=True)

    sv_event_type = Column(String(32), nullable=True)
    sv_length = Column(Integer, nullable=True)
    sv_ci_pos_left = Column(String(32), nullable=True)
    sv_ci_pos_right = Column(String(32), nullable=True)

    targeted_drugs = Column(JSON, default=list)

    sample = relationship("Sample", back_populates="variants")

    created_at = Column(DateTime(timezone=True), server_default=func.now())


class Cohort(Base):
    __tablename__ = "cohorts"

    id = Column(Integer, primary_key=True, index=True)
    cohort_id = Column(String(64), unique=True, index=True, nullable=False)
    cohort_name = Column(String(128))
    description = Column(Text)
    sample_count = Column(Integer, default=0)

    status = Column(SQLEnum(TaskStatus), default=TaskStatus.PENDING)
    joint_vcf_path = Column(String(512))

    created_by = Column(String(64))
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), onupdate=func.now())


class CohortSample(Base):
    __tablename__ = "cohort_samples"

    id = Column(Integer, primary_key=True, index=True)
    cohort_id = Column(Integer, ForeignKey("cohorts.id"), nullable=False)
    sample_id = Column(Integer, ForeignKey("samples.id"), nullable=True)
    family_id = Column(Integer, ForeignKey("families.id"), nullable=True)

    added_at = Column(DateTime(timezone=True), server_default=func.now())


class DataArchive(Base):
    __tablename__ = "data_archives"

    id = Column(Integer, primary_key=True, index=True)
    object_key = Column(String(512), unique=True, index=True, nullable=False)
    bucket = Column(String(64), nullable=False)
    file_type = Column(String(32))
    sample_id = Column(Integer, ForeignKey("samples.id"))
    size_bytes = Column(Integer)

    archive_date = Column(DateTime(timezone=True), server_default=func.now())
    retention_days = Column(Integer)
    delete_after = Column(DateTime(timezone=True))
    is_deleted = Column(Boolean, default=False)
    deleted_at = Column(DateTime(timezone=True))

    created_at = Column(DateTime(timezone=True), server_default=func.now())


class QCMetric(Base):
    __tablename__ = "qc_metrics"

    id = Column(Integer, primary_key=True, index=True)
    sample_id = Column(Integer, ForeignKey("samples.id"), nullable=False)
    step_type = Column(String(32))

    total_reads = Column(Integer)
    total_bases = Column(Integer)
    q20_bases = Column(Integer)
    q30_bases = Column(Integer)
    gc_content = Column(Float)
    adapter_content = Column(Float)
    duplication_rate = Column(Float)

    mapped_reads = Column(Integer)
    mapping_rate = Column(Float)
    properly_paired = Column(Integer)
    proper_pair_rate = Column(Float)
    mean_insert_size = Column(Float)

    on_target_rate = Column(Float)
    mean_coverage = Column(Float)
    coverage_1x = Column(Float)
    coverage_10x = Column(Float)
    coverage_20x = Column(Float)
    coverage_30x = Column(Float)

    transition_transversion_ratio = Column(Float)
    het_hom_ratio = Column(Float)

    metrics_json = Column(JSON, default=dict)

    created_at = Column(DateTime(timezone=True), server_default=func.now())


class VariantVisualization(Base):
    __tablename__ = "variant_visualizations"

    id = Column(Integer, primary_key=True, index=True)
    variant_id = Column(Integer, ForeignKey("variants.id"), nullable=False)
    sample_id = Column(Integer, ForeignKey("samples.id"), nullable=False)

    image_path = Column(String(512), nullable=False)
    image_type = Column(String(32), default="pileup")
    chromosome = Column(String(16))
    position = Column(Integer)
    window_size = Column(Integer, default=50)

    minio_object_key = Column(String(512), nullable=True)

    created_at = Column(DateTime(timezone=True), server_default=func.now())
