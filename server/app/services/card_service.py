from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select
from typing import List, Optional
from uuid import UUID

from app.models.models import KnowledgeCardModel, KnowledgeRelationModel
from app.models.schemas import (
    KnowledgeCard,
    KnowledgeCardCreate,
    RelatedNode,
    RelationType,
)


class KnowledgeCardService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_card(self, card_data: KnowledgeCardCreate) -> KnowledgeCard:
        db_card = KnowledgeCardModel(
            content=card_data.content,
            source_url=card_data.source_url,
            tags=card_data.tags,
        )
        self.db.add(db_card)
        await self.db.commit()
        await self.db.refresh(db_card)
        return self._model_to_schema(db_card)

    async def get_card_by_id(self, card_id: UUID) -> Optional[KnowledgeCard]:
        result = await self.db.execute(
            select(KnowledgeCardModel).where(KnowledgeCardModel.id == card_id)
        )
        db_card = result.scalar_one_or_none()
        if db_card:
            return self._model_to_schema(db_card)
        return None

    async def get_all_cards(self) -> List[KnowledgeCard]:
        result = await self.db.execute(select(KnowledgeCardModel))
        db_cards = result.scalars().all()
        return [self._model_to_schema(card) for card in db_cards]

    async def update_card_embedding(self, card_id: UUID, embedding: List[float]) -> Optional[KnowledgeCard]:
        result = await self.db.execute(
            select(KnowledgeCardModel).where(KnowledgeCardModel.id == card_id)
        )
        db_card = result.scalar_one_or_none()
        if db_card:
            db_card.vector_embedding = embedding
            await self.db.commit()
            await self.db.refresh(db_card)
            return self._model_to_schema(db_card)
        return None

    async def add_relation(
        self,
        source_id: UUID,
        target_id: UUID,
        relation_type: RelationType,
        reason: Optional[str] = None,
    ) -> None:
        relation = KnowledgeRelationModel(
            source_id=source_id,
            target_id=target_id,
            relation_type=relation_type,
            reason=reason,
        )
        self.db.add(relation)
        await self.db.commit()

    def _model_to_schema(self, db_card: KnowledgeCardModel) -> KnowledgeCard:
        related_nodes = []
        for rel in db_card.outgoing_relations:
            related_nodes.append(
                RelatedNode(
                    target_id=str(rel.target_id),
                    relation_type=rel.relation_type,
                    reason=rel.reason or "",
                )
            )
        
        return KnowledgeCard(
            id=str(db_card.id),
            content=db_card.content,
            source_url=db_card.source_url,
            tags=db_card.tags or [],
            vector_embedding=db_card.vector_embedding,
            related_nodes=related_nodes,
            created_at=db_card.created_at,
            updated_at=db_card.updated_at,
        )
