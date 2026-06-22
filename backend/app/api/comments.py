from typing import List, Optional
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session
from sqlalchemy import desc

from app.core.database import get_db
from app.core.deps import get_current_user, get_current_user_optional
from app.models.models import User, Snippet, Comment, Favorite, Star, TeamMember
from app.schemas.schemas import CommentCreate, CommentResponse

router = APIRouter(prefix="/api/snippets/{snippet_id}/comments", tags=["comments"])


def can_view_snippet(db: Session, snippet: Snippet, user: Optional[User]) -> bool:
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


@router.get("", response_model=List[CommentResponse])
def list_comments(
    snippet_id: int,
    current_user: Optional[User] = Depends(get_current_user_optional),
    db: Session = Depends(get_db),
):
    snippet = db.query(Snippet).filter(Snippet.id == snippet_id).first()
    if not snippet:
        raise HTTPException(status_code=404, detail="Snippet not found")
    if not can_view_snippet(db, snippet, current_user):
        raise HTTPException(status_code=403, detail="You don't have permission to view this snippet")

    comments = db.query(Comment).filter(Comment.snippet_id == snippet_id).order_by(desc(Comment.created_at)).all()
    result = []
    for c in comments:
        result.append(CommentResponse(
            id=c.id,
            content=c.content,
            created_at=c.created_at,
            updated_at=c.updated_at,
            author_id=c.author_id,
            author_username=c.author.username if c.author else "",
            author_avatar_url=c.author.avatar_url if c.author else None,
        ))
    return result


@router.post("", response_model=CommentResponse, status_code=status.HTTP_201_CREATED)
def create_comment(
    snippet_id: int,
    comment_data: CommentCreate,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    snippet = db.query(Snippet).filter(Snippet.id == snippet_id).first()
    if not snippet:
        raise HTTPException(status_code=404, detail="Snippet not found")
    if not can_view_snippet(db, snippet, current_user):
        raise HTTPException(status_code=403, detail="You don't have permission to comment on this snippet")

    comment = Comment(
        content=comment_data.content,
        author_id=current_user.id,
        snippet_id=snippet_id,
    )
    db.add(comment)
    db.commit()
    db.refresh(comment)

    return CommentResponse(
        id=comment.id,
        content=comment.content,
        created_at=comment.created_at,
        updated_at=comment.updated_at,
        author_id=comment.author_id,
        author_username=current_user.username,
        author_avatar_url=current_user.avatar_url,
    )


@router.put("/{comment_id}", response_model=CommentResponse)
def update_comment(
    snippet_id: int,
    comment_id: int,
    comment_data: CommentCreate,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    comment = db.query(Comment).filter(Comment.id == comment_id, Comment.snippet_id == snippet_id).first()
    if not comment:
        raise HTTPException(status_code=404, detail="Comment not found")
    if comment.author_id != current_user.id:
        raise HTTPException(status_code=403, detail="You don't have permission to edit this comment")

    comment.content = comment_data.content
    db.commit()
    db.refresh(comment)

    return CommentResponse(
        id=comment.id,
        content=comment.content,
        created_at=comment.created_at,
        updated_at=comment.updated_at,
        author_id=comment.author_id,
        author_username=comment.author.username if comment.author else "",
        author_avatar_url=comment.author.avatar_url if comment.author else None,
    )


@router.delete("/{comment_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_comment(
    snippet_id: int,
    comment_id: int,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    comment = db.query(Comment).filter(Comment.id == comment_id, Comment.snippet_id == snippet_id).first()
    if not comment:
        raise HTTPException(status_code=404, detail="Comment not found")
    if comment.author_id != current_user.id:
        raise HTTPException(status_code=403, detail="You don't have permission to delete this comment")

    db.delete(comment)
    db.commit()
    return None
