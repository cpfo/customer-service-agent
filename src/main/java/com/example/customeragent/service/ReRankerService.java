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
            return List.of();
        }

        if (documents.size() <= topK) {
            return documents;
        }

        Set<String> queryTerms = tokenize(query);

        List<Map.Entry<Document, Double>> scored = IntStream.range(0, documents.size())
                .mapToObj(i -> {
                    Document doc = documents.get(i);
                    double vectorScore = Math.max(0, doc.getScore());
                    double keywordScore = computeKeywordOverlap(queryTerms, doc.getText());
                    double combined = vectorScore * VECTOR_WEIGHT + keywordScore * KEYWORD_WEIGHT;
                    return new AbstractMap.SimpleEntry<>(doc, combined);
                })
                .sorted(Map.Entry.<Document, Double>comparingByValue().reversed())
                .collect(Collectors.toList());

        log.debug("重排序完成，top1 分数: {}, bottom 分数: {}",
                String.format("%.4f", scored.get(0).getValue()),
                String.format("%.4f", scored.get(scored.size() - 1).getValue()));

        return scored.stream()
                .limit(topK)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
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
