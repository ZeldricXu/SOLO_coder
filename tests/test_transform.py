import pandas as pd
import pytest

from etl_engine.transform.engine import TransformEngine
from etl_engine.transform.schema_inference import compare_schemas, infer_schema
from etl_engine.transform.sql_transform import SQLTransform
from etl_engine.transform.udf_transform import UDFTransform


def test_sql_transform(sample_df):
    sql = SQLTransform()
    result = sql.apply_sql(sample_df, "SELECT * FROM input WHERE value > 25")
    assert len(result) == 3
    assert all(result["value"] > 25)


def test_udf_inline_transform(sample_df):
    udf = UDFTransform()
    code = "result = df[df['value'] > 30]"
    result = udf.apply_udf(sample_df, {"inline_code": code})
    assert len(result) == 3
    assert all(result["value"] > 30)


def test_schema_inference(sample_df):
    schema = infer_schema(sample_df)
    assert "columns" in schema
    col_names = [c["name"] for c in schema["columns"]]
    assert col_names == ["id", "name", "value", "created_at"]

    col_map = {c["name"]: c for c in schema["columns"]}
    assert col_map["id"]["dtype"] == "int"
    assert col_map["name"]["dtype"] == "string"
    assert col_map["value"]["dtype"] == "float"
    assert col_map["created_at"]["dtype"] == "datetime"
    assert col_map["id"]["nullable"] is False


def test_schema_comparison():
    schema_a = {
        "columns": [
            {"name": "id", "dtype": "int", "nullable": False, "sample_values": []},
            {"name": "name", "dtype": "string", "nullable": False, "sample_values": []},
            {"name": "value", "dtype": "float", "nullable": False, "sample_values": []},
        ]
    }
    schema_b = {
        "columns": [
            {"name": "id", "dtype": "string", "nullable": False, "sample_values": []},
            {"name": "name", "dtype": "string", "nullable": False, "sample_values": []},
            {"name": "extra_col", "dtype": "int", "nullable": True, "sample_values": []},
        ]
    }
    diff = compare_schemas(schema_a, schema_b)
    assert "value" in diff["missing_columns"]
    assert "extra_col" in diff["extra_columns"]
    assert len(diff["type_changes"]) == 1
    assert diff["type_changes"][0]["name"] == "id"
    assert diff["type_changes"][0]["expected_dtype"] == "int"
    assert diff["type_changes"][0]["actual_dtype"] == "string"


def test_transform_engine_apply(sample_df):
    engine = TransformEngine()
    transforms = [
        {"type": "sql", "expression": "SELECT * FROM input WHERE value >= 20"},
        {
            "type": "udf",
            "expression": "result = df.assign(value_doubled=df['value'] * 2)",
        },
    ]
    result = engine.apply(sample_df, transforms)
    assert len(result) == 4
    assert "value_doubled" in result.columns
    assert list(result["value_doubled"]) == pytest.approx([40.6, 60.2, 81.4, 101.8])
