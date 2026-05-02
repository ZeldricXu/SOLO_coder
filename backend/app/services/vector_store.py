import chromadb
from chromadb.config import Settings as ChromaSettings
from typing import List, Optional, Dict, Any
from langchain_core.documents import Document as LangchainDocument
from langchain_openai import OpenAIEmbeddings
from langchain_chroma import Chroma
from app.core.config import settings
import os


class VectorStoreService:
    def __init__(self):
        self.embeddings = OpenAIEmbeddings(
            model=settings.EMBEDDING_MODEL,
            api_key=settings.OPENAI_API_KEY,
            base_url=settings.OPENAI_API_BASE
        )

        os.makedirs(settings.CHROMA_PERSIST_DIR, exist_ok=True)

        self._client = chromadb.PersistentClient(
            path=settings.CHROMA_PERSIST_DIR,
            settings=ChromaSettings(
                anonymized_telemetry=False,
                allow_reset=True
            )
        )

        self._vector_stores: Dict[str, Chroma] = {}

    def _get_or_create_collection(self, collection_name: str) -> Chroma:
        if collection_name in self._vector_stores:
            return self._vector_stores[collection_name]

        vector_store = Chroma(
            client=self._client,
            collection_name=collection_name,
            embedding_function=self.embeddings,
            persist_directory=settings.CHROMA_PERSIST_DIR
        )

        self._vector_stores[collection_name] = vector_store
        return vector_store
    
    def add_documents(
        self,
        documents: List[LangchainDocument],
        collection_name: str = "default",
        document_id: Optional[str] = None
    ) -> List[str]:
        if document_id:
            for doc in documents:
                doc.metadata["document_id"] = document_id
        
        vector_store = self._get_or_create_collection(collection_name)
        ids = vector_store.add_documents(documents)
        
        return ids
    
    def similarity_search(
        self,
        query: str,
        collection_name: str = "default",
        top_k: int = 5
    ) -> List[LangchainDocument]:
        vector_store = self._get_or_create_collection(collection_name)
        
        results = vector_store.similarity_search_with_score(
            query,
            k=top_k
        )
        
        documents_with_scores = []
        for doc, score in results:
            doc.metadata["score"] = score
            documents_with_scores.append(doc)
        
        return documents_with_scores
    
    def delete_document(
        self,
        document_id: str,
        collection_name: str = "default"
    ) -> bool:
        try:
            collection = self._client.get_collection(collection_name)
            
            results = collection.get(
                where={"document_id": document_id}
            )
            
            if results["ids"]:
                collection.delete(ids=results["ids"])
                return True
            return False
        except Exception:
            return False
    
    def get_collection_info(self, collection_name: str = "default") -> Dict[str, Any]:
        try:
            collection = self._client.get_collection(collection_name)
            count = collection.count()
            
            return {
                "name": collection_name,
                "chunk_count": count,
                "exists": True
            }
        except Exception:
            return {
                "name": collection_name,
                "chunk_count": 0,
                "exists": False
            }
    
    def list_collections(self) -> List[str]:
        collections = self._client.list_collections()
        return [col.name for col in collections]
