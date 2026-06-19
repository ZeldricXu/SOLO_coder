from unittest.mock import MagicMock, patch

import pandas as pd
import pytest
import yaml

from etl_engine.connectors.base import BaseSource
from etl_engine.connectors.document_source import DocumentSource
from etl_engine.orchestrator.dag import DAGDefinition
from etl_engine.quality.rules import QualityRule
from etl_engine.quality.validator import QualityValidator
from etl_engine.transform.engine import TransformEngine


@pytest.mark.unit
@pytest.mark.backward_compat
class TestYAMLWithoutModeDefaultsToBatch:
    def test_yaml_without_mode_defaults_to_batch(self):
        yaml_str = """
nodes:
  - id: extract_users
    type: extract
    config:
      source_type: mysql
      query: "SELECT * FROM users"
    dependencies: []
  - id: load_users
    type: load
    config:
      target_type: postgresql
      table: users
    dependencies:
      - extract_users
edges:
  - source: extract_users
    target: load_users
schedule: "0 * * * *"
sla_seconds: 3600
"""
        parsed = yaml.safe_load(yaml_str)
        dag_def = DAGDefinition(**parsed)

        assert dag_def.mode == "batch"
        assert dag_def.streaming_config is None
        assert len(dag_def.nodes) == 2
        assert dag_def.nodes[0].id == "extract_users"
        assert dag_def.schedule == "0 * * * *"
        assert dag_def.sla_seconds == 3600

    def test_old_style_workflow_yaml_parses_to_batch(self):
        yaml_str = """
workflow:
  name: old_pipeline
  tasks:
    - id: task1
      type: extract
      config: {}
      dependencies: []
    - id: task2
      type: load
      config: {}
      dependencies: [task1]
"""
        parsed = yaml.safe_load(yaml_str)
        workflow = parsed["workflow"]

        dag_dict = {
            "nodes": workflow["tasks"],
            "edges": [
                {"source": "task1", "target": "task2"},
            ],
        }
        dag_def = DAGDefinition(**dag_dict)

        assert dag_def.mode == "batch"
        assert len(dag_def.nodes) == 2

    def test_minimal_yaml_without_mode(self):
        yaml_str = """
nodes:
  - id: node1
    type: extract
    config: {}
    dependencies: []
edges: []
"""
        parsed = yaml.safe_load(yaml_str)
        dag_def = DAGDefinition(**parsed)
        assert dag_def.mode == "batch"
        assert dag_def.streaming_config is None


@pytest.mark.unit
@pytest.mark.backward_compat
class TestOldPipelineYAMLStillWorks:
    def test_sample_yaml_workflow_parses_correctly(self, sample_yaml_workflow):
        parsed = yaml.safe_load(sample_yaml_workflow)
        assert "workflow" in parsed
        workflow = parsed["workflow"]

        assert workflow["name"] == "user_pipeline"
        assert len(workflow["tasks"]) == 3

        tasks = workflow["tasks"]
        assert tasks[0]["id"] == "extract_users"
        assert tasks[0]["type"] == "extract"
        assert tasks[0]["config"]["source_type"] == "mysql"
        assert tasks[0]["dependencies"] == []

        assert tasks[1]["id"] == "clean_users"
        assert tasks[1]["type"] == "transform"
        assert "email IS NOT NULL" in tasks[1]["config"]["sql"]
        assert tasks[1]["dependencies"] == ["extract_users"]

        assert tasks[2]["id"] == "load_users"
        assert tasks[2]["type"] == "load"
        assert tasks[2]["config"]["target_type"] == "postgresql"
        assert tasks[2]["dependencies"] == ["clean_users"]

    def test_sample_yaml_converts_to_dag_definition(self, sample_yaml_workflow):
        parsed = yaml.safe_load(sample_yaml_workflow)
        workflow = parsed["workflow"]
        tasks = workflow["tasks"]

        edges = []
        for task in tasks:
            for dep in task["dependencies"]:
                edges.append({"source": dep, "target": task["id"]})

        dag_dict = {
            "nodes": tasks,
            "edges": edges,
        }
        dag_def = DAGDefinition(**dag_dict)

        assert dag_def.mode == "batch"
        assert len(dag_def.nodes) == 3
        assert len(dag_def.edges) == 2
        assert dag_def.streaming_config is None

        for i, node in enumerate(dag_def.nodes):
            assert node.id == tasks[i]["id"]
            assert node.type == tasks[i]["type"]
            assert node.config == tasks[i]["config"]
            assert node.dependencies == tasks[i]["dependencies"]

    def test_yaml_with_cycle_still_detected(self, sample_yaml_with_cycle):
        parsed = yaml.safe_load(sample_yaml_with_cycle)
        workflow = parsed["workflow"]
        tasks = workflow["tasks"]

        edges = []
        for task in tasks:
            for dep in task["dependencies"]:
                edges.append({"source": dep, "target": task["id"]})

        dag_dict = {
            "nodes": tasks,
            "edges": edges,
        }
        dag_def = DAGDefinition(**dag_dict)
        assert dag_def.mode == "batch"

        from etl_engine.orchestrator.dag import DAG
        dag = DAG(dag_def)
        errors = dag.validate_with_details()
        assert len(errors) > 0
        assert any(e["type"] == "cyclic_dependency" for e in errors)


@pytest.mark.unit
@pytest.mark.backward_compat
class TestExistingTransformationsWork:
    def test_transform_engine_apply_still_works(self, sample_df):
        engine = TransformEngine(use_dask=False)

        transformations = [
            {
                "id": "step1",
                "type": "sql",
                "expression": "SELECT id, UPPER(name) as name_upper, value, created_at FROM input",
            },
            {
                "id": "step2",
                "type": "udf",
                "expression": {
                    "inline_code": "result = df.copy(); result['doubled'] = result['value'] * 2",
                },
            },
            {
                "id": "step3",
                "type": "sql",
                "expression": "SELECT id, name_upper, value, doubled FROM input WHERE value > 20.3",
            },
        ]

        result_df = engine.apply(sample_df, transformations)

        assert result_df.shape[0] == 3
        expected_columns = ["id", "name_upper", "value", "doubled"]
        for col in expected_columns:
            assert col in result_df.columns
        assert (result_df["doubled"] == result_df["value"] * 2).all()

    def test_sql_transform_still_works(self, sample_df):
        engine = TransformEngine()
        result = engine.apply(
            sample_df,
            [{"type": "sql", "expression": "SELECT COUNT(*) as cnt FROM input"}],
        )
        assert result.shape[0] == 1
        assert result.iloc[0]["cnt"] == 5

    def test_udf_transform_still_works(self, sample_df):
        engine = TransformEngine()
        result = engine.apply(
            sample_df,
            [
                {
                    "type": "udf",
                    "expression": {
                        "inline_code": "result = df.copy(); result['value_squared'] = result['value'] ** 2",
                    },
                }
            ],
        )
        assert "value_squared" in result.columns
        expected_squared = sample_df["value"] ** 2
        assert (result["value_squared"] == expected_squared).all()

    def test_empty_transformations_return_original(self, sample_df):
        engine = TransformEngine()
        result = engine.apply(sample_df, [])
        pd.testing.assert_frame_equal(result, sample_df)

    def test_single_transformation_works(self, sample_df):
        engine = TransformEngine()
        result = engine.apply(
            sample_df,
            [{"type": "sql", "expression": "SELECT id, name FROM input WHERE id > 2"}],
        )
        assert result.shape[0] == 3
        assert list(result.columns) == ["id", "name"]


@pytest.mark.unit
@pytest.mark.backward_compat
class TestOldBaseSourceInterface:
    def test_base_source_interface_unchanged(self):
        class MockSource(BaseSource):
            async def connect(self):
                self._connected = True

            async def disconnect(self):
                self._connected = False

            async def read(self, query=None, **kwargs):
                return pd.DataFrame({"id": [1, 2, 3], "value": [10, 20, 30]})

            async def test_connection(self):
                return True

        source = MockSource({"host": "localhost"})
        assert source.config == {"host": "localhost"}
        assert source.is_connected is False

        import asyncio
        asyncio.run(source.connect())
        assert source.is_connected is True

        result = asyncio.run(source.read())
        assert isinstance(result, pd.DataFrame)
        assert len(result) == 3

        assert asyncio.run(source.test_connection()) is True

        asyncio.run(source.disconnect())
        assert source.is_connected is False

    def test_document_source_does_not_break_base_source(self):
        class MockDocumentSource(DocumentSource):
            async def connect(self):
                self._connected = True

            async def disconnect(self):
                self._connected = False

            async def find(self, query):
                return pd.DataFrame([{"id": 1, "name": "test"}])

            async def aggregate(self, pipeline):
                return pd.DataFrame([{"count": 1}])

            async def scan(self, batch_size=1000, **kwargs):
                from etl_engine.connectors.document_source import DocumentScanResult
                return DocumentScanResult(documents=[{"id": 1}], total=1)

        doc_source = MockDocumentSource({"host": "localhost"})
        assert hasattr(doc_source, "connect")
        assert hasattr(doc_source, "disconnect")
        assert hasattr(doc_source, "find")
        assert hasattr(doc_source, "aggregate")
        assert hasattr(doc_source, "scan")
        assert hasattr(doc_source, "find_iter")
        assert doc_source.is_connected is False

        class MockRegularSource(BaseSource):
            async def connect(self):
                self._connected = True

            async def disconnect(self):
                self._connected = False

            async def read(self, query=None, **kwargs):
                return pd.DataFrame()

            async def test_connection(self):
                return True

        regular_source = MockRegularSource({"host": "localhost"})
        assert hasattr(regular_source, "read")
        assert not hasattr(regular_source, "find")
        assert not hasattr(regular_source, "aggregate")
        assert not hasattr(regular_source, "scan")

    def test_base_source_abstract_methods_required(self):
        class IncompleteSource(BaseSource):
            pass

        with pytest.raises(TypeError):
            IncompleteSource({})

    def test_document_source_abstract_methods_required(self):
        class IncompleteDocSource(DocumentSource):
            pass

        with pytest.raises(TypeError):
            IncompleteDocSource({})


@pytest.mark.unit
@pytest.mark.backward_compat
class TestOldQualityValidator:
    def test_quality_validator_null_rate_unchanged(self):
        rules = [
            QualityRule(
                rule_type="null_rate",
                column="name",
                params={"max_null_rate": 0.05},
                threshold=1.0,
                strategy="alert",
            ),
        ]
        validator = QualityValidator(rules)

        df = pd.DataFrame({
            "id": [1, 2, 3, 4, 5],
            "name": ["Alice", "Bob", None, "David", "Eve"],
        })

        result = validator.validate(df)

        assert result.total_rules == 1
        assert result.passed_rules == 0
        assert result.failed_rules == 1
        assert result.passed is False
        assert not result.blocked

        rule_result = result.rule_results[0]
        assert rule_result.rule_type == "null_rate"
        assert rule_result.column == "name"
        assert rule_result.actual_value == 0.2
        assert rule_result.passed is False

    def test_quality_validator_uniqueness_unchanged(self):
        rules = [
            QualityRule(
                rule_type="uniqueness",
                column="id",
                params={"expect_unique": True},
                threshold=1.0,
                strategy="alert",
            ),
        ]
        validator = QualityValidator(rules)

        df = pd.DataFrame({
            "id": [1, 2, 3, 4, 5],
            "name": ["Alice", "Bob", "Charlie", "David", "Eve"],
        })

        result = validator.validate(df)

        assert result.passed is True
        assert result.passed_rules == 1
        assert result.failed_rules == 0

        rule_result = result.rule_results[0]
        assert rule_result.actual_value == 1.0
        assert rule_result.passed is True

    def test_quality_validator_value_range_unchanged(self):
        rules = [
            QualityRule(
                rule_type="value_range",
                column="value",
                params={"min_value": 0, "max_value": 100},
                threshold=1.0,
                strategy="block",
            ),
        ]
        validator = QualityValidator(rules)

        df = pd.DataFrame({
            "id": [1, 2, 3],
            "value": [10, 50, 150],
        })

        result = validator.validate(df)

        assert result.passed is False
        assert result.blocked is True
        assert result.failed_rules == 1

        rule_result = result.rule_results[0]
        assert rule_result.actual_value == 2/3
        assert rule_result.details["actual_min"] == 10.0
        assert rule_result.details["actual_max"] == 150.0

    def test_quality_validator_multiple_rules(self):
        rules = [
            QualityRule(
                rule_type="null_rate",
                column="name",
                params={"max_null_rate": 0.05},
                threshold=1.0,
                strategy="alert",
            ),
            QualityRule(
                rule_type="uniqueness",
                column="id",
                params={"expect_unique": True},
                threshold=1.0,
                strategy="alert",
            ),
            QualityRule(
                rule_type="value_range",
                column="value",
                params={"min_value": 0, "max_value": 100},
                threshold=1.0,
                strategy="block",
            ),
        ]
        validator = QualityValidator(rules)

        df = pd.DataFrame({
            "id": [1, 2, 3, 4, 5],
            "name": ["Alice", "Bob", "Charlie", "David", "Eve"],
            "value": [10.5, 20.3, 30.1, 40.7, 50.9],
        })

        result = validator.validate(df)

        assert result.total_rules == 3
        assert result.passed_rules == 3
        assert result.failed_rules == 0
        assert result.passed is True
        assert result.blocked is False
        assert result.summary["pass_rate"] == 1.0

    def test_quality_validator_summary_unchanged(self, sample_quality_rules, sample_df):
        rules = [QualityRule(**r) for r in sample_quality_rules]
        validator = QualityValidator(rules)

        result = validator.validate(sample_df)

        assert "by_type" in result.summary
        assert "pass_rate" in result.summary
        assert result.summary["pass_rate"] == 1.0

        for rule_type in ["null_rate", "uniqueness", "value_range"]:
            assert rule_type in result.summary["by_type"]
            assert result.summary["by_type"][rule_type]["total"] == 1
            assert result.summary["by_type"][rule_type]["passed"] == 1
            assert result.summary["by_type"][rule_type]["failed"] == 0
