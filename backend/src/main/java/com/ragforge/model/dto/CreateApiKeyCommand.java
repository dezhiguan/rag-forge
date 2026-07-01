package com.ragforge.model.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建 API Key 的命令（服务层输入，解耦 controller DTO）。
 *
 * @param keyName 名称
 * @param scopeMode 可读范围 ORG_ALL(默认) | KB_LIST
 * @param allowedKbIds KB_LIST 模式下授权的知识库 id（须属于当前组织）
 * @param accessLevel 权限级别（本期仅 READ 生效）
 * @param expiresAt 过期时间；null=永不过期
 */
public record CreateApiKeyCommand(
    String keyName,
    String scopeMode,
    List<Long> allowedKbIds,
    String accessLevel,
    LocalDateTime expiresAt) {}
