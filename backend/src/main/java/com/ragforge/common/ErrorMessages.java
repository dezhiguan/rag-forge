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
          Map.entry("ORG_SLUG_INVALID", "组织标识不合法（小写字母/数字/连字符，2–64 位）"),
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
          // 知识库（常见用户可见）
          Map.entry("KB_ACCESS_DENIED", "无权访问该知识库或案例"),
          Map.entry("KB_WRITE_FORBIDDEN", "您没有该知识库的写权限"),
          Map.entry("KB_NOT_FOUND", "知识库不存在"),
          Map.entry("KB_IDS_REQUIRED", "请至少选择一个知识库"),
          Map.entry("QUERY_REQUIRED", "请输入您的问题"),
          // 通用兜底码
          Map.entry("INVALID_REQUEST", "请求参数有误，请检查后重试"),
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
}
