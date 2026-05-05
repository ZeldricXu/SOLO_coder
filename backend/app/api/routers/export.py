from fastapi import APIRouter, HTTPException, Query, BackgroundTasks
from fastapi.responses import FileResponse, StreamingResponse
from typing import List, Optional
from pydantic import BaseModel
from datetime import datetime
import io

from app.modules.exporter import exporter
from app.modules.result_storage import result_storage

router = APIRouter()


class ExportRequest(BaseModel):
    result_ids: Optional[List[str]] = None
    format: str = "json"
    include_metadata: bool = True


class ExportResponse(BaseModel):
    success: bool
    message: str
    export_id: str
    file_path: str
    total_count: int


class ExportInfo(BaseModel):
    export_id: str
    format: str
    file_path: str
    file_size: int
    created_at: str


class ExportListResponse(BaseModel):
    exports: List[ExportInfo]
    total_count: int


@router.post("/", response_model=ExportResponse)
async def export_results(request: ExportRequest):
    try:
        if request.result_ids:
            result = exporter.export_by_ids(
                result_ids=request.result_ids,
                format=request.format,
                include_metadata=request.include_metadata
            )
        else:
            result = exporter.export_by_query(
                format=request.format,
                include_metadata=request.include_metadata
            )

        if result["success"]:
            return ExportResponse(
                success=True,
                message=result["message"],
                export_id=result["export_id"],
                file_path=result["file_path"],
                total_count=result["total_count"]
            )
        else:
            raise HTTPException(status_code=400, detail=result["message"])

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"导出失败: {str(e)}")


@router.get("/list", response_model=ExportListResponse)
async def list_exports():
    try:
        exports = exporter.list_exports()
        export_infos = []
        for e in exports:
            export_infos.append(ExportInfo(
                export_id=e["export_id"],
                format=e["format"],
                file_path=e["file_path"],
                file_size=e["file_size"],
                created_at=e["created_at"]
            ))
        return ExportListResponse(
            exports=export_infos,
            total_count=len(export_infos)
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"获取导出列表异常: {str(e)}")


@router.get("/{export_id}", response_class=FileResponse)
async def download_export(export_id: str):
    try:
        export_info = exporter.get_export_file(export_id)
        if not export_info:
            raise HTTPException(status_code=404, detail=f"导出文件不存在: {export_id}")

        file_path = export_info["file_path"]
        file_format = export_info["format"]
        filename = f"{export_id}.{file_format}"

        return FileResponse(
            path=file_path,
            filename=filename,
            media_type="application/json" if file_format == "json" else "text/csv"
        )

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"下载导出文件异常: {str(e)}")


@router.get("/download/json")
async def download_json(
    limit: int = Query(10000, ge=1, le=100000),
    offset: int = Query(0, ge=0),
    model_version: Optional[str] = None
):
    try:
        query_result = result_storage.list_results(
            limit=limit,
            offset=offset,
            model_version=model_version
        )

        results = query_result.get("results", [])
        json_string = exporter.results_to_json_string(results)

        response = StreamingResponse(
            io.StringIO(json_string),
            media_type="application/json"
        )
        response.headers["Content-Disposition"] = f"attachment; filename=export_{datetime.now().strftime('%Y%m%d%H%M%S')}.json"
        return response

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"导出JSON异常: {str(e)}")


@router.get("/download/csv")
async def download_csv(
    limit: int = Query(10000, ge=1, le=100000),
    offset: int = Query(0, ge=0),
    model_version: Optional[str] = None
):
    try:
        query_result = result_storage.list_results(
            limit=limit,
            offset=offset,
            model_version=model_version
        )

        results = query_result.get("results", [])
        csv_string = exporter.results_to_csv_string(results)

        response = StreamingResponse(
            io.StringIO(csv_string),
            media_type="text/csv"
        )
        response.headers["Content-Disposition"] = f"attachment; filename=export_{datetime.now().strftime('%Y%m%d%H%M%S')}.csv"
        return response

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"导出CSV异常: {str(e)}")


@router.delete("/{export_id}")
async def delete_export(export_id: str):
    try:
        result = exporter.delete_export(export_id)
        if result["success"]:
            return {"code": 200, "message": result["message"]}
        else:
            raise HTTPException(status_code=404, detail=result["message"])
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"删除导出文件异常: {str(e)}")
