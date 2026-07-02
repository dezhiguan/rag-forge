package com.ragforge.archive;

import java.util.Locale;
import java.util.Set;

/**
 * 与格式无关的结构性护栏判定（纯函数，便于穷举单测）：路径穿越规范化、嵌套压缩包识别、叶子名提取。 大小 / 数量 /
 * 压缩比 / 加密等有状态或需读字节的护栏在 {@link ArchiveExpander} 实现中施加。
 */
final class ArchiveGuardrails {

  private ArchiveGuardrails() {}

  /** 视为"嵌套压缩包"的扩展名（跳过、不递归解压）。 */
  private static final Set<String> NESTED_ARCHIVE_EXTS =
      Set.of("zip", "tar", "gz", "tgz", "7z", "rar", "bz2", "xz", "zipx", "z");

  /**
   * 规范化 entry 路径并做路径穿越校验。返回安全的相对路径；若非法（穿越 / 绝对路径 / 空 / 含 NUL）返回 {@code
   * null}。
   *
   * <p>规则：反斜杠归一为正斜杠；拒绝绝对路径（以 / 开头或 Windows 盘符 X:）；拒绝任何 {@code ..} 片段；
   * 丢弃 {@code .} 与空片段后重组；重组为空则非法。
   */
  static String normalizePath(String rawName) {
    if (rawName == null || rawName.isBlank()) {
      return null;
    }
    if (rawName.indexOf('\0') >= 0) {
      return null;
    }
    String n = rawName.replace('\\', '/').trim();
    // 绝对路径（Unix）
    if (n.startsWith("/")) {
      return null;
    }
    // 绝对路径（Windows 盘符，如 C:/ 或 C:）
    if (n.length() >= 2 && Character.isLetter(n.charAt(0)) && n.charAt(1) == ':') {
      return null;
    }
    String[] segments = n.split("/");
    StringBuilder safe = new StringBuilder();
    for (String seg : segments) {
      if (seg.isEmpty() || seg.equals(".")) {
        continue;
      }
      if (seg.equals("..")) {
        return null; // 任何上跳片段一律拒绝
      }
      if (safe.length() > 0) {
        safe.append('/');
      }
      safe.append(seg);
    }
    return safe.length() == 0 ? null : safe.toString();
  }

  /** entry 是否为嵌套压缩包（按扩展名判定）。 */
  static boolean isNestedArchive(String name) {
    if (name == null) {
      return false;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    int dot = lower.lastIndexOf('.');
    if (dot < 0 || dot == lower.length() - 1) {
      return false;
    }
    return NESTED_ARCHIVE_EXTS.contains(lower.substring(dot + 1));
  }

  /** 取安全路径的叶子文件名（最后一个片段）。 */
  static String leafName(String safePath) {
    if (safePath == null) {
      return null;
    }
    int slash = safePath.lastIndexOf('/');
    return slash < 0 ? safePath : safePath.substring(slash + 1);
  }
}
