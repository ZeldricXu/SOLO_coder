from fastapi import APIRouter, Depends, Header, HTTPException
from typing import Optional, List
from src.core import ApiResponse, get_trace_id
from src.modules.notification import (
    NotificationTemplate,
    NotificationRequest,
    NotificationChannel,
    NotificationStatus,
    ChannelConfig,
    NotificationBatchRequest,
)
from src.di import DIContainer, get_container

router = APIRouter(prefix="/api/v1/notifications", tags=["Notification"])


@router.post("/templates", response_model=ApiResponse)
async def create_template(
    template: NotificationTemplate,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.notification.create_template(template, trace_id or get_trace_id())
    return ApiResponse.created(result)


@router.get("/templates", response_model=ApiResponse)
async def list_templates(
    channel: Optional[NotificationChannel] = None,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.notification.list_templates(channel, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/templates/{template_id}", response_model=ApiResponse)
async def get_template(
    template_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.notification.get_template(template_id, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/templates/{template_id}/render", response_model=ApiResponse)
async def render_template(
    template_id: str,
    variables: dict,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.notification.render_template(
        template_id, variables, trace_id or get_trace_id()
    )
    return ApiResponse.success(result)


@router.post("/send", response_model=ApiResponse)
async def send_notification(
    request: NotificationRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.notification.send_notification(request, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/send/batch", response_model=ApiResponse)
async def send_batch(
    request: NotificationBatchRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.notification.send_batch(
        request.notifications, trace_id or get_trace_id()
    )
    return ApiResponse.success(result)


@router.get("/channels", response_model=ApiResponse)
async def list_channels():
    return ApiResponse.success([c.value for c in NotificationChannel])


@router.post("/channels/config", response_model=ApiResponse)
async def configure_channel(
    config: ChannelConfig,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.notification.configure_channel(config, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/channels/{channel}/stats", response_model=ApiResponse)
async def get_channel_stats(
    channel: NotificationChannel,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.notification.get_channel_stats(channel, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/{notification_id}", response_model=ApiResponse)
async def get_notification(
    notification_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.notification.get_notification(notification_id, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/{notification_id}/read", response_model=ApiResponse)
async def mark_as_read(
    notification_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.notification.mark_as_read(notification_id, trace_id or get_trace_id())
    return ApiResponse.success({"read": result})
