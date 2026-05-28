package com.example.customeragent.controller;

import com.example.customeragent.service.ReRankerService;
import com.example.customeragent.service.SessionService;
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

import java.util.*;
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
            不要编造信息。
            """;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ReRankerService reRankerService;
    private final SessionService sessionService;
    private final int retrieveTopK;

    public ChatController(ChatClient chatClient,
                          VectorStore vectorStore,
                          ReRankerService reRankerService,
                          SessionService sessionService,
                          @Value("${app.reranker.retrieve-top-k:10}") int retrieveTopK) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.reRankerService = reRankerService;
        this.sessionService = sessionService;
        this.retrieveTopK = retrieveTopK;
    }

    @GetMapping
    public String chat(@RequestParam(defaultValue = "default") String sessionId,
                       @RequestParam String message) {
        log.info("=== 请求开始 [{}] ===", sessionId);
        log.info("用户消息: {}", message);

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
            log.debug("知识内容:\n{}", context);
        } else {
            systemPrompt = NO_CONTEXT_PROMPT;
            log.info("未检索到相关知识，使用无上下文 system prompt");
        }

        List<Message> history = sessionService.getHistory(sessionId);
        log.info("历史消息数: {}", history.size());
        if (log.isDebugEnabled()) {
            for (int i = 0; i < history.size(); i++) {
                Message msg = history.get(i);
                log.debug("历史[{}] {}: {}", i,
                        msg.getMessageType(),
                        truncate(msg.getText(), 100));
            }
        }

        log.debug("system prompt 预览: {}", truncate(systemPrompt, 200));

        String reply = chatClient.prompt()
                .system(systemPrompt)
                .messages(history)
                .user(message)
                .call()
                .content();

        log.info("模型回复: {}", truncate(reply, 200));
        log.debug("模型回复(完整):\n{}", reply);

        sessionService.saveExchange(sessionId, new UserMessage(message), new AssistantMessage(reply));

        log.info("=== 请求结束 [{}] ===", sessionId);
        return reply;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...(共" + text.length() + "字符)";
    }
}
