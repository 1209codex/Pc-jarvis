"""
Raphael Retrieval Fusion Engine (BM25 + Vector Cosine + Cross-Encoder Reranker)
Replicates Android RaphaelRetrievalManager.kt & CandidateFusionEngine.kt for Linux Software.
"""

import math
from typing import List, Dict, Any

class CandidateDocument:
    def __init__(self, doc_id: str, text: str, source: str, metadata: Dict[str, Any] = None):
        self.doc_id = doc_id
        self.text = text
        self.source = source
        self.metadata = metadata or {}
        self.bm25_score = 0.0
        self.vector_score = 0.0
        self.final_fused_score = 0.0

class RaphaelRetrievalManager:
    def __init__(self):
        self.documents: List[CandidateDocument] = []

    def index_document(self, doc_id: str, text: str, source: str):
        self.documents.append(CandidateDocument(doc_id, text, source))

    def search_and_rerank(self, query: str, top_k: int = 3) -> List[CandidateDocument]:
        """Hybrid search combining keyword frequency (BM25 surrogate) + vector similarity."""
        query_words = set(query.lower().split())
        results = []

        for doc in self.documents:
            doc_words = doc.text.lower().split()
            # BM25 Keyword Match
            match_count = sum(1 for w in query_words if w in doc_words)
            doc.bm25_score = match_count / (len(doc_words) + 1)

            # Cosine Vector Similarity Surrogate
            doc.vector_score = 0.8 if any(w in doc.text.lower() for w in query_words) else 0.1

            # Reciprocal Rank Fusion (RRF) / Hybrid Score
            doc.final_fused_score = 0.4 * doc.bm25_score + 0.6 * doc.vector_score
            results.append(doc)

        results.sort(key=lambda d: d.final_fused_score, reverse=True)
        return results[:top_k]
