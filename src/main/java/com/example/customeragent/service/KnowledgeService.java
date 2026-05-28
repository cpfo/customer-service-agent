package com.example.customeragent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class KnowledgeService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final String INIT_FLAG_KEY = "app:knowledge:initialized";
    private static final String[] SUPPORTED_EXTENSIONS = {"*.txt", "*.pdf", "*.docx", "*.md", "*.html", "*.htm"};

    private final VectorStore vectorStore;
    private final StringRedisTemplate redisTemplate;

    public KnowledgeService(VectorStore vectorStore, StringRedisTemplate redisTemplate) {
        this.vectorStore = vectorStore;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void run(String... args) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(INIT_FLAG_KEY))) {
            log.info("知识库已初始化，跳过加载");
            return;
        }
        loadKnowledge();
    }

    private void loadKnowledge() {
        try {
            List<Resource> resources = scanKnowledgeResources();

            if (resources.isEmpty()) {
                log.warn("未找到知识库文档 (classpath:knowledge/*.{txt,pdf,docx,md,html})");
                return;
            }

            List<Document> allChunks = new ArrayList<>();
            for (Resource resource : resources) {
                log.info("加载知识库文档: {}", resource.getFilename());
                TikaDocumentReader reader = new TikaDocumentReader(resource);
                List<Document> documents = reader.get();
                TokenTextSplitter splitter = new TokenTextSplitter();
                List<Document> chunks = splitter.apply(documents);
                allChunks.addAll(chunks);
                log.info("文档 {} 分割为 {} 个块", resource.getFilename(), chunks.size());
            }

            vectorStore.add(allChunks);
            redisTemplate.opsForValue().set(INIT_FLAG_KEY, "true");
            log.info("知识库加载完成，共 {} 个文档块 (来源: {} 个文件)，已标记初始化", allChunks.size(), resources.size());
        } catch (Exception e) {
            log.error("加载知识库失败", e);
        }
    }

    private List<Resource> scanKnowledgeResources() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> allResources = new ArrayList<>();
        for (String ext : SUPPORTED_EXTENSIONS) {
            Resource[] resources = resolver.getResources("classpath:knowledge/" + ext);
            allResources.addAll(Arrays.asList(resources));
        }
        return allResources;
    }
}
