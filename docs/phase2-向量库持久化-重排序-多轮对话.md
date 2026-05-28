# Phase 2 - 向量库持久化、重排序、多轮对话

## 目标
在 Phase 1 基础上增强 RAG 质量与对话体验：向量库持久化、检索结果重排序、多轮会话上下文管理。

## 完成内容

### 1. 向量库文件持久化 — `KnowledgeService`
- `SimpleVectorStore` 启动时自动从 `./data/vector-store.json` 加载
- 若文件不存在则从 `classpath:knowledge/*.txt` 重新加载并写入文件
- 关闭前（`@PreDestroy`）自动保存，重启不丢失知识库
- 删除 `vector-store.json` 即触发重新索引

### 2. 重排序服务 — 新增 `ReRankerService`
- 混合评分策略，无需额外网络请求：
  - **向量相似度（70%）**：直接复用 `Document.getScore()` 中的向量检索分数
  - **关键词重叠（30%）**：计算问题与文档的公共词占比
- 配置项：
  - `app.reranker.top-k`：最终保留条数（默认 3）
  - `app.reranker.retrieve-top-k`：向量库检索条数（默认 10）

### 3. 多轮对话 — `ChatController`
- 新增 `sessionId` 查询参数（默认 `"default"`），每个会话独立维护上下文
- 使用 `ConcurrentHashMap<String, List<Message>>` 管理会话历史
- 每次请求注入最近 10 轮对话历史
- System prompt 更新：新增"结合对话历史理解上下文"规则
- 无知识时：优先用历史回答，其次引导转人工

### 4. 配置重构 — `RAGConfig`
- `SimpleVectorStore` 作为独立 Bean 暴露（供 `KnowledgeService` 直接注入使用持久化 API）
- `VectorStore` 接口 Bean 指向同一实例（供 `ChatController` 检索使用）
- 移除非必要的 `buildContext()` 方法，检索逻辑转移至 Controller

## 配置文件新增
```yaml
app:
  vectorstore:
    persistence-path: ./data/vector-store.json
  reranker:
    top-k: 3
    retrieve-top-k: 10
```

## 启动方式
```bash
set AI_DASHSCOPE_API_KEY=YourKey
mvn spring-boot:run

# 测试多轮对话
curl "http://localhost:8080/api/chat?sessionId=user1&message=有什么退货政策？"
curl "http://localhost:8080/api/chat?sessionId=user1&message=运费谁承担？"
curl "http://localhost:8080/api/chat?sessionId=user2&message=你好"  # 独立会话
```

## 文件清单
| 文件 | 说明 |
|------|------|
| `service/ReRankerService.java` | 新增：重排序服务（向量+关键词混合评分） |
| `service/KnowledgeService.java` | 重构：增加 save/load 文件持久化 |
| `controller/ChatController.java` | 重构：多轮对话 + 重排序集成 |
| `config/RAGConfig.java` | 重构：SimpleVectorStore 独立 Bean |
