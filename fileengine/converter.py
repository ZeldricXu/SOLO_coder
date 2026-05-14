import time
import io
import shutil
import subprocess
from pathlib import Path
from typing import Optional, Dict, Any, List, Tuple
from PIL import Image

try:
    from pypdf import PdfReader
except ImportError:
    PdfReader = None

try:
    import moviepy.editor as moviepy
except ImportError:
    moviepy = None

from .config import settings, conversion_profiles
from .storage import storage
from .metadata import metadata
from .logger import logger
from .models import (
    FileInfo,
    ConvertTask,
    ConvertResult,
    TaskStatus,
    now_iso,
    generate_id,
)


SUPPORTED_FORMATS = {
    "pdf": ["jpg", "jpeg", "png", "webp", "tiff"],
    "image": ["jpg", "jpeg", "png", "webp", "gif", "tiff", "bmp"],
    "video": ["mp4", "webm", "avi", "mkv", "mov"],
}


class ConverterManager:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self):
        self.temp_dir = settings.temp_dir
        self.result_dir = settings.result_dir
        self.ffmpeg_path = settings.ffmpeg_path

    def _get_source_format(self, file_ext: str) -> str:
        ext = file_ext.lower()
        if ext == "pdf":
            return "pdf"
        elif ext in ["jpg", "jpeg", "png", "webp", "gif", "tiff", "bmp"]:
            return "image"
        elif ext in ["mp4", "webm", "avi", "mkv", "mov", "wmv"]:
            return "video"
        return ext

    def _is_format_supported(self, source_format: str, target_format: str) -> bool:
        target = target_format.lower()
        if source_format == "pdf":
            return target in SUPPORTED_FORMATS["pdf"]
        elif source_format == "image":
            return target in SUPPORTED_FORMATS["image"]
        elif source_format == "video":
            return target in SUPPORTED_FORMATS["video"]
        return False

    def _get_profile_name(
        self,
        user_params: Dict[str, Any] = None,
    ) -> str:
        if user_params:
            profile = user_params.get("profile")
            if profile and profile in conversion_profiles.list_profiles():
                return profile
        return "default"

    def _merge_conversion_params(
        self,
        source_format: str,
        profile_name: str,
        user_params: Dict[str, Any] = None,
    ) -> Dict[str, Any]:
        merged = conversion_profiles.merge_with_user_params(
            profile_name=profile_name,
            source_format=source_format,
            user_params=user_params or {},
        )
        return merged

    def _validate_conversion_params(
        self,
        params: Dict[str, Any],
        source_format: str,
        target_format: str,
    ) -> Tuple[bool, str]:
        try:
            if "quality" in params:
                quality = params["quality"]
                if not isinstance(quality, int) or quality < 1 or quality > 100:
                    return False, "quality must be between 1 and 100"

            if "dpi" in params:
                dpi = params["dpi"]
                if not isinstance(dpi, int) or dpi < 1 or dpi > 1200:
                    return False, "dpi must be between 1 and 1200"

            if "max_width" in params and params["max_width"] is not None:
                if not isinstance(params["max_width"], int) or params["max_width"] < 1:
                    return False, "max_width must be a positive integer"

            if "max_height" in params and params["max_height"] is not None:
                if not isinstance(params["max_height"], int) or params["max_height"] < 1:
                    return False, "max_height must be a positive integer"

            if "fps" in params and source_format == "video":
                fps = params["fps"]
                if not isinstance(fps, int) or fps < 1 or fps > 120:
                    return False, "fps must be between 1 and 120"

            if "crf" in params and source_format == "video":
                crf = params["crf"]
                if not isinstance(crf, int) or crf < 0 or crf > 51:
                    return False, "crf must be between 0 and 51 for video"

            if "pages" in params:
                pages = params["pages"]
                if not isinstance(pages, (list, type(None))):
                    return False, "pages must be a list of integers or None"
                if isinstance(pages, list):
                    for p in pages:
                        if not isinstance(p, int):
                            return False, "pages list must contain integers"

            return True, "Valid"
        except Exception as e:
            return False, f"Validation error: {str(e)}"

    def create_convert_task(
        self,
        file_id: str,
        target_format: str,
        conversion_params: Dict[str, Any] = None,
        user_id: str = "anonymous",
        profile_name: str = None,
    ) -> Tuple[bool, Optional[ConvertTask], str]:
        file_info = metadata.get_file(file_id)
        if not file_info:
            return False, None, f"Source file not found: {file_id}"

        source_format = self._get_source_format(file_info.file_type)
        target_format = target_format.lower()

        if not self._is_format_supported(source_format, target_format):
            return False, None, f"Unsupported conversion: {source_format} -> {target_format}"

        if not profile_name:
            profile_name = self._get_profile_name(conversion_params)

        merged_params = self._merge_conversion_params(
            source_format=source_format,
            profile_name=profile_name,
            user_params=conversion_params,
        )

        valid, error_msg = self._validate_conversion_params(
            merged_params,
            source_format,
            target_format,
        )
        if not valid:
            return False, None, f"Invalid conversion parameters: {error_msg}"

        if conversion_params:
            merged_params["_profile_used"] = profile_name

        task = ConvertTask(
            source_file_id=file_id,
            source_format=source_format,
            target_format=target_format,
            conversion_params=merged_params,
        )

        metadata.save_convert_task(task)

        logger.info(
            f"Convert task created: {task.task_id} ({file_info.file_type} -> {target_format}, profile: {profile_name})",
            task_id=task.task_id,
            file_id=file_id,
            task_type="convert",
        )

        return True, task, "Convert task created"

    def list_conversion_profiles(self) -> List[Dict[str, Any]]:
        profiles = []
        for name in conversion_profiles.list_profiles():
            profile = conversion_profiles.get_profile(name)
            profiles.append({
                "name": name,
                "description": profile.get("description", ""),
            })
        return profiles

    def get_profile_details(self, profile_name: str) -> Optional[Dict[str, Any]]:
        if profile_name not in conversion_profiles.list_profiles():
            return None
        return conversion_profiles.get_profile(profile_name)

    def _convert_pdf_to_images(
        self,
        source_path: Path,
        target_format: str,
        params: Dict[str, Any],
    ) -> List[Path]:
        if PdfReader is None:
            raise ImportError("pypdf is not installed. Please install it with: pip install pypdf")

        dpi = params.get("dpi", 300)
        quality = params.get("quality", 80)
        page_range = params.get("pages", None)

        reader = PdfReader(str(source_path))
        num_pages = len(reader.pages)

        if page_range:
            pages_to_convert = [p for p in page_range if 0 <= p < num_pages]
        else:
            pages_to_convert = list(range(num_pages))

        converted_files = []
        for page_num in pages_to_convert:
            page = reader.pages[page_num]

            try:
                images = page.images
                if images:
                    for i, image_file in enumerate(images):
                        output_file = storage.get_temp_path(
                            f"page_{page_num + 1}_{i}.{target_format}"
                        )

                        img = Image.open(io.BytesIO(image_file.data))
                        if img.mode != "RGB":
                            img = img.convert("RGB")

                        img.save(
                            str(output_file),
                            format=target_format.upper(),
                            quality=quality,
                            optimize=True,
                        )
                        converted_files.append(output_file)
                else:
                    img = Image.new("RGB", (1000, 1414), "white")
                    output_file = storage.get_temp_path(f"page_{page_num + 1}.{target_format}")
                    img.save(
                        str(output_file),
                        format=target_format.upper(),
                        quality=quality,
                        optimize=True,
                    )
                    converted_files.append(output_file)
            except Exception as e:
                logger.warning(f"Error processing page {page_num}: {e}")
                img = Image.new("RGB", (1000, 1414), "white")
                output_file = storage.get_temp_path(f"page_{page_num + 1}.{target_format}")
                img.save(
                    str(output_file),
                    format=target_format.upper(),
                    quality=quality,
                    optimize=True,
                )
                converted_files.append(output_file)

        return converted_files

    def _convert_image(
        self,
        source_path: Path,
        target_format: str,
        params: Dict[str, Any],
    ) -> Path:
        quality = params.get("quality", 80)
        max_width = params.get("max_width")
        max_height = params.get("max_height")
        resize = params.get("resize")

        output_file = storage.get_temp_path(f"converted.{target_format}")

        with Image.open(str(source_path)) as img:
            original_mode = img.mode

            if resize:
                img = img.resize(tuple(resize), Image.LANCZOS)
            elif max_width or max_height:
                ratio = 1.0
                if max_width and img.width > max_width:
                    ratio = min(ratio, max_width / img.width)
                if max_height and img.height > max_height:
                    ratio = min(ratio, max_height / img.height)
                if ratio < 1.0:
                    new_size = (int(img.width * ratio), int(img.height * ratio))
                    img = img.resize(new_size, Image.LANCZOS)

            if target_format.lower() in ["jpg", "jpeg"]:
                if img.mode != "RGB":
                    img = img.convert("RGB")

            img.save(
                str(output_file),
                format=target_format.upper(),
                quality=quality,
                optimize=True,
            )

        return output_file

    def _convert_video(
        self,
        source_path: Path,
        target_format: str,
        params: Dict[str, Any],
    ) -> Path:
        output_file = storage.get_temp_path(f"converted.{target_format}")

        codec_map = {
            "mp4": "libx264",
            "webm": "libvpx",
            "avi": "mpeg4",
            "mkv": "libx264",
            "mov": "libx264",
        }

        if moviepy is not None:
            try:
                clip = moviepy.VideoFileClip(str(source_path))

                fps = params.get("fps")
                if fps:
                    clip = clip.set_fps(fps)

                resize = params.get("resize")
                if resize:
                    clip = clip.resize(height=resize[1] if len(resize) > 1 else resize[0])

                bitrate = params.get("bitrate", "1000k")
                audio_bitrate = params.get("audio_bitrate", "128k")

                clip.write_videofile(
                    str(output_file),
                    codec=codec_map.get(target_format, "libx264"),
                    bitrate=bitrate,
                    audio_bitrate=audio_bitrate,
                    verbose=False,
                    logger=None,
                )
                clip.close()
                return output_file
            except Exception as e:
                logger.warning(f"MoviePy conversion failed, trying ffmpeg: {e}")

        ffmpeg_cmd = self.ffmpeg_path or "ffmpeg"
        cmd = [
            ffmpeg_cmd,
            "-i",
            str(source_path),
            "-y",
        ]

        if params.get("fps"):
            cmd.extend(["-r", str(params["fps"])])

        crf = params.get("crf", 23)
        preset = params.get("preset", "medium")

        if target_format in ["mp4", "mkv", "mov"]:
            cmd.extend(["-c:v", "libx264", "-preset", preset, "-crf", str(crf)])
        elif target_format == "webm":
            cmd.extend(["-c:v", "libvpx-vp9", "-crf", str(crf)])

        cmd.append(str(output_file))

        try:
            subprocess.run(cmd, check=True, capture_output=True)
            return output_file
        except subprocess.CalledProcessError as e:
            raise RuntimeError(f"FFmpeg conversion failed: {e.stderr.decode() if e.stderr else str(e)}")

    def execute_convert(self, task_id: str) -> Tuple[bool, Optional[ConvertResult], str]:
        task = metadata.get_convert_task(task_id)
        if not task:
            return False, None, f"Task not found: {task_id}"

        file_info = metadata.get_file(task.source_file_id)
        if not file_info:
            error = f"Source file not found: {task.source_file_id}"
            metadata.update_file(task.source_file_id, {"status": TaskStatus.FAILED})
            task.task_status = TaskStatus.FAILED
            task.error_message = error
            metadata.save_convert_task(task)
            logger.error(error, task_id=task_id, task_type="convert")
            return False, None, error

        source_path = Path(file_info.storage_path)
        if not source_path.exists():
            error = f"Source file path does not exist: {file_info.storage_path}"
            task.task_status = TaskStatus.FAILED
            task.error_message = error
            metadata.save_convert_task(task)
            logger.error(error, task_id=task_id, file_id=task.source_file_id, task_type="convert")
            return False, None, error

        task.task_status = TaskStatus.PROCESSING
        task.started_at = now_iso()
        metadata.save_convert_task(task)

        logger.info(
            f"Starting conversion: {task_id} ({task.source_format} -> {task.target_format})",
            task_id=task_id,
            file_id=task.source_file_id,
            task_type="convert",
        )

        start_time = time.time()

        try:
            converted_files: List[Path] = []

            if task.source_format == "pdf":
                converted_files = self._convert_pdf_to_images(
                    source_path,
                    task.target_format,
                    task.conversion_params,
                )
            elif task.source_format == "image":
                converted_file = self._convert_image(
                    source_path,
                    task.target_format,
                    task.conversion_params,
                )
                converted_files = [converted_file]
            elif task.source_format == "video":
                converted_file = self._convert_video(
                    source_path,
                    task.target_format,
                    task.conversion_params,
                )
                converted_files = [converted_file]
            else:
                raise ValueError(f"Unsupported source format: {task.source_format}")

            result_file_info: Optional[FileInfo] = None
            result_files: List[FileInfo] = []

            for i, conv_file in enumerate(converted_files):
                if len(converted_files) == 1:
                    result_name = f"{Path(file_info.file_name).stem}.{task.target_format}"
                else:
                    result_name = f"{Path(file_info.file_name).stem}_page_{i + 1}.{task.target_format}"

                stored_file = storage.store_file_from_path(
                    conv_file,
                    target_filename=result_name,
                    upload_user=file_info.upload_user,
                    is_result=True,
                )
                result_files.append(stored_file)

                if i == 0:
                    result_file_info = stored_file

            conversion_time = time.time() - start_time

            if result_file_info:
                task.target_file_id = result_file_info.file_id
                task.task_status = TaskStatus.COMPLETED
                task.completed_at = now_iso()

                result = ConvertResult(
                    result_file_id=result_file_info.file_id,
                    source_file_id=task.source_file_id,
                    result_format=task.target_format,
                    result_size=result_file_info.file_size,
                    result_path=result_file_info.storage_path,
                    conversion_time=conversion_time,
                )

                metadata.save_convert_task(task)

                logger.info(
                    f"Conversion completed: {task_id} in {conversion_time:.2f}s",
                    task_id=task_id,
                    file_id=result_file_info.file_id,
                    task_type="convert",
                )

                return True, result, "Conversion completed"
            else:
                raise RuntimeError("No converted files generated")

        except Exception as e:
            conversion_time = time.time() - start_time
            error_msg = f"Conversion failed after {conversion_time:.2f}s: {str(e)}"

            task.task_status = TaskStatus.FAILED
            task.error_message = error_msg
            task.completed_at = now_iso()
            metadata.save_convert_task(task)

            logger.error(error_msg, task_id=task_id, file_id=task.source_file_id, task_type="convert")
            return False, None, error_msg

    def get_task_status(self, task_id: str) -> Optional[Dict[str, Any]]:
        task = metadata.get_convert_task(task_id)
        if not task:
            return None

        return {
            "task_id": task.task_id,
            "status": task.task_status,
            "source_file_id": task.source_file_id,
            "target_file_id": task.target_file_id,
            "source_format": task.source_format,
            "target_format": task.target_format,
            "created_at": task.created_at,
            "started_at": task.started_at,
            "completed_at": task.completed_at,
            "error_message": task.error_message,
        }


converter = ConverterManager()
