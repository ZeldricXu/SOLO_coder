from typing import List, Optional
from fastapi import APIRouter, Depends, HTTPException, status, Query
from sqlalchemy import or_, desc, asc, and_, func
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.deps import get_current_user, get_current_user_optional
from app.core.config import settings
from app.core.highlight import highlight_code
from app.core import search_fts
from app.models.models import User, Snippet, Tag, SnippetTag, Favorite, Star, Team, TeamMember, TagAlias
from app.schemas.schemas import (
    SnippetCreate,
    SnippetUpdate,
    SnippetResponse,
    SnippetListResponse,
    PaginatedSnippets,
)

router = APIRouter(prefix="/api/snippets", tags=["snippets"])

MAX_SNIPPET_SIZE_BYTES = settings.max_snippet_size_kb * 1024


def get_or_create_tags(db: Session, tag_names: List[str]) -> List[Tag]:
    tags = []
    for name in tag_names:
        name = name.strip().lower()
        if not name:
            continue
        alias_row = db.query(TagAlias).filter(TagAlias.alias == name).first()
        if alias_row:
            canonical = db.query(Tag).filter(Tag.id == alias_row.canonical_tag_id).first()
            if canonical:
                tags.append(canonical)
                continue
        tag = db.query(Tag).filter(Tag.name == name).first()
        if not tag:
            tag = Tag(name=name)
            db.add(tag)
            db.flush()
        tags.append(tag)
    return tags


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


def snippet_to_detail_response(db: Session, snippet: Snippet, current_user: Optional[User] = None) -> SnippetResponse:
    tag_names = [st.tag.name for st in snippet.tags]
    is_favorited = False
    is_starred = False
    if current_user:
        is_favorited = db.query(Favorite).filter(
            Favorite.user_id == current_user.id,
            Favorite.snippet_id == snippet.id
        ).first() is not None
        is_starred = db.query(Star).filter(
            Star.user_id == current_user.id,
            Star.snippet_id == snippet.id
        ).first() is not None

    parent_title = None
    parent_author_username = None
    parent_updated_at = None
    parent_has_updates = False

    if snippet.parent:
        parent_title = snippet.parent.title
        parent_author_username = snippet.parent.author.username if snippet.parent.author else ""
        parent_updated_at = snippet.parent.updated_at
        if snippet.parent.updated_at > snippet.updated_at:
            parent_has_updates = True

    return SnippetResponse(
        id=snippet.id,
        title=snippet.title,
        description=snippet.description,
        code=snippet.code,
        rendered_html=snippet.rendered_html,
        language=snippet.language,
        visibility=snippet.visibility,
        stars_count=snippet.stars_count,
        forks_count=snippet.forks_count,
        views_count=snippet.views_count,
        is_deleted=snippet.is_deleted,
        created_at=snippet.created_at,
        updated_at=snippet.updated_at,
        author_id=snippet.author_id,
        author_username=snippet.author.username if snippet.author else "",
        author_avatar_url=snippet.author.avatar_url if snippet.author else None,
        team_id=snippet.team_id,
        team_name=snippet.team.name if snippet.team else None,
        parent_id=snippet.parent_id,
        parent_title=parent_title,
        parent_author_username=parent_author_username,
        parent_updated_at=parent_updated_at,
        parent_has_updates=parent_has_updates,
        tags=tag_names,
        is_favorited=is_favorited,
        is_starred=is_starred,
    )


def can_view_snippet(db: Session, snippet: Snippet, user: Optional[User]) -> bool:
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
def list_snippets(
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    sort_by: str = Query("updated_at", regex="^(created_at|updated_at|stars_count|forks_count|views_count)$"),
    sort_order: str = Query("desc", regex="^(asc|desc)$"),
    language: Optional[str] = None,
    tag: Optional[str] = None,
    tags: Optional[List[str]] = Query(None),
    author: Optional[str] = None,
    visibility: Optional[str] = None,
    current_user: Optional[User] = Depends(get_current_user_optional),
    db: Session = Depends(get_db),
):
    from sqlalchemy import func

    query = db.query(Snippet).filter(Snippet.is_deleted == False)

    if visibility and visibility == "public":
        query = query.filter(Snippet.visibility == "public")
    elif current_user:
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
        if visibility and visibility == "private":
            query = query.filter(Snippet.visibility == "private", Snippet.author_id == current_user.id)
        elif visibility and visibility == "team":
            query = query.filter(Snippet.visibility == "team", Snippet.team_id.in_(team_ids))
    else:
        query = query.filter(Snippet.visibility == "public")

    if language:
        query = query.filter(Snippet.language == language)
    if author:
        query = query.join(User).filter(User.username == author)
    if tag:
        query = query.join(SnippetTag).join(Tag).filter(Tag.name == tag.lower())
    if tags:
        query = apply_tag_filter(query, tags, db)

    total = query.count()

    sort_col = getattr(Snippet, sort_by)
    if sort_order == "desc":
        query = query.order_by(desc(sort_col))
    else:
        query = query.order_by(asc(sort_col))

    snippets = query.offset((page - 1) * page_size).limit(page_size).all()
    items = [snippet_to_list_response(s) for s in snippets]

    return PaginatedSnippets(
        items=items,
        total=total,
        page=page,
        page_size=page_size,
        total_pages=(total + page_size - 1) // page_size,
    )


@router.get("/{snippet_id}", response_model=SnippetResponse)
def get_snippet(
    snippet_id: int,
    current_user: Optional[User] = Depends(get_current_user_optional),
    db: Session = Depends(get_db),
):
    snippet = db.query(Snippet).filter(Snippet.id == snippet_id).first()
    if not snippet:
        raise HTTPException(status_code=404, detail="Snippet not found")
    if snippet.is_deleted:
        raise HTTPException(status_code=404, detail="Snippet not found")
    if not can_view_snippet(db, snippet, current_user):
        raise HTTPException(status_code=403, detail="You don't have permission to view this snippet")

    snippet.views_count += 1
    db.commit()
    db.refresh(snippet)

    return snippet_to_detail_response(db, snippet, current_user)


@router.post("", response_model=SnippetResponse, status_code=status.HTTP_201_CREATED)
def create_snippet(
    snippet_data: SnippetCreate,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    if snippet_data.visibility not in ["public", "private", "team"]:
        raise HTTPException(status_code=400, detail="Invalid visibility type")

    code_size = len(snippet_data.code.encode("utf-8"))
    if code_size > MAX_SNIPPET_SIZE_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"Snippet code too large. Maximum size is {settings.max_snippet_size_kb}KB, got {code_size // 1024}KB"
        )

    if snippet_data.visibility == "team" and snippet_data.team_id:
        team = db.query(Team).filter(Team.id == snippet_data.team_id).first()
        if not team:
            raise HTTPException(status_code=404, detail="Team not found")
        membership = db.query(TeamMember).filter(
            TeamMember.team_id == snippet_data.team_id,
            TeamMember.user_id == current_user.id
        ).first()
        if not membership:
            raise HTTPException(status_code=403, detail="You are not a member of this team")

    parent = None
    if snippet_data.parent_id:
        parent = db.query(Snippet).filter(Snippet.id == snippet_data.parent_id).first()
        if not parent or parent.is_deleted:
            raise HTTPException(status_code=404, detail="Parent snippet not found")
        if not can_view_snippet(db, parent, current_user):
            raise HTTPException(status_code=403, detail="You don't have permission to fork this snippet")

    snippet = Snippet(
        title=snippet_data.title,
        description=snippet_data.description,
        code=snippet_data.code,
        rendered_html=highlight_code(snippet_data.code, snippet_data.language),
        language=snippet_data.language,
        visibility=snippet_data.visibility,
        author_id=current_user.id,
        team_id=snippet_data.team_id if snippet_data.visibility == "team" else None,
        parent_id=snippet_data.parent_id,
    )
    db.add(snippet)
    db.flush()

    tags = get_or_create_tags(db, snippet_data.tags)
    for tag in tags:
        st = SnippetTag(snippet_id=snippet.id, tag_id=tag.id)
        db.add(st)

    if parent:
        parent.forks_count += 1

    db.commit()
    db.refresh(snippet)

    tags_str = " ".join(sorted({t.name for t in tags}))
    search_fts.upsert_fts_entry(
        db, snippet.id, snippet.title, snippet.description or "",
        snippet.code, tags_str
    )
    db.commit()

    return snippet_to_detail_response(db, snippet, current_user)


@router.put("/{snippet_id}", response_model=SnippetResponse)
def update_snippet(
    snippet_id: int,
    snippet_data: SnippetUpdate,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    snippet = db.query(Snippet).filter(Snippet.id == snippet_id).first()
    if not snippet or snippet.is_deleted:
        raise HTTPException(status_code=404, detail="Snippet not found")
    if snippet.author_id != current_user.id:
        raise HTTPException(status_code=403, detail="You don't have permission to edit this snippet")

    update_data = snippet_data.model_dump(exclude_unset=True)

    if "code" in update_data:
        code_size = len(update_data["code"].encode("utf-8"))
        if code_size > MAX_SNIPPET_SIZE_BYTES:
            raise HTTPException(
                status_code=413,
                detail=f"Snippet code too large. Maximum size is {settings.max_snippet_size_kb}KB, got {code_size // 1024}KB"
            )

    if "visibility" in update_data and update_data["visibility"] not in ["public", "private", "team"]:
        raise HTTPException(status_code=400, detail="Invalid visibility type")

    if "visibility" in update_data and update_data["visibility"] == "team":
        team_id = update_data.get("team_id") or snippet.team_id
        if not team_id:
            raise HTTPException(status_code=400, detail="Team ID is required for team visibility")
        membership = db.query(TeamMember).filter(
            TeamMember.team_id == team_id,
            TeamMember.user_id == current_user.id
        ).first()
        if not membership:
            raise HTTPException(status_code=403, detail="You are not a member of this team")
    elif "visibility" in update_data and update_data["visibility"] != "team":
        if "team_id" not in update_data:
            update_data["team_id"] = None

    code_updated = False
    lang_updated = False
    tags_updated = False

    for key, value in update_data.items():
        if key == "code":
            code_updated = True
        if key == "language":
            lang_updated = True
        if key != "tags":
            setattr(snippet, key, value)

    if code_updated or lang_updated:
        snippet.rendered_html = highlight_code(snippet.code, snippet.language)

    if "tags" in update_data and update_data["tags"] is not None:
        tags_updated = True
        db.query(SnippetTag).filter(SnippetTag.snippet_id == snippet.id).delete()
        tags = get_or_create_tags(db, update_data["tags"])
        for tag in tags:
            st = SnippetTag(snippet_id=snippet.id, tag_id=tag.id)
            db.add(st)

    db.commit()
    db.refresh(snippet)

    if code_updated or lang_updated or tags_updated or "title" in update_data or "description" in update_data:
        tag_names = [st.tag.name for st in snippet.tags]
        tags_str = " ".join(sorted(set(tag_names)))
        search_fts.upsert_fts_entry(
            db, snippet.id, snippet.title, snippet.description or "",
            snippet.code, tags_str
        )
        db.commit()

    return snippet_to_detail_response(db, snippet, current_user)


@router.delete("/{snippet_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_snippet(
    snippet_id: int,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    snippet = db.query(Snippet).filter(Snippet.id == snippet_id).first()
    if not snippet or snippet.is_deleted:
        raise HTTPException(status_code=404, detail="Snippet not found")
    if snippet.author_id != current_user.id:
        raise HTTPException(status_code=403, detail="You don't have permission to delete this snippet")

    if snippet.forks_count > 0:
        snippet.is_deleted = True
        if snippet.parent_id:
            parent = db.query(Snippet).filter(Snippet.id == snippet.parent_id).first()
            if parent and parent.forks_count > 0:
                parent.forks_count -= 1
        db.commit()
    else:
        if snippet.parent_id:
            parent = db.query(Snippet).filter(Snippet.id == snippet.parent_id).first()
            if parent and parent.forks_count > 0:
                parent.forks_count -= 1
        db.delete(snippet)
        db.commit()

    search_fts.delete_fts_entry(db, snippet_id)
    db.commit()

    return None


@router.post("/{snippet_id}/fork", response_model=SnippetResponse, status_code=status.HTTP_201_CREATED)
def fork_snippet(
    snippet_id: int,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    original = db.query(Snippet).filter(Snippet.id == snippet_id).first()
    if not original or original.is_deleted:
        raise HTTPException(status_code=404, detail="Snippet not found")
    if not can_view_snippet(db, original, current_user):
        raise HTTPException(status_code=403, detail="You don't have permission to fork this snippet")

    forked = Snippet(
        title=original.title + " (fork)",
        description=original.description,
        code=original.code,
        rendered_html=original.rendered_html,
        language=original.language,
        visibility="private",
        author_id=current_user.id,
        parent_id=original.id,
    )
    db.add(forked)
    db.flush()

    tag_names_list = []
    for st in original.tags:
        new_st = SnippetTag(snippet_id=forked.id, tag_id=st.tag_id)
        db.add(new_st)
        if st.tag:
            tag_names_list.append(st.tag.name)

    original.forks_count += 1

    db.commit()
    db.refresh(forked)

    tags_str = " ".join(sorted(set(tag_names_list)))
    search_fts.upsert_fts_entry(
        db, forked.id, forked.title, forked.description or "",
        forked.code, tags_str
    )
    db.commit()

    return snippet_to_detail_response(db, forked, current_user)


@router.post("/{snippet_id}/star", status_code=status.HTTP_200_OK)
def toggle_star(
    snippet_id: int,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    snippet = db.query(Snippet).filter(Snippet.id == snippet_id).first()
    if not snippet or snippet.is_deleted:
        raise HTTPException(status_code=404, detail="Snippet not found")
    if not can_view_snippet(db, snippet, current_user):
        raise HTTPException(status_code=403, detail="You don't have permission to star this snippet")

    existing = db.query(Star).filter(
        Star.user_id == current_user.id,
        Star.snippet_id == snippet_id
    ).first()

    if existing:
        db.delete(existing)
        snippet.stars_count = max(0, snippet.stars_count - 1)
        starred = False
    else:
        star = Star(user_id=current_user.id, snippet_id=snippet_id)
        db.add(star)
        snippet.stars_count += 1
        starred = True

    db.commit()
    return {"starred": starred, "stars_count": snippet.stars_count}


@router.post("/{snippet_id}/favorite", status_code=status.HTTP_200_OK)
def toggle_favorite(
    snippet_id: int,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    snippet = db.query(Snippet).filter(Snippet.id == snippet_id).first()
    if not snippet or snippet.is_deleted:
        raise HTTPException(status_code=404, detail="Snippet not found")
    if not can_view_snippet(db, snippet, current_user):
        raise HTTPException(status_code=403, detail="You don't have permission to favorite this snippet")

    existing = db.query(Favorite).filter(
        Favorite.user_id == current_user.id,
        Favorite.snippet_id == snippet_id
    ).first()

    if existing:
        db.delete(existing)
        favorited = False
    else:
        fav = Favorite(user_id=current_user.id, snippet_id=snippet_id)
        db.add(fav)
        favorited = True

    db.commit()
    return {"favorited": favorited}


@router.get("/{snippet_id}/forks", response_model=List[SnippetListResponse])
def get_forks(
    snippet_id: int,
    current_user: Optional[User] = Depends(get_current_user_optional),
    db: Session = Depends(get_db),
):
    snippet = db.query(Snippet).filter(Snippet.id == snippet_id).first()
    if not snippet or snippet.is_deleted:
        raise HTTPException(status_code=404, detail="Snippet not found")
    if not can_view_snippet(db, snippet, current_user):
        raise HTTPException(status_code=403, detail="You don't have permission to view this snippet")

    forks = db.query(Snippet).filter(
        Snippet.parent_id == snippet_id,
        Snippet.is_deleted == False
    ).order_by(desc(Snippet.created_at)).all()
    visible_forks = [f for f in forks if can_view_snippet(db, f, current_user)]
    return [snippet_to_list_response(f) for f in visible_forks]
