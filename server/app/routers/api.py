from fastapi import APIRouter, HTTPException
from typing import List, Optional
import math
import random

from app.models.schemas import (
    KnowledgeCard,
    KnowledgeCardCreate,
    IngestRequest,
    SuggestRequest,
    SemanticSuggestion,
    GraphData,
    RelationType,
)
from app.services.memory_store import memory_store, random_vector

router = APIRouter(prefix="/api/v1")


def cosine_similarity(vec1: List[float], vec2: List[float]) -> float:
    dot_product = sum(a * b for a, b in zip(vec1, vec2))
    norm1 = math.sqrt(sum(a * a for a in vec1))
    norm2 = math.sqrt(sum(b * b for b in vec2))
    if norm1 == 0 or norm2 == 0:
        return 0.0
    return dot_product / (norm1 * norm2)


def generate_simple_embedding(text: str) -> List[float]:
    embedding = [random.random() for _ in range(128)]
    
    keyword_weights = {
        "机器学习": 1.5, "深度学习": 1.5, "神经网络": 1.5,
        "人工智能": 1.2, "自然语言处理": 1.2, "nlp": 1.2,
        "向量": 1.0, "嵌入": 1.0, "语义": 1.0,
        "知识图谱": 1.3, "图": 1.0, "关系": 1.0,
        "数据": 0.8, "学习": 0.8, "模型": 0.8,
    }
    
    text_lower = text.lower()
    for keyword, weight in keyword_weights.items():
        if keyword in text_lower:
            idx = hash(keyword) % 128
            embedding[idx] += weight * 0.3
    
    norm = math.sqrt(sum(v * v for v in embedding))
    if norm > 0:
        embedding = [v / norm for v in embedding]
    
    return embedding


def generate_relation_reason(source_content: str, target_content: str) -> tuple:
    source_lower = source_content.lower()
    target_lower = target_content.lower()
    
    keywords = {
        "机器学习": ["深度学习", "神经网络", "监督学习", "无监督学习"],
        "深度学习": ["神经网络", "卷积神经网络", "递归神经网络"],
        "自然语言处理": ["nlp", "文本", "语言", "语义"],
        "向量": ["嵌入", "语义搜索", "相似度"],
        "知识图谱": ["图", "关系", "节点", "边"],
    }
    
    explains = False
    
    for main_key, related_keys in keywords.items():
        if main_key in source_lower:
            for rel_key in related_keys:
                if rel_key in target_lower:
                    explains = True
                    break
    
    source_words = set(source_lower.split())
    target_words = set(target_lower.split())
    common_words = source_words & target_words
    supports = len(common_words) >= 3
    
    if explains:
        relation_type = RelationType.explains
        reason = "内容主题相关，提供补充说明"
    elif supports:
        relation_type = RelationType.supports
        reason = "共享多个关键词，内容相互支持"
    else:
        relation_type = RelationType.explains
        reason = "基于语义相似度发现的潜在关联"
    
    return relation_type, reason


@router.post("/cards/ingest", response_model=KnowledgeCard)
async def ingest_card(request: IngestRequest):
    card_create = KnowledgeCardCreate(
        content=request.content,
        source_url=request.source_url,
        tags=request.tags or [],
    )
    
    card = memory_store.create_card(card_create)
    
    embedding = generate_simple_embedding(card.content)
    card = memory_store.update_card_embedding(card.id, embedding)
    
    all_cards = memory_store.get_all_cards()
    other_cards = [c for c in all_cards if c.id != card.id]
    
    if card.vector_embedding:
        similarities = []
        for other_card in other_cards:
            if other_card.vector_embedding:
                sim = cosine_similarity(card.vector_embedding, other_card.vector_embedding)
                similarities.append((other_card, sim))
        
        similarities.sort(key=lambda x: x[1], reverse=True)
        
        for similar_card, similarity in similarities[:3]:
            if similarity > 0.6:
                rel_type, reason = generate_relation_reason(card.content, similar_card.content)
                memory_store.add_relation(card.id, similar_card.id, rel_type, reason)
    
    return card


@router.post("/cards/suggest", response_model=List[SemanticSuggestion])
async def get_suggestions(request: SuggestRequest):
    all_cards = memory_store.get_all_cards()
    
    if len(all_cards) == 0:
        return []
    
    query_embedding = generate_simple_embedding(request.text)
    
    similarities = []
    for card in all_cards:
        if card.vector_embedding:
            sim = cosine_similarity(query_embedding, card.vector_embedding)
            similarities.append((card, sim))
    
    similarities.sort(key=lambda x: x[1], reverse=True)
    
    suggestions = []
    for card, similarity in similarities[:5]:
        if similarity > 0.3:
            rel_type, reason = generate_relation_reason(request.text, card.content)
            suggestions.append(
                SemanticSuggestion(
                    card=card,
                    similarity=similarity,
                    reason=reason,
                )
            )
    
    return suggestions


@router.get("/cards", response_model=List[KnowledgeCard])
async def get_all_cards():
    return memory_store.get_all_cards()


@router.get("/cards/{card_id}", response_model=KnowledgeCard)
async def get_card_by_id(card_id: str):
    card = memory_store.get_card_by_id(card_id)
    if not card:
        raise HTTPException(status_code=404, detail="Card not found")
    return card


@router.get("/graph/subgraph", response_model=GraphData)
async def get_subgraph(
    center_id: Optional[str] = None,
    depth: int = 2,
):
    return memory_store.get_graph_data(center_id)
