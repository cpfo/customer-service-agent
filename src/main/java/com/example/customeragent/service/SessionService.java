package com.example.customeragent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final String PREFIX = "chat:session:";
    private static final int MAX_ROUNDS = 10;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public SessionService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public List<Message> getHistory(String sessionId) {
        String json = redisTemplate.opsForValue().get(PREFIX + sessionId);
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            List<MessageEntry> entries = objectMapper.readValue(json,
                    new TypeReference<List<MessageEntry>>() {});
            return entries.stream()
                    .map(MessageEntry::toMessage)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("反序列化会话历史失败，将重置会话: {}", sessionId, e);
            redisTemplate.delete(PREFIX + sessionId);
            return new ArrayList<>();
        }
    }

    public void saveExchange(String sessionId, Message userMsg, Message assistantMsg) {
        List<Message> history = getHistory(sessionId);
        history.add(userMsg);
        history.add(assistantMsg);

        int maxMessages = MAX_ROUNDS * 2;
        if (history.size() > maxMessages) {
            history = history.subList(history.size() - maxMessages, history.size());
        }

        List<MessageEntry> entries = history.stream()
                .map(MessageEntry::from)
                .collect(Collectors.toList());

        try {
            String json = objectMapper.writeValueAsString(entries);
            redisTemplate.opsForValue().set(PREFIX + sessionId, json);
            log.debug("会话 {} 历史已保存，当前 {} 条消息", sessionId, entries.size());
        } catch (Exception e) {
            log.error("保存会话历史失败: {}", sessionId, e);
        }
    }

    private record MessageEntry(String type, String content) {
        static MessageEntry from(Message msg) {
            if (msg instanceof UserMessage) {
                return new MessageEntry("user", msg.getText());
            }
            if (msg instanceof AssistantMessage) {
                return new MessageEntry("assistant", msg.getText());
            }
            return new MessageEntry("system", msg.getText());
        }

        Message toMessage() {
            return switch (type) {
                case "user" -> new UserMessage(content);
                case "assistant" -> new AssistantMessage(content);
                default -> new UserMessage(content);
            };
        }
    }
}
