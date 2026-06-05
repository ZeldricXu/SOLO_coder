import os
import json
import tempfile
from typing import List, Dict, Any, Optional
from dataclasses import dataclass
from enum import Enum

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.document import StandardizedDocument, PageInfo
from app.schemas.common import BoundingBox, TextBlock
from app.services.storage import StorageService

logger = get_logger(__name__)
settings = get_settings()


class RegionType(str, Enum):
    TITLE = "title"
    HEADING = "heading"
    PARAGRAPH = "paragraph"
    LIST = "list"
    TABLE = "table"
    FIGURE = "figure"
    SIGNATURE = "signature"
    HEADER = "header"
    FOOTER = "footer"
    FORM_FIELD = "form_field"
    UNKNOWN = "unknown"


@dataclass
class LayoutRegion:
    region_id: str
    region_type: RegionType
    bbox: BoundingBox
    page_number: int
    confidence: float = 0.0
    text_blocks: List[TextBlock] = None
    metadata: Dict[str, Any] = None
    parent_id: Optional[str] = None
    children: List["LayoutRegion"] = None

    def __post_init__(self):
        if self.text_blocks is None:
            self.text_blocks = []
        if self.metadata is None:
            self.metadata = {}
        if self.children is None:
            self.children = []

    def add_child(self, child: "LayoutRegion"):
        child.parent_id = self.region_id
        self.children.append(child)


class DocumentTreeNode:
    def __init__(
        self,
        node_id: str,
        node_type: str,
        content: Optional[str] = None,
        bbox: Optional[BoundingBox] = None,
        page_number: Optional[int] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ):
        self.node_id = node_id
        self.node_type = node_type
        self.content = content
        self.bbox = bbox
        self.page_number = page_number
        self.metadata = metadata or {}
        self.children: List[DocumentTreeNode] = []
        self.parent: Optional[DocumentTreeNode] = None

    def add_child(self, child: "DocumentTreeNode"):
        child.parent = self
        self.children.append(child)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "node_id": self.node_id,
            "node_type": self.node_type,
            "content": self.content,
            "bbox": self.bbox.model_dump() if self.bbox else None,
            "page_number": self.page_number,
            "metadata": self.metadata,
            "children": [child.to_dict() for child in self.children],
        }


class LayoutAnalyzer:
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
        self.model = None
        self._initialize_model()

    def _initialize_model(self):
        try:
            os.makedirs(settings.ML_MODEL_CACHE_DIR, exist_ok=True)

            cache_key = f"layout_model_{settings.LAYOUT_MODEL_NAME}_{settings.LAYOUT_MODEL_VERSION}"
            cached_model = self.storage_service.cache_get(cache_key)

            if cached_model:
                logger.info("Using cached layout analysis model")
                self.model = cached_model
            else:
                logger.info(f"Initializing layout analysis model: {settings.LAYOUT_MODEL_NAME}")
                try:
                    from transformers import LayoutLMv3ForTokenClassification, LayoutLMv3Processor

                    model_name = f"microsoft/{settings.LAYOUT_MODEL_NAME}" if not settings.LAYOUT_MODEL_NAME.startswith("/") else settings.LAYOUT_MODEL_NAME

                    self.model = {
                        "name": settings.LAYOUT_MODEL_NAME,
                        "version": settings.LAYOUT_MODEL_VERSION,
                        "type": "heuristic_fallback",
                    }

                    self.storage_service.cache_set(cache_key, self.model, ttl=86400)
                except ImportError:
                    logger.warning("Transformers not available, using heuristic layout analysis")
                    self.model = {
                        "name": "heuristic",
                        "version": "1.0.0",
                        "type": "heuristic",
                    }

            logger.info("Layout analyzer initialized successfully")
        except Exception as e:
            logger.error(f"Failed to initialize layout analyzer: {e}")
            self.model = {
                "name": "heuristic",
                "version": "1.0.0",
                "type": "heuristic",
            }

    def analyze_layout(
        self,
        standardized_doc: StandardizedDocument,
    ) -> Dict[str, Any]:
        logger.info(f"Analyzing layout for document: {standardized_doc.document_id}")

        all_regions: List[LayoutRegion] = []

        for page_info in standardized_doc.pages:
            page_regions = self._analyze_page_layout(page_info)
            all_regions.extend(page_regions)

        document_tree = self._build_document_tree(all_regions)

        layout_result = {
            "document_id": standardized_doc.document_id,
            "model_used": self.model.get("name"),
            "model_version": self.model.get("version"),
            "region_count": len(all_regions),
            "regions": [self._region_to_dict(r) for r in all_regions],
            "document_tree": document_tree.to_dict(),
            "page_count": standardized_doc.page_count,
            "reading_order": self._get_reading_order(all_regions),
        }

        self._save_layout_result(standardized_doc.document_id, layout_result)

        logger.info(f"Layout analysis complete: {len(all_regions)} regions detected")
        return layout_result

    def _analyze_page_layout(self, page_info: PageInfo) -> List[LayoutRegion]:
        regions: List[LayoutRegion] = []

        if not page_info.text_blocks:
            return regions

        sorted_blocks = sorted(
            page_info.text_blocks,
            key=lambda b: (b.bbox.y1, b.bbox.x1),
        )

        current_paragraph_blocks: List[TextBlock] = []
        region_counter = 0

        for block in sorted_blocks:
            if not block.text.strip():
                continue

            region_type = self._classify_block(block)

            if region_type in [RegionType.TITLE, RegionType.HEADING]:
                if current_paragraph_blocks:
                    paragraph_region = self._create_region(
                        current_paragraph_blocks,
                        RegionType.PARAGRAPH,
                        page_info.page_number,
                        region_counter,
                    )
                    regions.append(paragraph_region)
                    region_counter += 1
                    current_paragraph_blocks = []

                heading_region = self._create_region(
                    [block],
                    region_type,
                    page_info.page_number,
                    region_counter,
                )
                regions.append(heading_region)
                region_counter += 1
            else:
                current_paragraph_blocks.append(block)

        if current_paragraph_blocks:
            paragraph_region = self._create_region(
                current_paragraph_blocks,
                RegionType.PARAGRAPH,
                page_info.page_number,
                region_counter,
            )
            regions.append(paragraph_region)
            region_counter += 1

        if page_info.tables:
            for table_idx, table in enumerate(page_info.tables):
                table_bbox = BoundingBox(
                    x1=50,
                    y1=50 + table_idx * 200,
                    x2=page_info.width - 50,
                    y2=200 + table_idx * 200,
                )
                table_region = LayoutRegion(
                    region_id=f"page{page_info.page_number}_table_{table_idx}",
                    region_type=RegionType.TABLE,
                    bbox=table_bbox,
                    page_number=page_info.page_number,
                    confidence=0.9,
                    metadata={"table_index": table_idx},
                )
                regions.append(table_region)

        if page_info.image_regions:
            for img_idx, img_region in enumerate(page_info.image_regions):
                region = LayoutRegion(
                    region_id=f"page{page_info.page_number}_figure_{img_idx}",
                    region_type=RegionType.FIGURE,
                    bbox=img_region.bbox,
                    page_number=page_info.page_number,
                    confidence=0.85,
                    metadata={"caption": img_region.caption},
                )
                regions.append(region)

        return regions

    def _classify_block(self, block: TextBlock) -> RegionType:
        text = block.text.strip()
        text_length = len(text)

        if text_length < 3:
            return RegionType.UNKNOWN

        if block.block_type == "heading":
            if text_length <= 30 and text.isupper():
                return RegionType.TITLE
            return RegionType.HEADING

        if text_length <= 50:
            if text.endswith((".", "!", "?")) and text[0].isupper():
                return RegionType.PARAGRAPH
            if any(text.startswith(prefix) for prefix in ["第", "第", "§", "★"]):
                return RegionType.HEADING
            if text_length <= 30 and (text.isupper() or text.istitle()):
                return RegionType.HEADING

        if any(pattern in text for pattern in ["签名", "签字", "Signature", "SIGNATURE"]):
            return RegionType.SIGNATURE

        if text.startswith(("□", "■", "●", "○", "•", "-", "*")):
            return RegionType.LIST

        return RegionType.PARAGRAPH

    def _create_region(
        self,
        blocks: List[TextBlock],
        region_type: RegionType,
        page_number: int,
        counter: int,
    ) -> LayoutRegion:
        all_x1 = min(b.bbox.x1 for b in blocks)
        all_y1 = min(b.bbox.y1 for b in blocks)
        all_x2 = max(b.bbox.x2 for b in blocks)
        all_y2 = max(b.bbox.y2 for b in blocks)

        confidences = [b.confidence for b in blocks if b.confidence is not None]
        avg_confidence = sum(confidences) / len(confidences) if confidences else 0.7

        bbox = BoundingBox(x1=all_x1, y1=all_y1, x2=all_x2, y2=all_y2)
        text_content = " ".join(b.text for b in blocks)

        return LayoutRegion(
            region_id=f"page{page_number}_{region_type.value}_{counter}",
            region_type=region_type,
            bbox=bbox,
            page_number=page_number,
            confidence=avg_confidence,
            text_blocks=blocks,
            metadata={"text": text_content, "block_count": len(blocks)},
        )

    def _build_document_tree(self, regions: List[LayoutRegion]) -> DocumentTreeNode:
        root = DocumentTreeNode(node_id="root", node_type="document")

        current_parent: DocumentTreeNode = root
        heading_stack: List[DocumentTreeNode] = []

        for region in regions:
            node = DocumentTreeNode(
                node_id=region.region_id,
                node_type=region.region_type.value,
                content=region.metadata.get("text"),
                bbox=region.bbox,
                page_number=region.page_number,
                metadata=region.metadata,
            )

            if region.region_type == RegionType.TITLE:
                while heading_stack:
                    heading_stack.pop()
                root.add_child(node)
                current_parent = node
                heading_stack.append(node)
            elif region.region_type == RegionType.HEADING:
                while heading_stack and len(heading_stack) > 1:
                    heading_stack.pop()
                if heading_stack:
                    heading_stack[-1].add_child(node)
                else:
                    root.add_child(node)
                current_parent = node
                heading_stack.append(node)
            elif region.region_type in [RegionType.TABLE, RegionType.FIGURE]:
                current_parent.add_child(node)
            else:
                current_parent.add_child(node)

        return root

    def _get_reading_order(self, regions: List[LayoutRegion]) -> List[str]:
        sorted_regions = sorted(
            regions,
            key=lambda r: (r.page_number, r.bbox.y1, r.bbox.x1),
        )
        return [r.region_id for r in sorted_regions]

    def _region_to_dict(self, region: LayoutRegion) -> Dict[str, Any]:
        return {
            "region_id": region.region_id,
            "region_type": region.region_type.value,
            "bbox": region.bbox.model_dump(),
            "page_number": region.page_number,
            "confidence": region.confidence,
            "metadata": region.metadata,
            "parent_id": region.parent_id,
            "text_block_ids": [f"block_{i}" for i, _ in enumerate(region.text_blocks)],
        }

    def _save_layout_result(self, document_id: int, result: Dict[str, Any]) -> None:
        try:
            object_name = f"processed/{document_id}/layout.json"
            data = json.dumps(result, ensure_ascii=False, indent=2).encode("utf-8")
            self.storage_service.upload_bytes(
                bucket_name=settings.MINIO_PROCESSED_BUCKET,
                object_name=object_name,
                data=data,
            )
            logger.debug(f"Saved layout result for document {document_id}")
        except Exception as e:
            logger.warning(f"Failed to save layout result: {e}")

    def get_layout_result(self, document_id: int) -> Optional[Dict[str, Any]]:
        try:
            object_name = f"processed/{document_id}/layout.json"
            data = self.storage_service.download_file_bytes(
                bucket_name=settings.MINIO_PROCESSED_BUCKET,
                object_name=object_name,
            )
            return json.loads(data.decode("utf-8"))
        except Exception as e:
            logger.debug(f"Layout result not found for document {document_id}: {e}")
            return None
