既然你对“内容管理 + AI 增强”这类逻辑感兴趣，我们可以将目光从“感性创作（小说）”转向“**理性知识（科研/学习）**”。

现在的热门痛点是：**信息过载**。用户面对大量的 PDF 论文、会议纪要和网页剪报，很难快速建立知识关联。

我为你策划的第二个项目是：**"Lumina Mind" (萤火笔记) —— 语义关联型 AI 知识中台**。

***

# 产品需求文档 (PRD) - Lumina Mind 智能知识中台

## 1. Project Definition

- **项目名称**: Lumina Mind (萤火笔记)
- **项目描述**: 一个基于“原子化笔记”概念的 AI 知识管理平台，通过 AI 自动提取知识点间的语义关联，构建非线性的知识图谱。
- **目标用户**: 研究人员、学生、重度阅读者、产品经理。
- **核心问题**: 解决笔记碎片化、收集不消化、以及无法在数千条笔记中快速找到“灵感关联”的问题。

## 2. Task Analysis

- **任务类型**: 0-1 Project Generation (全栈工程)
- **领域**: AI + Graph Database + Knowledge Management
- **范围**: 跨模块 (PDF 解析、向量化、图谱可视化、AI 对话)
- **假设**: 用户上传内容以 PDF 和 Markdown 为主；侧重于“发现关联”而非单纯“存储”。

## 3. MVP Feature Design

- **核心功能 (Core)**:
  - **多源上传**: 支持 PDF、URL 抓取和 Markdown 直接输入。
  - **自动原子化**: AI 自动将长文档拆解为“知识卡片”。
  - **语义雷达**: 自动识别当前笔记与历史笔记的相似点，并在侧边栏推荐“你可能还记过...”。
  - **双向链接**: 自动生成的智能引用（Auto-Backlink）。
- **可选功能 (Optional)**: 全库 AI 问答（基于所有笔记回答问题）、知识图谱可视化、Anki 闪卡自动生成。
- **非目标 (Out of Scope)**: 多人协作实时编辑、手写笔记识别。

## 4. System Design

- **系统架构**:
  - **Parsing Layer**: `Marker` 或 `PyMuPDF` 处理文档解析。
  - **Storage Layer**: `PostgreSQL` (结构化数据) + `Neo4j` (关系图谱) + `Milvus` (语义向量)。
  - **Logic Layer**: 使用任务队列 (Redis + Celery) 处理耗时的向量计算与拓扑构建。
- **数据流**: 用户上传 -> 文档分片 -> AI 提取关键词与摘要 -> 向量化入库 -> Graph DB 建立潜在联系。

## 5. Detailed Feature Design: 语义雷达 (Semantic Radar)

- **功能描述**: 当用户在写新笔记时，系统实时计算并显示与当前思考内容相关的历史碎片。
- **输入**: `current_note_draft` (当前草稿文本)。
- **输出**: 关联度排名 Top 5 的历史笔记片段及其“关联理由”。
- **处理步骤**:
  1. 提取草稿的 Embedding。
  2. 在向量库中执行相似度检索 (Cosine Similarity)。
  3. 将 Top 结果与当前草稿发送给 LLM。
  4. LLM 生成一句话关联理由（例如：“这段话与你在《XX书籍》中记录的关于分布式系统的观点一致”）。
- **异常处理**: 若无相关内容，则显示“暂无相关灵感关联”。

## 6. Data Model

JSON

```
{
  "card": {
    "id": "uuid",
    "content": "string",
    "source_url": "string",
    "tags": ["string"],
    "vector_embedding": "float[]",
    "related_nodes": [
      {
        "target_id": "uuid",
        "relation_type": "contradicts | supports | explains",
        "reason": "string"
      }
    ]
  }
}

```

## 7. API Design

- **POST** `/api/v1/cards/ingest`: 上传并自动处理。
- **POST** `/api/v1/cards/suggest`: 获取实时关联建议。
  - Request: `{ "text": "当前书写的段落..." }`
- **GET** `/api/v1/graph/subgraph`: 获取以当前卡片为中心的局部图谱数据。

## 8. Pseudo Code (关联发现逻辑)

Python

```
def find_hidden_connections(new_card_content):
    # 1. 语义搜索
    candidates = vector_db.search(new_card_content, top_k=10)
    
    # 2. 逻辑关联判定
    connections = []
    for cand in candidates:
        relation = llm.reasoning(
            f"判断 A: {new_card_content} 与 B: {cand.text} 的逻辑关系。"
            "输出格式：类型(矛盾/支持/补充), 理由"
        )
        if relation.is_meaningful:
            connections.append({"id": cand.id, "rel": relation})
            
    # 3. 更新图数据库
    graph_db.create_edges(new_card_id, connections)

```

## 9. Tech Stack

- **前端**: **Next.js** + **React Force Graph** (用于展示知识球体图谱)。
- **后端**: **Python (FastAPI)**。
- **AI 层**: **LlamaIndex** (专门为知识索引设计的框架)。
- **数据库**: **Supabase** (Postgres) + **Neo4j** (社区版即可)。

## 10. Risks & Optimizations

- **风险**: 知识卡片过多导致图谱变成“乱麻”。 **优化**: 引入“重要度权重”算法（类似 PageRank），仅显示高权重节点。
- **性能**: 实时语义搜索压力。 **优化**: 采用客户端本地缓存 + 后端防抖查询。

## 11. Dev Plan

1. **Step 1**: 实现 Markdown 编辑器与基础的卡片增删改查。
2. **Step 2**: 接入 LLM，实现长文自动拆解为原子卡片的功能。
3. **Step 3**: 部署向量数据库，实现基于内容的相似度推荐。
4. **Step 4**: 集成图数据库，可视化卡片间的连接。
5. **Step 5**: 优化“语义雷达”的响应速度，支持毫秒级灵感反馈。

