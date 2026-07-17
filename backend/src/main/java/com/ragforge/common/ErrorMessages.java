package com.ragforge.common;

import java.util.Map;

/**
 * 错误码 → 中文用户提示目录。用于错误响应把机器码翻译成可读中文（msg），机器码单独放在 errorCode 字段。
 *
 * <p>未收录的码回退为原码本身（保持向后兼容，前端仍可自行本地化）。 刻意不收录 ANSWER_DISABLED /
 * REPLAY_ALREADY_RUNNING / DOC_IDENTITY_CONFLICT —— 前端对这些码有硬编码字符串比较，翻成中文会破坏其判断。
 */
public final class ErrorMessages {

  private ErrorMessages() {}

  private static final Map<String, String> CN =
      Map.ofEntries(
          // 组织
          Map.entry("ORG_NAME_REQUIRED", "请填写组织名称"),
          Map.entry("ORG_NAME_TOO_LONG", "组织名称过长（最多 128 个字符）"),
          Map.entry("ORG_SLUG_INVALID", "组织标识不合法（2–64 位，小写字母/数字/连字符，且不能以连字符开头或结尾）"),
          Map.entry("ORG_SLUG_TAKEN", "该组织标识已被占用，请换一个"),
          Map.entry("ORG_NOT_FOUND", "组织不存在或已被删除"),
          Map.entry("ORG_HAS_KBS", "该组织下仍有知识库，无法删除"),
          Map.entry("INDIVIDUAL_ORG_NO_INVITE", "个人组织不支持邀请成员，请先升级为团队组织"),
          Map.entry("INDIVIDUAL_ORG_NO_MEMBER", "个人组织无需管理成员"),
          Map.entry("NOT_INDIVIDUAL_ORG", "该操作仅适用于个人组织"),
          // 成员 / 角色 / 所有权
          Map.entry("NOT_ORG_ADMIN", "只有组织的所有者或管理员才能执行此操作"),
          Map.entry("NOT_ORG_OWNER", "只有组织所有者才能执行此操作"),
          Map.entry("NOT_ORG_MEMBER", "你不是该组织成员"),
          Map.entry("MEMBER_NOT_FOUND", "成员不存在"),
          Map.entry("ROLE_INVALID", "所选角色不合法"),
          Map.entry("ALREADY_OWNER", "对方已是所有者"),
          Map.entry("ONLY_OWNER", "只有所有者才能执行此操作"),
          Map.entry("ONLY_OWNER_CAN_CHANGE_OWNER", "只有所有者才能转移所有权"),
          Map.entry("ONLY_OWNER_CAN_GRANT_OWNER", "只有所有者才能授予所有者角色"),
          Map.entry("LAST_OWNER", "组织需至少保留一名所有者"),
          Map.entry("LAST_OWNER_CANNOT_LEAVE", "你是唯一所有者，请先转移所有权再退出"),
          // 邀请
          Map.entry("INVITE_ALREADY_PENDING", "该用户已有一条待接受的邀请，请等待对方处理"),
          Map.entry("ALREADY_MEMBER", "该手机号对应的用户已是本组织成员，无需重复邀请"),
          Map.entry("INVITE_NOT_FOUND", "邀请不存在或无权操作"),
          Map.entry("INVITE_NOT_PENDING", "该邀请已被处理（已接受 / 拒绝 / 撤销）"),
          Map.entry("INVITE_EXPIRED", "邀请已过期，请让对方重新发送"),
          // 通知
          Map.entry("NOTIFICATION_NOT_FOUND", "通知不存在或无权操作"),
          // 鉴权
          Map.entry("UNAUTHORIZED", "登录状态已失效，请重新登录"),
          Map.entry("TOKEN_INVALID", "登录状态已失效，请重新登录"),
          Map.entry("LOGIN_REQUIRED", "请先登录后再操作"),
          // 知识库（常见用户可见）
          Map.entry("KB_ACCESS_DENIED", "无权访问该知识库或案例"),
          Map.entry("KB_WRITE_FORBIDDEN", "您没有该知识库的写权限"),
          Map.entry("KB_NOT_FOUND", "知识库不存在"),
          Map.entry("KB_IDS_REQUIRED", "请至少选择一个知识库"),
          Map.entry("QUERY_REQUIRED", "请输入您的问题"),
          // 知识库可见性（收敛为 私有 / 组织内公开；提示不暴露技术枚举值）
          Map.entry("KB_VISIBILITY_INVALID", "所选可见性不可用；个人知识库仅支持「私有」"),
          Map.entry("ORG_KB_VISIBILITY_INVALID", "团队知识库仅支持「私有」或「组织内公开」，请重新选择"),
          Map.entry("KB_VISIBILITY_REQUIRED", "请选择知识库可见性"),
          // API Key 创建 / 授权
          Map.entry("KEY_NAME_REQUIRED", "请输入 API 密钥名称"),
          Map.entry("KEY_NAME_TOO_LONG", "API 密钥名称过长（最多 100 个字符）"),
          Map.entry("API_KEY_NOT_FOUND", "API 密钥不存在或已被删除"),
          Map.entry("ALLOWED_KB_IDS_REQUIRED", "请至少选择一个要授权的知识库"),
          Map.entry("KB_NOT_IN_ORG", "所选知识库不属于当前组织，无法授权"),
          Map.entry("PLATFORM_VIEW_READONLY", "全平台视图为只读治理视角，不能创建或修改内容，请切换到具体组织后再试"),
          // API Key 鉴权（外部调用直连响应，务必友好中文）
          Map.entry("API_KEY_MISSING", "缺少 API 密钥，请在请求头 X-API-Key 中携带"),
          Map.entry("API_KEY_INVALID", "API 密钥无效或已被禁用，请检查后重试"),
          Map.entry("API_KEY_EXPIRED", "密钥已过期，请重新生成后使用"),
          Map.entry("API_KEY_RATE_LIMITED", "请求过于频繁，请稍后再试"),
          Map.entry(
              "API_KEY_READ_ONLY", "该 API 密钥为只读，无法写入数据；请在开发者中心创建具有写入权限（WRITE）的密钥"),
          Map.entry(
              "API_KEY_KB_NOT_AUTHORIZED", "该 API 密钥未被授权写入此知识库，请检查密钥的可访问范围"),
          // 治理 / 破玻璃
          Map.entry("GOVERNANCE_REQUIRES_BREAKGLASS", "该治理操作需先进入「全平台视图」（破玻璃）后才能执行"),
          Map.entry("GOVERNANCE_QUERY_TOO_SHORT", "请输入至少 3 个字符的密钥名称或前缀"),
          Map.entry("REVOKE_REASON_REQUIRED", "请填写吊销原因"),
          // 文档 / 分块
          Map.entry("DOCUMENT_NOT_FOUND", "文档不存在或已被删除"),
          Map.entry(
              "INVALID_STRATEGY", "所选分块策略不适用于该文档（「按标题分块」仅支持 Markdown 文档），请改用其他策略"),
          Map.entry("SEMANTIC_REQUIRES_LONG_TEXT", "文档内容过短，暂不适用语义分块，请改用其他分块策略"),
          Map.entry("INVALID_CHUNKER_PROFILE_JSON", "分块配置格式有误，请检查后重试"),
          // 压缩包上传 / 解压
          Map.entry("UNSUPPORTED_ARCHIVE_FORMAT", "暂不支持该压缩格式（仅支持 zip 与 tar.gz）"),
          Map.entry("ARCHIVE_SUSPICIOUS_RATIO", "压缩包异常：疑似恶意文件（压缩比过高），已终止处理"),
          Map.entry("ARCHIVE_TOTAL_SIZE_EXCEEDED", "压缩包解压后体积超出上限，已终止处理"),
          Map.entry("ARCHIVE_TOO_MANY_ENTRIES", "压缩包内文件数量超出上限，已终止处理"),
          Map.entry("ARCHIVE_ENCRYPTED_UNSUPPORTED", "暂不支持加密压缩包，请上传未加密的压缩包"),
          Map.entry("ARCHIVE_EMPTY", "压缩包内没有可入库的文件"),
          Map.entry("ARCHIVE_CORRUPTED", "压缩包已损坏或格式不正确，无法解压"),
          // 文档处理管道（子文档解析/向量化失败——面向用户，不泄露内部细节）
          Map.entry("EMBEDDING_RATE_LIMITED", "向量化服务繁忙，请稍后重试"),
          Map.entry("EMBEDDING_CALL_FAILED", "文档向量化失败，请稍后重试"),
          Map.entry("NO_CHUNKER_STRATEGY_AVAILABLE", "文档无法分块（内容可能为空或格式不支持）"),
          Map.entry("DOC_PROCESS_FAILED", "文档处理失败，请稍后重试"),
          // CSV 解析
          Map.entry("CSV_EMPTY", "CSV 文件内容为空"),
          Map.entry("CSV_NO_DATA_ROWS", "CSV 只有表头、没有数据行"),
          Map.entry("CSV_PARSE_FAILED", "CSV 解析失败，请检查文件格式或分隔符"),
          // 评测
          Map.entry("JUDGE_RESULT_NOT_FOUND", "评测结果不存在或已被清理"),
          Map.entry("EVAL_DATASET_NOT_FOUND", "评测数据集不存在"),
          Map.entry("GOLDEN_SET_EMPTY", "当前组织还没有启用黄金题，请先到评测数据集勾选后再回放"),
          Map.entry("GOLDEN_BUDGET_EXCEEDED", "本月评测额度已用完，请联系平台管理员或下月再试"),
          Map.entry("BUDGET_ADMIN_ONLY", "月度评测预算仅平台管理员可配置，请联系平台管理员"),
          Map.entry("GOLDEN_REPLAY_COOLDOWN", "刚刚已发起回放，请稍后再试（每 5 分钟一次）"),
          Map.entry("ORG_CONTEXT_REQUIRED", "请先选择组织后再操作"),
          Map.entry("EVAL_RESOURCE_NOT_IN_ORG", "所选评测资源不属于当前组织"),
          Map.entry("ALREADY_IN_PROGRESS", "任务正在进行中，请稍候再试"),
          // 模型 & 成本中心
          Map.entry("MODEL_TOGGLE_REQUIRES_PLATFORM_VIEW", "启停模型需切换到「全平台视图」（平台管理员）"),
          Map.entry("MODEL_NOT_FOUND", "模型不存在或已下线"),
          Map.entry("ENABLED_REQUIRED", "缺少启用状态参数，请刷新页面后重试"),
          Map.entry(
              "MODEL_DISABLE_WOULD_LEAVE_PURPOSE_UNAVAILABLE",
              "停用后该用途将没有可用模型，请先启用备用模型"),
          Map.entry(
              "CORE_QUESTION_LOCKED",
              "这是冻结基线的核心题，已锁定，不可编辑或删除"),
          Map.entry(
              "CORE_DATASET_LOCKED",
              "该数据集是冻结的基线评测集，已锁定，不可删除"),
          // 通用兜底码
          Map.entry("INVALID_REQUEST", "请求格式有误，请检查后重试"),
          Map.entry("INVALID_PARAM", "请求参数有误，请检查后重试"),
          Map.entry("MISSING_PARAM", "请求缺少必要参数，请检查后重试"),
          Map.entry("METHOD_NOT_ALLOWED", "请求方式不支持"),
          Map.entry("NOT_FOUND", "请求的资源不存在"),
          Map.entry("FORBIDDEN", "无权访问，请联系管理员"),
          Map.entry("INTERNAL_ERROR", "服务暂时异常，请稍后重试"));

  /**
   * 翻译错误码为中文；支持动态码 {@code CODE:suffix}（翻译前缀，未收录则回退原码）。 未收录的码返回其本身，保证向后兼容。
   */
  public static String toChinese(String errorCode) {
    if (errorCode == null || errorCode.isBlank()) {
      return "操作失败，请稍后重试";
    }
    String cn = CN.get(errorCode);
    if (cn != null) {
      return cn;
    }
    int colon = errorCode.indexOf(':');
    if (colon > 0) {
      String prefixCn = CN.get(errorCode.substring(0, colon));
      if (prefixCn != null) {
        return prefixCn;
      }
    }
    return errorCode; // 未收录：回退原码，前端仍可本地化
  }

  /**
   * 把管道内部的原始异常消息净化成对用户友好的中文（用于持久化到 {@code documents.error_msg} 展示）。
   * 优先翻译机器码；对含内部细节（HTTP 429 / JSON / URL / 堆栈 / request_id / 英文异常）的消息归为通用提示，
   * 不向用户泄露内部信息；已是干净短中文文案则保留。
   */
  public static String toUserFriendly(String raw) {
    if (raw == null || raw.isBlank()) {
      return "文档处理失败，请稍后重试";
    }
    String s = raw.trim();
    String cn = CN.get(s);
    if (cn != null) {
      return cn;
    }
    if (s.matches("(?s).*(429|RateQuota|Throttling|[Rr]ate ?[Ll]imit).*")) {
      return "向量化服务繁忙，请稍后重试";
    }
    if (s.contains("Embedding") || s.contains("embedding") || s.contains("向量化")) {
      return "文档向量化失败，请稍后重试";
    }
    if (s.contains("OCR") || s.contains("ocr")) {
      return "图片识别失败，请稍后重试";
    }
    if (s.matches("[A-Z][A-Z0-9_]{4,}")) { // 裸机器码
      return CN.getOrDefault(s, "文档处理失败，请稍后重试");
    }
    if (s.contains("{")
        || s.contains("http")
        || s.contains("request_id")
        || s.contains("at com.")
        || s.contains("Exception")
        || s.contains("HTTP ")) { // 含内部细节
      return "文档处理失败，请稍后重试";
    }
    if (s.length() <= 80 && s.matches("(?s).*[一-龥].*") && !s.matches("(?s).*[A-Za-z]{5,}.*")) {
      return s; // 干净短中文，保留
    }
    return "文档处理失败，请稍后重试";
  }
}
