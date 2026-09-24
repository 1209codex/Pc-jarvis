from jarvis.retrieval.raphael_retrieval import RaphaelRetrievalManager
from jarvis.rag.rag_store import RagStore

def test_raphael_fusion_retrieval():
    mgr = RaphaelRetrievalManager()
    mgr.index_document("doc1", "Linux pulseaudio sound settings configuration", "system_docs")
    mgr.index_document("doc2", "Python PySide6 Stitch Material 3 desktop GUI", "ui_docs")

    results = mgr.search_and_rerank("pulseaudio sound")
    assert len(results) > 0
    assert results[0].doc_id == "doc1"

def test_rag_store_chunking():
    rag = RagStore()
    rag.add_document("manual", "J.A.R.V.I.S. is an autonomous AI super-agent for Linux desktop operating systems.", chunk_size=5)
    
    ctx = rag.retrieve_context("autonomous AI super-agent")
    assert len(ctx) > 0
    assert "J.A.R.V.I.S." in ctx[0]
