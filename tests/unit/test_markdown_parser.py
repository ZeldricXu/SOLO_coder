import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from app.editor import MarkdownParser, DocumentModel, NodeType


class TestPlainTextParsing:

    def test_single_paragraph(self, markdown_parser: MarkdownParser):
        text = "Hello, World!"
        model = markdown_parser.parse(text)

        assert model.root.node_type == NodeType.DOCUMENT
        assert len(model.root.children) == 1
        assert model.root.children[0].node_type == NodeType.PARAGRAPH
        assert model.root.children[0].children[0].node_type == NodeType.TEXT
        assert model.root.children[0].children[0].text_content == "Hello, World!"

    def test_multiple_paragraphs(self, markdown_parser: MarkdownParser):
        text = "First paragraph.\n\nSecond paragraph."
        model = markdown_parser.parse(text)

        assert len(model.root.children) == 2
        assert model.root.children[0].node_type == NodeType.PARAGRAPH
        assert model.root.children[1].node_type == NodeType.PARAGRAPH
        assert "First paragraph" in model.root.children[0].children[0].text_content
        assert "Second paragraph" in model.root.children[1].children[0].text_content

    def test_empty_text(self, markdown_parser: MarkdownParser):
        model = markdown_parser.parse("")
        assert model.root.node_type == NodeType.DOCUMENT
        assert len(model.root.children) == 0


class TestHeadingParsing:

    def test_h1_heading(self, markdown_parser: MarkdownParser):
        model = markdown_parser.parse("# Heading 1")
        heading = model.root.children[0]
        assert heading.node_type == NodeType.HEADING
        assert heading.attributes["level"] == 1
        assert heading.children[0].text_content == "Heading 1"

    def test_h2_heading(self, markdown_parser: MarkdownParser):
        model = markdown_parser.parse("## Heading 2")
        heading = model.root.children[0]
        assert heading.node_type == NodeType.HEADING
        assert heading.attributes["level"] == 2
        assert heading.children[0].text_content == "Heading 2"

    def test_h3_heading(self, markdown_parser: MarkdownParser):
        model = markdown_parser.parse("### Heading 3")
        heading = model.root.children[0]
        assert heading.node_type == NodeType.HEADING
        assert heading.attributes["level"] == 3
        assert heading.children[0].text_content == "Heading 3"

    def test_h4_heading(self, markdown_parser: MarkdownParser):
        model = markdown_parser.parse("#### Heading 4")
        heading = model.root.children[0]
        assert heading.node_type == NodeType.HEADING
        assert heading.attributes["level"] == 4
        assert heading.children[0].text_content == "Heading 4"

    def test_h5_heading(self, markdown_parser: MarkdownParser):
        model = markdown_parser.parse("##### Heading 5")
        heading = model.root.children[0]
        assert heading.node_type == NodeType.HEADING
        assert heading.attributes["level"] == 5
        assert heading.children[0].text_content == "Heading 5"

    def test_h6_heading(self, markdown_parser: MarkdownParser):
        model = markdown_parser.parse("###### Heading 6")
        heading = model.root.children[0]
        assert heading.node_type == NodeType.HEADING
        assert heading.attributes["level"] == 6
        assert heading.children[0].text_content == "Heading 6"

    def test_heading_content(self, markdown_parser: MarkdownParser):
        model = markdown_parser.parse("# Test Title with spaces")
        heading = model.root.children[0]
        assert heading.children[0].text_content == "Test Title with spaces"

    def test_heading_then_paragraph(self, markdown_parser: MarkdownParser):
        text = "# Title\n\nThis is a paragraph after heading."
        model = markdown_parser.parse(text)

        assert len(model.root.children) == 2
        assert model.root.children[0].node_type == NodeType.HEADING
        assert model.root.children[0].attributes["level"] == 1
        assert model.root.children[1].node_type == NodeType.PARAGRAPH
        assert "paragraph after heading" in model.root.children[1].children[0].text_content


class TestListParsing:

    def test_unordered_list_dash(self, markdown_parser: MarkdownParser):
        text = "- Item 1\n- Item 2\n- Item 3"
        model = markdown_parser.parse(text)

        list_node = model.root.children[0]
        assert list_node.node_type == NodeType.LIST
        assert list_node.attributes["ordered"] is False
        assert len(list_node.children) == 3
        assert list_node.children[0].node_type == NodeType.LIST_ITEM
        assert list_node.children[0].children[0].text_content == "Item 1"

    def test_unordered_list_asterisk(self, markdown_parser: MarkdownParser):
        text = "* Item A\n* Item B\n* Item C"
        model = markdown_parser.parse(text)

        list_node = model.root.children[0]
        assert list_node.node_type == NodeType.LIST
        assert list_node.attributes["ordered"] is False
        assert len(list_node.children) == 3
        assert list_node.children[1].children[0].text_content == "Item B"

    def test_unordered_list_plus(self, markdown_parser: MarkdownParser):
        text = "+ Item X\n+ Item Y"
        model = markdown_parser.parse(text)

        list_node = model.root.children[0]
        assert list_node.node_type == NodeType.LIST
        assert list_node.attributes["ordered"] is False
        assert len(list_node.children) == 2

    def test_ordered_list(self, markdown_parser: MarkdownParser):
        text = "1. First\n2. Second\n3. Third"
        model = markdown_parser.parse(text)

        list_node = model.root.children[0]
        assert list_node.node_type == NodeType.LIST
        assert list_node.attributes["ordered"] is True
        assert len(list_node.children) == 3
        assert list_node.children[0].attributes["index"] == 1
        assert list_node.children[0].children[0].text_content == "First"
        assert list_node.children[1].children[0].text_content == "Second"
        assert list_node.children[2].children[0].text_content == "Third"

    def test_multiple_list_items(self, markdown_parser: MarkdownParser):
        text = "- One\n- Two\n- Three\n- Four\n- Five"
        model = markdown_parser.parse(text)

        list_node = model.root.children[0]
        assert len(list_node.children) == 5

    def test_nested_list(self, markdown_parser: MarkdownParser):
        text = "- Parent\n  - Child 1\n  - Child 2\n- Parent 2"
        model = markdown_parser.parse(text)

        list_node = model.root.children[0]
        assert list_node.node_type == NodeType.LIST
        assert len(list_node.children) == 4
        assert list_node.children[0].children[0].text_content == "Parent"
        assert list_node.children[1].children[0].text_content == "Child 1"
        assert list_node.children[2].children[0].text_content == "Child 2"
        assert list_node.children[3].children[0].text_content == "Parent 2"


class TestCodeBlockParsing:

    def test_code_block_with_language(self, markdown_parser: MarkdownParser):
        text = "```python\ndef hello():\n    return 'world'\n```"
        model = markdown_parser.parse(text)

        code_block = model.root.children[0]
        assert code_block.node_type == NodeType.CODE_BLOCK
        assert code_block.attributes["language"] == "python"
        assert "def hello():" in code_block.text_content
        assert "return 'world'" in code_block.text_content

    def test_code_block_without_language(self, markdown_parser: MarkdownParser):
        text = "```\nsome code\nwithout language\n```"
        model = markdown_parser.parse(text)

        code_block = model.root.children[0]
        assert code_block.node_type == NodeType.CODE_BLOCK
        assert code_block.attributes["language"] == ""
        assert "some code" in code_block.text_content
        assert "without language" in code_block.text_content

    def test_code_block_preserves_indentation(self, markdown_parser: MarkdownParser):
        text = "```python\nif True:\n    print('indented')\n    if nested:\n        pass\n```"
        model = markdown_parser.parse(text)

        code_block = model.root.children[0]
        lines = code_block.text_content.split('\n')
        assert lines[0] == "if True:"
        assert lines[1] == "    print('indented')"
        assert lines[2] == "    if nested:"
        assert lines[3] == "        pass"

    def test_multiline_code_block(self, markdown_parser: MarkdownParser):
        code_lines = [
            "line1",
            "line2",
            "line3",
            "line4",
            "line5"
        ]
        text = "```\n" + "\n".join(code_lines) + "\n```"
        model = markdown_parser.parse(text)

        code_block = model.root.children[0]
        result_lines = code_block.text_content.split('\n')
        assert len(result_lines) == 5
        for i, line in enumerate(code_lines):
            assert result_lines[i] == line


class TestTableParsing:

    def test_simple_table(self, markdown_parser: MarkdownParser):
        text = "| A | B | C |\n|---|---|---|\n| 1 | 2 | 3 |"
        model = markdown_parser.parse(text)

        table = model.root.children[0]
        assert table.node_type == NodeType.TABLE
        assert len(table.children) == 2

    def test_table_header_and_data(self, markdown_parser: MarkdownParser):
        text = "| Col1 | Col2 | Col3 |\n|------|------|------|\n| A    | B    | C    |\n| D    | E    | F    |"
        model = markdown_parser.parse(text)

        table = model.root.children[0]
        header_row = table.children[0]
        assert header_row.node_type == NodeType.TABLE_ROW
        assert header_row.attributes["is_header"] is True
        assert len(header_row.children) == 3
        assert header_row.children[0].children[0].text_content == "Col1"
        assert header_row.children[1].children[0].text_content == "Col2"
        assert header_row.children[2].children[0].text_content == "Col3"

        data_row1 = table.children[1]
        assert data_row1.node_type == NodeType.TABLE_ROW
        assert data_row1.attributes.get("is_header", False) is False
        assert data_row1.children[0].children[0].text_content == "A"
        assert data_row1.children[1].children[0].text_content == "B"
        assert data_row1.children[2].children[0].text_content == "C"

        data_row2 = table.children[2]
        assert data_row2.children[0].children[0].text_content == "D"
        assert data_row2.children[1].children[0].text_content == "E"
        assert data_row2.children[2].children[0].text_content == "F"

    def test_table_cell_count(self, markdown_parser: MarkdownParser):
        text = "| H1 | H2 |\n|----|----|\n| D1 | D2 |\n| D3 | D4 |"
        model = markdown_parser.parse(text)

        cells = model.get_all_nodes_of_type(NodeType.TABLE_CELL)
        assert len(cells) == 6

    def test_table_cell_content(self, markdown_parser: MarkdownParser):
        text = "| Name | Value |\n|------|-------|\n| Test | 123   |"
        model = markdown_parser.parse(text)

        table = model.root.children[0]
        header_row = table.children[0]
        assert header_row.children[0].children[0].text_content == "Name"
        assert header_row.children[1].children[0].text_content == "Value"

        data_row = table.children[1]
        assert data_row.children[0].children[0].text_content == "Test"
        assert data_row.children[1].children[0].text_content == "123"


class TestQuoteParsing:

    def test_single_quote_line(self, markdown_parser: MarkdownParser):
        text = "> This is a quote"
        model = markdown_parser.parse(text)

        quote = model.root.children[0]
        assert quote.node_type == NodeType.QUOTE
        assert quote.children[0].text_content == "This is a quote"

    def test_multiline_quote(self, markdown_parser: MarkdownParser):
        text = "> First line\n> Second line\n> Third line"
        model = markdown_parser.parse(text)

        quote = model.root.children[0]
        assert quote.node_type == NodeType.QUOTE
        assert "First line" in quote.children[0].text_content
        assert "Second line" in quote.children[0].text_content
        assert "Third line" in quote.children[0].text_content


class TestImageParsing:

    def test_image_with_alt_text(self, markdown_parser: MarkdownParser):
        text = "![Test Alt Text](images/test.png)"
        model = markdown_parser.parse(text)

        para = model.root.children[0]
        image = para.children[0]
        assert image.node_type == NodeType.IMAGE
        assert image.attributes["alt"] == "Test Alt Text"
        assert image.attributes["path"] == "images/test.png"

    def test_image_without_alt_text(self, markdown_parser: MarkdownParser):
        text = "![](path/to/image.jpg)"
        model = markdown_parser.parse(text)

        para = model.root.children[0]
        image = para.children[0]
        assert image.node_type == NodeType.IMAGE
        assert image.attributes["alt"] == ""
        assert image.attributes["path"] == "path/to/image.jpg"


class TestInlineElements:

    def test_bold_text(self, markdown_parser: MarkdownParser):
        text = "This is **bold** text"
        model = markdown_parser.parse(text)

        para = model.root.children[0]
        text_nodes = [c for c in para.children if c.node_type == NodeType.TEXT]
        bold_node = next(n for n in text_nodes if n.attributes.get("bold"))
        assert bold_node.text_content == "bold"
        assert bold_node.attributes["bold"] is True

    def test_italic_text(self, markdown_parser: MarkdownParser):
        text = "This is *italic* text"
        model = markdown_parser.parse(text)

        para = model.root.children[0]
        text_nodes = [c for c in para.children if c.node_type == NodeType.TEXT]
        italic_node = next(n for n in text_nodes if n.attributes.get("italic"))
        assert italic_node.text_content == "italic"
        assert italic_node.attributes["italic"] is True

    def test_inline_code(self, markdown_parser: MarkdownParser):
        text = "Use `print()` function"
        model = markdown_parser.parse(text)

        para = model.root.children[0]
        code_node = next(c for c in para.children if c.node_type == NodeType.INLINE_CODE)
        assert code_node.text_content == "print()"

    def test_link(self, markdown_parser: MarkdownParser):
        text = "Visit [Example](https://example.com) site"
        model = markdown_parser.parse(text)

        para = model.root.children[0]
        link_node = next(c for c in para.children if c.node_type == NodeType.LINK)
        assert link_node.attributes["href"] == "https://example.com"
        assert link_node.children[0].text_content == "Example"

    def test_inline_formula(self, markdown_parser: MarkdownParser):
        text = "Formula: $E=mc^2$ is famous"
        model = markdown_parser.parse(text)

        para = model.root.children[0]
        formula_node = next(c for c in para.children if c.node_type == NodeType.FORMULA)
        assert formula_node.attributes["latex"] == "E=mc^2"
        assert formula_node.attributes["inline"] is True

    def test_block_formula(self, markdown_parser: MarkdownParser):
        text = "$$\n\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}\n$$"
        model = markdown_parser.parse(text)

        formula_node = model.root.children[0]
        assert formula_node.node_type == NodeType.FORMULA
        assert "sum_{i=1}^{n}" in formula_node.attributes["latex"]
        assert formula_node.attributes["inline"] is False


class TestComplexDocument:

    def test_sample_markdown_contains_all_elements(self, sample_markdown_text: str, markdown_parser: MarkdownParser):
        model = markdown_parser.parse(sample_markdown_text)

        assert model.root.node_type == NodeType.DOCUMENT
        assert len(model.root.children) > 0

    def test_node_counts(self, sample_markdown_text: str, markdown_parser: MarkdownParser):
        model = markdown_parser.parse(sample_markdown_text)

        headings = model.get_all_nodes_of_type(NodeType.HEADING)
        assert len(headings) >= 2

        code_blocks = model.get_all_nodes_of_type(NodeType.CODE_BLOCK)
        assert len(code_blocks) >= 1

        images = model.get_all_nodes_of_type(NodeType.IMAGE)
        assert len(images) >= 1

        formulas = model.get_all_nodes_of_type(NodeType.FORMULA)
        assert len(formulas) >= 2

        tables = model.get_all_nodes_of_type(NodeType.TABLE)
        assert len(tables) >= 1

        lists = model.get_all_nodes_of_type(NodeType.LIST)
        assert len(lists) >= 1

        quotes = model.get_all_nodes_of_type(NodeType.QUOTE)
        assert len(quotes) >= 1

        paragraphs = model.get_all_nodes_of_type(NodeType.PARAGRAPH)
        assert len(paragraphs) >= 3

    def test_heading_levels(self, sample_markdown_text: str, markdown_parser: MarkdownParser):
        model = markdown_parser.parse(sample_markdown_text)
        headings = model.get_all_nodes_of_type(NodeType.HEADING)

        levels = [h.attributes["level"] for h in headings]
        assert 1 in levels
        assert 2 in levels

    def test_list_items_count(self, sample_markdown_text: str, markdown_parser: MarkdownParser):
        model = markdown_parser.parse(sample_markdown_text)
        list_items = model.get_all_nodes_of_type(NodeType.LIST_ITEM)
        assert len(list_items) >= 3

    def test_table_cells_count(self, sample_markdown_text: str, markdown_parser: MarkdownParser):
        model = markdown_parser.parse(sample_markdown_text)
        cells = model.get_all_nodes_of_type(NodeType.TABLE_CELL)
        assert len(cells) >= 9

    def test_document_structure_complete(self, sample_markdown_text: str, markdown_parser: MarkdownParser):
        model = markdown_parser.parse(sample_markdown_text)

        assert model.root is not None
        assert model.root.node_type == NodeType.DOCUMENT

        for child in model.root.children:
            assert child.parent == model.root
            assert child.node_type is not None

        def check_tree(node, depth=0):
            assert depth < 10
            for child in node.children:
                assert child.parent == node
                check_tree(child, depth + 1)

        check_tree(model.root)
