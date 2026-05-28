package com.example.customeragent.controller;

import com.example.customeragent.service.KnowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
@Tag(name = "知识库管理", description = "知识库文档的增删查改与重载操作，上传文档自动解析并写入向量库和 BM25 索引")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    @Operation(summary = "列出知识库文件", description = "返回所有已加载的知识库文档清单，包含文件名、块数、内容预览和添加时间。")
    public ResponseEntity<?> listFiles() {
        List<KnowledgeService.FileMeta> files = knowledgeService.listFiles();
        return ResponseEntity.ok(Map.of(
                "total", files.size(),
                "files", files,
                "initialized", knowledgeService.isInitialized()
        ));
    }

    @PostMapping
    @Operation(summary = "上传知识库文档", description = "上传文档文件（txt/pdf/docx/md/html/htm），自动解析为文本、分块后写入向量库和 BM25 索引，上限 10MB。")
    public ResponseEntity<?> uploadFile(
            @Parameter(description = "文档文件，支持 txt/pdf/docx/md/html/htm 格式", required = true)
            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "文件为空"));
        }
        String originalName = file.getOriginalFilename();
        log.info("上传知识库文件: {} ({} bytes)", originalName, file.getSize());

        try {
            KnowledgeService.FileUploadResult result = knowledgeService.addFile(
                    new InputStreamResource(file.getInputStream()) {
                        @Override
                        public String getFilename() {
                            return originalName;
                        }
                    });

            if (result.success()) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", result.message(),
                        "filename", result.filename(),
                        "chunkCount", result.chunkCount()
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", result.message()
                ));
            }
        } catch (Exception e) {
            log.error("上传文件失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "上传失败: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/{filename}")
    @Operation(summary = "查看文件详情", description = "查看指定知识库文件的元数据信息和完整文本内容预览。")
    public ResponseEntity<?> getFileDetail(
            @Parameter(description = "文件名（含扩展名）", example = "faq.txt")
            @PathVariable String filename) {
        String content = knowledgeService.getFileContentPreview(filename);
        List<KnowledgeService.FileMeta> all = knowledgeService.listFiles();
        KnowledgeService.FileMeta meta = all.stream()
                .filter(f -> f.filename().equals(filename))
                .findFirst().orElse(null);
        if (meta == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "meta", meta,
                "content", content
        ));
    }

    @DeleteMapping("/{filename}")
    @Operation(summary = "删除知识库文件", description = "从向量库和 DocumentStore 中删除指定文件的所有文档块。")
    public ResponseEntity<?> deleteFile(
            @Parameter(description = "文件名（含扩展名）", example = "faq.txt")
            @PathVariable String filename) {
        log.info("删除知识库文件: {}", filename);
        knowledgeService.deleteFile(filename);
        return ResponseEntity.ok(Map.of("success", true, "message", "已删除: " + filename));
    }

    @PostMapping("/reload")
    @Operation(summary = "重新加载知识库", description = "清空所有已加载的知识库内容，重新扫描 classpath:knowledge/ 下的所有文档文件并重建向量库和 BM25 索引。")
    public ResponseEntity<?> reloadAll() {
        log.info("重新加载知识库");
        knowledgeService.reloadAll();
        List<KnowledgeService.FileMeta> files = knowledgeService.listFiles();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "重新加载完成",
                "fileCount", files.size()
        ));
    }
}
