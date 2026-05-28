# AGENTS.md — 智能客服 AI Agent

## Quick Start

```bash
docker compose up -d                     # 启动 Redis Stack（必须先运行）
set AI_DASHSCOPE_API_KEY=sk-...         # 环境变量，或直接在 application.yml 中硬编码
.\mvnw spring-boot:run                  # 启动应用（端口 8080）
```

## Build

```bash
.\mvnw compile                          # 仅编译
.\mvnw spring-boot:run                  # 编译+运行
```

- 无 lint / typecheck / checkstyle / spotless 配置——只需 `compile` 通过
- `pom.xml` 已强制 `-Dfile.encoding=UTF-8`
- `spring-ai` BOM 和 `spring-ai-alibaba` 版本在 `<properties>` 中独立声明

## Tests

**此项目没有任何测试**，`src/test/` 不存在。不要运行测试命令。

## Runtime Requirements

- **Redis Stack** 必须运行（`docker-compose.yml` 已提供）
- **环境变量 `AI_DASHSCOPE_API_KEY`** 必须设置 DashScope API Key
- 应用启动后 `KnowledgeService`（`CommandLineRunner`）自动加载 `classpath:knowledge/*.{txt,pdf,docx,md,html}` 到 Redis 向量库
- Redis key `app:knowledge:initialized` 标记初始化状态，已标记则跳过加载（重启不重新加载）
- `DocumentStore` 是纯内存缓存，重启后丢失；classpath 文档在 `run()` 中重新加载，但热上传的文件需调用 `/api/knowledge/reload` 恢复

## Architecture

- 单一模块 Maven 项目，包 `com.example.customeragent`
- **入口**：`CustomerServiceAgentApplication.java`
- **关键配置类**：`RAGConfig.java`（声明 `RedisVectorStore`、`ChatClient`、`JedisPooled` 三个 Bean）
- **检索流程**：查询改写 → 向量检索 → 截断 `fusionTopK` → 重排序（交叉编码器 gte-rerank 或 Hybrid）→ LLM
- **检索已移除 BM25**，仅保留纯向量检索 + 重排序链路
- SSE 流式端点在 `ChatController.java`，超时 300s
- **API Key 配置**：`spring.ai.dashscope.api-key=${AI_DASHSCOPE_API_KEY}`

## API Endpoints

| 端点 | 说明 |
|------|------|
| `GET /api/chat` | 同步对话 |
| `GET /api/chat/stream` | SSE 流式对话 |
| `POST /api/knowledge` | 上传文档（multipart, ≤10MB） |
| `GET /api/knowledge` | 列出文件 |
| `DELETE /api/knowledge/{filename}` | 删除文件 |
| `POST /api/knowledge/reload` | 热重载知识库 |
| `GET /actuator/prometheus` | 监控指标 |

启动后 `http://localhost:8080/swagger-ui.html` 可查看完整 API 文档。

## Stale References

以下文档文件中的 BM25 引用已被清理，但如果修改相关文案请注意不再提及 BM25：
- `docs/项目架构与设计.md`
- `docs/phase4-精度提升.md`
- `docs/API接口文档.md`
- `docs/phase5-运维与管理.md`

## Code Style

- 变量 camelCase，类 PascalCase，无特定格式化工具
- Swagger 注解用于所有 Controller 端点（`@Tag` / `@Operation` / `@Parameter` / `@ApiResponse`）
- 日志使用 SLF4J，通过 `logback-spring.xml` 输出到控制台和滚动文件（`logs/` 目录，保留 30 天）
- Logback `<charset>UTF-8</charset>` 已强制
- 知识库文档路径 `src/main/resources/knowledge/`，支持：txt, pdf, docx, md, html, htm
