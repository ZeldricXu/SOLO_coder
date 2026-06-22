import hashlib
import os
import time
from pathlib import Path
from typing import Dict, List, Tuple

import pytest
from lxml import html as lxml_html

from app.config import Config
from app.database import Database
from app.io.html_io import export_note_to_html, import_html_to_note
from app.io.markdown_io import export_note_to_markdown, import_markdown_to_note
from app.literature.pdf_extractor import extract_metadata, extract_text
from app.search.search_engine import SearchEngine, preprocess_query


def _create_test_pdf(pdf_path: str) -> None:
    from reportlab.lib.pagesizes import letter
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.lib.units import inch
    from reportlab.platypus import (
        Paragraph,
        Spacer,
        SimpleDocTemplate,
    )
    from reportlab.pdfbase.pdfdoc import PDFDocument

    doc = SimpleDocTemplate(pdf_path, pagesize=letter,
                            rightMargin=72, leftMargin=72,
                            topMargin=72, bottomMargin=18,
                            title="Test Paper on Machine Learning",
                            author="John Smith")

    styles = getSampleStyleSheet()
    title_style = ParagraphStyle(
        'CustomTitle',
        parent=styles['Heading1'],
        fontSize=24,
        leading=28,
        alignment=1,
    )
    author_style = ParagraphStyle(
        'CustomAuthor',
        parent=styles['Normal'],
        fontSize=14,
        leading=18,
        alignment=1,
        textColor='#555555',
    )
    abstract_style = ParagraphStyle(
        'CustomAbstract',
        parent=styles['Normal'],
        fontSize=12,
        leading=16,
        firstLineIndent=24,
    )
    body_style = styles['BodyText']

    story = []

    story.append(Paragraph("Test Paper on Machine Learning", title_style))
    story.append(Spacer(1, 0.3 * inch))
    story.append(Paragraph("by John Smith", author_style))
    story.append(Spacer(1, 0.5 * inch))

    story.append(Paragraph("Abstract", styles['Heading2']))
    abstract_text = (
        "This paper explores the fundamentals of machine learning algorithms and their "
        "applications in modern data analysis. We discuss various supervised and unsupervised "
        "learning techniques, with a focus on neural networks and deep learning architectures. "
        "Experimental results demonstrate significant improvements in model performance when "
        "using the proposed optimization methods. The implications for future research directions "
        "are also discussed."
    )
    story.append(Paragraph(abstract_text, abstract_style))
    story.append(Spacer(1, 0.3 * inch))

    story.append(Paragraph("Keywords: machine learning, neural networks, deep learning", body_style))
    story.append(Spacer(1, 0.2 * inch))
    story.append(Paragraph("DOI: 10.1234/test.5678", body_style))
    story.append(Spacer(1, 0.5 * inch))

    story.append(Paragraph("1. Introduction", styles['Heading2']))
    intro_text = (
        "Machine learning has become one of the most important fields in computer science. "
        "The ability to learn from data and make predictions has revolutionized many industries. "
        "In this paper, we provide an overview of key concepts in machine learning."
    )
    story.append(Paragraph(intro_text, body_style))

    doc.build(story)


def _create_test_image(image_path: str) -> None:
    from PIL import Image
    img = Image.new('RGB', (200, 100), color=(73, 109, 137))
    img.save(image_path)


@pytest.fixture
def setup_notes(db: Database) -> Tuple[int, int, int]:
    note_c_id = db.create_note(
        title="Note C",
        content="This is Note C about deep learning research.",
        markdown_content="This is Note C about deep learning research.",
    )

    note_b_id = db.create_note(
        title="Note B",
        content=f"This is Note B about machine learning. See also [nid:{note_c_id}].",
        markdown_content=f"This is Note B about machine learning. See also [nid:{note_c_id}].",
    )

    note_a_id = db.create_note(
        title="Note A",
        content=f"This is Note A about AI and machine learning. References: [nid:{note_b_id}] and [nid:{note_c_id}]. Machine learning is a subset of AI.",
        markdown_content=f"This is Note A about AI and machine learning. References: [nid:{note_b_id}] and [nid:{note_c_id}]. Machine learning is a subset of AI.",
    )

    db.add_reference(note_a_id, note_b_id)
    db.add_reference(note_a_id, note_c_id)
    db.add_reference(note_b_id, note_c_id)

    return note_a_id, note_b_id, note_c_id


@pytest.fixture
def setup_tags(db: Database, setup_notes: Tuple[int, int, int]) -> Tuple[int, int, int, int, int, int]:
    note_a_id, note_b_id, note_c_id = setup_notes

    tag_ai_id = db.create_tag("AI", color="#4A90D9")
    tag_ml_id = db.create_tag("ML", color="#34A853")
    tag_research_id = db.create_tag("Research", color="#9C27B0")

    db.add_tag_to_note(note_a_id, tag_ai_id)
    db.add_tag_to_note(note_a_id, tag_ml_id)
    db.add_tag_to_note(note_b_id, tag_ml_id)
    db.add_tag_to_note(note_c_id, tag_research_id)
    db.add_tag_to_note(note_c_id, tag_ai_id)

    return note_a_id, note_b_id, note_c_id, tag_ai_id, tag_ml_id, tag_research_id


@pytest.fixture
def setup_html_export(db: Database, setup_tags: Tuple[int, int, int, int, int, int], tmp_path: Path) -> Tuple[int, int, int, str]:
    note_a_id, note_b_id, note_c_id, _, _, _ = setup_tags

    image_path = tmp_path / "test_image.png"
    _create_test_image(str(image_path))

    md5 = hashlib.md5(open(image_path, 'rb').read()).hexdigest()
    file_size = os.path.getsize(image_path)

    attachment_id = db.create_attachment(
        md5_hash=md5,
        file_name="test_image.png",
        file_path=str(image_path),
        file_size=file_size,
        mime_type="image/png",
    )
    db.link_attachment_to_note(note_a_id, attachment_id)

    updated_content = db.get_note(note_a_id)["content"]
    updated_content += f"\n\n![Test Image](test_image.png)"
    db.update_note(
        note_a_id,
        content=updated_content,
        markdown_content=updated_content,
    )

    html_path = str(tmp_path / "note_a_export.html")
    success = export_note_to_html(db, note_a_id, html_path, standalone=True)
    assert success is True

    return note_a_id, note_b_id, note_c_id, html_path


class TestFullWorkflow:
    @pytest.fixture(autouse=True)
    def setup(self, db: Database, test_config: Config, tmp_path: Path):
        self.db = db
        self.config = test_config
        self.tmp_path = tmp_path
        self.search_engine = SearchEngine(db)

    def test_step1_create_linked_notes(self, setup_notes: Tuple[int, int, int]):
        note_a_id, note_b_id, note_c_id = setup_notes

        refs = self.db.get_all_references()
        assert (note_a_id, note_b_id) in refs
        assert (note_a_id, note_c_id) in refs
        assert (note_b_id, note_c_id) in refs
        assert len(refs) == 3

        backlinks_b = self.db.get_backlinks(note_b_id)
        assert len(backlinks_b) == 1
        assert backlinks_b[0]["id"] == note_a_id

        backlinks_c = self.db.get_backlinks(note_c_id)
        backlink_ids = [bl["id"] for bl in backlinks_c]
        assert note_a_id in backlink_ids
        assert note_b_id in backlink_ids
        assert len(backlink_ids) == 2

    def test_step2_import_pdf_and_extract_metadata(self, setup_notes: Tuple[int, int, int]):
        note_a_id, _, _ = setup_notes

        pdf_path = str(self.tmp_path / "test_paper.pdf")
        _create_test_pdf(pdf_path)

        extracted_text = extract_text(pdf_path)
        assert "Test Paper on Machine Learning" in extracted_text
        assert "John Smith" in extracted_text

        metadata = extract_metadata(pdf_path)
        assert metadata["title"] == "Test Paper on Machine Learning"
        assert metadata["authors"] == "John Smith"
        assert metadata["doi"] == "10.1234/test.5678"
        assert metadata["abstract"] is not None
        assert "machine learning" in metadata["abstract"].lower()

        md5 = hashlib.md5(open(pdf_path, 'rb').read()).hexdigest()
        file_size = os.path.getsize(pdf_path)

        attachment_id = self.db.create_attachment(
            md5_hash=md5,
            file_name="test_paper.pdf",
            file_path=pdf_path,
            file_size=file_size,
            mime_type="application/pdf",
        )
        assert attachment_id > 0

        attachment = self.db.get_attachment(attachment_id)
        assert attachment is not None
        assert attachment["file_name"] == "test_paper.pdf"
        assert attachment["md5_hash"] == md5

        literature_id = self.db.create_literature(
            title=metadata["title"],
            authors=metadata["authors"],
            doi=metadata["doi"],
            abstract=metadata["abstract"],
            attachment_id=attachment_id,
            note_id=note_a_id,
        )
        assert literature_id > 0

        literature = self.db.get_literature(literature_id)
        assert literature is not None
        assert literature["title"] == "Test Paper on Machine Learning"
        assert literature["authors"] == "John Smith"
        assert literature["doi"] == "10.1234/test.5678"
        assert literature["note_id"] == note_a_id

        self.db.link_attachment_to_note(note_a_id, attachment_id)
        note_attachments = self.db.get_note_attachments(note_a_id)
        assert len(note_attachments) == 1
        assert note_attachments[0]["id"] == attachment_id

    def test_step3_tag_notes(self, setup_tags: Tuple[int, int, int, int, int, int]):
        note_a_id, note_b_id, note_c_id, tag_ai_id, tag_ml_id, tag_research_id = setup_tags

        assert tag_ai_id > 0
        assert tag_ml_id > 0
        assert tag_research_id > 0

        tags_a = self.db.get_note_tags(note_a_id)
        tag_names_a = [t["name"] for t in tags_a]
        assert "AI" in tag_names_a
        assert "ML" in tag_names_a
        assert len(tag_names_a) == 2

        tags_b = self.db.get_note_tags(note_b_id)
        tag_names_b = [t["name"] for t in tags_b]
        assert "ML" in tag_names_b
        assert len(tag_names_b) == 1

        tags_c = self.db.get_note_tags(note_c_id)
        tag_names_c = [t["name"] for t in tags_c]
        assert "Research" in tag_names_c
        assert "AI" in tag_names_c
        assert len(tag_names_c) == 2

    def test_step4_fulltext_search_and_sorting(self, setup_tags: Tuple[int, int, int, int, int, int]):
        note_a_id, note_b_id, note_c_id, tag_ai_id, _, _ = setup_tags

        self.db.update_note(
            note_a_id,
            content="This is Note A about AI and machine learning. Machine learning is a subset of artificial intelligence. References: [nid:{}] and [nid:{}]. Machine learning algorithms can learn from data. Deep learning is part of machine learning. The future of machine learning looks bright.".format(note_b_id, note_c_id),
            markdown_content="This is Note A about AI and machine learning. Machine learning is a subset of artificial intelligence. References: [nid:{}] and [nid:{}]. Machine learning algorithms can learn from data. Deep learning is part of machine learning. The future of machine learning looks bright.".format(note_b_id, note_c_id),
        )

        self.db.update_note(
            note_b_id,
            content="This is Note B about machine learning. See also [nid:{}].".format(note_c_id),
            markdown_content="This is Note B about machine learning. See also [nid:{}].".format(note_c_id),
        )

        self.db.update_note(
            note_c_id,
            content="This is Note C about deep learning research and neural networks.",
            markdown_content="This is Note C about deep learning research and neural networks.",
        )

        results = self.search_engine.search("machine learning")
        assert len(results) >= 2

        result_ids = [r["id"] for r in results]
        assert note_a_id in result_ids
        assert note_b_id in result_ids
        assert note_c_id not in result_ids

        idx_a = result_ids.index(note_a_id)
        idx_b = result_ids.index(note_b_id)
        assert idx_a < idx_b

        note_a = self.db.get_note(note_a_id)
        highlighted = self.search_engine.highlight_text(note_a["content"], "machine learning")
        assert "<mark>" in highlighted
        assert "</mark>" in highlighted
        assert "machine" in highlighted.lower()

        results_filtered = self.search_engine.search("learning", tag_ids=[tag_ai_id])
        filtered_ids = [r["id"] for r in results_filtered]
        assert note_a_id in filtered_ids
        assert note_c_id in filtered_ids
        assert note_b_id not in filtered_ids

    def test_step5_date_range_filter(self, setup_notes: Tuple[int, int, int]):
        note_a_id, note_b_id, note_c_id = setup_notes

        now = int(time.time())
        seven_days_ago = now - 7 * 86400
        yesterday = now - 1 * 86400
        three_days_ago = now - 3 * 86400

        with self.db.transaction() as cur:
            cur.execute("UPDATE notes SET updated_at = ?, created_at = ? WHERE id = ?",
                        (seven_days_ago, seven_days_ago, note_a_id))
            cur.execute("UPDATE notes SET updated_at = ?, created_at = ? WHERE id = ?",
                        (yesterday, yesterday, note_b_id))
            cur.execute("UPDATE notes SET updated_at = ?, created_at = ? WHERE id = ?",
                        (now, now, note_c_id))

        results = self.search_engine.search("learning", date_from=three_days_ago)
        result_ids = [r["id"] for r in results]

        assert note_b_id in result_ids
        assert note_c_id in result_ids
        assert note_a_id not in result_ids

    def test_step6_export_to_html(self, setup_tags: Tuple[int, int, int, int, int, int], tmp_path: Path):
        note_a_id, note_b_id, note_c_id, _, _, _ = setup_tags

        image_path = tmp_path / "test_image.png"
        _create_test_image(str(image_path))

        md5 = hashlib.md5(open(image_path, 'rb').read()).hexdigest()
        file_size = os.path.getsize(image_path)

        attachment_id = self.db.create_attachment(
            md5_hash=md5,
            file_name="test_image.png",
            file_path=str(image_path),
            file_size=file_size,
            mime_type="image/png",
        )
        self.db.link_attachment_to_note(note_a_id, attachment_id)

        updated_content = self.db.get_note(note_a_id)["content"]
        updated_content += f"\n\n![Test Image](test_image.png)"
        self.db.update_note(
            note_a_id,
            content=updated_content,
            markdown_content=updated_content,
        )

        html_path = str(tmp_path / "note_a_export.html")
        success = export_note_to_html(self.db, note_a_id, html_path, standalone=True)
        assert success is True
        assert os.path.exists(html_path)

        with open(html_path, 'r', encoding='utf-8') as f:
            html_content = f.read()

        assert "<style>" in html_content
        assert "box-sizing: border-box" in html_content
        assert ".note-title" in html_content

        assert "data:image/png;base64," in html_content

        assert f"note:{note_b_id}" in html_content or f"nid:{note_b_id}" in html_content
        assert f"note:{note_c_id}" in html_content or f"nid:{note_c_id}" in html_content

        assert "Note A" in html_content
        assert '<span class="tag">AI</span>' in html_content
        assert '<span class="tag">ML</span>' in html_content

        parser = lxml_html.HTMLParser(recover=False)
        tree = lxml_html.fromstring(html_content, parser=parser)
        assert tree is not None
        assert tree.find(".//h1") is not None

        return html_path

    def test_step7_reimport_html(self, setup_html_export: Tuple[int, int, int, str]):
        note_a_id, note_b_id, note_c_id, html_path = setup_html_export

        original_note = self.db.get_note(note_a_id)

        new_note_id = import_html_to_note(self.db, self.config, html_path)
        assert new_note_id > 0
        assert new_note_id != note_a_id

        new_note = self.db.get_note(new_note_id)
        assert new_note is not None
        assert new_note["title"] == original_note["title"]

        new_content = new_note.get("markdown_content", "") or new_note.get("content", "")
        original_content = original_note.get("markdown_content", "") or original_note.get("content", "")
        assert "machine learning" in new_content.lower()
        assert "AI" in new_content or "artificial intelligence" in new_content.lower()

        outgoing_links = self.db.get_outgoing_links(new_note_id)
        outgoing_ids = [link["id"] for link in outgoing_links]
        assert note_b_id in outgoing_ids or note_c_id in outgoing_ids

    def test_step8_markdown_roundtrip(self, setup_tags: Tuple[int, int, int, int, int, int]):
        note_a_id, note_b_id, note_c_id, _, _, _ = setup_tags

        original_note = self.db.get_note(note_b_id)
        original_tags = self.db.get_note_tags(note_b_id)
        original_tag_names = sorted([t["name"] for t in original_tags])

        export_dir = str(self.tmp_path / "markdown_export")
        md_path = export_note_to_markdown(self.db, note_b_id, export_dir)
        assert os.path.exists(md_path)

        with open(md_path, 'r', encoding='utf-8') as f:
            md_content = f.read()

        assert md_content.startswith("---")
        assert "title:" in md_content
        assert "Note B" in md_content
        assert "tags:" in md_content
        assert "ML" in md_content
        assert "created_at:" in md_content

        self.db.delete_note(note_b_id)
        assert self.db.get_note(note_b_id) is None

        imported_id = import_markdown_to_note(self.db, self.config, md_path)
        assert imported_id > 0

        imported_note = self.db.get_note(imported_id)
        assert imported_note is not None
        assert imported_note["title"] == original_note["title"]

        imported_content = imported_note.get("markdown_content", "") or imported_note.get("content", "")
        original_content = original_note.get("markdown_content", "") or original_note.get("content", "")
        assert imported_content.strip() == original_content.strip()

        imported_tags = self.db.get_note_tags(imported_id)
        imported_tag_names = sorted([t["name"] for t in imported_tags])
        assert imported_tag_names == original_tag_names

    def test_full_workflow_end_to_end(self, db: Database, setup_notes: Tuple[int, int, int], tmp_path: Path):
        note_a_id, note_b_id, note_c_id = setup_notes

        pdf_path = str(tmp_path / "test_paper.pdf")
        _create_test_pdf(pdf_path)
        metadata = extract_metadata(pdf_path)
        md5 = hashlib.md5(open(pdf_path, 'rb').read()).hexdigest()
        file_size = os.path.getsize(pdf_path)
        attachment_id = db.create_attachment(
            md5_hash=md5,
            file_name="test_paper.pdf",
            file_path=pdf_path,
            file_size=file_size,
            mime_type="application/pdf",
        )
        literature_id = db.create_literature(
            title=metadata["title"],
            authors=metadata["authors"],
            doi=metadata["doi"],
            abstract=metadata["abstract"],
            attachment_id=attachment_id,
            note_id=note_a_id,
        )
        assert literature_id > 0

        tag_ai_id = db.create_tag("AI", color="#4A90D9")
        tag_ml_id = db.create_tag("ML", color="#34A853")
        tag_research_id = db.create_tag("Research", color="#9C27B0")
        db.add_tag_to_note(note_a_id, tag_ai_id)
        db.add_tag_to_note(note_a_id, tag_ml_id)
        db.add_tag_to_note(note_b_id, tag_ml_id)
        db.add_tag_to_note(note_c_id, tag_research_id)
        db.add_tag_to_note(note_c_id, tag_ai_id)

        db.update_note(
            note_a_id,
            content="This is Note A about AI and machine learning. Machine learning is a subset of artificial intelligence. Machine learning algorithms can learn from data. Deep learning is part of machine learning.",
            markdown_content="This is Note A about AI and machine learning. Machine learning is a subset of artificial intelligence. Machine learning algorithms can learn from data. Deep learning is part of machine learning.",
        )
        db.update_note(
            note_b_id,
            content="This is Note B about machine learning.",
            markdown_content="This is Note B about machine learning.",
        )

        results = self.search_engine.search("machine learning")
        result_ids = [r["id"] for r in results]
        assert note_a_id in result_ids
        assert note_b_id in result_ids
        assert result_ids.index(note_a_id) < result_ids.index(note_b_id)

        now = int(time.time())
        seven_days_ago = now - 7 * 86400
        three_days_ago = now - 3 * 86400
        with db.transaction() as cur:
            cur.execute("UPDATE notes SET updated_at = ? WHERE id = ?", (seven_days_ago, note_a_id))

        results_filtered = self.search_engine.search("learning", date_from=three_days_ago)
        filtered_ids = [r["id"] for r in results_filtered]
        assert note_a_id not in filtered_ids

        html_path = str(tmp_path / "note_a_export.html")
        success = export_note_to_html(db, note_a_id, html_path, standalone=True)
        assert success is True

        new_note_id = import_html_to_note(db, self.config, html_path)
        assert new_note_id > 0
        new_note = db.get_note(new_note_id)
        assert new_note["title"] == db.get_note(note_a_id)["title"]

        export_dir = str(tmp_path / "markdown_export")
        md_path = export_note_to_markdown(db, note_b_id, export_dir)
        assert os.path.exists(md_path)

        db.delete_note(note_b_id)
        imported_id = import_markdown_to_note(db, self.config, md_path)
        assert imported_id > 0
        assert db.get_note(imported_id)["title"] == "Note B"

        refs = db.get_all_references()
        assert (note_a_id, note_c_id) in refs
        assert len(refs) >= 1

        print("全流程测试通过！")
