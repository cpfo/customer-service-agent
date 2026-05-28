package com.example.customeragent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class DashScopeReranker {

    private static final Logger log = LoggerFactory.getLogger(DashScopeReranker.class);
    private static final String RERANK_URL = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final String apiKey;
    private final String model;
    private final int topK;
    private final boolean enabled;
    private final ObjectMapper objectMapper;

    public DashScopeReranker(
            @Value("${spring.ai.dashscope.api-key}") String apiKey,
            @Value("${app.reranker.cross-encoder-model:gte-rerank}") String model,
            @Value("${app.reranker.top-k:3}") int topK,
            @Value("${app.reranker.type:cross-encoder}") String type,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.topK = topK;
        this.enabled = "cross-encoder".equalsIgnoreCase(type);
        this.objectMapper = objectMapper;
    }

    public List<Document> rerank(String query, List<Document> documents) {
        if (!enabled) {
            log.debug("交叉编码器重排序已禁用");
            return documents;
        }
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        if (documents.size() <= 1) {
            return documents;
        }

        try {
            return callRerankApi(query, documents);
        } catch (Exception e) {
            log.warn("交叉编码器重排序失败，返回原顺序: {}", e.getMessage());
            return documents;
        }
    }

    private List<Document> callRerankApi(String query, List<Document> documents) throws Exception {
        List<String> texts = documents.stream()
                .map(Document::getText)
                .collect(Collectors.toList());

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("query", query);
        ArrayNode docsArray = input.putArray("documents");
        for (String text : texts) {
            docsArray.add(text);
        }
        requestBody.set("input", input);

        RestTemplate restTemplate = new RestTemplate();
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.set("Content-Type", "application/json");
        org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(
                objectMapper.writeValueAsString(requestBody), headers);

        log.info("交叉编码器重排序: query=\"{}\", {} 条文档, model={}",
                truncate(query, 50), documents.size(), model);

        long start = System.currentTimeMillis();
        String response = restTemplate.postForObject(RERANK_URL, entity, String.class);
        long elapsed = System.currentTimeMillis() - start;
        log.info("交叉编码器重排序完成，耗时 {}ms", elapsed);

        if (response == null) {
            log.warn("交叉编码器 API 返回空");
            return documents;
        }

        JsonNode root = objectMapper.readTree(response);
        JsonNode results = root.path("output").path("results");
        if (!results.isArray()) {
            log.warn("交叉编码器响应格式异常，缺少 output.results");
            return documents;
        }

        List<RerankResult> reranked = new ArrayList<>();
        for (JsonNode item : results) {
            int idx = item.path("index").asInt();
            double score = item.path("relevance_score").asDouble();
            reranked.add(new RerankResult(idx, score));
        }

        reranked.sort((a, b) -> Double.compare(b.score, a.score));
        int showIdx = Math.min(topK - 1, reranked.size() - 1);
        if (!reranked.isEmpty()) {
            log.info("重排序 top1={} top{}=({}条中取{}条)",
                    String.format("%.4f", reranked.get(0).score),
                    showIdx + 1,
                    reranked.size(),
                    Math.min(topK, reranked.size()));
        }

        List<Document> result = new ArrayList<>();
        int limit = Math.min(topK, reranked.size());
        for (int i = 0; i < limit; i++) {
            RerankResult rr = reranked.get(i);
            Document doc = documents.get(rr.index);
            doc.getMetadata().put("rerank_score", String.valueOf(rr.score));
            result.add(doc);
        }
        return result;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private record RerankResult(int index, double score) {}
}
