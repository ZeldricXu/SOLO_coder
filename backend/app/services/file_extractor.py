import io
from typing import Optional
import pdfplumber
from app.core.config import settings


class FileExtractor:
    @staticmethod
    def extract_text_from_txt(file_content: bytes) -> str:
        try:
            text = file_content.decode('utf-8')
        except UnicodeDecodeError:
            try:
                text = file_content.decode('gbk')
            except UnicodeDecodeError:
                text = file_content.decode('latin-1')
        return text

    @staticmethod
    def extract_text_from_pdf(file_content: bytes) -> str:
        text_parts = []
        try:
            with pdfplumber.open(io.BytesIO(file_content)) as pdf:
                for page_num, page in enumerate(pdf.pages):
                    page_text = page.extract_text()
                    if page_text:
                        text_parts.append(page_text)
        except Exception as e:
            raise ValueError(f"PDF提取失败: {str(e)}")
        
        return '\n\n'.join(text_parts)

    @staticmethod
    def extract_text_from_md(file_content: bytes) -> str:
        return FileExtractor.extract_text_from_txt(file_content)

    @classmethod
    def extract(
        cls,
        file_content: bytes,
        file_extension: str
    ) -> str:
        file_extension = file_extension.lower()
        
        extractors = {
            '.txt': cls.extract_text_from_txt,
            '.pdf': cls.extract_text_from_pdf,
            '.md': cls.extract_text_from_md
        }
        
        if file_extension not in extractors:
            raise ValueError(f"不支持的文件类型: {file_extension}")
        
        return extractors[file_extension](file_content)

    @staticmethod
    def validate_file_size(file_content: bytes) -> bool:
        if len(file_content) > settings.MAX_FILE_SIZE:
            return False
        return True

    @staticmethod
    def validate_file_type(filename: str) -> bool:
        import os
        ext = os.path.splitext(filename)[1].lower()
        return ext in settings.ALLOWED_FILE_TYPES
