from typing import Dict, List, Optional
from datetime import datetime
import uuid
import random
import math
from app.models.schemas import (
    KnowledgeCard,
    KnowledgeCardCreate,
    RelatedNode,
    RelationType,
    GraphData,
    GraphNode,
    GraphLink,
)


def random_vector(dim: int = 128) -> List[float]:
    vec = [random.random() for _ in range(dim)]
    norm = math.sqrt(sum(v * v for v in vec))
    return [v / norm for v in vec]


class MemoryStore:
    _instance: Optional["MemoryStore"] = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance
    
    def __init__(self):
        if self._initialized:
            return
        self._cards: Dict[str, KnowledgeCard] = {}
        self._relations: List[Dict] = []
        self._initialized = False
        self._init_sample_data()
    
    def _init_sample_data(self):
        if len(self._cards) > 0:
            return
        
        sample_cards = [
            {
                "content": "机器学习是人工智能的一个分支，它使计算机能够从数据中学习并改进其性能，而无需进行显式编程。监督学习、无监督学习和强化学习是三种主要的机器学习范式。",
                "tags": ["机器学习", "人工智能", "技术"],
                "source_url": None,
            },
            {
                "content": "深度学习是基于人工神经网络和表示学习的机器学习方法。学习可以是有监督的、半监督的或无监督的。深度学习架构如深度神经网络、卷积神经网络和递归神经网络已被应用于计算机视觉、语音识别、自然语言处理等领域。",
                "tags": ["深度学习", "神经网络", "AI"],
                "source_url": None,
            },
            {
                "content": "自然语言处理（NLP）是计算机科学和语言学的一个子领域，涉及计算机与人类语言之间的交互。它包括编程计算机来处理和分析大量的自然语言数据。NLP 的目标是让计算机能够理解、解释和生成人类语言。",
                "tags": ["NLP", "自然语言处理", "人工智能"],
                "source_url": None,
            },
            {
                "content": "向量嵌入是将文本、图像或其他数据类型转换为高维向量表示的技术。这些向量捕获了数据的语义含义，使得相似的数据点在向量空间中彼此接近。余弦相似度是衡量两个向量嵌入之间相似性的常用方法。",
                "tags": ["嵌入", "向量", "语义搜索"],
                "source_url": None,
            },
            {
                "content": "知识图谱是一种使用图结构来表示知识的方法，其中节点表示实体，边表示实体之间的关系。知识图谱可以用于问答系统、推荐系统和语义搜索等应用。",
                "tags": ["知识图谱", "图数据库", "语义网络"],
                "source_url": None,
            },
        ]
        
        for card_data in sample_cards:
            card = KnowledgeCard(
                id=str(uuid.uuid4()),
                content=card_data["content"],
                tags=card_data["tags"],
                source_url=card_data["source_url"],
                vector_embedding=random_vector(128),
                related_nodes=[],
                created_at=datetime.utcnow(),
                updated_at=datetime.utcnow(),
            )
            self._cards[card.id] = card
        
        self._initialized = True
    
    def get_all_cards(self) -> List[KnowledgeCard]:
        return list(self._cards.values())
    
    def get_card_by_id(self, card_id: str) -> Optional[KnowledgeCard]:
        return self._cards.get(card_id)
    
    def create_card(self, card_create: KnowledgeCardCreate) -> KnowledgeCard:
        card = KnowledgeCard(
            id=str(uuid.uuid4()),
            content=card_create.content,
            tags=card_create.tags,
            source_url=card_create.source_url,
            vector_embedding=random_vector(128),
            related_nodes=[],
            created_at=datetime.utcnow(),
            updated_at=datetime.utcnow(),
        )
        self._cards[card.id] = card
        return card
    
    def update_card_embedding(self, card_id: str, embedding: List[float]) -> Optional[KnowledgeCard]:
        card = self._cards.get(card_id)
        if card:
            card.vector_embedding = embedding
            card.updated_at = datetime.utcnow()
            return card
        return None
    
    def add_relation(
        self,
        source_id: str,
        target_id: str,
        relation_type: RelationType,
        reason: Optional[str] = None,
    ) -> Optional[KnowledgeCard]:
        source_card = self._cards.get(source_id)
        target_card = self._cards.get(target_id)
        
        if not source_card or not target_card:
            return None
        
        related_node = RelatedNode(
            target_id=target_id,
            relation_type=relation_type,
            reason=reason or "",
        )
        source_card.related_nodes.append(related_node)
        source_card.updated_at = datetime.utcnow()
        
        self._relations.append({
            "source_id": source_id,
            "target_id": target_id,
            "relation_type": relation_type,
            "reason": reason,
        })
        
        return source_card
    
    def get_graph_data(self, center_id: Optional[str] = None) -> GraphData:
        all_cards = list(self._cards.values())
        nodes = []
        links = []
        
        if center_id:
            center_card = self._cards.get(center_id)
            if not center_card:
                return GraphData(nodes=[], links=[])
            
            related_ids = set([center_id])
            for rel in center_card.related_nodes:
                related_ids.add(rel.target_id)
            
            for rel in self._relations:
                if rel["source_id"] == center_id or rel["target_id"] == center_id:
                    related_ids.add(rel["source_id"])
                    related_ids.add(rel["target_id"])
            
            filtered_cards = [c for c in all_cards if c.id in related_ids]
        else:
            filtered_cards = all_cards
        
        card_ids = set([c.id for c in filtered_cards])
        
        for card in filtered_cards:
            label = card.content[:40] + "..." if len(card.content) > 40 else card.content
            nodes.append(GraphNode(
                id=card.id,
                label=label,
                color="#818CF8",
                size=8.0,
            ))
        
        for rel in self._relations:
            if rel["source_id"] in card_ids and rel["target_id"] in card_ids:
                rel_type_map = {
                    RelationType.supports: "SUPPORTS",
                    RelationType.contradicts: "CONTRADICTS",
                    RelationType.explains: "EXPLAINS",
                }
                links.append(GraphLink(
                    source=rel["source_id"],
                    target=rel["target_id"],
                    relation=rel_type_map.get(rel["relation_type"], "RELATES_TO"),
                ))
        
        return GraphData(nodes=nodes, links=links)


memory_store = MemoryStore()
