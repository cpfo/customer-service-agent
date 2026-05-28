package com.example.customeragent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import redis.clients.jedis.JedisPooled;

@Configuration
public class RAGConfig {

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
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
