package com.example.customeragent.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Component
public class KnowledgeService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final SimpleVectorStore simpleVectorStore;
    private final String persistencePath;

    public KnowledgeService(SimpleVectorStore simpleVectorStore,
                            @Value("${app.vectorstore.persistence-path:./data/vector-store.json}")
                            String persistencePath) {
        this.simpleVectorStore = simpleVectorStore;
        this.persistencePath = persistencePath;
    }

    @Override
    public void run(String... args) {
        File file = new File(persistencePath);
        if (file.exists()) {
            loadPersistedStore(file);
        } else {
            loadFromKnowledgeFiles(file);
        }
    }

    private void loadPersistedStore(File file) {
        try {
            simpleVectorStore.load(file);
            log.info("从持久化文件加载向量库完成: {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.warn("加载持久化向量库失败，将重新从知识文件加载", e);
            loadFromKnowledgeFiles(file);
        }
    }

    private void loadFromKnowledgeFiles(File file) {
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

            simpleVectorStore.add(allChunks);
            log.info("知识库加载完成，共 {} 个文档块", allChunks.size());

            saveToFile(file);
        } catch (Exception e) {
            log.error("加载知识库失败", e);
        }
    }

    private void saveToFile(File file) throws IOException {
        Paths.get(file.getParent()).toFile().mkdirs();
        simpleVectorStore.save(file);
        log.info("向量库已持久化到: {}", file.getAbsolutePath());
    }

    @PreDestroy
    public void onShutdown() {
        try {
            File file = new File(persistencePath);
            file.getParentFile().mkdirs();
            simpleVectorStore.save(file);
            log.info("关闭前保存向量库到: {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.warn("保存向量库失败", e);
        }
    }
}
