ALTER TABLE knowledge_bases
  ALTER COLUMN answer_model SET DEFAULT 'qwen-plus';

UPDATE knowledge_bases
SET answer_model = 'qwen-plus'
WHERE answer_model = 'qwen-max';
