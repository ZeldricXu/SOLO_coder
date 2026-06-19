import os
import sys
import json
import uuid
import hashlib
import tempfile
import shutil
from pathlib import Path
from datetime import datetime, timedelta
from typing import Dict, List, Any, Generator, Optional

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, Session
from unittest.mock import Mock, MagicMock, patch

sys.path.insert(0, str(Path(__file__).parent.parent))

from db.database import Base
from db.models import (
    Sample,
    AnalysisTask,
    TaskStep,
    Variant,
    Cohort,
    CohortSample,
    QCMetric,
    DataArchive,
    SampleType,
    SampleStatus,
    TaskStatus,
    StepStatus,
    ACMGClassification,
)
from config.pipeline_config import (
    PipelineStep,
    PipelineStepType,
    PipelineDefinition,
)


@pytest.fixture(scope="session")
def test_env():
    env_vars = {
        "DB_HOST": "localhost",
        "DB_PORT": "5432",
        "DB_NAME": "test_genome_pipeline",
        "DB_USER": "postgres",
        "DB_PASSWORD": "postgres",
        "REDIS_HOST": "localhost",
        "REDIS_PORT": "6379",
        "REDIS_DB": "1",
        "MINIO_ENDPOINT": "localhost:9000",
        "MINIO_ACCESS_KEY": "test_minio",
        "MINIO_SECRET_KEY": "test_minio_secret",
        "PIPELINE_WORK_DIR": "/tmp/test_pipeline_work",
        "PIPELINE_TEMP_DIR": "/tmp/test_pipeline_temp",
        "PIPELINE_LOG_DIR": "/tmp/test_pipeline_logs",
        "ENVIRONMENT": "testing",
    }
    with patch.dict(os.environ, env_vars):
        yield env_vars


@pytest.fixture(scope="session")
def sqlite_db_url(tmp_path_factory):
    db_path = tmp_path_factory.mktemp("db") / "test.db"
    return f"sqlite:///{db_path}"


@pytest.fixture(scope="session")
def db_engine(sqlite_db_url):
    engine = create_engine(
        sqlite_db_url,
        connect_args={"check_same_thread": False},
        echo=False,
    )
    Base.metadata.create_all(bind=engine)
    yield engine
    Base.metadata.drop_all(bind=engine)


@pytest.fixture
def db_session(db_engine) -> Generator[Session, None, None]:
    connection = db_engine.connect()
    transaction = connection.begin()
    session = sessionmaker(bind=connection)()

    yield session

    session.close()
    transaction.rollback()
    connection.close()


@pytest.fixture
def sample_data_factory():
    def _factory(
        sample_id: str = None,
        sample_type: SampleType = SampleType.WES,
        status: SampleStatus = SampleStatus.REGISTERED,
        patient_id: str = None,
        **kwargs
    ) -> Dict[str, Any]:
        return {
            "sample_id": sample_id or f"SAMPLE_{uuid.uuid4().hex[:8]}",
            "sample_type": sample_type,
            "status": status,
            "patient_id": patient_id or f"PATIENT_{uuid.uuid4().hex[:8]}",
            "library_id": f"LIB_{uuid.uuid4().hex[:8]}",
            "sequencing_platform": "Illumina NovaSeq 6000",
            "paired_end": True,
            "read_length": 150,
            "phenotype_hpo": ["HP:0001250", "HP:0000707"],
            "clinical_diagnosis": "Hereditary breast cancer syndrome",
            "referring_physician": "Dr. Smith",
            "institution": "Test Hospital",
            **kwargs,
        }
    return _factory


@pytest.fixture
def sample_factory(db_session, sample_data_factory):
    def _factory(**kwargs):
        data = sample_data_factory(**kwargs)
        sample = Sample(**data)
        db_session.add(sample)
        db_session.commit()
        db_session.refresh(sample)
        return sample
    return _factory


@pytest.fixture
def normal_sample(sample_factory):
    return sample_factory(
        sample_id="NORMAL_SAMPLE_001",
        sample_type=SampleType.WES,
        status=SampleStatus.REGISTERED,
        clinical_diagnosis="Suspected rare disease",
    )


@pytest.fixture
def low_quality_sample(sample_factory):
    return sample_factory(
        sample_id="LOWQ_SAMPLE_001",
        sample_type=SampleType.WES,
        status=SampleStatus.REGISTERED,
        clinical_diagnosis="Low quality test sample",
        qc_metrics={"expected_issues": ["low_mapping_rate"]},
    )


@pytest.fixture
def tumor_sample(sample_factory):
    return sample_factory(
        sample_id="TUMOR_SAMPLE_001",
        sample_type=SampleType.cfDNA,
        status=SampleStatus.REGISTERED,
        clinical_diagnosis="Metastatic breast cancer - cfDNA liquid biopsy",
    )


@pytest.fixture
def family_samples(sample_factory):
    samples = []
    samples.append(sample_factory(
        sample_id="PROBAND_001",
        sample_type=SampleType.WES,
        patient_id="FAM001_PROBAND",
        clinical_diagnosis="Proband with suspected Mendelian disorder",
    ))
    samples.append(sample_factory(
        sample_id="MOTHER_001",
        sample_type=SampleType.WES,
        patient_id="FAM001_MOTHER",
        clinical_diagnosis="Unaffected mother",
    ))
    samples.append(sample_factory(
        sample_id="FATHER_001",
        sample_type=SampleType.WES,
        patient_id="FAM001_FATHER",
        clinical_diagnosis="Unaffected father",
    ))
    return samples


@pytest.fixture
def task_factory(db_session):
    def _factory(sample: Sample, status: TaskStatus = TaskStatus.PENDING, **kwargs):
        task = AnalysisTask(
            task_id=f"TASK_{uuid.uuid4().hex[:12]}",
            task_name=kwargs.get("task_name", "Single sample analysis"),
            sample_id=sample.id,
            pipeline_version="1.0.0",
            reference_genome="hg38",
            status=status,
            priority=kwargs.get("priority", 0),
            **{k: v for k, v in kwargs.items() if k not in ["task_name", "priority"]}
        )
        db_session.add(task)
        db_session.commit()
        db_session.refresh(task)
        return task
    return _factory


@pytest.fixture
def variant_factory(db_session):
    def _factory(sample: Sample, **kwargs):
        variant = Variant(
            sample_id=sample.id,
            variant_id=f"VAR_{uuid.uuid4().hex[:12]}",
            chromosome=kwargs.get("chromosome", "chr17"),
            position=kwargs.get("position", 43045629),
            ref=kwargs.get("ref", "T"),
            alt=kwargs.get("alt", "C"),
            variant_type=kwargs.get("variant_type", "SNV"),
            genotype=kwargs.get("genotype", "0/1"),
            genotype_quality=kwargs.get("genotype_quality", 99.0),
            depth=kwargs.get("depth", 120),
            allele_depth=kwargs.get("allele_depth", 58),
            allele_frequency=kwargs.get("allele_frequency", 0.483),
            gene=kwargs.get("gene", "BRCA1"),
            transcript=kwargs.get("transcript", "ENST00000357654"),
            hgvsc=kwargs.get("hgvsc", "NM_007294.4:c.5266T>C"),
            hgvsp=kwargs.get("hgvsp", "NP_009225.1:p.Cys1756Arg"),
            consequence=kwargs.get("consequence", "missense_variant"),
            impact=kwargs.get("impact", "MODERATE"),
            gnomad_af=kwargs.get("gnomad_af", 0.000123),
            thousandg_af=kwargs.get("thousandg_af", 0.0002),
            cadd_score=kwargs.get("cadd_score", 28.5),
            revel_score=kwargs.get("revel_score", 0.92),
            sift_score=kwargs.get("sift_score", 0.01),
            polyphen2_score=kwargs.get("polyphen2_score", 0.98),
            clinvar_id=kwargs.get("clinvar_id", "RCV000000123"),
            clinvar_clinsig=kwargs.get("clinvar_clinsig", "Likely pathogenic"),
            acmg_classification=kwargs.get("acmg_classification", ACMGClassification.LIKELY_PATHOGENIC),
            acmg_score=kwargs.get("acmg_score", 7.0),
            acmg_criteria=kwargs.get("acmg_criteria", ["PS2", "PM2", "PP3"]),
            is_secondary_finding=kwargs.get("is_secondary_finding", True),
        )
        for k, v in kwargs.items():
            if hasattr(variant, k):
                setattr(variant, k, v)
        db_session.add(variant)
        db_session.commit()
        db_session.refresh(variant)
        return variant
    return _factory


@pytest.fixture
def temp_work_dir(tmp_path):
    work_dir = tmp_path / "pipeline_work"
    work_dir.mkdir(parents=True, exist_ok=True)
    temp_dir = tmp_path / "pipeline_temp"
    temp_dir.mkdir(parents=True, exist_ok=True)
    log_dir = tmp_path / "pipeline_logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    return {
        "work_dir": work_dir,
        "temp_dir": temp_dir,
        "log_dir": log_dir,
    }


@pytest.fixture
def mock_fastq_files(tmp_path):
    data_dir = tmp_path / "fastq_data"
    data_dir.mkdir(parents=True, exist_ok=True)

    r1_content = (
        "@SRR000001.1\n"
        "AGATCGGAAGAGCACACGTCTGAACTCCAGTCACNNNNNNATCTCGTATGCCGTCTTCTGCTTG\n"
        "+\n"
        "FFFFFFFFFFFFFFFFFFFFFBBBBBBBBBBBBBBBBBBBBBBB####################\n"
        "@SRR000001.2\n"
        "CGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCG\n"
        "+\n"
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF\n"
    )
    r2_content = (
        "@SRR000001.1\n"
        "CAAGCAGAAGACGGCATACGAGATNNNNNNGTGACTGGAGTTCAGACGTGTGCTCTTCCGATCT\n"
        "+\n"
        "FFFFFFFFFFFFFFFFFFFFFBBBBBBBBBBBBBBBBBBBBBBB####################\n"
        "@SRR000001.2\n"
        "GCTAGCTAGCTAGCTAGCTAGCTAGCTAGCTAGCTAGCTAGCTAGCTAGCTAGCTAGCTAGCT\n"
        "+\n"
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF\n"
    )

    r1_path = data_dir / "test_R1.fastq.gz"
    r2_path = data_dir / "test_R2.fastq.gz"

    import gzip
    with gzip.open(r1_path, "wt") as f:
        f.write(r1_content)
    with gzip.open(r2_path, "wt") as f:
        f.write(r2_content)

    return {
        "r1": r1_path,
        "r2": r2_path,
        "data_dir": data_dir,
        "md5_r1": hashlib.md5(r1_path.read_bytes()).hexdigest(),
        "md5_r2": hashlib.md5(r2_path.read_bytes()).hexdigest(),
        "size_r1": r1_path.stat().st_size,
        "size_r2": r2_path.stat().st_size,
    }


@pytest.fixture
def known_brca1_variants():
    return [
        {
            "chromosome": "chr17",
            "position": 43045629,
            "ref": "T",
            "alt": "C",
            "gene": "BRCA1",
            "hgvsc": "c.5266T>C",
            "hgvsp": "p.Cys1756Arg",
            "variant_type": "SNV",
            "acmg_expected": ACMGClassification.LIKELY_PATHOGENIC,
            "clinvar_clinsig": "Likely pathogenic",
        },
        {
            "chromosome": "chr17",
            "position": 43047643,
            "ref": "GA",
            "alt": "G",
            "gene": "BRCA1",
            "hgvsc": "c.3256delA",
            "hgvsp": "p.Lys1086fs",
            "variant_type": "Indel",
            "acmg_expected": ACMGClassification.PATHOGENIC,
            "clinvar_clinsig": "Pathogenic",
        },
        {
            "chromosome": "chr17",
            "position": 43067607,
            "ref": "C",
            "alt": "CGAAAGCGGTACATGCCTAAGATTGTCACTCA",
            "gene": "BRCA1",
            "hgvsc": "c.1010_1011ins30",
            "hgvsp": "p.(=)",
            "variant_type": "Indel",
            "acmg_expected": ACMGClassification.UNCERTAIN_SIGNIFICANCE,
            "clinvar_clinsig": "Uncertain significance",
        },
    ]


@pytest.fixture
def pipeline_steps_from_json():
    def _factory(json_def: Dict[str, Any]) -> List[PipelineStep]:
        steps = []
        for s in json_def.get("steps", []):
            step_type = s.get("step_type")
            if isinstance(step_type, str):
                step_type = PipelineStepType(step_type)
            steps.append(PipelineStep(
                step_id=s["step_id"],
                step_type=step_type,
                name=s.get("name", s["step_id"]),
                description=s.get("description", ""),
                inputs=s.get("inputs", []),
                outputs=s.get("outputs", []),
                dependencies=s.get("dependencies", []),
                params=s.get("params", {}),
                max_retries=s.get("max_retries", 3),
                parallel_group=s.get("parallel_group"),
                is_parallel=s.get("is_parallel", False),
            ))
        return steps
    return _factory


@pytest.fixture
def mock_minio_client():
    with patch("storage.minio_client.get_minio_client") as mock_getter:
        client = Mock()
        client.bucket_exists.return_value = True
        client.upload_file.return_value = True
        client.download_file.return_value = True
        client.delete_object.return_value = True
        client.get_object_metadata.return_value = {}
        client.upload_sample_fastq.return_value = {
            "r1_object": "samples/test/fastq/test_R1.fastq.gz",
            "r2_object": "samples/test/fastq/test_R2.fastq.gz",
            "r1_size": 1024000,
            "r2_size": 1024000,
        }
        client.upload_analysis_results.return_value = [
            "samples/test/results/test.vcf.gz",
            "samples/test/results/test_report.pdf",
        ]
        client.upload_report.return_value = "samples/test/reports/test_report.pdf"
        mock_getter.return_value = client
        yield client


@pytest.fixture
def mock_celery_task():
    task = Mock()
    task.id = str(uuid.uuid4())
    task.apply_async.return_value = Mock(id=str(uuid.uuid4()))
    task.delay.return_value = Mock(id=str(uuid.uuid4()))
    return task
