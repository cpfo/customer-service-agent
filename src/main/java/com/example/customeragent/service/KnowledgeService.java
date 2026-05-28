package com.example.customeragent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class KnowledgeService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final String INIT_FLAG_KEY = "app:knowledge:initialized";
    private static final String FILES_SET_KEY = "app:knowledge:files";
    private static final String FILE_META_PREFIX = "app:knowledge:meta:";
    private static final String[] SUPPORTED_EXTENSIONS = {"*.txt", "*.pdf", "*.docx", "*.md", "*.html", "*.htm"};

    private final VectorStore vectorStore;
    private final StringRedisTemplate redisTemplate;
    private final DocumentStore documentStore;
    private final ObjectMapper objectMapper;

    public KnowledgeService(VectorStore vectorStore, StringRedisTemplate redisTemplate,
                            DocumentStore documentStore,
                            ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.redisTemplate = redisTemplate;
        this.documentStore = documentStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(INIT_FLAG_KEY))) {
            log.info("知识库已初始化，跳过加载");
            return;
        }
        loadFromClasspath();
    }

    public synchronized void loadFromClasspath() {
        try {
            List<Resource> resources = scanKnowledgeResources();
            if (resources.isEmpty()) {
                log.warn("未找到知识库文档 (classpath:knowledge/*.{txt,pdf,docx,md,html})");
                return;
            }

            List<Document> allChunks = new ArrayList<>();
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                log.info("加载知识库文档: {}", filename);
                allChunks.addAll(parseResource(resource, filename));
            }

            addToStores(allChunks);
            markInitialized();
            log.info("知识库加载完成，共 {} 个文档块 (来源: {} 个文件)，已标记初始化",
                    allChunks.size(), resources.size());
        } catch (Exception e) {
            log.error("加载知识库失败", e);
        }
    }

    public synchronized FileUploadResult addFile(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null || filename.isBlank()) {
            return new FileUploadResult(false, "文件名无效", null, 0);
        }

        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            List<Document> chunks = parseResource(resource, filename);

            for (Document chunk : chunks) {
                chunk.getMetadata().put("added_at", timestamp);
            }

            addToStores(chunks);
            trackFileInRedis(filename, chunks, timestamp);

            log.info("新增知识库文件: {} ({} 个块)", filename, chunks.size());
            return new FileUploadResult(true, "上传成功", filename, chunks.size());
        } catch (Exception e) {
            log.error("解析文件失败: {}", filename, e);
            return new FileUploadResult(false, "解析失败: " + e.getMessage(), filename, 0);
        }
    }

    public synchronized boolean deleteFile(String filename) {
        Set<String> chunkIds = getChunkIdsForFile(filename);
        if (!chunkIds.isEmpty()) {
            vectorStore.delete(new ArrayList<>(chunkIds));
            int removed = documentStore.removeByIds(chunkIds);
            log.info("从向量库删除 {} 个块: {}", removed, filename);
        }

        redisTemplate.opsForSet().remove(FILES_SET_KEY, filename);
        redisTemplate.delete(FILE_META_PREFIX + filename);

        log.info("已删除知识库文件: {}", filename);
        return true;
    }

    public synchronized void reloadAll() {
        unmarkInitialized();

        List<FileMeta> existingFiles = listFiles();
        for (FileMeta meta : existingFiles) {
            Set<String> ids = getChunkIdsForFile(meta.filename());
            if (!ids.isEmpty()) {
                vectorStore.delete(new ArrayList<>(ids));
            }
        }

        documentStore.clear();
        redisTemplate.delete(FILES_SET_KEY);
        Set<String> keys = redisTemplate.keys(FILE_META_PREFIX + "*");
        if (keys != null) {
            redisTemplate.delete(keys);
        }

        loadFromClasspath();
    }

    public List<FileMeta> listFiles() {
        Set<String> filenames = redisTemplate.opsForSet().members(FILES_SET_KEY);
        if (filenames == null || filenames.isEmpty()) {
            return List.of();
        }
        List<FileMeta> result = new ArrayList<>();
        for (String fn : filenames) {
            String json = redisTemplate.opsForValue().get(FILE_META_PREFIX + fn);
            if (json != null) {
                try {
                    result.add(objectMapper.readValue(json, FileMeta.class));
                } catch (Exception e) {
                    result.add(new FileMeta(fn, 0, "", ""));
                }
            }
        }
        result.sort(Comparator.comparing(FileMeta::addedAt).reversed());
        return result;
    }

    public String getFileContentPreview(String filename) {
        List<Document> docs = documentStore.findByFilename(filename);
        if (docs.isEmpty()) return "无内容";
        return docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n...\n"));
    }

    public boolean isInitialized() {
        return Boolean.TRUE.equals(redisTemplate.hasKey(INIT_FLAG_KEY));
    }

    private List<Document> parseResource(Resource resource, String filename) throws Exception {
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> documents = reader.get();
        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(documents);

        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            chunk.getMetadata().put("source", filename);
            chunk.getMetadata().put("chunk_index", String.valueOf(i));
            chunk.getMetadata().put("doc_id", filename + "#" + i);
        }
        return chunks;
    }

    private void addToStores(List<Document> chunks) {
        vectorStore.add(chunks);
        documentStore.addAll(chunks);
    }

    private void trackFileInRedis(String filename, List<Document> chunks, String timestamp) {
        List<String> chunkIds = chunks.stream()
                .map(doc -> doc.getMetadata().getOrDefault("doc_id", "").toString())
                .filter(id -> !id.isBlank())
                .collect(Collectors.toList());

        FileMeta meta = new FileMeta(filename, chunks.size(),
                chunks.get(0).getText().substring(0, Math.min(80, chunks.get(0).getText().length())),
                timestamp);

        try {
            redisTemplate.opsForSet().add(FILES_SET_KEY, filename);
            redisTemplate.opsForValue().set(FILE_META_PREFIX + filename, objectMapper.writeValueAsString(meta));
        } catch (Exception e) {
            log.warn("保存文件元数据失败: {}", filename, e);
        }
    }

    private Set<String> getChunkIdsForFile(String filename) {
        String json = redisTemplate.opsForValue().get(FILE_META_PREFIX + filename);
        if (json == null) return Set.of();
        try {
            FileMeta meta = objectMapper.readValue(json, FileMeta.class);
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < meta.chunkCount(); i++) {
                ids.add(meta.filename() + "#" + i);
            }
            return ids;
        } catch (Exception e) {
            return Set.of();
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

    private void markInitialized() {
        redisTemplate.opsForValue().set(INIT_FLAG_KEY, "true");
    }

    private void unmarkInitialized() {
        redisTemplate.delete(INIT_FLAG_KEY);
    }

    public record FileMeta(String filename, int chunkCount, String preview, String addedAt) {}

    public record FileUploadResult(boolean success, String message, String filename, int chunkCount) {}
}
