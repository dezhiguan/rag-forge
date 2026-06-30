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
  void toolsList_exposesSearchKnowledgeTool() {
    StreamableMcpController controller = new StreamableMcpController(tools);

    ResponseEntity<?> response =
        controller.handle(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list", "params", Map.of()));

    Map<?, ?> body = (Map<?, ?>) response.getBody();
    Map<?, ?> result = (Map<?, ?>) body.get("result");
    List<?> toolList = (List<?>) result.get("tools");
    Map<?, ?> tool = (Map<?, ?>) toolList.getFirst();
    assertThat(tool.get("name")).isEqualTo("search_knowledge");
    assertThat(tool.get("inputSchema")).isInstanceOf(Map.class);
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
  void notification_returnsAccepted() {
    StreamableMcpController controller = new StreamableMcpController(tools);

    ResponseEntity<?> response =
        controller.handle(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }
}
