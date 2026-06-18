from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import Field
from typing import Optional


class DatabaseSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DB_", env_file=".env", extra="ignore")

    host: str = "localhost"
    port: int = 5432
    name: str = "genome_pipeline"
    user: str = "postgres"
    password: str = "postgres"

    @property
    def url(self) -> str:
        return f"postgresql://{self.user}:{self.password}@{self.host}:{self.port}/{self.name}"


class RedisSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="REDIS_", env_file=".env", extra="ignore")

    host: str = "localhost"
    port: int = 6379
    db: int = 0
    password: Optional[str] = None

    @property
    def url(self) -> str:
        if self.password:
            return f"redis://:{self.password}@{self.host}:{self.port}/{self.db}"
        return f"redis://{self.host}:{self.port}/{self.db}"


class MinIOSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="MINIO_", env_file=".env", extra="ignore")

    endpoint: str = "localhost:9000"
    access_key: str = "minioadmin"
    secret_key: str = "minioadmin"
    secure: bool = False
    raw_data_bucket: str = "raw-sequencing-data"
    results_bucket: str = "analysis-results"
    reports_bucket: str = "clinical-reports"


class ToolPaths(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="TOOL_", env_file=".env", extra="ignore")

    fastqc: str = "fastqc"
    fastp: str = "fastp"
    bwa: str = "bwa"
    samtools: str = "samtools"
    picard: str = "picard"
    gatk: str = "gatk"
    vardict: str = "vardict-java"
    vep: str = "vep"


class ReferenceGenome(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="REF_", env_file=".env", extra="ignore")

    hg38_fasta: str = "/data/reference/hg38/Homo_sapiens_assembly38.fasta"
    hg38_dict: str = "/data/reference/hg38/Homo_sapiens_assembly38.dict"
    hg38_bwa_index: str = "/data/reference/hg38/Homo_sapiens_assembly38.fasta"
    known_sites_snp: str = "/data/reference/hg38/dbsnp_146.hg38.vcf.gz"
    known_sites_indel: str = "/data/reference/hg38/Mills_and_1000G_gold_standard.indels.hg38.vcf.gz"
    known_sites_1000g: str = "/data/reference/hg38/1000G_phase1.snps.high_confidence.hg38.vcf.gz"


class AnnotationDatabases(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="ANNO_", env_file=".env", extra="ignore")

    vep_cache_dir: str = "/data/vep/cache"
    vep_assembly: str = "GRCh38"
    dbnsfp_db: str = "/data/annotation/dbNSFP/dbNSFP4.3a.gz"
    clinvar_vcf: str = "/data/annotation/clinvar/clinvar_20240107.vcf.gz"
    gnomad_vcf: str = "/data/annotation/gnomad/gnomad.genomes.r4.0.sites.vcf.gz"


class RetentionPolicy(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="RETENTION_", env_file=".env", extra="ignore")

    raw_fastq_days: int = 90
    bam_days: int = 365
    gvcf_days: int = -1
    report_days: int = -1


class PipelineSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="PIPELINE_", env_file=".env", extra="ignore")

    max_retries: int = 3
    max_parallel_chromosomes: int = 8
    work_dir: str = "/data/work"
    temp_dir: str = "/tmp/pipeline"
    log_dir: str = "/data/logs"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database: DatabaseSettings = Field(default_factory=DatabaseSettings)
    redis: RedisSettings = Field(default_factory=RedisSettings)
    minio: MinIOSettings = Field(default_factory=MinIOSettings)
    tools: ToolPaths = Field(default_factory=ToolPaths)
    reference: ReferenceGenome = Field(default_factory=ReferenceGenome)
    annotation: AnnotationDatabases = Field(default_factory=AnnotationDatabases)
    retention: RetentionPolicy = Field(default_factory=RetentionPolicy)
    pipeline: PipelineSettings = Field(default_factory=PipelineSettings)
    environment: str = "development"


settings = Settings()
