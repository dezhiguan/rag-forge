ALTER TABLE knowledge_bases
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'tn_default',
    ADD COLUMN IF NOT EXISTS owner_user_id BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
    ADD COLUMN IF NOT EXISTS kb_type VARCHAR(32) NOT NULL DEFAULT 'GENERAL';

UPDATE knowledge_bases
SET tenant_id = 'tn_01J_PERSONAL_GUANDEZHI',
    owner_user_id = 26,
    visibility = CASE
        WHEN id IN (16, 17) THEN 'PUBLIC'
        ELSE 'PRIVATE'
    END,
    kb_type = CASE name
        WHEN 'Personal KB' THEN 'PERSONAL'
        WHEN 'JD Pattern KB' THEN 'JD_PATTERN'
        WHEN 'Company Intel KB' THEN 'COMPANY_INTEL'
        WHEN 'Interview Q&A KB' THEN 'INTERVIEW_QA'
        WHEN 'Salary Reference KB' THEN 'SALARY_REFERENCE'
        WHEN '我的简历库' THEN 'PERSONAL'
        WHEN '岗位 JD 库' THEN 'JD_PATTERN'
        WHEN '目标公司库' THEN 'COMPANY_INTEL'
        WHEN '面试题库' THEN 'INTERVIEW_QA'
        WHEN '薪资行情库' THEN 'SALARY_REFERENCE'
        ELSE kb_type
    END
WHERE id IN (15, 16, 17, 18, 19, 20)
   OR name IN (
       'Personal KB',
       'JD Pattern KB',
       'Company Intel KB',
       'Interview Q&A KB',
       'Salary Reference KB',
       '我的简历库',
       '岗位 JD 库',
       '目标公司库',
       '面试题库',
       '薪资行情库'
   );

CREATE INDEX IF NOT EXISTS idx_knowledge_bases_tenant_visibility
    ON knowledge_bases (tenant_id, visibility);

CREATE INDEX IF NOT EXISTS idx_knowledge_bases_owner
    ON knowledge_bases (owner_user_id);
