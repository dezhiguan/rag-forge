package com.ragforge.mcp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.answer.AnswerModels.AnswerRequest;
import com.ragforge.answer.AnswerModels.AnswerResponse;
import com.ragforge.answer.AnswerService;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.search.RetrievalService;
import com.ragforge.search.RetrievalService.RetrievalOutput;
import com.ragforge.security.KbAccessGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagForgeMcpTools {

    private final RetrievalService retrievalService;
    private final AnswerService answerService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KbAccessGuard kbAccessGuard;

    @Tool(name = "search_knowledge",
            description = """
        搜索 RAGForge 知识库，返回最相关的文本片段。
        支持混合检索（向量 + 关键词）。
        适合用于：简历分析、JD 匹配、面试题参考、行业知识查询。
        """)
    public String searchKnowledgeBase(
            @ToolParam(description = "搜索查询内容") String query,
            @ToolParam(description = "知识库 ID 列表，逗号分隔，例如 '15,16'。不填则搜索所有 KB") String kbIds,
            @ToolParam(description = "返回结果数量，默认 5，最大 10") int topK) {
        try {
            List<Long> kbIdList = parseKbIds(kbIds);
            Set<Long> readableKbIds = kbIdList.isEmpty()
                    ? kbAccessGuard.allReadableKbIds()
                    : kbAccessGuard.filterReadable(kbIdList);
            if (readableKbIds.isEmpty()) {
                return "没有可访问的知识库。";
            }
            int k = Math.min(Math.max(topK <= 0 ? 5 : topK, 1), 10);

            RetrievalOutput output =
                    retrievalService.retrieve(query, new ArrayList<>(readableKbIds), null, "hybrid", null, k, 5, null);

            if (output.getResults() == null || output.getResults().isEmpty()) {
                return "未找到相关内容（query=" + query + "）";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(output.getResults().size()).append(" 条相关内容：\n\n");
            for (int i = 0; i < output.getResults().size(); i++) {
                var r = output.getResults().get(i);
                sb.append("[").append(i + 1).append("] ");
                if (r.getFilename() != null) {
                    sb.append("来源：").append(r.getFilename()).append("\n");
                }
                sb.append(r.getContent()).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("MCP searchKnowledgeBase failed: {}", e.getMessage());
            return "搜索失败：" + e.getMessage();
        }
    }

    @Tool(
            name = "list_knowledge_bases",
            description = "列出 RAGForge 中所有可用的知识库，包含名称、文档数、片段数等信息")
    public String listKnowledgeBases() {
        try {
            List<KnowledgeBase> kbs =
                    knowledgeBaseMapper.selectList(
                            new LambdaQueryWrapper<KnowledgeBase>()
                                    .ne(KnowledgeBase::getStatus, "deleted")
                                    .orderByAsc(KnowledgeBase::getId));
            Set<Long> readableKbIds = kbAccessGuard.allReadableKbIds();
            kbs = kbs.stream()
                    .filter(kb -> readableKbIds.contains(kb.getId()))
                    .collect(Collectors.toList());
            if (kbs.isEmpty()) {
                return "当前没有可用的知识库。";
            }
            StringBuilder sb = new StringBuilder("可用知识库列表：\n\n");
            for (KnowledgeBase kb : kbs) {
                sb.append("- ID=").append(kb.getId())
                        .append(" 名称=").append(kb.getName())
                        .append(" 文档数=").append(kb.getDocCount() == null ? 0 : kb.getDocCount())
                        .append(" 片段数=").append(kb.getChunkCount() == null ? 0 : kb.getChunkCount());
                if (kb.getDescription() != null && !kb.getDescription().isBlank()) {
                    sb.append(" 描述=").append(kb.getDescription());
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("MCP listKnowledgeBases failed: {}", e.getMessage());
            return "获取知识库列表失败：" + e.getMessage();
        }
    }

    @Tool(name = "answer_with_citations",
            description = "用 RAGForge 知识库回答问题，返回带引用的答案（含图片 URL）")
    public AnswerResponse answer(
            @ToolParam(description = "用户问题") String query,
            @ToolParam(description = "知识库 ID 列表") List<Long> kbIds) {
        List<Long> requested = kbIds == null ? List.of() : kbIds;
        Set<Long> readableKbIds = requested.isEmpty()
                ? kbAccessGuard.allReadableKbIds()
                : kbAccessGuard.filterReadable(requested);
        if (readableKbIds.isEmpty()) {
            throw new IllegalArgumentException("没有可访问的知识库。");
        }
        AnswerRequest request = new AnswerRequest();
        request.setQuery(query);
        request.setKbIds(new ArrayList<>(readableKbIds));
        request.setRetrievalStrategy("hybrid");
        request.setAnswerMode("ON");
        request.setStream(false);
        request.setTopK(10);
        request.setMaxTokens(800);
        return answerService.answerBlocking(request);
    }

    private List<Long> parseKbIds(String kbIds) {
        if (kbIds == null || kbIds.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(kbIds.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .map(Long::parseLong)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }
}
