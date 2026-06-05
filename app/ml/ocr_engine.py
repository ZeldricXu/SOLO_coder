import os
import numpy as np
from typing import List, Optional, Tuple, Dict, Any
from PIL import Image
import io

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.common import BoundingBox, TextBlock

logger = get_logger(__name__)
settings = get_settings()


class OCREngine:
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
        self.ocr = None
        self._initialize_engine()

    def _initialize_engine(self):
        try:
            from paddleocr import PaddleOCR

            logger.info("Initializing PaddleOCR engine...")

            ocr_kwargs = {
                "lang": settings.ocr_lang_list[0],
                "use_gpu": settings.OCR_USE_GPU,
                "show_log": settings.APP_DEBUG,
            }

            if settings.OCR_DET_MODEL_DIR:
                ocr_kwargs["det_model_dir"] = settings.OCR_DET_MODEL_DIR
            if settings.OCR_REC_MODEL_DIR:
                ocr_kwargs["rec_model_dir"] = settings.OCR_REC_MODEL_DIR
            if settings.OCR_CLS_MODEL_DIR:
                ocr_kwargs["cls_model_dir"] = settings.OCR_CLS_MODEL_DIR

            self.ocr = PaddleOCR(**ocr_kwargs)
            logger.info("PaddleOCR engine initialized successfully")
        except ImportError:
            logger.warning("PaddleOCR not installed. OCR functionality will be limited.")
            self.ocr = None
        except Exception as e:
            logger.error(f"Failed to initialize PaddleOCR: {e}")
            self.ocr = None

    def is_available(self) -> bool:
        return self.ocr is not None

    def ocr_image(self, image: Image.Image, page_number: int = 0) -> List[TextBlock]:
        if not self.is_available():
            logger.warning("OCR engine not available. Returning empty text blocks.")
            return []

        try:
            image_np = np.array(image)

            result = self.ocr.ocr(image_np, cls=True)

            text_blocks: List[TextBlock] = []

            if not result or not result[0]:
                return text_blocks

            for line in result[0]:
                try:
                    bbox_points = line[0]
                    text = line[1][0]
                    confidence = float(line[1][1])

                    x_coords = [p[0] for p in bbox_points]
                    y_coords = [p[1] for p in bbox_points]

                    bbox = BoundingBox(
                        x1=min(x_coords),
                        y1=min(y_coords),
                        x2=max(x_coords),
                        y2=max(y_coords),
                    )

                    text_block = TextBlock(
                        text=text,
                        bbox=bbox,
                        confidence=confidence,
                        block_type="ocr_text",
                        page_number=page_number,
                    )

                    text_blocks.append(text_block)
                except Exception as e:
                    logger.warning(f"Error processing OCR line: {e}")
                    continue

            logger.info(f"OCR completed for page {page_number}, found {len(text_blocks)} text blocks")
            return text_blocks

        except Exception as e:
            logger.error(f"OCR failed for page {page_number}: {e}")
            return []

    def ocr_image_file(self, image_path: str, page_number: int = 0) -> List[TextBlock]:
        try:
            image = Image.open(image_path).convert("RGB")
            return self.ocr_image(image, page_number)
        except Exception as e:
            logger.error(f"Failed to open image {image_path}: {e}")
            return []

    def detect_text_regions(self, image: Image.Image) -> List[Tuple[BoundingBox, float]]:
        if not self.is_available():
            return []

        try:
            image_np = np.array(image)
            result = self.ocr.ocr(image_np, det=True, rec=False, cls=False)

            regions: List[Tuple[BoundingBox, float]] = []

            if not result or not result[0]:
                return regions

            for line in result[0]:
                try:
                    bbox_points = line
                    x_coords = [p[0] for p in bbox_points]
                    y_coords = [p[1] for p in bbox_points]

                    bbox = BoundingBox(
                        x1=min(x_coords),
                        y1=min(y_coords),
                        x2=max(x_coords),
                        y2=max(y_coords),
                    )

                    regions.append((bbox, 1.0))
                except Exception as e:
                    logger.warning(f"Error processing detection region: {e}")
                    continue

            return regions

        except Exception as e:
            logger.error(f"Text detection failed: {e}")
            return []

    def get_ocr_metadata(self) -> Dict[str, Any]:
        return {
            "engine": "paddleocr",
            "available": self.is_available(),
            "languages": settings.ocr_lang_list,
            "use_gpu": settings.OCR_USE_GPU,
        }
