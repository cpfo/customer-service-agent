# 智能客服 AI Agent

## 项目概述

基于 Spring AI Alibaba + DashScope（通义千问）的电商智能客服系统，通过 RAG（检索增强生成）技术构建知识问答能力。

| 组件 | 选型 |
|------|------|
| 框架 | Spring Boot 3.3 + Spring AI 1.1.2 |
| 大模型 | qwen-flash (DashScope) |
| 嵌入/重排序 | text-embedding-v3 / gte-rerank |
| 向量库 | Redis Stack (RediSearch) |
| 构建 | Maven Wrapper (Java 17) |

## 构建与运行

```bash
# 启动 Redis Stack
docker compose up -d

# 设置 API Key
export AI_DASHSCOPE_API_KEY=your-key

# 启动应用
./mvnw spring-boot:run

# 打包
./mvnw clean package -DskipTests
```

## 目录结构

```
src/main/java/com/example/customeragent/
├── config/          # Bean 声明 (RedisVectorStore, ChatClient, OpenAPI)
├── controller/      # HTTP 接口 (ChatController, KnowledgeController)
└── service/         # 核心业务逻辑
    ├── ChatCacheService      # 请求缓存
    ├── ContextCompressor     # 上下文压缩
    ├── CustomerTools         # Function Calling 工具
    ├── DashScopeReranker     # DashScope 重排序实现
    ├── DocumentStore         # 文档元数据存储
    ├── HumanHandoffService   # 人工转接检测
    ├── KnowledgeService      # 知识库加载/文件管理
    ├── MetricsService        # Micrometer 指标
    ├── QueryRewriter         # 查询改写
    ├── ReRankerService       # 重排序抽象
    └── SessionService        # 会话历史 (Redis)
```

## 架构约定

### RAG 流程
用户请求 → 查询改写 → 向量检索 → 重排序 → 构建 Prompt → LLM → 返回

### 编码规范
- **依赖注入**：使用构造器注入，避免 field injection
- **日志**：使用 SLF4J LoggerFactory，关键路径记录 info 日志
- **配置**：通过 `@Value` 从 `application.yml` 读取，提供默认值
- **API 文档**：使用 SpringDoc OpenAPI 3 注解（`@Tag`、`@Operation`、`@Parameter`）
- **错误处理**：Controller 层返回结构化 JSON（`Map.of("success", boolean, "message", String)`）
- **指标**：通过 `MetricsService` 上报 Micrometer 指标
- **缓存**：通过 `ChatCacheService` 缓存相同 query 的结果

### API 端点
| 端点 | 说明 |
|------|------|
| `GET /api/chat` | 同步文本对话 |
| `GET /api/chat/stream` | SSE 流式对话 |
| `POST /api/knowledge` | 上传知识文档 |
| `GET /api/knowledge` | 列出已上传文件 |
| `GET /api/knowledge/{filename}` | 查看文件详情 |
| `DELETE /api/knowledge/{filename}` | 删除文件 |
| `POST /api/knowledge/reload` | 热重载知识库 |

### Function Calling 工具
- 订单查询、物流跟踪、退货申请（通过 CustomerTools 注册）

## 环境变量

| 变量 | 说明 |
|------|------|
| `AI_DASHSCOPE_API_KEY` | DashScope API 密钥（必填） |
