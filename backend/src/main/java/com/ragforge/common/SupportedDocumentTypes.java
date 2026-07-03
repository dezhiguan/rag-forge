package com.ragforge.common;

import java.util.Locale;
import java.util.Set;

/**
 * 压缩包解压后可入库的扩展名白名单，分两类，决定子文档路由到哪条管道：
 *
 * <ul>
 *   <li>{@link #DOC_EXTENSIONS} 文档类（含 txt）→ 文档管道 DocumentPipelineService（抽文本 + 内嵌图）
 *   <li>{@link #IMAGE_EXTENSIONS} 图片类 → 图片管道 ImagePipelineService（OCR/VL）
 * </ul>
 *
 * <p>命中任一类即受支持；否则跳过（{@code unsupported_type}）。路由由子文档 contentType（image/* vs 其它）
 * 决定，见 {@code ArchiveExpandConsumer.guessContentType}。
 */
public final class SupportedDocumentTypes {

  private SupportedDocumentTypes() {}

  /** 文档类（走文本/PDF/Office 解析；含 txt，与散传口径一致）。 */
  public static final Set<String> DOC_EXTENSIONS =
      Set.of("pdf", "doc", "docx", "md", "markdown", "html", "htm", "txt", "csv");

  /** 图片类（走图片 OCR/VL 管道；对齐前端散传 accept）。 */
  public static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp");

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

  /** 扩展名是否受支持（文档类或图片类，大小写不敏感）。 */
  public static boolean isAllowed(String filename) {
    String ext = extensionOf(filename);
    return DOC_EXTENSIONS.contains(ext) || IMAGE_EXTENSIONS.contains(ext);
  }

  /** 是否为图片类（用于路由到图片管道）。 */
  public static boolean isImage(String filename) {
    return IMAGE_EXTENSIONS.contains(extensionOf(filename));
  }
}
