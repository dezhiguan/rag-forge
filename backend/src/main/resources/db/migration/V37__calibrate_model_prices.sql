-- 按官方公布价校准模型单价（DeepSeek 官网 + 阿里云百炼，2026-06 核对）。
-- 单位：元/百万 Token。
--
-- 已与官方一致、无需变更：
--   qwen-turbo            输入 0.30 / 输出 0.60
--   qwen-plus             输入 0.80 / 输出 2.00
--   deepseek-v4-flash     输入 1.00 / 输出 2.00（缓存未命中价；命中价 0.02 暂未单独建模）
--   qwen3-vl-embedding    输入 0.70（文本价；图片/视频 1.80 暂未单独建模，多以文本为主）
--
-- 以下两个与官方不符，更正：

-- qwen-vl-ocr：官方 0.006 元/千Token（输入/输出同价）= 6.00 元/百万Token
UPDATE model_config
   SET input_price = 6.00, output_price = 6.00, updated_at = NOW()
 WHERE code = 'qwen-vl-ocr';

-- qwen3-rerank：官方 0.0005 元/千Token = 0.50 元/百万Token（仅输入计费）
UPDATE model_config
   SET input_price = 0.50, output_price = 0.00, updated_at = NOW()
 WHERE code = 'qwen3-rerank';
