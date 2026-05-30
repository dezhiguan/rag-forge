from transformers import AutoModelForSequenceClassification, AutoTokenizer
import torch

MODEL_NAME = "jinaai/jina-reranker-v3"


class Reranker:
    def __init__(self):
        self.tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME, trust_remote_code=True)
        self.model = AutoModelForSequenceClassification.from_pretrained(MODEL_NAME, trust_remote_code=True)
        self.model.eval()
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.model.to(self.device)

    def rerank(self, query: str, documents: list[str], top_n: int) -> list[dict]:
        pairs = [[query, doc] for doc in documents]
        inputs = self.tokenizer(
            pairs,
            padding=True,
            truncation=True,
            max_length=512,
            return_tensors="pt",
        ).to(self.device)

        with torch.no_grad():
            scores = self.model(**inputs, return_dict=True).logits.view(-1).cpu().tolist()

        scores = [float(torch.sigmoid(torch.tensor(s)).item()) for s in scores]
        ranked = sorted(
            [{"index": i, "score": scores[i]} for i in range(len(scores))],
            key=lambda x: x["score"],
            reverse=True,
        )
        return ranked[:top_n]
