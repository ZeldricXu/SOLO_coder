import uuid
import datetime
from typing import Optional, Dict, Any, List
from datetime import datetime, timezone
from pydantic import BaseModel, Field
from sqlalchemy import Column, String, Integer, Float, DateTime, Text, JSON, create_engine
from sqlalchemy.orm import sessionmaker, declarative_base

from videoprocess.config import settings


Base = declarative_base()


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:8]}"


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class VideoORM(Base):
    __tablename__ = "videos"

    video_id = Column(String(64), primary_key=True)
    video_name = Column(String(255), nullable=False)
    video_format = Column(String(32), nullable=False)
    video_duration = Column(Float, default=0.0)
    video_size = Column(Integer, default=0)
    upload_user = Column(String(64), default="anonymous")
    upload_time = Column(DateTime, default=utc_now)
    video_status = Column(String(32), default="uploaded")
    storage_path = Column(String(1024), nullable=False)
    video_metadata = Column(JSON, default=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "video_id": self.video_id,
            "video_name": self.video_name,
            "video_format": self.video_format,
            "video_duration": self.video_duration,
            "video_size": self.video_size,
            "upload_user": self.upload_user,
            "upload_time": self.upload_time.isoformat() if self.upload_time else None,
            "video_status": self.video_status,
            "storage_path": self.storage_path,
            "metadata": self.video_metadata,
        }


class TranscodeRecordORM(Base):
    __tablename__ = "transcode_records"

    transcode_id = Column(String(64), primary_key=True)
    video_id = Column(String(64), nullable=False, index=True)
    source_format = Column(String(32), nullable=False)
    target_format = Column(String(32), nullable=False)
    target_codec = Column(String(32), nullable=True)
    transcode_status = Column(String(32), default="pending")
    transcode_time = Column(Float, default=0.0)
    output_path = Column(String(1024), nullable=True)
    transcoded_at = Column(DateTime, default=utc_now)
    profile = Column(String(64), nullable=True)
    error_message = Column(Text, nullable=True)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "transcode_id": self.transcode_id,
            "video_id": self.video_id,
            "source_format": self.source_format,
            "target_format": self.target_format,
            "target_codec": self.target_codec,
            "transcode_status": self.transcode_status,
            "transcode_time": self.transcode_time,
            "output_path": self.output_path,
            "transcoded_at": self.transcoded_at.isoformat() if self.transcoded_at else None,
            "profile": self.profile,
            "error_message": self.error_message,
        }


class EditRecordORM(Base):
    __tablename__ = "edit_records"

    edit_id = Column(String(64), primary_key=True)
    video_id = Column(String(64), nullable=False, index=True)
    edit_type = Column(String(32), nullable=False)
    edit_params = Column(JSON, default=dict)
    edit_status = Column(String(32), default="pending")
    output_path = Column(String(1024), nullable=True)
    edited_at = Column(DateTime, default=utc_now)
    duration = Column(Float, default=0.0)
    error_message = Column(Text, nullable=True)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "edit_id": self.edit_id,
            "video_id": self.video_id,
            "edit_type": self.edit_type,
            "edit_params": self.edit_params,
            "edit_status": self.edit_status,
            "output_path": self.output_path,
            "edited_at": self.edited_at.isoformat() if self.edited_at else None,
            "duration": self.duration,
            "error_message": self.error_message,
        }


class QualityReportORM(Base):
    __tablename__ = "quality_reports"

    quality_id = Column(String(64), primary_key=True)
    video_id = Column(String(64), nullable=False, index=True)
    resolution = Column(String(32), nullable=True)
    bitrate = Column(Integer, default=0)
    frame_rate = Column(Float, default=0.0)
    quality_score = Column(Integer, default=0)
    quality_issues = Column(JSON, default=list)
    detected_at = Column(DateTime, default=utc_now)
    duration = Column(Float, default=0.0)
    codec = Column(String(64), nullable=True)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "quality_id": self.quality_id,
            "video_id": self.video_id,
            "resolution": self.resolution,
            "bitrate": self.bitrate,
            "frame_rate": self.frame_rate,
            "quality_score": self.quality_score,
            "quality_issues": self.quality_issues,
            "detected_at": self.detected_at.isoformat() if self.detected_at else None,
            "duration": self.duration,
            "codec": self.codec,
        }


class ThumbnailORM(Base):
    __tablename__ = "thumbnails"

    thumbnail_id = Column(String(64), primary_key=True)
    video_id = Column(String(64), nullable=False, index=True)
    thumbnail_path = Column(String(1024), nullable=False)
    thumbnail_size = Column(Integer, default=0)
    generated_at = Column(DateTime, default=utc_now)
    size_name = Column(String(32), default="medium")
    width = Column(Integer, default=0)
    height = Column(Integer, default=0)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "thumbnail_id": self.thumbnail_id,
            "video_id": self.video_id,
            "thumbnail_path": self.thumbnail_path,
            "thumbnail_size": self.thumbnail_size,
            "generated_at": self.generated_at.isoformat() if self.generated_at else None,
            "size_name": self.size_name,
            "width": self.width,
            "height": self.height,
        }


class VideoStatORM(Base):
    __tablename__ = "video_stats"

    stat_id = Column(String(64), primary_key=True)
    stat_date = Column(String(32), nullable=False, index=True, unique=True)
    upload_count = Column(Integer, default=0)
    transcode_count = Column(Integer, default=0)
    edit_count = Column(Integer, default=0)
    total_size = Column(Integer, default=0)
    avg_duration = Column(Float, default=0.0)
    created_at = Column(DateTime, default=utc_now)
    updated_at = Column(DateTime, default=utc_now, onupdate=utc_now)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "stat_id": self.stat_id,
            "stat_date": self.stat_date,
            "upload_count": self.upload_count,
            "transcode_count": self.transcode_count,
            "edit_count": self.edit_count,
            "total_size": self.total_size,
            "avg_duration": self.avg_duration,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
        }


class HistoryRecordORM(Base):
    __tablename__ = "history_records"

    history_id = Column(String(64), primary_key=True)
    video_id = Column(String(64), nullable=False, index=True)
    action_type = Column(String(32), nullable=False)
    action_details = Column(JSON, default=dict)
    status = Column(String(32), default="completed")
    created_at = Column(DateTime, default=utc_now)
    duration = Column(Float, default=0.0)
    result_path = Column(String(1024), nullable=True)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "history_id": self.history_id,
            "video_id": self.video_id,
            "action_type": self.action_type,
            "action_details": self.action_details,
            "status": self.status,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "duration": self.duration,
            "result_path": self.result_path,
        }


engine = create_engine(settings.database_url, echo=settings.debug)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def init_database():
    Base.metadata.create_all(bind=engine)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
