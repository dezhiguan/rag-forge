package com.ragforge.mcp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ragforge.answer.AnswerModels.AnswerRequest;
import com.ragforge.answer.AnswerModels.AnswerResponse;
import com.ragforge.answer.AnswerModels.Citation;
import com.ragforge.common.BizException;
import com.ragforge.common.ErrorMessages;
import com.ragforge.answer.AnswerService;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.search.RetrievalService;
import com.ragforge.search.RetrievalService.RetrievalOutput;
import com.ragforge.search.SearchResult;
import com.ragforge.security.KbAccessGuard;
import com.ragforge.storage.ChunkImageResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagForgeMcpTools {

    private final RetrievalService retrievalService;
    private final AnswerService answerService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final KbAccessGuard kbAccessGuard;
    private final ChunkImageResolver chunkImageResolver;

    @Tool(name = "search_knowledge",
            description = """
        搜索 RAGForge 知识库，返回最相关的文本片段。
        支持混合检索（向量 + 关键词）。
        若命中的片段为图片，会附带一个有时效的图片可访问 URL（"图片："行）。
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

            List<SearchResult> results = output.getResults();
            // 批量回填图片可访问 URL：一条 JOIN 取 image_key+bucket 再批量预签名（仅 IMAGE chunk 有值）。
            Map<Long, String> imageUrls =
                    chunkImageResolver.presignedUrls(
                            results.stream()
                                    .map(SearchResult::getChunkId)
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.toList()));

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(results.size()).append(" 条相关内容：\n\n");
            for (int i = 0; i < results.size(); i++) {
                SearchResult r = results.get(i);
                sb.append("[").append(i + 1).append("] ");
                if (r.getFilename() != null) {
                    sb.append("来源：").append(r.getFilename()).append("\n");
                }
                sb.append(r.getContent()).append("\n");
                String imageUrl = imageUrls.get(r.getChunkId());
                if (imageUrl != null && !imageUrl.isBlank()) {
                    sb.append("图片：").append(imageUrl).append("\n");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (IllegalArgumentException e) {
            // 参数错误（如 kbIds 格式非法）：显式回报，避免调用方误以为已限定知识库而实际被静默忽略。
            return "参数错误：" + e.getMessage();
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
            // 用实时聚合的文档数覆盖 knowledge_bases.doc_count（该计数器在失败/删除/replace 等非常规路径会漂移，
            // 曾出现"文档数=0"误导 LLM 判空跳过），口径与 KnowledgeBaseServiceImpl.applyRealCounts 一致。批量 group by 避免 N+1。
            Map<Long, Integer> realDocCounts = countDocsByKb(
                    kbs.stream().map(KnowledgeBase::getId).collect(Collectors.toList()));
            StringBuilder sb = new StringBuilder("可用知识库列表：\n\n");
            for (KnowledgeBase kb : kbs) {
                sb.append("- ID=").append(kb.getId())
                        .append(" 名称=").append(kb.getName())
                        .append(" 文档数=").append(realDocCounts.getOrDefault(kb.getId(), 0))
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

    /** 批量实时统计各库文档数（一次 group by），规避 knowledge_bases.doc_count 计数器漂移。 */
    private Map<Long, Integer> countDocsByKb(List<Long> kbIds) {
        Map<Long, Integer> counts = new HashMap<>();
        if (kbIds == null || kbIds.isEmpty()) {
            return counts;
        }
        List<Map<String, Object>> rows =
                documentMapper.selectMaps(
                        new QueryWrapper<Document>()
                                .select("kb_id", "count(*) AS cnt")
                                .in("kb_id", kbIds)
                                .groupBy("kb_id"));
        if (rows == null) {
            return counts;
        }
        for (Map<String, Object> row : rows) {
            Object kbId = row.get("kb_id");
            Object cnt = row.get("cnt");
            if (kbId instanceof Number && cnt instanceof Number) {
                counts.put(((Number) kbId).longValue(), ((Number) cnt).intValue());
            }
        }
        return counts;
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

    /**
     * 无状态 /mcp（StreamableMcpController）调用入口：带引用应答，返回渲染后的文本。
     * 复用 {@link #answer(String, List)} 的越权过滤（KbAccessGuard）与检索策略；异常统一转友好文案，绝不抛到 500。
     */
    public String answerWithCitations(String query, String kbIds) {
        try {
            List<Long> kbIdList = parseKbIds(kbIds);
            AnswerResponse resp = answer(query, kbIdList);
            return formatAnswer(resp);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            // 无可读库时保持与检索一致的干净文案；其余参数问题（如 kbIds 格式）加"参数错误："前缀。
            return msg != null && msg.startsWith("没有可访问") ? msg : "参数错误：" + msg;
        } catch (BizException e) {
            // 业务码转友好中文，避免把机器码（如 ANSWER_DISABLED）直接透给 MCP 客户端/LLM。
            return "应答失败：" + friendlyAnswerError(e.getMessage());
        } catch (Exception e) {
            // 兜底：仅记录内部原因，对外回固定友好文案，不外泄原始异常信息。
            log.warn("MCP answerWithCitations failed: {}", e.getMessage());
            return "应答失败：应答服务暂时不可用，请稍后重试。";
        }
    }

    /** 把应答业务码翻成对 MCP 客户端友好的中文提示。 */
    private String friendlyAnswerError(String code) {
        if ("ANSWER_DISABLED".equals(code)) {
            return "该知识库未开启应答功能，请先在知识库设置中开启应答，或改用 search_knowledge 检索。";
        }
        String cn = ErrorMessages.toChinese(code);
        // toChinese 对未收录码回退原码；此时给固定友好兜底，避免机器码外露。
        return cn == null || cn.equals(code) ? "应答服务暂时不可用，请稍后重试。" : cn;
    }

    /** 将带引用的应答渲染为 MCP 文本内容：答案正文 + 引用列表（含图片 URL，若有）。 */
    private String formatAnswer(AnswerResponse resp) {
        if (resp == null || resp.getAnswer() == null || resp.getAnswer().isBlank()) {
            return "未生成应答。";
        }
        StringBuilder sb = new StringBuilder(resp.getAnswer());
        List<Citation> citations = resp.getCitations();
        if (citations != null && !citations.isEmpty()) {
            sb.append("\n\n引用：\n");
            for (Citation c : citations) {
                sb.append("[").append(c.getId()).append("] ");
                if (c.getTextSnippet() != null && !c.getTextSnippet().isBlank()) {
                    sb.append(c.getTextSnippet());
                }
                sb.append("\n");
                if (c.getImageUrl() != null && !c.getImageUrl().isBlank()) {
                    sb.append("图片：").append(c.getImageUrl()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 解析逗号分隔的 kbIds。空/未传 → 空列表（表示"搜索所有可访问库"）；
     * 传了但含非法 token（如 "abc"）→ 抛 IllegalArgumentException，而不是静默返回空列表——
     * 否则会退化为"搜索全部库"，调用方以为已限定范围实则被忽略（危险的边界）。
     */
    private List<Long> parseKbIds(String kbIds) {
        if (kbIds == null || kbIds.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String token : kbIds.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "kbIds 格式错误，应为逗号分隔的知识库 ID（如 \"15,16\"），无法解析：" + trimmed);
            }
        }
        return ids;
    }
}
