package com.ragforge.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
      default -> error(id, -32601, "Method not found: " + method);
    };
  }

  private Map<String, Object> initializeResult(Map<String, Object> params) {
    return orderedMap(
        "protocolVersion", protocolVersion(params),
        "capabilities", Map.of("tools", Map.of("listChanged", false)),
        "serverInfo", Map.of("name", "ragforge-mcp-server", "version", "1.0.0"),
        "instructions", "Use search_knowledge to search the caller's readable RAGForge knowledge bases.");
  }

  private Map<String, Object> toolsListResult() {
    Map<String, Object> properties = orderedMap(
        "query", orderedMap("type", "string", "description", "Search query."),
        "kbIds", orderedMap("type", "string", "description", "Optional comma-separated knowledge base ids."),
        "topK", orderedMap("type", "integer", "description", "Number of results, default 5, maximum 10."));
    Map<String, Object> inputSchema = orderedMap(
        "type", "object",
        "properties", properties,
        "required", List.of("query"));
    Map<String, Object> tool = orderedMap(
        "name", "search_knowledge",
        "description", "Search readable RAGForge knowledge bases with hybrid retrieval and return cited snippets.",
        "inputSchema", inputSchema);
    return Map.of("tools", List.of(tool));
  }

  private Map<String, Object> callTool(Map<String, Object> params) {
    String name = asString(params.get("name"));
    if (!"search_knowledge".equals(name) && !"searchKnowledgeBase".equals(name)) {
      return toolText("Unknown tool: " + name, true);
    }
    Map<String, Object> arguments = map(params.get("arguments"));
    String query = asString(arguments.get("query"));
    if (query == null || query.isBlank()) {
      return toolText("Missing required argument: query", true);
    }
    String kbIds = normalizeKbIds(arguments.get("kbIds"));
    int topK = intValue(arguments.get("topK"), 5);
    String result = tools.searchKnowledgeBase(query, kbIds, topK);
    return toolText(result, false);
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
