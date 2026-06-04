from datetime import datetime
from typing import Optional, Dict, Any
from pydantic import BaseModel


class SlowSQLRecord(BaseModel):
    fingerprint: str
    sql_text: str
    table_name: Optional[str] = None
    duration_ms: float


class SQLExplainRequest(BaseModel):
    slow_sql_id: int
    plan_json: Optional[Dict[str, Any]] = None
