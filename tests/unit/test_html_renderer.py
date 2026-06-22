import re
import base64
from pathlib import Path

import pytest

from app.editor import DocumentHtmlRenderer, MarkdownParser, NodeType, DocumentModel


def _make_1x1_png(path: Path) -> Path:
    png_bytes = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z/C/HgAGgwJ/lK3Q6wAAAABJRU5ErkJggg=="
    )
    path.write_bytes(png_bytes)
    return path


def _check_html_balanced(html: str) -> bool:
    if not html or not html.strip():
        return True

    try:
        from lxml import html as lxml_html
        lxml_html.fromstring(f"<div>{html}</div>")
        return True
    except ImportError:
        pass
    except Exception:
        pass

    tag_pattern = re.compile(r"<(/?)([a-zA-Z][a-zA-Z0-9]*)\b[^>]*>")
    stack = []
    void_tags = {"br", "hr", "img", "input", "meta", "link"}

    for match in tag_pattern.finditer(html):
        is_closing = match.group(1) == "/"
        tag_name = match.group(2).lower()

        if tag_name in void_tags:
            continue

        if is_closing:
            if not stack:
                return False
            if stack[-1] != tag_name:
                return False
            stack.pop()
        else:
            stack.append(tag_name)

    return len(stack) == 0


class TestBasicHtmlSerialization:
    def test_empty_document_renders_empty(self):
        model = DocumentModel()
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert isinstance(result, str)
        assert result.strip() == ""

    def test_paragraph_renders_p_tag(self):
        model = DocumentModel()
        model.append_paragraph("Hello, World!")
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert "<p>" in result
        assert "</p>" in result
        assert "Hello, World!" in result

    @pytest.mark.parametrize("level", [1, 2, 3, 4, 5, 6])
    def test_heading_levels_render_h_tags(self, level):
        model = DocumentModel()
        model.append_heading(level, f"Heading {level}")
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert f"<h{level}>" in result
        assert f"</h{level}>" in result
        assert f"Heading {level}" in result


class TestCodeBlockRendering:
    def test_code_block_with_language(self):
        model = DocumentModel()
        model.append_code_block("python", "print('hello')")
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert "<pre>" in result
        assert "<code" in result
        assert 'class="language-python"' in result
        assert "</code></pre>" in result

    def test_code_content_html_escaped(self):
        model = DocumentModel()
        model.append_code_block("python", "if x < 5 && y > 10:")
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert "&lt;" in result
        assert "&gt;" in result
        assert "&amp;" in result
        code_content = result.split("<code", 1)[1].rsplit("</code>", 1)[0]
        code_inner = code_content.split(">", 1)[1] if ">" in code_content else code_content
        assert "<" not in code_inner
        assert ">" not in code_inner

    def test_code_block_without_language(self):
        model = DocumentModel()
        model.append_code_block("", "some plain code")
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert "<pre><code>" in result
        assert "class=" not in result
        assert "some plain code" in result


class TestImagePathRendering:
    def test_image_node_renders_img_tag(self):
        model = DocumentModel()
        model.append_image("test.png", "Test Alt")
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert '<img' in result
        assert 'src="test.png"' in result
        assert 'alt="Test Alt"' in result

    def test_image_path_with_attachments_dir(self, tmp_path):
        images_dir = tmp_path / "attachments" / "images"
        images_dir.mkdir(parents=True)
        model = DocumentModel()
        model.append_image("photo.jpg", "Photo")
        renderer = DocumentHtmlRenderer(images_dir=str(images_dir))
        result = renderer.render(model)
        assert 'src="photo.jpg"' in result

    def test_inline_images_base64_data_uri(self, tmp_path):
        images_dir = tmp_path / "images"
        images_dir.mkdir(parents=True)
        test_img = images_dir / "inline_test.png"
        _make_1x1_png(test_img)
        model = DocumentModel()
        model.append_image("inline_test.png", "Inline Test")
        renderer = DocumentHtmlRenderer(images_dir=str(images_dir), inline_images=True)
        result = renderer.render(model)
        assert 'src="data:image/png;base64,' in result

    def test_inline_images_with_absolute_path(self, tmp_path):
        images_dir = tmp_path / "images"
        images_dir.mkdir(parents=True)
        test_img = images_dir / "abs_test.png"
        _make_1x1_png(test_img)
        model = DocumentModel()
        model.append_image(str(test_img), "Absolute Path")
        renderer = DocumentHtmlRenderer(images_dir=str(images_dir), inline_images=True)
        result = renderer.render(model)
        assert 'src="data:image/png;base64,' in result


class TestKaTeXFormulaWrapper:
    def test_inline_formula_wrapper(self):
        model = DocumentModel()
        model.append_formula("E = mc^2", inline=True)
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert '<span class="katex-inline"' in result
        assert 'data-latex="E = mc^2"' in result

    def test_block_formula_wrapper(self):
        model = DocumentModel()
        model.append_formula("\\sum_{i=1}^{n} i", inline=False)
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert '<div class="katex-block"' in result
        assert 'data-latex="\\sum_{i=1}^{n} i"' in result

    def test_latex_attribute_not_html_escaped(self):
        model = DocumentModel()
        model.append_formula("x < 5 && y > 10", inline=True)
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert 'data-latex="x < 5 && y > 10"' in result
        assert 'data-latex="x &lt;' not in result


class TestTableRendering:
    def test_table_renders_table_tag(self):
        model = DocumentModel()
        model.append_table([["A", "B"], ["C", "D"]], headers=["H1", "H2"])
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert "<table>" in result
        assert "</table>" in result
        assert "<tr>" in result

    def test_table_header_uses_th(self):
        model = DocumentModel()
        model.append_table([["A", "B"]], headers=["H1", "H2"])
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert "<th>H1</th>" in result
        assert "<th>H2</th>" in result

    def test_table_data_uses_td(self):
        model = DocumentModel()
        model.append_table([["A", "B"], ["C", "D"]], headers=["H1", "H2"])
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert "<td>A</td>" in result
        assert "<td>B</td>" in result
        assert "<td>C</td>" in result
        assert "<td>D</td>" in result

    def test_table_cell_content_correct(self):
        model = DocumentModel()
        model.append_table([["Data 1", "Data 2"]], headers=["Head 1", "Head 2"])
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert "Head 1" in result
        assert "Head 2" in result
        assert "Data 1" in result
        assert "Data 2" in result


class TestListRendering:
    def test_unordered_list_renders_ul(self):
        model = DocumentModel()
        model.append_list(["Item 1", "Item 2", "Item 3"], ordered=False)
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert "<ul>" in result
        assert "</ul>" in result
        assert "<li>" in result
        assert "</li>" in result

    def test_ordered_list_renders_ol(self):
        model = DocumentModel()
        model.append_list(["First", "Second", "Third"], ordered=True)
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert "<ol>" in result
        assert "</ol>" in result
        assert "<li>" in result
        assert "</li>" in result

    def test_list_item_text_correct(self):
        model = DocumentModel()
        model.append_list(["Apple", "Banana", "Cherry"], ordered=False)
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert "<li>Apple</li>" in result
        assert "<li>Banana</li>" in result
        assert "<li>Cherry</li>" in result


class TestLinkAndQuoteRendering:
    def test_link_renders_a_tag(self):
        parser = MarkdownParser()
        model = parser.parse("[Example](https://example.com)")
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert '<a href="https://example.com">' in result
        assert '>Example</a>' in result

    def test_quote_renders_blockquote(self):
        model = DocumentModel()
        model.append_quote("This is a quote.")
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert "<blockquote>" in result
        assert "</blockquote>" in result
        assert "This is a quote." in result

    def test_inline_code_renders_code_tag(self):
        parser = MarkdownParser()
        model = parser.parse("Use `print()` to output.")
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert "<code>" in result
        assert "</code>" in result
        assert "print()" in result


class TestFullDocumentRendering:
    def test_full_document_contains_all_tags(self, sample_markdown_text):
        parser = MarkdownParser()
        model = parser.parse(sample_markdown_text)
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)

        assert "<h1>" in result
        assert "</h1>" in result
        assert "<h2>" in result
        assert "</h2>" in result
        assert "<ul>" in result or "<ol>" in result
        assert "<li>" in result
        assert "<blockquote>" in result
        assert "<pre>" in result
        assert "<code" in result
        assert "<table>" in result
        assert "<img" in result
        assert "<a " in result

    def test_full_document_tags_balanced(self, sample_markdown_text):
        parser = MarkdownParser()
        model = parser.parse(sample_markdown_text)
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert _check_html_balanced(result)

    def test_full_document_content_present(self, sample_markdown_text):
        parser = MarkdownParser()
        model = parser.parse(sample_markdown_text)
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)

        assert "Test Document" in result
        assert "Second Heading" in result
        assert "List item 1" in result
        assert "blockquote" in result.lower() or "This is a blockquote" in result
        assert "hello" in result.lower()
        assert "Header 1" in result
        assert "Cell 1" in result
        assert "Test Image" in result or "test.png" in result
        assert "example.com" in result


class TestSpecialCharacterEscaping:
    def test_code_special_chars_escaped(self):
        model = DocumentModel()
        model.append_code_block("python", "if (x < y) && (z > 0):")
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert "&lt;" in result
        assert "&gt;" in result
        assert "&amp;" in result

    def test_heading_special_chars_escaped(self):
        parser = MarkdownParser()
        model = parser.parse("# Heading with <b>bold</b> & other")
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert "&lt;" in result
        assert "&gt;" in result
        assert "&amp;" in result

    def test_link_href_special_chars(self):
        parser = MarkdownParser()
        model = parser.parse('[Link](https://example.com?q=test&foo=bar)')
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert 'href="https://example.com?q=test&foo=bar"' in result

    def test_inline_code_escaping(self):
        parser = MarkdownParser()
        model = parser.parse("`<div>hello</div>`")
        renderer = DocumentHtmlRenderer()
        result = renderer.render(model)
        assert "<code>" in result
        assert "&lt;" in result
        assert "&gt;" in result
