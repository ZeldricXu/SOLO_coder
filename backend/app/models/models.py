from datetime import datetime
from sqlalchemy import Column, Integer, String, Text, DateTime, ForeignKey, Boolean, UniqueConstraint
from sqlalchemy.orm import relationship

from app.core.database import Base


class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String(50), unique=True, index=True, nullable=False)
    email = Column(String(100), unique=True, index=True, nullable=False)
    hashed_password = Column(String(200), nullable=False)
    avatar_url = Column(String(255), nullable=True)
    bio = Column(Text, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)

    snippets = relationship("Snippet", back_populates="author", cascade="all, delete-orphan")
    comments = relationship("Comment", back_populates="author", cascade="all, delete-orphan")
    favorites = relationship("Favorite", back_populates="user", cascade="all, delete-orphan")
    stars = relationship("Star", back_populates="user", cascade="all, delete-orphan")
    team_memberships = relationship("TeamMember", back_populates="user", cascade="all, delete-orphan")


class Team(Base):
    __tablename__ = "teams"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(100), unique=True, index=True, nullable=False)
    description = Column(Text, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    creator_id = Column(Integer, ForeignKey("users.id"), nullable=False)

    members = relationship("TeamMember", back_populates="team", cascade="all, delete-orphan")
    snippets = relationship("Snippet", back_populates="team")


class TeamMember(Base):
    __tablename__ = "team_members"

    id = Column(Integer, primary_key=True, index=True)
    team_id = Column(Integer, ForeignKey("teams.id"), nullable=False)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    role = Column(String(20), default="member")
    joined_at = Column(DateTime, default=datetime.utcnow)

    team = relationship("Team", back_populates="members")
    user = relationship("User", back_populates="team_memberships")

    __table_args__ = (UniqueConstraint("team_id", "user_id", name="unique_team_member"),)


class Snippet(Base):
    __tablename__ = "snippets"

    id = Column(Integer, primary_key=True, index=True)
    title = Column(String(200), nullable=False)
    description = Column(Text, nullable=True)
    code = Column(Text, nullable=False)
    language = Column(String(50), nullable=False, index=True)
    visibility = Column(String(20), nullable=False, default="private", index=True)
    stars_count = Column(Integer, default=0)
    forks_count = Column(Integer, default=0)
    views_count = Column(Integer, default=0)
    created_at = Column(DateTime, default=datetime.utcnow, index=True)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow, index=True)

    author_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    team_id = Column(Integer, ForeignKey("teams.id"), nullable=True, index=True)
    parent_id = Column(Integer, ForeignKey("snippets.id"), nullable=True, index=True)

    author = relationship("User", back_populates="snippets")
    team = relationship("Team", back_populates="snippets")
    parent = relationship("Snippet", remote_side=[id], backref="forks")
    tags = relationship("SnippetTag", back_populates="snippet", cascade="all, delete-orphan")
    comments = relationship("Comment", back_populates="snippet", cascade="all, delete-orphan")
    favorited_by = relationship("Favorite", back_populates="snippet", cascade="all, delete-orphan")
    starred_by = relationship("Star", back_populates="snippet", cascade="all, delete-orphan")


class Tag(Base):
    __tablename__ = "tags"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(50), unique=True, index=True, nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow)

    snippets = relationship("SnippetTag", back_populates="tag")


class SnippetTag(Base):
    __tablename__ = "snippet_tags"

    id = Column(Integer, primary_key=True, index=True)
    snippet_id = Column(Integer, ForeignKey("snippets.id"), nullable=False)
    tag_id = Column(Integer, ForeignKey("tags.id"), nullable=False)

    snippet = relationship("Snippet", back_populates="tags")
    tag = relationship("Tag", back_populates="snippets")

    __table_args__ = (UniqueConstraint("snippet_id", "tag_id", name="unique_snippet_tag"),)


class Comment(Base):
    __tablename__ = "comments"

    id = Column(Integer, primary_key=True, index=True)
    content = Column(Text, nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    author_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    snippet_id = Column(Integer, ForeignKey("snippets.id"), nullable=False, index=True)

    author = relationship("User", back_populates="comments")
    snippet = relationship("Snippet", back_populates="comments")


class Favorite(Base):
    __tablename__ = "favorites"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    snippet_id = Column(Integer, ForeignKey("snippets.id"), nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow)

    user = relationship("User", back_populates="favorites")
    snippet = relationship("Snippet", back_populates="favorited_by")

    __table_args__ = (UniqueConstraint("user_id", "snippet_id", name="unique_favorite"),)


class Star(Base):
    __tablename__ = "stars"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    snippet_id = Column(Integer, ForeignKey("snippets.id"), nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow)

    user = relationship("User", back_populates="stars")
    snippet = relationship("Snippet", back_populates="starred_by")

    __table_args__ = (UniqueConstraint("user_id", "snippet_id", name="unique_star"),)
