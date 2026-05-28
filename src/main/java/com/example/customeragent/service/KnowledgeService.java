package com.example.customeragent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class KnowledgeService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final VectorStore vectorStore;

    public KnowledgeService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        loadKnowledgeBase();
    }

    public void loadKnowledgeBase() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:knowledge/*.txt");

            if (resources.length == 0) {
                log.warn("未找到知识库文档 (classpath:knowledge/*.txt)");
                return;
            }

            List<Document> allChunks = new ArrayList<>();
            for (Resource resource : resources) {
                log.info("加载知识库文档: {}", resource.getFilename());
                TextReader reader = new TextReader(resource);
                List<Document> documents = reader.get();
                TokenTextSplitter splitter = new TokenTextSplitter();
                List<Document> chunks = splitter.apply(documents);
                allChunks.addAll(chunks);
                log.info("文档 {} 分割为 {} 个块", resource.getFilename(), chunks.size());
            }

            vectorStore.add(allChunks);
            log.info("知识库加载完成，共 {} 个文档块", allChunks.size());
        } catch (Exception e) {
            log.error("加载知识库失败", e);
        }
    }
}
