-- failure_reason 可能含 DeepSeek 原始 JSON + Java 栈帧，VARCHAR(256) 不足
ALTER TABLE judge_results
  ALTER COLUMN failure_reason TYPE TEXT;
