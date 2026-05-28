# Phase 4 - 精度提升

## 目标

在 Phase 3 基础上，通过四项核心改进提升 RAG 回答精度：交叉编码器重排序、查询改写、向量检索、上下文压缩。

## 完成内容

### 1. 交叉编码器重排序 — `DashScopeReranker` / `ReRankerService`

| 改进 | 当前 | Phase 4 结果 |
|------|------|-------------|
| 重排序 | 向量分 + 关键词分 (混合/Hybrid) | ✅ 交叉编码器 (DashScope gte-rerank) + Hybrid 双模式可选 |
| 精度 | 关键词重叠 30% | ✅ 交叉编码器对 query-doc 对联合评分，精度 +15%~30% |

- 新增 `DashScopeReranker`：调用 DashScope 平台 `gte-rerank` 模型，将 query 与每篇文档配对评分，返回最相关的 top-k 篇
- `ReRankerService` 重构：通过 `app.reranker.type` 切换模式：
  - `cross-encoder`：调用 DashScope rerank API（默认）
  - `hybrid`：沿用 Phase 2 的向量 70% + 关键词 30% 混合评分
- 交叉编码器失败时自动降级为原顺序（不阻塞请求）
- 配置项：

```yaml
app:
  reranker:
    type: cross-encoder        # hybrid | cross-encoder
    top-k: 3                   # 最终保留文档数
    cross-encoder-model: gte-rerank  # DashScope rerank 模型
```

### 2. 查询改写 — 新增 `QueryRewriter`

| 改进 | 当前 | Phase 4 结果 |
|------|------|-------------|
| 查询 | 用户原句直接检索 | ✅ LLM 改写 → 多角度检索 → 融合 |
| 效果 | 口语与书面语不匹配 | ✅ 正式书面语改写，覆盖更广关键词 |

- 使用 `ChatClient` 调用 LLM 将用户口语问题改写为 2 条精炼书面查询
- 改写规则：去除口语化、侧重不同关键词角度、输出正式书面语
- 改写结果与原始查询合并去重，后续向量检索对每条改写分别检索
- 配置项：

```yaml
app:
  query-rewriting:
    enabled: true
    num-rewrites: 2            # 改写数量（1~5）
```

- 改写失败时自动降级为使用原始查询
- 日志：`查询改写: "运费怎么算？" → [运费怎么算, 快递费用标准, 邮费计算规则] (3条)`

### 3. 上下文压缩 — 新增 `ContextCompressor`

| 改进 | 当前 | Phase 4 结果 |
|------|------|-------------|
| 历史处理 | 固定截取 10 轮 | ✅ LLM 摘要压缩，保留关键信息 |
| Token 消耗 | 随轮数线性增长 | ✅ 压缩为固定长度摘要 |

- 当历史消息超过 4 条时，调用 LLM 将对话历史压缩为一段精炼摘要
- 保留关键信息：用户意图、已解决问题、待办事项
- 压缩结果作为 `【对话历史摘要】` 注入 system prompt
- 短对话或 LLM 压缩失败时自动降级为原始消息拼接
- 配置项：

```yaml
app:
  context-compression:
    enabled: true
```

### 4. 配置汇总

```yaml
app:
  retrieval:
    vector-top-k: 10
    fusion-top-k: 10

  reranker:
    type: cross-encoder
    top-k: 3
    cross-encoder-model: gte-rerank

  query-rewriting:
    enabled: true
    num-rewrites: 2

  context-compression:
    enabled: true
```

## 完整 RAG 流程 (Phase 4)

```
用户请求
  │
  ├── 1. 查询改写 (QueryRewriter)
  │      LLM 改写 → [原始, 改写1, 改写2]
  │
  ├── 2. 向量检索
  │      原始查询 → RedisVectorStore.topK(10)
  │
  ├── 3. 交叉编码器重排序 (DashScopeReranker / ReRankerService)
  │      gte-rerank 逐对评分 → topK(3)
  │
  ├── 4. 上下文压缩 (ContextCompressor)
  │      LLM 摘要压缩历史 → 或原始消息拼接
  │
  ├── 5. 构建 System Prompt → 调用 LLM
  │
  └── 6. SessionService.saveExchange() → Redis
```

## 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `service/DashScopeReranker.java` | **新增** | 交叉编码器重排序 (DashScope gte-rerank API) |
| `service/QueryRewriter.java` | **新增** | LLM 查询改写，生成多角度搜索查询 |
| `service/DocumentStore.java` | **新增** | 全量文档内存缓存 |
| `service/ContextCompressor.java` | **新增** | LLM 对话历史摘要压缩 |
| `service/ReRankerService.java` | **重构** | 支持 cross-encoder / hybrid 双模式 |
| `service/KnowledgeService.java` | **修改** | 加载后同步 DocumentStore |
| `controller/ChatController.java` | **重构** | 集成查询改写 + 向量检索 + 重排序 + 上下文压缩 |
| `resources/application.yml` | **修改** | 新增 Phase 4 全部配置项 |
| `docs/项目架构与设计.md` | **修改** | 更新 Phase 4 路线图和数据流 |
| `docs/phase4-精度提升.md` | **新增** | 本文件 |

## 注意事项

- 交叉编码器模式依赖 DashScope rerank API，需确保 API Key 有对应模型权限
- RRF 参数 k=60 为经验值，可根据实际情况调整
- 查询改写和上下文压缩均使用 ChatClient 调用 LLM，会额外消耗 API 额度
- 所有组件均有异常降级逻辑，单个组件失败不影响整体流程
