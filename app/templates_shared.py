from fastapi.templating import Jinja2Templates
from app.context_processors import register_helpers

templates = Jinja2Templates(directory="app/templates")
register_helpers(templates)
