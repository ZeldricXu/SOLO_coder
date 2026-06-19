import pytest
import yaml

from etl_engine.orchestrator.dag import DAG, DAGDefinition, DAGNode, DAGEdge


@pytest.mark.unit
class TestYAMLToDAG:
    def test_yaml_parse_and_dag_validate(self, sample_yaml_workflow):
        parsed = yaml.safe_load(sample_yaml_workflow)
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
        dag = DAG(dag_def)

        assert dag.validate() is True


@pytest.mark.unit
class TestTopologicalOrder:
    def test_execution_order_layers_correct(self, sample_yaml_workflow):
        parsed = yaml.safe_load(sample_yaml_workflow)
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
        dag = DAG(dag_def)
        execution_order = dag.get_execution_order()

        assert len(execution_order) >= 3
        assert "extract_users" in execution_order[0]
        assert "clean_users" in execution_order[1]
        assert "load_users" in execution_order[2]

        task_layer = {}
        for layer_idx, layer in enumerate(execution_order):
            for task_id in layer:
                task_layer[task_id] = layer_idx

        for task in tasks:
            for dep in task.get("dependencies", []):
                assert task_layer[dep] < task_layer[task["id"]]


@pytest.mark.unit
class TestDAGNodeConfigPreserved:
    def test_node_type_and_config_accessible(self, sample_yaml_workflow):
        parsed = yaml.safe_load(sample_yaml_workflow)
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
        dag = DAG(dag_def)

        extract_node = dag.get_node("extract_users")
        assert extract_node.type == "extract"
        assert extract_node.config["source_type"] == "mysql"
        assert extract_node.config["query"] == "SELECT * FROM users"

        clean_node = dag.get_node("clean_users")
        assert clean_node.type == "transform"
        assert "sql" in clean_node.config

        load_node = dag.get_node("load_users")
        assert load_node.type == "load"
        assert load_node.config["target_type"] == "postgresql"
        assert load_node.config["table"] == "cleaned_users"


@pytest.mark.unit
class TestDAGEdges:
    def test_edges_parsed_and_upstream_downstream_correct(self, sample_yaml_workflow):
        parsed = yaml.safe_load(sample_yaml_workflow)
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
        dag = DAG(dag_def)

        assert len(dag.definition.edges) == 2

        assert dag.get_upstream("clean_users") == ["extract_users"]
        assert dag.get_upstream("load_users") == ["clean_users"]
        assert dag.get_upstream("extract_users") == []

        assert dag.get_downstream("extract_users") == ["clean_users"]
        assert dag.get_downstream("clean_users") == ["load_users"]
        assert dag.get_downstream("load_users") == []
