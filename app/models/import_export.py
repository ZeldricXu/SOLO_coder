from __future__ import annotations
from datetime import datetime
from typing import Optional, Union, Dict, List
from sqlalchemy import Integer, String, Text, DateTime, JSON, ForeignKey, Enum, Index
from sqlalchemy.orm import Mapped, mapped_column, relationship
import enum

from app.core.database import Base


class ImportJobType(str, enum.Enum):
    PRODUCT_IMPORT = "PRODUCT_IMPORT"
    SKU_IMPORT = "SKU_IMPORT"


class ImportStatus(str, enum.Enum):
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class FileType(str, enum.Enum):
    EXCEL = "EXCEL"
    CSV = "CSV"


class ImportErrorCode(str, enum.Enum):
    REQUIRED_FIELD_MISSING = "REQUIRED_FIELD_MISSING"
    INVALID_ATTRIBUTE = "INVALID_ATTRIBUTE"
    DUPLICATE_SKU_CODE = "DUPLICATE_SKU_CODE"
    INVALID_PRICE = "INVALID_PRICE"
    INVALID_STATUS = "INVALID_STATUS"


class ImportJob(Base):
    __tablename__ = "import_jobs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    job_type: Mapped[ImportJobType] = mapped_column(
        Enum(ImportJobType), nullable=False, index=True
    )
    file_name: Mapped[str] = mapped_column(String(255), nullable=False)
    file_type: Mapped[FileType] = mapped_column(Enum(FileType), nullable=False)
    total_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    success_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    failed_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    status: Mapped[ImportStatus] = mapped_column(
        Enum(ImportStatus), nullable=False, default=ImportStatus.PENDING, index=True
    )
    created_by: Mapped[int] = mapped_column(Integer, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, default=datetime.utcnow, index=True
    )
    completed_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    error_report_path: Mapped[Optional[str]] = mapped_column(String(500), nullable=True)

    errors: Mapped[List["ImportError"]] = relationship(
        "ImportError", back_populates="job", cascade="all, delete-orphan"
    )

    __table_args__ = (
        Index("ix_import_job_type_status", "job_type", "status"),
        Index("ix_import_job_created_at", "created_at"),
    )

    def __repr__(self) -> str:
        return (
            f"<ImportJob(id={self.id}, job_type='{self.job_type}', "
            f"status='{self.status}', total={self.total_count})>"
        )


class ImportError(Base):
    __tablename__ = "import_errors"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    job_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("import_jobs.id"), nullable=False, index=True
    )
    row_number: Mapped[int] = mapped_column(Integer, nullable=False)
    field_name: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    error_code: Mapped[ImportErrorCode] = mapped_column(
        Enum(ImportErrorCode), nullable=False
    )
    error_message: Mapped[str] = mapped_column(Text, nullable=False)
    raw_data: Mapped[Optional[Union[Dict, List]]] = mapped_column(JSON, nullable=True)

    job: Mapped["ImportJob"] = relationship("ImportJob", back_populates="errors")

    __table_args__ = (
        Index("ix_import_error_job_id", "job_id"),
        Index("ix_import_error_row", "job_id", "row_number"),
    )

    def __repr__(self) -> str:
        return (
            f"<ImportError(id={self.id}, job_id={self.job_id}, "
            f"row={self.row_number}, code='{self.error_code}')>"
        )
