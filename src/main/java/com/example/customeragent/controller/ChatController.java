package com.example.customeragent.controller;

import com.example.customeragent.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
@Tag(name = "聊天接口", description = "智能客服对话，支持同步文本和 SSE 流式两种返回方式，集成多轮会话、RAG 检索、Function Calling")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是一位专业的电商客服助手，名叫"小智"。

            核心规则：
            1. 必须基于提供的知识库内容回答，不要编造信息
            2. 如果知识库中没有相关信息，礼貌告知用户无法回答，并建议联系人工客服
            3. 回答要简洁、友好、专业
            4. 涉及退换货、退款时，清晰说明条件和流程
            5. 结合对话历史理解用户问题的上下文
            6. 不要暴露内部系统细节
            7. 你可以使用提供的工具查询订单状态、物流信息、处理退货申请

            以下是知识库中与用户问题相关的内容：
            ---------------------
            %s
            ---------------------
            """;

    private static final String NO_CONTEXT_PROMPT = """
            你是一位专业的电商客服助手，名叫"小智"。

            如果用户的问题能通过已有对话历史回答，请正常回答。
            否则，知识库中暂未找到与用户问题直接相关的信息，请礼貌告知用户，
            并建议联系人工客服（热线 400-888-8888）获取更准确的帮助。
            不要编造信息。你可以使用提供的工具查询订单状态、物流信息、处理退货申请。
            """;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ReRankerService reRankerService;
    private final SessionService sessionService;
    private final CustomerTools customerTools;
    private final QueryRewriter queryRewriter;
    private final ContextCompressor contextCompressor;
    private final ChatCacheService chatCacheService;
    private final HumanHandoffService humanHandoffService;
    private final MetricsService metricsService;
    private final ObjectMapper objectMapper;
    private final int vectorTopK;
    private final int fusionTopK;
    private final boolean fallbackEnabled;
    private final int maxRetries;

    public ChatController(ChatClient chatClient,
                          VectorStore vectorStore,
                          ReRankerService reRankerService,
                          SessionService sessionService,
                          CustomerTools customerTools,
                          QueryRewriter queryRewriter,
                          ContextCompressor contextCompressor,
                          ChatCacheService chatCacheService,
                          HumanHandoffService humanHandoffService,
                          MetricsService metricsService,
                          ObjectMapper objectMapper,
                          @Value("${app.retrieval.vector-top-k:10}") int vectorTopK,
                          @Value("${app.retrieval.fusion-top-k:10}") int fusionTopK,
                          @Value("${app.fallback.enabled:true}") boolean fallbackEnabled,
                          @Value("${app.fallback.max-retries:1}") int maxRetries) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.reRankerService = reRankerService;
        this.sessionService = sessionService;
        this.customerTools = customerTools;
        this.queryRewriter = queryRewriter;
        this.contextCompressor = contextCompressor;
        this.chatCacheService = chatCacheService;
        this.humanHandoffService = humanHandoffService;
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
        this.vectorTopK = vectorTopK;
        this.fusionTopK = fusionTopK;
        this.fallbackEnabled = fallbackEnabled;
        this.maxRetries = maxRetries;
    }

    @GetMapping
    @Operation(summary = "同步聊天", description = "发送消息给智能客服，返回纯文本回复。支持多轮对话（sessionId）、RAG 知识库检索、Function Calling。")
    @ApiResponse(responseCode = "200", description = "成功返回文本回复",
            content = @Content(mediaType = "text/plain",
                    examples = @ExampleObject("您好！请问有什么可以帮助您的？")))
    public String chat(
            @Parameter(description = "会话 ID，用于维持多轮对话上下文", example = "user123")
            @RequestParam(defaultValue = "default") String sessionId,
            @Parameter(description = "用户发送的消息", example = "你们的退货政策是什么？", required = true)
            @RequestParam String message) {
        metricsService.incrementRequestTotal();
        log.info("=== 同步请求开始 [{}] ===", sessionId);
        log.info("用户消息: {}", message);

        String cached = chatCacheService.get(sessionId, message);
        if (cached != null) {
            metricsService.incrementCacheHit();
            log.info("返回缓存结果，长度: {} 字符", cached.length());
            return cached;
        }
        metricsService.incrementCacheMiss();

        RAGContext ctx = buildRAGContext(message, sessionId);

        HandoffResult hr = evaluateHandoff(message, ctx);
        if (hr.shouldFallback()) {
            metricsService.incrementHandoff();
            String reply = hr.fallbackMessage();
            sessionService.saveExchange(sessionId, new UserMessage(message), new AssistantMessage(reply));
            log.info("=== 同步请求结束(人工转接) [{}] ===", sessionId);
            return reply;
        }

        String reply = callLlmWithRetry(ctx, message);
        if (reply == null) {
            reply = humanHandoffService.getFallbackMessage("LLM 调用失败");
            metricsService.incrementHandoff();
        } else {
            chatCacheService.put(sessionId, message, reply);
            metricsService.incrementRequestSuccess();
        }

        log.info("模型回复: {}", truncate(reply, 200));
        log.debug("完整回复:\n{}", reply);

        sessionService.saveExchange(sessionId, new UserMessage(message), new AssistantMessage(reply));
        log.info("=== 同步请求结束 [{}] ===", sessionId);
        return reply;
    }

    @GetMapping("/stream")
    @Operation(summary = "SSE 流式聊天", description = "以 Server-Sent Events 方式逐 token 推送回复，首字延迟 ~200ms。" +
            "事件格式：data {\"content\":\"...\"} 为普通 token，event:done 为结束，event:error 为异常。")
    @ApiResponse(responseCode = "200", description = "返回 text/event-stream 流",
            content = @Content(mediaType = "text/event-stream"))
    public SseEmitter chatStream(
            @Parameter(description = "会话 ID，用于维持多轮对话上下文", example = "user123")
            @RequestParam(defaultValue = "default") String sessionId,
            @Parameter(description = "用户发送的消息", example = "查一下我的订单ORD20240501001", required = true)
            @RequestParam String message) {
        metricsService.incrementRequestTotal();
        log.info("=== SSE 请求开始 [{}] ===", sessionId);
        log.info("用户消息: {}", message);

        SseEmitter emitter = new SseEmitter(300_000L);

        try {
            String cached = chatCacheService.get(sessionId, message);
            if (cached != null) {
                metricsService.incrementCacheHit();
                sendSseToken(emitter, cached);
                sendSseDone(emitter, cached);
                return emitter;
            }
            metricsService.incrementCacheMiss();

            RAGContext ctx = buildRAGContext(message, sessionId);

            HandoffResult hr = evaluateHandoff(message, ctx);
            if (hr.shouldFallback()) {
                metricsService.incrementHandoff();
                sendSseToken(emitter, hr.fallbackMessage());
                sendSseDone(emitter, hr.fallbackMessage());
                sessionService.saveExchange(sessionId,
                        new UserMessage(message), new AssistantMessage(hr.fallbackMessage()));
                return emitter;
            }

            StringBuilder fullReply = new StringBuilder();
            chatClient.prompt()
                    .system(ctx.systemPrompt)
                    .messages(ctx.history)
                    .user(message)
                    .tools(customerTools)
                    .stream()
                    .content()
                    .subscribe(
                            chunk -> {
                                fullReply.append(chunk);
                                try {
                                    String json = objectMapper.writeValueAsString(
                                            Collections.singletonMap("content", chunk));
                                    emitter.send(SseEmitter.event().data(json));
                                } catch (IOException e) {
                                    log.warn("SSE 发送失败: {}", sessionId, e);
                                    throw new RuntimeException(e);
                                }
                            },
                            error -> {
                                log.error("SSE 流异常 [{}]", sessionId, error);
                                String fallback = humanHandoffService.getFallbackMessage("SSE 异常");
                                try {
                                    String errorJson = objectMapper.writeValueAsString(
                                            Map.of("type", "error", "message", error.getMessage()));
                                    emitter.send(SseEmitter.event().name("error").data(errorJson));
                                } catch (IOException e1) {
                                    log.warn("SSE 发送错误事件失败", e1);
                                }
                                emitter.completeWithError(error);
                            },
                            () -> {
                                String reply = fullReply.toString();
                                log.info("流式响应结束 [{}]，共 {} 字符", sessionId, reply.length());
                                log.debug("完整回复:\n{}", reply);

                                chatCacheService.put(sessionId, message, reply);
                                metricsService.incrementRequestSuccess();
                                sessionService.saveExchange(sessionId,
                                        new UserMessage(message), new AssistantMessage(reply));
                                log.info("=== SSE 请求结束 [{}] ===", sessionId);
                                sendSseDone(emitter, reply);
                            });

            emitter.onCompletion(() -> log.debug("SSE 连接关闭 [{}]", sessionId));
            emitter.onTimeout(() -> log.warn("SSE 连接超时 [{}]", sessionId));

        } catch (Exception e) {
            log.error("SSE 请求处理失败 [{}]", sessionId, e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    private String callLlmWithRetry(RAGContext ctx, String message) {
        int attempts = 0;
        while (attempts <= maxRetries) {
            try {
                return metricsService.timeLlm(() ->
                        chatClient.prompt()
                                .system(ctx.systemPrompt)
                                .messages(ctx.history)
                                .user(message)
                                .tools(customerTools)
                                .call()
                                .content());
            } catch (Exception e) {
                attempts++;
                metricsService.incrementRetry();
                log.warn("LLM 调用失败 (第{}次): {}", attempts, e.getMessage());
                if (attempts > maxRetries) {
                    log.error("LLM 调用已达最大重试次数({})", maxRetries);
                    return null;
                }
                log.info("LLM 重试中 ({}/{})...", attempts, maxRetries);
                try { Thread.sleep(1000L * attempts); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    private HandoffResult evaluateHandoff(String message, RAGContext ctx) {
        boolean hasKnowledge = ctx.systemPrompt.contains("以下是知识库中");
        List<String> failures = new ArrayList<>();
        if (!hasKnowledge) failures.add("no_knowledge");

        HumanHandoffService.HandoffResult result = humanHandoffService.evaluate(
                message, hasKnowledge, failures);
        if (result.shouldHandoff()) {
            return new HandoffResult(true, result.fallbackMessage());
        }
        return new HandoffResult(false, null);
    }

    private RAGContext buildRAGContext(String message, String sessionId) {
        List<Document> fused = metricsService.timeRetrieval(() -> {
            List<String> queries = queryRewriter.rewrite(message);
            log.info("查询改写: {} 条查询", queries.size());

            List<Document> vectorDocs = retrieveVector(queries.get(0));
            log.info("向量检索到 {} 条文档", vectorDocs.size());

            return vectorDocs.size() > fusionTopK
                    ? vectorDocs.subList(0, fusionTopK)
                    : vectorDocs;
        });

        List<Document> reranked = reRankerService.reRank(message, fused);
        log.info("重排序后保留 {} 条文档", reranked.size());

        String systemPrompt;
        if (!reranked.isEmpty()) {
            String context = reranked.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n---\n"));
            systemPrompt = SYSTEM_PROMPT_TEMPLATE.formatted(context);
            log.info("使用有上下文的 system prompt，知识长度: {} 字符", context.length());
        } else {
            systemPrompt = NO_CONTEXT_PROMPT;
            log.info("未检索到相关知识，使用无上下文 system prompt");
        }

        List<Message> history = sessionService.getHistory(sessionId);
        log.info("历史消息数: {}", history.size());

        String compressedHistory = contextCompressor.compress(history);
        log.info("上下文压缩完成，长度: {} 字符", compressedHistory.length());

        return new RAGContext(systemPrompt, history);
    }

    private List<Document> retrieveVector(String query) {
        return vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(vectorTopK).build());
    }

    private void sendSseToken(SseEmitter emitter, String content) {
        try {
            String json = objectMapper.writeValueAsString(Collections.singletonMap("content", content));
            emitter.send(SseEmitter.event().data(json));
        } catch (IOException e) {
            log.warn("SSE 发送失败", e);
        }
    }

    private void sendSseDone(SseEmitter emitter, String fullReply) {
        try {
            String doneJson = objectMapper.writeValueAsString(
                    Map.of("type", "done", "content", fullReply));
            emitter.send(SseEmitter.event().name("done").data(doneJson));
        } catch (IOException e) {
            log.warn("SSE 发送完成事件失败", e);
        }
        emitter.complete();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...(共" + text.length() + "字符)";
    }

    private record RAGContext(String systemPrompt, List<Message> history) {}

    private record HandoffResult(boolean shouldFallback, String fallbackMessage) {}
}
