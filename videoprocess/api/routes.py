from typing import Optional, List, Dict, Any
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, UploadFile, File, Form
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session

from videoprocess.config import settings
from videoprocess.models import SessionLocal, init_database
from videoprocess.schemas import (
    VideoUploadResponse,
    TranscodeRequest,
    TranscodeResponse,
    EditRequest,
    EditResponse,
    ApiResponse,
)
from videoprocess.modules.upload import UploadModule
from videoprocess.modules.transcode import TranscodeModule
from videoprocess.modules.edit import EditModule
from videoprocess.modules.storage import StorageModule
from videoprocess.modules.analytics import AnalyticsModule
from videoprocess.modules.watermark import WatermarkModule
from videoprocess.modules.quality import QualityModule
from videoprocess.modules.thumbnail import ThumbnailModule
from videoprocess.modules.history import HistoryModule
from videoprocess.codec_config import codec_config_manager


router = APIRouter()


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


@router.on_event("startup")
async def startup_event():
    init_database()


@router.post("/api/v1/videos/upload", response_model=ApiResponse)
async def upload_video(
    file: UploadFile = File(...),
    upload_user: str = Form(default="anonymous"),
    db: Session = Depends(get_db),
):
    try:
        content = await file.read()

        upload_module = UploadModule(db)
        video_data = upload_module.save_video(
            file_content=content,
            original_filename=file.filename or "video.mp4",
            upload_user=upload_user,
        )

        analytics = AnalyticsModule(db)
        analytics.increment_upload_count(video_data["video_size"], video_data["video_duration"])

        history = HistoryModule(db)
        history.record_action(
            video_id=video_data["video_id"],
            action_type="upload",
            action_details={"original_name": file.filename, "size": video_data["video_size"]},
            status="completed",
        )

        return ApiResponse(
            code=200,
            message="success",
            data={
                "video_id": video_data["video_id"],
                "status": "uploaded",
            },
        )

    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/v1/videos/transcode", response_model=ApiResponse)
async def transcode_video(
    request: TranscodeRequest,
    db: Session = Depends(get_db),
):
    try:
        upload_module = UploadModule(db)
        video = upload_module.get_video(request.video_id)
        if not video:
            raise HTTPException(status_code=404, detail=f"Video not found: {request.video_id}")

        transcode_module = TranscodeModule(db)
        record = transcode_module.create_transcode_record(
            video=video,
            target_format=request.target_format,
            target_codec=request.target_codec,
            profile=request.profile,
        )

        if transcode_module.is_queue_enabled():
            queue_result = transcode_module.submit_to_queue(video, record)
            return ApiResponse(
                code=200,
                message="queued",
                data={
                    "transcode_id": record.transcode_id,
                    "task_id": queue_result.get("task_id"),
                    "status": "queued",
                    "queue_enabled": True,
                },
            )

        result = transcode_module.execute_transcode(video, record)

        if not result["success"]:
            history = HistoryModule(db)
            history.record_action(
                video_id=video.video_id,
                action_type="transcode",
                action_details={"target_format": request.target_format, "target_codec": request.target_codec},
                status="failed",
                result_path=record.output_path,
            )
            return ApiResponse(
                code=500,
                message="transcode_failed",
                data={"error": result.get("error")},
            )

        thumbnail_module = ThumbnailModule(db)
        thumbnail_module.generate_thumbnail(video)

        quality_module = QualityModule(db)
        quality_report = quality_module.analyze_video(video)

        analytics = AnalyticsModule(db)
        analytics.increment_transcode_count()

        history = HistoryModule(db)
        history.record_action(
            video_id=video.video_id,
            action_type="transcode",
            action_details={
                "target_format": request.target_format,
                "target_codec": request.target_codec,
                "transcode_id": record.transcode_id,
            },
            status="completed",
            duration=result["transcode_time"],
            result_path=record.output_path,
        )

        return ApiResponse(
            code=200,
            message="success",
            data={
                "transcode_id": record.transcode_id,
                "status": "completed",
                "codec_used": result.get("codec_used"),
            },
        )

    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/v1/transcodes/{transcode_id}/retry", response_model=ApiResponse)
async def retry_transcode(
    transcode_id: str,
    db: Session = Depends(get_db),
):
    transcode_module = TranscodeModule(db)
    result = transcode_module.retry_failed_transcode(transcode_id)

    if result is None:
        raise HTTPException(status_code=404, detail=f"Transcode not found: {transcode_id}")

    return ApiResponse(
        code=200,
        message="success" if result.get("success") else "failed",
        data=result,
    )


@router.get("/api/v1/transcodes/queue/stats", response_model=ApiResponse)
async def get_transcode_queue_stats(db: Session = Depends(get_db)):
    transcode_module = TranscodeModule(db)
    stats = transcode_module.get_queue_stats()
    return ApiResponse(code=200, message="success", data=stats)


@router.post("/api/v1/videos/edit", response_model=ApiResponse)
async def edit_video(
    request: EditRequest,
    db: Session = Depends(get_db),
):
    try:
        upload_module = UploadModule(db)
        video = upload_module.get_video(request.video_id)
        if not video:
            raise HTTPException(status_code=404, detail=f"Video not found: {request.video_id}")

        edit_module = EditModule(db)
        record = edit_module.create_edit_record(
            video=video,
            edit_type=request.edit_type,
            edit_params=request.edit_params,
        )

        if edit_module.is_queue_enabled():
            queue_result = edit_module.submit_to_queue(video, record)
            return ApiResponse(
                code=200,
                message="queued",
                data={
                    "edit_id": record.edit_id,
                    "task_id": queue_result.get("task_id"),
                    "status": "queued",
                    "queue_enabled": True,
                },
            )

        if request.edit_type == "cut":
            result = edit_module.execute_cut(video, record)
        elif request.edit_type == "merge":
            video_ids = request.edit_params.get("video_ids", [])
            if len(video_ids) < 2:
                raise HTTPException(status_code=400, detail="Merge requires at least 2 video IDs")

            merge_videos = []
            for vid in video_ids[1:]:
                v = upload_module.get_video(vid)
                if not v:
                    raise HTTPException(status_code=404, detail=f"Video not found: {vid}")
                merge_videos.append(v)

            result = edit_module.execute_merge(video, record, merge_videos)
        else:
            raise HTTPException(status_code=400, detail=f"Unsupported edit type: {request.edit_type}")

        if not result["success"]:
            history = HistoryModule(db)
            history.record_action(
                video_id=video.video_id,
                action_type="edit",
                action_details={"edit_type": request.edit_type, "params": request.edit_params},
                status="failed",
                result_path=record.output_path,
            )
            return ApiResponse(
                code=500,
                message="edit_failed",
                data={"error": result.get("error")},
            )

        output_path = result["output_path"]

        if request.add_watermark and request.watermark_text:
            watermark_module = WatermarkModule(db)
            wm_result = watermark_module.add_text_watermark(
                video=video,
                text=request.watermark_text,
                output_path=output_path,
            )
            if wm_result.get("success"):
                output_path = wm_result["output_path"]

        thumbnail_module = ThumbnailModule(db)
        thumbnail_module.generate_thumbnail(video)

        quality_module = QualityModule(db)
        quality_report = quality_module.analyze_video(video)

        analytics = AnalyticsModule(db)
        analytics.increment_edit_count()

        history = HistoryModule(db)
        history.record_action(
            video_id=video.video_id,
            action_type="edit",
            action_details={
                "edit_type": request.edit_type,
                "params": request.edit_params,
                "edit_id": record.edit_id,
            },
            status="completed",
            duration=result["duration"],
            result_path=output_path,
        )

        return ApiResponse(
            code=200,
            message="success",
            data={
                "edit_id": record.edit_id,
                "status": "completed",
            },
        )

    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/v1/edits/{edit_id}/retry", response_model=ApiResponse)
async def retry_edit(
    edit_id: str,
    db: Session = Depends(get_db),
):
    edit_module = EditModule(db)
    result = edit_module.retry_failed_edit(edit_id)

    if result is None:
        raise HTTPException(status_code=404, detail=f"Edit not found: {edit_id}")

    return ApiResponse(
        code=200,
        message="success" if result.get("success") else "failed",
        data=result,
    )


@router.get("/api/v1/edits/queue/stats", response_model=ApiResponse)
async def get_edit_queue_stats(db: Session = Depends(get_db)):
    edit_module = EditModule(db)
    stats = edit_module.get_queue_stats()
    return ApiResponse(code=200, message="success", data=stats)


@router.get("/api/v1/codecs", response_model=ApiResponse)
async def list_codecs(
    format: Optional[str] = None,
):
    if format:
        codecs = codec_config_manager.get_supported_codecs_for_format(format)
        codec_details = []
        for c in codecs:
            cfg = codec_config_manager.get_codec_config(c)
            if cfg:
                codec_details.append({
                    "codec_name": c,
                    "display_name": cfg.get("name", c),
                    "description": cfg.get("description", ""),
                    "presets": list(cfg.get("presets", {}).keys()),
                })
        return ApiResponse(
            code=200,
            message="success",
            data={
                "format": format,
                "codecs": codec_details,
            },
        )

    all_codecs = codec_config_manager.get_all_codecs()
    result = []
    for key, cfg in all_codecs.items():
        result.append({
            "codec_name": key,
            "display_name": cfg.get("name", key),
            "description": cfg.get("description", ""),
            "supported_formats": cfg.get("supported_formats", []),
            "presets": list(cfg.get("presets", {}).keys()),
        })

    return ApiResponse(
        code=200,
        message="success",
        data={
            "codecs": result,
            "default_config": codec_config_manager.get_default_config(),
        },
    )


@router.get("/api/v1/codecs/{codec_name}", response_model=ApiResponse)
async def get_codec_config(codec_name: str):
    config = codec_config_manager.get_codec_config(codec_name)
    if not config:
        raise HTTPException(status_code=404, detail=f"Codec not found: {codec_name}")

    return ApiResponse(
        code=200,
        message="success",
        data={
            "codec_name": codec_name,
            "config": config,
        },
    )


@router.get("/api/v1/videos/{video_id}", response_model=ApiResponse)
async def get_video_info(
    video_id: str,
    db: Session = Depends(get_db),
):
    upload_module = UploadModule(db)
    video = upload_module.get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail=f"Video not found: {video_id}")

    storage_module = StorageModule(db)
    references = storage_module.check_video_references(video_id)

    return ApiResponse(
        code=200,
        message="success",
        data={
            **video.to_dict(),
            "references": references,
        },
    )


@router.get("/api/v1/videos", response_model=ApiResponse)
async def list_videos(
    upload_user: Optional[str] = None,
    limit: int = 100,
    offset: int = 0,
    db: Session = Depends(get_db),
):
    upload_module = UploadModule(db)
    videos = upload_module.list_videos(upload_user=upload_user, limit=limit, offset=offset)
    return ApiResponse(
        code=200,
        message="success",
        data={
            "videos": [v.to_dict() for v in videos],
            "total": len(videos),
            "limit": limit,
            "offset": offset,
        },
    )


@router.get("/api/v1/transcodes/{transcode_id}", response_model=ApiResponse)
async def get_transcode_status(
    transcode_id: str,
    db: Session = Depends(get_db),
):
    transcode_module = TranscodeModule(db)
    record = transcode_module.get_transcode_record(transcode_id)
    if not record:
        raise HTTPException(status_code=404, detail=f"Transcode not found: {transcode_id}")

    return ApiResponse(code=200, message="success", data=record.to_dict())


@router.get("/api/v1/edits/{edit_id}", response_model=ApiResponse)
async def get_edit_status(
    edit_id: str,
    db: Session = Depends(get_db),
):
    edit_module = EditModule(db)
    record = edit_module.get_edit_record(edit_id)
    if not record:
        raise HTTPException(status_code=404, detail=f"Edit not found: {edit_id}")

    return ApiResponse(code=200, message="success", data=record.to_dict())


@router.get("/api/v1/quality/{video_id}", response_model=ApiResponse)
async def get_quality_report(
    video_id: str,
    db: Session = Depends(get_db),
):
    quality_module = QualityModule(db)
    upload_module = UploadModule(db)

    video = upload_module.get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail=f"Video not found: {video_id}")

    reports = quality_module.get_video_quality_reports(video_id)
    if not reports:
        report = quality_module.analyze_video(video)
        reports = [report]

    return ApiResponse(
        code=200,
        message="success",
        data={
            "video_id": video_id,
            "reports": [r.to_dict() for r in reports],
            "latest": reports[0].to_dict() if reports else None,
        },
    )


@router.get("/api/v1/thumbnails/{video_id}", response_model=ApiResponse)
async def get_thumbnails(
    video_id: str,
    db: Session = Depends(get_db),
):
    thumbnail_module = ThumbnailModule(db)
    upload_module = UploadModule(db)

    video = upload_module.get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail=f"Video not found: {video_id}")

    thumbnails = thumbnail_module.get_video_thumbnails(video_id)
    if not thumbnails:
        thumbnails = thumbnail_module.generate_thumbnails_batch(video)

    return ApiResponse(
        code=200,
        message="success",
        data={
            "video_id": video_id,
            "thumbnails": [t.to_dict() for t in thumbnails],
        },
    )


@router.get("/api/v1/history/{video_id}", response_model=ApiResponse)
async def get_video_history(
    video_id: str,
    action_type: Optional[str] = None,
    limit: int = 100,
    db: Session = Depends(get_db),
):
    history_module = HistoryModule(db)
    upload_module = UploadModule(db)

    video = upload_module.get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail=f"Video not found: {video_id}")

    records = history_module.get_video_history(
        video_id=video_id,
        action_type=action_type,
        limit=limit,
    )

    return ApiResponse(
        code=200,
        message="success",
        data={
            "video_id": video_id,
            "history": [r.to_dict() for r in records],
        },
    )


@router.get("/api/v1/analytics/stats", response_model=ApiResponse)
async def get_statistics(
    days: int = 30,
    db: Session = Depends(get_db),
):
    analytics_module = AnalyticsModule(db)
    report = analytics_module.get_comprehensive_report(days=days)

    return ApiResponse(code=200, message="success", data=report)


@router.get("/api/v1/storage", response_model=ApiResponse)
async def get_storage_info(db: Session = Depends(get_db)):
    storage_module = StorageModule(db)
    summary = storage_module.get_cleanup_summary()

    return ApiResponse(code=200, message="success", data=summary)


@router.post("/api/v1/storage/cleanup", response_model=ApiResponse)
async def run_cleanup(
    cleanup_type: str = "expired",
    db: Session = Depends(get_db),
):
    storage_module = StorageModule(db)

    if cleanup_type == "expired":
        result = storage_module.cleanup_expired_files()
    elif cleanup_type == "capacity":
        result = storage_module.cleanup_by_capacity()
    else:
        raise HTTPException(status_code=400, detail=f"Invalid cleanup type: {cleanup_type}")

    return ApiResponse(code=200, message="success", data=result)


@router.get("/api/v1/storage/references", response_model=ApiResponse)
async def get_referenced_videos(db: Session = Depends(get_db)):
    storage_module = StorageModule(db)
    referenced = storage_module.get_referenced_videos()

    return ApiResponse(
        code=200,
        message="success",
        data={
            "count": len(referenced),
            "videos": referenced,
        },
    )


@router.get("/api/v1/health")
async def health_check():
    return ApiResponse(code=200, message="healthy", data={"status": "ok"})
