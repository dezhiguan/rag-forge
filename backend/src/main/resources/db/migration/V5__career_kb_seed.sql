-- 初始化 5 类求职专属知识库
-- 注意：若已存在同名 KB（重复执行场景），使用 ON CONFLICT 跳过；本表 name 列若未来加唯一索引可去掉 WHERE 子句
INSERT INTO knowledge_bases (name, description, embedding_model, chunk_size, chunk_overlap, status)
SELECT v.name, v.description, 'text-embedding-v4', 512, 64, 'active'
FROM (VALUES
    ('Personal KB',        '用户简历与项目个人语料（每用户隔离，按 metadata.user_id 区分）'),
    ('JD Pattern KB',      '行业 JD 模式知识库（职责段/要求段/福利段分块）'),
    ('Company Intel KB',   '公司情报：技术博客、开源 README、媒体报道'),
    ('Interview Q&A KB',   '面试题库：按公司/岗位/题型组织'),
    ('Salary Reference KB','薪资基准数据：城市/职级/年份')
) AS v(name, description)
WHERE NOT EXISTS (
    SELECT 1 FROM knowledge_bases kb WHERE kb.name = v.name
);
