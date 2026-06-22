from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
import os

from app.core.database import engine, Base
from app.core.config import settings

from app.api.auth import router as auth_router
from app.api.snippets import router as snippets_router
from app.api.comments import router as comments_router
from app.api.search import router as search_router
from app.api.users import router as users_router
from app.api.teams import router as teams_router

Base.metadata.create_all(bind=engine)

app = FastAPI(title=settings.app_name, version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(auth_router)
app.include_router(snippets_router)
app.include_router(comments_router)
app.include_router(search_router)
app.include_router(users_router)
app.include_router(teams_router)

frontend_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "frontend")
if os.path.exists(frontend_dir):
    app.mount("/static", StaticFiles(directory=frontend_dir), name="static")

    @app.get("/")
    async def read_root():
        return FileResponse(os.path.join(frontend_dir, "index.html"))

    @app.get("/snippets/{snippet_id}")
    async def snippet_page(snippet_id: int):
        return FileResponse(os.path.join(frontend_dir, "snippet.html"))

    @app.get("/new")
    async def new_snippet_page():
        return FileResponse(os.path.join(frontend_dir, "new.html"))

    @app.get("/edit/{snippet_id}")
    async def edit_snippet_page(snippet_id: int):
        return FileResponse(os.path.join(frontend_dir, "edit.html"))

    @app.get("/search")
    async def search_page():
        return FileResponse(os.path.join(frontend_dir, "search.html"))

    @app.get("/users/{username}")
    async def user_profile_page(username: str):
        return FileResponse(os.path.join(frontend_dir, "profile.html"))

    @app.get("/teams")
    async def teams_page():
        return FileResponse(os.path.join(frontend_dir, "teams.html"))

    @app.get("/teams/{team_id}")
    async def team_detail_page(team_id: int):
        return FileResponse(os.path.join(frontend_dir, "team.html"))

    @app.get("/login")
    async def login_page():
        return FileResponse(os.path.join(frontend_dir, "login.html"))

    @app.get("/register")
    async def register_page():
        return FileResponse(os.path.join(frontend_dir, "register.html"))


@app.get("/api/health")
async def health_check():
    return {"status": "ok", "app": settings.app_name}
