from pathlib import Path
from typing import Optional, Tuple, Dict, Any
from .storage import storage
from .metadata import metadata
from .logger import logger
from .models import FileInfo


class DownloadManager:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def get_file_for_download(
        self,
        file_id: str,
    ) -> Tuple[bool, Optional[Path], Optional[FileInfo], str]:
        file_info = metadata.get_file(file_id)
        if not file_info:
            return False, None, None, f"File not found: {file_id}"

        file_path = Path(file_info.storage_path)
        if not file_path.exists():
            return False, None, file_info, f"File path does not exist: {file_info.storage_path}"

        logger.info(
            f"File prepared for download: {file_id} ({file_info.file_name})",
            file_id=file_id,
            task_type="download",
        )

        return True, file_path, file_info, "File ready for download"

    def get_file_info_for_download(
        self,
        file_id: str,
    ) -> Optional[Dict[str, Any]]:
        file_info = metadata.get_file(file_id)
        if not file_info:
            return None

        file_path = Path(file_info.storage_path)
        if not file_path.exists():
            return None

        return {
            "file_path": str(file_path),
            "file_name": file_info.file_name,
            "file_size": file_info.file_size,
            "file_type": file_info.file_type,
            "mime_type": file_info.mime_type or self._guess_mime_type(file_path),
        }

    def _guess_mime_type(self, file_path: Path) -> str:
        ext = file_path.suffix.lower().lstrip(".")
        mime_map = {
            "pdf": "application/pdf",
            "jpg": "image/jpeg",
            "jpeg": "image/jpeg",
            "png": "image/png",
            "gif": "image/gif",
            "webp": "image/webp",
            "tiff": "image/tiff",
            "bmp": "image/bmp",
            "mp4": "video/mp4",
            "webm": "video/webm",
            "avi": "video/x-msvideo",
            "mkv": "video/x-matroska",
            "mov": "video/quicktime",
            "mp3": "audio/mpeg",
            "wav": "audio/wav",
            "flac": "audio/flac",
            "zip": "application/zip",
            "7z": "application/x-7z-compressed",
            "rar": "application/vnd.rar",
            "tar": "application/x-tar",
            "gz": "application/gzip",
            "txt": "text/plain",
            "csv": "text/csv",
            "json": "application/json",
            "xml": "application/xml",
            "html": "text/html",
            "css": "text/css",
            "js": "application/javascript",
            "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xls": "application/vnd.ms-excel",
            "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "doc": "application/msword",
            "pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "ppt": "application/vnd.ms-powerpoint",
        }
        return mime_map.get(ext, "application/octet-stream")

    def list_downloadable_files(self, user_id: Optional[str] = None):
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
                "download_url": f"/api/v1/files/download?file_id={f.file_id}",
            }
            for f in files
        ]

    def stream_file_iterator(self, file_path: Path, chunk_size: int = 8192):
        with open(file_path, "rb") as f:
            while True:
                chunk = f.read(chunk_size)
                if not chunk:
                    break
                yield chunk


download_manager = DownloadManager()
