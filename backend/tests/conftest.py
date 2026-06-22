import os
import tempfile
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

os.environ["TESTING"] = "1"

from app.main import app
from app.core.database import get_db, Base
from app.core.config import settings
from app.core.security import get_password_hash
from app.core import search_fts
from app.models.models import User, Team, TeamMember

_test_db_fd, _test_db_path = tempfile.mkstemp(suffix=".db")
TEST_DATABASE_URL = f"sqlite:///{_test_db_path}"

engine = create_engine(TEST_DATABASE_URL, connect_args={"check_same_thread": False})
TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def override_get_db():
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()


app.dependency_overrides[get_db] = override_get_db


@pytest.fixture(scope="session", autouse=True)
def setup_database():
    Base.metadata.create_all(bind=engine)
    from sqlalchemy import text
    with engine.begin() as conn:
        conn.execute(text("""
        CREATE VIRTUAL TABLE IF NOT EXISTS snippets_fts USING fts5(
            snippet_id UNINDEXED,
            title,
            description,
            code,
            tags UNINDEXED,
            tokenize='unicode61 remove_diacritics 2'
        )
        """))
    yield
    Base.metadata.drop_all(bind=engine)
    os.close(_test_db_fd)
    os.unlink(_test_db_path)


@pytest.fixture
def db():
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.rollback()
        db.close()


@pytest.fixture
def client():
    with TestClient(app) as c:
        yield c


@pytest.fixture
def test_user(client, db):
    username = "testuser"
    password = "testpass123"
    user = db.query(User).filter(User.username == username).first()
    if not user:
        user = User(
            username=username,
            email="test@example.com",
            hashed_password=get_password_hash(password),
        )
        db.add(user)
        db.commit()
        db.refresh(user)
    return user


@pytest.fixture
def test_user_token(client, test_user):
    response = client.post(
        "/api/auth/login/json",
        json={"username": "testuser", "password": "testpass123"},
    )
    assert response.status_code == 200
    return response.json()["access_token"]


@pytest.fixture
def auth_headers(test_user_token):
    return {"Authorization": f"Bearer {test_user_token}"}


@pytest.fixture
def test_user2(client, db):
    username = "testuser2"
    password = "testpass456"
    user = db.query(User).filter(User.username == username).first()
    if not user:
        user = User(
            username=username,
            email="test2@example.com",
            hashed_password=get_password_hash(password),
        )
        db.add(user)
        db.commit()
        db.refresh(user)
    return user


@pytest.fixture
def test_user2_token(client, test_user2):
    response = client.post(
        "/api/auth/login/json",
        json={"username": "testuser2", "password": "testpass456"},
    )
    assert response.status_code == 200
    return response.json()["access_token"]


@pytest.fixture
def auth_headers2(test_user2_token):
    return {"Authorization": f"Bearer {test_user2_token}"}


@pytest.fixture
def test_team(db, test_user):
    team = db.query(Team).filter(Team.name == "test-team").first()
    if not team:
        team = Team(name="test-team", description="Test Team", creator_id=test_user.id)
        db.add(team)
        db.flush()
        member = TeamMember(team_id=team.id, user_id=test_user.id, role="admin")
        db.add(member)
        db.commit()
        db.refresh(team)
    return team
