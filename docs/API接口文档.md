# API 接口文档

> 智能客服 AI Agent — 基于 Spring AI Alibaba + DashScope (通义千问)

---

## 基础信息

| 项目 | 值 |
|------|-----|
| 基础 URL | `http://localhost:8080` |
| 数据格式 | JSON（特殊标注除外） |
| 字符编码 | UTF-8（全链路强制） |
| 文件上传限制 | 10MB |

---

## 一、聊天接口

---

### 1.1 同步聊天

`GET /api/chat`

返回纯文本回复，适合简单集成。

#### 请求参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `sessionId` | string | 否 | `"default"` | 会话 ID，用于多轮对话上下文 |
| `message`  | string | 是 | — | 用户发送的消息内容（UTF-8 URL 编码） |

#### 请求示例

```bash
curl "http://localhost:8080/api/chat?sessionId=user123&message=你们的退货政策是什么？"
```

#### 响应示例

```
Content-Type: text/plain;charset=UTF-8

您好！根据我们的退换货政策，自签收之日起7天内，商品未使用、未拆封、不影响二次销售的，支持无理由退换货。如果是质量问题，自签收之日起15天内可申请退换货。请问您需要具体了解哪方面的政策呢？
```

#### 流程说明

```
请求 → 缓存查询 → 查询改写 → 多路召回(向量+BM25) → RRF融合 → 重排序
→ 人工转接检测 → LLM调用(含重试) → 写入缓存 → 保存会话 → 返回
```

---

### 1.2 流式聊天 (SSE)

`GET /api/chat/stream`

返回 `text/event-stream`，逐 token 推送，首字延迟 ~200ms。

#### 请求参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `sessionId` | string | 否 | `"default"` | 会话 ID，用于多轮对话上下文 |
| `message`  | string | 是 | — | 用户发送的消息内容 |

#### 请求示例

```bash
# Windows curl 需加 -N (no-buffer)
curl -N "http://localhost:8080/api/chat/stream?sessionId=user123&message=查一下我的订单ORD20240501001"
```

#### SSE 事件格式

**普通 token：**
```
data: {"content":"您好"}

data: {"content":"，"}

data: {"content":"您"}
...
```

**完成事件：**
```
event: done
data: {"type":"done","content":"完整回复内容..."}
```

**错误事件：**
```
event: error
data: {"type":"error","message":"错误描述"}
```

#### 响应字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | string | 当前 token 文本（data 事件）或完整回复（done 事件） |
| `type`    | string | 事件类型：`done`（完成）/ `error`（错误） |

---

## 二、知识库管理

---

### 2.1 列出知识库文件

`GET /api/knowledge`

返回所有已加载的知识库文档清单。

#### 请求示例

```bash
curl "http://localhost:8080/api/knowledge"
```

#### 响应示例

```json
{
  "total": 5,
  "initialized": true,
  "files": [
    {
      "filename": "faq.txt",
      "chunkCount": 1,
      "preview": "问：你们的发货时间是什么？答：我们支持全国范围内发货，默认快递为顺丰速运...",
      "addedAt": "2026-05-28T12:00:00"
    },
    {
      "filename": "account-faq.pdf",
      "chunkCount": 2,
      "preview": "问1：如何注册账号？答：您可以通过以下方式注册账号：1. 访问官网首页...",
      "addedAt": "2026-05-28T12:00:00"
    }
  ]
}
```

#### 响应字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `total` | int | 文件总数 |
| `initialized` | bool | 知识库是否已初始化 |
| `files` | array | 文件列表 |
| `files[].filename` | string | 文件名 |
| `files[].chunkCount` | int | 该文件被分割的块数 |
| `files[].preview` | string | 首块内容预览（前80字符） |
| `files[].addedAt` | string | 添加时间（ISO 8601） |

---

### 2.2 上传知识库文件

`POST /api/knowledge`

上传文档文件，自动解析为文本，分块后写入向量库和 BM25 索引。

#### 请求

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `file` | file (multipart) | 是 | 文档文件（支持 txt/pdf/docx/md/html/htm，上限 10MB） |

#### 请求示例

```bash
curl -X POST "http://localhost:8080/api/knowledge" \
  -F "file=@/path/to/return-policy.pdf"
```

#### 响应示例（成功）

```json
{
  "success": true,
  "message": "上传成功",
  "filename": "return-policy.pdf",
  "chunkCount": 3
}
```

#### 响应示例（失败）

```json
{
  "success": false,
  "message": "解析失败: ..."
}
```

#### 响应字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `success` | bool | 是否成功 |
| `message` | string | 结果描述 |
| `filename` | string | 文件名 |
| `chunkCount` | int | 分割后的文档块数（成功时） |

---

### 2.3 查看文件详情

`GET /api/knowledge/{filename}`

查看指定文件的内容预览。

#### 请求示例

```bash
curl "http://localhost:8080/api/knowledge/faq.txt"
```

#### 响应示例

```json
{
  "meta": {
    "filename": "faq.txt",
    "chunkCount": 1,
    "preview": "问：你们的发货时间是什么？...",
    "addedAt": "2026-05-28T12:00:00"
  },
  "content": "问：你们的发货时间是什么？\n答：我们支持全国范围内发货...\n..."
}
```

#### 响应字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `meta` | object | 文件元数据（同 list 接口） |
| `content` | string | 完整文本内容（按块拼接） |

---

### 2.4 删除知识库文件

`DELETE /api/knowledge/{filename}`

从向量库和 BM25 索引中删除指定文件。

#### 请求示例

```bash
curl -X DELETE "http://localhost:8080/api/knowledge/return-policy.pdf"
```

#### 响应示例

```json
{
  "success": true,
  "message": "已删除: return-policy.pdf"
}
```

---

### 2.5 重新加载知识库

`POST /api/knowledge/reload`

清空所有已加载的知识库内容，重新扫描 `classpath:knowledge/` 下的所有文档文件。

#### 请求示例

```bash
curl -X POST "http://localhost:8080/api/knowledge/reload"
```

#### 响应示例

```json
{
  "success": true,
  "message": "重新加载完成",
  "fileCount": 5
}
```

#### 响应字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `fileCount` | int | 重载后的文件数 |

---

## 三、运维与监控

---

### 3.1 健康检查

`GET /actuator/health`

#### 请求示例

```bash
curl "http://localhost:8080/actuator/health"
```

#### 响应示例

```json
{
  "status": "UP"
}
```

---

### 3.2 应用信息

`GET /actuator/info`

---

### 3.3 Prometheus 指标

`GET /actuator/prometheus`

返回 Prometheus 格式的监控指标，需配置 Prometheus Server 拉取。

#### 指标列表

| 指标名 | 类型 | 标签 | 说明 |
|--------|------|------|------|
| `app_chat_request_total` | Counter | — | 聊天请求总数 |
| `app_chat_request_success` | Counter | — | 成功请求数 |
| `app_chat_request_error` | Counter | — | 失败请求数 |
| `app_chat_cache_hit` | Counter | — | 缓存命中数 |
| `app_chat_cache_miss` | Counter | — | 缓存未命中数 |
| `app_chat_handoff_count` | Counter | — | 人工转接次数 |
| `app_chat_retry_count` | Counter | — | LLM 重试次数 |
| `app_chat_llm_duration_seconds` | Timer | — | LLM 调用耗时分布 |
| `app_chat_retrieval_duration_seconds` | Timer | — | 检索耗时分布 |
| `app_knowledge_document_count` | Gauge | — | 知识库文档块数 |
| `app_cache_size` | Gauge | — | 缓存条目数 |

所有指标带标签 `application="customer-service-agent"`。

---

## 四、错误码

| HTTP 状态码 | 说明 |
|-------------|------|
| 200 | 请求成功 |
| 400 | 参数错误（如文件为空） |
| 404 | 文件不存在 |
| 500 | 服务器内部错误 |
| 503 | LLM 服务不可用（已降级） |

---

## 五、curl 测试速查

```bash
# 1. 同步聊天
curl "http://localhost:8080/api/chat?message=你好"

# 2. 多轮对话
curl "http://localhost:8080/api/chat?sessionId=user1&message=有什么退货政策？"
curl "http://localhost:8080/api/chat?sessionId=user1&message=运费谁承担？"

# 3. SSE 流式聊天 (Windows 需 -N)
curl -N "http://localhost:8080/api/chat/stream?message=发货政策是什么？"

# 4. Function Calling — 查订单
curl "http://localhost:8080/api/chat?message=查一下订单ORD20240501001"

# 5. Function Calling — 退货
curl "http://localhost:8080/api/chat?message=我要退货，订单号ORD20240501001"

# 6. 列出知识库
curl "http://localhost:8080/api/knowledge"

# 7. 上传知识库文档
curl -X POST "http://localhost:8080/api/knowledge" -F "file=@doc.pdf"

# 8. 查看文件详情
curl "http://localhost:8080/api/knowledge/faq.txt"

# 9. 删除文件
curl -X DELETE "http://localhost:8080/api/knowledge/faq.txt"

# 10. 重载知识库
curl -X POST "http://localhost:8080/api/knowledge/reload"

# 11. 健康检查
curl "http://localhost:8080/actuator/health"

# 12. Prometheus 指标
curl "http://localhost:8080/actuator/prometheus"
```

---

## 六、配置参考

接口行为可通过 `application.yml` 配置项调整：

```yaml
app:
  cache:
    enabled: true                # 启用请求缓存
    ttl-minutes: 60              # 缓存 TTL

  fallback:
    enabled: true                # 启用容错降级
    max-retries: 1               # LLM 调用重试次数

  handoff:
    enabled: true                # 启用人工转接
    phone: "400-888-8888"        # 客服热线
```

更多配置项见 `docs/项目架构与设计.md`。
