from typing import List, Dict, Any, Optional, Tuple
from datetime import datetime, timezone
import asyncio
import heapq
from collections import defaultdict

from .schemas import (
    JobStatus,
    JobPriority,
    PreemptionPolicy,
    SchedulerStatus,
    GPUResource,
    GPUComputeNode,
    GPUJob,
    GPUJobRequest,
    GPUJobResponse,
    ResourceRequest,
    JobCancelResponse,
    ClusterStatusResponse,
    SchedulerMetrics,
)
from common.logger import get_logger
from common.utils import generate_id, utc_now

logger = get_logger(__name__)


class GPUSchedulerService:
    def __init__(self):
        self.nodes: Dict[str, GPUComputeNode] = {}
        self.jobs: Dict[str, GPUJob] = {}
        self.pending_queue: List[Tuple[int, int, str]] = []
        self.running_jobs: Dict[str, str] = {}
        self.scheduler_status: SchedulerStatus = SchedulerStatus.RUNNING
        self.cluster_id = "llm-gateway-gpu-cluster-001"
        self._counter = 0

        self._init_default_nodes()

    def _init_default_nodes(self):
        default_nodes = [
            {
                "node_id": "node-a100-01",
                "hostname": "gpu-a100-01.local",
                "ip_address": "192.168.1.10",
                "region": "cn-east",
                "zone": "cn-east-1a",
                "total_gpus": GPUResource(gpu_type="A100", gpu_count=8, vram_gb=80.0 * 8),
                "available_gpus": GPUResource(gpu_type="A100", gpu_count=8, vram_gb=80.0 * 8),
                "cpu_cores": 128,
                "memory_gb": 512.0,
            },
            {
                "node_id": "node-a100-02",
                "hostname": "gpu-a100-02.local",
                "ip_address": "192.168.1.11",
                "region": "cn-east",
                "zone": "cn-east-1a",
                "total_gpus": GPUResource(gpu_type="A100", gpu_count=8, vram_gb=80.0 * 8),
                "available_gpus": GPUResource(gpu_type="A100", gpu_count=8, vram_gb=80.0 * 8),
                "cpu_cores": 128,
                "memory_gb": 512.0,
            },
            {
                "node_id": "node-h100-01",
                "hostname": "gpu-h100-01.local",
                "ip_address": "192.168.1.20",
                "region": "cn-east",
                "zone": "cn-east-1b",
                "total_gpus": GPUResource(gpu_type="H100", gpu_count=8, vram_gb=80.0 * 8),
                "available_gpus": GPUResource(gpu_type="H100", gpu_count=8, vram_gb=80.0 * 8),
                "cpu_cores": 128,
                "memory_gb": 1024.0,
            },
            {
                "node_id": "node-rtx4090-01",
                "hostname": "gpu-rtx4090-01.local",
                "ip_address": "192.168.1.30",
                "region": "cn-east",
                "zone": "cn-east-1c",
                "total_gpus": GPUResource(gpu_type="RTX4090", gpu_count=8, vram_gb=24.0 * 8),
                "available_gpus": GPUResource(gpu_type="RTX4090", gpu_count=8, vram_gb=24.0 * 8),
                "cpu_cores": 64,
                "memory_gb": 256.0,
            },
        ]

        for node_data in default_nodes:
            node = GPUComputeNode(
                **node_data,
                registered_at=utc_now(),
                last_heartbeat=utc_now(),
            )
            self.nodes[node.node_id] = node

        logger.info(f"Initialized {len(self.nodes)} GPU compute nodes")

    async def submit_job(self, request: GPUJobRequest) -> GPUJobResponse:
        if self.scheduler_status in [SchedulerStatus.PAUSED, SchedulerStatus.DRAINING]:
            raise ValueError(f"Scheduler is {self.scheduler_status.value}, not accepting new jobs")

        job_id = generate_id("job_gpu_")
        now = utc_now()

        job = GPUJob(
            job_id=job_id,
            job_name=request.job_name,
            job_type=request.job_type,
            resource_request=request.resource_request,
            priority=request.priority,
            status=JobStatus.PENDING,
            max_runtime_seconds=request.max_runtime_seconds,
            deadline=request.deadline,
            command=request.command,
            args=request.args,
            env_vars=request.env_vars,
            working_dir=request.working_dir,
            preemption_policy=request.preemption_policy,
            allow_preemption=request.allow_preemption,
            checkpoint_path=request.checkpoint_path,
            retry_count=request.retry_count,
            tags=request.tags,
            submitted_by=request.submitted_by,
            submitted_at=now,
        )

        self.jobs[job_id] = job

        self._counter += 1
        heapq.heappush(self.pending_queue, (-request.priority.value, self._counter, job_id))

        logger.info(f"Job {job_id} ({request.job_name}) submitted with priority {request.priority.name}")

        asyncio.create_task(self._try_schedule())

        queue_pos = self._get_queue_position(job_id)
        wait_time = self._estimate_wait_time(request.priority, request.resource_request)

        return GPUJobResponse(
            job=job,
            queue_position=queue_pos,
            estimated_wait_time_seconds=wait_time,
        )

    async def cancel_job(self, job_id: str, force: bool = False, reason: Optional[str] = None) -> JobCancelResponse:
        if job_id not in self.jobs:
            raise ValueError(f"Job {job_id} not found")

        job = self.jobs[job_id]

        if job.status in [JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.PREEMPTED]:
            return JobCancelResponse(
                job_id=job_id,
                status=job.status,
                message=f"Job already in terminal state: {job.status.value}",
            )

        if job.status == JobStatus.RUNNING and job.allocated_node_id:
            self._release_resources(job.allocated_node_id, job)
            if job_id in self.running_jobs:
                del self.running_jobs[job_id]

        if job.status == JobStatus.PENDING:
            self.pending_queue = [(p, c, j) for (p, c, j) in self.pending_queue if j != job_id]
            heapq.heapify(self.pending_queue)

        job.status = JobStatus.CANCELLED
        job.completed_at = utc_now()
        job.error_message = reason or "Cancelled by user"

        logger.info(f"Job {job_id} cancelled: {reason}")

        return JobCancelResponse(
            job_id=job_id,
            status=JobStatus.CANCELLED,
            message=f"Job cancelled successfully: {reason}" if reason else "Job cancelled successfully",
        )

    def get_job(self, job_id: str) -> GPUJob:
        if job_id not in self.jobs:
            raise ValueError(f"Job {job_id} not found")
        return self.jobs[job_id]

    def list_jobs(
        self,
        status: Optional[JobStatus] = None,
        priority: Optional[JobPriority] = None,
        job_type: Optional[str] = None,
        submitted_by: Optional[str] = None,
        node_id: Optional[str] = None,
        limit: int = 100,
    ) -> List[GPUJob]:
        jobs = list(self.jobs.values())

        if status:
            jobs = [j for j in jobs if j.status == status]
        if priority:
            jobs = [j for j in jobs if j.priority == priority]
        if job_type:
            jobs = [j for j in jobs if j.job_type == job_type]
        if submitted_by:
            jobs = [j for j in jobs if j.submitted_by == submitted_by]
        if node_id:
            jobs = [j for j in jobs if j.allocated_node_id == node_id]

        jobs.sort(key=lambda j: j.submitted_at, reverse=True)
        return jobs[:limit]

    def get_cluster_status(self) -> ClusterStatusResponse:
        total_gpus = sum(n.total_gpus.gpu_count for n in self.nodes.values())
        available_gpus = sum(n.available_gpus.gpu_count for n in self.nodes.values())
        total_vram = sum(n.total_gpus.vram_gb for n in self.nodes.values())
        available_vram = sum(n.available_gpus.vram_gb for n in self.nodes.values())
        active_nodes = sum(1 for n in self.nodes.values() if n.status == "active")

        pending = sum(1 for j in self.jobs.values() if j.status == JobStatus.PENDING)
        running = sum(1 for j in self.jobs.values() if j.status == JobStatus.RUNNING)
        completed = sum(1 for j in self.jobs.values() if j.status == JobStatus.COMPLETED)
        failed = sum(1 for j in self.jobs.values() if j.status in [JobStatus.FAILED, JobStatus.PREEMPTED])

        gpu_utilization = (total_gpus - available_gpus) / total_gpus if total_gpus > 0 else 0.0

        metrics = SchedulerMetrics(
            total_jobs=len(self.jobs),
            pending_jobs=pending,
            running_jobs=running,
            completed_jobs=completed,
            failed_jobs=failed,
            average_wait_time_seconds=10.0,
            average_run_time_seconds=300.0,
            total_gpu_utilization=gpu_utilization,
            queue_depth=len(self.pending_queue),
        )

        return ClusterStatusResponse(
            cluster_id=self.cluster_id,
            scheduler_status=self.scheduler_status,
            total_nodes=len(self.nodes),
            active_nodes=active_nodes,
            total_gpus=total_gpus,
            available_gpus=available_gpus,
            total_vram_gb=total_vram,
            available_vram_gb=available_vram,
            nodes=list(self.nodes.values()),
            metrics=metrics,
            last_updated=utc_now(),
        )

    async def update_job_progress(self, job_id: str, progress: float, metrics: Optional[Dict[str, Any]] = None) -> GPUJob:
        if job_id not in self.jobs:
            raise ValueError(f"Job {job_id} not found")

        job = self.jobs[job_id]
        job.progress = max(0.0, min(1.0, progress))
        if metrics:
            job.metrics = metrics

        if progress >= 1.0 and job.status == JobStatus.RUNNING:
            job.status = JobStatus.COMPLETED
            job.completed_at = utc_now()
            if job.allocated_node_id:
                self._release_resources(job.allocated_node_id, job)
                if job_id in self.running_jobs:
                    del self.running_jobs[job_id]
            asyncio.create_task(self._try_schedule())

        return job

    def register_node(self, node: GPUComputeNode) -> GPUComputeNode:
        if node.node_id in self.nodes:
            raise ValueError(f"Node {node.node_id} already registered")
        self.nodes[node.node_id] = node
        logger.info(f"Node {node.node_id} registered")
        return node

    def unregister_node(self, node_id: str) -> bool:
        if node_id not in self.nodes:
            return False
        del self.nodes[node_id]
        logger.info(f"Node {node_id} unregistered")
        return True

    async def _try_schedule(self):
        if self.scheduler_status != SchedulerStatus.RUNNING:
            return

        scheduled = []
        while self.pending_queue:
            priority, counter, job_id = heapq.heappop(self.pending_queue)
            job = self.jobs.get(job_id)

            if not job or job.status != JobStatus.PENDING:
                continue

            node = self._find_best_node(job.resource_request)
            if node:
                self._allocate_resources(node.node_id, job)
                job.status = JobStatus.SCHEDULED
                job.allocated_node_id = node.node_id
                job.scheduled_at = utc_now()

                asyncio.create_task(self._start_job(job))
                scheduled.append(job_id)
            else:
                if job.priority == JobPriority.EMERGENCY or job.priority == JobPriority.CRITICAL:
                    preempted = self._try_preempt(job)
                    if preempted:
                        node = self._find_best_node(job.resource_request)
                        if node:
                            self._allocate_resources(node.node_id, job)
                            job.status = JobStatus.SCHEDULED
                            job.allocated_node_id = node.node_id
                            job.scheduled_at = utc_now()
                            asyncio.create_task(self._start_job(job))
                            scheduled.append(job_id)
                            continue

                heapq.heappush(self.pending_queue, (priority, counter, job_id))
                break

        if scheduled:
            logger.info(f"Scheduled {len(scheduled)} jobs")

    async def _start_job(self, job: GPUJob):
        await asyncio.sleep(0.1)
        job.status = JobStatus.RUNNING
        job.started_at = utc_now()
        self.running_jobs[job.job_id] = job.allocated_node_id
        logger.info(f"Job {job.job_id} started on node {job.allocated_node_id}")

    def _find_best_node(self, request: ResourceRequest) -> Optional[GPUComputeNode]:
        candidates = []
        for node in self.nodes.values():
            if node.status != "active":
                continue
            if request.regions and node.region not in request.regions:
                continue
            if request.gpu_type and node.available_gpus.gpu_type != request.gpu_type:
                continue
            if node.available_gpus.gpu_count < request.min_gpu_count:
                continue
            if request.min_vram_gb and node.available_gpus.vram_gb < request.min_vram_gb:
                continue
            if node.cpu_cores < request.cpu_cores:
                continue
            if node.memory_gb < request.memory_gb:
                continue
            if request.node_labels:
                if not node.labels or not all(node.labels.get(k) == v for k, v in request.node_labels.items()):
                    continue

            score = (
                node.available_gpus.gpu_count
                + node.available_gpus.vram_gb / 10
                + node.cpu_cores / 10
            )
            candidates.append((score, node))

        if not candidates:
            return None

        candidates.sort(key=lambda x: x[0], reverse=True)
        return candidates[0][1]

    def _allocate_resources(self, node_id: str, job: GPUJob):
        node = self.nodes[node_id]
        request = job.resource_request

        allocated = GPUResource(
            gpu_type=node.available_gpus.gpu_type,
            gpu_count=request.min_gpu_count,
            vram_gb=request.min_vram_gb or (request.min_gpu_count * 80.0),
        )

        node.available_gpus.gpu_count -= allocated.gpu_count
        node.available_gpus.vram_gb -= allocated.vram_gb
        job.allocated_gpus = allocated

        logger.info(
            f"Allocated {allocated.gpu_count}x {allocated.gpu_type} GPUs on {node_id} for job {job.job_id}"
        )

    def _release_resources(self, node_id: str, job: GPUJob):
        if node_id not in self.nodes:
            return

        node = self.nodes[node_id]
        if job.allocated_gpus:
            node.available_gpus.gpu_count += job.allocated_gpus.gpu_count
            node.available_gpus.vram_gb += job.allocated_gpus.vram_gb
            logger.info(
                f"Released {job.allocated_gpus.gpu_count}x {job.allocated_gpus.gpu_type} GPUs on {node_id}"
            )

    def _try_preempt(self, new_job: GPUJob) -> bool:
        if not new_job.allow_preemption:
            return False

        running_list = [
            (self.jobs[jid], nid)
            for jid, nid in self.running_jobs.items()
            if self.jobs[jid].allow_preemption and self.jobs[jid].priority < new_job.priority
        ]

        if not running_list:
            return False

        running_list.sort(key=lambda x: (x[0].priority, -x[0].progress))

        for job, node_id in running_list:
            if job.priority < new_job.priority:
                asyncio.create_task(self._preempt_job(job, node_id, new_job))
                return True

        return False

    async def _preempt_job(self, job: GPUJob, node_id: str, preempting_job: GPUJob):
        logger.warning(f"Preempting job {job.job_id} for higher priority job {preempting_job.job_id}")

        job.status = JobStatus.PREEMPTED
        job.completed_at = utc_now()
        job.error_message = f"Preempted by higher priority job {preempting_job.job_id}"

        self._release_resources(node_id, job)
        if job.job_id in self.running_jobs:
            del self.running_jobs[job.job_id]

        if job.retry_count > 0:
            job.retry_count -= 1
            job.status = JobStatus.PENDING
            job.submitted_at = utc_now()
            job.scheduled_at = None
            job.started_at = None
            job.completed_at = None
            job.allocated_node_id = None
            job.allocated_gpus = None
            job.progress = 0.0
            job.error_message = None

            self._counter += 1
            heapq.heappush(self.pending_queue, (-job.priority.value, self._counter, job.job_id))
            logger.info(f"Job {job.job_id} requeued with {job.retry_count} retries remaining")

    def _get_queue_position(self, job_id: str) -> int:
        for i, (p, c, j) in enumerate(sorted(self.pending_queue)):
            if j == job_id:
                return i + 1
        return None

    def _estimate_wait_time(self, priority: JobPriority, request: ResourceRequest) -> float:
        base_wait = {
            JobPriority.LOW: 300.0,
            JobPriority.MEDIUM: 60.0,
            JobPriority.HIGH: 10.0,
            JobPriority.CRITICAL: 1.0,
            JobPriority.EMERGENCY: 0.1,
        }

        wait = base_wait.get(priority, 60.0)
        wait *= request.min_gpu_count

        return wait


gpu_scheduler_service = GPUSchedulerService()
