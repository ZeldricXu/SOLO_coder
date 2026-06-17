from __future__ import annotations

import logging
from datetime import datetime

import pandas as pd

logger = logging.getLogger(__name__)

_TYPE_MAP: dict[str, str] = {
    "int64": "int",
    "Int64": "int",
    "float64": "float",
    "Float64": "float",
    "bool": "bool",
    "boolean": "bool",
    "datetime64[ns]": "datetime",
    "object": "string",
    "string": "string",
    "category": "string",
    "timedelta64[ns]": "string",
}


def _map_dtype(dtype: pd.api.types.pandas_dtype) -> str:
    dtype_str = str(dtype)
    if "datetime" in dtype_str or "date" in dtype_str:
        return "datetime"
    if "int" in dtype_str:
        return "int"
    if "float" in dtype_str:
        return "float"
    if "bool" in dtype_str:
        return "bool"
    if "timedelta" in dtype_str:
        return "string"
    if "category" in dtype_str:
        return "string"
    if "object" in dtype_str:
        return "string"
    if "string" in dtype_str:
        return "string"
    return _TYPE_MAP.get(dtype_str, "string")


def infer_schema(df: pd.DataFrame) -> dict:
    columns: list[dict] = []
    sample_size = min(10, len(df))
    sample_df = df.head(sample_size)

    for col_name in df.columns:
        series = df[col_name]
        sample_values = sample_df[col_name].dropna().tolist()
        for i, v in enumerate(sample_values):
            if isinstance(v, (pd.Timestamp, datetime)):
                sample_values[i] = v.isoformat()
        mapped_type = _map_dtype(series.dtype)
        nullable = bool(series.isnull().any())
        columns.append({
            "name": col_name,
            "dtype": mapped_type,
            "nullable": nullable,
            "sample_values": sample_values,
        })

    return {"columns": columns}


def compare_schemas(expected: dict, actual: dict) -> dict:
    expected_cols = {c["name"]: c for c in expected.get("columns", [])}
    actual_cols = {c["name"]: c for c in actual.get("columns", [])}

    missing_columns = [name for name in expected_cols if name not in actual_cols]
    extra_columns = [name for name in actual_cols if name not in expected_cols]

    type_changes: list[dict] = []
    for name in expected_cols:
        if name in actual_cols:
            if expected_cols[name]["dtype"] != actual_cols[name]["dtype"]:
                type_changes.append({
                    "name": name,
                    "expected_dtype": expected_cols[name]["dtype"],
                    "actual_dtype": actual_cols[name]["dtype"],
                })

    return {
        "missing_columns": missing_columns,
        "extra_columns": extra_columns,
        "type_changes": type_changes,
    }


def schema_from_great_expectations(ge_result: dict) -> dict:
    columns: list[dict] = []
    expectations = ge_result.get("expectations", ge_result.get("results", []))

    for exp in expectations:
        kwargs = exp.get("kwargs", {})
        col_name = kwargs.get("column")
        if col_name is None:
            continue

        existing = {c["name"] for c in columns}
        if col_name in existing:
            continue

        expectation_type = exp.get("expectation_type", "")
        if "int" in expectation_type.lower():
            dtype = "int"
        elif "float" in expectation_type.lower():
            dtype = "float"
        elif "datetime" in expectation_type.lower() or "date" in expectation_type.lower():
            dtype = "datetime"
        elif "bool" in expectation_type.lower():
            dtype = "bool"
        else:
            dtype = "string"

        nullable = True
        if "not_null" in expectation_type or "notnull" in expectation_type:
            nullable = False

        columns.append({
            "name": col_name,
            "dtype": dtype,
            "nullable": nullable,
            "sample_values": [],
        })

    return {"columns": columns}
