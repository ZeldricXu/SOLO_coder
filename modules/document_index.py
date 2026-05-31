import os
import re
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set
from datetime import datetime
from enum import Enum
from abc import ABC, abstractmethod
from .logging_module import get_logger

logger = get_logger(__name__)


class DocumentSource(str, Enum):
    CONFLUENCE = "confluence"
    GITHUB = "github"
    NOTION = "notion"
    LOCAL = "local"
    WEB = "web"


class DocumentType(str, Enum):
    GUIDE = "guide"
    API = "api"
    README = "readme"
    ARCHITECTURE = "architecture"
    DESIGN = "design"
    MEETING = "meeting"


@dataclass
class Document:
    doc_id: str
    title: str
    content: str
    source: DocumentSource
    doc_type: DocumentType
    url: Optional[str] = None
    author: Optional[str] = None
    created_at: datetime = field(default_factory=datetime.utcnow)
    tags: List[str] = field(default_factory=list)
    permissions: List[str] = field(default_factory=lambda: ["public"])


@dataclass
class SearchResult:
    doc_id: str
    title: str
    score: float
    snippet: str
    tags: List[str]
    source: DocumentSource


class DocumentFetcher(ABC):
    @abstractmethod
    def fetch(self, config: Dict[str, Any]) -> List[Document]:
        pass


class LocalFileFetcher(DocumentFetcher):
    def fetch(self, config: Dict[str, Any]) -> List[Document]:
        directory = config.get("directory", "./docs")
        documents: List[Document] = []
        if not os.path.exists(directory):
            return documents

        for root, _, files in os.walk(directory):
            for filename in files:
                if filename.endswith((".md", ".txt")):
                    filepath = os.path.join(root, filename)
                    try:
                        with open(filepath, "r", encoding="utf-8") as f:
                            content = f.read()
                        title = self._extract_title(content, filename)
                        documents.append(Document(
                            doc_id=f"doc_{uuid.uuid4().hex[:8]}",
                            title=title,
                            content=content,
                            source=DocumentSource.LOCAL,
                            doc_type=DocumentType.GUIDE,
                            url=f"file://{filepath}",
                        ))
                    except Exception as e:
                        logger.error(f"Error reading {filepath}: {e}")
        return documents

    def _extract_title(self, content: str, filename: str) -> str:
        match = re.search(r'^#\s+(.+)$', content, re.MULTILINE)
        if match:
            return match.group(1).strip()
        return os.path.splitext(filename)[0]


class SimpleSearchEngine:
    def __init__(self):
        self._documents: Dict[str, Document] = {}
        self._index: Dict[str, List[str]] = {}
        self._stop_words: Set[str] = {
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "is", "are", "was", "were", "be", "been",
            "have", "has", "had", "do", "does", "did", "this", "that", "these", "those",
        }

    def index_document(self, doc: Document) -> None:
        self._documents[doc.doc_id] = doc
        tokens = self._tokenize(doc.title + " " + doc.content)
        for token in set(tokens):
            if token not in self._index:
                self._index[token] = []
            self._index[token].append(doc.doc_id)
        logger.info(f"Indexed: {doc.doc_id} ({doc.title})")

    def _tokenize(self, text: str) -> List[str]:
        text = text.lower()
        text = re.sub(r'[^\w\s]', ' ', text)
        return [t for t in text.split() if t not in self._stop_words and len(t) > 2]

    def search(self, query: str, user_perms: Optional[List[str]] = None, limit: int = 10,
               source_filter: Optional[List[DocumentSource]] = None) -> List[SearchResult]:
        query_tokens = self._tokenize(query)
        if not query_tokens:
            return []

        doc_scores: Dict[str, float] = {}
        for token in query_tokens:
            if token in self._index:
                for doc_id in self._index[token]:
                    doc_scores[doc_id] = doc_scores.get(doc_id, 0) + 1

        results = []
        for doc_id, score in sorted(doc_scores.items(), key=lambda x: -x[1]):
            doc = self._documents.get(doc_id)
            if not doc:
                continue
            if user_perms and "public" not in doc.permissions and not any(p in doc.permissions for p in user_perms):
                continue
            if source_filter and doc.source not in source_filter:
                continue
            results.append(SearchResult(
                doc_id=doc_id, title=doc.title, score=score,
                snippet=self._snippet(doc.content, query_tokens),
                tags=doc.tags, source=doc.source,
            ))
            if len(results) >= limit:
                break
        return results

    def _snippet(self, content: str, tokens: List[str]) -> str:
        sentences = re.split(r'[.!?]', content)
        best = ""
        best_score = 0
        for s in sentences:
            score = sum(1 for t in tokens if t in s.lower())
            if score > best_score:
                best_score = score
                best = s.strip()
        return (best or sentences[0].strip())[:200]

    def get_document(self, doc_id: str, user_perms: Optional[List[str]] = None) -> Optional[Document]:
        doc = self._documents.get(doc_id)
        if not doc:
            return None
        if user_perms and "public" not in doc.permissions and not any(p in doc.permissions for p in user_perms):
            return None
        return doc

    def list_documents(self, user_perms: Optional[List[str]] = None) -> List[Document]:
        docs = list(self._documents.values())
        if user_perms:
            docs = [d for d in docs if "public" in d.permissions or any(p in d.permissions for p in user_perms)]
        return docs

    def delete_document(self, doc_id: str) -> bool:
        if doc_id not in self._documents:
            return False
        del self._documents[doc_id]
        for token, ids in list(self._index.items()):
            self._index[token] = [i for i in ids if i != doc_id]
            if not self._index[token]:
                del self._index[token]
        return True

    def get_stats(self) -> Dict[str, Any]:
        source_counts: Dict[str, int] = {}
        for doc in self._documents.values():
            source_counts[doc.source] = source_counts.get(doc.source, 0) + 1
        return {
            "total_documents": len(self._documents),
            "by_source": source_counts,
            "indexed_terms": len(self._index),
        }


class DocumentIndexManager:
    def __init__(self):
        self._engine = SimpleSearchEngine()
        self._fetchers: Dict[DocumentSource, DocumentFetcher] = {
            DocumentSource.LOCAL: LocalFileFetcher(),
        }

    def fetch_and_index(self, source: DocumentSource, config: Dict[str, Any]) -> int:
        fetcher = self._fetchers.get(source)
        if not fetcher:
            return 0
        docs = fetcher.fetch(config)
        for doc in docs:
            self._engine.index_document(doc)
        return len(docs)

    def index_document(self, doc: Document) -> None:
        self._engine.index_document(doc)

    def search(self, query: str, **kwargs) -> List[SearchResult]:
        return self._engine.search(query, **kwargs)

    def get_document(self, doc_id: str, **kwargs) -> Optional[Document]:
        return self._engine.get_document(doc_id, **kwargs)

    def list_documents(self, **kwargs) -> List[Document]:
        return self._engine.list_documents(**kwargs)

    def delete_document(self, doc_id: str) -> bool:
        return self._engine.delete_document(doc_id)

    def get_stats(self) -> Dict[str, Any]:
        return self._engine.get_stats()


_doc_index: Optional[DocumentIndexManager] = None


def get_document_index() -> DocumentIndexManager:
    global _doc_index
    if _doc_index is None:
        _doc_index = DocumentIndexManager()
    return _doc_index
