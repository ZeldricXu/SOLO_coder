from datetime import datetime
from typing import List, Optional
from pydantic import BaseModel, EmailStr


class UserBase(BaseModel):
    username: str
    email: EmailStr


class UserCreate(UserBase):
    password: str


class UserLogin(BaseModel):
    username: str
    password: str


class UserResponse(UserBase):
    id: int
    avatar_url: Optional[str] = None
    bio: Optional[str] = None
    created_at: datetime

    class Config:
        from_attributes = True


class UserProfile(UserResponse):
    snippets_count: int = 0
    forks_count: int = 0


class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"


class TokenData(BaseModel):
    username: Optional[str] = None


class TagBase(BaseModel):
    name: str


class TagResponse(TagBase):
    id: int

    class Config:
        from_attributes = True


class TeamBase(BaseModel):
    name: str
    description: Optional[str] = None


class TeamCreate(TeamBase):
    pass


class TeamResponse(TeamBase):
    id: int
    creator_id: int
    created_at: datetime
    members_count: int = 0

    class Config:
        from_attributes = True


class TeamMemberResponse(BaseModel):
    user_id: int
    username: str
    role: str
    joined_at: datetime

    class Config:
        from_attributes = True


class SnippetBase(BaseModel):
    title: str
    description: Optional[str] = None
    code: str
    language: str
    visibility: str = "private"
    tags: List[str] = []
    team_id: Optional[int] = None


class SnippetCreate(SnippetBase):
    parent_id: Optional[int] = None


class SnippetUpdate(BaseModel):
    title: Optional[str] = None
    description: Optional[str] = None
    code: Optional[str] = None
    language: Optional[str] = None
    visibility: Optional[str] = None
    tags: Optional[List[str]] = None
    team_id: Optional[int] = None


class SnippetResponse(BaseModel):
    id: int
    title: str
    description: Optional[str] = None
    code: str
    rendered_html: Optional[str] = None
    language: str
    visibility: str
    stars_count: int
    forks_count: int
    views_count: int
    is_deleted: bool = False
    created_at: datetime
    updated_at: datetime
    author_id: int
    author_username: str
    author_avatar_url: Optional[str] = None
    team_id: Optional[int] = None
    team_name: Optional[str] = None
    parent_id: Optional[int] = None
    parent_title: Optional[str] = None
    parent_author_username: Optional[str] = None
    parent_updated_at: Optional[datetime] = None
    parent_has_updates: bool = False
    tags: List[str] = []
    is_favorited: bool = False
    is_starred: bool = False

    class Config:
        from_attributes = True


class SnippetListResponse(BaseModel):
    id: int
    title: str
    description: Optional[str] = None
    language: str
    visibility: str
    stars_count: int
    forks_count: int
    views_count: int
    created_at: datetime
    updated_at: datetime
    author_id: int
    author_username: str
    team_name: Optional[str] = None
    tags: List[str] = []

    class Config:
        from_attributes = True


class PaginatedSnippets(BaseModel):
    items: List[SnippetListResponse]
    total: int
    page: int
    page_size: int
    total_pages: int


class CommentBase(BaseModel):
    content: str


class CommentCreate(CommentBase):
    pass


class CommentResponse(CommentBase):
    id: int
    created_at: datetime
    updated_at: datetime
    author_id: int
    author_username: str
    author_avatar_url: Optional[str] = None

    class Config:
        from_attributes = True


class FavoriteResponse(BaseModel):
    id: int
    snippet_id: int
    created_at: datetime

    class Config:
        from_attributes = True
