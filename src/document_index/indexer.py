from __future__ import annotations

import logging
import re
import shutil
import tempfile
from typing import Any, Dict, List, Optional

from whoosh import index as whoosh_index
from whoosh.analysis import StemmingAnalyzer, StandardAnalyzer
from whoosh.fields import ID, TEXT, Schema, KEYWORD, DATETIME, STORED
from whoosh.qparser import MultifieldParser, QueryParser
from whoosh.query import And, Or, Term
from whoosh.highlight import Highlighter, FragmentScorer, ContextFragmenter

from src.common.utils import async_retry
from src.document_index.models import Document, SearchQuery, SearchResult, SearchResponse, DocumentStatus

logger = logging.getLogger(__name__)


class DocumentIndexer:
    def __init__(self, index_dir: Optional[str] = None) -> None:
        self.index_dir = index_dir or tempfile.mkdtemp(prefix="doc_index_")
        self.schema = self._create_schema()
        self._index: Optional[whoosh_index.Index] = None
        self._highlighter = Highlighter(
            fragmenter=ContextFragmenter(maxchars=200, surround=30),
            scorer=FragmentScorer(),
        )
        self._ensure_index()

    def _create_schema(self) -> Schema:
        return Schema(
            doc_id=ID(stored=True, unique=True),
            title=TEXT(stored=True, analyzer=StemmingAnalyzer()),
            content=TEXT(stored=True, analyzer=StemmingAnalyzer()),
            source=ID(stored=True),
            tags=KEYWORD(stored=True, commas=True),
            categories=KEYWORD(stored=True, commas=True),
            acl=KEYWORD(stored=True, commas=True),
            owner_id=ID(stored=True),
            created_at=DATETIME(stored=True),
            updated_at=DATETIME(stored=True),
            source_url=STORED,
        )

    def _ensure_index(self) -> None:
        import os
        if not os.path.exists(self.index_dir):
            os.makedirs(self.index_dir)
            self._index = whoosh_index.create_in(self.index_dir, self.schema)
        else:
            self._index = whoosh_index.open_dir(self.index_dir)

    @async_retry(max_attempts=3)
    async def index_document(self, document: Document) -> str:
        assert self._index is not None
        writer = self._index.writer()
        try:
            writer.update_document(
                doc_id=document.doc_id,
                title=document.title,
                content=document.content,
                source=document.source,
                tags=",".join(document.tags),
                categories=",".join(document.categories),
                acl=",".join(document.acl),
                owner_id=document.owner_id or "",
                created_at=document.created_at,
                updated_at=document.updated_at,
                source_url=document.source_url,
            )
            writer.commit()
            document.status = DocumentStatus.INDEXED
            logger.info(f"Indexed document: {document.doc_id}")
            return document.doc_id
        except Exception as e:
            writer.cancel()
            document.status = DocumentStatus.FAILED
            logger.error(f"Failed to index document {document.doc_id}: {e}")
            raise

    async def index_documents(self, documents: List[Document]) -> List[str]:
        results: List[str] = []
        for doc in documents:
            try:
                doc_id = await self.index_document(doc)
                results.append(doc_id)
            except Exception:
                pass
        return results

    async def delete_document(self, doc_id: str) -> bool:
        assert self._index is not None
        try:
            writer = self._index.writer()
            writer.delete_by_term("doc_id", doc_id)
            writer.commit()
            return True
        except Exception as e:
            logger.error(f"Failed to delete document {doc_id}: {e}")
            return False

    def _build_acl_filter(self, user_id: Optional[str], user_roles: List[str]) -> Optional[Or]:
        terms: List[Term] = []
        if user_id:
            terms.append(Term("acl", f"user:{user_id}"))
        for role in user_roles:
            terms.append(Term("acl", f"role:{role}"))
        terms.append(Term("acl", "public"))
        return Or(terms) if terms else None

    async def search(self, query: SearchQuery) -> SearchResponse:
        import time
        assert self._index is not None
        start_time = time.time()

        with self._index.searcher() as searcher:
            parser = MultifieldParser(["title", "content"], schema=self.schema)
            q = parser.parse(query.query)

            filters: List[Any] = []

            if query.tags:
                filters.append(Or([Term("tags", tag) for tag in query.tags]))
            if query.categories:
                filters.append(Or([Term("categories", cat) for cat in query.categories]))
            if query.sources:
                filters.append(Or([Term("source", src.value) for src in query.sources]))

            acl_filter = self._build_acl_filter(query.user_id, query.user_roles)
            if acl_filter is not None:
                filters.append(acl_filter)

            combined_filter = And(filters) if filters else None

            offset = (query.page - 1) * query.page_size
            results = searcher.search(q, limit=offset + query.page_size, filter=combined_filter)

            total = len(results)
            page_results = results[offset:offset + query.page_size]

            search_results: List[SearchResult] = []
            for hit in page_results:
                highlighted = None
                try:
                    highlighted = {
                        "title": list(self._highlighter.hit_phrases(hit, "title")),
                        "content": list(self._highlighter.hit_phrases(hit, "content")),
                    }
                except Exception:
                    pass

                snippet = hit.highlights("content", top=3) or (hit["content"][:200] + "...")

                search_results.append(SearchResult(
                    doc_id=hit["doc_id"],
                    title=hit["title"],
                    snippet=snippet,
                    source=hit["source"],
                    score=hit.score,
                    tags=hit.get("tags", "").split(",") if hit.get("tags") else [],
                    source_url=hit.get("source_url"),
                    highlighted=highlighted,
                ))

            processing_time = (time.time() - start_time) * 1000

            return SearchResponse(
                results=search_results,
                total=total,
                page=query.page,
                page_size=query.page_size,
                processing_time_ms=processing_time,
            )

    def get_stats(self) -> Dict[str, Any]:
        assert self._index is not None
        with self._index.searcher() as searcher:
            return {
                "total_documents": searcher.doc_count(),
                "index_dir": self.index_dir,
            }

    def close(self) -> None:
        if self._index is not None:
            self._index.close()
        shutil.rmtree(self.index_dir, ignore_errors=True)
