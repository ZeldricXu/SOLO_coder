from typing import Any, Dict, List, Optional
from core import emit_event, EventTypes
from .task_executor import Task, TaskStatus, task_executor


class WorkflowStatus:
    CREATED = "created"
    RUNNING = "running"
    PAUSED = "paused"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class WorkflowStep:
    def __init__(
        self,
        step_id: str,
        name: str,
        task_type: str,
        payload: Dict[str, Any],
        dependencies: Optional[List[str]] = None,
        timeout: int = 300,
        retry_count: int = 3,
        on_failure: str = "stop",
    ):
        self.step_id = step_id
        self.name = name
        self.task_type = task_type
        self.payload = payload
        self.dependencies = dependencies or []
        self.timeout = timeout
        self.retry_count = retry_count
        self.on_failure = on_failure
        self.status = TaskStatus.PENDING
        self.task_id: Optional[str] = None
        self.result: Optional[Any] = None
        self.error: Optional[str] = None


class Workflow:
    def __init__(
        self,
        name: str,
        steps: List[WorkflowStep],
        metadata: Optional[Dict[str, Any]] = None,
    ):
        self.workflow_id = None
        self.name = name
        self.steps = {step.step_id: step for step in steps}
        self.metadata = metadata or {}
        self.status = WorkflowStatus.CREATED
        self.created_at = None
        self.started_at = None
        self.completed_at = None
        self.variables: Dict[str, Any] = {}

    def get_step(self, step_id: str) -> Optional[WorkflowStep]:
        return self.steps.get(step_id)

    def get_runnable_steps(self) -> List[WorkflowStep]:
        runnable = []
        for step in self.steps.values():
            if step.status != TaskStatus.PENDING:
                continue

            deps_met = all(
                self.steps[dep_id].status == TaskStatus.SUCCESS
                for dep_id in step.dependencies
                if dep_id in self.steps
            )

            if deps_met:
                runnable.append(step)

        return runnable

    def is_complete(self) -> bool:
        return all(
            step.status in [TaskStatus.SUCCESS, TaskStatus.FAILED, TaskStatus.SKIPPED]
            for step in self.steps.values()
        )

    def is_successful(self) -> bool:
        return all(
            step.status == TaskStatus.SUCCESS
            for step in self.steps.values()
        )


class WorkflowEngine:
    def __init__(self):
        self._workflows: Dict[str, Workflow] = {}
        self._task_to_step: Dict[str, str] = {}

    def create_workflow(self, name: str, steps: List[WorkflowStep]) -> str:
        workflow = Workflow(name, steps)
        workflow.workflow_id = None
        self._workflows[name] = workflow
        return name

    async def start_workflow(self, name: str) -> Workflow:
        if name not in self._workflows:
            raise ValueError(f"Workflow not found: {name}")

        workflow = self._workflows[name]
        workflow.status = WorkflowStatus.RUNNING

        await self._schedule_steps(workflow)
        return workflow

    async def _schedule_steps(self, workflow: Workflow) -> None:
        runnable_steps = workflow.get_runnable_steps()

        for step in runnable_steps:
            task = Task(
                task_type=step.task_type,
                payload=step.payload,
                timeout=step.timeout,
                max_retries=step.retry_count,
            )

            step.status = TaskStatus.SCHEDULED
            step.task_id = await task_executor.submit(task)
            self._task_to_step[step.task_id] = f"{workflow.name}:{step.step_id}"

            emit_event(
                EventTypes.TASK_CREATED,
                "workflow_engine",
                {
                    "workflow_name": workflow.name,
                    "step_id": step.step_id,
                    "task_id": step.task_id,
                },
            )

    async def handle_task_completion(self, task_id: str, success: bool, result: Any) -> None:
        if task_id not in self._task_to_step:
            return

        workflow_name, step_id = self._task_to_step[task_id].split(":", 1)
        workflow = self._workflows.get(workflow_name)

        if not workflow:
            return

        step = workflow.get_step(step_id)
        if not step:
            return

        if success:
            step.status = TaskStatus.SUCCESS
            step.result = result
        else:
            step.status = TaskStatus.FAILED
            step.error = str(result) if result else "Unknown error"

            if step.on_failure == "skip":
                step.status = TaskStatus.SKIPPED
            elif step.on_failure == "stop":
                workflow.status = WorkflowStatus.FAILED
                return

        if workflow.is_complete():
            workflow.status = (
                WorkflowStatus.COMPLETED
                if workflow.is_successful()
                else WorkflowStatus.FAILED
            )
            return

        await self._schedule_steps(workflow)

    def get_workflow(self, name: str) -> Optional[Workflow]:
        return self._workflows.get(name)

    def list_workflows(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": name,
                "status": wf.status,
                "step_count": len(wf.steps),
                "created_at": wf.created_at,
            }
            for name, wf in self._workflows.items()
        ]


workflow_engine = WorkflowEngine()
