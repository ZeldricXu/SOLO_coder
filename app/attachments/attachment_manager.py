import hashlib
import mimetypes
import shutil
import tempfile
from pathlib import Path
from typing import Optional, List, Dict, Any, Union

from app.database import Database
from app.config import Config
from app.attachments.image_utils import is_image_file, compress_image, create_thumbnail


def compute_md5(file_path: str, chunk_size: int = 8192) -> str:
    md5 = hashlib.md5()
    with open(file_path, "rb") as f:
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            md5.update(chunk)
    return md5.hexdigest()


def add_attachment(db: Database, config: Config, source_path: str, note_id: Optional[int] = None) -> Dict[str, Any]:
    config.ensure_directories()

    md5_hash = compute_md5(source_path)

    existing = db.get_attachment_by_md5(md5_hash)
    if existing:
        if note_id is not None:
            db.link_attachment_to_note(note_id, existing["id"])
        return existing

    src_path = Path(source_path)
    ext = src_path.suffix.lower()
    file_name = src_path.name
    mime_type = mimetypes.guess_type(source_path)[0] or ""

    sub_dir = md5_hash[:2]
    target_dir = Path(config.attachments_dir) / sub_dir
    target_dir.mkdir(parents=True, exist_ok=True)

    final_path = target_dir / f"{md5_hash}{ext}"
    thumbnail_path = ""

    if is_image_file(source_path):
        from PIL import Image
        try:
            with Image.open(source_path) as img:
                width, _ = img.size
                if width > config.image_max_width:
                    compress_image(source_path, str(final_path), config.image_max_width)
                else:
                    shutil.copy2(source_path, str(final_path))
        except Exception:
            shutil.copy2(source_path, str(final_path))

        thumb_name = f"{md5_hash}.jpg"
        thumb_dir = Path(config.thumbnails_dir) / sub_dir
        thumb_file = thumb_dir / thumb_name
        if create_thumbnail(str(final_path), str(thumb_file), config.thumbnail_size):
            thumbnail_path = str(thumb_file)
    else:
        shutil.copy2(source_path, str(final_path))

    file_size = final_path.stat().st_size

    attachment_id = db.create_attachment(
        md5_hash=md5_hash,
        file_name=file_name,
        file_path=str(final_path),
        file_size=file_size,
        mime_type=mime_type,
        thumbnail_path=thumbnail_path,
    )

    if note_id is not None:
        db.link_attachment_to_note(note_id, attachment_id)

    attachment = db.get_attachment(attachment_id)
    return attachment or {}


def delete_unused_attachments(db: Database, dry_run: bool = False) -> Union[List[Dict[str, Any]], int]:
    unused = db.get_unused_attachments()

    if dry_run:
        return unused

    total_freed = 0
    for att in unused:
        file_path = att.get("file_path", "")
        thumbnail_path = att.get("thumbnail_path", "")

        try:
            if file_path and Path(file_path).exists():
                total_freed += Path(file_path).stat().st_size
                Path(file_path).unlink()
        except Exception:
            pass

        try:
            if thumbnail_path and Path(thumbnail_path).exists():
                total_freed += Path(thumbnail_path).stat().st_size
                Path(thumbnail_path).unlink()
        except Exception:
            pass

        db.delete_attachment(att["id"])

    return total_freed


def get_attachment_stats(db: Database) -> Dict[str, Any]:
    cur = db.conn.cursor()
    cur.execute("SELECT COUNT(*) as cnt, COALESCE(SUM(file_size), 0) as total FROM attachments")
    row = cur.fetchone()
    total_count = row["cnt"]
    total_size = row["total"]

    unused = db.get_unused_attachments()
    unused_count = len(unused)
    unused_size = sum(a.get("file_size", 0) for a in unused)

    cur.close()

    return {
        "total_count": total_count,
        "total_size": total_size,
        "unused_count": unused_count,
        "unused_size": unused_size,
    }


def format_file_size(size_bytes: int) -> str:
    if size_bytes < 1024:
        return f"{size_bytes} B"
    elif size_bytes < 1024 * 1024:
        return f"{size_bytes / 1024:.1f} KB"
    elif size_bytes < 1024 * 1024 * 1024:
        return f"{size_bytes / (1024 * 1024):.1f} MB"
    else:
        return f"{size_bytes / (1024 * 1024 * 1024):.2f} GB"


def get_attachment_note_count(db: Database, attachment_id: int) -> int:
    cur = db.conn.cursor()
    cur.execute("SELECT COUNT(*) as cnt FROM note_attachments WHERE attachment_id = ?", (attachment_id,))
    row = cur.fetchone()
    cnt = row["cnt"] if row else 0
    cur.close()
    return cnt


def list_all_attachments(db: Database) -> List[Dict[str, Any]]:
    cur = db.conn.cursor()
    cur.execute("SELECT * FROM attachments ORDER BY created_at DESC")
    rows = cur.fetchall()
    cur.close()
    return [dict(r) for r in rows]
