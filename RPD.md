**InkSoul AI (墨魂) - 智能小说创作管理平台**

***

# 产品需求文档 (PRD) - InkSoul AI 智能小说管理平台

## 1. Project Definition

- **项目名称**: InkSoul AI (墨魂)
- **项目描述**: 一款面向网文作者的 AI 增强型创作管理平台，集成结构化大纲管理、角色知识库（RAG）与沉浸式 AI 辅助写作。
- **目标用户**: 独立作者、网文工作室、内容策划。
- **核心问题**: 解决长篇创作中的“人设崩塌”、“情节卡顿”及“设定查找困难”痛点。

## 2. Task Analysis

- **任务类型**: 0-1 Project Generation (全栈工程)
- **领域**: AI 应用 + 富文本编辑器 + 内容管理 (CMS)
- **范围**: 涵盖前端编辑器、后端 API、向量存储、AI 编排
- **假设**: 使用 OpenAI/DeepSeek 兼容接口；前端支持 Web 端；初期不考虑多端实时同步（单端持久化）。

## 3. MVP Feature Design

- **核心功能 (Core)**:
  - **工作台**: 卷-章-节三级树状结构管理。
  - **角色/设定库**: 结构化录入人设、地理、功法等信息，并支持向量化检索。
  - **AI 智笔**: 选中文本续写、基于当前章节语境的润色、灵感对话框。
  - **沉浸式编辑器**: 无干扰写作模式、字数统计、自动保存。
- **可选功能 (Optional)**: 角色关系图谱可视化、全书一键导出 (PDF/Epub)、AI 生成角色插图。
- **非目标 (Out of Scope)**: 用户社交系统、在线阅读发布平台。

## 4. System Design

- **系统架构**: 典型的 **BFF (Backend For Frontend)** 模式。
  - **Frontend**: Next.js (App Router) + TipTap Editor + Shadcn UI。
  - **Backend**: Python FastAPI (处理 AI 流式响应) + PostgreSQL (结构化数据)。
  - **Vector DB**: Pinecone 或 PostgreSQL pgvector (存储设定集索引)。
- **数据流**: 用户输入 -> TipTap 插件触发 -> 后端检索向量库匹配人设 -> 组装 Prompt -> 调用 LLM -> SSE 流式回显。

## 5. Detailed Feature Design: AI 知识库续写

- **功能描述**: 当作者写作遇到瓶颈时，AI 参考已有的“角色卡”和“前文”进行逻辑一致的续写。
- **输入**: `novel_id`, `chapter_content`, `last_n_tokens`。
- **输出**: 增量文本流 (Stream)。
- **处理步骤**:
  1. **Context Extraction**: 提取当前光标前 2000 字。
  2. **Semantic Search**: 将文本转为向量，在设定库中检索相关度最高的 3 条角色/背景设定。
  3. **Prompt Engineering**: 模板为：`你是一个小说家，请根据【背景设定】和【前文】进行续写，保持风格一致。背景：{retrieved_data}，前文：{context}`。
  4. **Streaming**: 使用 OpenAI Stream 接口返回。
- **异常处理**: 若向量库检索为空，则仅基于前文续写；若 API 报错，前端 Toast 提示并保留已写内容。

## 6. Data Model

JSON

```
{
  "novel": {
    "id": "uuid",
    "title": "string",
    "description": "text",
    "chapters": [
      {
        "id": "uuid",
        "title": "string",
        "content": "text (html/json)",
        "order": "integer"
      }
    ],
    "assets": [
      {
        "id": "uuid",
        "type": "character|world|item",
        "name": "string",
        "content": "text",
        "vector_id": "string"
      }
    ]
  }
}

```

## 7. API Design

- **POST** `/api/v1/write/autocomplete`: 触发 AI 续写。
  - Request: `{ "novel_id": "...", "text": "...", "metadata": {} }`
  - Response: `text/event-stream`
- **GET** `/api/v1/assets/search`: 搜索设定。
  - Query: `q=角色名&limit=5`
- **PATCH** `/api/v1/chapters/{id}`: 增量保存章节。

## 8. Pseudo Code (Core Logic)

Python

```
# 后端：AI 增强推理逻辑
async def ai_autocomplete(novel_id, user_text):
    # 1. 获取向量嵌入并检索知识库
    query_vector = embeddings.embed_query(user_text[-100:])
    relevant_assets = vector_db.search(novel_id, query_vector, k=2)
    
    # 2. 构建 Prompt
    context_str = "\n".join([a.content for a in relevant_assets])
    full_prompt = f"设定依据：{context_str}\n续写内容：{user_text}"
    
    # 3. 调用大模型并流式输出
    async for chunk in llm.astream(full_prompt):
        yield f"data: {chunk.content}\n\n"

```

## 9. Tech Stack

- **Frontend**: React + **TipTap** (极其重要，因为需要自定义 AI 插件) + **Zustand**。
- **Backend**: **FastAPI** (支持异步和流式输出)。
- **Database**: **Supabase** (集成 Postgres + Auth + Vector)。
- **AI Framework**: **LangChain** (用于处理 RAG 链)。

## 10. Risks & Optimizations

- **风险**: 续写内容与前文风格突变。 **优化**: 在 Prompt 中加入 Few-shot 示例，抽取用户前文的 3 个片段作为 Style Reference。
- **性能**: 长文本渲染卡顿。 **优化**: TipTap 采用分段渲染技术，仅加载当前编辑章节。

## 11. Dev Plan

1. **Step 1**: 搭建 Next.js 项目，实现左侧目录树和中间编辑器（基于 TipTap）。
2. **Step 2**: 设计数据库表结构，实现章节的 CRUD 和自动保存。
3. **Step 3**: 集成向量数据库，实现角色卡录入与语义检索。
4. **Step 4**: 编写 TipTap AI 扩展，实现“斜杠命令”调用后端续写接口。
5. **Step 5**: 优化 UI/UX，添加打字机效果的流式输出显示。

***

**提示**: 您可以先从 **Step 1 (编辑器基础)** 开始。如果您需要我为您生成具体的 `TipTap AI Extension` 代码，请随时告诉我。
