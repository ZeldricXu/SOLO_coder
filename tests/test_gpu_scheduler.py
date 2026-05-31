import pytest
from gpu_scheduler import (
    GPUJobCreateRequest,
    JobPriority,
    gpu_scheduler_service,
)


@pytest.mark.asyncio
async def test_submit_job():
    request = GPUJobCreateRequest(
        job_name="test-training-job",
        job_type="training",
        priority=JobPriority.MEDIUM,
        gpu_count=2,
        estimated_duration_minutes=30,
        command="python train.py",
    )
    job = await gpu_scheduler_service.submit_job(request)
    assert job.job_name == "test-training-job"
    assert job.gpu_count == 2


@pytest.mark.asyncio
async def test_list_jobs():
    jobs = await gpu_scheduler_service.list_jobs(limit=10)
    assert isinstance(jobs, list)


def test_cluster_info():
    info = gpu_scheduler_service.get_cluster_info()
    assert "total_gpus" in info
    assert "available_gpus" in info
