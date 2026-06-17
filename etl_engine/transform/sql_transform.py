from __future__ import annotations

import logging
import re

import duckdb
import pandas as pd

logger = logging.getLogger(__name__)

_FORBIDDEN_PATTERNS = [
    r"\bDROP\b",
    r"\bDELETE\b",
    r"\bTRUNCATE\b",
    r"\bALTER\b",
    r"\bCREATE\b",
    r"\bINSERT\b",
    r"\bUPDATE\b",
    r"\bGRANT\b",
    r"\bREVOKE\b",
]


class SQLTransform:
    def apply_sql(
        self,
        df: pd.DataFrame,
        sql_expression: str,
        params: dict | None = None,
    ) -> pd.DataFrame:
        con = duckdb.connect()
        try:
            con.register("input", df)
            if params:
                result = con.execute(sql_expression, list(params.values())).fetchdf()
            else:
                result = con.execute(sql_expression).fetchdf()
            return result
        except Exception as e:
            logger.error("SQL transform failed: %s | SQL: %s", e, sql_expression)
            raise
        finally:
            con.close()

    def validate_sql(self, sql_expression: str) -> bool:
        if not sql_expression or not sql_expression.strip():
            return False
        upper = sql_expression.upper()
        for pattern in _FORBIDDEN_PATTERNS:
            if re.search(pattern, upper):
                logger.warning("SQL validation failed: forbidden keyword in '%s'", sql_expression)
                return False
        try:
            con = duckdb.connect()
            con.execute("CREATE TABLE input (_dummy INT)")
            con.execute("EXPLAIN " + sql_expression)
            con.close()
            return True
        except Exception:
            return False
