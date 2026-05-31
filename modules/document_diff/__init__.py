from .models import (
    DocumentVersion,
    DocumentDiffResult,
    KeyClauseDefinition,
    ChangeType,
    ChangeSeverity,
    DocumentType,
    DiffAlgorithm,
    DocumentCreate,
    DocumentVersionResponse,
    DocumentDiffRequest,
    DocumentDiffResponse,
    ChangeDetail,
    ChangeSummary,
    HighlightedChange,
    ClauseDefinitionCreate,
    ClauseDefinitionResponse,
)
from .service import (
    TextDiffService,
    KeyClauseService,
    DocumentDiffService,
)
from .router import router

__all__ = [
    "DocumentVersion",
    "DocumentDiffResult",
    "KeyClauseDefinition",
    "ChangeType",
    "ChangeSeverity",
    "DocumentType",
    "DiffAlgorithm",
    "DocumentCreate",
    "DocumentVersionResponse",
    "DocumentDiffRequest",
    "DocumentDiffResponse",
    "ChangeDetail",
    "ChangeSummary",
    "HighlightedChange",
    "ClauseDefinitionCreate",
    "ClauseDefinitionResponse",
    "TextDiffService",
    "KeyClauseService",
    "DocumentDiffService",
    "router",
]


class DocumentDiffModule:
    name = "document_diff"
    description = "文本差异分析、关键条款高亮与变更摘要生成模块"
    router = router

    def __init__(self):
        pass
