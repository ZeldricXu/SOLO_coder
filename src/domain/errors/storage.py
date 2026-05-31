"""Storage-related error classes."""
from __future__ import annotations

from typing import Any, Dict, Optional

from .base import BaseError, ErrorCode


class StorageError(BaseError):
    def __init__(
        self,
        message: str,
        code: ErrorCode = ErrorCode.INTERNAL_ERROR,
        details: Optional[Dict[str, Any]] = None,
        suggestion: Optional[str] = None,
    ) -> None:
        super().__init__(message, code, details, suggestion)


class FileNotFoundError(StorageError):
    def __init__(self, file_path: str, suggestion: Optional[str] = None) -> None:
        super().__init__(
            message=f"File not found: {file_path}",
            code=ErrorCode.FILE_NOT_FOUND,
            details={"file_path": file_path},
            suggestion=suggestion or "Check if the file path is correct and the file exists.",
        )


class FileAlreadyExistsError(StorageError):
    def __init__(self, file_path: str, suggestion: Optional[str] = None) -> None:
        super().__init__(
            message=f"File already exists: {file_path}",
            code=ErrorCode.FILE_ALREADY_EXISTS,
            details={"file_path": file_path},
            suggestion=suggestion or "Use a different file name or enable overwrite mode.",
        )


class StorageCapacityExceededError(StorageError):
    def __init__(
        self,
        required_size: int,
        available_size: int,
        tier: str,
        suggestion: Optional[str] = None,
    ) -> None:
        super().__init__(
            message=f"Storage capacity exceeded for tier '{tier}'. Required: {required_size} bytes, Available: {available_size} bytes",
            code=ErrorCode.STORAGE_CAPACITY_EXCEEDED,
            details={
                "required_size": required_size,
                "available_size": available_size,
                "storage_tier": tier,
            },
            suggestion=suggestion or "Consider archiving older files or upgrading storage capacity.",
        )


class ChecksumMismatchError(StorageError):
    def __init__(
        self,
        file_path: str,
        expected_checksum: str,
        actual_checksum: str,
        suggestion: Optional[str] = None,
    ) -> None:
        super().__init__(
            message=f"Checksum mismatch for file: {file_path}",
            code=ErrorCode.CHECKSUM_MISMATCH,
            details={
                "file_path": file_path,
                "expected_checksum": expected_checksum,
                "actual_checksum": actual_checksum,
            },
            suggestion=suggestion or "The file may be corrupted. Try re-uploading the file.",
        )


class InvalidStorageTierError(StorageError):
    def __init__(
        self,
        tier: str,
        valid_tiers: list,
        suggestion: Optional[str] = None,
    ) -> None:
        super().__init__(
            message=f"Invalid storage tier: {tier}. Valid tiers are: {', '.join(valid_tiers)}",
            code=ErrorCode.INVALID_STORAGE_TIER,
            details={"provided_tier": tier, "valid_tiers": valid_tiers},
            suggestion=suggestion or "Use one of the valid storage tiers: hot, cold, archive.",
        )
