-- 阶段4：组织邀请（按手机号 + 接受流程）+ 站内通知。
-- 邀请按手机号解析（网关 resolve-by-phone）：已注册绑 invitee_user_id；未注册按 invitee_phone 暂存，
-- 注册后凭手机号关联。PENDING 不计入正式成员，接受后才写 org_members。

CREATE TABLE IF NOT EXISTS org_invitations (
    id               BIGSERIAL PRIMARY KEY,
    org_id           BIGINT      NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    inviter_user_id  BIGINT      NOT NULL,             -- 发起人 auth_user_id（须 OWNER/ADMIN）
    invitee_user_id  BIGINT      NULL,                 -- 已注册时填解析出的 auth_user_id
    invitee_phone    VARCHAR(32) NULL,                 -- 脱敏手机号（仅展示/核对，不存明文）
    role             VARCHAR(16) NOT NULL DEFAULT 'MEMBER',  -- 预设角色 ADMIN/MEMBER
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING|ACCEPTED|DECLINED|REVOKED|EXPIRED
    token            VARCHAR(64) NOT NULL,             -- 一次性邀请令牌
    expires_at       TIMESTAMP   NOT NULL,
    created_at       TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_org_inv_invitee ON org_invitations(invitee_user_id) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_org_inv_org ON org_invitations(org_id);
-- 同一组织对同一已注册用户的 PENDING 邀请去重
CREATE UNIQUE INDEX IF NOT EXISTS uq_org_inv_pending
    ON org_invitations(org_id, invitee_user_id)
    WHERE status = 'PENDING' AND invitee_user_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS notifications (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL,                 -- 接收人 auth_user_id
    type         VARCHAR(32) NOT NULL,                 -- ORG_INVITE 等
    title        VARCHAR(256) NOT NULL,
    body         VARCHAR(512),
    ref_id       BIGINT      NULL,                     -- 关联实体（如 org_invitations.id）
    read_at      TIMESTAMP   NULL,                     -- 未读为 null
    created_at   TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread
    ON notifications(user_id, created_at DESC) WHERE read_at IS NULL;
