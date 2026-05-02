import os
from typing import List, Optional
from langchain_core.documents import Document as LangchainDocument
from langchain_text_splitters import RecursiveCharacterTextSplitter
from app.core.config import settings


class DocumentParser:
    def __init__(self):
        self.text_splitter = RecursiveCharacterTextSplitter(
            chunk_size=settings.CHUNK_SIZE,
            chunk_overlap=settings.CHUNK_OVERLAP,
            separators=["\n\n", "\n", " ", "", "。", "，", "；", "："]
        )

    def parse_pdf(self, file_path: str) -> List[LangchainDocument]:
        from pypdf import PdfReader
        reader = PdfReader(file_path)
        documents = []
        for i, page in enumerate(reader.pages):
            text = page.extract_text()
            if text:
                documents.append(LangchainDocument(
                    page_content=text,
                    metadata={"page": i + 1, "source": file_path}
                ))
        return documents

    def parse_markdown(self, file_path: str) -> List[LangchainDocument]:
        with open(file_path, "r", encoding="utf-8") as f:
            content = f.read()
        return [LangchainDocument(page_content=content, metadata={"source": file_path})]

    def parse_txt(self, file_path: str) -> List[LangchainDocument]:
        with open(file_path, "r", encoding="utf-8") as f:
            content = f.read()
        return [LangchainDocument(page_content=content, metadata={"source": file_path})]
    
    def parse_file(self, file_path: str, file_type: str) -> List[LangchainDocument]:
        file_type = file_type.lower()
        
        if file_type == "pdf":
            return self.parse_pdf(file_path)
        elif file_type in ["md", "markdown"]:
            return self.parse_markdown(file_path)
        elif file_type == "txt":
            return self.parse_txt(file_path)
        else:
            raise ValueError(f"Unsupported file type: {file_type}")
    
    def split_documents(self, documents: List[LangchainDocument]) -> List[LangchainDocument]:
        return self.text_splitter.split_documents(documents)
    
    def process_file(self, file_path: str, file_type: str) -> List[LangchainDocument]:
        documents = self.parse_file(file_path, file_type)
        chunks = self.split_documents(documents)
        
        for i, chunk in enumerate(chunks):
            chunk.metadata["chunk_index"] = i
        
        return chunks
