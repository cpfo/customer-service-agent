# Phase 3 - 生产化增强：Redis 向量库 + 会话存储 + 多格式文档

## 目标
将向量库和会话历史从内存/文件迁移到 Redis Stack，实现持久化、多实例共享；扩展知识库文档格式支持，引入 Apache Tika 自动解析 PDF/DOCX/Markdown/HTML。

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

### 5. 基础设施

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

# 3. 测试
curl "http://localhost:8080/api/chat?sessionId=user1&message=发货政策是什么？"
curl "http://localhost:8080/api/chat?sessionId=user1&message=隐私政策呢？"   # 多轮
```

## 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `pom.xml` | 修改 | 添加 `spring-boot-starter-data-redis`、`spring-ai-redis-store`、`spring-ai-tika-document-reader` |
| `config/RAGConfig.java` | 重构 | `SimpleVectorStore` → `RedisVectorStore`，新增 `JedisPooled` Bean |
| `service/KnowledgeService.java` | 重构 | 移除文件持久化，改用 Redis 标记；`TextReader` → `TikaDocumentReader`，支持多格式扫描 |
| `service/SessionService.java` | 新增 | Redis 会话历史管理（StringRedisTemplate + JSON 序列化） |
| `controller/ChatController.java` | 重构 | `ConcurrentHashMap` → `SessionService` |
| `docker-compose.yml` | 新增 | Redis Stack Server 容器配置 |
| `knowledge/shipping-policy.md` | 新增 | Markdown 格式示例知识库 |
| `knowledge/privacy-policy.html` | 新增 | HTML 格式示例知识库 |

## 注意事项

- `RedisVectorStore` 在 `org.springframework.ai.vectorstore.redis` 包下，非 `org.springframework.ai.vectorstore`
- `spring-ai-redis-store` 使用 Jedis 连接 Redis，需与 Spring Data Redis（Lettuce）共存
- 首次运行需确保 Redis Stack 已启动且端口可访问
- Tika 解析 DOCX/PDF 时依赖较多，首次编译可能需要较长时间下载依赖
