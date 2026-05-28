[toc]

# 智能客服 AI Agent

基于 Spring AI Alibaba + DashScope (通义千问) 的智能客服系统，通过 RAG（检索增强生成）技术构建电商客服场景的知识问答能力。

## 快速开始

### 前置条件

- JDK 17+
- Docker（运行 Redis Stack）

### 启动

```bash
# 1. 启动 Redis Stack（向量库 + 会话存储 + 缓存）
docker compose up -d

# 2. 配置环境变量
set AI_DASHSCOPE_API_KEY=your-api-key

# 3. 启动应用
./mvnw spring-boot:run
```

首次启动时自动加载 `src/main/resources/knowledge/` 下的文档到 Redis 向量库。

### 验证

```bash
# 流式对话
curl -N "http://localhost:8080/api/chat/stream?message=退换货政策是什么&sessionId=test001"
```

## 项目说明

### 快速概览

| 组件 | 选型 |
|------|------|
| 框架 | Spring Boot 3.3 + Spring AI 1.1.2 |
| 大模型 | qwen-flash (DashScope) |
| 嵌入/重排序 | text-embedding-v3 / gte-rerank |
| 向量库 | Redis Stack (RediSearch) |
| 构建 | Maven Wrapper (Java 17) |

### 流程示意

```
用户请求 → 查询改写 → 向量检索 → 重排序 → 构建 Prompt → LLM → 返回
```

具体包括：
- **查询改写**：LLM 将口语问题改写为多条精准查询
- **向量检索**：Redis 向量库语义匹配
- **重排序**：交叉编码器 (gte-rerank) 逐对评分，取 top-3
- **上下文压缩**：长历史自动摘要
- **Function Calling**：模型可调用订单查询/物流跟踪/退货申请等工具

### API 端点

| 端点 | 说明 |
|------|------|
| `GET /api/chat` | 同步文本对话 |
| `GET /api/chat/stream` | SSE 流式对话 |
| `POST /api/knowledge` | 上传知识文档 |
| `GET /api/knowledge` | 列出已上传文件 |
| `DELETE /api/knowledge` | 删除文件 |
| `POST /api/knowledge/reload` | 热重载知识库 |
| `GET /actuator/prometheus` | 监控指标 |

启动后访问 `http://localhost:8080/swagger-ui.html` 查看完整 API 文档。

### 项目结构

```
src/main/java/com/example/customeragent/
├── config/          # Bean声明 (RedisVectorStore, ChatClient)
├── controller/      # HTTP 接口 + RAG 编排
└── service/         # 核心业务逻辑
    ├── KnowledgeService   # 知识库加载/文件管理
    ├── SessionService     # 会话历史 (Redis)
    ├── ReRankerService    # 重排序 (交叉编码器/Hybrid)
    ├── QueryRewriter      # 查询改写
    ├── ContextCompressor  # 上下文压缩
    ├── CustomerTools      # Function Calling 工具
    ├── ChatCacheService   # 请求缓存
    ├── HumanHandoffService# 人工转接检测
    └── MetricsService     # Micrometer 指标
```

## 架构说明

> 详细架构设计请参阅 [docs/项目架构与设计.md](docs/项目架构与设计.md)，包含核心组件说明、数据流、依赖关系、配置参考、日志体系及全 Phase 路线图。

### 迭代路线图

| Phase | 内容 | 文档 |
|-------|------|------|
| 1 | 基础架构 + RAG 检索 | [docs/phase1-基础架构与RAG检索.md](docs/phase1-基础架构与RAG检索.md) |
| 2 | 向量库持久化 + 重排序 + 多轮对话 | [docs/phase2-向量库持久化-重排序-多轮对话.md](docs/phase2-向量库持久化-重排序-多轮对话.md) |
| 3 | 生产化增强（Redis + 多格式文档 + SSE + Function Calling） | [docs/phase3-生产化增强-Redis-多格式文档.md](docs/phase3-生产化增强-Redis-多格式文档.md) |
| 4 | 精度提升（查询改写 + 交叉编码器重排序 + 上下文压缩） | [docs/phase4-精度提升.md](docs/phase4-精度提升.md) |
| 5 | 运维与管理（知识库API + 缓存 + 容错 + 可观测性） | [docs/phase5-运维与管理.md](docs/phase5-运维与管理.md) |

更多内容请参见 [docs/API接口文档.md](docs/API接口文档.md)。
