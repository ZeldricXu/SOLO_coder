import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Optional, List, Dict, Any, Tuple, Iterator


SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS folders (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    parent_id INTEGER,
    sort_order INTEGER DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (parent_id) REFERENCES folders(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS notes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL DEFAULT 'Untitled',
    content TEXT NOT NULL DEFAULT '',
    markdown_content TEXT NOT NULL DEFAULT '',
    folder_id INTEGER,
    is_pinned INTEGER DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (folder_id) REFERENCES folders(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS tags (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    color TEXT DEFAULT '#4A90D9',
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS note_tags (
    note_id INTEGER NOT NULL,
    tag_id INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (note_id, tag_id),
    FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS "references" (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    from_note_id INTEGER NOT NULL,
    to_note_id INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE(from_note_id, to_note_id),
    FOREIGN KEY (from_note_id) REFERENCES notes(id) ON DELETE CASCADE,
    FOREIGN KEY (to_note_id) REFERENCES notes(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS attachments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    md5_hash TEXT NOT NULL UNIQUE,
    file_name TEXT NOT NULL,
    file_path TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    mime_type TEXT,
    thumbnail_path TEXT,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS note_attachments (
    note_id INTEGER NOT NULL,
    attachment_id INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (note_id, attachment_id),
    FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE CASCADE,
    FOREIGN KEY (attachment_id) REFERENCES attachments(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS literature (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT,
    authors TEXT,
    abstract TEXT,
    journal TEXT,
    year INTEGER,
    doi TEXT,
    volume TEXT,
    issue TEXT,
    pages TEXT,
    bibtex_key TEXT,
    attachment_id INTEGER,
    note_id INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (attachment_id) REFERENCES attachments(id) ON DELETE SET NULL,
    FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS graph_layout (
    note_id INTEGER PRIMARY KEY,
    x REAL DEFAULT 0,
    y REAL DEFAULT 0,
    FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE CASCADE
);

CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts5(
    title,
    content,
    tags,
    literature_text,
    content=''
);

CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts_vocab USING fts5vocab(notes_fts, 'instance');

CREATE INDEX IF NOT EXISTS idx_notes_folder ON notes(folder_id);
CREATE INDEX IF NOT EXISTS idx_notes_updated ON notes(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_notes_created ON notes(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_note_tags_tag ON note_tags(tag_id);
CREATE INDEX IF NOT EXISTS idx_references_from ON "references"(from_note_id);
CREATE INDEX IF NOT EXISTS idx_references_to ON "references"(to_note_id);
CREATE INDEX IF NOT EXISTS idx_literature_doi ON literature(doi);
CREATE INDEX IF NOT EXISTS idx_literature_year ON literature(year);
"""


class Database:
    def __init__(self, db_path: str):
        self.db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(db_path)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA foreign_keys = ON")
        self.conn.execute("PRAGMA journal_mode = WAL")

    def init_schema(self):
        with self.transaction() as cur:
            cur.executescript(SCHEMA_SQL)
        self._ensure_root_folder()

    @contextmanager
    def transaction(self) -> Iterator[sqlite3.Cursor]:
        cur = self.conn.cursor()
        try:
            yield cur
            self.conn.commit()
        except Exception:
            self.conn.rollback()
            raise
        finally:
            cur.close()

    def close(self):
        self.conn.close()

    def _now(self) -> int:
        return int(time.time())

    def _ensure_root_folder(self):
        with self.transaction() as cur:
            cur.execute("SELECT COUNT(*) as cnt FROM folders WHERE parent_id IS NULL")
            if cur.fetchone()["cnt"] == 0:
                cur.execute(
                    "INSERT INTO folders (name, parent_id, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                    ("My Notes", None, 0, self._now(), self._now()),
                )

    # ---------- Folders ----------
    def create_folder(self, name: str, parent_id: Optional[int] = None) -> int:
        with self.transaction() as cur:
            cur.execute("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM folders WHERE parent_id IS ?", (parent_id,))
            sort_order = cur.fetchone()[0]
            cur.execute(
                "INSERT INTO folders (name, parent_id, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                (name, parent_id, sort_order, self._now(), self._now()),
            )
            return cur.lastrowid

    def update_folder(self, folder_id: int, **kwargs):
        if not kwargs:
            return
        kwargs["updated_at"] = self._now()
        fields = ", ".join(f"{k} = ?" for k in kwargs)
        with self.transaction() as cur:
            cur.execute(f"UPDATE folders SET {fields} WHERE id = ?", list(kwargs.values()) + [folder_id])

    def delete_folder(self, folder_id: int):
        with self.transaction() as cur:
            cur.execute("DELETE FROM folders WHERE id = ?", (folder_id,))

    def get_folder(self, folder_id: int) -> Optional[Dict]:
        cur = self.conn.cursor()
        cur.execute("SELECT * FROM folders WHERE id = ?", (folder_id,))
        row = cur.fetchone()
        cur.close()
        return dict(row) if row else None

    def list_folders(self, parent_id: Optional[int] = None) -> List[Dict]:
        cur = self.conn.cursor()
        cur.execute("SELECT * FROM folders WHERE parent_id IS ? ORDER BY sort_order, name", (parent_id,))
        rows = cur.fetchall()
        cur.close()
        return [dict(r) for r in rows]

    def get_folder_tree(self) -> List[Dict]:
        def _build(parent_id):
            folders = self.list_folders(parent_id)
            for f in folders:
                f["children"] = _build(f["id"])
            return folders
        return _build(None)

    def reorder_folders(self, folder_ids: List[int], parent_id: Optional[int] = None):
        with self.transaction() as cur:
            for idx, fid in enumerate(folder_ids):
                cur.execute("UPDATE folders SET sort_order = ?, parent_id = ?, updated_at = ? WHERE id = ?",
                            (idx, parent_id, self._now(), fid))

    # ---------- Notes ----------
    def create_note(self, title: str = "Untitled", folder_id: Optional[int] = None,
                    content: str = "", markdown_content: str = "") -> int:
        now = self._now()
        with self.transaction() as cur:
            cur.execute(
                "INSERT INTO notes (title, content, markdown_content, folder_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                (title, content, markdown_content, folder_id, now, now),
            )
            note_id = cur.lastrowid
            cur.execute(
                "INSERT INTO notes_fts (rowid, title, content, tags, literature_text) VALUES (?, ?, ?, '', '')",
                (note_id, title, content),
            )
            return note_id

    def update_note(self, note_id: int, **kwargs):
        if not kwargs:
            return
        kwargs["updated_at"] = self._now()
        fields = ", ".join(f"{k} = ?" for k in kwargs)
        with self.transaction() as cur:
            cur.execute(f"UPDATE notes SET {fields} WHERE id = ?", list(kwargs.values()) + [note_id])
            if "title" in kwargs or "content" in kwargs:
                title = kwargs.get("title") or self.get_note(note_id)["title"]
                content = kwargs.get("content") or self.get_note(note_id)["content"]
                tags_str = self._get_tags_string(note_id)
                lit_str = self._get_literature_string(note_id)
                cur.execute(
                    "INSERT OR REPLACE INTO notes_fts (rowid, title, content, tags, literature_text) VALUES (?, ?, ?, ?, ?)",
                    (note_id, title, content, tags_str, lit_str),
                )

    def delete_note(self, note_id: int):
        with self.transaction() as cur:
            cur.execute("DELETE FROM notes WHERE id = ?", (note_id,))
            cur.execute("INSERT INTO notes_fts(notes_fts, rowid) VALUES('delete', ?)", (note_id,))

    def get_note(self, note_id: int) -> Optional[Dict]:
        cur = self.conn.cursor()
        cur.execute("SELECT * FROM notes WHERE id = ?", (note_id,))
        row = cur.fetchone()
        cur.close()
        return dict(row) if row else None

    def list_notes(self, folder_id: Optional[int] = None, tag_id: Optional[int] = None,
                   limit: int = 100, offset: int = 0) -> List[Dict]:
        query = "SELECT DISTINCT n.* FROM notes n"
        params: List[Any] = []
        joins = []
        wheres = []
        if tag_id is not None:
            joins.append("JOIN note_tags nt ON nt.note_id = n.id")
            wheres.append("nt.tag_id = ?")
            params.append(tag_id)
        if folder_id is not None:
            wheres.append("n.folder_id = ?")
            params.append(folder_id)
        if joins:
            query += " " + " ".join(joins)
        if wheres:
            query += " WHERE " + " AND ".join(wheres)
        query += " ORDER BY n.is_pinned DESC, n.updated_at DESC LIMIT ? OFFSET ?"
        params.extend([limit, offset])
        cur = self.conn.cursor()
        cur.execute(query, params)
        rows = cur.fetchall()
        cur.close()
        return [dict(r) for r in rows]

    def list_recent_notes(self, limit: int = 20) -> List[Dict]:
        cur = self.conn.cursor()
        cur.execute("SELECT * FROM notes ORDER BY updated_at DESC LIMIT ?", (limit,))
        rows = cur.fetchall()
        cur.close()
        return [dict(r) for r in rows]

    # ---------- Tags ----------
    def create_tag(self, name: str, color: str = "#4A90D9") -> int:
        with self.transaction() as cur:
            cur.execute("INSERT OR IGNORE INTO tags (name, color, created_at) VALUES (?, ?, ?)",
                        (name, color, self._now()))
            cur.execute("SELECT id FROM tags WHERE name = ?", (name,))
            return cur.fetchone()["id"]

    def get_or_create_tag(self, name: str) -> int:
        return self.create_tag(name)

    def update_tag(self, tag_id: int, **kwargs):
        if not kwargs:
            return
        fields = ", ".join(f"{k} = ?" for k in kwargs)
        with self.transaction() as cur:
            cur.execute(f"UPDATE tags SET {fields} WHERE id = ?", list(kwargs.values()) + [tag_id])

    def delete_tag(self, tag_id: int):
        with self.transaction() as cur:
            cur.execute("DELETE FROM tags WHERE id = ?", (tag_id,))

    def list_tags(self) -> List[Dict]:
        cur = self.conn.cursor()
        cur.execute("""
            SELECT t.*, COUNT(nt.note_id) as note_count
            FROM tags t LEFT JOIN note_tags nt ON nt.tag_id = t.id
            GROUP BY t.id ORDER BY note_count DESC, t.name
        """)
        rows = cur.fetchall()
        cur.close()
        return [dict(r) for r in rows]

    def get_note_tags(self, note_id: int) -> List[Dict]:
        cur = self.conn.cursor()
        cur.execute("""
            SELECT t.* FROM tags t
            JOIN note_tags nt ON nt.tag_id = t.id
            WHERE nt.note_id = ? ORDER BY t.name
        """, (note_id,))
        rows = cur.fetchall()
        cur.close()
        return [dict(r) for r in rows]

    def add_tag_to_note(self, note_id: int, tag_id: int):
        with self.transaction() as cur:
            cur.execute("INSERT OR IGNORE INTO note_tags (note_id, tag_id, created_at) VALUES (?, ?, ?)",
                        (note_id, tag_id, self._now()))
            tags_str = self._get_tags_string(note_id)
            note = self.get_note(note_id)
            if note:
                lit_str = self._get_literature_string(note_id)
                cur.execute(
                    "INSERT OR REPLACE INTO notes_fts (rowid, title, content, tags, literature_text) VALUES (?, ?, ?, ?, ?)",
                    (note_id, note["title"], note["content"], tags_str, lit_str),
                )

    def remove_tag_from_note(self, note_id: int, tag_id: int):
        with self.transaction() as cur:
            cur.execute("DELETE FROM note_tags WHERE note_id = ? AND tag_id = ?", (note_id, tag_id))
            tags_str = self._get_tags_string(note_id)
            note = self.get_note(note_id)
            if note:
                lit_str = self._get_literature_string(note_id)
                cur.execute(
                    "INSERT OR REPLACE INTO notes_fts (rowid, title, content, tags, literature_text) VALUES (?, ?, ?, ?, ?)",
                    (note_id, note["title"], note["content"], tags_str, lit_str),
                )

    def _get_tags_string(self, note_id: int) -> str:
        cur = self.conn.cursor()
        cur.execute("""
            SELECT GROUP_CONCAT(t.name, ' ') FROM tags t
            JOIN note_tags nt ON nt.tag_id = t.id WHERE nt.note_id = ?
        """, (note_id,))
        row = cur.fetchone()
        cur.close()
        return row[0] or ""

    def _get_literature_string(self, note_id: int) -> str:
        cur = self.conn.cursor()
        cur.execute("SELECT title, authors, abstract, journal FROM literature WHERE note_id = ?", (note_id,))
        row = cur.fetchone()
        cur.close()
        if row:
            parts = [v for v in row if v]
            return " ".join(parts)
        return ""

    # ---------- References ----------
    def add_reference(self, from_note_id: int, to_note_id: int):
        if from_note_id == to_note_id:
            return
        with self.transaction() as cur:
            cur.execute('INSERT OR IGNORE INTO "references" (from_note_id, to_note_id, created_at) VALUES (?, ?, ?)',
                        (from_note_id, to_note_id, self._now()))

    def remove_reference(self, from_note_id: int, to_note_id: int):
        with self.transaction() as cur:
            cur.execute('DELETE FROM "references" WHERE from_note_id = ? AND to_note_id = ?',
                        (from_note_id, to_note_id))

    def get_backlinks(self, note_id: int) -> List[Dict]:
        cur = self.conn.cursor()
        cur.execute('''
            SELECT n.* FROM notes n
            JOIN "references" r ON r.from_note_id = n.id
            WHERE r.to_note_id = ? ORDER BY n.updated_at DESC
        ''', (note_id,))
        rows = cur.fetchall()
        cur.close()
        return [dict(r) for r in rows]

    def get_outgoing_links(self, note_id: int) -> List[Dict]:
        cur = self.conn.cursor()
        cur.execute('''
            SELECT n.* FROM notes n
            JOIN "references" r ON r.to_note_id = n.id
            WHERE r.from_note_id = ? ORDER BY n.updated_at DESC
        ''', (note_id,))
        rows = cur.fetchall()
        cur.close()
        return [dict(r) for r in rows]

    def get_all_references(self) -> List[Tuple[int, int]]:
        cur = self.conn.cursor()
        cur.execute('SELECT from_note_id, to_note_id FROM "references"')
        rows = cur.fetchall()
        cur.close()
        return [(r["from_note_id"], r["to_note_id"]) for r in rows]

    def get_citation_count(self, note_id: int) -> int:
        cur = self.conn.cursor()
        cur.execute('SELECT COUNT(*) as cnt FROM "references" WHERE to_note_id = ?', (note_id,))
        cnt = cur.fetchone()["cnt"]
        cur.close()
        return cnt

    def get_isolated_notes(self) -> List[Dict]:
        cur = self.conn.cursor()
        cur.execute('''
            SELECT * FROM notes WHERE id NOT IN (
                SELECT from_note_id FROM "references" UNION SELECT to_note_id FROM "references"
            ) ORDER BY updated_at DESC
        ''')
        rows = cur.fetchall()
        cur.close()
        return [dict(r) for r in rows]

    # ---------- Attachments ----------
    def create_attachment(self, md5_hash: str, file_name: str, file_path: str,
                          file_size: int, mime_type: str = "", thumbnail_path: str = "") -> int:
        with self.transaction() as cur:
            cur.execute("SELECT id FROM attachments WHERE md5_hash = ?", (md5_hash,))
            row = cur.fetchone()
            if row:
                return row["id"]
            cur.execute(
                "INSERT INTO attachments (md5_hash, file_name, file_path, file_size, mime_type, thumbnail_path, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                (md5_hash, file_name, file_path, file_size, mime_type, thumbnail_path, self._now()),
            )
            return cur.lastrowid

    def get_attachment(self, attachment_id: int) -> Optional[Dict]:
        cur = self.conn.cursor()
        cur.execute("SELECT * FROM attachments WHERE id = ?", (attachment_id,))
        row = cur.fetchone()
        cur.close()
        return dict(row) if row else None

    def get_attachment_by_md5(self, md5_hash: str) -> Optional[Dict]:
        cur = self.conn.cursor()
        cur.execute("SELECT * FROM attachments WHERE md5_hash = ?", (md5_hash,))
        row = cur.fetchone()
        cur.close()
        return dict(row) if row else None

    def link_attachment_to_note(self, note_id: int, attachment_id: int):
        with self.transaction() as cur:
            cur.execute("INSERT OR IGNORE INTO note_attachments (note_id, attachment_id, created_at) VALUES (?, ?, ?)",
                        (note_id, attachment_id, self._now()))

    def get_note_attachments(self, note_id: int) -> List[Dict]:
        cur = self.conn.cursor()
        cur.execute("""
            SELECT a.* FROM attachments a
            JOIN note_attachments na ON na.attachment_id = a.id
            WHERE na.note_id = ? ORDER BY a.created_at DESC
        """, (note_id,))
        rows = cur.fetchall()
        cur.close()
        return [dict(r) for r in rows]

    def get_unused_attachments(self) -> List[Dict]:
        cur = self.conn.cursor()
        cur.execute("""
            SELECT a.* FROM attachments a
            WHERE a.id NOT IN (SELECT attachment_id FROM note_attachments UNION SELECT attachment_id FROM literature WHERE attachment_id IS NOT NULL)
            ORDER BY a.created_at
        """)
        rows = cur.fetchall()
        cur.close()
        return [dict(r) for r in rows]

    def delete_attachment(self, attachment_id: int):
        with self.transaction() as cur:
            cur.execute("DELETE FROM attachments WHERE id = ?", (attachment_id,))

    # ---------- Literature ----------
    def create_literature(self, **kwargs) -> int:
        now = self._now()
        kwargs.setdefault("created_at", now)
        kwargs.setdefault("updated_at", now)
        fields = ", ".join(kwargs.keys())
        placeholders = ", ".join("?" for _ in kwargs)
        with self.transaction() as cur:
            cur.execute(f"INSERT INTO literature ({fields}) VALUES ({placeholders})", list(kwargs.values()))
            lit_id = cur.lastrowid
            if kwargs.get("note_id"):
                note = self.get_note(kwargs["note_id"])
                if note:
                    tags_str = self._get_tags_string(kwargs["note_id"])
                    lit_str = self._get_literature_string(kwargs["note_id"])
                    cur.execute(
                        "INSERT OR REPLACE INTO notes_fts (rowid, title, content, tags, literature_text) VALUES (?, ?, ?, ?, ?)",
                        (kwargs["note_id"], note["title"], note["content"], tags_str, lit_str),
                    )
            return lit_id

    def update_literature(self, lit_id: int, **kwargs):
        if not kwargs:
            return
        kwargs["updated_at"] = self._now()
        fields = ", ".join(f"{k} = ?" for k in kwargs)
        with self.transaction() as cur:
            cur.execute(f"UPDATE literature SET {fields} WHERE id = ?", list(kwargs.values()) + [lit_id])
            cur.execute("SELECT note_id FROM literature WHERE id = ?", (lit_id,))
            row = cur.fetchone()
            if row and row["note_id"]:
                note = self.get_note(row["note_id"])
                if note:
                    tags_str = self._get_tags_string(row["note_id"])
                    lit_str = self._get_literature_string(row["note_id"])
                    cur.execute(
                        "INSERT OR REPLACE INTO notes_fts (rowid, title, content, tags, literature_text) VALUES (?, ?, ?, ?, ?)",
                        (row["note_id"], note["title"], note["content"], tags_str, lit_str),
                    )

    def delete_literature(self, lit_id: int):
        with self.transaction() as cur:
            cur.execute("DELETE FROM literature WHERE id = ?", (lit_id,))

    def get_literature(self, lit_id: int) -> Optional[Dict]:
        cur = self.conn.cursor()
        cur.execute("SELECT * FROM literature WHERE id = ?", (lit_id,))
        row = cur.fetchone()
        cur.close()
        return dict(row) if row else None

    def list_literature(self, sort_by: str = "year", desc: bool = True, search: str = "") -> List[Dict]:
        order_field = {"year": "year", "author": "authors", "journal": "journal", "title": "title"}.get(sort_by, "year")
        query = "SELECT * FROM literature"
        params: List[Any] = []
        if search:
            query += " WHERE title LIKE ? OR authors LIKE ? OR journal LIKE ? OR doi LIKE ?"
            like = f"%{search}%"
            params = [like, like, like, like]
        query += f" ORDER BY {order_field} {'DESC' if desc else 'ASC'} NULLS LAST"
        cur = self.conn.cursor()
        cur.execute(query, params)
        rows = cur.fetchall()
        cur.close()
        return [dict(r) for r in rows]

    def find_literature_by_doi(self, doi: str) -> Optional[Dict]:
        cur = self.conn.cursor()
        cur.execute("SELECT * FROM literature WHERE doi = ?", (doi,))
        row = cur.fetchone()
        cur.close()
        return dict(row) if row else None

    # ---------- Graph Layout ----------
    def get_graph_layout(self, note_id: int) -> Optional[Tuple[float, float]]:
        cur = self.conn.cursor()
        cur.execute("SELECT x, y FROM graph_layout WHERE note_id = ?", (note_id,))
        row = cur.fetchone()
        cur.close()
        return (row["x"], row["y"]) if row else None

    def set_graph_layout(self, note_id: int, x: float, y: float):
        with self.transaction() as cur:
            cur.execute("INSERT OR REPLACE INTO graph_layout (note_id, x, y) VALUES (?, ?, ?)",
                        (note_id, x, y))

    def get_all_graph_layouts(self) -> Dict[int, Tuple[float, float]]:
        cur = self.conn.cursor()
        cur.execute("SELECT note_id, x, y FROM graph_layout")
        rows = cur.fetchall()
        cur.close()
        return {r["note_id"]: (r["x"], r["y"]) for r in rows}

    # ---------- FTS Search ----------
    def fts_search(self, query: str, limit: int = 50) -> List[Dict]:
        if not query or not query.strip():
            return []
        cur = self.conn.cursor()
        try:
            cur.execute("""
                SELECT n.*, bm25(notes_fts) as rank
                FROM notes_fts f
                JOIN notes n ON n.id = f.rowid
                WHERE notes_fts MATCH ?
                ORDER BY rank, n.updated_at DESC
                LIMIT ?
            """, (query, limit))
            rows = cur.fetchall()
            cur.close()
            return [dict(r) for r in rows]
        except sqlite3.OperationalError:
            cur.close()
            return []
