package com.example.customeragent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class BM25Index {

    private static final Logger log = LoggerFactory.getLogger(BM25Index.class);

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final DocumentStore documentStore;

    private List<String> allTokens;
    private Map<String, Double> idfCache;
    private double avgDocLength;
    private boolean built;

    public BM25Index(DocumentStore documentStore) {
        this.documentStore = documentStore;
        this.allTokens = List.of();
        this.idfCache = Map.of();
        this.avgDocLength = 0;
        this.built = false;
    }

    public synchronized void rebuild() {
        List<Document> docs = documentStore.getAll();
        if (docs.isEmpty()) {
            log.warn("BM25Index 重建失败: DocumentStore 为空");
            return;
        }

        List<List<String>> tokenizedDocs = docs.stream()
                .map(d -> tokenize(d.getText()))
                .collect(Collectors.toList());

        Set<String> vocab = new HashSet<>();
        Map<String, Integer> df = new HashMap<>();
        long totalTerms = 0;

        for (List<String> tokens : tokenizedDocs) {
            Set<String> uniqueInDoc = new HashSet<>(tokens);
            for (String token : uniqueInDoc) {
                df.merge(token, 1, Integer::sum);
            }
            vocab.addAll(tokens);
            totalTerms += tokens.size();
        }

        int N = docs.size();
        avgDocLength = (double) totalTerms / N;

        Map<String, Double> idf = new HashMap<>();
        for (String term : vocab) {
            int docFreq = df.getOrDefault(term, 1);
            idf.put(term, Math.log((N - docFreq + 0.5) / (docFreq + 0.5) + 1.0));
        }

        allTokens = new ArrayList<>(vocab);
        idfCache = idf;
        built = true;
        log.info("BM25Index 重建完成: {} 个文档, {} 个词条, avgDocLen={}", N, vocab.size(), String.format("%.1f", avgDocLength));
    }

    public List<Document> search(String query, int topK) {
        if (!built) {
            rebuild();
        }
        if (!built) {
            return List.of();
        }

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        List<Document> allDocs = documentStore.getAll();
        List<ScoredDoc> scored = new ArrayList<>();

        for (Document doc : allDocs) {
            String text = doc.getText();
            List<String> docTokens = tokenize(text);
            long docLen = docTokens.size();

            Map<String, Long> tf = docTokens.stream()
                    .collect(Collectors.groupingBy(t -> t, Collectors.counting()));

            double score = 0;
            for (String term : queryTokens) {
                long termFreq = tf.getOrDefault(term, 0L);
                double idf = idfCache.getOrDefault(term, 0.0);
                score += idf * (termFreq * (K1 + 1))
                        / (termFreq + K1 * (1 - B + B * docLen / avgDocLength));
            }
            scored.add(new ScoredDoc(doc, score));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        if (!scored.isEmpty()) {
            double topScore = scored.get(0).score;
            if (topScore > 0) {
                log.debug("BM25 top1={} score={}", truncate(scored.get(0).doc.getText(), 60),
                        String.format("%.4f", scored.get(0).score));
            }
        }

        return scored.stream()
                .filter(s -> s.score > 0)
                .limit(topK)
                .map(s -> {
                    s.doc.getMetadata().put("bm25_score", String.valueOf(s.score));
                    return s.doc;
                })
                .collect(Collectors.toList());
    }

    public boolean isBuilt() {
        return built;
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        String cleaned = text.toLowerCase().replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", " ");
        List<String> tokens = new ArrayList<>();
        for (String part : cleaned.split("\\s+")) {
            if (part.isBlank()) continue;
            for (int i = 0; i < part.length(); ) {
                int cp = part.codePointAt(i);
                if (cp >= 0x4E00 && cp <= 0x9FFF) {
                    tokens.add(new String(new int[]{cp}, 0, 1));
                    i += Character.charCount(cp);
                } else {
                    StringBuilder sb = new StringBuilder();
                    while (i < part.length()) {
                        cp = part.codePointAt(i);
                        if ((cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9')) {
                            sb.append((char) cp);
                            i += Character.charCount(cp);
                        } else break;
                    }
                    if (sb.length() >= 2) tokens.add(sb.toString());
                }
            }
        }
        return tokens;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private record ScoredDoc(Document doc, double score) {}
}
