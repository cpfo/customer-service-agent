package com.example.customeragent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    private static final String REWRITE_PROMPT = """
            你是一个电商客服搜索助手。请将用户的问题改写成%d种不同的搜索查询，便于从知识库中检索相关信息。
            要求：
            - 每条改写应侧重不同的角度或关键词
            - 去除口语化表达，使用正式、精炼的书面语
            - 每条一行，不要编号
            - 如果用户问题已经很清晰，可以保留原意但换种表述

            用户问题：%s
            """;

    private final ChatClient chatClient;
    private final boolean enabled;
    private final int numRewrites;

    public QueryRewriter(ChatClient chatClient,
                         @Value("${app.query-rewriting.enabled:true}") boolean enabled,
                         @Value("${app.query-rewriting.num-rewrites:2}") int numRewrites) {
        this.chatClient = chatClient;
        this.enabled = enabled;
        this.numRewrites = Math.min(Math.max(numRewrites, 1), 5);
    }

    public List<String> rewrite(String originalQuery) {
        if (!enabled) {
            log.debug("查询改写已禁用，使用原句");
            return List.of(originalQuery);
        }

        try {
            String response = chatClient.prompt()
                    .system(REWRITE_PROMPT.formatted(numRewrites, originalQuery))
                    .user(originalQuery)
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                log.warn("查询改写返回空，使用原句");
                return List.of(originalQuery);
            }

            Set<String> rewrites = new LinkedHashSet<>();
            rewrites.add(originalQuery);
            for (String line : response.split("\n")) {
                String trimmed = line.trim()
                        .replaceAll("^\\d+[.、)\\s]*", "")
                        .replaceAll("^[\\-*]\\s*", "")
                        .replaceAll("[\"\"']", "")
                        .trim();
                if (!trimmed.isBlank() && !trimmed.equalsIgnoreCase(originalQuery)) {
                    rewrites.add(trimmed);
                }
            }

            List<String> result = new ArrayList<>(rewrites);
            log.info("查询改写: \"{}\" → {} ({}条)", truncate(originalQuery, 30),
                    result, result.size());
            return result;
        } catch (Exception e) {
            log.warn("查询改写失败，使用原句: {}", e.getMessage());
            return List.of(originalQuery);
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
