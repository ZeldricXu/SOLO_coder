import os
import sys
import tempfile
import shutil
from pathlib import Path
from typing import Generator

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent))

from app.config import Config
from app.database import Database
from app.editor import ImageHandler, HtmlSanitizer, MarkdownParser, DocumentHtmlRenderer
from app.graph import GraphWidget, GraphNode, GraphEdge


@pytest.fixture(scope="session")
def qapp_cls():
    from PyQt6.QtWidgets import QApplication
    return QApplication


@pytest.fixture(scope="session")
def qapp(qapp_cls):
    app = qapp_cls.instance()
    if app is None:
        app = qapp_cls([])
    yield app
    app.quit()


@pytest.fixture
def temp_dir(tmp_path: Path) -> Path:
    test_dir = tmp_path / "kv_test"
    test_dir.mkdir(parents=True, exist_ok=True)
    yield test_dir
    shutil.rmtree(test_dir, ignore_errors=True)


@pytest.fixture
def temp_db_path(tmp_path: Path) -> Generator[str, None, None]:
    db_path = tmp_path / "test_knowledge.db"
    yield str(db_path)
    if db_path.exists():
        try:
            db_path.unlink()
            for suffix in (".wal", ".shm", ".journal"):
                extra = db_path.with_suffix(db_path.suffix + suffix)
                if extra.exists():
                    extra.unlink()
        except:
            pass


@pytest.fixture
def db(temp_db_path: str) -> Generator[Database, None, None]:
    database = Database(temp_db_path)
    database.init_schema()
    yield database
    database.close()


@pytest.fixture
def test_config(temp_dir: Path) -> Config:
    config = Config(
        app_data_dir=str(temp_dir / "data"),
        last_opened_note=None,
        theme="light",
        font_family="PingFang SC",
        font_size=14,
        auto_save_interval_ms=30000,
        image_max_width=2000,
        thumbnail_size=256,
    )
    config.ensure_directories()
    return config


@pytest.fixture
def image_handler(test_config: Config) -> ImageHandler:
    return ImageHandler(test_config.images_dir, test_config.image_max_width)


@pytest.fixture
def html_sanitizer() -> HtmlSanitizer:
    return HtmlSanitizer()


@pytest.fixture
def markdown_parser() -> MarkdownParser:
    return MarkdownParser()


@pytest.fixture
def html_renderer(test_config: Config) -> DocumentHtmlRenderer:
    return DocumentHtmlRenderer(images_dir=test_config.images_dir)


@pytest.fixture
def sample_markdown_text() -> str:
    return """# Test Document

This is a **bold** and *italic* text with `inline code`.

## Second Heading

- List item 1
- List item 2
- List item 3

> This is a blockquote
> with multiple lines.

```python
def hello():
    print("Hello, World!")
    return True
```

| Header 1 | Header 2 | Header 3 |
|----------|----------|----------|
| Cell 1   | Cell 2   | Cell 3   |
| Cell 4   | Cell 5   | Cell 6   |

![Test Image](images/test.png)

Inline formula: $E = mc^2$

$$
\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}
$$

[Visit Example](https://example.com)
"""


@pytest.fixture
def sample_malicious_html() -> str:
    return """
<div>
    <h1>Normal Heading</h1>
    <p>This is normal text.</p>
    <script>alert('malicious');</script>
    <iframe src="evil.com"></iframe>
    <img src="javascript:alert('xss')" alt="XSS">
    <a href="javascript:stealCookies()">Click Me</a>
    <style>body { background: red; }</style>
    <div onclick="alert('click')">Clickable Div</div>
    <p onmouseover="alert('hover')">Hover Me</p>
</div>
"""


@pytest.fixture
def sample_50mb_image_bytes() -> bytes:
    return b"\x89PNG\r\n\x1a\n" + b"\x00" * (50 * 1024 * 1024)


@pytest.fixture
def graph_test_db(db: Database) -> Database:
    tag1_id = db.create_tag("重要", "#E74C3C")
    tag2_id = db.create_tag("工作", "#2ECC71")
    tag3_id = db.create_tag("研究", "#F39C12")

    note1_id = db.create_note(
        title="笔记一的标题很长超过十五个字",
        content="内容1"
    )
    note2_id = db.create_note(
        title="笔记二",
        content="内容2"
    )
    note3_id = db.create_note(
        title="笔记三标题超长用于测试截断显示效果",
        content="内容3"
    )
    note4_id = db.create_note(
        title="笔记四",
        content="内容4"
    )
    note5_id = db.create_note(
        title="笔记五",
        content="内容5"
    )
    note6_id = db.create_note(
        title="孤立笔记",
        content="这是一篇孤立的笔记"
    )

    db.add_tag_to_note(note1_id, tag1_id)
    db.add_tag_to_note(note2_id, tag2_id)
    db.add_tag_to_note(note3_id, tag3_id)

    references = [
        (note1_id, note2_id),
        (note1_id, note3_id),
        (note2_id, note3_id),
        (note2_id, note4_id),
        (note2_id, note5_id),
        (note3_id, note5_id),
        (note4_id, note5_id),
        (note5_id, note2_id),
    ]
    for from_id, to_id in references:
        db.add_reference(from_id, to_id)

    return db


@pytest.fixture
def graph_widget(qapp, qtbot) -> GraphWidget:
    widget = GraphWidget()
    widget.resize(1200, 900)
    widget.show()
    qtbot.addWidget(widget)
    qtbot.waitForWindowShown(widget)
    return widget


def pytest_configure(config):
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
