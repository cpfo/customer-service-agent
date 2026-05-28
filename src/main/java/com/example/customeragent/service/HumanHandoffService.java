package com.example.customeragent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class HumanHandoffService {

    private static final Logger log = LoggerFactory.getLogger(HumanHandoffService.class);

    private static final List<String> HANDOFF_KEYWORDS = List.of(
            "转人工", "人工客服", "活人", "找人工", "转接人工",
            "human", "客服人员", "人工服务", "在线客服"
    );

    private final boolean enabled;
    private final String phone;
    private final ConcurrentHashMap<String, AtomicInteger> handoffCounts = new ConcurrentHashMap<>();

    public HumanHandoffService(@Value("${app.handoff.enabled:true}") boolean enabled,
                               @Value("${app.handoff.phone:400-888-8888}") String phone) {
        this.enabled = enabled;
        this.phone = phone;
    }

    public HandoffResult evaluate(String message, boolean hasKnowledge, List<String> recentFailures) {
        if (!enabled) {
            return new HandoffResult(false, null, null);
        }

        if (isExplicitHandoff(message)) {
            log.info("人工转接: 用户明确要求转人工, message=\"{}\"", truncate(message, 50));
            return createHandoff("用户明确要求转人工");
        }

        if (!hasKnowledge && !recentFailures.isEmpty()) {
            boolean repeatedFailure = recentFailures.size() >= 2;
            if (repeatedFailure) {
                log.info("人工转接: 连续 {} 次未找到相关知识", recentFailures.size());
                return createHandoff("连续" + recentFailures.size() + "次未命中知识库");
            }
        }

        return new HandoffResult(false, null, null);
    }

    public void recordHandoff(String sessionId) {
        handoffCounts.computeIfAbsent(sessionId, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public int getHandoffCount(String sessionId) {
        AtomicInteger count = handoffCounts.get(sessionId);
        return count == null ? 0 : count.get();
    }

    public String getFallbackMessage(String reason) {
        return "抱歉，我暂时无法回答您的问题。\n"
                + "您已转接至人工客服，请拨打 " + phone + "（工作日 9:00-18:00），"
                + "或稍后重试。";
    }

    private boolean isExplicitHandoff(String message) {
        String lower = message.toLowerCase();
        return HANDOFF_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private HandoffResult createHandoff(String reason) {
        String msg = getFallbackMessage(reason);
        return new HandoffResult(true, msg, new HandoffDetails(reason, phone));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    public record HandoffResult(boolean shouldHandoff, String fallbackMessage, HandoffDetails details) {}

    public record HandoffDetails(String reason, String phone) {}
}
