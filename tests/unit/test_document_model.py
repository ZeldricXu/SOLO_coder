import pytest
from app.editor import DocumentModel, DocumentNode, NodeType


class TestDocumentNodeDataStructure:
    def test_create_paragraph_node(self):
        node = DocumentNode(NodeType.PARAGRAPH)
        assert node.node_type == NodeType.PARAGRAPH
        assert node.children == []
        assert node.attributes == {}
        assert node.text_content == ""
        assert node.parent is None

    def test_create_heading_node(self):
        node = DocumentNode(NodeType.HEADING, attributes={"level": 2})
        assert node.node_type == NodeType.HEADING
        assert node.attributes["level"] == 2

    def test_create_code_block_node(self):
        node = DocumentNode(
            NodeType.CODE_BLOCK,
            attributes={"language": "python"},
            text_content="print('hello')"
        )
        assert node.node_type == NodeType.CODE_BLOCK
        assert node.attributes["language"] == "python"
        assert node.text_content == "print('hello')"

    def test_create_image_node(self):
        node = DocumentNode(
            NodeType.IMAGE,
            attributes={"path": "/test.png", "alt": "test image", "title": "Test"}
        )
        assert node.node_type == NodeType.IMAGE
        assert node.attributes["path"] == "/test.png"
        assert node.attributes["alt"] == "test image"
        assert node.attributes["title"] == "Test"

    def test_create_formula_node(self):
        node = DocumentNode(
            NodeType.FORMULA,
            attributes={"latex": "E=mc^2", "inline": True}
        )
        assert node.node_type == NodeType.FORMULA
        assert node.attributes["latex"] == "E=mc^2"
        assert node.attributes["inline"] is True

    def test_create_table_node(self):
        table = DocumentNode(NodeType.TABLE)
        row = DocumentNode(NodeType.TABLE_ROW)
        cell = DocumentNode(NodeType.TABLE_CELL)
        table.add_child(row)
        row.add_child(cell)
        assert table.node_type == NodeType.TABLE
        assert row.node_type == NodeType.TABLE_ROW
        assert cell.node_type == NodeType.TABLE_CELL

    def test_create_list_node(self):
        list_node = DocumentNode(NodeType.LIST, attributes={"ordered": True})
        item = DocumentNode(NodeType.LIST_ITEM)
        list_node.add_child(item)
        assert list_node.node_type == NodeType.LIST
        assert list_node.attributes["ordered"] is True
        assert item.node_type == NodeType.LIST_ITEM

    def test_create_quote_node(self):
        node = DocumentNode(NodeType.QUOTE)
        assert node.node_type == NodeType.QUOTE

    def test_create_text_node(self):
        node = DocumentNode(NodeType.TEXT, text_content="Hello")
        assert node.node_type == NodeType.TEXT
        assert node.text_content == "Hello"

    def test_node_attributes_setting(self):
        node = DocumentNode(NodeType.HEADING, attributes={"level": 3})
        assert node.attributes["level"] == 3
        node.attributes["class"] = "title"
        assert node.attributes["class"] == "title"

    def test_heading_level_attribute(self):
        for level in range(1, 7):
            node = DocumentNode(NodeType.HEADING, attributes={"level": level})
            assert node.attributes["level"] == level

    def test_code_language_attribute(self):
        for lang in ["python", "java", "javascript", "html", "css"]:
            node = DocumentNode(NodeType.CODE_BLOCK, attributes={"language": lang})
            assert node.attributes["language"] == lang

    def test_image_path_and_alt(self):
        node = DocumentNode(
            NodeType.IMAGE,
            attributes={"path": "/images/test.jpg", "alt": "Test Alt"}
        )
        assert node.attributes["path"] == "/images/test.jpg"
        assert node.attributes["alt"] == "Test Alt"

    def test_formula_latex_and_inline(self):
        inline = DocumentNode(
            NodeType.FORMULA,
            attributes={"latex": "x+1", "inline": True}
        )
        block = DocumentNode(
            NodeType.FORMULA,
            attributes={"latex": "\\int x dx", "inline": False}
        )
        assert inline.attributes["latex"] == "x+1"
        assert inline.attributes["inline"] is True
        assert block.attributes["latex"] == "\\int x dx"
        assert block.attributes["inline"] is False

    def test_list_contains_list_items(self):
        list_node = DocumentNode(NodeType.LIST)
        for i in range(3):
            item = DocumentNode(NodeType.LIST_ITEM)
            list_node.add_child(item)
        assert len(list_node.children) == 3
        for child in list_node.children:
            assert child.node_type == NodeType.LIST_ITEM

    def test_table_contains_rows_and_cells(self):
        table = DocumentNode(NodeType.TABLE)
        for r in range(2):
            row = DocumentNode(NodeType.TABLE_ROW)
            for c in range(3):
                cell = DocumentNode(NodeType.TABLE_CELL)
                row.add_child(cell)
            table.add_child(row)
        assert len(table.children) == 2
        for row in table.children:
            assert row.node_type == NodeType.TABLE_ROW
            assert len(row.children) == 3
            for cell in row.children:
                assert cell.node_type == NodeType.TABLE_CELL

    def test_nested_node_structure(self):
        doc = DocumentNode(NodeType.DOCUMENT)
        para = DocumentNode(NodeType.PARAGRAPH)
        text = DocumentNode(NodeType.TEXT, text_content="Hello")
        doc.add_child(para)
        para.add_child(text)
        assert len(doc.children) == 1
        assert len(para.children) == 1
        assert para.children[0] is text

    def test_parent_pointer_on_add_child(self):
        parent = DocumentNode(NodeType.PARAGRAPH)
        child = DocumentNode(NodeType.TEXT)
        assert child.parent is None
        parent.add_child(child)
        assert child.parent is parent

    def test_parent_pointer_on_insert_child(self):
        parent = DocumentNode(NodeType.PARAGRAPH)
        child1 = DocumentNode(NodeType.TEXT, text_content="1")
        child2 = DocumentNode(NodeType.TEXT, text_content="2")
        parent.add_child(child1)
        parent.insert_child(0, child2)
        assert child1.parent is parent
        assert child2.parent is parent

    def test_parent_pointer_on_remove_child(self):
        parent = DocumentNode(NodeType.PARAGRAPH)
        child = DocumentNode(NodeType.TEXT)
        parent.add_child(child)
        assert child.parent is parent
        parent.remove_child(child)
        assert child.parent is None

    def test_parent_pointer_on_remove_child_at(self):
        parent = DocumentNode(NodeType.PARAGRAPH)
        child = DocumentNode(NodeType.TEXT)
        parent.add_child(child)
        removed = parent.remove_child_at(0)
        assert removed is child
        assert child.parent is None

    def test_children_pointer_correctness(self):
        parent = DocumentNode(NodeType.PARAGRAPH)
        child1 = DocumentNode(NodeType.TEXT, text_content="a")
        child2 = DocumentNode(NodeType.TEXT, text_content="b")
        parent.add_child(child1)
        parent.add_child(child2)
        assert parent.children[0] is child1
        assert parent.children[1] is child2
        assert parent[0] is child1
        assert parent[1] is child2

    def test_node_len_and_iter(self):
        parent = DocumentNode(NodeType.PARAGRAPH)
        for i in range(5):
            parent.add_child(DocumentNode(NodeType.TEXT))
        assert len(parent) == 5
        items = list(iter(parent))
        assert len(items) == 5
        for i, item in enumerate(items):
            assert item.node_type == NodeType.TEXT


class TestDocumentModelCRUDOperations:
    def test_append_paragraph(self):
        doc = DocumentModel()
        para = doc.append_paragraph("Hello World")
        assert para.node_type == NodeType.PARAGRAPH
        assert len(para.children) == 1
        assert para.children[0].node_type == NodeType.TEXT
        assert para.children[0].text_content == "Hello World"
        assert para.parent is doc.root

    def test_append_heading(self):
        doc = DocumentModel()
        heading = doc.append_heading(2, "My Title")
        assert heading.node_type == NodeType.HEADING
        assert heading.attributes["level"] == 2
        assert len(heading.children) == 1
        assert heading.children[0].text_content == "My Title"

    def test_append_heading_all_levels(self):
        doc = DocumentModel()
        for level in range(1, 7):
            heading = doc.append_heading(level, f"Level {level}")
            assert heading.attributes["level"] == level

    def test_append_code_block(self):
        doc = DocumentModel()
        code = doc.append_code_block("python", "print('hi')\nprint('bye')")
        assert code.node_type == NodeType.CODE_BLOCK
        assert code.attributes["language"] == "python"
        assert code.text_content == "print('hi')\nprint('bye')"

    def test_append_image(self):
        doc = DocumentModel()
        img = doc.append_image("/path/to/img.png", "alt text", "image title")
        assert img.node_type == NodeType.IMAGE
        assert img.attributes["path"] == "/path/to/img.png"
        assert img.attributes["alt"] == "alt text"
        assert img.attributes["title"] == "image title"

    def test_append_formula(self):
        doc = DocumentModel()
        formula = doc.append_formula("E=mc^2", inline=True)
        assert formula.node_type == NodeType.FORMULA
        assert formula.attributes["latex"] == "E=mc^2"
        assert formula.attributes["inline"] is True

    def test_append_block_formula(self):
        doc = DocumentModel()
        formula = doc.append_formula("\\int_0^1 x dx", inline=False)
        assert formula.attributes["inline"] is False

    def test_append_table(self):
        doc = DocumentModel()
        rows = [["a", "b"], ["c", "d"]]
        headers = ["Col1", "Col2"]
        table = doc.append_table(rows, headers)
        assert table.node_type == NodeType.TABLE
        assert table.attributes["row_count"] == 2
        assert table.attributes["col_count"] == 2
        assert len(table.children) == 3
        assert table.children[0].attributes.get("is_header") is True
        assert table.children[0].children[0].children[0].text_content == "Col1"
        assert table.children[1].children[0].children[0].text_content == "a"
        assert table.children[2].children[1].children[0].text_content == "d"

    def test_append_table_without_headers(self):
        doc = DocumentModel()
        rows = [["1", "2"], ["3", "4"]]
        table = doc.append_table(rows)
        assert len(table.children) == 2
        assert not table.children[0].attributes.get("is_header", False)

    def test_append_ordered_list(self):
        doc = DocumentModel()
        items = ["one", "two", "three"]
        list_node = doc.append_list(items, ordered=True)
        assert list_node.node_type == NodeType.LIST
        assert list_node.attributes["ordered"] is True
        assert len(list_node.children) == 3
        for i, item in enumerate(list_node.children):
            assert item.node_type == NodeType.LIST_ITEM
            assert item.attributes["index"] == i + 1
            assert item.children[0].text_content == items[i]

    def test_append_unordered_list(self):
        doc = DocumentModel()
        items = ["a", "b", "c"]
        list_node = doc.append_list(items, ordered=False)
        assert list_node.attributes["ordered"] is False
        for item in list_node.children:
            assert item.attributes["index"] == 0

    def test_append_quote(self):
        doc = DocumentModel()
        quote = doc.append_quote("This is a quote")
        assert quote.node_type == NodeType.QUOTE
        assert len(quote.children) == 1
        assert quote.children[0].text_content == "This is a quote"

    def test_insert_node_at_beginning(self):
        doc = DocumentModel()
        para1 = doc.append_paragraph("first")
        para2 = doc.append_paragraph("second")
        new_para = DocumentNode(NodeType.PARAGRAPH)
        doc.insert_node(doc.root, 0, new_para)
        assert doc.root.children[0] is new_para
        assert doc.root.children[1] is para1
        assert doc.root.children[2] is para2

    def test_insert_node_in_middle(self):
        doc = DocumentModel()
        doc.append_paragraph("1")
        doc.append_paragraph("3")
        para2 = DocumentNode(NodeType.PARAGRAPH, text_content="2")
        doc.insert_node(doc.root, 1, para2)
        assert doc.root.children[1] is para2

    def test_remove_node(self):
        doc = DocumentModel()
        para = doc.append_paragraph("test")
        assert para.parent is doc.root
        result = doc.remove_node(para)
        assert result is True
        assert para.parent is None
        assert len(doc.root.children) == 0

    def test_remove_node_without_parent(self):
        doc = DocumentModel()
        node = DocumentNode(NodeType.PARAGRAPH)
        result = doc.remove_node(node)
        assert result is False

    def test_update_node_attributes(self):
        doc = DocumentModel()
        heading = doc.append_heading(1, "Title")
        doc.update_node_attributes(heading, level=2, class_name="main")
        assert heading.attributes["level"] == 2
        assert heading.attributes["class_name"] == "main"

    def test_update_node_text(self):
        doc = DocumentModel()
        code = doc.append_code_block("python", "old code")
        doc.update_node_text(code, "new code")
        assert code.text_content == "new code"

    def test_append_node_generic(self):
        doc = DocumentModel()
        node = DocumentNode(NodeType.PARAGRAPH)
        result = doc.append_node(doc.root, node)
        assert result is node
        assert node.parent is doc.root

    def test_multiple_append_operations(self):
        doc = DocumentModel()
        doc.append_heading(1, "H1")
        doc.append_paragraph("P1")
        doc.append_paragraph("P2")
        doc.append_code_block("python", "code")
        assert len(doc.root.children) == 4
        assert doc.root.children[0].node_type == NodeType.HEADING
        assert doc.root.children[1].node_type == NodeType.PARAGRAPH
        assert doc.root.children[2].node_type == NodeType.PARAGRAPH
        assert doc.root.children[3].node_type == NodeType.CODE_BLOCK


class TestUndoRedoStack:
    def test_initial_undo_redo_state(self):
        doc = DocumentModel()
        assert doc.can_undo is False
        assert doc.can_redo is False

    def test_can_undo_after_operation(self):
        doc = DocumentModel()
        doc.append_paragraph("test")
        assert doc.can_undo is True
        assert doc.can_redo is False

    def test_undo_removes_node(self):
        doc = DocumentModel()
        doc.append_paragraph("test")
        assert len(doc.root.children) == 1
        result = doc.undo()
        assert result is True
        assert len(doc.root.children) == 0
        assert doc.can_undo is False
        assert doc.can_redo is True

    def test_redo_restores_node(self):
        doc = DocumentModel()
        doc.append_paragraph("test")
        doc.undo()
        assert len(doc.root.children) == 0
        result = doc.redo()
        assert result is True
        assert len(doc.root.children) == 1
        assert doc.can_undo is True
        assert doc.can_redo is False

    def test_multiple_undo_redo_cycles(self):
        doc = DocumentModel()
        doc.append_paragraph("1")
        doc.append_paragraph("2")
        doc.append_paragraph("3")
        assert len(doc.root.children) == 3

        doc.undo()
        assert len(doc.root.children) == 2
        doc.undo()
        assert len(doc.root.children) == 1
        doc.undo()
        assert len(doc.root.children) == 0
        assert doc.can_undo is False
        assert doc.can_redo is True

        doc.redo()
        assert len(doc.root.children) == 1
        doc.redo()
        assert len(doc.root.children) == 2
        doc.redo()
        assert len(doc.root.children) == 3
        assert doc.can_undo is True
        assert doc.can_redo is False

    def test_new_operation_clears_redo_stack(self):
        doc = DocumentModel()
        doc.append_paragraph("1")
        doc.append_paragraph("2")
        doc.undo()
        assert doc.can_redo is True
        doc.append_paragraph("3")
        assert doc.can_redo is False
        assert len(doc.root.children) == 2
        assert doc.root.children[1].children[0].text_content == "3"

    def test_undo_on_empty_stack_returns_false(self):
        doc = DocumentModel()
        result = doc.undo()
        assert result is False

    def test_redo_on_empty_stack_returns_false(self):
        doc = DocumentModel()
        result = doc.redo()
        assert result is False

    def test_undo_undoes_attribute_update(self):
        doc = DocumentModel()
        heading = doc.append_heading(1, "Title")
        doc.update_node_attributes(heading, level=2)
        assert heading.attributes["level"] == 2
        doc.undo()
        current_heading = doc.root.children[0]
        assert current_heading.attributes["level"] == 1

    def test_undo_undoes_text_update(self):
        doc = DocumentModel()
        code = doc.append_code_block("python", "old")
        doc.update_node_text(code, "new")
        assert code.text_content == "new"
        doc.undo()
        current_code = doc.root.children[0]
        assert current_code.text_content == "old"

    def test_undo_undoes_node_removal(self):
        doc = DocumentModel()
        para = doc.append_paragraph("test")
        doc.remove_node(para)
        assert len(doc.root.children) == 0
        doc.undo()
        assert len(doc.root.children) == 1

    def test_undo_undoes_node_insert(self):
        doc = DocumentModel()
        doc.append_paragraph("1")
        para2 = DocumentNode(NodeType.PARAGRAPH)
        doc.insert_node(doc.root, 1, para2)
        assert len(doc.root.children) == 2
        doc.undo()
        assert len(doc.root.children) == 1

    def test_transaction_begins(self):
        doc = DocumentModel()
        doc.begin_transaction()
        assert doc._transaction is not None

    def test_transaction_ends(self):
        doc = DocumentModel()
        doc.begin_transaction()
        doc.append_paragraph("1")
        doc.append_paragraph("2")
        assert doc.can_undo is False
        doc.end_transaction()
        assert doc._transaction is None
        assert doc.can_undo is True

    def test_transaction_single_undo_for_multiple_operations(self):
        doc = DocumentModel()
        doc.begin_transaction()
        doc.append_paragraph("1")
        doc.append_paragraph("2")
        doc.append_paragraph("3")
        doc.end_transaction()
        assert len(doc.root.children) == 3
        assert len(doc._undo_stack) == 1
        doc.undo()
        assert len(doc.root.children) == 0
        assert doc.can_redo is True

    def test_transaction_redo(self):
        doc = DocumentModel()
        doc.begin_transaction()
        doc.append_heading(1, "Title")
        doc.append_paragraph("Content")
        doc.end_transaction()
        doc.undo()
        assert len(doc.root.children) == 0
        doc.redo()
        assert len(doc.root.children) == 2

    def test_nested_transaction_treated_as_single(self):
        doc = DocumentModel()
        doc.begin_transaction()
        doc.begin_transaction()
        doc.append_paragraph("test")
        doc.end_transaction()
        doc.end_transaction()
        assert len(doc._undo_stack) == 1

    def test_undo_redo_preserves_parent_relationships(self):
        doc = DocumentModel()
        doc.begin_transaction()
        p = doc.append_paragraph("text")
        doc.append_heading(1, "title")
        doc.end_transaction()
        assert doc.root.children[0].parent is doc.root
        assert doc.root.children[1].parent is doc.root
        doc.undo()
        assert len(doc.root.children) == 0
        doc.redo()
        assert doc.root.children[0].parent is doc.root
        assert doc.root.children[1].parent is doc.root

    def test_history_limit_enforced(self):
        doc = DocumentModel()
        doc._history_limit = 5
        for i in range(10):
            doc.append_paragraph(f"item {i}")
        assert len(doc._undo_stack) == 5

    def test_clear_document(self):
        doc = DocumentModel()
        doc.append_paragraph("1")
        doc.append_paragraph("2")
        assert len(doc.root.children) == 2
        doc.clear()
        assert len(doc.root.children) == 0
        assert doc.root.node_type == NodeType.DOCUMENT
        assert doc.can_undo is True


class TestQueryMethods:
    def test_get_all_nodes_of_type_single(self):
        doc = DocumentModel()
        doc.append_paragraph("test")
        paragraphs = doc.get_all_nodes_of_type(NodeType.PARAGRAPH)
        assert len(paragraphs) == 1
        assert paragraphs[0].node_type == NodeType.PARAGRAPH

    def test_get_all_nodes_of_type_multiple(self):
        doc = DocumentModel()
        doc.append_paragraph("1")
        doc.append_heading(1, "H1")
        doc.append_paragraph("2")
        doc.append_paragraph("3")
        paragraphs = doc.get_all_nodes_of_type(NodeType.PARAGRAPH)
        assert len(paragraphs) == 3

    def test_get_all_nodes_of_type_nested(self):
        doc = DocumentModel()
        list_node = doc.append_list(["a", "b", "c"])
        list_items = doc.get_all_nodes_of_type(NodeType.LIST_ITEM)
        assert len(list_items) == 3
        for item in list_items:
            assert item.node_type == NodeType.LIST_ITEM

    def test_get_all_nodes_of_type_table_cells(self):
        doc = DocumentModel()
        doc.append_table([["1", "2"], ["3", "4"]], ["A", "B"])
        cells = doc.get_all_nodes_of_type(NodeType.TABLE_CELL)
        assert len(cells) == 6

    def test_get_all_nodes_of_type_none_found(self):
        doc = DocumentModel()
        doc.append_paragraph("test")
        formulas = doc.get_all_nodes_of_type(NodeType.FORMULA)
        assert formulas == []

    def test_find_all_text_single_node(self):
        doc = DocumentModel()
        doc.append_paragraph("Hello World")
        text = doc.root.find_all_text()
        assert text == "Hello World"

    def test_find_all_text_multiple_nodes(self):
        doc = DocumentModel()
        doc.append_heading(1, "Title")
        doc.append_paragraph("Paragraph one.")
        doc.append_paragraph("Paragraph two.")
        text = doc.root.find_all_text()
        assert "Title" in text
        assert "Paragraph one." in text
        assert "Paragraph two." in text

    def test_find_all_text_table_content(self):
        doc = DocumentModel()
        doc.append_table([["cell1", "cell2"]], ["head1", "head2"])
        text = doc.root.find_all_text()
        assert "head1" in text
        assert "head2" in text
        assert "cell1" in text
        assert "cell2" in text

    def test_find_all_text_list_content(self):
        doc = DocumentModel()
        doc.append_list(["item1", "item2", "item3"])
        text = doc.root.find_all_text()
        assert "item1" in text
        assert "item2" in text
        assert "item3" in text

    def test_find_all_text_quote_content(self):
        doc = DocumentModel()
        doc.append_quote("To be or not to be")
        text = doc.root.find_all_text()
        assert "To be or not to be" in text

    def test_get_node_by_path_root(self):
        doc = DocumentModel()
        node = doc.get_node_by_path([])
        assert node is doc.root

    def test_get_node_by_path_simple(self):
        doc = DocumentModel()
        doc.append_paragraph("first")
        second = doc.append_paragraph("second")
        doc.append_paragraph("third")
        node = doc.get_node_by_path([1])
        assert node is second

    def test_get_node_by_path_nested(self):
        doc = DocumentModel()
        list_node = doc.append_list(["a", "b", "c"])
        second_item = doc.get_node_by_path([0, 1])
        assert second_item is list_node.children[1]
        assert second_item.children[0].text_content == "b"

    def test_get_node_by_path_table_cell(self):
        doc = DocumentModel()
        doc.append_table([["r1c1", "r1c2"], ["r2c1", "r2c2"]], ["h1", "h2"])
        cell = doc.get_node_by_path([0, 1, 1])
        assert cell.node_type == NodeType.TABLE_CELL
        assert cell.children[0].text_content == "r1c2"

    def test_get_node_by_path_invalid_index(self):
        doc = DocumentModel()
        doc.append_paragraph("test")
        node = doc.get_node_by_path([999])
        assert node is None

    def test_get_node_by_path_negative_index(self):
        doc = DocumentModel()
        doc.append_paragraph("test")
        node = doc.get_node_by_path([-1])
        assert node is None

    def test_get_node_by_path_empty_path_is_root(self):
        doc = DocumentModel()
        node = doc.get_node_by_path([])
        assert node.node_type == NodeType.DOCUMENT

    def test_to_dict_structure(self):
        doc = DocumentModel()
        heading = doc.append_heading(1, "My Title")
        doc.append_paragraph("Hello")
        data = doc.root.to_dict()
        assert data["node_type"] == "document"
        assert data["children"][0]["node_type"] == "heading"
        assert data["children"][0]["attributes"]["level"] == 1
        assert data["children"][1]["node_type"] == "paragraph"

    def test_from_dict_reconstructs_structure(self):
        doc = DocumentModel()
        doc.append_heading(2, "Title")
        doc.append_paragraph("Content")
        original_data = doc.root.to_dict()
        reconstructed = DocumentNode.from_dict(original_data)
        assert reconstructed.node_type == NodeType.DOCUMENT
        assert len(reconstructed.children) == 2
        assert reconstructed.children[0].node_type == NodeType.HEADING
        assert reconstructed.children[0].attributes["level"] == 2
        assert reconstructed.children[0].children[0].text_content == "Title"

    def test_to_dict_from_dict_roundtrip(self):
        doc = DocumentModel()
        doc.append_heading(1, "Title")
        doc.append_paragraph("Paragraph")
        doc.append_code_block("python", "code")
        doc.append_image("/img.png", "alt")
        doc.append_formula("E=mc^2", True)
        doc.append_table([["a", "b"]], ["1", "2"])
        doc.append_list(["1", "2"], True)
        doc.append_quote("Quote")

        data = doc.root.to_dict()
        reconstructed = DocumentNode.from_dict(data)
        reconstructed_data = reconstructed.to_dict()

        assert data == reconstructed_data

    def test_to_dict_from_dict_preserves_nested_parent_relationships(self):
        doc = DocumentModel()
        doc.append_list(["a", "b"])
        data = doc.root.to_dict()
        reconstructed = DocumentNode.from_dict(data)
        list_node = reconstructed.children[0]
        assert list_node.children[0].parent is list_node
        assert list_node.children[1].parent is list_node

    def test_to_dict_from_dict_with_nonexistent_attributes(self):
        data = {
            "node_type": "paragraph",
            "attributes": {},
            "text_content": "",
            "children": []
        }
        node = DocumentNode.from_dict(data)
        assert node.node_type == NodeType.PARAGRAPH
        assert node.attributes == {}
        assert node.text_content == ""

    def test_find_nodes_by_type_on_node(self):
        doc = DocumentModel()
        doc.append_heading(1, "H1")
        doc.append_paragraph("P")
        doc.append_heading(2, "H2")
        headings = doc.root.find_nodes_by_type(NodeType.HEADING)
        assert len(headings) == 2

    def test_nested_text_content_in_paragraph(self):
        doc = DocumentModel()
        para = doc.append_paragraph("")
        text1 = DocumentNode(NodeType.TEXT, text_content="Hello ")
        text2 = DocumentNode(NodeType.TEXT, text_content="World")
        para.add_child(text1)
        para.add_child(text2)
        assert para.find_all_text() == "Hello World"
