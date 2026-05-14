import hashlib
import shutil
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional, List
from .config import settings, cleanup_strategies
from .models import FileInfo, FileStatus, expire_at_days, generate_id
from .metadata import metadata
from .logger import logger


class StorageManager:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self):
        self.upload_dir = settings.upload_dir
        self.result_dir = settings.result_dir
        self.temp_dir = settings.temp_dir
        self.chunks_dir = settings.chunks_dir
        self.expire_days = settings.file_expire_days

    def _get_file_type_category(self, file_ext: str) -> str:
        ext = file_ext.lower()
        if ext in ["jpg", "jpeg", "png", "webp", "gif", "tiff", "bmp", "svg"]:
            return "image"
        elif ext in ["mp4", "webm", "avi", "mkv", "mov", "wmv", "flv"]:
            return "video"
        elif ext == "pdf":
            return "pdf"
        elif ext in ["zip", "rar", "tar", "gz", "bz2", "7z"]:
            return "archive"
        else:
            return "default"

    def _get_expire_days_for_type(self, file_ext: str, is_result: bool = False) -> int:
        if is_result:
            return cleanup_strategies.get_expire_days("result")
        category = self._get_file_type_category(file_ext)
        return cleanup_strategies.get_expire_days(category)

    def _calculate_sha256(self, file_path: Path) -> str:
        sha256 = hashlib.sha256()
        with open(file_path, "rb") as f:
            for chunk in iter(lambda: f.read(4096), b""):
                sha256.update(chunk)
        return sha256.hexdigest()

    def _get_file_extension(self, filename: str) -> str:
        ext = Path(filename).suffix.lower()
        if ext.startswith("."):
            ext = ext[1:]
        return ext or "bin"

    def store_file(
        self,
        file_data: bytes,
        filename: str,
        upload_user: str = "anonymous",
        mime_type: Optional[str] = None,
    ) -> FileInfo:
        file_ext = self._get_file_extension(filename)
        file_id = generate_id("file")
        storage_filename = f"{file_id}.{file_ext}"
        storage_path = self.upload_dir / storage_filename

        with open(storage_path, "wb") as f:
            f.write(file_data)

        file_size = len(file_data)
        sha256_hash = self._calculate_sha256(storage_path)
        file_expire_days = self._get_expire_days_for_type(file_ext, is_result=False)

        file_info = FileInfo(
            file_id=file_id,
            file_name=filename,
            file_type=file_ext,
            file_size=file_size,
            storage_path=str(storage_path),
            upload_user=upload_user,
            status=FileStatus.STORED,
            expire_at=expire_at_days(file_expire_days),
            sha256=sha256_hash,
            mime_type=mime_type,
        )

        metadata.save_file(file_info)
        logger.info(
            f"File stored: {file_id} ({filename}, {file_size} bytes)",
            file_id=file_id,
        )
        return file_info

    def store_file_from_path(
        self,
        source_path: Path,
        target_filename: Optional[str] = None,
        upload_user: str = "anonymous",
        is_result: bool = False,
    ) -> FileInfo:
        filename = target_filename or source_path.name
        file_ext = self._get_file_extension(filename)
        file_id = generate_id("file")
        storage_filename = f"{file_id}.{file_ext}"

        target_dir = self.result_dir if is_result else self.upload_dir
        storage_path = target_dir / storage_filename

        shutil.copy2(source_path, storage_path)

        file_size = storage_path.stat().st_size
        sha256_hash = self._calculate_sha256(storage_path)
        file_expire_days = self._get_expire_days_for_type(file_ext, is_result=is_result)

        file_info = FileInfo(
            file_id=file_id,
            file_name=filename,
            file_type=file_ext,
            file_size=file_size,
            storage_path=str(storage_path),
            upload_user=upload_user,
            status=FileStatus.STORED,
            expire_at=expire_at_days(file_expire_days),
            sha256=sha256_hash,
        )

        metadata.save_file(file_info)
        logger.info(
            f"File stored from path: {file_id} ({filename})",
            file_id=file_id,
        )
        return file_info

    def get_file_path(self, file_id: str) -> Optional[Path]:
        file_info = metadata.get_file(file_id)
        if file_info:
            return Path(file_info.storage_path)
        return None

    def get_file_info(self, file_id: str) -> Optional[FileInfo]:
        return metadata.get_file(file_id)

    def delete_file(self, file_id: str) -> bool:
        file_info = metadata.get_file(file_id)
        if file_info:
            try:
                path = Path(file_info.storage_path)
                if path.exists():
                    path.unlink()
            except Exception as e:
                logger.error(f"Error deleting file {file_id}: {e}")

            metadata.delete_file(file_id)
            logger.info(f"File deleted: {file_id}")
            return True
        return False

    def cleanup_expired(self) -> int:
        now = datetime.utcnow()
        deleted_count = 0

        for file_info in metadata.list_files():
            try:
                expire_str = file_info.expire_at.replace("Z", "")
                expire_time = datetime.fromisoformat(expire_str)
                if now > expire_time:
                    self.delete_file(file_info.file_id)
                    deleted_count += 1
            except Exception as e:
                logger.error(f"Error checking expiration for {file_info.file_id}: {e}")

        if deleted_count > 0:
            logger.info(f"Cleaned up {deleted_count} expired files")
        return deleted_count

    def get_temp_path(self, filename: str) -> Path:
        temp_id = generate_id("temp")
        return self.temp_dir / f"{temp_id}_{filename}"

    def create_chunk_session(
        self, file_name: str, total_size: int, upload_user: str = "anonymous"
    ) -> tuple:
        from .models import UploadSession

        total_chunks = (total_size + settings.chunk_size - 1) // settings.chunk_size
        session = UploadSession(
            file_name=file_name,
            total_size=total_size,
            total_chunks=total_chunks,
            upload_user=upload_user,
        )
        session_dir = self.chunks_dir / session.session_id
        session_dir.mkdir(parents=True, exist_ok=True)

        metadata.save_upload_session(session)
        logger.info(
            f"Chunk session created: {session.session_id} ({file_name}, {total_chunks} chunks)"
        )
        return session, total_chunks

    def store_chunk(
        self, session_id: str, chunk_index: int, chunk_data: bytes
    ) -> tuple:
        session = metadata.get_upload_session(session_id)
        if not session:
            return False, "Session not found", 0

        session_dir = self.chunks_dir / session_id
        chunk_path = session_dir / f"chunk_{chunk_index}"

        with open(chunk_path, "wb") as f:
            f.write(chunk_data)

        if chunk_index not in session.chunks_received:
            session.chunks_received.append(chunk_index)
            session.chunks_received.sort()

        metadata.save_upload_session(session)
        progress = len(session.chunks_received) / session.total_chunks * 100

        return True, f"Chunk {chunk_index} stored", progress

    def is_chunks_complete(self, session_id: str) -> bool:
        session = metadata.get_upload_session(session_id)
        if not session:
            return False
        return len(session.chunks_received) == session.total_chunks

    def merge_chunks(self, session_id: str) -> Optional[FileInfo]:
        session = metadata.get_upload_session(session_id)
        if not session:
            return None

        if not self.is_chunks_complete(session_id):
            return None

        session_dir = self.chunks_dir / session_id

        file_ext = self._get_file_extension(session.file_name)
        file_id = generate_id("file")
        storage_filename = f"{file_id}.{file_ext}"
        storage_path = self.upload_dir / storage_filename

        total_size = 0
        with open(storage_path, "wb") as out_file:
            for i in range(session.total_chunks):
                chunk_path = session_dir / f"chunk_{i}"
                if chunk_path.exists():
                    with open(chunk_path, "rb") as chunk_file:
                        data = chunk_file.read()
                        total_size += len(data)
                        out_file.write(data)

        sha256_hash = self._calculate_sha256(storage_path)
        file_expire_days = self._get_expire_days_for_type(file_ext, is_result=False)

        file_info = FileInfo(
            file_id=file_id,
            file_name=session.file_name,
            file_type=file_ext,
            file_size=total_size,
            storage_path=str(storage_path),
            upload_user=session.upload_user,
            status=FileStatus.STORED,
            expire_at=expire_at_days(file_expire_days),
            sha256=sha256_hash,
            chunks=session.total_chunks,
            chunks_received=session.total_chunks,
            chunk_session_id=session_id,
        )

        metadata.save_file(file_info)

        try:
            shutil.rmtree(session_dir)
        except Exception as e:
            logger.warning(f"Failed to clean up chunk directory: {e}")

        metadata.delete_upload_session(session_id)

        logger.info(
            f"Chunks merged: {file_id} ({session.file_name}, {total_size} bytes)",
            file_id=file_id,
        )
        return file_info

    def list_all_files(self) -> List[FileInfo]:
        return metadata.list_files()


storage = StorageManager()
