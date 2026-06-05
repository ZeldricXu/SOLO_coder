import os
import json
import tempfile
from typing import List, Dict, Any, Optional, Tuple
from dataclasses import dataclass
import numpy as np

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.document import StandardizedDocument, PageInfo
from app.schemas.common import BoundingBox, TableData, TableCellSchema
from app.services.storage import StorageService
from app.core.database import get_sync_db
from app.models.table import TableStructure, TableCell

logger = get_logger(__name__)
settings = get_settings()


@dataclass
class DetectedTable:
    table_id: str
    page_number: int
    bbox: BoundingBox
    confidence: float
    row_count: int = 0
    col_count: int = 0
    cells: List[TableCellSchema] = None
    has_header: bool = False
    has_merged_cells: bool = False
    raw_data: Dict[str, Any] = None

    def __post_init__(self):
        if self.cells is None:
            self.cells = []
        if self.raw_data is None:
            self.raw_data = {}


class TableExtractor:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        if self._initialized:
            return
        self._initialized = True
        self.storage_service = StorageService()
        self.detection_model = None
        self.structure_model = None
        self._initialize_models()

    def _initialize_models(self):
        try:
            cache_key = f"table_model_{settings.TABLE_MODEL_NAME}_{settings.TABLE_MODEL_VERSION}"
            cached_model = self.storage_service.cache_get(cache_key)

            if cached_model:
                logger.info("Using cached table extraction models")
                self.detection_model = cached_model
            else:
                logger.info(f"Initializing table extraction model: {settings.TABLE_MODEL_NAME}")
                try:
                    from transformers import (
                        DetrFeatureExtractor,
                        TableTransformerForObjectDetection,
                    )

                    self.detection_model = {
                        "name": settings.TABLE_MODEL_NAME,
                        "version": settings.TABLE_MODEL_VERSION,
                        "type": "table_transformer",
                    }

                    self.storage_service.cache_set(cache_key, self.detection_model, ttl=86400)
                except ImportError:
                    logger.warning("Transformers not available, using heuristic table extraction")
                    self.detection_model = {
                        "name": "heuristic_table",
                        "version": "1.0.0",
                        "type": "heuristic",
                    }

            self.structure_model = self.detection_model
            logger.info("Table extractor initialized successfully")
        except Exception as e:
            logger.error(f"Failed to initialize table extractor: {e}")
            self.detection_model = {
                "name": "heuristic_table",
                "version": "1.0.0",
                "type": "heuristic",
            }
            self.structure_model = self.detection_model

    def extract_tables(
        self,
        document_id: int,
        standardized_doc: StandardizedDocument,
        layout_result: Optional[Dict[str, Any]] = None,
    ) -> List[Dict[str, Any]]:
        logger.info(f"Extracting tables for document: {document_id}")

        all_tables: List[DetectedTable] = []

        for page_info in standardized_doc.pages:
            page_tables = self._extract_page_tables(page_info, layout_result)
            all_tables.extend(page_tables)

        structured_tables = []
        for table_idx, table in enumerate(all_tables):
            structured_table = self._structure_table(table, page_info)
            self._save_table_to_db(document_id, structured_table)
            structured_tables.append(self._table_to_dict(structured_table))

        self._save_tables_result(document_id, structured_tables)

        logger.info(f"Table extraction complete: {len(all_tables)} tables detected")
        return structured_tables

    def _extract_page_tables(
        self,
        page_info: PageInfo,
        layout_result: Optional[Dict[str, Any]] = None,
    ) -> List[DetectedTable]:
        tables: List[DetectedTable] = []

        if page_info.tables:
            for table_idx, table_data in enumerate(page_info.tables):
                table = self._convert_pdf_table(page_info, table_data, table_idx)
                tables.append(table)

        if layout_result and "regions" in layout_result:
            table_regions = [
                r for r in layout_result["regions"]
                if r.get("region_type") == "table" and r.get("page_number") == page_info.page_number
            ]
            for region_idx, region in enumerate(table_regions):
                existing_match = any(
                    abs(t.bbox.x1 - region["bbox"]["x1"]) < 50
                    and abs(t.bbox.y1 - region["bbox"]["y1"]) < 50
                    for t in tables
                )
                if not existing_match:
                    table = self._extract_from_layout_region(page_info, region, region_idx)
                    tables.append(table)

        if not tables:
            heuristic_tables = self._heuristic_table_detection(page_info)
            tables.extend(heuristic_tables)

        return tables

    def _convert_pdf_table(
        self,
        page_info: PageInfo,
        table_data: TableData,
        table_idx: int,
    ) -> DetectedTable:
        cells: List[TableCellSchema] = []
        all_text = []

        if table_data.headers:
            for col_idx, header in enumerate(table_data.headers):
                cell = TableCellSchema(
                    row_index=0,
                    col_index=col_idx,
                    text=header or "",
                    is_header=True,
                    bbox=BoundingBox(
                        x1=50 + col_idx * 100,
                        y1=50,
                        x2=150 + col_idx * 100,
                        y2=80,
                    ),
                    confidence=0.9,
                )
                cells.append(cell)
                all_text.append(header or "")

        for row_idx, row in enumerate(table_data.rows):
            for col_idx, cell_text in enumerate(row):
                cell = TableCellSchema(
                    row_index=row_idx + 1,
                    col_index=col_idx,
                    text=cell_text or "",
                    is_header=False,
                    bbox=BoundingBox(
                        x1=50 + col_idx * 100,
                        y1=80 + row_idx * 30,
                        x2=150 + col_idx * 100,
                        y2=110 + row_idx * 30,
                    ),
                    confidence=0.9,
                )
                cells.append(cell)
                all_text.append(cell_text or "")

        bbox = BoundingBox(x1=50, y1=50, x2=page_info.width - 50, y2=page_info.height - 50)
        if cells:
            all_x1 = min(c.bbox.x1 for c in cells)
            all_y1 = min(c.bbox.y1 for c in cells)
            all_x2 = max(c.bbox.x2 for c in cells)
            all_y2 = max(c.bbox.y2 for c in cells)
            bbox = BoundingBox(x1=all_x1, y1=all_y1, x2=all_x2, y2=all_y2)

        detected_table = DetectedTable(
            table_id=f"page{page_info.page_number}_table_{table_idx}",
            page_number=page_info.page_number,
            bbox=bbox,
            confidence=0.9,
            row_count=table_data.row_count,
            col_count=table_data.col_count,
            cells=cells,
            has_header=len(table_data.headers) > 0,
            raw_data={
                "source": "pymupdf",
                "headers": table_data.headers,
                "rows": table_data.rows,
            },
        )

        return detected_table

    def _extract_from_layout_region(
        self,
        page_info: PageInfo,
        region: Dict[str, Any],
        region_idx: int,
    ) -> DetectedTable:
        bbox = BoundingBox(**region["bbox"])

        region_blocks = [
            tb for tb in page_info.text_blocks
            if self._is_block_in_bbox(tb, bbox)
        ]

        sorted_blocks = sorted(
            region_blocks,
            key=lambda b: (b.bbox.y1, b.bbox.x1),
        )

        rows = self._group_blocks_into_rows(sorted_blocks)
        cells: List[TableCellSchema] = []

        for row_idx, row_blocks in enumerate(rows):
            for col_idx, block in enumerate(row_blocks):
                cell = TableCellSchema(
                    row_index=row_idx,
                    col_index=col_idx,
                    text=block.text,
                    is_header=row_idx == 0,
                    bbox=block.bbox,
                    confidence=block.confidence or 0.7,
                )
                cells.append(cell)

        return DetectedTable(
            table_id=f"page{page_info.page_number}_table_{region_idx}",
            page_number=page_info.page_number,
            bbox=bbox,
            confidence=region.get("confidence", 0.7),
            row_count=len(rows),
            col_count=max(len(r) for r in rows) if rows else 0,
            cells=cells,
            has_header=len(rows) > 0,
            raw_data={"source": "layout_analysis"},
        )

    def _heuristic_table_detection(self, page_info: PageInfo) -> List[DetectedTable]:
        tables: List[DetectedTable] = []

        if not page_info.text_blocks:
            return tables

        aligned_blocks = self._find_aligned_blocks(page_info.text_blocks)

        for group_idx, group in enumerate(aligned_blocks):
            if len(group) < 3:
                continue

            rows = self._group_blocks_into_rows(group)
            if len(rows) < 2 or max(len(r) for r in rows) < 2:
                continue

            cells: List[TableCellSchema] = []
            for row_idx, row_blocks in enumerate(rows):
                for col_idx, block in enumerate(row_blocks):
                    cell = TableCellSchema(
                        row_index=row_idx,
                        col_index=col_idx,
                        text=block.text,
                        is_header=row_idx == 0,
                        bbox=block.bbox,
                        confidence=block.confidence or 0.6,
                    )
                    cells.append(cell)

            all_x1 = min(b.bbox.x1 for b in group)
            all_y1 = min(b.bbox.y1 for b in group)
            all_x2 = max(b.bbox.x2 for b in group)
            all_y2 = max(b.bbox.y2 for b in group)

            table = DetectedTable(
                table_id=f"page{page_info.page_number}_table_{group_idx}",
                page_number=page_info.page_number,
                bbox=BoundingBox(x1=all_x1, y1=all_y1, x2=all_x2, y2=all_y2),
                confidence=0.6,
                row_count=len(rows),
                col_count=max(len(r) for r in rows),
                cells=cells,
                has_header=len(rows) > 0,
                raw_data={"source": "heuristic"},
            )
            tables.append(table)

        return tables

    def _is_block_in_bbox(self, block, bbox: BoundingBox, tolerance: int = 10) -> bool:
        return (
            block.bbox.x1 >= bbox.x1 - tolerance
            and block.bbox.y1 >= bbox.y1 - tolerance
            and block.bbox.x2 <= bbox.x2 + tolerance
            and block.bbox.y2 <= bbox.y2 + tolerance
        )

    def _find_aligned_blocks(self, blocks: List) -> List[List]:
        x_positions = {}
        for block in blocks:
            x_center = (block.bbox.x1 + block.bbox.x2) / 2
            rounded_x = round(x_center / 20) * 20
            if rounded_x not in x_positions:
                x_positions[rounded_x] = []
            x_positions[rounded_x].append(block)

        groups = []
        used_blocks = set()

        for x_pos, pos_blocks in sorted(x_positions.items()):
            if len(pos_blocks) >= 2:
                nearby_x = [x for x in x_positions.keys() if abs(x - x_pos) <= 40]
                all_nearby_blocks = []
                for x in nearby_x:
                    all_nearby_blocks.extend(x_positions[x])

                unique_blocks = list({b for b in all_nearby_blocks if id(b) not in used_blocks})
                if len(unique_blocks) >= 3:
                    groups.append(unique_blocks)
                    used_blocks.update(id(b) for b in unique_blocks)

        return groups

    def _group_blocks_into_rows(self, blocks: List, row_tolerance: int = 15) -> List[List]:
        if not blocks:
            return []

        sorted_blocks = sorted(blocks, key=lambda b: (b.bbox.y1, b.bbox.x1))
        rows = []
        current_row = [sorted_blocks[0]]
        current_y = (sorted_blocks[0].bbox.y1 + sorted_blocks[0].bbox.y2) / 2

        for block in sorted_blocks[1:]:
            block_y = (block.bbox.y1 + block.bbox.y2) / 2
            if abs(block_y - current_y) <= row_tolerance:
                current_row.append(block)
            else:
                rows.append(sorted(current_row, key=lambda b: b.bbox.x1))
                current_row = [block]
                current_y = block_y

        if current_row:
            rows.append(sorted(current_row, key=lambda b: b.bbox.x1))

        return rows

    def _structure_table(self, detected_table: DetectedTable, page_info: PageInfo) -> DetectedTable:
        if not detected_table.cells:
            return detected_table

        max_row = max(c.row_index for c in detected_table.cells)
        max_col = max(c.col_index for c in detected_table.cells)

        grid = [[None for _ in range(max_col + 1)] for _ in range(max_row + 1)]

        for cell in detected_table.cells:
            if 0 <= cell.row_index <= max_row and 0 <= cell.col_index <= max_col:
                grid[cell.row_index][cell.col_index] = cell

        for row_idx in range(max_row + 1):
            for col_idx in range(max_col + 1):
                if grid[row_idx][col_idx] is None:
                    merged = self._detect_merged_cell(grid, row_idx, col_idx, max_row, max_col)
                    if merged:
                        grid[row_idx][col_idx] = merged
                        detected_table.has_merged_cells = True

        detected_table.cells = [cell for row in grid for cell in row if cell is not None]
        return detected_table

    def _detect_merged_cell(
        self,
        grid: List[List],
        row_idx: int,
        col_idx: int,
        max_row: int,
        max_col: int,
    ) -> Optional[TableCellSchema]:
        row_span = 1
        col_span = 1

        while (
            col_idx + col_span <= max_col
            and grid[row_idx][col_idx + col_span] is None
        ):
            col_span += 1

        while (
            row_idx + row_span <= max_row
            and all(
                grid[row_idx + row_span][c] is None
                for c in range(col_idx, col_idx + col_span)
            )
        ):
            row_span += 1

        if row_span > 1 or col_span > 1:
            return TableCellSchema(
                row_index=row_idx,
                col_index=col_idx,
                row_span=row_span,
                col_span=col_span,
                text="",
                is_header=row_idx == 0,
                is_merged=True,
                confidence=0.5,
            )
        return None

    def _save_table_to_db(self, document_id: int, detected_table: DetectedTable) -> int:
        db = next(get_sync_db())
        try:
            table_struct = TableStructure(
                document_id=document_id,
                page_number=detected_table.page_number,
                table_id=detected_table.table_id,
                bounding_box=detected_table.bbox.model_dump(),
                confidence=detected_table.confidence,
                row_count=detected_table.row_count,
                col_count=detected_table.col_count,
                has_header=detected_table.has_header,
                has_merged_cells=detected_table.has_merged_cells,
                raw_detection=detected_table.raw_data,
                structure_json=self._table_to_dict(detected_table),
            )
            db.add(table_struct)
            db.flush()

            for cell in detected_table.cells:
                cell_record = TableCell(
                    table_id=table_struct.id,
                    row_index=cell.row_index,
                    col_index=cell.col_index,
                    row_span=cell.row_span,
                    col_span=cell.col_span,
                    is_header=cell.is_header,
                    is_merged=cell.is_merged,
                    text=cell.text,
                    confidence=cell.confidence,
                    bounding_box=cell.bbox.model_dump() if cell.bbox else None,
                )
                db.add(cell_record)

            db.commit()
            return table_struct.id
        except Exception as e:
            logger.error(f"Failed to save table to DB: {e}")
            db.rollback()
            raise
        finally:
            db.close()

    def _table_to_dict(self, detected_table: DetectedTable) -> Dict[str, Any]:
        return {
            "table_id": detected_table.table_id,
            "page_number": detected_table.page_number,
            "bbox": detected_table.bbox.model_dump(),
            "confidence": detected_table.confidence,
            "row_count": detected_table.row_count,
            "col_count": detected_table.col_count,
            "has_header": detected_table.has_header,
            "has_merged_cells": detected_table.has_merged_cells,
            "cells": [
                {
                    "row_index": c.row_index,
                    "col_index": c.col_index,
                    "row_span": c.row_span,
                    "col_span": c.col_span,
                    "text": c.text,
                    "is_header": c.is_header,
                    "is_merged": c.is_merged,
                    "confidence": c.confidence,
                    "bbox": c.bbox.model_dump() if c.bbox else None,
                }
                for c in detected_table.cells
            ],
            "html": self._table_to_html(detected_table),
            "json_data": self._table_to_json(detected_table),
        }

    def _table_to_html(self, detected_table: DetectedTable) -> str:
        if not detected_table.cells:
            return "<table></table>"

        max_row = max(c.row_index for c in detected_table.cells)
        max_col = max(c.col_index for c in detected_table.cells)

        grid = [["" for _ in range(max_col + 1)] for _ in range(max_row + 1)]
        for cell in detected_table.cells:
            if cell.row_span > 1 or cell.col_span > 1:
                continue
            grid[cell.row_index][cell.col_index] = cell.text

        html = ["<table border='1'>"]
        for row_idx, row in enumerate(grid):
            html.append("<tr>")
            for col_idx, cell_text in enumerate(row):
                tag = "th" if row_idx == 0 and detected_table.has_header else "td"
                html.append(f"<{tag}>{cell_text}</{tag}>")
            html.append("</tr>")
        html.append("</table>")

        return "".join(html)

    def _table_to_json(self, detected_table: DetectedTable) -> Dict[str, Any]:
        if not detected_table.cells:
            return {"headers": [], "rows": []}

        cells_by_pos = {(c.row_index, c.col_index): c for c in detected_table.cells}
        max_row = max(c.row_index for c in detected_table.cells)
        max_col = max(c.col_index for c in detected_table.cells)

        headers = []
        rows = []

        for row_idx in range(max_row + 1):
            row_data = []
            for col_idx in range(max_col + 1):
                cell = cells_by_pos.get((row_idx, col_idx))
                text = cell.text if cell else ""
                if row_idx == 0 and detected_table.has_header:
                    headers.append(text)
                else:
                    row_data.append(text)
            if row_idx > 0 or not detected_table.has_header:
                rows.append(row_data)

        return {"headers": headers, "rows": rows}

    def _save_tables_result(self, document_id: int, tables: List[Dict[str, Any]]) -> None:
        try:
            object_name = f"processed/{document_id}/tables.json"
            data = json.dumps(tables, ensure_ascii=False, indent=2).encode("utf-8")
            self.storage_service.upload_bytes(
                bucket_name=settings.MINIO_PROCESSED_BUCKET,
                object_name=object_name,
                data=data,
            )
            logger.debug(f"Saved tables result for document {document_id}")
        except Exception as e:
            logger.warning(f"Failed to save tables result: {e}")

    def get_tables_result(self, document_id: int) -> Optional[List[Dict[str, Any]]]:
        try:
            object_name = f"processed/{document_id}/tables.json"
            data = self.storage_service.download_file_bytes(
                bucket_name=settings.MINIO_PROCESSED_BUCKET,
                object_name=object_name,
            )
            return json.loads(data.decode("utf-8"))
        except Exception as e:
            logger.debug(f"Tables result not found for document {document_id}: {e}")
            return None
