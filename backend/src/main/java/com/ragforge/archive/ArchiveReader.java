package com.ragforge.archive;

import java.io.IOException;
import java.io.InputStream;

/**
 * 格式相关的流式归档读取器（内部 SPI）：把 zip / tar.gz 的差异收敛到"逐 entry 回调"。 护栏与部分成功语义由
 * {@link ArchiveExpander} 统一施加，读取器只负责迭代。
 */
interface ArchiveReader {

  /** 该读取器对应的格式。 */
  ArchiveFormat format();

  /**
   * 流式迭代归档中的每个 entry，逐个回调 visitor。<b>纯流式</b>，不整包 load、不落盘。
   *
   * @param source 归档字节流（调用方负责关闭外层）
   * @param visitor 每个 entry 的回调
   * @throws IOException 底层流损坏 / 截断 / 格式错误（上层转 {@code ARCHIVE_CORRUPTED}）
   */
  void read(InputStream source, EntryVisitor visitor) throws IOException;

  /** entry 回调。visitor 内抛出的 {@link ArchiveException} 会中止迭代并向上传播（致命护栏）。 */
  interface EntryVisitor {
    void visit(RawEntry entry) throws IOException;
  }
}
