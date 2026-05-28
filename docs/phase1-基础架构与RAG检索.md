# Phase 1 - 基础架构与 RAG 检索

## 目标
搭建 Spring AI Alibaba 智能客服 AI Agent 基础框架，实现知识库加载和 RAG 检索问答。

## 完成内容

### 1. 项目脚手架
- Spring Boot 3.3.13 + Spring AI Alibaba 1.1.2.2
- DashScope (通义千问 `qwen-max`) 作为 LLM，`text-embedding-v3` 作为嵌入模型
- Maven Wrapper 绕过旧版 Maven 3.3.9 限制
- 兼容 Maven 3.3.9 的插件版本覆盖（maven-compiler-plugin 3.11.0 等）
- 日志与控制台编码强制 UTF-8（logback-spring.xml + JVM 参数）

### 2. 知识库加载 — `KnowledgeService`
- 启动时自动扫描 `classpath:knowledge/*.txt`
- 使用 `TextReader` 读取 + `TokenTextSplitter` 分块
- 写入 `SimpleVectorStore`（纯内存向量库）
- 示例知识库：`faq.txt`（发货/支付/售后FAQ）、`refund-policy.txt`（退换货政策）

### 3. RAG 配置 — `RAGConfig`
- `SimpleVectorStore` + `ChatClient` Bean 声明
- `buildContext()` 方法：向量检索 → 拼接知识上下文字符串

### 4. 对话接口 — `ChatController`
- `GET /api/chat?message=xxx`
- 手动两步 RAG：检索 → 构造 system prompt → 调用 LLM
- 有两套 system prompt：
  - **有上下文**：基于知识回答，禁止编造
  - **无上下文**：引导转人工客服（防幻觉）

### 5. 工程配置
- `.gitignore` 排除 target / IDE 文件
- Git 仓库初始化（仓库在 codex-test 根目录）
- `mvn compile` / `mvn package` 编译验证通过

## 关键技术决策
| 决策 | 方案 |
|------|------|
| 向量库 | `SimpleVectorStore`（纯内存，零外部依赖，Phase 1 快速验证） |
| RAG 模式 | 手动两步（检索→生成），而非 `QuestionAnswerAdvisor` |
| Maven 兼容 | Maven Wrapper + 插件版本覆盖 |

## 启动方式
```bash
set AI_DASHSCOPE_API_KEY=YourKey
mvn spring-boot:run

# 测试
curl "http://localhost:8080/api/chat?message=你们的退货政策是什么？"
```
