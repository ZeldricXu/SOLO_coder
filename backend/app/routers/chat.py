import json
import asyncio
from datetime import datetime
from typing import List, Optional, Dict, Any, AsyncIterator
from fastapi import APIRouter, HTTPException, Depends, Request
from fastapi.responses import StreamingResponse, JSONResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_db
from app.models.schemas import (
    ChatRequest,
    ChatResponse,
    ErrorResponse,
    Message
)
from app.core.di_provider import (
    get_chat_orchestrator,
    ChatOrchestrator,
    ChatRequest as ServiceChatRequest
)
from app.core.background_tasks import submit_log_task

router = APIRouter()


def format_sse_event(event_type: str, data: Any) -> str:
    event_str = json.dumps(data, ensure_ascii=False)
    return f"data: {event_str}\n\n"


async def chat_stream_generator(
    orchestrator: ChatOrchestrator,
    request: ChatRequest,
    start_time: datetime
) -> AsyncIterator[str]:
    service_request = ServiceChatRequest(
        model=request.model,
        messages=[
            {"role": msg.role.value if hasattr(msg.role, 'value') else msg.role, "content": msg.content}
            for msg in request.messages
        ],
        collection_name=request.collection_name,
        stream=request.stream,
        temperature=request.temperature or 0.7,
        max_tokens=request.max_tokens,
        use_rag=(request.collection_name is not None)
    )

    model_name = request.model
    status_code = 200

    try:
        async for event in orchestrator.chat(service_request):
            event_data = {
                "type": event.event_type,
                "data": event.data
            }
            yield format_sse_event(event.event_type, event.data)
            await asyncio.sleep(0)

            if event.event_type == "error":
                status_code = 500

    except Exception as e:
        error_data = {"type": "error", "data": str(e)}
        yield format_sse_event("error", str(e))
        status_code = 500

    finally:
        duration_ms = (datetime.utcnow() - start_time).total_seconds() * 1000
        submit_log_task(
            model_name=model_name,
            duration_ms=duration_ms,
            status_code=status_code,
            endpoint="/api/v1/chat",
            method="POST",
            request_time=start_time
        )


@router.post(
    "/chat",
    responses={
        400: {"model": ErrorResponse},
        500: {"model": ErrorResponse}
    }
)
async def chat(
    request: ChatRequest,
    raw_request: Request = None,
    db: AsyncSession = Depends(get_db),
    orchestrator: ChatOrchestrator = Depends(get_chat_orchestrator)
):
    if not request.messages:
        raise HTTPException(
            status_code=400,
            detail="消息列表不能为空"
        )

    start_time = datetime.utcnow()

    if request.stream:
        return StreamingResponse(
            chat_stream_generator(
                orchestrator=orchestrator,
                request=request,
                start_time=start_time
            ),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache, no-store, must-revalidate",
                "Pragma": "no-cache",
                "Expires": "0",
                "X-Accel-Buffering": "no",
                "Connection": "keep-alive",
                "Transfer-Encoding": "chunked",
                "X-Content-Type-Options": "nosniff",
            }
        )
    else:
        try:
            service_request = ServiceChatRequest(
                model=request.model,
                messages=[
                    {"role": msg.role.value if hasattr(msg.role, 'value') else msg.role, "content": msg.content}
                    for msg in request.messages
                ],
                collection_name=request.collection_name,
                stream=False,
                temperature=request.temperature or 0.7,
                max_tokens=request.max_tokens,
                use_rag=(request.collection_name is not None)
            )

            result = await orchestrator.chat_non_stream(service_request)

            duration_ms = (datetime.utcnow() - start_time).total_seconds() * 1000
            submit_log_task(
                model_name=request.model,
                duration_ms=duration_ms,
                status_code=200,
                endpoint="/api/v1/chat",
                method="POST",
                request_time=start_time
            )

            return ChatResponse(
                content=result.get("content", ""),
                model=request.model,
                created_at=start_time.isoformat()
            )

        except HTTPException:
            raise
        except Exception as e:
            duration_ms = (datetime.utcnow() - start_time).total_seconds() * 1000
            submit_log_task(
                model_name=request.model,
                duration_ms=duration_ms,
                status_code=500,
                endpoint="/api/v1/chat",
                method="POST",
                request_time=start_time
            )
            raise HTTPException(
                status_code=500,
                detail=f"对话处理失败: {str(e)}"
            )
