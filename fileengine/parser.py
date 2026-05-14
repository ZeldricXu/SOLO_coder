import json
import csv
from pathlib import Path
from typing import Optional, Dict, Any, List, Tuple
from io import StringIO, BytesIO

try:
    from pypdf import PdfReader
except ImportError:
    PdfReader = None

try:
    import openpyxl
except ImportError:
    openpyxl = None

try:
    import pandas as pd
except ImportError:
    pd = None

try:
    from PIL import Image
except ImportError:
    Image = None

from .config import settings
from .storage import storage
from .metadata import metadata
from .logger import logger
from .models import ParseResult, TaskStatus


class ParserManager:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def _extract_text_from_txt(self, file_path: Path, params: Dict[str, Any]) -> str:
        encoding = params.get("encoding", "utf-8")
        with open(file_path, "r", encoding=encoding, errors="ignore") as f:
            return f.read()

    def _extract_text_from_pdf(self, file_path: Path, params: Dict[str, Any]) -> str:
        if PdfReader is None:
            raise ImportError("pypdf is not installed. Please install it with: pip install pypdf")

        reader = PdfReader(str(file_path))
        text_parts = []

        page_range = params.get("pages")
        if page_range:
            pages_to_extract = [p for p in page_range if 0 <= p < len(reader.pages)]
        else:
            pages_to_extract = list(range(len(reader.pages)))

        for page_num in pages_to_extract:
            page = reader.pages[page_num]
            page_text = page.extract_text() or ""
            text_parts.append(f"--- Page {page_num + 1} ---\n{page_text}")

        return "\n\n".join(text_parts)

    def _extract_text_from_csv(self, file_path: Path, params: Dict[str, Any]) -> str:
        encoding = params.get("encoding", "utf-8")
        delimiter = params.get("delimiter", ",")

        with open(file_path, "r", encoding=encoding, errors="ignore") as f:
            return f.read()

    def _parse_table_from_csv(self, file_path: Path, params: Dict[str, Any]) -> Dict[str, Any]:
        encoding = params.get("encoding", "utf-8")
        delimiter = params.get("delimiter", ",")
        has_header = params.get("has_header", True)

        rows = []
        headers = []

        with open(file_path, "r", encoding=encoding, errors="ignore") as f:
            reader = csv.reader(f, delimiter=delimiter)
            if has_header:
                headers = next(reader, [])
            for row in reader:
                rows.append(row)

        return {
            "headers": headers,
            "rows": rows,
            "row_count": len(rows),
            "column_count": len(headers) if headers else (len(rows[0]) if rows else 0),
        }

    def _parse_table_from_excel(self, file_path: Path, params: Dict[str, Any]) -> Dict[str, Any]:
        if openpyxl is None and pd is None:
            raise ImportError("openpyxl or pandas is required for Excel parsing")

        sheet_name = params.get("sheet_name", 0)
        has_header = params.get("has_header", True)

        if pd is not None:
            try:
                if has_header:
                    df = pd.read_excel(file_path, sheet_name=sheet_name)
                    headers = df.columns.tolist()
                    rows = df.values.tolist()
                else:
                    df = pd.read_excel(file_path, sheet_name=sheet_name, header=None)
                    headers = []
                    rows = df.values.tolist()

                rows = [[str(cell) if cell is not None else "" for cell in row] for row in rows]

                return {
                    "headers": headers,
                    "rows": rows,
                    "row_count": len(rows),
                    "column_count": len(headers) if headers else (len(rows[0]) if rows else 0),
                    "sheet_name": str(sheet_name),
                }
            except Exception as e:
                logger.warning(f"Pandas Excel parsing failed, trying openpyxl: {e}")

        if openpyxl is not None:
            wb = openpyxl.load_workbook(str(file_path), read_only=True, data_only=True)
            if isinstance(sheet_name, int):
                ws = wb.worksheets[sheet_name]
            else:
                ws = wb[sheet_name]

            rows = []
            headers = []
            for i, row in enumerate(ws.iter_rows(values_only=True)):
                row_data = [str(cell) if cell is not None else "" for cell in row]
                if has_header and i == 0:
                    headers = row_data
                else:
                    rows.append(row_data)

            wb.close()

            return {
                "headers": headers,
                "rows": rows,
                "row_count": len(rows),
                "column_count": len(headers) if headers else (len(rows[0]) if rows else 0),
                "sheet_name": ws.title,
            }

        raise ImportError("No Excel parser available")

    def _parse_table_from_json(self, file_path: Path, params: Dict[str, Any]) -> Dict[str, Any]:
        with open(file_path, "r", encoding="utf-8") as f:
            data = json.load(f)

        if isinstance(data, list):
            if len(data) > 0 and isinstance(data[0], dict):
                headers = list(data[0].keys())
                rows = [[str(item.get(key, "")) for key in headers] for item in data]
            else:
                headers = ["value"]
                rows = [[str(item)] for item in data]
        elif isinstance(data, dict):
            headers = ["key", "value"]
            rows = [[str(k), str(v)] for k, v in data.items()]
        else:
            headers = []
            rows = []

        return {
            "headers": headers,
            "rows": rows,
            "row_count": len(rows),
            "column_count": len(headers),
        }

    def _get_image_info(self, file_path: Path, params: Dict[str, Any]) -> Dict[str, Any]:
        if Image is None:
            raise ImportError("Pillow is required for image parsing")

        with Image.open(str(file_path)) as img:
            return {
                "format": img.format,
                "mode": img.mode,
                "width": img.width,
                "height": img.height,
                "size": (img.width, img.height),
                "info": {k: str(v) for k, v in img.info.items() if isinstance(v, (str, int, float))},
            }

    def _get_file_extension(self, file_path: Path) -> str:
        return file_path.suffix.lower().lstrip(".")

    def parse(
        self,
        file_id: str,
        parse_type: str,
        params: Dict[str, Any] = None,
    ) -> Tuple[bool, Optional[ParseResult], str]:
        file_info = metadata.get_file(file_id)
        if not file_info:
            return False, None, f"File not found: {file_id}"

        file_path = Path(file_info.storage_path)
        if not file_path.exists():
            return False, None, f"File path does not exist: {file_info.storage_path}"

        params = params or {}
        ext = self._get_file_extension(file_path)

        logger.info(
            f"Parsing file: {file_id} (type: {parse_type}, ext: {ext})",
            file_id=file_id,
            task_type="parse",
        )

        try:
            result_data = None

            if parse_type == "text_extract":
                if ext == "txt":
                    result_data = {
                        "content": self._extract_text_from_txt(file_path, params),
                        "encoding": params.get("encoding", "utf-8"),
                    }
                elif ext == "pdf":
                    result_data = {
                        "content": self._extract_text_from_pdf(file_path, params),
                        "pages": params.get("pages"),
                    }
                elif ext == "csv":
                    result_data = {
                        "content": self._extract_text_from_csv(file_path, params),
                        "format": "csv",
                    }
                else:
                    return (
                        False,
                        None,
                        f"Text extraction not supported for file type: {ext}",
                    )

            elif parse_type == "table_extract":
                if ext == "csv":
                    result_data = self._parse_table_from_csv(file_path, params)
                    result_data["format"] = "csv"
                elif ext in ["xlsx", "xls", "xlsm"]:
                    result_data = self._parse_table_from_excel(file_path, params)
                    result_data["format"] = "excel"
                elif ext == "json":
                    result_data = self._parse_table_from_json(file_path, params)
                    result_data["format"] = "json"
                else:
                    return (
                        False,
                        None,
                        f"Table extraction not supported for file type: {ext}",
                    )

            elif parse_type == "image_info":
                if ext in ["jpg", "jpeg", "png", "gif", "webp", "tiff", "bmp"]:
                    result_data = self._get_image_info(file_path, params)
                else:
                    return (
                        False,
                        None,
                        f"Image info not supported for file type: {ext}",
                    )

            elif parse_type == "file_info":
                result_data = {
                    "file_name": file_info.file_name,
                    "file_id": file_info.file_id,
                    "file_type": file_info.file_type,
                    "file_size": file_info.file_size,
                    "storage_path": file_info.storage_path,
                    "upload_time": file_info.upload_time,
                    "expire_at": file_info.expire_at,
                    "sha256": file_info.sha256,
                    "mime_type": file_info.mime_type,
                }

            else:
                return False, None, f"Unsupported parse type: {parse_type}"

            result = ParseResult(
                file_id=file_id,
                parse_type=parse_type,
                parse_result=result_data,
                parse_status=TaskStatus.COMPLETED,
            )

            metadata.save_parse_result(result)

            logger.info(
                f"Parse completed: {result.parse_id}",
                file_id=file_id,
                task_type="parse",
            )

            return True, result, "Parse completed"

        except Exception as e:
            error_msg = f"Parse failed: {str(e)}"

            result = ParseResult(
                file_id=file_id,
                parse_type=parse_type,
                parse_result=None,
                parse_status=TaskStatus.FAILED,
                error_message=error_msg,
            )

            metadata.save_parse_result(result)

            logger.error(error_msg, file_id=file_id, task_type="parse")
            return False, result, error_msg

    def get_parse_result(self, parse_id: str) -> Optional[Dict[str, Any]]:
        result = metadata.get_parse_result(parse_id)
        if not result:
            return None

        return result.model_dump()


parser = ParserManager()
