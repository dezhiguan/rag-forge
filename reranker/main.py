import time

import uvicorn
from fastapi import FastAPI
from pydantic import BaseModel

from reranker import Reranker

app = FastAPI(title="RAGForge Reranker", version="1.0")
reranker = Reranker()


class RerankRequest(BaseModel):
    query: str
    documents: list[str]
    top_n: int = 5


class RerankResult(BaseModel):
    index: int
    score: float


class RerankResponse(BaseModel):
    results: list[RerankResult]
    latency_ms: float


@app.post("/rerank")
def rerank_api(req: RerankRequest):
    start = time.time()
    results = reranker.rerank(req.query, req.documents, req.top_n)
    latency_ms = (time.time() - start) * 1000
    return RerankResponse(results=results, latency_ms=latency_ms)


@app.get("/health")
def health():
    return {"status": "ok"}


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8001)
