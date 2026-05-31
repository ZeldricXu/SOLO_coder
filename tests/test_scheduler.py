import pytest
import asyncio

from top.domain.scheduling import (
    WorkflowEngine,
    TaskGraph,
    TaskExecutor,
    DependencyGraph,
    DependencyResolver,
    WorkflowDefinition,
    TaskDefinition,
    TaskResult,
    ExecutionResult,
    ExecutionContext,
    ExecutionStatus,
    ExecutionPhase,
    RetryPolicy,
    TaskScheduler,
    get_workflow_engine,
)


class TestDependencyGraph:
    def test_create_from_tasks(self):
        tasks = [
            TaskDefinition(task_id="A", name="Task A", dependencies=[], handler="handler"),
            TaskDefinition(task_id="B", name="Task B", dependencies=["A"], handler="handler"),
            TaskDefinition(task_id="C", name="Task C", dependencies=["A"], handler="handler"),
            TaskDefinition(task_id="D", name="Task D", dependencies=["B", "C"], handler="handler"),
        ]

        graph = DependencyGraph(tasks)

        order = graph.topological_order()

        assert len(order) == 4
        assert order.index("A") < order.index("B")
        assert order.index("A") < order.index("C")
        assert order.index("B") < order.index("D")
        assert order.index("C") < order.index("D")

    def test_cycle_detection(self):
        tasks = [
            TaskDefinition(task_id="A", name="Task A", dependencies=["B"], handler="handler"),
            TaskDefinition(task_id="B", name="Task B", dependencies=["A"], handler="handler"),
        ]

        graph = DependencyGraph(tasks)

        assert graph.has_cycle() is True

    def test_no_cycle(self):
        tasks = [
            TaskDefinition(task_id="A", name="Task A", dependencies=[], handler="handler"),
            TaskDefinition(task_id="B", name="Task B", dependencies=["A"], handler="handler"),
        ]

        graph = DependencyGraph(tasks)

        assert graph.has_cycle() is False

    def test_get_ready_tasks(self):
        tasks = [
            TaskDefinition(task_id="A", name="Task A", dependencies=[], handler="handler"),
            TaskDefinition(task_id="B", name="Task B", dependencies=["A"], handler="handler"),
            TaskDefinition(task_id="C", name="Task C", dependencies=["A"], handler="handler"),
            TaskDefinition(task_id="D", name="Task D", dependencies=[], handler="handler"),
        ]

        graph = DependencyGraph(tasks)

        ready = graph.get_ready_tasks(completed=set(), failed=set())
        assert "A" in ready
        assert "D" in ready
        assert "B" not in ready
        assert "C" not in ready

        ready_after_a = graph.get_ready_tasks(completed={"A"}, failed=set())
        assert "B" in ready_after_a
        assert "C" in ready_after_a


class TestTaskExecutor:
    @pytest.mark.asyncio
    async def test_execute_success(self):
        executor = TaskExecutor()

        async def handler_fn(task, context):
            return {"processed": True, "task_id": task.task_id}

        executor.register_handler("test_handler", handler_fn)

        task = TaskDefinition(
            task_id="test_1",
            name="Test Task",
            dependencies=[],
            handler="test_handler",
        )
        context = ExecutionContext(
            run_id="run_1",
            trace_id="trace_1",
            workflow_id="wf_1",
            task_id="test_1",
        )

        result = await executor.execute(task, context)

        assert result.task_id == "test_1"
        assert result.status == ExecutionStatus.SUCCESS
        assert result.result["processed"] is True

    @pytest.mark.asyncio
    async def test_execute_handler_not_found(self):
        executor = TaskExecutor()

        task = TaskDefinition(
            task_id="test_1",
            name="Test Task",
            dependencies=[],
            handler="nonexistent_handler",
        )
        context = ExecutionContext(
            run_id="run_1",
            trace_id="trace_1",
            workflow_id="wf_1",
            task_id="test_1",
        )

        result = await executor.execute(task, context)

        assert result.status == ExecutionStatus.FAILED
        assert "not registered" in result.error

    @pytest.mark.asyncio
    async def test_execute_with_retry(self):
        call_count = [0]

        async def flaky_handler(task, context):
            call_count[0] += 1
            if call_count[0] < 3:
                raise RuntimeError(f"Attempt {call_count[0]} failed")
            return {"success": True}

        executor = TaskExecutor()
        executor.register_handler("flaky", flaky_handler)

        task = TaskDefinition(
            task_id="test_1",
            name="Test Task",
            dependencies=[],
            handler="flaky",
            retries=3,
        )
        context = ExecutionContext(
            run_id="run_1",
            trace_id="trace_1",
            workflow_id="wf_1",
            task_id="test_1",
        )
        policy = RetryPolicy(
            max_retries=3,
            initial_delay_ms=10,
            max_delay_ms=100,
        )

        result = await executor.execute(task, context, policy)

        assert result.status == ExecutionStatus.SUCCESS
        assert call_count[0] == 3

    @pytest.mark.asyncio
    async def test_execute_exhaust_retries(self):
        call_count = [0]

        async def always_fail(task, context):
            call_count[0] += 1
            raise RuntimeError(f"Attempt {call_count[0]} failed")

        executor = TaskExecutor()
        executor.register_handler("fail", always_fail)

        task = TaskDefinition(
            task_id="test_1",
            name="Test Task",
            dependencies=[],
            handler="fail",
            retries=2,
        )
        context = ExecutionContext(
            run_id="run_1",
            trace_id="trace_1",
            workflow_id="wf_1",
            task_id="test_1",
        )
        policy = RetryPolicy(
            max_retries=2,
            initial_delay_ms=10,
            max_delay_ms=50,
        )

        result = await executor.execute(task, context, policy)

        assert result.status == ExecutionStatus.FAILED
        assert call_count[0] == 3


class TestWorkflowEngine:
    @pytest.mark.asyncio
    async def test_simple_sequential_workflow(self):
        engine = WorkflowEngine()
        results = []

        async def handler_a(task, context):
            results.append("A")
            return {"task": "A"}

        async def handler_b(task, context):
            results.append("B")
            return {"task": "B"}

        async def handler_c(task, context):
            results.append("C")
            return {"task": "C"}

        engine.register_task_handler("handler_a", handler_a)
        engine.register_task_handler("handler_b", handler_b)
        engine.register_task_handler("handler_c", handler_c)

        workflow = WorkflowDefinition(
            workflow_id="wf_simple",
            name="Simple Workflow",
            tasks=[
                TaskDefinition(task_id="A", name="Task A", dependencies=[], handler="handler_a"),
                TaskDefinition(task_id="B", name="Task B", dependencies=["A"], handler="handler_b"),
                TaskDefinition(task_id="C", name="Task C", dependencies=["B"], handler="handler_c"),
            ],
        )

        result = await engine.run_workflow_definition(workflow)

        assert result.status == ExecutionStatus.SUCCESS
        assert len(result.task_results) == 3
        assert results == ["A", "B", "C"]

    @pytest.mark.asyncio
    async def test_parallel_workflow(self):
        engine = WorkflowEngine()
        results = []

        async def handler(task, context):
            task_name = task.task_id
            results.append(task_name)
            await asyncio.sleep(0.01)
            return {"task": task_name}

        engine.register_task_handler("handler", handler)

        workflow = WorkflowDefinition(
            workflow_id="wf_parallel",
            name="Parallel Workflow",
            tasks=[
                TaskDefinition(task_id="A", name="Task A", dependencies=[], handler="handler"),
                TaskDefinition(task_id="B", name="Task B", dependencies=[], handler="handler"),
                TaskDefinition(task_id="C", name="Task C", dependencies=[], handler="handler"),
                TaskDefinition(task_id="D", name="Task D", dependencies=["A", "B", "C"], handler="handler"),
            ],
        )

        result = await engine.run_workflow_definition(workflow)

        assert result.status == ExecutionStatus.SUCCESS
        assert "D" in results
        d_index = results.index("D")
        assert "A" in results[:d_index]
        assert "B" in results[:d_index]
        assert "C" in results[:d_index]

    @pytest.mark.asyncio
    async def test_failed_workflow_stops_remaining(self):
        engine = WorkflowEngine()

        async def fail_handler(task, context):
            raise RuntimeError("Task failed")

        async def never_handler(task, context):
            return {"result": "should not happen"}

        engine.register_task_handler("fail", fail_handler)
        engine.register_task_handler("never", never_handler)

        workflow = WorkflowDefinition(
            workflow_id="wf_fail",
            name="Failing Workflow",
            tasks=[
                TaskDefinition(task_id="A", name="Task A", dependencies=[], handler="fail"),
                TaskDefinition(task_id="B", name="Task B", dependencies=["A"], handler="never"),
            ],
        )

        result = await engine.run_workflow_definition(workflow)

        assert result.status == ExecutionStatus.FAILED

        task_a_result = [r for r in result.task_results if r.task_id == "A"][0]
        task_b_result = [r for r in result.task_results if r.task_id == "B"][0]

        assert task_a_result.status == ExecutionStatus.FAILED
        assert task_b_result.status == ExecutionStatus.SKIPPED

    @pytest.mark.asyncio
    async def test_circular_dependency_detected(self):
        engine = WorkflowEngine()

        async def handler(task, context):
            return {"ok": True}

        engine.register_task_handler("handler", handler)

        workflow = WorkflowDefinition(
            workflow_id="wf_cycle",
            name="Cycle Workflow",
            tasks=[
                TaskDefinition(task_id="A", name="Task A", dependencies=["B"], handler="handler"),
                TaskDefinition(task_id="B", name="Task B", dependencies=["A"], handler="handler"),
            ],
        )

        result = await engine.run_workflow_definition(workflow)

        assert result.status == ExecutionStatus.FAILED
        assert "Circular dependency" in result.error

    @pytest.mark.asyncio
    async def test_workflow_history(self):
        engine = WorkflowEngine()

        async def handler(task, context):
            return {"ok": True}

        engine.register_task_handler("handler", handler)

        workflow1 = WorkflowDefinition(
            workflow_id="wf_1",
            name="Workflow 1",
            tasks=[
                TaskDefinition(task_id="A", name="Task A", dependencies=[], handler="handler"),
            ],
        )

        workflow2 = WorkflowDefinition(
            workflow_id="wf_2",
            name="Workflow 2",
            tasks=[
                TaskDefinition(task_id="B", name="Task B", dependencies=[], handler="handler"),
            ],
        )

        await engine.run_workflow_definition(workflow1)
        await engine.run_workflow_definition(workflow2)
        await engine.run_workflow_definition(workflow1)

        all_history = engine.get_execution_history()
        assert len(all_history) == 3

        wf1_history = engine.get_execution_history(workflow_id="wf_1")
        assert len(wf1_history) == 2
