import uuid
import time
import json
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional, Dict, Any, List, Tuple
from dataclasses import dataclass, field


def generate_test_id(prefix: str = "test") -> str:
    timestamp = int(time.time() * 1000)
    unique = uuid.uuid4().hex[:6]
    return f"{prefix}_{timestamp}_{unique}"


def iso_time(dt: Optional[datetime] = None) -> str:
    if dt is None:
        dt = datetime.utcnow()
    return dt.isoformat() + "Z"


@dataclass
class TestFileInfo:
    file_id: str = field(default_factory=lambda: generate_test_id("file"))
    file_name: str = "test_file.txt"
    file_type: str = "txt"
    file_size: int = 1024
    storage_path: str = ""
    upload_user: str = "test_user"
    upload_time: str = field(default_factory=iso_time)
    status: str = "stored"
    expire_at: str = ""
    sha256: str = ""

    def __post_init__(self):
        if not self.expire_at:
            self.expire_at = iso_time(datetime.utcnow() + timedelta(days=30))


@dataclass
class TestConvertTask:
    task_id: str = field(default_factory=lambda: generate_test_id("task"))
    source_file_id: str = ""
    source_format: str = "pdf"
    target_format: str = "jpg"
    target_file_id: Optional[str] = None
    conversion_params: Dict[str, Any] = field(default_factory=dict)
    task_status: str = "pending"
    created_at: str = field(default_factory=iso_time)
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    error_message: Optional[str] = None
    retry_count: int = 0


@dataclass
class TestConversionParams:
    quality: int = 80
    dpi: int = 300
    max_width: Optional[int] = None
    max_height: Optional[int] = None
    resize: Optional[List[int]] = None
    pages: Optional[List[int]] = None
    fps: Optional[int] = None
    crf: int = 23
    preset: str = "medium"
    bitrate: str = "1000k"

    def to_dict(self) -> Dict[str, Any]:
        result = {}
        if self.quality:
            result["quality"] = self.quality
        if self.dpi:
            result["dpi"] = self.dpi
        if self.max_width:
            result["max_width"] = self.max_width
        if self.max_height:
            result["max_height"] = self.max_height
        if self.resize:
            result["resize"] = self.resize
        if self.pages:
            result["pages"] = self.pages
        if self.fps:
            result["fps"] = self.fps
        if self.crf:
            result["crf"] = self.crf
        if self.preset:
            result["preset"] = self.preset
        if self.bitrate:
            result["bitrate"] = self.bitrate
        return result


class TestDataBuilder:
    def __init__(self, temp_dir: Optional[Path] = None):
        self.temp_dir = temp_dir

    def create_test_file_info(
        self,
        file_name: str = "test.txt",
        file_type: str = "txt",
        file_size: int = 1024,
        upload_user: str = "test_user",
        expire_days: int = 30,
        status: str = "stored",
    ) -> TestFileInfo:
        expire_dt = datetime.utcnow() + timedelta(days=expire_days)
        storage_path = ""
        if self.temp_dir:
            storage_path = str(self.temp_dir / f"{generate_test_id('file')}.{file_type}")

        return TestFileInfo(
            file_name=file_name,
            file_type=file_type,
            file_size=file_size,
            upload_user=upload_user,
            expire_at=iso_time(expire_dt),
            status=status,
            storage_path=storage_path,
        )

    def create_expired_file_info(
        self,
        file_name: str = "expired.txt",
        days_expired: int = 1,
    ) -> TestFileInfo:
        return self.create_test_file_info(
            file_name=file_name,
            expire_days=-days_expired,
            status="stored",
        )

    def create_critical_expire_file_info(
        self,
        file_name: str = "critical.txt",
        seconds_to_expire: int = 0,
    ) -> TestFileInfo:
        expire_dt = datetime.utcnow() + timedelta(seconds=seconds_to_expire)
        return TestFileInfo(
            file_name=file_name,
            expire_at=iso_time(expire_dt),
            status="stored",
        )

    def create_convert_task(
        self,
        source_file_id: str = "",
        source_format: str = "pdf",
        target_format: str = "jpg",
        params: Optional[TestConversionParams] = None,
        status: str = "pending",
    ) -> TestConvertTask:
        conversion_params = params.to_dict() if params else {}
        return TestConvertTask(
            source_file_id=source_file_id,
            source_format=source_format,
            target_format=target_format,
            conversion_params=conversion_params,
            task_status=status,
        )

    def create_conversion_params(
        self,
        quality: int = 80,
        dpi: int = 300,
        max_width: Optional[int] = None,
        max_height: Optional[int] = None,
        resize: Optional[List[int]] = None,
        pages: Optional[List[int]] = None,
        fps: Optional[int] = None,
    ) -> TestConversionParams:
        return TestConversionParams(
            quality=quality,
            dpi=dpi,
            max_width=max_width,
            max_height=max_height,
            resize=resize,
            pages=pages,
            fps=fps,
        )

    def create_image_conversion_params_low_quality(self) -> TestConversionParams:
        return TestConversionParams(quality=30, dpi=72)

    def create_image_conversion_params_high_quality(self) -> TestConversionParams:
        return TestConversionParams(quality=100, dpi=600)

    def create_image_conversion_params_low_resolution(self) -> TestConversionParams:
        return TestConversionParams(max_width=320, max_height=240)

    def create_image_conversion_params_high_resolution(self) -> TestConversionParams:
        return TestConversionParams(max_width=3840, max_height=2160)

    def create_pdf_conversion_params(self, pages: Optional[List[int]] = None) -> TestConversionParams:
        return TestConversionParams(quality=85, dpi=300, pages=pages)

    def create_video_conversion_params(self, fps: int = 30) -> TestConversionParams:
        return TestConversionParams(fps=fps, crf=23, preset="medium", bitrate="1000k")

    def create_chunk_upload_session(
        self,
        file_name: str = "large_file.bin",
        total_size: int = 5 * 1024 * 1024,
        chunk_size: int = 1024 * 1024,
    ) -> Dict[str, Any]:
        total_chunks = (total_size + chunk_size - 1) // chunk_size
        return {
            "session_id": generate_test_id("sess"),
            "file_name": file_name,
            "total_size": total_size,
            "chunk_size": chunk_size,
            "total_chunks": total_chunks,
            "chunks_received": [],
        }

    def create_chunk_data(
        self,
        index: int,
        size: int = 1024 * 1024,
        seed: Optional[int] = None,
    ) -> bytes:
        if seed is None:
            seed = index
        data = bytearray()
        for i in range(size):
            data.append((seed + i) % 256)
        return bytes(data)

    def create_test_image_data(
        self,
        width: int = 100,
        height: int = 100,
        format_type: str = "png",
    ) -> bytes:
        try:
            from PIL import Image
            import io

            img = Image.new("RGB", (width, height), color="red")
            buf = io.BytesIO()
            img.save(buf, format=format_type.upper())
            return buf.getvalue()
        except ImportError:
            header = b"\x89PNG\r\n\x1a\n"
            ihdr = b"".join([
                b"\x00\x00\x00\x0d",
                b"IHDR",
                width.to_bytes(4, "big"),
                height.to_bytes(4, "big"),
                b"\x08\x02\x00\x00\x00",
                b"\x00\x00\x00\x00",
            ])
            idat = b"\x00\x00\x00\x0aIDATx\x9cc\xfc\xff\xff?\x00\x05\xfe\x02\xfe"
            iend = b"\x00\x00\x00\x00IEND\xaeB`\x82"
            return header + ihdr + idat + iend

    def create_test_pdf_data(self) -> bytes:
        pdf_header = b"%PDF-1.4\n"
        pdf_body = b"""
1 0 obj
<< /Type /Catalog /Pages 2 0 R >>
endobj

2 0 obj
<< /Type /Pages /Kids [3 0 R] /Count 1 >>
endobj

3 0 obj
<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>
endobj

xref
0 4
0000000000 65535 f 
0000000010 00000 n 
0000000060 00000 n 
0000000110 00000 n 

trailer
<< /Size 4 /Root 1 0 R >>

startxref
180
%%EOF
"""
        return pdf_header + pdf_body

    def create_batch_files(
        self,
        count: int = 5,
        base_name: str = "batch_file",
        file_type: str = "txt",
        expire_days: int = 30,
    ) -> List[TestFileInfo]:
        files = []
        for i in range(count):
            file_info = self.create_test_file_info(
                file_name=f"{base_name}_{i}.{file_type}",
                file_type=file_type,
                file_size=1024 * (i + 1),
                expire_days=expire_days,
            )
            files.append(file_info)
        return files

    def create_batch_expired_files(
        self,
        count: int = 5,
        expired_days_range: Tuple[int, int] = (1, 10),
    ) -> List[TestFileInfo]:
        files = []
        for i in range(count):
            days_expired = expired_days_range[0] + i % (expired_days_range[1] - expired_days_range[0] + 1)
            file_info = self.create_expired_file_info(
                file_name=f"expired_{i}.txt",
                days_expired=days_expired,
            )
            files.append(file_info)
        return files

    def create_mixed_expiry_files(
        self,
        expired_count: int = 3,
        valid_count: int = 3,
        critical_count: int = 2,
    ) -> List[TestFileInfo]:
        files = []
        files.extend(self.create_batch_expired_files(count=expired_count))
        files.extend(self.create_batch_files(count=valid_count, expire_days=30))

        for i in range(critical_count):
            seconds = -60 if i < critical_count // 2 else 60
            file_info = self.create_critical_expire_file_info(
                file_name=f"critical_{i}.txt",
                seconds_to_expire=seconds,
            )
            files.append(file_info)

        return files

    def create_conversion_scenarios(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": "PDF to JPG basic",
                "source_format": "pdf",
                "target_format": "jpg",
                "params": self.create_conversion_params(quality=80, dpi=300),
                "expected_status": "pending",
            },
            {
                "name": "PDF to PNG high quality",
                "source_format": "pdf",
                "target_format": "png",
                "params": self.create_conversion_params(quality=100, dpi=600),
                "expected_status": "pending",
            },
            {
                "name": "Image resize",
                "source_format": "image",
                "target_format": "jpg",
                "params": self.create_conversion_params(max_width=800, max_height=600),
                "expected_status": "pending",
            },
            {
                "name": "Video re-encode",
                "source_format": "video",
                "target_format": "mp4",
                "params": self.create_conversion_params(fps=24),
                "expected_status": "pending",
            },
        ]

    def create_task_status_transitions(self) -> List[Dict[str, Any]]:
        return [
            {
                "transition": "pending -> processing",
                "initial_status": "pending",
                "action": "start",
                "expected_status": "processing",
            },
            {
                "transition": "processing -> completed",
                "initial_status": "processing",
                "action": "complete",
                "expected_status": "completed",
            },
            {
                "transition": "processing -> failed",
                "initial_status": "processing",
                "action": "fail",
                "expected_status": "failed",
            },
            {
                "transition": "pending -> failed",
                "initial_status": "pending",
                "action": "fail",
                "expected_status": "failed",
            },
            {
                "transition": "failed -> retrying",
                "initial_status": "failed",
                "action": "retry",
                "expected_status": "retrying",
            },
            {
                "transition": "retrying -> processing",
                "initial_status": "retrying",
                "action": "start",
                "expected_status": "processing",
            },
        ]


test_builder = TestDataBuilder()
