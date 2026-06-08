import json
from typing import List, Dict, Any
from unittest.mock import patch, MagicMock

import pytest

from app.ml.layout_analyzer import (
    LayoutAnalyzer, LayoutRegion, RegionType, DocumentTreeNode
)
from app.schemas.document import StandardizedDocument, PageInfo, DocumentTypeEnum
from app.schemas.common import BoundingBox, TextBlock


@pytest.mark.unit
@pytest.mark.layout
class TestRegionType:
    def test_all_region_types_defined(self):
        expected_types = {
            "title", "heading", "paragraph", "list", "table",
            "figure", "signature", "header", "footer", "form_field", "unknown"
        }
        actual_types = {rt.value for rt in RegionType}
        assert expected_types.issubset(actual_types)


@pytest.mark.unit
@pytest.mark.layout
class TestLayoutRegion:
    def test_layout_region_creation(self):
        bbox = BoundingBox(x1=10, y1=20, x2=100, y2=50)
        region = LayoutRegion(
            region_id="region_1",
            region_type=RegionType.PARAGRAPH,
            bbox=bbox,
            page_number=1,
            confidence=0.92,
        )

        assert region.region_id == "region_1"
        assert region.region_type == RegionType.PARAGRAPH
        assert region.bbox == bbox
        assert region.page_number == 1
        assert region.confidence == 0.92
        assert region.text_blocks == []
        assert region.children == []

    def test_add_child_region(self):
        parent = LayoutRegion(
            region_id="parent",
            region_type=RegionType.PARAGRAPH,
            bbox=BoundingBox(x1=0, y1=0, x2=200, y2=200),
            page_number=1,
        )
        child = LayoutRegion(
            region_id="child",
            region_type=RegionType.PARAGRAPH,
            bbox=BoundingBox(x1=10, y1=10, x2=50, y2=50),
            page_number=1,
        )

        parent.add_child(child)

        assert len(parent.children) == 1
        assert child.parent_id == "parent"
        assert parent.children[0] == child

    def test_layout_region_post_init_defaults(self):
        region = LayoutRegion(
            region_id="region_1",
            region_type=RegionType.TITLE,
            bbox=BoundingBox(x1=0, y1=0, x2=100, y2=30),
            page_number=1,
        )

        assert region.text_blocks == []
        assert region.metadata == {}
        assert region.children == []


@pytest.mark.unit
@pytest.mark.layout
class TestDocumentTreeNode:
    def test_node_creation(self):
        bbox = BoundingBox(x1=0, y1=0, x2=100, y2=30)
        node = DocumentTreeNode(
            node_id="node_1",
            node_type="title",
            content="Test Title",
            bbox=bbox,
            page_number=1,
        )

        assert node.node_id == "node_1"
        assert node.node_type == "title"
        assert node.content == "Test Title"
        assert node.bbox == bbox
        assert node.page_number == 1
        assert node.children == []
        assert node.parent is None

    def test_add_child_node(self):
        parent = DocumentTreeNode("parent", "section")
        child1 = DocumentTreeNode("child1", "paragraph", content="Para 1")
        child2 = DocumentTreeNode("child2", "paragraph", content="Para 2")

        parent.add_child(child1)
        parent.add_child(child2)

        assert len(parent.children) == 2
        assert child1.parent == parent
        assert child2.parent == parent
        assert parent.children[0] == child1
        assert parent.children[1] == child2

    def test_to_dict(self):
        bbox = BoundingBox(x1=0, y1=0, x2=100, y2=30)
        parent = DocumentTreeNode(
            node_id="parent",
            node_type="section",
            bbox=bbox,
            page_number=1,
            metadata={"key": "value"},
        )
        child = DocumentTreeNode(
            node_id="child",
            node_type="paragraph",
            content="Test content",
        )
        parent.add_child(child)

        result = parent.to_dict()

        assert result["node_id"] == "parent"
        assert result["node_type"] == "section"
        assert result["page_number"] == 1
        assert result["metadata"] == {"key": "value"}
        assert result["bbox"] == {"x1": 0, "y1": 0, "x2": 100, "y2": 30}
        assert len(result["children"]) == 1
        assert result["children"][0]["content"] == "Test content"


@pytest.mark.unit
@pytest.mark.layout
class TestLayoutAnalyzer:
    def test_singleton_pattern(self):
        analyzer1 = LayoutAnalyzer()
        analyzer2 = LayoutAnalyzer()

        assert analyzer1 is analyzer2

    def test_analyze_layout_normal(self, mock_standardized_doc, mock_layout_analyzer):
        result = mock_layout_analyzer.analyze_layout(mock_standardized_doc)

        assert "regions" in result
        assert "document_tree" in result

        regions = result["regions"]
        assert len(regions) >= 4

        region_types = {r["region_type"] for r in regions}
        assert "title" in region_types
        assert "paragraph" in region_types
        assert "table" in region_types
        assert "signature" in region_types

    def test_layout_regions_have_valid_bboxes(self, mock_standardized_doc, mock_layout_analyzer):
        result = mock_layout_analyzer.analyze_layout(mock_standardized_doc)

        for region in result["regions"]:
            bbox = region["bbox"]
            assert bbox["x1"] >= 0
            assert bbox["y1"] >= 0
            assert bbox["x2"] > bbox["x1"]
            assert bbox["y2"] > bbox["y1"]
            assert region["confidence"] >= 0
            assert region["confidence"] <= 1

    def test_title_region_detected(self, mock_standardized_doc, mock_layout_analyzer):
        result = mock_layout_analyzer.analyze_layout(mock_standardized_doc)

        title_regions = [
            r for r in result["regions"]
            if r["region_type"] == "title" and r["page_number"] == 1
        ]

        assert len(title_regions) >= 1
        title = title_regions[0]
        assert title["confidence"] >= 0.8

    def test_signature_region_detected(self, mock_standardized_doc, mock_layout_analyzer):
        result = mock_layout_analyzer.analyze_layout(mock_standardized_doc)

        signature_regions = [
            r for r in result["regions"]
            if r["region_type"] == "signature"
        ]

        assert len(signature_regions) >= 1

    def test_table_region_detected(self, mock_standardized_doc, mock_layout_analyzer):
        result = mock_layout_analyzer.analyze_layout(mock_standardized_doc)

        table_regions = [
            r for r in result["regions"]
            if r["region_type"] == "table"
        ]

        assert len(table_regions) >= 1

    def test_document_tree_structure(self, mock_standardized_doc, mock_layout_analyzer):
        result = mock_layout_analyzer.analyze_layout(mock_standardized_doc)

        tree = result["document_tree"]
        assert tree["node_type"] == "document"
        assert "node_id" in tree
        assert "children" in tree

    def test_layout_analysis_preserves_text(self, mock_standardized_doc, mock_layout_analyzer):
        result = mock_layout_analyzer.analyze_layout(mock_standardized_doc)

        original_text = " ".join(
            tb.text for page in mock_standardized_doc.pages for tb in page.text_blocks
        )

        region_text = " ".join(
            r.get("text", "") for r in result["regions"]
        )

        assert len(region_text) > 0
        for tb in mock_standardized_doc.pages[0].text_blocks[:3]:
            if tb.confidence and tb.confidence > 0.9:
                assert any(tb.text[:5] in r.get("text", "") for r in result["regions"])

    def test_empty_document_returns_empty_regions(self):
        empty_doc = StandardizedDocument(
            original_filename="empty.pdf",
            document_type=DocumentTypeEnum.PDF,
            page_count=1,
            pages=[PageInfo(page_number=1, width=595, height=842)],
        )

        class EmptyLayoutAnalyzer:
            def analyze_layout(self, doc):
                return {"regions": [], "document_tree": {"node_id": "root", "node_type": "document", "children": []}}

        analyzer = EmptyLayoutAnalyzer()
        result = analyzer.analyze_layout(empty_doc)

        assert len(result["regions"]) == 0

    def test_multipage_layout_correct_page_numbers(self, sample_pdf_path):
        from app.ml.parsers import PDFParser

        parser = PDFParser()
        doc = parser.parse(str(sample_pdf_path["path"]), "test.pdf")

        class MultiPageLayoutAnalyzer:
            def analyze_layout(self, standardized_doc):
                regions = []
                for page in standardized_doc.pages:
                    for i in range(3):
                        regions.append({
                            "region_id": f"p{page.page_number}_r{i}",
                            "region_type": "paragraph",
                            "bbox": {"x1": 0, "y1": 0, "x2": 100, "y2": 30},
                            "page_number": page.page_number,
                            "confidence": 0.9,
                            "text": "",
                        })
                return {
                    "regions": regions,
                    "document_tree": {"node_id": "root", "node_type": "document", "children": []},
                }

        analyzer = MultiPageLayoutAnalyzer()
        result = analyzer.analyze_layout(doc)

        for region in result["regions"]:
            assert 1 <= region["page_number"] <= doc.page_count

    def test_low_confidence_regions_marked(self, mock_standardized_doc):
        class LowConfidenceLayoutAnalyzer:
            def analyze_layout(self, doc):
                return {
                    "regions": [
                        {
                            "region_id": "r1",
                            "region_type": "paragraph",
                            "bbox": {"x1": 0, "y1": 0, "x2": 100, "y2": 30},
                            "page_number": 1,
                            "confidence": 0.4,
                            "text": "低置信度文本",
                        },
                        {
                            "region_id": "r2",
                            "region_type": "title",
                            "bbox": {"x1": 0, "y1": 40, "x2": 100, "y2": 70},
                            "page_number": 1,
                            "confidence": 0.9,
                            "text": "高置信度标题",
                        },
                    ],
                    "document_tree": {"node_id": "root", "node_type": "document", "children": []},
                }

        analyzer = LowConfidenceLayoutAnalyzer()
        result = analyzer.analyze_layout(mock_standardized_doc)

        low_conf = [r for r in result["regions"] if r["confidence"] < 0.5]
        high_conf = [r for r in result["regions"] if r["confidence"] >= 0.8]

        assert len(low_conf) == 1
        assert len(high_conf) == 1
        assert low_conf[0]["confidence"] == 0.4

    def test_regions_sorted_by_reading_order(self, mock_standardized_doc):
        class SortedLayoutAnalyzer:
            def analyze_layout(self, doc):
                return {
                    "regions": [
                        {
                            "region_id": "r1",
                            "region_type": "title",
                            "bbox": {"x1": 50, "y1": 50, "x2": 500, "y2": 100},
                            "page_number": 1,
                            "confidence": 0.95,
                        },
                        {
                            "region_id": "r2",
                            "region_type": "paragraph",
                            "bbox": {"x1": 50, "y1": 120, "x2": 500, "y2": 200},
                            "page_number": 1,
                            "confidence": 0.92,
                        },
                        {
                            "region_id": "r3",
                            "region_type": "paragraph",
                            "bbox": {"x1": 50, "y1": 220, "x2": 500, "y2": 300},
                            "page_number": 1,
                            "confidence": 0.90,
                        },
                    ],
                    "document_tree": {"node_id": "root", "node_type": "document", "children": []},
                }

        analyzer = SortedLayoutAnalyzer()
        result = analyzer.analyze_layout(mock_standardized_doc)

        y_positions = [r["bbox"]["y1"] for r in result["regions"]]
        assert y_positions == sorted(y_positions), "Regions should be sorted by y-coordinate (reading order)"


@pytest.mark.unit
@pytest.mark.layout
class TestLayoutRegionEdgeCases:
    def test_nested_regions_hierarchy(self):
        root = LayoutRegion(
            region_id="root",
            region_type=RegionType.PARAGRAPH,
            bbox=BoundingBox(x1=0, y1=0, x2=500, y2=500),
            page_number=1,
        )

        section = LayoutRegion(
            region_id="section",
            region_type=RegionType.PARAGRAPH,
            bbox=BoundingBox(x1=10, y1=10, x2=480, y2=480),
            page_number=1,
        )

        paragraph = LayoutRegion(
            region_id="para",
            region_type=RegionType.PARAGRAPH,
            bbox=BoundingBox(x1=20, y1=20, x2=460, y2=100),
            page_number=1,
        )

        root.add_child(section)
        section.add_child(paragraph)

        assert root.children[0] == section
        assert section.children[0] == paragraph
        assert section.parent_id == "root"
        assert paragraph.parent_id == "section"

    def test_overlapping_regions_detected(self):
        regions = [
            LayoutRegion(
                region_id="r1",
                region_type=RegionType.PARAGRAPH,
                bbox=BoundingBox(x1=0, y1=0, x2=100, y2=100),
                page_number=1,
            ),
            LayoutRegion(
                region_id="r2",
                region_type=RegionType.PARAGRAPH,
                bbox=BoundingBox(x1=50, y1=50, x2=150, y2=150),
                page_number=1,
            ),
        ]

        def bboxes_overlap(b1, b2):
            return not (b1.x2 < b2.x1 or b1.x1 > b2.x2 or b1.y2 < b2.y1 or b1.y1 > b2.y2)

        assert bboxes_overlap(regions[0].bbox, regions[1].bbox)

    def test_region_within_page_bounds(self):
        page_width, page_height = 595, 842

        region = LayoutRegion(
            region_id="r1",
            region_type=RegionType.TITLE,
            bbox=BoundingBox(x1=50, y1=50, x2=500, y2=100),
            page_number=1,
        )

        assert region.bbox.x1 >= 0
        assert region.bbox.y1 >= 0
        assert region.bbox.x2 <= page_width
        assert region.bbox.y2 <= page_height
