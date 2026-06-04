from fastapi import APIRouter, Depends, HTTPException, status, Query, Body
from typing import Optional, List, Dict, Any

from recommendation_engine.models.schemas import ContentItem, ContentEmbedding
from recommendation_engine.api.dependencies import (
    get_content_index_svc,
    verify_api_key,
)
from recommendation_engine.content_embedding_index import ContentEmbeddingIndex

router = APIRouter(prefix="/api/v1/content-index", tags=["content-index"], dependencies=[Depends(verify_api_key)])


@router.post("/items", status_code=status.HTTP_201_CREATED)
async def add_content_item(
    item: ContentItem,
    service: ContentEmbeddingIndex = Depends(get_content_index_svc),
):
    try:
        success = await service.add_content_item(item)
        if not success:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail="Failed to add content item",
            )
        return {"status": "created", "content_id": item.content_id}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to add content item: {str(e)}",
        )


@router.post("/items/batch", status_code=status.HTTP_202_ACCEPTED)
async def add_content_items_batch(
    items: List[ContentItem],
    service: ContentEmbeddingIndex = Depends(get_content_index_svc),
):
    try:
        success_count = 0
        for item in items:
            if await service.add_content_item(item):
                success_count += 1
        return {"status": "accepted", "success_count": success_count, "total": len(items)}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to add content items: {str(e)}",
        )


@router.post("/embeddings", status_code=status.HTTP_202_ACCEPTED)
async def add_content_embedding(
    embedding: ContentEmbedding,
    service: ContentEmbeddingIndex = Depends(get_content_index_svc),
):
    try:
        success = await service.add_embedding(embedding)
        if not success:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail="Failed to add content embedding",
            )
        return {"status": "accepted", "content_id": embedding.content_id}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to add content embedding: {str(e)}",
        )


@router.post("/embeddings/batch", status_code=status.HTTP_202_ACCEPTED)
async def add_content_embeddings_batch(
    embeddings: List[ContentEmbedding],
    service: ContentEmbeddingIndex = Depends(get_content_index_svc),
):
    try:
        success_count = 0
        for embedding in embeddings:
            if await service.add_embedding(embedding):
                success_count += 1
        return {"status": "accepted", "success_count": success_count, "total": len(embeddings)}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to add content embeddings: {str(e)}",
        )


@router.post("/search")
async def search_similar(
    query_vector: List[float] = Body(..., embed=True),
    top_k: int = Query(50, ge=1, le=500),
    service: ContentEmbeddingIndex = Depends(get_content_index_svc),
):
    try:
        results = await service.search(query_vector, top_k)
        return {
            "results": [
                {"content_id": cid, "score": float(score)}
                for cid, score in results
            ]
        }
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to search: {str(e)}",
        )


@router.post("/search/{content_id}")
async def find_similar_items(
    content_id: str,
    top_k: int = Query(50, ge=1, le=500),
    service: ContentEmbeddingIndex = Depends(get_content_index_svc),
):
    try:
        results = await service.find_similar(content_id, top_k)
        return {
            "content_id": content_id,
            "results": [
                {"content_id": cid, "score": float(score)}
                for cid, score in results
            ]
        }
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to find similar items: {str(e)}",
        )


@router.post("/flush", status_code=status.HTTP_200_OK)
async def flush_pending_updates(
    service: ContentEmbeddingIndex = Depends(get_content_index_svc),
):
    try:
        count = await service.flush_pending_updates()
        return {"status": "success", "flushed_count": count}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to flush updates: {str(e)}",
        )


@router.post("/rebuild", status_code=status.HTTP_202_ACCEPTED)
async def trigger_full_rebuild(
    service: ContentEmbeddingIndex = Depends(get_content_index_svc),
):
    try:
        await service.trigger_full_rebuild()
        return {"status": "accepted", "message": "Full rebuild triggered"}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to trigger rebuild: {str(e)}",
        )


@router.get("/stats")
async def get_index_stats(
    service: ContentEmbeddingIndex = Depends(get_content_index_svc),
):
    try:
        stats = await service.get_stats()
        return stats
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to get stats: {str(e)}",
        )
