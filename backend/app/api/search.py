from typing import Optional, List
from fastapi import APIRouter, Depends, Query, HTTPException
from sqlalchemy import or_, and_, desc, asc, func
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.deps import get_current_user_optional, get_current_user
from app.core import search_fts
from app.models.models import User, Snippet, Tag, SnippetTag, TeamMember, TagAlias
from app.schemas.schemas import PaginatedSnippets, SnippetListResponse

router = APIRouter(prefix="/api/search", tags=["search"])


def snippet_to_list_response(snippet: Snippet) -> SnippetListResponse:
    tag_names = [st.tag.name for st in snippet.tags]
    return SnippetListResponse(
        id=snippet.id,
        title=snippet.title,
        description=snippet.description,
        language=snippet.language,
        visibility=snippet.visibility,
        stars_count=snippet.stars_count,
        forks_count=snippet.forks_count,
        views_count=snippet.views_count,
        created_at=snippet.created_at,
        updated_at=snippet.updated_at,
        author_id=snippet.author_id,
        author_username=snippet.author.username if snippet.author else "",
        team_name=snippet.team.name if snippet.team else None,
        tags=tag_names,
    )


@router.get("", response_model=PaginatedSnippets)
def search_snippets(
    q: str = Query("", description="Search query"),
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    sort_by: str = Query("relevance", regex="^(created_at|updated_at|stars_count|forks_count|views_count|relevance)$"),
    sort_order: str = Query("desc", regex="^(asc|desc)$"),
    language: Optional[str] = None,
    tag: Optional[str] = None,
    tags: Optional[List[str]] = Query(None),
    author: Optional[str] = None,
    current_user: Optional[User] = Depends(get_current_user_optional),
    db: Session = Depends(get_db),
):
    user_id = current_user.id if current_user else None

    expanded_tags = []
    if tag:
        expanded_tags = search_fts.expand_tag_aliases(db, [tag])
    if tags:
        all_expanded = search_fts.expand_tag_aliases(db, tags)
        expanded_tags = list(set(expanded_tags + all_expanded))

    if not expanded_tags and tag:
        expanded_tags = [tag.lower()]

    results, total = search_fts.search_fts(
        db,
        query=q,
        user_id=user_id,
        language=language,
        author_username=author,
        tag_filters=expanded_tags if expanded_tags else None,
        sort_by=sort_by,
        sort_order=sort_order,
        page=page,
        page_size=page_size,
    )

    snippet_ids = [r[0] for r in results]
    snippets_by_id = {}
    if snippet_ids:
        rows = db.query(Snippet).filter(Snippet.id.in_(snippet_ids)).all()
        for s in rows:
            snippets_by_id[s.id] = s

    ordered = []
    for sid, score in results:
        s = snippets_by_id.get(sid)
        if s and not s.is_deleted:
            ordered.append(s)

    items = [snippet_to_list_response(s) for s in ordered]

    return PaginatedSnippets(
        items=items,
        total=total,
        page=page,
        page_size=page_size,
        total_pages=(total + page_size - 1) // page_size if total > 0 else 0,
    )


@router.get("/languages")
def get_languages(db: Session = Depends(get_db)):
    result = db.query(
        Snippet.language,
        func.count(Snippet.id).label("count")
    ).filter(
        Snippet.visibility == "public",
        Snippet.is_deleted == False
    ).group_by(Snippet.language).order_by(desc(func.count(Snippet.id))).all()
    return [{"language": r[0], "count": r[1]} for r in result]


@router.get("/tags")
def get_popular_tags(
    limit: int = Query(50, ge=1, le=200),
    db: Session = Depends(get_db),
):
    result = db.query(
        Tag.name,
        func.count(SnippetTag.id).label("count")
    ).join(SnippetTag).join(Snippet).filter(
        Snippet.visibility == "public",
        Snippet.is_deleted == False
    ).group_by(Tag.name).order_by(desc(func.count(SnippetTag.id))).limit(limit).all()
    return [{"name": r[0], "count": r[1]} for r in result]


@router.get("/tags/suggest")
def suggest_tag(
    q: str = Query("", description="Tag name prefix to suggest"),
    db: Session = Depends(get_db),
):
    from app.models.models import TagAlias
    q_lower = q.strip().lower()
    if not q_lower:
        return []

    exact = db.query(Tag).filter(Tag.name == q_lower).first()
    if exact:
        aliases = [a.alias for a in exact.aliases]
        return [{"name": exact.name, "is_alias": False, "aliases": aliases}]

    alias_row = db.query(TagAlias).filter(TagAlias.alias == q_lower).first()
    if alias_row:
        canonical = db.query(Tag).filter(Tag.id == alias_row.canonical_tag_id).first()
        if canonical:
            all_aliases = [a.alias for a in canonical.aliases]
            return [{"name": canonical.name, "is_alias": True, "aliases": all_aliases}]

    similar = db.query(Tag).filter(Tag.name.like(f"{q_lower}%")).limit(5).all()
    results = []
    for t in similar:
        aliases = [a.alias for a in t.aliases]
        results.append({"name": t.name, "is_alias": False, "aliases": aliases})

    similar_aliases = db.query(TagAlias).filter(TagAlias.alias.like(f"{q_lower}%")).limit(5).all()
    for sa in similar_aliases:
        canonical = db.query(Tag).filter(Tag.id == sa.canonical_tag_id).first()
        if canonical:
            already = any(r["name"] == canonical.name for r in results)
            if not already:
                all_aliases = [a.alias for a in canonical.aliases]
                results.append({"name": canonical.name, "is_alias": True, "aliases": all_aliases})

    return results[:10]


@router.post("/tags/aliases", status_code=201)
def create_tag_alias(
    alias: str = Query(..., description="Alias name"),
    canonical_tag: str = Query(..., description="Canonical tag name"),
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    alias_lower = alias.strip().lower()
    canonical_lower = canonical_tag.strip().lower()

    if alias_lower == canonical_lower:
        raise HTTPException(status_code=400, detail="Alias cannot be the same as canonical tag")

    canonical = db.query(Tag).filter(Tag.name == canonical_lower).first()
    if not canonical:
        canonical = Tag(name=canonical_lower)
        db.add(canonical)
        db.flush()

    existing_alias = db.query(TagAlias).filter(TagAlias.alias == alias_lower).first()
    if existing_alias:
        raise HTTPException(status_code=400, detail=f"Alias '{alias_lower}' already exists")

    existing_tag = db.query(Tag).filter(Tag.name == alias_lower).first()
    if existing_tag:
        existing_tag_name = existing_tag.name
        snippet_tags = db.query(SnippetTag).filter(SnippetTag.tag_id == existing_tag.id).all()
        for st in snippet_tags:
            already_linked = db.query(SnippetTag).filter(
                SnippetTag.snippet_id == st.snippet_id,
                SnippetTag.tag_id == canonical.id
            ).first()
            if not already_linked:
                st.tag_id = canonical.id
            else:
                db.delete(st)
        if not canonical.aliases:
            pass
        db.delete(existing_tag)
        db.flush()

    new_alias = TagAlias(alias=alias_lower, canonical_tag_id=canonical.id)
    db.add(new_alias)
    db.commit()

    return {"alias": alias_lower, "canonical_tag": canonical_lower}


@router.get("/tags/aliases")
def list_tag_aliases(
    db: Session = Depends(get_db),
):
    aliases = db.query(TagAlias).all()
    result = []
    for a in aliases:
        canonical = db.query(Tag).filter(Tag.id == a.canonical_tag_id).first()
        result.append({
            "id": a.id,
            "alias": a.alias,
            "canonical_tag": canonical.name if canonical else "",
            "created_at": a.created_at,
        })
    return result


@router.delete("/tags/aliases/{alias_id}", status_code=204)
def delete_tag_alias(
    alias_id: int,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    alias_row = db.query(TagAlias).filter(TagAlias.id == alias_id).first()
    if not alias_row:
        raise HTTPException(status_code=404, detail="Tag alias not found")
    db.delete(alias_row)
    db.commit()
    return None
