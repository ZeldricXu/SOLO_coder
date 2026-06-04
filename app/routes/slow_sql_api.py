from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from typing import Optional, List

from app.database import get_db
from app.templates_shared import templates
from app.services import SlowSQLService
from app.schemas import SlowSQLRecord, SQLExplainRequest

router = APIRouter(prefix="/api/slow-sql", tags=["slow-sql"])


@router.get("/list")
async def get_slow_sql_list(
    table_name: Optional[str] = None,
    min_duration: Optional[float] = None,
    sort_by: str = Query("last_seen"),
    limit: int = Query(100),
    db: Session = Depends(get_db),
):
    slow_sql_service = SlowSQLService(db)
    sqls = slow_sql_service.get_slow_sql_list(
        table_name=table_name,
        min_duration=min_duration,
        sort_by=sort_by,
        limit=limit,
    )
    return {
        "success": True,
        "count": len(sqls),
        "sqls": sqls,
    }


@router.get("/{slow_sql_id}")
async def get_slow_sql_detail(
    slow_sql_id: int,
    db: Session = Depends(get_db),
):
    slow_sql_service = SlowSQLService(db)
    sql = slow_sql_service.get_slow_sql_by_id(slow_sql_id)
    if not sql:
        raise HTTPException(status_code=404, detail="Slow SQL not found")
    return {
        "success": True,
        "sql": sql,
    }


@router.post("/record")
async def record_slow_sql(
    data: SlowSQLRecord,
    db: Session = Depends(get_db),
):
    slow_sql_service = SlowSQLService(db)
    sql = slow_sql_service.record_slow_sql(data)
    return {
        "success": True,
        "sql": sql,
    }


@router.post("/batch")
async def batch_record(
    records: List[SlowSQLRecord],
    db: Session = Depends(get_db),
):
    slow_sql_service = SlowSQLService(db)
    results = slow_sql_service.batch_record(records)
    return {
        "success": True,
        "count": len(results),
        "results": results,
    }


@router.get("/explain/{slow_sql_id}")
async def get_explain(
    slow_sql_id: int,
    db: Session = Depends(get_db),
):
    slow_sql_service = SlowSQLService(db)
    explain = slow_sql_service.get_explain(slow_sql_id)
    if not explain:
        explain = slow_sql_service.generate_explain(slow_sql_id)
    return {
        "success": True,
        "explain": explain,
    }


@router.post("/explain")
async def create_explain(
    data: SQLExplainRequest,
    db: Session = Depends(get_db),
):
    slow_sql_service = SlowSQLService(db)
    explain = slow_sql_service.generate_explain(data.slow_sql_id)
    return {
        "success": True,
        "explain": explain,
    }


@router.get("/statistics")
async def get_statistics(
    days: int = Query(7),
    db: Session = Depends(get_db),
):
    slow_sql_service = SlowSQLService(db)
    stats = slow_sql_service.get_statistics(days=days)
    return {
        "success": True,
        "statistics": stats,
    }


@router.get("/tables")
async def get_tables(
    db: Session = Depends(get_db),
):
    slow_sql_service = SlowSQLService(db)
    tables = slow_sql_service.get_tables()
    return {
        "success": True,
        "tables": tables,
    }


@router.delete("/{slow_sql_id}")
async def delete_slow_sql(
    slow_sql_id: int,
    db: Session = Depends(get_db),
):
    slow_sql_service = SlowSQLService(db)
    success = slow_sql_service.delete_slow_sql(slow_sql_id)
    if not success:
        raise HTTPException(status_code=404, detail="Slow SQL not found")
    return {
        "success": True,
        "message": "Slow SQL deleted",
    }


@router.get("/partial/list")
async def get_sql_list_partial(
    table_name: Optional[str] = None,
    sort_by: str = Query("last_seen"),
    limit: int = Query(50),
    db: Session = Depends(get_db),
):
    from starlette.requests import Request as StarletteRequest

    slow_sql_service = SlowSQLService(db)
    sqls = slow_sql_service.get_slow_sql_list(
        table_name=table_name,
        sort_by=sort_by,
        limit=limit,
    )
    stats = slow_sql_service.get_statistics()

    scope = {"type": "http", "method": "GET", "path": "/api/slow-sql/partial/list", "headers": []}
    request = StarletteRequest(scope)

    return templates.TemplateResponse(
        "components/slow_sql_list.html",
        {
            "request": request,
            "sqls": sqls,
            "stats": stats,
        },
    )


@router.get("/partial/explain/{slow_sql_id}")
async def get_explain_partial(
    slow_sql_id: int,
    db: Session = Depends(get_db),
):
    from starlette.requests import Request as StarletteRequest

    slow_sql_service = SlowSQLService(db)
    sql = slow_sql_service.get_slow_sql_by_id(slow_sql_id)
    if not sql:
        raise HTTPException(status_code=404, detail="Slow SQL not found")

    explain = slow_sql_service.get_explain(slow_sql_id)
    if not explain:
        explain = slow_sql_service.generate_explain(slow_sql_id)

    scope = {"type": "http", "method": "GET", "path": "/api/slow-sql/partial/explain", "headers": []}
    request = StarletteRequest(scope)

    return templates.TemplateResponse(
        "components/sql_explain.html",
        {
            "request": request,
            "sql": sql,
            "explain": explain,
        },
    )
