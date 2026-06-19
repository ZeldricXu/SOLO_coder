from pathlib import Path
from typing import Tuple

from PIL import Image

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"}


def is_image_file(path: str) -> bool:
    ext = Path(path).suffix.lower()
    return ext in IMAGE_EXTENSIONS


def compress_image(src_path: str, dst_path: str, max_width: int, quality: int = 85) -> bool:
    try:
        img = Image.open(src_path)
        width, height = img.size

        if width > max_width:
            ratio = max_width / width
            new_size = (max_width, int(height * ratio))
            img = img.resize(new_size, Image.Resampling.LANCZOS)

        Path(dst_path).parent.mkdir(parents=True, exist_ok=True)

        ext = Path(src_path).suffix.lower()
        save_format = _get_save_format(ext)

        if ext == ".png":
            img.save(dst_path, format="PNG", optimize=True)
        elif ext == ".webp":
            img.save(dst_path, format="WEBP", quality=quality)
        elif ext == ".gif":
            img.save(dst_path, format="GIF", save_all=True, optimize=True)
        else:
            if img.mode in ("RGBA", "P", "LA"):
                background = Image.new("RGB", img.size, (255, 255, 255))
                background.paste(img, mask=img.split()[-1] if img.mode == "RGBA" else None)
                img = background
            img.save(dst_path, format=save_format, quality=quality, optimize=True)

        return True
    except Exception:
        return False


def create_thumbnail(src_path: str, dst_path: str, size: int = 256) -> bool:
    try:
        img = Image.open(src_path)
        img = img.convert("RGB")

        width, height = img.size
        if width == 0 or height == 0:
            return False

        scale = max(size / width, size / height)
        new_width = int(width * scale)
        new_height = int(height * scale)
        img = img.resize((new_width, new_height), Image.Resampling.LANCZOS)

        left = (new_width - size) // 2
        top = (new_height - size) // 2
        right = left + size
        bottom = top + size
        img = img.crop((left, top, right, bottom))

        Path(dst_path).parent.mkdir(parents=True, exist_ok=True)
        img.save(dst_path, format="JPEG", quality=85, optimize=True)
        return True
    except Exception:
        return False


def _get_save_format(ext: str) -> str:
    mapping = {
        ".jpg": "JPEG",
        ".jpeg": "JPEG",
        ".png": "PNG",
        ".gif": "GIF",
        ".bmp": "BMP",
        ".webp": "WEBP",
    }
    return mapping.get(ext, "JPEG")


def get_image_size(path: str) -> Tuple[int, int]:
    try:
        with Image.open(path) as img:
            return img.size
    except Exception:
        return (0, 0)
