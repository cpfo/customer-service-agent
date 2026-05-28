# Phase 5 - 运维与管理

## 目标

在 Phase 4 精度提升基础上，完善生产级运维能力：知识库管理 API、请求缓存、容错降级、人工转接、可观测性。

## 完成内容

### 1. 知识库管理 API — `KnowledgeController`

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/knowledge` | GET | 列出已加载知识库文档清单及元数据 |
| `/api/knowledge` | POST | 上传文档文件（multipart/form-data），解析后加入向量库和 BM25 索引 |
| `/api/knowledge/{filename}` | GET | 查看指定文件的内容预览 |
| `/api/knowledge/{filename}` | DELETE | 从向量库和 DocumentStore 删除指定文件 |
| `/api/knowledge/reload` | POST | 清空所有知识，重新扫描 classpath:knowledge/ |

- 上传文件自动分配 `{filename}#{index}` 格式的块 ID
- 块 ID 和元数据存储在 Redis（`app:knowledge:files` SET + `app:knowledge:meta:{filename}` Hash）
- 删除时先根据元数据查询块 ID → `VectorStore.delete()` → DocumentStore 移除 → BM25 重建
- 所有操作线程安全（synchronized）

### 2. 请求缓存 — `ChatCacheService`

| 改进 | 当前 | Phase 5 结果 |
|------|------|-------------|
| 重复请求 | 每次都调用 LLM | ✅ Redis 缓存命中直接返回 |
| 成本 | 每次请求消耗 API 额度 | ✅ 相同 (sessionId + message) 60分钟 TTL |

- 缓存 key：`chat:cache:{md5(sessionId + "|" + message)}`
- 存储：Redis String，TTL 可配置（默认 60 分钟）
- 命中时直接返回，跳过 RAG 检索和 LLM 调用
- 配置项：

```yaml
app:
  cache:
    enabled: true
    ttl-minutes: 60
```

### 3. 容错降级 — `ChatController.callLlmWithRetry()`

| 改进 | 当前 | Phase 5 结果 |
|------|------|-------------|
| 模型超时 | 直接返回错误 | ✅ 自动重试（最多1次）后降级 |
| 限频异常 | 服务不可用 | ✅ 重试间隔 1s×attempts 避让 |

- LLM 调用失败时自动重试（配置 `app.fallback.max-retries: 1`）
- 重试间隔递增（1s, 2s, ...）
- 超过重试次数后返回人工转接降级消息
- SSE 流异常也捕获并发送 error 事件 + completeWithError

```yaml
app:
  fallback:
    enabled: true
    max-retries: 1
```

### 4. 人工转接 — `HumanHandoffService`

| 触发条件 | 说明 |
|---------|------|
| 用户明确要求转人工 | 匹配关键词：转人工、人工客服、活人等（中英文 11 个） |
| 连续未命中知识库 | 2 次以上检索返回空文档 |

- 触发转接时返回标准化降级消息，引导用户拨打 400-888-8888
- 记录转接次数到 `MetricsService`（`app.chat.handoff.count`）
- 配置项：

```yaml
app:
  handoff:
    enabled: true
    phone: "400-888-8888"
```

### 5. 可观测性 — `MetricsService` + Micrometer + Prometheus

| 指标 | 类型 | 名称 |
|------|------|------|
| 请求总数 | Counter | `app.chat.request.total` |
| 成功请求数 | Counter | `app.chat.request.success` |
| 错误请求数 | Counter | `app.chat.request.error` |
| 缓存命中数 | Counter | `app.chat.cache.hit` |
| 缓存未命中数 | Counter | `app.chat.cache.miss` |
| 人工转接数 | Counter | `app.chat.handoff.count` |
| 重试次数 | Counter | `app.chat.retry.count` |
| LLM 调用耗时 | Timer | `app.chat.llm.duration` |
| 检索耗时 | Timer | `app.chat.retrieval.duration` |
| 知识库文档数 | Gauge | `app.knowledge.document.count` |
| 缓存大小 | Gauge | `app.cache.size` |

- 依赖：`micrometer-registry-prometheus` + `spring-boot-starter-actuator`
- 端点：`/actuator/prometheus` 暴露 Prometheus 格式指标
- 配置项：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  metrics:
    tags:
      application: customer-service-agent
```

## 完整请求流程 (Phase 5)

```
用户请求
  │
  ├── 1. Metrics: request.total++
  ├── 2. 缓存查询 (ChatCacheService)
  │      ├── 命中 → Metrics: cache.hit++ → 直接返回
  │      └── 未命中 → Metrics: cache.miss++
  │
  ├── 3. 查询改写 → 多路召回 → RRF 融合 → 重排序 (Phase 4)
  │      所有操作 Metrics: retrieval.duration 计时
  │
  ├── 4. 人工转接检测 (HumanHandoffService)
  │      ├── 转接 → Metrics: handoff.count++ → 返回降级消息
  │      └── 正常 → 继续
  │
  ├── 5. LLM 调用 (callLlmWithRetry)
  │      Metrics: llm.duration 计时
  │      ├── 成功 → Metrics: success++ → 写入缓存
  │      └── 失败 → 重试 ×N → Metrics: retry.count++
  │                    → 全部失败 → 降级消息
  │
  ├── 6. SessionService.saveExchange() → Redis
  └── 7. 返回响应
```

## 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `controller/KnowledgeController.java` | **新增** | 知识库管理 REST API（5 个端点） |
| `service/ChatCacheService.java` | **新增** | Redis 请求缓存（MD5 key + TTL） |
| `service/HumanHandoffService.java` | **新增** | 人工转接检测 + 降级消息 |
| `service/MetricsService.java` | **新增** | Micrometer 指标定义 + 计时器 |
| `controller/ChatController.java` | **重构** | 集成缓存/重试/降级/转接/监控 |
| `service/KnowledgeService.java` | **重构** | 增加 addFile/deleteFile/reload/listFiles 方法 + Redis 文件元数据跟踪 |
| `service/DocumentStore.java` | **重构** | 增加 removeByFilename / removeByIds / findByFilename 方法 |
| `pom.xml` | **修改** | 新增 actuator、micrometer-registry-prometheus、commons-codec |
| `resources/application.yml` | **修改** | 新增 cache/fallback/handoff/management 配置块 |
| `docs/项目架构与设计.md` | **修改** | 更新 Phase 5 路线图 + 依赖关系 |
| `docs/phase5-运维与管理.md` | **新增** | 本文件 |

## 注意事项

- 上传知识库文件通过 `POST /api/knowledge` 的 multipart 请求，文件大小限制 10MB
- 缓存使用 MD5 对 `sessionId|message` 取摘要作为 key，不包含检索到的知识，因此仅适用于完全相同的问题
- 人工转接检测仅包含简单关键词匹配 + 连续无知识检测，实际生产中可引入 LLM 意图识别
- Prometheus 指标需配合 Prometheus Server 拉取 `/actuator/prometheus`，Grafana 展示面板
- 文件元数据存储在 Redis，重启不丢失；但 DocumentStore 在重启后需重新加载（reload API 或重启重建）
