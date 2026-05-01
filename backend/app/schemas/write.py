from pydantic import BaseModel, Field
from typing import Optional, Dict, Any
from uuid import UUID


class AutocompleteRequest(BaseModel):
    novel_id: UUID = Field(..., description="小说 ID")
    text: str = Field(..., description="当前文本内容")
    metadata: Optional[Dict[str, Any]] = Field(
        default_factory=dict, description="额外元数据"
    )
    max_tokens: int = Field(
        default=500, ge=1, le=2000, description="最大生成 token 数"
    )
    temperature: float = Field(
        default=0.7, ge=0.0, le=2.0, description="生成温度"
    )


class AutocompleteResponse(BaseModel):
    text: str = Field(..., description="生成的文本")
    finish_reason: str = Field(..., description="结束原因")
