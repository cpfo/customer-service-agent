package com.example.customeragent.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class MetricsService {

    private final MeterRegistry meterRegistry;

    private final Counter requestTotal;
    private final Counter requestSuccess;
    private final Counter requestError;
    private final Counter cacheHit;
    private final Counter cacheMiss;
    private final Counter handoffCount;
    private final Counter retryCount;
    private final Timer llmTimer;
    private final Timer retrievalTimer;

    public MetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.requestTotal = Counter.builder("app.chat.request.total")
                .description("Total chat requests").register(meterRegistry);
        this.requestSuccess = Counter.builder("app.chat.request.success")
                .description("Successful chat requests").register(meterRegistry);
        this.requestError = Counter.builder("app.chat.request.error")
                .description("Failed chat requests").register(meterRegistry);
        this.cacheHit = Counter.builder("app.chat.cache.hit")
                .description("Cache hit count").register(meterRegistry);
        this.cacheMiss = Counter.builder("app.chat.cache.miss")
                .description("Cache miss count").register(meterRegistry);
        this.handoffCount = Counter.builder("app.chat.handoff.count")
                .description("Human handoff count").register(meterRegistry);
        this.retryCount = Counter.builder("app.chat.retry.count")
                .description("LLM retry count").register(meterRegistry);
        this.llmTimer = Timer.builder("app.chat.llm.duration")
                .description("LLM call duration").register(meterRegistry);
        this.retrievalTimer = Timer.builder("app.chat.retrieval.duration")
                .description("Retrieval duration").register(meterRegistry);
    }

    public void incrementRequestTotal() { requestTotal.increment(); }
    public void incrementRequestSuccess() { requestSuccess.increment(); }
    public void incrementRequestError() { requestError.increment(); }
    public void incrementCacheHit() { cacheHit.increment(); }
    public void incrementCacheMiss() { cacheMiss.increment(); }
    public void incrementHandoff() { handoffCount.increment(); }
    public void incrementRetry() { retryCount.increment(); }

    public <T> T timeLlm(Supplier<T> supplier) {
        return llmTimer.record(supplier);
    }

    public <T> T timeRetrieval(Supplier<T> supplier) {
        return retrievalTimer.record(supplier);
    }

    public void gaugeKnowledgeDocs(int count) {
        meterRegistry.gauge("app.knowledge.document.count", count);
    }

    public void gaugeCacheSize(long size) {
        meterRegistry.gauge("app.cache.size", size);
    }
}
