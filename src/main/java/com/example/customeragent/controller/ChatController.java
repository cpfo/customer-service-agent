package com.example.customeragent.controller;

import com.example.customeragent.config.RAGConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是一位专业的电商客服助手，名叫"小智"。

            核心规则：
            1. 必须基于提供的知识库内容回答，不要编造信息
            2. 如果知识库中没有相关信息，礼貌告知用户无法回答，并建议联系人工客服
            3. 回答要简洁、友好、专业
            4. 涉及退换货、退款时，清晰说明条件和流程
            5. 不要暴露内部系统细节

            以下是知识库中与用户问题相关的内容：
            ---------------------
            %s
            ---------------------
            """;

    private static final String NO_CONTEXT_PROMPT = """
            你是一位专业的电商客服助手，名叫"小智"。

            知识库中暂未找到与用户问题直接相关的信息，请礼貌告知用户，
            并建议联系人工客服（热线 400-888-8888）获取更准确的帮助。
            不要编造信息。
            """;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final RAGConfig ragConfig;

    public ChatController(ChatClient chatClient, VectorStore vectorStore, RAGConfig ragConfig) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.ragConfig = ragConfig;
    }

    @GetMapping
    public String chat(@RequestParam String message) {
        String context = ragConfig.buildContext(vectorStore, message);
        String systemPrompt;

        if (context != null) {
            systemPrompt = SYSTEM_PROMPT_TEMPLATE.formatted(context);
        } else {
            systemPrompt = NO_CONTEXT_PROMPT;
        }

        return chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .call()
                .content();
    }
}
