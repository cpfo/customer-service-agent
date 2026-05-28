package com.example.customeragent.controller;

import com.example.customeragent.service.ReRankerService;
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
import java.util.concurrent.ConcurrentHashMap;
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
    private final int retrieveTopK;
    private final Map<String, List<Message>> sessions = new ConcurrentHashMap<>();

    public ChatController(ChatClient chatClient,
                          VectorStore vectorStore,
                          ReRankerService reRankerService,
                          @Value("${app.reranker.retrieve-top-k:10}") int retrieveTopK) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.reRankerService = reRankerService;
        this.retrieveTopK = retrieveTopK;
    }

    @GetMapping
    public String chat(@RequestParam(defaultValue = "default") String sessionId,
                       @RequestParam String message) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(message).topK(retrieveTopK).build());
        List<Document> reranked = reRankerService.reRank(message, docs);

        String systemPrompt;
        if (!reranked.isEmpty()) {
            String context = reranked.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n---\n"));
            systemPrompt = SYSTEM_PROMPT_TEMPLATE.formatted(context);
        } else {
            systemPrompt = NO_CONTEXT_PROMPT;
        }

        List<Message> history = sessions.getOrDefault(sessionId, new ArrayList<>());
        List<Message> recentHistory = history.size() > 10
                ? history.subList(history.size() - 10, history.size())
                : history;

        log.info("[{}] 用户: {}, 历史消息数: {}", sessionId, message, recentHistory.size());

        String reply = chatClient.prompt()
                .system(systemPrompt)
                .messages(recentHistory)
                .user(message)
                .call()
                .content();

        history.add(new UserMessage(message));
        history.add(new AssistantMessage(reply));
        sessions.put(sessionId, history);

        return reply;
    }
}
