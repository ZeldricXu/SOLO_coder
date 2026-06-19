import pytest
import yaml

from etl_engine.orchestrator.dag import DAG, DAGDefinition, DAGEdge, DAGNode


def _build_dag_from_yaml(yaml_str: str) -> DAG:
    parsed = yaml.safe_load(yaml_str)
    tasks = parsed["workflow"]["tasks"]

    nodes = []
    edges = []
    for task in tasks:
        nodes.append(DAGNode(
            id=task["id"],
            type=task["type"],
            config=task.get("config", {}),
            dependencies=task.get("dependencies", []),
        ))
        for dep in task.get("dependencies", []):
            edges.append(DAGEdge(source=dep, target=task["id"]))

    dag_def = DAGDefinition(nodes=nodes, edges=edges)
    return DAG(dag_def)


@pytest.mark.unit
@pytest.mark.exception
class TestCyclicYAMLRejected:
    def test_cycle_dag_validate_returns_false(self, sample_yaml_with_cycle):
        dag = _build_dag_from_yaml(sample_yaml_with_cycle)

        result = dag.validate()

        assert result is False

    def test_cycle_validation_includes_task_names(self, sample_yaml_with_cycle):
        dag = _build_dag_from_yaml(sample_yaml_with_cycle)

        errors = dag.validate_with_details()

        assert len(errors) > 0
        cycle_errors = [e for e in errors if e["type"] == "cyclic_dependency"]
        assert len(cycle_errors) > 0

        cycle_error = cycle_errors[0]
        cycle_nodes = cycle_error.get("nodes", [])
        cycle_message = cycle_error.get("message", "")

        expected_tasks = {"task_a", "task_b", "task_c"}
        found_tasks = set(cycle_nodes)
        tasks_in_msg = any(t in cycle_message for t in expected_tasks)

        assert expected_tasks.issubset(found_tasks) or tasks_in_msg, (
            f"Cycle detection should mention task_a, task_b, task_c. "
            f"Got nodes={cycle_nodes}, message={cycle_message}"
        )

    def test_detected_cycle_contains_at_least_two_nodes(self, sample_yaml_with_cycle):
        dag = _build_dag_from_yaml(sample_yaml_with_cycle)

        errors = dag.validate_with_details()
        cycle_errors = [e for e in errors if e["type"] == "cyclic_dependency"]
        cycle_nodes = cycle_errors[0].get("nodes", [])

        assert len(cycle_nodes) >= 2


@pytest.mark.unit
@pytest.mark.exception
class TestSelfLoopRejected:
    @pytest.fixture
    def self_loop_yaml(self) -> str:
        return """
workflow:
  name: self_loop_workflow
  tasks:
    - id: task_a
      type: extract
      config: {}
      dependencies:
        - task_a
"""

    def test_self_loop_validation_fails(self, self_loop_yaml):
        dag = _build_dag_from_yaml(self_loop_yaml)

        result = dag.validate()

        assert result is False

    def test_self_loop_mentions_self_referencing_task(self, self_loop_yaml):
        dag = _build_dag_from_yaml(self_loop_yaml)

        errors = dag.validate_with_details()

        messages_joined = " ".join(e.get("message", "") for e in errors)
        has_task_a = "task_a" in messages_joined

        cycle_errors = [e for e in errors if e["type"] == "cyclic_dependency"]
        nodes_in_cycle = set()
        for ce in cycle_errors:
            nodes_in_cycle.update(ce.get("nodes", []))
        cycle_contains_task_a = "task_a" in nodes_in_cycle

        assert has_task_a or cycle_contains_task_a, (
            f"Self-loop detection should mention 'task_a'. "
            f"Got errors: {errors}"
        )


@pytest.mark.unit
@pytest.mark.exception
class TestCycleDetectionErrorMessage:
    @pytest.fixture
    def two_node_cycle_yaml(self) -> str:
        return """
workflow:
  name: two_node_cycle
  tasks:
    - id: alpha
      type: extract
      config: {}
      dependencies:
        - beta
    - id: beta
      type: transform
      config: {}
      dependencies:
        - alpha
"""

    def test_error_points_to_specific_tasks_in_cycle(self, two_node_cycle_yaml):
        dag = _build_dag_from_yaml(two_node_cycle_yaml)

        errors = dag.validate_with_details()
        cycle_errors = [e for e in errors if e["type"] == "cyclic_dependency"]

        assert len(cycle_errors) > 0
        cycle_info = cycle_errors[0]
        cycle_nodes = cycle_info.get("nodes", [])
        message = cycle_info.get("message", "")

        alpha_present = "alpha" in cycle_nodes or "alpha" in message.lower()
        beta_present = "beta" in cycle_nodes or "beta" in message.lower()

        assert alpha_present and beta_present, (
            f"Cycle error should identify both 'alpha' and 'beta'. "
            f"Got nodes={cycle_nodes}, msg={message}"
        )

    def test_cycle_pair_identifies_creating_edge(self, two_node_cycle_yaml):
        dag = _build_dag_from_yaml(two_node_cycle_yaml)

        errors = dag.validate_with_details()
        cycle_errors = [e for e in errors if e["type"] == "cyclic_dependency"]

        cycle_info = cycle_errors[0]
        cycle_pair = cycle_info.get("cycle_pair")
        cycle_nodes = cycle_info.get("nodes", [])

        if cycle_pair is not None:
            pair_set = set(cycle_pair)
            assert pair_set == {"alpha", "beta"} or pair_set.issubset({"alpha", "beta"})
        else:
            assert "alpha" in cycle_nodes and "beta" in cycle_nodes, (
                f"Expected alpha and beta in cycle nodes, got {cycle_nodes}"
            )


@pytest.mark.unit
@pytest.mark.exception
class TestOrphanNodeWarning:
    @pytest.fixture
    def orphan_node_yaml(self) -> str:
        return """
workflow:
  name: workflow_with_orphan
  tasks:
    - id: extract_a
      type: extract
      config: {}
      dependencies: []
    - id: transform_a
      type: transform
      config: {}
      dependencies:
        - extract_a
    - id: lonely_task
      type: quality_check
      config: {}
      dependencies: []
"""

    def test_orphan_node_does_not_crash_validation(self, orphan_node_yaml):
        dag = _build_dag_from_yaml(orphan_node_yaml)

        try:
            result = dag.validate()
            details = dag.validate_with_details()
        except Exception as e:
            pytest.fail(f"DAG validation with orphan node crashed: {e}")

        assert isinstance(result, bool)
        assert isinstance(details, list)

    def test_orphan_detected_or_validation_handles_it(self, orphan_node_yaml):
        dag = _build_dag_from_yaml(orphan_node_yaml)
        errors = dag.validate_with_details()

        if len(errors) > 0:
            orphan_errors = [e for e in errors if e.get("type") == "orphan_node"]
            if orphan_errors:
                orphan_error = orphan_errors[0]
                assert "lonely_task" in orphan_error.get("message", "")
                assert orphan_error.get("node") == "lonely_task"


@pytest.mark.unit
@pytest.mark.exception
class TestMissingDependency:
    @pytest.fixture
    def missing_dep_yaml(self) -> str:
        return """
workflow:
  name: missing_dep_workflow
  tasks:
    - id: real_task
      type: extract
      config: {}
      dependencies: []
    - id: downstream_task
      type: transform
      config: {}
      dependencies:
        - real_task
        - nonexistent_task_xyz
"""

    def test_missing_dependency_caught_by_validation(self, missing_dep_yaml):
        dag = _build_dag_from_yaml(missing_dep_yaml)

        result = dag.validate()

        assert result is False

    def test_missing_dependency_identifies_bad_task_name(self, missing_dep_yaml):
        dag = _build_dag_from_yaml(missing_dep_yaml)

        errors = dag.validate_with_details()

        missing_dep_errors = [e for e in errors if e.get("type") == "missing_dependency"]
        assert len(missing_dep_errors) > 0

        md_error = missing_dep_errors[0]
        assert md_error.get("missing_dependency") == "nonexistent_task_xyz"
        assert md_error.get("node") == "downstream_task"
        assert "nonexistent_task_xyz" in md_error.get("message", "")
