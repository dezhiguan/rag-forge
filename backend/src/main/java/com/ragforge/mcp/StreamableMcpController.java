package com.ragforge.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StreamableMcpController {

  private static final String PROTOCOL_VERSION = "2025-03-26";
  private final RagForgeMcpTools tools;

  /**
   * Cursor Streamable HTTP 可能对 /mcp 发 GET（Accept: text/event-stream）。
   * 本端点仅支持无状态 POST；显式返回 JSON 405，避免落入 /error 后再被鉴权成 LOGIN_REQUIRED。
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> getNotAllowed() {
    return ResponseEntity.status(405)
        .header("Allow", "POST")
        .body(
            orderedMap(
                "jsonrpc", "2.0",
                "error",
                orderedMap(
                    "code", -32600,
                    "message", "RAGForge MCP is stateless POST /mcp only (Streamable HTTP JSON-RPC).")));
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> handle(@RequestBody Map<String, Object> request) {
    Object id = request.get("id");
    String method = asString(request.get("method"));
    if (id == null) {
      return ResponseEntity.accepted().build();
    }

    return switch (method) {
      case "initialize" -> ok(id, initializeResult(params(request)));
      case "tools/list" -> ok(id, toolsListResult());
      case "tools/call" -> ok(id, callTool(params(request)));
      case "ping" -> ok(id, Map.of());
      default -> error(id, -32601, "方法不存在：" + method);
    };
  }

  private Map<String, Object> initializeResult(Map<String, Object> params) {
    return orderedMap(
        "protocolVersion", protocolVersion(params),
        "capabilities", Map.of("tools", Map.of("listChanged", false)),
        "serverInfo", Map.of("name", "ragforge-mcp-server", "version", "1.0.0"),
        "instructions",
            """
            Call list_knowledge_bases before search_knowledge unless the user explicitly provided knowledge base ids.
            Choose the most relevant readable knowledge base ids by name and description, then pass them to search_knowledge.kbIds.
            Search private or sensitive knowledge bases only when the user explicitly asks for that scope.
            """);
  }

  private Map<String, Object> toolsListResult() {
    Map<String, Object> searchProperties = orderedMap(
        "query", orderedMap("type", "string", "description", "Search query."),
        "kbIds", orderedMap("type", "string", "description", "Optional comma-separated knowledge base ids."),
        "topK",
        orderedMap(
            "type",
            "integer",
            "description",
            "Number of results, default 5, range 1-10 (values outside are clamped to the nearest bound, not rejected)."));
    Map<String, Object> searchInputSchema = orderedMap(
        "type", "object",
        "properties", searchProperties,
        "required", List.of("query"));
    Map<String, Object> listTool = orderedMap(
        "name", "list_knowledge_bases",
        "description", "List readable RAGForge knowledge bases with ids, names, counts, and descriptions.",
        "inputSchema", orderedMap("type", "object", "properties", Map.of()));
    Map<String, Object> searchTool = orderedMap(
        "name", "search_knowledge",
        "description",
            "Search readable RAGForge knowledge bases with hybrid retrieval and return cited snippets. Prefer passing kbIds selected from list_knowledge_bases.",
        "inputSchema", searchInputSchema);
    Map<String, Object> answerProperties = orderedMap(
        "query", orderedMap("type", "string", "description", "Question to answer."),
        "kbIds", orderedMap("type", "string", "description", "Optional comma-separated knowledge base ids."));
    Map<String, Object> answerInputSchema = orderedMap(
        "type", "object",
        "properties", answerProperties,
        "required", List.of("query"));
    Map<String, Object> answerTool = orderedMap(
        "name", "answer_with_citations",
        "description",
            "Answer a question grounded in readable RAGForge knowledge bases, returning a cited answer (citations may include image URLs).",
        "inputSchema", answerInputSchema);
    return Map.of("tools", List.of(listTool, searchTool, answerTool));
  }

  private Map<String, Object> callTool(Map<String, Object> params) {
    String name = asString(params.get("name"));
    if ("list_knowledge_bases".equals(name) || "listKnowledgeBases".equals(name)) {
      return toolText(tools.listKnowledgeBases(), false);
    }
    if ("search_knowledge".equals(name) || "searchKnowledgeBase".equals(name)) {
      Map<String, Object> arguments = map(params.get("arguments"));
      String query = asString(arguments.get("query"));
      if (query == null || query.isBlank()) {
        return toolText("参数错误：缺少必填参数 query。", true);
      }
      String kbIds = normalizeKbIds(arguments.get("kbIds"));
      int topK = intValue(arguments.get("topK"), 5);
      String result = tools.searchKnowledgeBase(query, kbIds, topK);
      // 参数错误、后端检索故障、无可访问库均置 isError=true，避免调用方把错误提示当成检索结果喂给 LLM（与 answer 分支口径一致）。
      boolean isError =
          result.startsWith("参数错误：")
              || result.startsWith("搜索失败：")
              || result.startsWith("没有可访问");
      return toolText(result, isError);
    }
    // answer_with_citations：无状态 /mcp 直接接线，复用 RagForgeMcpTools 的越权过滤与检索策略。
    if ("answer_with_citations".equals(name) || "answer".equals(name)) {
      Map<String, Object> arguments = map(params.get("arguments"));
      String query = asString(arguments.get("query"));
      if (query == null || query.isBlank()) {
        return toolText("参数错误：缺少必填参数 query。", true);
      }
      String kbIds = normalizeKbIds(arguments.get("kbIds"));
      String result = tools.answerWithCitations(query, kbIds);
      boolean isError =
          result.startsWith("参数错误：")
              || result.startsWith("应答失败：")
              || result.startsWith("没有可访问");
      return toolText(result, isError);
    }
    // name 缺失单独提示，避免出现机器化的“未知工具：null”。
    if (name == null || name.isBlank()) {
      return toolText("参数错误：缺少工具名称 name。", true);
    }
    return toolText("未知工具：" + name, true);
  }

  private ResponseEntity<Map<String, Object>> ok(Object id, Map<String, Object> result) {
    return ResponseEntity.ok(jsonRpc("id", id, "result", result));
  }

  private ResponseEntity<Map<String, Object>> error(Object id, int code, String message) {
    return ResponseEntity.ok(jsonRpc("id", id, "error", Map.of("code", code, "message", message)));
  }

  private Map<String, Object> toolText(String text, boolean isError) {
    return orderedMap("content", List.of(Map.of("type", "text", "text", text)), "isError", isError);
  }

  private Map<String, Object> jsonRpc(Object... values) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("jsonrpc", "2.0");
    for (int i = 0; i < values.length; i += 2) {
      response.put(String.valueOf(values[i]), values[i + 1]);
    }
    return response;
  }

  private static Map<String, Object> orderedMap(Object... values) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) {
      map.put(String.valueOf(values[i]), values[i + 1]);
    }
    return map;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> params(Map<String, Object> request) {
    return map(request.get("params"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
  }

  private static String normalizeKbIds(Object value) {
    if (value instanceof List<?> values) {
      return values.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }
    return asString(value);
  }

  private static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static int intValue(Object value, int fallback) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return value == null ? fallback : Integer.parseInt(String.valueOf(value));
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static String protocolVersion(Map<String, Object> params) {
    String requested = asString(params.get("protocolVersion"));
    return requested == null || requested.isBlank() ? PROTOCOL_VERSION : requested;
  }
}
