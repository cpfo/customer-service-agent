package com.example.customeragent.controller;

import com.example.customeragent.service.CustomerTools;
import com.example.customeragent.service.ReRankerService;
import com.example.customeragent.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
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
    private final ObjectMapper objectMapper;
    private final int retrieveTopK;

    public ChatController(ChatClient chatClient,
                          VectorStore vectorStore,
                          ReRankerService reRankerService,
                          SessionService sessionService,
                          CustomerTools customerTools,
                          ObjectMapper objectMapper,
                          @Value("${app.reranker.retrieve-top-k:10}") int retrieveTopK) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.reRankerService = reRankerService;
        this.sessionService = sessionService;
        this.customerTools = customerTools;
        this.objectMapper = objectMapper;
        this.retrieveTopK = retrieveTopK;
    }

    @GetMapping
    public String chat(@RequestParam(defaultValue = "default") String sessionId,
                       @RequestParam String message) {
        log.info("=== 同步请求开始 [{}] ===", sessionId);
        log.info("用户消息: {}", message);

        RAGContext ctx = buildRAGContext(message, sessionId);

        String reply = chatClient.prompt()
                .system(ctx.systemPrompt)
                .messages(ctx.history)
                .user(message)
                .tools(customerTools)
                .call()
                .content();

        log.info("模型回复: {}", truncate(reply, 200));
        log.debug("完整回复:\n{}", reply);

        sessionService.saveExchange(sessionId, new UserMessage(message), new AssistantMessage(reply));
        log.info("=== 同步请求结束 [{}] ===", sessionId);
        return reply;
    }

    @GetMapping("/stream")
    public SseEmitter chatStream(@RequestParam(defaultValue = "default") String sessionId,
                                 @RequestParam String message) {
        log.info("=== SSE 请求开始 [{}] ===", sessionId);
        log.info("用户消息: {}", message);

        SseEmitter emitter = new SseEmitter(300_000L);

        try {
            RAGContext ctx = buildRAGContext(message, sessionId);
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
                                    log.warn("SSE 发送失败，客户端可能已断开: {}", sessionId, e);
                                    throw new RuntimeException(e);
                                }
                            },
                            error -> {
                                log.error("SSE 流异常 [{}]", sessionId, error);
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

                                sessionService.saveExchange(sessionId,
                                        new UserMessage(message), new AssistantMessage(reply));
                                log.info("=== SSE 请求结束 [{}] ===", sessionId);

                                try {
                                    String doneJson = objectMapper.writeValueAsString(
                                            Map.of("type", "done", "content", reply));
                                    emitter.send(SseEmitter.event().name("done").data(doneJson));
                                } catch (IOException e) {
                                    log.warn("SSE 发送完成事件失败", e);
                                }
                                emitter.complete();
                            });

            emitter.onCompletion(() -> log.debug("SSE 连接关闭 [{}]", sessionId));
            emitter.onTimeout(() -> log.warn("SSE 连接超时 [{}]", sessionId));

        } catch (Exception e) {
            log.error("SSE 请求处理失败 [{}]", sessionId, e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    private RAGContext buildRAGContext(String message, String sessionId) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(message).topK(retrieveTopK).build());
        log.info("向量库检索到 {} 条文档", docs.size());

        List<Document> reranked = reRankerService.reRank(message, docs);
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

        return new RAGContext(systemPrompt, history);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...(共" + text.length() + "字符)";
    }

    private record RAGContext(String systemPrompt, List<Message> history) {}
}
