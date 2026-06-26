package com.ragforge.modelcenter;

/** 模型用途枚举，与 model_config.purpose / DB CHECK 约束保持一致。 */
public enum Purpose {
  EMBEDDING,
  OCR,
  REWRITE,
  RERANK,
  ANSWER,
  JUDGE
}
