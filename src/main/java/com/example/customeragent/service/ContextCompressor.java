package com.example.customeragent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ContextCompressor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressor.class);

    private static final String COMPRESS_PROMPT = """
            你是智能客服"小智"的对话摘要助手。
            请将以下对话历史压缩为一段简洁的摘要，保留关键信息（用户意图、已解决的问题、待办事项等）。
            如果对话较短或内容简单，直接输出"无重要历史"。

            对话历史：
            %s

            摘要：
            """;

    private final ChatClient chatClient;
    private final boolean enabled;
    private static final int COMPRESS_THRESHOLD = 6;

    public ContextCompressor(ChatClient chatClient,
                             @Value("${app.context-compression.enabled:true}") boolean enabled) {
        this.chatClient = chatClient;
        this.enabled = enabled;
    }

    public String compress(List<Message> history) {
        if (!enabled) {
            return formatRaw(history);
        }
        if (history == null || history.isEmpty()) {
            return "";
        }
        if (history.size() <= 4) {
            log.debug("历史消息较少({}条)，跳过压缩", history.size());
            return formatRaw(history);
        }

        String historyText = history.stream()
                .map(m -> {
                    String role = (m instanceof UserMessage) ? "用户"
                            : (m instanceof AssistantMessage) ? "小智"
                            : "系统";
                    return role + ": " + m.getText();
                })
                .collect(Collectors.joining("\n"));

        try {
            String summary = chatClient.prompt()
                    .system(COMPRESS_PROMPT.formatted(historyText))
                    .user("请压缩以上对话历史")
                    .call()
                    .content();

            if (summary == null || summary.isBlank() || summary.contains("无重要历史")) {
                log.info("上下文压缩: 无重要历史，保留原始消息({}条)", history.size());
                return formatRaw(history);
            }

            log.info("上下文压缩完成: {} 条消息 → {} 字符摘要", history.size(), summary.length());
            log.debug("压缩摘要: {}", summary);
            return "【对话历史摘要】\n" + summary;

        } catch (Exception e) {
            log.warn("上下文压缩失败，使用原始历史: {}", e.getMessage());
            return formatRaw(history);
        }
    }

    private String formatRaw(List<Message> history) {
        if (history == null || history.isEmpty()) return "";
        return history.stream()
                .map(m -> {
                    String role = (m instanceof UserMessage) ? "用户"
                            : (m instanceof AssistantMessage) ? "客服"
                            : "系统";
                    return role + ": " + m.getText();
                })
                .collect(Collectors.joining("\n"));
    }
}
