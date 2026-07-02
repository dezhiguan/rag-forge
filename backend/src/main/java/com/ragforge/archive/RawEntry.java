package com.ragforge.archive;

import java.io.InputStream;

/**
 * 格式无关的原始 entry 视图（zip / tar 统一抽象）。由 {@link ArchiveReader} 在流式迭代中逐个产出，供
 * {@link ArchiveExpander} 施加护栏。{@link #stream()} 返回的是底层归档流在当前 entry 上的内容，<b>调用方
 * 只读不关闭</b>（关闭会中断整包迭代）；读到 {@code -1} 即当前 entry 结束。
 */
interface RawEntry {

  /** entry 原始名称（可能含路径分隔符 / 穿越片段，未规范化）。 */
  String name();

  /** 是否目录 entry（目录跳过、不计入 entry 数量）。 */
  boolean directory();

  /** 是否符号链接 / 硬链接（tar 特有；越权风险，按非法路径跳过）。 */
  boolean symlink();

  /** 是否加密 entry（zip 通过通用位标志判定；tar 恒为 false）。 */
  boolean encrypted();

  /** 解压后大小；未知返回 -1（zip 流式可能未知，tar header 恒已知）。 */
  long uncompressedSize();

  /** 压缩前占用大小；未知返回 -1（用于压缩比护栏，tar 无此概念返回 -1）。 */
  long compressedSize();

  /** 当前 entry 内容流；只读不关闭。 */
  InputStream stream();
}
