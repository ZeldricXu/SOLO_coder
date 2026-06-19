import io
import os
import hashlib
from pathlib import Path
from typing import Optional

from PyQt6.QtCore import QMimeData, QUrl
from PyQt6.QtGui import QImage, QPixmap, QImageReader

try:
    from PIL import Image as PILImage
    PIL_AVAILABLE = True
except ImportError:
    PIL_AVAILABLE = False


SUPPORTED_IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".tiff", ".svg"}


class ImageHandler:

    def __init__(self, images_dir: str, max_width: int = 2000):
        self.images_dir = Path(images_dir)
        self.images_dir.mkdir(parents=True, exist_ok=True)
        self.max_width = max_width

    @staticmethod
    def _get_file_md5(data: bytes) -> str:
        md5 = hashlib.md5()
        md5.update(data)
        return md5.hexdigest()

    def _get_save_path(self, md5_hash: str, ext: str = ".png") -> Path:
        if not ext.startswith("."):
            ext = "." + ext
        return self.images_dir / f"{md5_hash}{ext}"

    def _compress_image_if_needed(self, img_path: Path) -> Path:
        if not PIL_AVAILABLE:
            return img_path

        try:
            with PILImage.open(img_path) as img:
                width, height = img.size
                if width <= self.max_width:
                    return img_path

                new_width = self.max_width
                new_height = int(height * (new_width / width))

                resample = PILImage.LANCZOS if hasattr(PILImage, "LANCZOS") else PILImage.BILINEAR
                img = img.resize((new_width, new_height), resample)

                if img_path.suffix.lower() in (".jpg", ".jpeg"):
                    if img.mode in ("RGBA", "P"):
                        img = img.convert("RGB")
                    img.save(img_path, "JPEG", quality=85, optimize=True)
                elif img_path.suffix.lower() == ".png":
                    img.save(img_path, "PNG", optimize=True)
                else:
                    img.save(img_path)

            return img_path

        except Exception:
            return img_path

    def save_image_from_data(self, image_data: bytes, ext: str = ".png") -> Optional[Path]:
        if not image_data:
            return None

        md5_hash = self._get_file_md5(image_data)
        save_path = self._get_save_path(md5_hash, ext)

        if save_path.exists():
            self._compress_image_if_needed(save_path)
            return save_path

        try:
            save_path.write_bytes(image_data)
            self._compress_image_if_needed(save_path)
            return save_path
        except Exception:
            return None

    def save_image_from_qimage(self, image: QImage, ext: str = ".png") -> Optional[Path]:
        if image.isNull():
            return None

        buf = io.BytesIO()
        qt_ext = ext.lstrip(".").upper()
        if qt_ext == "JPG":
            qt_ext = "JPEG"

        if image.save(buf, qt_ext):
            return self.save_image_from_data(buf.getvalue(), ext)

        buf = io.BytesIO()
        if image.save(buf, "PNG"):
            return self.save_image_from_data(buf.getvalue(), ".png")

        return None

    def save_image_from_file(self, file_path: str) -> Optional[Path]:
        path = Path(file_path)
        if not path.exists() or not path.is_file():
            return None

        ext = path.suffix.lower()
        if ext not in SUPPORTED_IMAGE_EXTENSIONS:
            return None

        try:
            data = path.read_bytes()
            return self.save_image_from_data(data, ext)
        except Exception:
            return None

    def save_image_from_url(self, url: QUrl) -> Optional[Path]:
        if not url.isLocalFile():
            return None
        return self.save_image_from_file(url.toLocalFile())

    @staticmethod
    def extract_image_from_mime(mime_data: QMimeData) -> Optional[QImage]:
        if mime_data.hasImage():
            image = mime_data.imageData()
            if isinstance(image, QImage):
                return image
            if isinstance(image, QPixmap):
                return image.toImage()

        if mime_data.hasUrls():
            for url in mime_data.urls():
                if url.isLocalFile():
                    path = Path(url.toLocalFile())
                    if path.suffix.lower() in SUPPORTED_IMAGE_EXTENSIONS:
                        reader = QImageReader(str(path))
                        reader.setAutoTransform(True)
                        img = reader.read()
                        if not img.isNull():
                            return img

        return None

    def handle_mime_data(self, mime_data: QMimeData) -> Optional[Path]:
        image = self.extract_image_from_mime(mime_data)
        if image and not image.isNull():
            ext = ".png"
            if mime_data.hasUrls() and mime_data.urls():
                url = mime_data.urls()[0]
                if url.isLocalFile():
                    ext = Path(url.toLocalFile()).suffix.lower() or ".png"
            return self.save_image_from_qimage(image, ext)

        if mime_data.hasUrls():
            for url in mime_data.urls():
                result = self.save_image_from_url(url)
                if result:
                    return result

        return None

    @staticmethod
    def generate_html_tag(image_path: Path, alt_text: str = "", width: Optional[int] = None) -> str:
        img_src = image_path.as_posix()
        if os.name == "nt":
            img_src = "file:///" + img_src
        else:
            img_src = "file://" + img_src

        attrs = [f'src="{img_src}"']
        if alt_text:
            attrs.append(f'alt="{alt_text}"')
        if width:
            attrs.append(f'width="{width}"')

        return f"<img {' '.join(attrs)} />"

    @staticmethod
    def generate_markdown_tag(image_path: Path, alt_text: str = "") -> str:
        return f"![{alt_text}]({image_path.as_posix()})"

    def get_image_dimensions(self, image_path: Path) -> tuple[int, int]:
        if PIL_AVAILABLE:
            try:
                with PILImage.open(image_path) as img:
                    return img.size
            except Exception:
                pass

        reader = QImageReader(str(image_path))
        size = reader.size()
        if size.isValid():
            return (size.width(), size.height())

        return (0, 0)
