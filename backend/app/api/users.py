from typing import List, Optional
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from sqlalchemy import desc

from app.core.database import get_db
from app.core.deps import get_current_user, get_current_user_optional
from app.models.models import User, Snippet, Favorite, Star
from app.schemas.schemas import UserResponse, UserProfile, SnippetListResponse

router = APIRouter(prefix="/api/users", tags=["users"])


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


@router.get("/me", response_model=UserResponse)
def get_current_user_info(current_user: User = Depends(get_current_user)):
    return current_user


@router.get("/{username}", response_model=UserProfile)
def get_user_profile(
    username: str,
    current_user: Optional[User] = Depends(get_current_user_optional),
    db: Session = Depends(get_db),
):
    user = db.query(User).filter(User.username == username).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    snippets_query = db.query(Snippet).filter(Snippet.author_id == user.id)
    if current_user and current_user.id == user.id:
        pass
    else:
        snippets_query = snippets_query.filter(Snippet.visibility == "public")

    snippets_count = snippets_query.count()
    forks_count = db.query(Snippet).filter(Snippet.author_id == user.id, Snippet.parent_id.isnot(None)).count()

    return UserProfile(
        id=user.id,
        username=user.username,
        email=user.email,
        avatar_url=user.avatar_url,
        bio=user.bio,
        created_at=user.created_at,
        snippets_count=snippets_count,
        forks_count=forks_count,
    )


@router.get("/{username}/snippets", response_model=List[SnippetListResponse])
def get_user_snippets(
    username: str,
    sort_by: str = Query("updated_at", regex="^(created_at|updated_at|stars_count|forks_count|views_count)$"),
    sort_order: str = Query("desc", regex="^(asc|desc)$"),
    current_user: Optional[User] = Depends(get_current_user_optional),
    db: Session = Depends(get_db),
):
    user = db.query(User).filter(User.username == username).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    query = db.query(Snippet).filter(Snippet.author_id == user.id)
    if current_user and current_user.id == user.id:
        pass
    else:
        query = query.filter(Snippet.visibility == "public")

    sort_col = getattr(Snippet, sort_by)
    if sort_order == "desc":
        query = query.order_by(desc(sort_col))
    else:
        query = query.order_by(asc(sort_col))

    snippets = query.all()
    return [snippet_to_list_response(s) for s in snippets]


@router.get("/{username}/favorites", response_model=List[SnippetListResponse])
def get_user_favorites(
    username: str,
    current_user: Optional[User] = Depends(get_current_user_optional),
    db: Session = Depends(get_db),
):
    user = db.query(User).filter(User.username == username).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    if current_user and current_user.id == user.id:
        favorites = db.query(Favorite).filter(Favorite.user_id == user.id).order_by(desc(Favorite.created_at)).all()
        snippets = []
        for fav in favorites:
            snippet = db.query(Snippet).filter(Snippet.id == fav.snippet_id).first()
            if snippet:
                snippets.append(snippet)
    else:
        favorites = db.query(Favorite).join(Snippet).filter(
            Favorite.user_id == user.id,
            Snippet.visibility == "public"
        ).order_by(desc(Favorite.created_at)).all()
        snippets = []
        for fav in favorites:
            snippet = db.query(Snippet).filter(Snippet.id == fav.snippet_id).first()
            if snippet:
                snippets.append(snippet)

    return [snippet_to_list_response(s) for s in snippets]


@router.get("/{username}/stars", response_model=List[SnippetListResponse])
def get_user_stars(
    username: str,
    current_user: Optional[User] = Depends(get_current_user_optional),
    db: Session = Depends(get_db),
):
    user = db.query(User).filter(User.username == username).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    if current_user and current_user.id == user.id:
        stars = db.query(Star).filter(Star.user_id == user.id).order_by(desc(Star.created_at)).all()
        snippets = []
        for st in stars:
            snippet = db.query(Snippet).filter(Snippet.id == st.snippet_id).first()
            if snippet:
                snippets.append(snippet)
    else:
        stars = db.query(Star).join(Snippet).filter(
            Star.user_id == user.id,
            Snippet.visibility == "public"
        ).order_by(desc(Star.created_at)).all()
        snippets = []
        for st in stars:
            snippet = db.query(Snippet).filter(Snippet.id == st.snippet_id).first()
            if snippet:
                snippets.append(snippet)

    return [snippet_to_list_response(s) for s in snippets]
