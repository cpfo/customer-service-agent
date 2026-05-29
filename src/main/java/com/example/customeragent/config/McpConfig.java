package com.example.customeragent.config;

import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    @Bean
    public McpToolNamePrefixGenerator mcpToolNamePrefixGenerator() {
        return McpToolNamePrefixGenerator.noPrefix();
    }

    @Bean
    public ApplicationRunner logMcpTools(List<McpSyncClient> mcpSyncClients) {
        return args -> {
            for (McpSyncClient client : mcpSyncClients) {
                try {
                    var result = client.listTools();
                    var tools = result.tools();
                    log.info("MCP 客户端工具列表 ({} 个):", tools.size());
                    tools.forEach(tool -> log.info("  └─ {}", tool.name()));
                } catch (Exception e) {
                    log.warn("获取 MCP 工具列表失败: {}", e.getMessage());
                }
            }
        };
    }
}
