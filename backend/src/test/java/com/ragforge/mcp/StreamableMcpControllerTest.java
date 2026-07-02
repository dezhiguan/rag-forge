package com.ragforge.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class StreamableMcpControllerTest {

  @Mock private RagForgeMcpTools tools;

  @Test
  void initialize_returnsToolCapabilities() {
    StreamableMcpController controller = new StreamableMcpController(tools);

    ResponseEntity<?> response =
            controller.handle(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of()));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> body = (Map<?, ?>) response.getBody();
    Map<?, ?> result = (Map<?, ?>) body.get("result");
    assertThat(result.get("protocolVersion")).isEqualTo("2025-03-26");
    assertThat(result.get("capabilities")).isEqualTo(Map.of("tools", Map.of("listChanged", false)));
  }

  @Test
  void initialize_echoesClientProtocolVersion() {
    StreamableMcpController controller = new StreamableMcpController(tools);

    ResponseEntity<?> response =
        controller.handle(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                1,
                "method",
                "initialize",
                "params",
                Map.of("protocolVersion", "2025-06-18")));

    Map<?, ?> body = (Map<?, ?>) response.getBody();
    Map<?, ?> result = (Map<?, ?>) body.get("result");
    assertThat(result.get("protocolVersion")).isEqualTo("2025-06-18");
  }

  @Test
  void toolsList_exposesListSearchAndAnswerTools() {
    StreamableMcpController controller = new StreamableMcpController(tools);

    ResponseEntity<?> response =
        controller.handle(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list", "params", Map.of()));

    Map<?, ?> body = (Map<?, ?>) response.getBody();
    Map<?, ?> result = (Map<?, ?>) body.get("result");
    List<?> toolList = (List<?>) result.get("tools");
    List<String> toolNames = toolList.stream().map(tool -> String.valueOf(((Map<?, ?>) tool).get("name"))).toList();
    // /mcp 现无状态暴露三工具（含 answer_with_citations），tools/list 只登记 snake_case 名。
    assertThat(toolNames)
        .containsExactly("list_knowledge_bases", "search_knowledge", "answer_with_citations");
    assertThat(((Map<?, ?>) toolList.getFirst()).get("inputSchema")).isInstanceOf(Map.class);
    assertThat(((Map<?, ?>) toolList.get(1)).get("inputSchema")).isInstanceOf(Map.class);
    assertThat(((Map<?, ?>) toolList.get(2)).get("inputSchema")).isInstanceOf(Map.class);
  }

  @Test
  void toolsCall_invokesListKnowledgeBases() {
    when(tools.listKnowledgeBases()).thenReturn("available kbs");
    StreamableMcpController controller = new StreamableMcpController(tools);

    ResponseEntity<?> response =
        controller.handle(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                3,
                "method",
                "tools/call",
                "params",
                Map.of("name", "list_knowledge_bases", "arguments", Map.of())));

    Map<?, ?> body = (Map<?, ?>) response.getBody();
    Map<?, ?> result = (Map<?, ?>) body.get("result");
    List<?> content = (List<?>) result.get("content");
    assertThat(((Map<?, ?>) content.getFirst()).get("text")).isEqualTo("available kbs");
    assertThat(result.get("isError")).isEqualTo(false);
  }

  @Test
  void toolsCall_invokesSearchKnowledge() {
    when(tools.searchKnowledgeBase("java", "15,16", 3)).thenReturn("found");
    StreamableMcpController controller = new StreamableMcpController(tools);

    ResponseEntity<?> response =
        controller.handle(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                3,
                "method",
                "tools/call",
                "params",
                Map.of(
                    "name",
                    "search_knowledge",
                    "arguments",
                    Map.of("query", "java", "kbIds", List.of(15, 16), "topK", 3))));

    Map<?, ?> body = (Map<?, ?>) response.getBody();
    Map<?, ?> result = (Map<?, ?>) body.get("result");
    List<?> content = (List<?>) result.get("content");
    assertThat(((Map<?, ?>) content.getFirst()).get("text")).isEqualTo("found");
    assertThat(result.get("isError")).isEqualTo(false);
  }

  @Test
  void toolsCall_searchBackendFailure_marksIsError() {
    // 后端检索故障返回「搜索失败：…」应置 isError=true，避免被 MCP 客户端当成正常检索结果（BUG-1）。
    when(tools.searchKnowledgeBase("java", null, 5)).thenReturn("搜索失败：ES is down");
    StreamableMcpController controller = new StreamableMcpController(tools);

    ResponseEntity<?> response =
        controller.handle(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                3,
                "method",
                "tools/call",
                "params",
                Map.of("name", "search_knowledge", "arguments", Map.of("query", "java"))));

    Map<?, ?> body = (Map<?, ?>) response.getBody();
    Map<?, ?> result = (Map<?, ?>) body.get("result");
    assertThat(result.get("isError")).isEqualTo(true);
  }

  @Test
  void toolsCall_searchNoAccessibleKb_marksIsError() {
    // 「没有可访问的知识库。」同样置 isError=true，口径与 answer 分支一致（BUG-1）。
    when(tools.searchKnowledgeBase("java", null, 5)).thenReturn("没有可访问的知识库。");
    StreamableMcpController controller = new StreamableMcpController(tools);

    ResponseEntity<?> response =
        controller.handle(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                3,
                "method",
                "tools/call",
                "params",
                Map.of("name", "search_knowledge", "arguments", Map.of("query", "java"))));

    Map<?, ?> body = (Map<?, ?>) response.getBody();
    Map<?, ?> result = (Map<?, ?>) body.get("result");
    assertThat(result.get("isError")).isEqualTo(true);
  }

  @Test
  void notification_returnsAccepted() {
    StreamableMcpController controller = new StreamableMcpController(tools);

    ResponseEntity<?> response =
        controller.handle(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }
}
