import zipfile
import tarfile
import shutil
from pathlib import Path
from typing import Optional, Dict, Any, List, Tuple

try:
    import py7zr
except ImportError:
    py7zr = None

try:
    import rarfile
except ImportError:
    rarfile = None

from .config import settings
from .storage import storage
from .metadata import metadata
from .logger import logger
from .models import CompressTask, TaskStatus, FileInfo, now_iso


class CompressorManager:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self):
        self.temp_dir = settings.temp_dir
        self.result_dir = settings.result_dir

    def _get_supported_formats(self) -> List[str]:
        formats = ["zip", "tar", "tar.gz", "tgz"]
        if py7zr is not None:
            formats.append("7z")
        if rarfile is not None:
            formats.append("rar")
        return formats

    def _compress_zip(
        self,
        source_files: List[Path],
        output_path: Path,
        params: Dict[str, Any],
    ) -> int:
        compression = zipfile.ZIP_DEFLATED
        compresslevel = params.get("compresslevel", 6)

        total_size = 0
        with zipfile.ZipFile(output_path, "w", compression, compresslevel=compresslevel) as zf:
            for src_path in source_files:
                if src_path.is_file():
                    zf.write(src_path, arcname=src_path.name)
                    total_size += src_path.stat().st_size
                elif src_path.is_dir():
                    for file in src_path.rglob("*"):
                        if file.is_file():
                            arcname = file.relative_to(src_path.parent)
                            zf.write(file, arcname=str(arcname))
                            total_size += file.stat().st_size

        return total_size

    def _compress_tar(
        self,
        source_files: List[Path],
        output_path: Path,
        compression: str,
        params: Dict[str, Any],
    ) -> int:
        mode = "w"
        if compression == "gz":
            mode = "w:gz"
        elif compression == "bz2":
            mode = "w:bz2"
        elif compression == "xz":
            mode = "w:xz"

        total_size = 0
        with tarfile.open(output_path, mode) as tf:
            for src_path in source_files:
                if src_path.is_file():
                    tf.add(src_path, arcname=src_path.name)
                    total_size += src_path.stat().st_size
                elif src_path.is_dir():
                    tf.add(src_path, arcname=src_path.name)
                    for f in src_path.rglob("*"):
                        if f.is_file():
                            total_size += f.stat().st_size

        return total_size

    def _compress_7z(
        self,
        source_files: List[Path],
        output_path: Path,
        params: Dict[str, Any],
    ) -> int:
        if py7zr is None:
            raise ImportError("py7zr is required for 7z compression")

        total_size = 0
        with py7zr.SevenZipFile(output_path, "w") as archive:
            for src_path in source_files:
                if src_path.is_file():
                    archive.write(src_path, arcname=src_path.name)
                    total_size += src_path.stat().st_size
                elif src_path.is_dir():
                    for f in src_path.rglob("*"):
                        if f.is_file():
                            archive.write(f, arcname=f.name)
                            total_size += f.stat().st_size

        return total_size

    def _extract_zip(
        self,
        archive_path: Path,
        output_dir: Path,
        params: Dict[str, Any],
    ) -> List[Path]:
        extracted = []
        with zipfile.ZipFile(archive_path, "r") as zf:
            zf.extractall(output_dir)
            for name in zf.namelist():
                extracted.append(output_dir / name)
        return extracted

    def _extract_tar(
        self,
        archive_path: Path,
        output_dir: Path,
        params: Dict[str, Any],
    ) -> List[Path]:
        extracted = []
        with tarfile.open(archive_path, "r:*") as tf:
            tf.extractall(output_dir)
            for member in tf.getmembers():
                extracted.append(output_dir / member.name)
        return extracted

    def _extract_7z(
        self,
        archive_path: Path,
        output_dir: Path,
        params: Dict[str, Any],
    ) -> List[Path]:
        if py7zr is None:
            raise ImportError("py7zr is required for 7z extraction")

        with py7zr.SevenZipFile(archive_path, "r") as archive:
            archive.extractall(path=output_dir)

        extracted = []
        for f in output_dir.rglob("*"):
            if f.is_file():
                extracted.append(f)
        return extracted

    def _extract_rar(
        self,
        archive_path: Path,
        output_dir: Path,
        params: Dict[str, Any],
    ) -> List[Path]:
        if rarfile is None:
            raise ImportError("rarfile is required for RAR extraction")

        with rarfile.RarFile(archive_path, "r") as rf:
            rf.extractall(output_dir)
            extracted = [output_dir / name for name in rf.namelist()]
        return extracted

    def compress(
        self,
        file_ids: List[str],
        compress_format: str = "zip",
        params: Dict[str, Any] = None,
        user_id: str = "anonymous",
    ) -> Tuple[bool, Optional[CompressTask], str]:
        source_paths = []
        for file_id in file_ids:
            file_info = metadata.get_file(file_id)
            if not file_info:
                return False, None, f"File not found: {file_id}"
            path = Path(file_info.storage_path)
            if not path.exists():
                return False, None, f"File path does not exist: {file_info.storage_path}"
            source_paths.append((file_id, path, file_info.file_name))

        compress_format = compress_format.lower()
        supported = self._get_supported_formats()

        if compress_format not in supported:
            return False, None, f"Unsupported compression format: {compress_format}. Supported: {supported}"

        task = CompressTask(
            source_files=file_ids,
            compress_format=compress_format,
            compression_params=params or {},
            compress_status=TaskStatus.PENDING,
        )

        metadata.save_compress_task(task)

        logger.info(
            f"Compress task created: {task.compress_id} (format: {compress_format}, files: {len(file_ids)})",
            task_id=task.compress_id,
            task_type="compress",
        )

        output_path = storage.get_temp_path(f"compressed.{compress_format}")

        try:
            task.compress_status = TaskStatus.PROCESSING
            metadata.save_compress_task(task)

            file_paths = [path for _, path, _ in source_paths]

            if compress_format == "zip":
                self._compress_zip(file_paths, output_path, params or {})
            elif compress_format in ["tar", "tar.gz", "tgz", "tar.bz2", "tar.xz"]:
                if compress_format == "tar.gz" or compress_format == "tgz":
                    comp = "gz"
                elif compress_format == "tar.bz2":
                    comp = "bz2"
                elif compress_format == "tar.xz":
                    comp = "xz"
                else:
                    comp = None
                self._compress_tar(file_paths, output_path, comp, params or {})
            elif compress_format == "7z":
                self._compress_7z(file_paths, output_path, params or {})

            output_filename = f"archive_{task.compress_id}.{compress_format}"
            result_file = storage.store_file_from_path(
                output_path,
                target_filename=output_filename,
                upload_user=user_id,
                is_result=True,
            )

            task.result_file_id = result_file.file_id
            task.compress_status = TaskStatus.COMPLETED
            task.compress_time = now_iso()

            metadata.save_compress_task(task)

            if output_path.exists():
                output_path.unlink()

            logger.info(
                f"Compress completed: {task.compress_id} -> {result_file.file_id}",
                task_id=task.compress_id,
                file_id=result_file.file_id,
                task_type="compress",
            )

            return True, task, "Compression completed"

        except Exception as e:
            error_msg = f"Compression failed: {str(e)}"
            task.compress_status = TaskStatus.FAILED
            task.error_message = error_msg
            metadata.save_compress_task(task)

            logger.error(error_msg, task_id=task.compress_id, task_type="compress")
            return False, task, error_msg

    def extract(
        self,
        file_id: str,
        params: Dict[str, Any] = None,
        user_id: str = "anonymous",
    ) -> Tuple[bool, Optional[List[str]], str]:
        file_info = metadata.get_file(file_id)
        if not file_info:
            return False, None, f"File not found: {file_id}"

        source_path = Path(file_info.storage_path)
        if not source_path.exists():
            return False, None, f"File path does not exist: {file_info.storage_path}"

        ext = source_path.suffix.lower().lstrip(".")
        name = source_path.name.lower()
        archive_type = None

        if name.endswith(".tar.gz") or name.endswith(".tgz"):
            archive_type = "tar"
        elif name.endswith(".tar.bz2") or name.endswith(".tar.xz"):
            archive_type = "tar"
        elif ext == "zip":
            archive_type = "zip"
        elif ext == "tar":
            archive_type = "tar"
        elif ext == "7z":
            archive_type = "7z"
        elif ext == "rar":
            archive_type = "rar"
        else:
            return False, None, f"Unsupported archive format: {ext}"

        params = params or {}
        output_dir = storage.get_temp_path(f"extract_{file_id}")
        output_dir.mkdir(parents=True, exist_ok=True)

        logger.info(
            f"Extracting archive: {file_id} (type: {archive_type})",
            file_id=file_id,
            task_type="extract",
        )

        try:
            if archive_type == "zip":
                extracted_files = self._extract_zip(source_path, output_dir, params)
            elif archive_type == "tar":
                extracted_files = self._extract_tar(source_path, output_dir, params)
            elif archive_type == "7z":
                extracted_files = self._extract_7z(source_path, output_dir, params)
            elif archive_type == "rar":
                extracted_files = self._extract_rar(source_path, output_dir, params)
            else:
                raise ValueError(f"Unsupported archive type: {archive_type}")

            stored_file_ids = []
            for extracted_file in extracted_files:
                if extracted_file.is_file():
                    stored = storage.store_file_from_path(
                        extracted_file,
                        upload_user=user_id,
                        is_result=True,
                    )
                    stored_file_ids.append(stored.file_id)

            logger.info(
                f"Extraction completed: {file_id}, {len(stored_file_ids)} files",
                file_id=file_id,
                task_type="extract",
            )

            return True, stored_file_ids, "Extraction completed"

        except Exception as e:
            error_msg = f"Extraction failed: {str(e)}"
            logger.error(error_msg, file_id=file_id, task_type="extract")
            return False, None, error_msg

    def get_compress_task(self, compress_id: str) -> Optional[Dict[str, Any]]:
        task = metadata.get_compress_task(compress_id)
        if not task:
            return None
        return task.model_dump()


compressor = CompressorManager()
