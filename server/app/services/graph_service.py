from typing import Optional, List
from neo4j import AsyncGraphDatabase, AsyncDriver
from app.core.config import settings
from app.models.schemas import GraphData, GraphNode, GraphLink, KnowledgeCard, RelationType


class GraphService:
    def __init__(self):
        self.driver: Optional[AsyncDriver] = None

    async def init(self):
        if settings.NEO4J_URI and settings.NEO4J_USER and settings.NEO4J_PASSWORD:
            self.driver = AsyncGraphDatabase.driver(
                settings.NEO4J_URI,
                auth=(settings.NEO4J_USER, settings.NEO4J_PASSWORD)
            )

    async def close(self):
        if self.driver:
            await self.driver.close()

    async def add_card(self, card: KnowledgeCard):
        if not self.driver:
            return
        
        async with self.driver.session() as session:
            await session.run(
                """
                CREATE (c:KnowledgeCard {
                    id: $id,
                    content: $content,
                    source_url: $source_url,
                    tags: $tags,
                    created_at: $created_at
                })
                """,
                id=card.id,
                content=card.content[:500],
                source_url=card.source_url,
                tags=card.tags,
                created_at=card.created_at.isoformat() if card.created_at else None
            )

    async def add_relation(
        self,
        source_id: str,
        target_id: str,
        relation_type: RelationType,
        reason: Optional[str] = None
    ):
        if not self.driver:
            return
        
        rel_type_map = {
            RelationType.contradicts: "CONTRADICTS",
            RelationType.supports: "SUPPORTS",
            RelationType.explains: "EXPLAINS",
        }
        
        neo4j_rel_type = rel_type_map.get(relation_type, "RELATES_TO")
        
        async with self.driver.session() as session:
            await session.run(
                f"""
                MATCH (a:KnowledgeCard {{id: $source_id}}), (b:KnowledgeCard {{id: $target_id}})
                CREATE (a)-[r:{neo4j_rel_type} {{reason: $reason}}]->(b)
                """,
                source_id=source_id,
                target_id=target_id,
                reason=reason
            )

    async def get_subgraph(self, center_id: Optional[str] = None, depth: int = 2) -> GraphData:
        if not self.driver:
            return GraphData(nodes=[], links=[])
        
        async with self.driver.session() as session:
            if center_id:
                result = await session.run(
                    """
                    MATCH (c:KnowledgeCard {id: $center_id})
                    OPTIONAL MATCH (c)-[r*1..$depth]-(related:KnowledgeCard)
                    WITH COLLECT(DISTINCT c) + COLLECT(DISTINCT related) AS all_nodes,
                         COLLECT(DISTINCT r) AS all_rels
                    UNWIND all_nodes AS node
                    WITH COLLECT(DISTINCT node) AS unique_nodes, all_rels
                    UNWIND all_rels AS rels
                    UNWIND rels AS rel
                    RETURN unique_nodes, COLLECT(DISTINCT rel) AS unique_rels
                    """,
                    center_id=center_id,
                    depth=depth
                )
            else:
                result = await session.run(
                    """
                    MATCH (n:KnowledgeCard)
                    OPTIONAL MATCH (n)-[r]->(m:KnowledgeCard)
                    WITH COLLECT(DISTINCT n) + COLLECT(DISTINCT m) AS all_nodes,
                         COLLECT(DISTINCT r) AS all_rels
                    UNWIND all_nodes AS node
                    WITH COLLECT(DISTINCT node) AS unique_nodes, all_rels
                    RETURN unique_nodes, all_rels
                    """
                )
            
            record = await result.single()
            
            if not record:
                return GraphData(nodes=[], links=[])
            
            nodes = []
            node_map = {}
            
            for node in record["unique_nodes"]:
                node_id = node["id"]
                label = node["content"][:50] + "..." if len(node["content"]) > 50 else node["content"]
                nodes.append(GraphNode(
                    id=node_id,
                    label=label,
                    color="#4F46E5",
                    size=10.0
                ))
                node_map[node_id] = True
            
            links = []
            for rel in record.get("unique_rels", []) + record.get("all_rels", []):
                if hasattr(rel, "start_node") and hasattr(rel, "end_node"):
                    source_id = rel.start_node["id"]
                    target_id = rel.end_node["id"]
                    if source_id in node_map and target_id in node_map:
                        links.append(GraphLink(
                            source=source_id,
                            target=target_id,
                            relation=rel.type
                        ))
            
            return GraphData(nodes=nodes, links=links)


graph_service = GraphService()
