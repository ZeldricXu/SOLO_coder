import os
import re
import shutil
import time
from datetime import datetime
from pathlib import Path
from typing import Optional, List, Dict, Any, Tuple

import yaml

from app.database import Database
from app.config import Config


def _ts_to_iso(ts: int) -> str:
    return datetime.fromtimestamp(ts).strftime("%Y-%m-%dT%H:%M:%S")


def _iso_to_ts(iso: str) -> int:
    try:
        return int(time.mktime(datetime.strptime(iso, "%Y-%m-%dT%H:%M:%S").timetuple()))
    except Exception:
        return int(time.time())


def _safe_filename(name: str) -> str:
    name = re.sub(r'[\\/*?:"<>|]', "_", name)
    name = name.strip().strip(".")
    return name or "untitled"


def _parse_markdown_internal_links(content: str) -> List[Tuple[int, str]]:
    links = []
    pattern = r"\[nid:(\d+)\]"
    for m in re.finditer(pattern, content):
        links.append((int(m.group(1)), m.group(0)))
    pattern2 = r"\[([^\]]+)\]\(note\)"
    for m in re.finditer(pattern2, content):
        links.append((-1, m.group(1)))
    return links


def _parse_markdown_images(content: str) -> List[str]:
    images = []
    pattern = r"!\[[^\]]*\]\(([^)]+)\)"
    for m in re.finditer(pattern, content):
        path = m.group(1).strip()
        if not path.startswith("http://") and not path.startswith("https://") and not path.startswith("data:"):
            images.append(path)
    return images


def export_note_to_markdown(db: Database, note_id: int, output_dir: str) -> str:
    note = db.get_note(note_id)
    if not note:
        raise ValueError(f"Note {note_id} not found")

    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    assets_dir = output_path / "assets"
    assets_dir.mkdir(exist_ok=True)

    tags = db.get_note_tags(note_id)
    folder = db.get_folder(note["folder_id"]) if note["folder_id"] else None

    content = note.get("markdown_content", "") or note.get("content", "")

    attachments = db.get_note_attachments(note_id)
    for att in attachments:
        src = Path(att["file_path"])
        if src.exists():
            dst = assets_dir / att["file_name"]
            try:
                shutil.copy2(src, dst)
            except Exception:
                pass

    frontmatter = {
        "title": note["title"],
        "tags": [t["name"] for t in tags],
        "folder": folder["name"] if folder else "",
        "created_at": _ts_to_iso(note["created_at"]),
        "updated_at": _ts_to_iso(note["updated_at"]),
    }

    yaml_str = yaml.dump(frontmatter, allow_unicode=True, sort_keys=False, default_flow_style=False)
    md_content = "---\n" + yaml_str + "---\n\n" + content

    filename = _safe_filename(note["title"]) + ".md"
    file_path = output_path / filename

    with open(file_path, "w", encoding="utf-8") as f:
        f.write(md_content)

    return str(file_path)


def import_markdown_to_note(db: Database, config: Config, md_path: str, folder_id: Optional[int] = None) -> int:
    md_file = Path(md_path)
    if not md_file.exists():
        raise FileNotFoundError(f"Markdown file not found: {md_path}")

    raw = md_file.read_text(encoding="utf-8")

    frontmatter = {}
    content = raw
    if raw.startswith("---"):
        parts = raw.split("---", 2)
        if len(parts) >= 3:
            try:
                frontmatter = yaml.safe_load(parts[1]) or {}
                content = parts[2].lstrip("\n")
            except yaml.YAMLError:
                content = raw

    title = frontmatter.get("title") or md_file.stem
    tag_names = frontmatter.get("tags", []) or []
    folder_name = frontmatter.get("folder", "")
    created_at = frontmatter.get("created_at")
    updated_at = frontmatter.get("updated_at")

    if isinstance(tag_names, str):
        tag_names = [t.strip() for t in tag_names.split(",") if t.strip()]

    target_folder_id = folder_id
    if folder_name and target_folder_id is None:
        for f in db.list_folders(None):
            if f["name"] == folder_name:
                target_folder_id = f["id"]
                break

    note_id = db.create_note(
        title=title,
        folder_id=target_folder_id,
        content=content,
        markdown_content=content,
    )

    if created_at:
        ts = _iso_to_ts(created_at) if isinstance(created_at, str) else int(created_at)
        with db.transaction() as cur:
            cur.execute("UPDATE notes SET created_at = ? WHERE id = ?", (ts, note_id))
    if updated_at:
        ts = _iso_to_ts(updated_at) if isinstance(updated_at, str) else int(updated_at)
        with db.transaction() as cur:
            cur.execute("UPDATE notes SET updated_at = ? WHERE id = ?", (ts, note_id))

    for tag_name in tag_names:
        tag_id = db.get_or_create_tag(str(tag_name))
        db.add_tag_to_note(note_id, tag_id)

    md_dir = md_file.parent
    images = _parse_markdown_images(content)
    for img_rel in images:
        img_path = md_dir / img_rel
        if not img_path.exists():
            alt_dir = md_dir / "assets" / Path(img_rel).name
            if alt_dir.exists():
                img_path = alt_dir
        if img_path.exists() and img_path.is_file():
            try:
                data = img_path.read_bytes()
                import hashlib
                md5 = hashlib.md5(data).hexdigest()
                from app.editor.image_handler import ImageHandler
                handler = ImageHandler(config.images_dir, config.image_max_width)
                saved = handler.save_image_from_data(data, img_path.suffix)
                if saved:
                    att_id = db.create_attachment(
                        md5_hash=md5,
                        file_name=img_path.name,
                        file_path=str(saved),
                        file_size=len(data),
                        mime_type=f"image/{img_path.suffix.lstrip('.')}",
                    )
                    db.link_attachment_to_note(note_id, att_id)
            except Exception:
                pass

    links = _parse_markdown_internal_links(content)
    for target_nid, _ in links:
        if target_nid > 0:
            tgt = db.get_note(target_nid)
            if tgt:
                db.add_reference(note_id, target_nid)
        else:
            pass

    return note_id
