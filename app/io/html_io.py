import base64
import mimetypes
import re
from datetime import datetime
from pathlib import Path
from typing import Optional

import markdown as md_lib
import html2text

from app.database import Database
from app.config import Config
from app.io.markdown_io import import_markdown_to_note


ARTICLE_CSS = """
<style>
  * { box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif;
    line-height: 1.8;
    color: #2c3e50;
    max-width: 860px;
    margin: 40px auto;
    padding: 0 24px;
    background: #fafafa;
  }
  .note-header {
    border-bottom: 2px solid #e8e8e8;
    padding-bottom: 20px;
    margin-bottom: 28px;
  }
  .note-title {
    font-size: 2em;
    font-weight: 600;
    color: #1a1a1a;
    margin: 0 0 12px 0;
  }
  .note-meta {
    font-size: 0.85em;
    color: #888;
  }
  .note-tags { margin-top: 8px; }
  .tag {
    display: inline-block;
    background: #e8f0fe;
    color: #1967d2;
    padding: 2px 10px;
    border-radius: 12px;
    font-size: 0.8em;
    margin-right: 6px;
  }
  h1 { font-size: 1.7em; border-bottom: 1px solid #e8e8e8; padding-bottom: 8px; }
  h2 { font-size: 1.45em; border-bottom: 1px solid #eee; padding-bottom: 6px; }
  h3 { font-size: 1.25em; }
  h4 { font-size: 1.1em; }
  p { margin: 14px 0; }
  a { color: #1967d2; text-decoration: none; }
  a:hover { text-decoration: underline; }
  code {
    background: #f4f4f4;
    padding: 2px 6px;
    border-radius: 4px;
    font-family: "SF Mono", Menlo, Consolas, monospace;
    font-size: 0.9em;
  }
  pre {
    background: #1e1e1e;
    color: #d4d4d4;
    padding: 16px;
    border-radius: 8px;
    overflow-x: auto;
  }
  pre code {
    background: transparent;
    color: inherit;
    padding: 0;
  }
  blockquote {
    border-left: 4px solid #ddd;
    margin: 14px 0;
    padding: 4px 16px;
    color: #666;
    background: #f9f9f9;
  }
  img {
    max-width: 100%;
    height: auto;
    border-radius: 6px;
    display: block;
    margin: 16px auto;
  }
  table {
    border-collapse: collapse;
    width: 100%;
    margin: 16px 0;
  }
  th, td {
    border: 1px solid #ddd;
    padding: 8px 12px;
    text-align: left;
  }
  th { background: #f5f5f5; font-weight: 600; }
  ul, ol { padding-left: 24px; }
  li { margin: 6px 0; }
  hr { border: none; border-top: 1px solid #eee; margin: 24px 0; }
</style>
"""


def _ts_to_str(ts: int) -> str:
    return datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M:%S")


def _image_to_data_uri(img_path: str) -> Optional[str]:
    p = Path(img_path)
    if not p.exists() or not p.is_file():
        return None
    try:
        data = p.read_bytes()
        mime, _ = mimetypes.guess_type(str(p))
        if not mime:
            ext = p.suffix.lower().lstrip(".")
            mime = f"image/{ext}" if ext else "application/octet-stream"
        b64 = base64.b64encode(data).decode("ascii")
        return f"data:{mime};base64,{b64}"
    except Exception:
        return None


def _convert_images_to_data_uri(html: str, base_dir: Path) -> str:
    pattern = r'<img[^>]+src=["\']([^"\']+)["\']'
    def replace(match):
        full_tag = match.group(0)
        src = match.group(1)
        if src.startswith("data:") or src.startswith("http://") or src.startswith("https://"):
            return full_tag
        img_path = base_dir / src
        data_uri = _image_to_data_uri(str(img_path))
        if data_uri:
            return full_tag.replace(src, data_uri)
        return full_tag
    return re.sub(pattern, replace, html, flags=re.IGNORECASE)


def _extract_md_images_paths(md_content: str, base_dir: Path) -> list[tuple[str, str]]:
    result = []
    pattern = r"!\[([^\]]*)\]\(([^)]+)\)"
    for m in re.finditer(pattern, md_content):
        alt = m.group(1)
        src = m.group(2).strip()
        if src.startswith("http://") or src.startswith("https://") or src.startswith("data:"):
            continue
        result.append((alt, src))
    return result


def _markdown_to_html_with_images(md_content: str, note_id: int, db: Database, standalone: bool) -> str:
    attachments = {Path(a["file_path"]).name: a for a in db.get_note_attachments(note_id)}

    processed = md_content
    image_map = {}
    base_dir = Path(db.db_path).parent

    for alt, src in _extract_md_images_paths(processed, base_dir):
        img_path = None
        p = Path(src)
        if p.name in attachments:
            img_path = Path(attachments[p.name]["file_path"])
        elif p.exists():
            img_path = p
        elif (base_dir / src).exists():
            img_path = base_dir / src

        if img_path and img_path.exists():
            if standalone:
                data_uri = _image_to_data_uri(str(img_path))
                if data_uri:
                    image_map[src] = data_uri
            else:
                image_map[src] = str(img_path)

    for old_src, new_src in image_map.items():
        processed = processed.replace(f"]({old_src})", f"]({new_src})")

    html_body = md_lib.markdown(
        processed,
        extensions=["extra", "codehilite", "tables", "fenced_code", "sane_lists", "nl2br"],
        extension_configs={
            "codehilite": {"guess_lang": False, "css_class": "codehilite"},
        },
    )
    return html_body


def export_note_to_html(db: Database, note_id: int, output_path: str, standalone: bool = True) -> bool:
    note = db.get_note(note_id)
    if not note:
        return False

    tags = db.get_note_tags(note_id)
    folder = db.get_folder(note["folder_id"]) if note["folder_id"] else None
    content = note.get("markdown_content", "") or note.get("content", "")

    html_body = _markdown_to_html_with_images(content, note_id, db, standalone)

    tags_html = ""
    if tags:
        tag_spans = "".join(f'<span class="tag">{t["name"]}</span>' for t in tags)
        tags_html = f'<div class="note-tags">{tag_spans}</div>'

    folder_str = folder["name"] if folder else ""
    header_html = f"""
    <div class="note-header">
      <h1 class="note-title">{note["title"]}</h1>
      <div class="note-meta">
        创建：{_ts_to_str(note["created_at"])} &nbsp;|&nbsp;
        更新：{_ts_to_str(note["updated_at"])}
        {f'&nbsp;|&nbsp;目录：{folder_str}' if folder_str else ''}
      </div>
      {tags_html}
    </div>
    """

    css_block = ARTICLE_CSS if standalone else '<link rel="stylesheet" href="note.css">'

    full_html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>{note["title"]}</title>
  {css_block}
</head>
<body>
  {header_html}
  <article class="note-content">
    {html_body}
  </article>
</body>
</html>
"""

    try:
        out = Path(output_path)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(full_html, encoding="utf-8")
        return True
    except Exception:
        return False


def import_html_to_note(db: Database, config: Config, html_path: str, folder_id: Optional[int] = None) -> int:
    html_file = Path(html_path)
    if not html_file.exists():
        raise FileNotFoundError(f"HTML file not found: {html_path}")

    raw_html = html_file.read_text(encoding="utf-8")

    h = html2text.HTML2Text()
    h.body_width = 0
    h.ignore_links = False
    h.ignore_images = False
    h.ignore_emphasis = False
    h.skip_internal_links = False
    h.inline_links = True
    h.protect_links = True
    h.wrap_links = False

    md_content = h.handle(raw_html)

    title = html_file.stem
    title_match = re.search(r"<title>(.*?)</title>", raw_html, re.IGNORECASE | re.DOTALL)
    if title_match:
        t = title_match.group(1).strip()
        if t:
            title = t

    h1_match = re.search(r"<h1[^>]*>(.*?)</h1>", raw_html, re.IGNORECASE | re.DOTALL)
    if h1_match:
        t = re.sub(r"<[^>]+>", "", h1_match.group(1)).strip()
        if t:
            title = t

    frontmatter = f"---\ntitle: {title}\n---\n\n"
    full_md = frontmatter + md_content

    tmp_md = html_file.with_suffix(".tmp.md")
    tmp_md.write_text(full_md, encoding="utf-8")

    try:
        note_id = import_markdown_to_note(db, config, str(tmp_md), folder_id)
    finally:
        try:
            tmp_md.unlink()
        except Exception:
            pass

    return note_id
