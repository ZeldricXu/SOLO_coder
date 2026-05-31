from typing import Dict, Any
from .types import Document, DocumentFormat
from src.core import ValidationError, generate_id
import logging

logger = logging.getLogger(__name__)


class DocumentParser:
    def __init__(self):
        self._parsers = {
            DocumentFormat.TXT: self._parse_txt,
            DocumentFormat.MD: self._parse_md,
            DocumentFormat.HTML: self._parse_html,
            DocumentFormat.JSON: self._parse_json,
            DocumentFormat.CSV: self._parse_csv,
            DocumentFormat.PDF: self._parse_pdf,
            DocumentFormat.WORD: self._parse_word,
            DocumentFormat.EXCEL: self._parse_excel,
            DocumentFormat.PPT: self._parse_ppt,
        }

    async def parse(self, doc: Document) -> str:
        logger.info(f"Parsing document {doc.document_id}, format={doc.format}")
        parser = self._parsers.get(doc.format)
        if not parser:
            raise ValidationError(f"Unsupported document format: {doc.format}")
        return await parser(doc)

    async def _parse_txt(self, doc: Document) -> str:
        try:
            return doc.content.decode("utf-8")
        except UnicodeDecodeError:
            return doc.content.decode("utf-8", errors="ignore")

    async def _parse_md(self, doc: Document) -> str:
        return await self._parse_txt(doc)

    async def _parse_html(self, doc: Document) -> str:
        text = await self._parse_txt(doc)
        import re
        text = re.sub(r"<[^>]+>", "", text)
        text = re.sub(r"\s+", " ", text)
        return text.strip()

    async def _parse_json(self, doc: Document) -> str:
        import json
        data = json.loads(doc.content)
        return json.dumps(data, ensure_ascii=False)

    async def _parse_csv(self, doc: Document) -> str:
        return await self._parse_txt(doc)

    async def _parse_pdf(self, doc: Document) -> str:
        logger.warning(f"PDF parsing for {doc.document_id} using fallback")
        return f"[PDF Document {doc.document_id}] - " + await self._parse_txt(doc)

    async def _parse_word(self, doc: Document) -> str:
        logger.warning(f"Word parsing for {doc.document_id} using fallback")
        return f"[Word Document {doc.document_id}] - " + await self._parse_txt(doc)

    async def _parse_excel(self, doc: Document) -> str:
        logger.warning(f"Excel parsing for {doc.document_id} using fallback")
        return f"[Excel Document {doc.document_id}] - " + await self._parse_txt(doc)

    async def _parse_ppt(self, doc: Document) -> str:
        logger.warning(f"PPT parsing for {doc.document_id} using fallback")
        return f"[PPT Document {doc.document_id}] - " + await self._parse_txt(doc)
