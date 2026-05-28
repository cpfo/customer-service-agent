# Phase 3 - 生产化增强：Redis + 多格式文档 + SSE + Function Calling

## 目标
将向量库和会话历史从内存/文件迁移到 Redis Stack，实现持久化、多实例共享；扩展知识库文档格式支持，引入 Apache Tika 自动解析 PDF/DOCX/Markdown/HTML；将同步 HTTP 改为 SSE 流式响应，降低首字延迟；引入 Function Calling 让 Agent 能调用业务工具。

## 完成内容

### 1. 向量库迁移至 Redis — `RAGConfig` / `KnowledgeService`

- `SimpleVectorStore`（内存 + 文件持久化）→ `RedisVectorStore`（Redis Stack RediSearch 模块）
- 新增 `JedisPooled` Bean 连接 Redis
- `RedisVectorStore` 索引名 `customer-service-vector-index`，启动时自动创建 schema
- `KnowledgeService` 不再需要 save/load 文件和 `@PreDestroy`，Redis 自动持久化

### 2. 知识库去重加载

- 新增 Redis 标记键 `app:knowledge:initialized`
- 首次启动时扫描知识文件 → 分块 → 写入向量库 → 设置标记
- 后续启动直接跳过，避免重复索引
- 如需重新索引：`redis-cli DEL app:knowledge:initialized` 或清空 Redis

### 3. 会话历史迁移至 Redis — 新增 `SessionService`

- `ConcurrentHashMap<String, List<Message>>`（内存）→ `StringRedisTemplate` + Jackson 序列化
- 存储结构：Redis key `chat:session:{sessionId}` → JSON 数组
  ```json
  [{"type":"user","content":"你好"}, {"type":"assistant","content":"您好！请问有什么可以帮助您的？"}]
  ```
- 保留最近 10 轮对话（20 条消息），自动截断
- 反序列化失败时自动重置会话，不阻塞请求
- 应用重启后会话不丢失

### 4. 多格式文档支持 — `KnowledgeService`

| 格式 | 扩展名 | 解析引擎 |
|------|--------|---------|
| 纯文本 | `.txt` | Apache Tika |
| PDF | `.pdf` | Apache PDFBox (Tika 调用) |
| Word | `.docx` | Apache POI (Tika 调用) |
| Markdown | `.md` | Apache Tika MarkdownParser |
| HTML | `.html` / `.htm` | Apache Tika HTMLParser |

- `TextReader` → `TikaDocumentReader`，Apache Tika 自动检测 MIME 类型并选择正确解析器
- 扫描轮询 6 种扩展名：`*.txt`、`*.pdf`、`*.docx`、`*.md`、`*.html`、`*.htm`
- 新增示例文档：`shipping-policy.md`（发货政策）、`privacy-policy.html`（隐私政策）

### 5. SSE 流式响应 — `ChatController`

- `@GetMapping(produces = text/event-stream)` 返回 `SseEmitter`
- `chatClient.prompt().stream().content()` 返回 `Flux<String>`，每收到一个 token 立即发送 SSE event
- SSE 事件格式：
  - 普通 token：`data: {"content":"token文本"}\n\n`
  - 完成事件：`event: done\ndata: {"type":"done","content":"完整回复"}\n\n`
  - 错误事件：`event: error\ndata: {"type":"error","message":"..."}\n\n`
- `SseEmitter` 超时 5 分钟，客户端断开自动取消订阅
- 流结束后自动保存会话历史（`SessionService.saveExchange()`）

### 6. Function Calling — 新增 `CustomerTools`

三个 `@Tool` 方法，模型根据用户意图自动选择调用：

| 工具 | 方法 | 参数 | 描述 |
|------|------|------|------|
| 查询订单状态 | `getOrderStatus` | `orderId` | 返回当前状态、预计送达 |
| 物流跟踪 | `trackLogistics` | `orderId` | 返回完整流转节点 |
| 退货申请 | `submitRefundRequest` | `orderId`, `reason` | 生成退货运单号、退货运费说明 |

- 注解驱动：`@Tool(description = "...")` 和 `@ToolParam(description = "...")`，模型自动理解参数含义
- `ChatController` 中通过 `.tools(customerTools)` 注入，无需修改 RAGConfig
- 当前为 Mock 数据，后续可对接真实 API

### 7. System Prompt 更新

有/无上下文两个 prompt 模板均新增第 7 条规则：
> 你可以使用提供的工具查询订单状态、物流信息、处理退货申请

### 8. 基础设施

- 新增 `docker-compose.yml`，一键启动 Redis Stack Server：
  ```yaml
  services:
    redis-stack:
      image: redis/redis-stack-server:latest
      ports:
        - "6379:6379"
  ```

## 配置文件变更

```yaml
# 新增
spring:
  redis:
    host: localhost
    port: 6379

# 已移除
# app.vectorstore.persistence-path: ./data/vector-store.json
```

## 启动方式

```bash
# 1. 启动 Redis
docker compose up -d

# 2. 启动应用（首次自动加载知识库 + 标记初始化）
set AI_DASHSCOPE_API_KEY=YourKey
.\mvnw.cmd spring-boot:run

# 3. 测试 SSE 流式响应
curl -N "http://localhost:8080/api/chat?sessionId=user1&message=发货政策是什么？"

# 4. 测试函数调用
curl -N "http://localhost:8080/api/chat?sessionId=user1&message=查一下我的订单ORD20240501001的物流"
curl -N "http://localhost:8080/api/chat?sessionId=user1&message=我要退货，订单号ORD20240501001"
```

## 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `pom.xml` | 修改 | 添加 `spring-boot-starter-data-redis`、`spring-ai-redis-store`、`spring-ai-tika-document-reader` |
| `config/RAGConfig.java` | 重构 | `SimpleVectorStore` → `RedisVectorStore`，新增 `JedisPooled` Bean |
| `service/KnowledgeService.java` | 重构 | 移除文件持久化，改用 Redis 标记；`TextReader` → `TikaDocumentReader`，支持多格式扫描 |
| `service/SessionService.java` | 新增 | Redis 会话历史管理（StringRedisTemplate + JSON 序列化） |
| `controller/ChatController.java` | 重构 | SSE (`SseEmitter` + `Flux`) + Function Calling (`.tools(customerTools)`) + `ConcurrentHashMap` → `SessionService` |
| `service/CustomerTools.java` | 新增 | 三个 `@Tool` 方法：订单查询、物流跟踪、退货申请 |
| `docker-compose.yml` | 新增 | Redis Stack Server 容器配置 |
| `knowledge/shipping-policy.md` | 新增 | Markdown 格式示例知识库 |
| `knowledge/privacy-policy.html` | 新增 | HTML 格式示例知识库 |

## 注意事项

- `RedisVectorStore` 在 `org.springframework.ai.vectorstore.redis` 包下，非 `org.springframework.ai.vectorstore`
- `spring-ai-redis-store` 使用 Jedis 连接 Redis，需与 Spring Data Redis（Lettuce）共存
- 首次运行需确保 Redis Stack 已启动且端口可访问
- Tika 解析 DOCX/PDF 时依赖较多，首次编译可能需要较长时间下载依赖
- SSE 端点返回 `Content-Type: text/event-stream`，客户端需支持 SSE 协议
  - curl 测试需加 `-N`（no-buffer）参数：`curl -N "http://localhost:8080/api/chat?..."`
- Function Calling 为 Mock 数据实现，`getOrderStatus` / `trackLogistics` / `submitRefundRequest` 返回预设示例数据
  - 对接真实 API 只需修改 `CustomerTools` 中的方法实现
