-- 统一认证改造 V1：RAG 本地用户资料表
-- 凭证(密码/邮箱/手机/用户名)落网关；本表只存"展示用"资料。
-- 注：网关只存 phone/email 的哈希，无法回显原文；注册/绑定时 RAG 代理那一刻能看到原值，
--     在此存脱敏手机号与邮箱、用户名、显示名，供 /me 与个人设置直接读取。
CREATE TABLE user_profile (
    auth_user_id BIGINT PRIMARY KEY,
    display_name VARCHAR(64),
    avatar       VARCHAR(512),
    bio          VARCHAR(512),
    username     VARCHAR(64),
    email        VARCHAR(255),
    masked_phone VARCHAR(32),
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
