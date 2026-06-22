from typing import Optional, List
from fastapi import APIRouter, Depends, Query
from sqlalchemy import or_, and_, desc, asc, func
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.deps import get_current_user_optional
from app.models.models import User, Snippet, Tag, SnippetTag, TeamMember
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


def can_view_snippet(snippet: Snippet, user: Optional[User], db: Session) -> bool:
    if snippet.is_deleted:
        return False
    if snippet.visibility == "public":
        return True
    if not user:
        return False
    if snippet.author_id == user.id:
        return True
    if snippet.visibility == "team" and snippet.team_id:
        membership = db.query(TeamMember).filter(
            TeamMember.team_id == snippet.team_id,
            TeamMember.user_id == user.id
        ).first()
        return membership is not None
    return False


def apply_tag_filter(query, tag_list: List[str], db: Session):
    if not tag_list:
        return query
    tag_count = len(tag_list)
    subq = (
        db.query(SnippetTag.snippet_id)
        .join(Tag)
        .filter(Tag.name.in_([t.lower() for t in tag_list]))
        .group_by(SnippetTag.snippet_id)
        .having(func.count(SnippetTag.tag_id) == tag_count)
        .subquery()
    )
    return query.filter(Snippet.id.in_(subq))


@router.get("", response_model=PaginatedSnippets)
def search_snippets(
    q: str = Query("", description="Search query"),
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    sort_by: str = Query("updated_at", regex="^(created_at|updated_at|stars_count|forks_count|views_count|relevance)$"),
    sort_order: str = Query("desc", regex="^(asc|desc)$"),
    language: Optional[str] = None,
    tag: Optional[str] = None,
    tags: Optional[List[str]] = Query(None),
    author: Optional[str] = None,
    current_user: Optional[User] = Depends(get_current_user_optional),
    db: Session = Depends(get_db),
):
    query = db.query(Snippet).join(User).filter(Snippet.is_deleted == False)

    if current_user:
        from sqlalchemy import select
        team_ids = select(TeamMember.team_id).where(TeamMember.user_id == current_user.id)
        query = query.filter(
            or_(
                Snippet.visibility == "public",
                Snippet.author_id == current_user.id,
                and_(
                    Snippet.visibility == "team",
                    Snippet.team_id.in_(team_ids),
                ),
            )
        )
    else:
        query = query.filter(Snippet.visibility == "public")

    if q:
        query = query.filter(
            or_(
                Snippet.title.ilike(f"%{q}%"),
                Snippet.description.ilike(f"%{q}%"),
                Snippet.code.ilike(f"%{q}%"),
            )
        )

    if language:
        query = query.filter(Snippet.language == language)
    if author:
        query = query.filter(User.username == author)
    if tag:
        query = query.join(SnippetTag).join(Tag).filter(Tag.name == tag.lower())
    if tags:
        query = apply_tag_filter(query, tags, db)

    total = query.count()

    if sort_by == "relevance" and q:
        from sqlalchemy import func
        title_match = func.coalesce(
            func.sum(func.case((Snippet.title.ilike(f"%{q}%"), 10), else_=0)), 0
        )
        desc_match = func.coalesce(
            func.sum(func.case((Snippet.description.ilike(f"%{q}%"), 5), else_=0)), 0
        )
        if sort_order == "desc":
            query = query.order_by(desc(title_match + desc_match), desc(Snippet.updated_at))
        else:
            query = query.order_by(asc(title_match + desc_match), asc(Snippet.updated_at))
    else:
        sort_col = getattr(Snippet, sort_by if sort_by != "relevance" else "updated_at")
        if sort_order == "desc":
            query = query.order_by(desc(sort_col))
        else:
            query = query.order_by(asc(sort_col))

    snippets = query.offset((page - 1) * page_size).limit(page_size).all()
    items = [snippet_to_list_response(s) for s in snippets if can_view_snippet(s, current_user, db)]

    return PaginatedSnippets(
        items=items,
        total=total,
        page=page,
        page_size=page_size,
        total_pages=(total + page_size - 1) // page_size,
    )


@router.get("/languages")
def get_languages(db: Session = Depends(get_db)):
    from sqlalchemy import func
    result = db.query(
        Snippet.language,
        func.count(Snippet.id).label("count")
    ).filter(Snippet.visibility == "public").group_by(Snippet.language).order_by(desc(func.count(Snippet.id))).all()
    return [{"language": r[0], "count": r[1]} for r in result]


@router.get("/tags")
def get_popular_tags(
    limit: int = Query(50, ge=1, le=200),
    db: Session = Depends(get_db),
):
    from sqlalchemy import func
    result = db.query(
        Tag.name,
        func.count(SnippetTag.id).label("count")
    ).join(SnippetTag).join(Snippet).filter(
        Snippet.visibility == "public"
    ).group_by(Tag.name).order_by(desc(func.count(SnippetTag.id))).limit(limit).all()
    return [{"name": r[0], "count": r[1]} for r in result]
