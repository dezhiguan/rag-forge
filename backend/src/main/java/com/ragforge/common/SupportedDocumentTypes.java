package com.ragforge.common;

import java.util.Locale;
import java.util.Set;

/**
 * 可入库的文档扩展名白名单（与 {@code DocumentServiceImpl.ALLOWED_EXTENSIONS} 口径一致）。 压缩包解压出的
 * entry 复用此白名单：非白名单 entry 跳过（{@code unsupported_type}）。
 *
 * <p>注：图片（image/*）由图片管道处理，不在此文本白名单内；压缩包内图片本期按非白名单跳过，
 * 与散传图片走 presign 图片管道的行为差异已在测试用例 AR-WL 覆盖并记录。
 */
public final class SupportedDocumentTypes {

  private SupportedDocumentTypes() {}

  public static final Set<String> ALLOWED_EXTENSIONS =
      Set.of("pdf", "doc", "docx", "md", "markdown", "html", "htm");

  /** 取文件名扩展名（小写，不含点）；无扩展名返回空串。 */
  public static String extensionOf(String filename) {
    if (filename == null) {
      return "";
    }
    String name = filename;
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    int dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1) {
      return "";
    }
    return name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /** 扩展名是否在白名单内（大小写不敏感）。 */
  public static boolean isAllowed(String filename) {
    return ALLOWED_EXTENSIONS.contains(extensionOf(filename));
  }
}
