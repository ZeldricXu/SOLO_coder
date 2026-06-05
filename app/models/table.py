from sqlalchemy import Column, Integer, String, Text, Float, Boolean, JSON, ForeignKey
from sqlalchemy.orm import relationship

from app.models.base import BaseModel, TimestampMixin


class TableStructure(BaseModel, TimestampMixin):
    __tablename__ = "table_structures"

    document_id = Column(Integer, ForeignKey("documents.id"), nullable=False, index=True)
    page_number = Column(Integer, nullable=False, index=True)

    table_index = Column(Integer, default=0)
    table_id = Column(String(128), index=True)

    bounding_box = Column(JSON)
    confidence = Column(Float, default=0.0)

    row_count = Column(Integer, default=0)
    col_count = Column(Integer, default=0)
    has_header = Column(Boolean, default=False)
    has_merged_cells = Column(Boolean, default=False)
    is_spanning_pages = Column(Boolean, default=False)

    raw_detection = Column(JSON)
    structure_json = Column(JSON)

    caption = Column(Text)
    footer = Column(Text)
    table_type = Column(String(128))

    document = relationship("Document", back_populates="tables")
    cells = relationship(
        "TableCell",
        back_populates="table",
        cascade="all, delete-orphan",
        lazy="dynamic",
    )


class TableCell(BaseModel, TimestampMixin):
    __tablename__ = "table_cells"

    table_id = Column(Integer, ForeignKey("table_structures.id"), nullable=False, index=True)

    row_index = Column(Integer, nullable=False)
    col_index = Column(Integer, nullable=False)
    row_span = Column(Integer, default=1)
    col_span = Column(Integer, default=1)

    is_header = Column(Boolean, default=False)
    is_merged = Column(Boolean, default=False)

    text = Column(Text)
    normalized_text = Column(Text)
    confidence = Column(Float, default=0.0)

    bounding_box = Column(JSON)
    cell_html = Column(Text)

    data_type = Column(String(64))
    value = Column(JSON)

    table = relationship("TableStructure", back_populates="cells")
