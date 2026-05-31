from datetime import datetime
from difflib import SequenceMatcher, unified_diff
from typing import Any, Dict, List, Optional, Tuple

from sqlalchemy import select, and_, or_
from sqlalchemy.ext.asyncio import AsyncSession

from core.exceptions import ValidationError, NotFoundError
from core.utils import validate_params, generate_id, utc_now
from .models import (
    DocumentVersion,
    DocumentDiffResult,
    KeyClauseDefinition,
    DocumentCreate,
    DocumentVersionResponse,
    DocumentDiffRequest,
    DocumentDiffResponse,
    ChangeDetail,
    ChangeSummary,
    HighlightedChange,
    ClauseDefinitionCreate,
    ClauseDefinitionResponse,
    ChangeType,
    ChangeSeverity,
    DocumentType,
    DiffAlgorithm,
)


class TextDiffService:
    def __init__(self):
        pass

    def line_diff(
        self, original_text: str, modified_text: str
    ) -> List[Dict[str, Any]]:
        original_lines = original_text.splitlines()
        modified_lines = modified_text.splitlines()

        matcher = SequenceMatcher(None, original_lines, modified_lines)
        diffs = []

        for tag, i1, i2, j1, j2 in matcher.get_opcodes():
            if tag == "equal":
                continue

            change_type = self._map_tag_to_change_type(tag)

            for idx in range(i1, i2):
                diffs.append(
                    {
                        "change_id": generate_id("chg"),
                        "change_type": change_type,
                        "line_number": idx + 1,
                        "original_text": original_lines[idx] if tag in ["delete", "replace"] else "",
                        "modified_text": "",
                        "position": idx,
                        "length": len(original_lines[idx]) if tag in ["delete", "replace"] else 0,
                    }
                )

            for idx in range(j1, j2):
                diffs.append(
                    {
                        "change_id": generate_id("chg"),
                        "change_type": change_type,
                        "line_number": idx + 1,
                        "original_text": "",
                        "modified_text": modified_lines[idx] if tag in ["insert", "replace"] else "",
                        "position": idx,
                        "length": len(modified_lines[idx]) if tag in ["insert", "replace"] else 0,
                    }
                )

        return diffs

    def word_diff(
        self, original_text: str, modified_text: str
    ) -> List[Dict[str, Any]]:
        def tokenize(text: str) -> List[str]:
            tokens = []
            current = ""
            for char in text:
                if char.isspace():
                    if current:
                        tokens.append(current)
                        current = ""
                    tokens.append(char)
                elif char in ".,;:!?()[]{}'\"":
                    if current:
                        tokens.append(current)
                        current = ""
                    tokens.append(char)
                else:
                    current += char
            if current:
                tokens.append(current)
            return tokens

        original_tokens = tokenize(original_text)
        modified_tokens = tokenize(modified_text)

        matcher = SequenceMatcher(None, original_tokens, modified_tokens)
        diffs = []
        pos = 0

        for tag, i1, i2, j1, j2 in matcher.get_opcodes():
            if tag == "equal":
                pos += sum(len(t) for t in original_tokens[i1:i2])
                continue

            change_type = self._map_tag_to_change_type(tag)

            original_text_segment = "".join(original_tokens[i1:i2])
            modified_text_segment = "".join(modified_tokens[j1:j2])

            diffs.append(
                {
                    "change_id": generate_id("chg"),
                    "change_type": change_type,
                    "position": pos,
                    "original_text": original_text_segment,
                    "modified_text": modified_text_segment,
                    "length": max(len(original_text_segment), len(modified_text_segment)),
                }
            )

            pos += len(modified_text_segment)

        return diffs

    def calculate_similarity(self, text1: str, text2: str) -> float:
        return SequenceMatcher(None, text1, text2).ratio()

    def _map_tag_to_change_type(self, tag: str) -> ChangeType:
        mapping = {
            "insert": ChangeType.INSERT,
            "delete": ChangeType.DELETE,
            "replace": ChangeType.MODIFY,
            "equal": ChangeType.UNCHANGED,
        }
        return mapping.get(tag, ChangeType.UNCHANGED)


class KeyClauseService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def detect_key_clauses(
        self, text: str, document_type: DocumentType, tenant_id: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        query = select(KeyClauseDefinition).where(
            KeyClauseDefinition.document_type == document_type,
            KeyClauseDefinition.is_active == True,
        )
        if tenant_id:
            query = query.where(
                or_(KeyClauseDefinition.tenant_id == tenant_id, KeyClauseDefinition.tenant_id == None)
            )

        result = await self.db.execute(query)
        clause_definitions = result.scalars().all()

        detected_clauses = []

        for clause_def in clause_definitions:
            matches = self._find_clause_matches(text, clause_def)
            for match in matches:
                detected_clauses.append(
                    {
                        "clause_id": clause_def.clause_id,
                        "name": clause_def.name,
                        "severity": clause_def.severity,
                        "start_position": match["start"],
                        "end_position": match["end"],
                        "matched_text": match["text"],
                    }
                )

        return detected_clauses

    def _find_clause_matches(
        self, text: str, clause_def: KeyClauseDefinition
    ) -> List[Dict[str, Any]]:
        matches = []
        text_lower = text.lower()

        for keyword in clause_def.keywords:
            pos = 0
            keyword_lower = keyword.lower()
            while True:
                idx = text_lower.find(keyword_lower, pos)
                if idx == -1:
                    break

                context_start = max(0, idx - 50)
                context_end = min(len(text), idx + len(keyword) + 50)

                matches.append(
                    {
                        "start": idx,
                        "end": idx + len(keyword),
                        "text": text[context_start:context_end],
                        "keyword": keyword,
                    }
                )
                pos = idx + 1

        return matches


class DocumentDiffService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.text_diff_service = TextDiffService()
        self.clause_service = KeyClauseService(db)

    async def create_document_version(
        self, doc_data: DocumentCreate
    ) -> DocumentVersionResponse:
        validation_rules = {
            "document_id": lambda x: x is not None and len(x) > 0,
            "title": lambda x: x is not None and len(x.strip()) > 0,
            "content": lambda x: x is not None,
        }
        validate_params(doc_data.model_dump(), validation_rules)

        query = select(DocumentVersion).where(
            DocumentVersion.document_id == doc_data.document_id,
            DocumentVersion.tenant_id == doc_data.tenant_id,
        ).order_by(DocumentVersion.version_number.desc())
        result = await self.db.execute(query)
        latest = result.scalar_one_or_none()

        version_number = latest.version_number + 1 if latest else 1

        doc_version = DocumentVersion(
            **doc_data.model_dump(),
            version_number=version_number,
        )
        self.db.add(doc_version)
        await self.db.flush()

        return DocumentVersionResponse.model_validate(doc_version)

    async def get_document_version(
        self, version_id: str, tenant_id: Optional[str] = None, include_content: bool = True
    ) -> DocumentVersionResponse:
        query = select(DocumentVersion).where(DocumentVersion.version_id == version_id)
        if tenant_id:
            query = query.where(DocumentVersion.tenant_id == tenant_id)

        result = await self.db.execute(query)
        doc_version = result.scalar_one_or_none()

        if not doc_version:
            raise NotFoundError(f"文档版本 {version_id} 不存在")

        response = DocumentVersionResponse.model_validate(doc_version)
        if not include_content:
            response.content = None

        return response

    async def list_document_versions(
        self,
        document_id: str,
        tenant_id: Optional[str] = None,
        limit: int = 50,
        offset: int = 0,
    ) -> List[DocumentVersionResponse]:
        query = select(DocumentVersion).where(DocumentVersion.document_id == document_id)
        if tenant_id:
            query = query.where(DocumentVersion.tenant_id == tenant_id)

        query = query.order_by(DocumentVersion.version_number.desc()).limit(limit).offset(offset)
        result = await self.db.execute(query)
        versions = result.scalars().all()

        return [DocumentVersionResponse.model_validate(v) for v in versions]

    async def compare_documents(
        self, diff_request: DocumentDiffRequest
    ) -> DocumentDiffResponse:
        base_doc = await self.get_document_version(
            diff_request.base_version_id, diff_request.tenant_id
        )
        compare_doc = await self.get_document_version(
            diff_request.compare_version_id, diff_request.tenant_id
        )

        base_content = base_doc.content or ""
        compare_content = compare_doc.content or ""

        if diff_request.algorithm == DiffAlgorithm.LINE_DIFF:
            diffs = self.text_diff_service.line_diff(base_content, compare_content)
        else:
            diffs = self.text_diff_service.word_diff(base_content, compare_content)

        similarity_score = self.text_diff_service.calculate_similarity(
            base_content, compare_content
        )

        change_details = await self._enrich_changes_with_severity(
            diffs, base_doc, compare_doc, diff_request.detect_clause_changes
        )

        summary = self._generate_change_summary(
            change_details, similarity_score, base_doc, compare_doc
        )

        highlighted_html = None
        if diff_request.highlight_changes:
            highlighted_html = self._generate_highlighted_html(
                base_content, compare_content, change_details
            )

        clause_changes = []
        if diff_request.detect_clause_changes:
            clause_changes = await self._detect_clause_changes(
                base_content, compare_content, base_doc.document_type, diff_request.tenant_id
            )

        insertions = sum(1 for c in change_details if c.change_type == ChangeType.INSERT)
        deletions = sum(1 for c in change_details if c.change_type == ChangeType.DELETE)
        modifications = sum(1 for c in change_details if c.change_type == ChangeType.MODIFY)
        critical_changes = [c for c in change_details if c.severity == ChangeSeverity.CRITICAL]

        diff_result = DocumentDiffResult(
            base_version_id=diff_request.base_version_id,
            compare_version_id=diff_request.compare_version_id,
            algorithm=diff_request.algorithm,
            line_diffs=diffs,
            word_diffs=diffs if diff_request.algorithm == DiffAlgorithm.WORD_DIFF else [],
            highlighted_changes=[c.model_dump() for c in change_details],
            clause_changes=clause_changes,
            change_summary=summary.model_dump(),
            similarity_score=similarity_score,
            insertions_count=insertions,
            deletions_count=deletions,
            modifications_count=modifications,
            critical_changes=[c.model_dump() for c in critical_changes],
            tenant_id=diff_request.tenant_id,
            created_by=diff_request.created_by,
        )
        self.db.add(diff_result)
        await self.db.flush()

        return DocumentDiffResponse(
            diff_id=diff_result.diff_id,
            base_version_id=diff_request.base_version_id,
            compare_version_id=diff_request.compare_version_id,
            algorithm=diff_request.algorithm,
            changes=change_details,
            highlighted_html=highlighted_html,
            summary=summary,
            clause_changes=clause_changes,
            created_at=utc_now(),
        )

    async def _enrich_changes_with_severity(
        self,
        diffs: List[Dict[str, Any]],
        base_doc: DocumentVersionResponse,
        compare_doc: DocumentVersionResponse,
        detect_clauses: bool,
    ) -> List[ChangeDetail]:
        change_details = []

        for diff in diffs:
            severity = self._assess_change_severity(diff)

            detail = ChangeDetail(
                change_id=diff["change_id"],
                change_type=diff["change_type"],
                severity=severity,
                line_number=diff.get("line_number"),
                position=diff.get("position"),
                length=diff.get("length"),
                original_text=diff["original_text"],
                modified_text=diff["modified_text"],
            )

            if detect_clauses:
                for clause in base_doc.key_clauses:
                    pos = diff.get("position", 0)
                    clause_start = clause.get("start_position", 0)
                    clause_end = clause.get("end_position", 0)
                    if clause_start <= pos <= clause_end:
                        detail.clause_reference = clause.get("clause_id")
                        detail.is_key_clause = True
                        detail.severity = max(
                            [severity, ChangeSeverity(clause.get("severity", severity))],
                            key=lambda x: ["minor", "moderate", "major", "critical"].index(x),
                        )
                        break

            change_details.append(detail)

        return change_details

    def _assess_change_severity(self, diff: Dict[str, Any]) -> ChangeSeverity:
        original_text = diff.get("original_text", "")
        modified_text = diff.get("modified_text", "")
        total_length = len(original_text) + len(modified_text)

        if total_length > 500:
            return ChangeSeverity.CRITICAL
        elif total_length > 200:
            return ChangeSeverity.MAJOR
        elif total_length > 50:
            return ChangeSeverity.MODERATE
        else:
            return ChangeSeverity.MINOR

    def _generate_change_summary(
        self,
        changes: List[ChangeDetail],
        similarity_score: float,
        base_doc: DocumentVersionResponse,
        compare_doc: DocumentVersionResponse,
    ) -> ChangeSummary:
        insertions = sum(1 for c in changes if c.change_type == ChangeType.INSERT)
        deletions = sum(1 for c in changes if c.change_type == ChangeType.DELETE)
        modifications = sum(1 for c in changes if c.change_type == ChangeType.MODIFY)

        critical = sum(1 for c in changes if c.severity == ChangeSeverity.CRITICAL)
        major = sum(1 for c in changes if c.severity == ChangeSeverity.MAJOR)
        moderate = sum(1 for c in changes if c.severity == ChangeSeverity.MODERATE)
        minor = sum(1 for c in changes if c.severity == ChangeSeverity.MINOR)

        affected_clauses = list({c.clause_reference for c in changes if c.clause_reference})

        summary_parts = []
        if insertions > 0:
            summary_parts.append(f"新增 {insertions} 处")
        if deletions > 0:
            summary_parts.append(f"删除 {deletions} 处")
        if modifications > 0:
            summary_parts.append(f"修改 {modifications} 处")
        if critical > 0:
            summary_parts.append(f"关键变更 {critical} 处")

        similarity_text = f"相似度 {similarity_score:.1%}"
        summary_text = f"文档版本比较结果：{', '.join(summary_parts) if summary_parts else '无显著变更'}。{similarity_text}。"

        return ChangeSummary(
            total_changes=len(changes),
            insertions=insertions,
            deletions=deletions,
            modifications=modifications,
            similarity_score=similarity_score,
            critical_changes=critical,
            major_changes=major,
            moderate_changes=moderate,
            minor_changes=minor,
            affected_clauses=affected_clauses,
            summary_text=summary_text,
        )

    def _generate_highlighted_html(
        self, base_text: str, compare_text: str, changes: List[ChangeDetail]
    ) -> str:
        insertions_html = []
        deletions_html = []

        for change in sorted(changes, key=lambda c: c.position or 0):
            if change.change_type == ChangeType.INSERT:
                insertions_html.append(
                    f'<span class="diff-insert" title="新增">+'
                    f'{self._escape_html(change.modified_text)}</span>'
                )
            elif change.change_type == ChangeType.DELETE:
                deletions_html.append(
                    f'<span class="diff-delete" title="删除">-'
                    f'{self._escape_html(change.original_text)}</span>'
                )
            elif change.change_type == ChangeType.MODIFY:
                deletions_html.append(
                    f'<span class="diff-delete" title="删除">-'
                    f'{self._escape_html(change.original_text)}</span>'
                )
                insertions_html.append(
                    f'<span class="diff-insert" title="新增">+'
                    f'{self._escape_html(change.modified_text)}</span>'
                )

        html = f"""
        <div class="diff-container">
            <style>
                .diff-insert {{ background-color: #e6ffed; color: #22863a; padding: 2px 4px; }}
                .diff-delete {{ background-color: #ffeef0; color: #b31d28; padding: 2px 4px; text-decoration: line-through; }}
                .diff-container {{ font-family: monospace; white-space: pre-wrap; line-height: 1.6; }}
            </style>
            <div class="diff-changes">
                <h4>变更详情</h4>
                <div class="deletions">
                    <h5>删除内容</h5>
                    {''.join(deletions_html) if deletions_html else '<em>无删除</em>'}
                </div>
                <div class="insertions">
                    <h5>新增内容</h5>
                    {''.join(insertions_html) if insertions_html else '<em>无新增</em>'}
                </div>
            </div>
        </div>
        """
        return html

    def _escape_html(self, text: str) -> str:
        return (
            text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace('"', "&quot;")
        )

    async def _detect_clause_changes(
        self,
        base_text: str,
        compare_text: str,
        document_type: DocumentType,
        tenant_id: Optional[str],
    ) -> List[Dict[str, Any]]:
        base_clauses = await self.clause_service.detect_key_clauses(
            base_text, document_type, tenant_id
        )
        compare_clauses = await self.clause_service.detect_key_clauses(
            compare_text, document_type, tenant_id
        )

        clause_changes = []

        base_clause_ids = {c["clause_id"] for c in base_clauses}
        compare_clause_ids = {c["clause_id"] for c in compare_clauses}

        for clause_id in base_clause_ids & compare_clause_ids:
            base_clause = next(c for c in base_clauses if c["clause_id"] == clause_id)
            compare_clause = next(c for c in compare_clauses if c["clause_id"] == clause_id)

            if base_clause["matched_text"] != compare_clause["matched_text"]:
                clause_changes.append(
                    {
                        "clause_id": clause_id,
                        "name": base_clause["name"],
                        "severity": base_clause["severity"],
                        "change_type": "modified",
                        "original_text": base_clause["matched_text"],
                        "modified_text": compare_clause["matched_text"],
                    }
                )

        for clause_id in base_clause_ids - compare_clause_ids:
            clause = next(c for c in base_clauses if c["clause_id"] == clause_id)
            clause_changes.append(
                {
                    "clause_id": clause_id,
                    "name": clause["name"],
                    "severity": clause["severity"],
                    "change_type": "deleted",
                    "original_text": clause["matched_text"],
                    "modified_text": "",
                }
            )

        for clause_id in compare_clause_ids - base_clause_ids:
            clause = next(c for c in compare_clauses if c["clause_id"] == clause_id)
            clause_changes.append(
                {
                    "clause_id": clause_id,
                    "name": clause["name"],
                    "severity": clause["severity"],
                    "change_type": "inserted",
                    "original_text": "",
                    "modified_text": clause["matched_text"],
                }
            )

        return clause_changes

    async def create_clause_definition(
        self, clause_data: ClauseDefinitionCreate
    ) -> ClauseDefinitionResponse:
        validation_rules = {
            "name": lambda x: x is not None and len(x.strip()) > 0,
            "document_type": lambda x: x is not None,
        }
        validate_params(clause_data.model_dump(), validation_rules)

        clause = KeyClauseDefinition(**clause_data.model_dump())
        self.db.add(clause)
        await self.db.flush()

        return ClauseDefinitionResponse.model_validate(clause)

    async def list_clause_definitions(
        self,
        document_type: Optional[DocumentType] = None,
        tenant_id: Optional[str] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[ClauseDefinitionResponse]:
        query = select(KeyClauseDefinition).where(KeyClauseDefinition.is_active == True)
        if document_type:
            query = query.where(KeyClauseDefinition.document_type == document_type)
        if tenant_id:
            query = query.where(
                or_(KeyClauseDefinition.tenant_id == tenant_id, KeyClauseDefinition.tenant_id == None)
            )

        query = query.order_by(KeyClauseDefinition.created_at.desc()).limit(limit).offset(offset)
        result = await self.db.execute(query)
        clauses = result.scalars().all()

        return [ClauseDefinitionResponse.model_validate(c) for c in clauses]
