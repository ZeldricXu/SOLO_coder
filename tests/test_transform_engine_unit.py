import pytest
import pandas as pd

from etl_engine.transform import TransformEngine, SQLTransform, UDFTransform, infer_schema


@pytest.mark.unit
class TestThreeStepTransformPipeline:
    def test_serial_three_step_pipeline(self, sample_df, sample_transformations):
        engine = TransformEngine()

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


@pytest.mark.unit
class TestSchemaInference:
    def test_infer_schema_returns_correct_structure(self, sample_df):
        schema = infer_schema(sample_df)

        assert "columns" in schema
        assert isinstance(schema["columns"], list)
        assert len(schema["columns"]) == len(sample_df.columns)

        for column in schema["columns"]:
            assert "name" in column
            assert "dtype" in column
            assert column["name"] in sample_df.columns


@pytest.mark.unit
class TestSQLTransformBasic:
    def test_sql_count_returns_correct_value(self, sample_df):
        sql_transform = SQLTransform()
        result = sql_transform.apply_sql(sample_df, "SELECT COUNT(*) as cnt FROM input")

        assert result.shape[0] == 1
        assert result.iloc[0]["cnt"] == 5


@pytest.mark.unit
class TestUDFTransformInline:
    def test_inline_udf_adds_column(self, sample_df):
        udf_transform = UDFTransform()
        udf_config = {
            "inline_code": "result = df.copy(); result['value_squared'] = result['value'] ** 2",
        }

        result = udf_transform.apply_udf(sample_df, udf_config)

        assert "value_squared" in result.columns
        expected_squared = sample_df["value"] ** 2
        assert (result["value_squared"] == expected_squared).all()
