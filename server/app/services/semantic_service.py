from typing import List, Optional, Tuple
import numpy as np
from openai import AsyncOpenAI
from app.core.config import settings
from app.models.schemas import KnowledgeCard, SemanticSuggestion, RelationType


class SemanticService:
    def __init__(self):
        self.client = AsyncOpenAI(api_key=settings.OPENAI_API_KEY) if settings.OPENAI_API_KEY else None
        self.embedding_model = settings.EMBEDDING_MODEL
        self.llm_model = settings.OPENAI_MODEL

    async def get_embedding(self, text: str) -> List[float]:
        if not self.client:
            return np.random.rand(1536).tolist()
        
        response = await self.client.embeddings.create(
            input=text,
            model=self.embedding_model
        )
        return response.data[0].embedding

    def cosine_similarity(self, vec1: List[float], vec2: List[float]) -> float:
        a = np.array(vec1)
        b = np.array(vec2)
        return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))

    async def find_similar_cards(
        self,
        query_text: str,
        all_cards: List[KnowledgeCard],
        top_k: int = 5,
    ) -> List[Tuple[KnowledgeCard, float]]:
        query_embedding = await self.get_embedding(query_text)
        
        similarities = []
        for card in all_cards:
            if card.vector_embedding:
                sim = self.cosine_similarity(query_embedding, card.vector_embedding)
                similarities.append((card, sim))
        
        similarities.sort(key=lambda x: x[1], reverse=True)
        return similarities[:top_k]

    async def generate_relation_reason(
        self,
        source_content: str,
        target_content: str,
    ) -> Tuple[RelationType, str]:
        if not self.client:
            return RelationType.explains, "基于语义相似度的关联"
        
        prompt = f"""分析以下两段文本的逻辑关系：

文本 A: {source_content}

文本 B: {target_content}

请判断它们之间的关系类型，并给出简短理由。
关系类型只能是以下三种之一：
- contradicts: 两段文本观点相互矛盾
- supports: 文本 B 支持或印证文本 A 的观点
- explains: 文本 B 补充或解释文本 A 的内容

请按以下格式输出（只输出一行）：
类型,理由"""

        response = await self.client.chat.completions.create(
            model=self.llm_model,
            messages=[
                {"role": "system", "content": "你是一个知识关联分析专家，擅长判断文本之间的逻辑关系。"},
                {"role": "user", "content": prompt}
            ],
            temperature=0.3,
        )
        
        result = response.choices[0].message.content.strip()
        parts = result.split(",", 1)
        
        if len(parts) == 2:
            rel_type_str, reason = parts[0].strip(), parts[1].strip()
            try:
                rel_type = RelationType(rel_type_str)
            except ValueError:
                rel_type = RelationType.explains
        else:
            rel_type = RelationType.explains
            reason = result
        
        return rel_type, reason

    async def get_semantic_suggestions(
        self,
        query_text: str,
        all_cards: List[KnowledgeCard],
        top_k: int = 5,
    ) -> List[SemanticSuggestion]:
        similar_pairs = await self.find_similar_cards(query_text, all_cards, top_k)
        
        suggestions = []
        for card, similarity in similar_pairs:
            rel_type, reason = await self.generate_relation_reason(query_text, card.content)
            suggestions.append(
                SemanticSuggestion(
                    card=card,
                    similarity=similarity,
                    reason=reason,
                )
            )
        
        return suggestions
