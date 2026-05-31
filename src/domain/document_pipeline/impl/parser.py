from typing import Optional, Dict, Any, List
from ..models import Document, DocumentFormat
from ..interfaces import DocumentParserPort
from src.core import generate_id, ValidationError
import logging

logger = logging.getLogger(__name__)


class DocumentParser(DocumentParserPort):
    def __init__(self):
        self._format_handlers = {
            DocumentFormat.TXT: self._parse_text,
            DocumentFormat.MD: self._parse_text,
            DocumentFormat.JSON: self._parse_json,
            DocumentFormat.CSV: self._parse_csv,
            DocumentFormat.HTML: self._parse_html,
            DocumentFormat.PDF: self._parse_pdf,
            DocumentFormat.WORD: self._parse_word,
            DocumentFormat.EXCEL: self._parse_excel,
            DocumentFormat.PPT: self._parse_ppt,
        }

    async def parse(self, document: Document) -> str:
        handler = self._format_handlers.get(document.format)
        if not handler:
            raise ValidationError(f"不支持的文档格式: {document.format}")

        try:
            return await handler(document.content)
        except ValidationError:
            raise
        except Exception as e:
            logger.error(f"文档解析失败 [{document.format}]: {e}")
            raise

    async def _parse_text(self, content: bytes) -> str:
        try:
            return content.decode("utf-8")
        except UnicodeDecodeError:
            return content.decode("utf-8", errors="replace")

    async def _parse_json(self, content: bytes) -> str:
        import json

        try:
            data = json.loads(content)
            return json.dumps(data, ensure_ascii=False, indent=2)
        except Exception:
            return content.decode("utf-8", errors="replace")

    async def _parse_csv(self, content: bytes) -> str:
        import csv
        import io

        try:
            text = content.decode("utf-8")
            reader = csv.reader(io.StringIO(text))
            rows = [row for row in reader]
            return "\n".join([",".join(row) for row in rows])
        except Exception:
            return content.decode("utf-8", errors="replace")

    async def _parse_html(self, content: bytes) -> str:
        try:
            import re

            text = content.decode("utf-8", errors="replace")
            text = re.sub(r"<script[^>]*>.*?</script>", "", text, flags=re.DOTALL)
            text = re.sub(r"<style[^>]*>.*?</style>", "", text, flags=re.DOTALL)
            text = re.sub(r"<[^>]+>", "", text)
            text = re.sub(r"\s+", " ", text).strip()
            return text
        except Exception:
            return content.decode("utf-8", errors="replace")

    async def _parse_pdf(self, content: bytes) -> str:
        return f"[PDF文档解析模拟] 内容长度: {len(content)} bytes"

    async def _parse_word(self, content: bytes) -> str:
        return f"[Word文档解析模拟] 内容长度: {len(content)} bytes"

    async def _parse_excel(self, content: bytes) -> str:
        return f"[Excel文档解析模拟] 内容长度: {len(content)} bytes"

    async def _parse_ppt(self, content: bytes) -> str:
        return f"[PPT文档解析模拟] 内容长度: {len(content)} bytes"

    def get_supported_formats(self) -> List[DocumentFormat]:
        return list(self._format_handlers.keys())
