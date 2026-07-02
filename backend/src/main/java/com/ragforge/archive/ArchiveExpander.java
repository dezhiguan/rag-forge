package com.ragforge.archive;

import java.io.InputStream;

/**
 * 压缩包解压门面：屏蔽 zip / tar.gz 格式差异，统一施加护栏（{@code ArchiveGuardrails}），对每个通过护栏的
 * entry 回调 {@link ArchiveEntryConsumer}。Consumer（Consumer 层）只负责落 OSS + 登记子文档，不感知格式。
 *
 * <p>全程纯流式、不落本地磁盘：逐 entry 读取，单 entry 受上限约束缓冲入内存（≤50MB），禁止整包 load。
 *
 * <p>致命错误（解压炸弹 / 总量超阈 / entry 数超阈 / 加密 / 损坏 / 空包）抛 {@link ArchiveException}，
 * 由调用方置容器 FAILED；单 entry 问题记入返回的 {@link ExpandOutcome#getSkipped()}，不失败整包。
 */
public interface ArchiveExpander {

  /**
   * @param archiveStream 压缩包字节流（来自 OSS，调用方负责关闭）
   * @param format 已识别的格式（必须是 {@link ArchiveFormat#isSupported()} 为 true 的格式）
   * @param consumer 每个通过护栏的 entry 的消费回调
   * @return 汇总结果（总数 / 登记数 / 跳过明细）
   * @throws ArchiveException 致命错误导致整包失败
   */
  ExpandOutcome expand(InputStream archiveStream, ArchiveFormat format, ArchiveEntryConsumer consumer);
}
