#!/usr/bin/env python3
from __future__ import annotations

import base64
import json
import os
import sys
import urllib.request
from pathlib import Path


ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"


def main() -> int:
    image_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "assets" / "image_no_text.png"
    api_key = os.environ.get("DASHSCOPE_API_KEY")
    if not api_key:
        print("DASHSCOPE_API_KEY is required", file=sys.stderr)
        return 2
    data_uri = "data:image/png;base64," + base64.b64encode(image_path.read_bytes()).decode("ascii")
    payload = {
        "model": "qwen-vl-ocr",
        "input": {
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"image": data_uri},
                        {"text": "识别图中所有文字。若没有文字，只返回空字符串。"},
                    ],
                }
            ]
        },
    }
    req = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    content = body.get("output", {}).get("choices", [{}])[0].get("message", {}).get("content", [{}])[0]
    text = (
        content.get("ocr_result", {}).get("processed_text")
        or content.get("text")
        or ""
    ).strip()
    if text:
        print(f"OCR text detected in no-text fixture: {text}", file=sys.stderr)
        return 1
    print("image_no_text.png verified: OCR returned empty text")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
