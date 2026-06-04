from typing import Optional, Dict, Any
from pydantic import BaseModel


class PreferenceUpdate(BaseModel):
    layout_config: Optional[Dict[str, Any]] = None
