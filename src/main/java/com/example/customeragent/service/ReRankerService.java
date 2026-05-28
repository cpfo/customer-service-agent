package com.example.customeragent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class ReRankerService {

    private static final Logger log = LoggerFactory.getLogger(ReRankerService.class);

    private static final double VECTOR_WEIGHT = 0.7;
    private static final double KEYWORD_WEIGHT = 0.3;

    private final int topK;

    public ReRankerService(@Value("${app.reranker.top-k:3}") int topK) {
        this.topK = topK;
    }

    public List<Document> reRank(String query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            log.warn("重排序输入为空，查询: {}", truncate(query, 100));
            return List.of();
        }

        log.info("重排序输入: {} 条文档, 查询: \"{}\"", documents.size(), truncate(query, 50));

        if (documents.size() <= topK) {
            log.info("文档数({}) <= topK({}), 跳过重排序", documents.size(), topK);
            return documents;
        }

        Set<String> queryTerms = tokenize(query);
        log.debug("查询分词: {}", queryTerms);

        List<Map.Entry<Document, Double>> scored = IntStream.range(0, documents.size())
                .mapToObj(i -> {
                    Document doc = documents.get(i);
                    double vectorScore = Math.max(0, doc.getScore());
                    double keywordScore = computeKeywordOverlap(queryTerms, doc.getText());
                    double combined = vectorScore * VECTOR_WEIGHT + keywordScore * KEYWORD_WEIGHT;
                    if (log.isTraceEnabled()) {
                        log.trace("文档[{}] 向量分={} 关键词分={} 综合={} 预览={}",
                                i, String.format("%.4f", vectorScore),
                                String.format("%.4f", keywordScore),
                                String.format("%.4f", combined),
                                truncate(doc.getText(), 60));
                    }
                    return new AbstractMap.SimpleEntry<>(doc, combined);
                })
                .sorted(Map.Entry.<Document, Double>comparingByValue().reversed())
                .collect(Collectors.toList());

        int showIdx = Math.min(topK, scored.size()) - 1;
        log.info("重排序完成, top1: {}, top{}: {}",
                String.format("%.4f", scored.get(0).getValue()),
                showIdx + 1,
                String.format("%.4f", scored.get(showIdx).getValue()));

        List<Document> result = scored.stream()
                .limit(topK)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        log.info("重排序输出: {} 条文档", result.size());
        return result;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().split("[\\s\\p{P}]+"))
                .filter(s -> s.length() >= 2)
                .collect(Collectors.toSet());
    }

    private double computeKeywordOverlap(Set<String> queryTerms, String docText) {
        if (queryTerms.isEmpty()) return 0;
        Set<String> docTerms = tokenize(docText);
        Set<String> intersection = new HashSet<>(queryTerms);
        intersection.retainAll(docTerms);
        return (double) intersection.size() / queryTerms.size();
    }
}
