from typing import Optional, Dict, Any, Tuple
from .config import settings
from .storage import storage
from .metadata import metadata
from .logger import logger
from .models import FileInfo, FileStatus, TaskStatus
from .async_upload import async_upload


class UploadManager:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self):
        self.max_file_size = settings.max_file_size
        self.chunk_size = settings.chunk_size
        self.enable_async_upload = settings.enable_async_upload

    def upload_file(
        self,
        file_data: bytes,
        filename: str,
        upload_user: str = "anonymous",
        mime_type: Optional[str] = None,
    ) -> Tuple[bool, Optional[FileInfo], str]:
        if len(file_data) > self.max_file_size:
            error_msg = f"File size exceeds maximum limit of {self.max_file_size} bytes"
            logger.error(f"Upload failed: {error_msg}")
            return False, None, error_msg

        try:
            file_info = storage.store_file(
                file_data=file_data,
                filename=filename,
                upload_user=upload_user,
                mime_type=mime_type,
            )
            logger.info(
                f"File uploaded successfully: {file_info.file_id}",
                file_id=file_info.file_id,
            )
            return True, file_info, "Upload completed"
        except Exception as e:
            error_msg = f"Upload failed: {str(e)}"
            logger.error(error_msg)
            return False, None, error_msg

    def init_chunk_upload(
        self,
        file_name: str,
        total_size: int,
        upload_user: str = "anonymous",
    ) -> Dict[str, Any]:
        if total_size > self.max_file_size:
            return {
                "success": False,
                "message": f"File size exceeds maximum limit of {self.max_file_size} bytes",
            }

        session, total_chunks = storage.create_chunk_session(
            file_name=file_name,
            total_size=total_size,
            upload_user=upload_user,
        )

        return {
            "success": True,
            "session_id": session.session_id,
            "total_chunks": total_chunks,
            "chunk_size": self.chunk_size,
            "message": "Chunk upload initialized",
        }

    def upload_chunk(
        self,
        session_id: str,
        chunk_index: int,
        chunk_data: bytes,
    ) -> Dict[str, Any]:
        success, message, progress = storage.store_chunk(
            session_id=session_id,
            chunk_index=chunk_index,
            chunk_data=chunk_data,
        )

        is_complete = storage.is_chunks_complete(session_id)

        result = {
            "success": success,
            "message": message,
            "progress": progress,
            "is_complete": is_complete,
        }

        if is_complete:
            result["message"] = "All chunks received, ready for merge"

        return result

    def complete_chunk_upload(self, session_id: str) -> Dict[str, Any]:
        session = metadata.get_upload_session(session_id)
        if not session:
            return {
                "success": False,
                "message": "Upload session not found",
            }

        if not storage.is_chunks_complete(session_id):
            return {
                "success": False,
                "message": "Not all chunks received",
                "progress": len(session.chunks_received) / session.total_chunks * 100,
            }

        try:
            file_info = storage.merge_chunks(session_id)
            if file_info:
                return {
                    "success": True,
                    "message": "Upload completed",
                    "file_id": file_info.file_id,
                    "file_name": file_info.file_name,
                    "file_size": file_info.file_size,
                    "upload_status": "completed",
                }
            else:
                return {
                    "success": False,
                    "message": "Failed to merge chunks",
                }
        except Exception as e:
            error_msg = f"Merge failed: {str(e)}"
            logger.error(error_msg, task_id=session_id)
            return {
                "success": False,
                "message": error_msg,
            }

    def get_upload_progress(self, session_id: str) -> Dict[str, Any]:
        session = metadata.get_upload_session(session_id)
        if not session:
            return {
                "success": False,
                "message": "Upload session not found",
            }

        progress = len(session.chunks_received) / session.total_chunks * 100
        is_complete = storage.is_chunks_complete(session_id)

        return {
            "success": True,
            "session_id": session_id,
            "file_name": session.file_name,
            "total_chunks": session.total_chunks,
            "chunks_received": len(session.chunks_received),
            "progress": progress,
            "is_complete": is_complete,
        }

    def get_file_info(self, file_id: str) -> Optional[FileInfo]:
        return metadata.get_file(file_id)

    def list_files(self, user_id: Optional[str] = None):
        files = metadata.list_files(user_id)
        return [
            {
                "file_id": f.file_id,
                "file_name": f.file_name,
                "file_type": f.file_type,
                "file_size": f.file_size,
                "upload_time": f.upload_time,
                "status": f.status,
                "expire_at": f.expire_at,
            }
            for f in files
        ]

    def delete_file(self, file_id: str) -> bool:
        return storage.delete_file(file_id)

    def init_async_upload(
        self,
        file_name: str,
        total_size: int,
        user_id: str = "anonymous",
    ) -> Dict[str, Any]:
        if not self.enable_async_upload:
            return {
                "success": False,
                "message": "Async upload is disabled",
            }
        return async_upload.init_async_upload(
            file_name=file_name,
            total_size=total_size,
            user_id=user_id,
        )

    def upload_chunk_async(
        self,
        upload_task_id: str,
        chunk_index: int,
        chunk_data: bytes,
    ) -> Dict[str, Any]:
        if not self.enable_async_upload:
            return {
                "success": False,
                "message": "Async upload is disabled",
            }
        return async_upload.upload_chunk_async(
            upload_task_id=upload_task_id,
            chunk_index=chunk_index,
            chunk_data=chunk_data,
        )

    def get_async_upload_status(self, upload_task_id: str) -> Optional[Dict[str, Any]]:
        if not self.enable_async_upload:
            return None
        return async_upload.get_upload_status(upload_task_id)

    def list_async_uploads(self, status: Optional[str] = None) -> list:
        if not self.enable_async_upload:
            return []
        return async_upload.list_async_uploads(status=status)

    def cancel_async_upload(self, upload_task_id: str) -> bool:
        if not self.enable_async_upload:
            return False
        return async_upload.cancel_async_upload(upload_task_id)


upload_manager = UploadManager()
