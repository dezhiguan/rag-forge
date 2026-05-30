from transformers import AutoModel
import torch

MODEL_NAME = "jinaai/jina-reranker-v3"


class Reranker:
    def __init__(self):
        self.model = AutoModel.from_pretrained(
            MODEL_NAME,
            dtype="auto",
            trust_remote_code=True,
        )
        self.model.eval()
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.model.to(self.device)

    def rerank(self, query: str, documents: list[str], top_n: int) -> list[dict]:
        results = self.model.rerank(query, documents, top_n=top_n)
        return [
            {"index": r["index"], "score": float(r["relevance_score"])}
            for r in results
        ]
