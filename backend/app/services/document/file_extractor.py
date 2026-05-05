import io
import os
from typing import Optional, List
from dataclasses import dataclass

import pdfplumber

from app.core.config import settings


@dataclass
class ExtractedDocument:
    content: str
    file_name: str
    file_size: int
    file_type: str
    page_count: Optional[int] = None


class FileExtractor:
    ALLOWED_EXTENSIONS = {".txt", ".pdf", ".md"}
    
    def __init__(
        self,
        max_file_size: Optional[int] = None,
        allowed_extensions: Optional[List[str]] = None
    ):
        self._max_file_size = max_file_size or settings.MAX_FILE_SIZE
        self._allowed_extensions = set(
            ext.lower() for ext in (allowed_extensions or settings.ALLOWED_FILE_TYPES)
        )

    def validate_file_size(self, file_content: bytes) -> bool:
        return len(file_content) <= self._max_file_size

    def validate_file_type(self, filename: str) -> bool:
        ext = os.path.splitext(filename)[1].lower()
        return ext in self._allowed_extensions

    def get_file_extension(self, filename: str) -> str:
        return os.path.splitext(filename)[1].lower()

    def extract_text_from_txt(self, file_content: bytes) -> str:
        encodings = ['utf-8', 'gbk', 'gb2312', 'gb18030', 'latin-1']
        
        for encoding in encodings:
            try:
                return file_content.decode(encoding)
            except UnicodeDecodeError:
                continue
        
        raise ValueError("无法识别文件编码")

    def extract_text_from_pdf(self, file_content: bytes) -> str:
        text_parts = []
        page_count = 0
        
        try:
            with pdfplumber.open(io.BytesIO(file_content)) as pdf:
                page_count = len(pdf.pages)
                for page_num, page in enumerate(pdf.pages):
                    page_text = page.extract_text()
                    if page_text:
                        text_parts.append(page_text)
        except Exception as e:
            raise ValueError(f"PDF提取失败: {str(e)}")
        
        result = '\n\n'.join(text_parts)
        return result

    def extract_text_from_md(self, file_content: bytes) -> str:
        return self.extract_text_from_txt(file_content)

    def extract(
        self,
        file_content: bytes,
        filename: str
    ) -> ExtractedDocument:
        if not self.validate_file_size(file_content):
            max_mb = self._max_file_size / (1024 * 1024)
            raise ValueError(
                f"文件大小超出限制，最大允许: {max_mb:.1f} MB"
            )

        if not self.validate_file_type(filename):
            allowed = ", ".join(self._allowed_extensions)
            raise ValueError(
                f"不支持的文件类型。允许的类型: {allowed}"
            )

        ext = self.get_file_extension(filename)
        
        extractors = {
            '.txt': self.extract_text_from_txt,
            '.pdf': self.extract_text_from_pdf,
            '.md': self.extract_text_from_md
        }
        
        content = extractors[ext](file_content)
        
        page_count = None
        if ext == '.pdf':
            try:
                with pdfplumber.open(io.BytesIO(file_content)) as pdf:
                    page_count = len(pdf.pages)
            except Exception:
                pass

        return ExtractedDocument(
            content=content,
            file_name=filename,
            file_size=len(file_content),
            file_type=ext.lstrip('.'),
            page_count=page_count
        )
