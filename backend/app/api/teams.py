from typing import List, Optional
from fastapi import APIRouter, Depends, HTTPException, status, Query
from sqlalchemy.orm import Session
from sqlalchemy import desc

from app.core.database import get_db
from app.core.deps import get_current_user, get_current_user_optional
from app.models.models import User, Team, TeamMember, Snippet
from app.schemas.schemas import TeamCreate, TeamResponse, TeamMemberResponse, SnippetListResponse

router = APIRouter(prefix="/api/teams", tags=["teams"])


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


def is_team_member(db: Session, team_id: int, user_id: int) -> bool:
    membership = db.query(TeamMember).filter(
        TeamMember.team_id == team_id,
        TeamMember.user_id == user_id
    ).first()
    return membership is not None


def team_to_response(team: Team, db: Session) -> TeamResponse:
    members_count = db.query(TeamMember).filter(TeamMember.team_id == team.id).count()
    return TeamResponse(
        id=team.id,
        name=team.name,
        description=team.description,
        creator_id=team.creator_id,
        created_at=team.created_at,
        members_count=members_count,
    )


@router.get("", response_model=List[TeamResponse])
def list_teams(
    current_user: Optional[User] = Depends(get_current_user_optional),
    db: Session = Depends(get_db),
):
    if current_user:
        teams = db.query(Team).order_by(desc(Team.created_at)).all()
    else:
        teams = db.query(Team).order_by(desc(Team.created_at)).all()
    return [team_to_response(t, db) for t in teams]


@router.get("/{team_id}", response_model=TeamResponse)
def get_team(
    team_id: int,
    current_user: Optional[User] = Depends(get_current_user_optional),
    db: Session = Depends(get_db),
):
    team = db.query(Team).filter(Team.id == team_id).first()
    if not team:
        raise HTTPException(status_code=404, detail="Team not found")
    return team_to_response(team, db)


@router.post("", response_model=TeamResponse, status_code=status.HTTP_201_CREATED)
def create_team(
    team_data: TeamCreate,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    existing = db.query(Team).filter(Team.name == team_data.name).first()
    if existing:
        raise HTTPException(status_code=400, detail="Team name already exists")

    team = Team(
        name=team_data.name,
        description=team_data.description,
        creator_id=current_user.id,
    )
    db.add(team)
    db.flush()

    membership = TeamMember(
        team_id=team.id,
        user_id=current_user.id,
        role="admin",
    )
    db.add(membership)

    db.commit()
    db.refresh(team)

    return team_to_response(team, db)


@router.get("/{team_id}/members", response_model=List[TeamMemberResponse])
def list_team_members(
    team_id: int,
    current_user: Optional[User] = Depends(get_current_user_optional),
    db: Session = Depends(get_db),
):
    team = db.query(Team).filter(Team.id == team_id).first()
    if not team:
        raise HTTPException(status_code=404, detail="Team not found")

    members = db.query(TeamMember).filter(TeamMember.team_id == team_id).all()
    result = []
    for m in members:
        result.append(TeamMemberResponse(
            user_id=m.user_id,
            username=m.user.username if m.user else "",
            role=m.role,
            joined_at=m.joined_at,
        ))
    return result


@router.post("/{team_id}/members", status_code=status.HTTP_201_CREATED)
def add_team_member(
    team_id: int,
    username: str,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    team = db.query(Team).filter(Team.id == team_id).first()
    if not team:
        raise HTTPException(status_code=404, detail="Team not found")

    if not is_team_member(db, team_id, current_user.id):
        raise HTTPException(status_code=403, detail="You are not a member of this team")

    user = db.query(User).filter(User.username == username).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    existing = db.query(TeamMember).filter(
        TeamMember.team_id == team_id,
        TeamMember.user_id == user.id
    ).first()
    if existing:
        raise HTTPException(status_code=400, detail="User is already a member of this team")

    membership = TeamMember(
        team_id=team_id,
        user_id=user.id,
        role="member",
    )
    db.add(membership)
    db.commit()

    return {"message": "Member added successfully"}


@router.delete("/{team_id}/members/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
def remove_team_member(
    team_id: int,
    user_id: int,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    team = db.query(Team).filter(Team.id == team_id).first()
    if not team:
        raise HTTPException(status_code=404, detail="Team not found")

    current_membership = db.query(TeamMember).filter(
        TeamMember.team_id == team_id,
        TeamMember.user_id == current_user.id
    ).first()
    if not current_membership:
        raise HTTPException(status_code=403, detail="You are not a member of this team")
    if current_membership.role != "admin" and current_user.id != user_id:
        raise HTTPException(status_code=403, detail="Only admins can remove members")

    membership = db.query(TeamMember).filter(
        TeamMember.team_id == team_id,
        TeamMember.user_id == user_id
    ).first()
    if not membership:
        raise HTTPException(status_code=404, detail="Member not found")

    if user_id == team.creator_id:
        raise HTTPException(status_code=400, detail="Cannot remove team creator")

    db.delete(membership)
    db.commit()
    return None


@router.get("/{team_id}/snippets", response_model=List[SnippetListResponse])
def get_team_snippets(
    team_id: int,
    sort_by: str = Query("updated_at", regex="^(created_at|updated_at|stars_count|forks_count|views_count)$"),
    sort_order: str = Query("desc", regex="^(asc|desc)$"),
    current_user: Optional[User] = Depends(get_current_user_optional),
    db: Session = Depends(get_db),
):
    team = db.query(Team).filter(Team.id == team_id).first()
    if not team:
        raise HTTPException(status_code=404, detail="Team not found")

    is_member = False
    if current_user:
        is_member = is_team_member(db, team_id, current_user.id)

    query = db.query(Snippet).filter(Snippet.team_id == team_id)
    if not is_member:
        query = query.filter(Snippet.visibility == "public")

    sort_col = getattr(Snippet, sort_by)
    if sort_order == "desc":
        query = query.order_by(desc(sort_col))
    else:
        query = query.order_by(asc(sort_col))

    snippets = query.all()
    return [snippet_to_list_response(s) for s in snippets]


@router.get("/me/teams", response_model=List[TeamResponse])
def get_my_teams(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    memberships = db.query(TeamMember).filter(TeamMember.user_id == current_user.id).all()
    team_ids = [m.team_id for m in memberships]
    teams = db.query(Team).filter(Team.id.in_(team_ids)).all()
    return [team_to_response(t, db) for t in teams]
