package com.ragforge.archive;

/**
 * 回调式 entry 消费者。{@link ArchiveExpander} 对每个通过护栏的 entry 调用一次，由实现方（Consumer）
 * 负责落 OSS + 登记子文档。抛出异常表示该 entry 消费失败——Expander 会记为
 * {@link SkipReason#REGISTER_FAILED} 并继续处理下一个，不失败整包。
 */
@FunctionalInterface
public interface ArchiveEntryConsumer {
  void consume(ExpandedEntry entry) throws Exception;
}
