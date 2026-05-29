package com.example.customeragent.config;

import com.example.customeragent.service.CustomerTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;

@Configuration
public class RAGConfig {

    private static final Logger logger = LoggerFactory.getLogger(RAGConfig.class);

    @Bean
    public JedisPooled jedisPooled(@Value("${spring.redis.host:localhost}") String host,
                                   @Value("${spring.redis.port:6379}") int port) {
        return new JedisPooled(host, port);
    }

    @Bean
    public RedisVectorStore redisVectorStore(EmbeddingModel embeddingModel, JedisPooled jedisPooled) {
        return RedisVectorStore.builder(jedisPooled, embeddingModel)
                .indexName("customer-service-vector-index")
                .initializeSchema(true)
                .build();
    }

    @Bean
    public VectorStore vectorStore(RedisVectorStore redisVectorStore) {
        return redisVectorStore;
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 CustomerTools customerTools,
                                 ToolCallbackProvider mcpToolCallbackProvider) {

        System.out.println("mcp 服务数量" + mcpToolCallbackProvider.getToolCallbacks().length);
        return builder
                .defaultTools(customerTools)
                .defaultToolCallbacks(mcpToolCallbackProvider)
                .build();
    }
}
