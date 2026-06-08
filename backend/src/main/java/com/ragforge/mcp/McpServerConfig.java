package com.ragforge.mcp;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class McpServerConfig {

    private final RagForgeMcpTools ragForgeMcpTools;

    @Bean
    public ToolCallbackProvider ragForgeToolCallbackProvider() {
        return MethodToolCallbackProvider.builder()
                .toolObjects(ragForgeMcpTools)
                .build();
    }
}
