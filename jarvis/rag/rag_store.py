"""
RAG Knowledge Vault & Dense Document Chunker
Replicates Android RagIndexStore.kt, DocumentChunker.kt & RagContextInjector.kt for Linux Software.
"""

from typing import List, Dict, Any

class DocumentChunk:
    def __init__(self, chunk_id: str, doc_name: str, content: str):
        self.chunk_id = chunk_id
        self.doc_name = doc_name
        self.content = content

class RagStore:
    def __init__(self):
        self.chunks: List[DocumentChunk] = []

    def add_document(self, doc_name: str, full_text: str, chunk_size: int = 500):
        words = full_text.split()
        for i in range(0, len(words), chunk_size):
            chunk_text = " ".join(words[i:i + chunk_size])
            chunk_id = f"{doc_name}_chunk_{i//chunk_size}"
            self.chunks.append(DocumentChunk(chunk_id, doc_name, chunk_text))

    def retrieve_context(self, query: str, top_k: int = 2) -> List[str]:
        query_terms = set(query.lower().split())
        scored = []
        for chunk in self.chunks:
            overlap = sum(1 for term in query_terms if term in chunk.content.lower())
            scored.append((overlap, chunk.content))
        scored.sort(key=lambda x: x[0], reverse=True)
        return [content for score, content in scored[:top_k] if score > 0]
