import asyncio
import json
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

try:
    import aiosmtplib
    from email.message import EmailMessage
    HAS_SMTP = True
except ImportError:
    HAS_SMTP = False

from ..core.exceptions import NotificationError


@dataclass
class NotificationResult:
    success: bool
    channel_id: str
    message_id: Optional[str] = None
    error: Optional[str] = None
    delivered_at: Optional[datetime] = None
    metadata: Dict[str, Any] = field(default_factory=dict)


class NotificationChannel(ABC):
    def __init__(self, channel_id: str, enabled: bool = True):
        self.channel_id = channel_id
        self.enabled = enabled
        self._message_count = 0
        self._failure_count = 0
        self._closed = False

    @abstractmethod
    async def send(self, recipient: str, subject: str, content: str, **kwargs) -> NotificationResult:
        pass

    async def send_batch(
        self,
        recipients: List[str],
        subject: str,
        content: str,
        **kwargs,
    ) -> List[NotificationResult]:
        results = []
        for recipient in recipients:
            result = await self.send(recipient, subject, content, **kwargs)
            results.append(result)
        return results

    def get_stats(self) -> Dict[str, Any]:
        return {
            "channel_id": self.channel_id,
            "enabled": self.enabled,
            "messages_sent": self._message_count,
            "failures": self._failure_count,
            "closed": self._closed,
        }

    async def close(self) -> None:
        self._closed = True

    def __del__(self):
        if not self._closed:
            try:
                import asyncio
                loop = asyncio.get_event_loop()
                if loop.is_running():
                    loop.create_task(self.close())
            except Exception:
                pass


class EmailChannel(NotificationChannel):
    def __init__(
        self,
        channel_id: str = "email",
        smtp_host: str = "localhost",
        smtp_port: int = 587,
        username: Optional[str] = None,
        password: Optional[str] = None,
        use_tls: bool = True,
        sender: str = "no-reply@example.com",
        timeout: float = 30.0,
    ):
        super().__init__(channel_id)
        if not smtp_host:
            raise ValueError("smtp_host cannot be empty")
        if not (1 <= smtp_port <= 65535):
            raise ValueError(f"smtp_port must be between 1 and 65535, got {smtp_port}")
        if timeout <= 0:
            raise ValueError(f"timeout must be positive, got {timeout}")
        self.smtp_host = smtp_host
        self.smtp_port = smtp_port
        self.username = username
        self.password = password
        self.use_tls = use_tls
        self.sender = sender
        self.timeout = timeout

    async def send(self, recipient: str, subject: str, content: str, **kwargs) -> NotificationResult:
        if self._closed:
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error="Channel closed",
            )
        if not HAS_SMTP:
            raise RuntimeError("aiosmtplib not installed. Install with 'pip install aiosmtplib'")
        if not self.enabled:
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error="Channel disabled",
            )
        if not recipient:
            self._failure_count += 1
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error="Recipient cannot be empty",
            )
        try:
            message = EmailMessage()
            message["From"] = self.sender
            message["To"] = recipient
            message["Subject"] = subject or ""
            message.set_content(content or "")
            if kwargs.get("html_content"):
                message.add_alternative(kwargs["html_content"], subtype="html")
            await aiosmtplib.send(
                message,
                hostname=self.smtp_host,
                port=self.smtp_port,
                username=self.username,
                password=self.password,
                use_tls=self.use_tls,
                timeout=self.timeout,
            )
            self._message_count += 1
            return NotificationResult(
                success=True,
                channel_id=self.channel_id,
                message_id=f"email_{self._message_count}",
                delivered_at=datetime.now(timezone.utc),
            )
        except Exception as e:
            self._failure_count += 1
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error=str(e),
            )


class SlackChannel(NotificationChannel):
    def __init__(
        self,
        channel_id: str = "slack",
        webhook_url: Optional[str] = None,
        bot_token: Optional[str] = None,
        timeout: float = 30.0,
    ):
        super().__init__(channel_id)
        if timeout <= 0:
            raise ValueError(f"timeout must be positive, got {timeout}")
        self.webhook_url = webhook_url
        self.bot_token = bot_token
        self.timeout = timeout

    async def send(self, recipient: str, subject: str, content: str, **kwargs) -> NotificationResult:
        if self._closed:
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error="Channel closed",
            )
        if not self.enabled:
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error="Channel disabled",
            )
        if not self.webhook_url and not self.bot_token:
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error="No webhook_url or bot_token configured",
            )
        if self.webhook_url:
            return await self._send_via_webhook(recipient, subject, content, **kwargs)
        else:
            return await self._send_via_api(recipient, subject, content, **kwargs)

    async def _send_via_webhook(self, recipient: str, subject: str, content: str, **kwargs) -> NotificationResult:
        try:
            import aiohttp
            payload = {
                "text": f"*{subject or ''}*\n{content or ''}",
            }
            if kwargs.get("blocks"):
                payload["blocks"] = kwargs["blocks"]
            session = None
            try:
                session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=self.timeout))
                async with session.post(self.webhook_url, json=payload) as response:
                    if response.status == 200:
                        self._message_count += 1
                        return NotificationResult(
                            success=True,
                            channel_id=self.channel_id,
                            message_id=f"slack_webhook_{self._message_count}",
                            delivered_at=datetime.now(timezone.utc),
                        )
                    text = await response.text()
                    self._failure_count += 1
                    return NotificationResult(
                        success=False,
                        channel_id=self.channel_id,
                        error=f"Slack webhook failed: {response.status} - {text}",
                    )
            finally:
                if session is not None:
                    await session.close()
        except ImportError:
            raise RuntimeError("aiohttp not installed")
        except Exception as e:
            self._failure_count += 1
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error=str(e),
            )

    async def _send_via_api(self, recipient: str, subject: str, content: str, **kwargs) -> NotificationResult:
        if not recipient:
            self._failure_count += 1
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error="Recipient cannot be empty",
            )
        try:
            import aiohttp
            url = "https://slack.com/api/chat.postMessage"
            headers = {"Authorization": f"Bearer {self.bot_token}"}
            payload = {
                "channel": recipient,
                "text": f"*{subject or ''}*\n{content or ''}",
            }
            if kwargs.get("blocks"):
                payload["blocks"] = kwargs["blocks"]
            session = None
            try:
                session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=self.timeout))
                async with session.post(url, headers=headers, json=payload) as response:
                    data = await response.json()
                    if data.get("ok"):
                        self._message_count += 1
                        return NotificationResult(
                            success=True,
                            channel_id=self.channel_id,
                            message_id=data.get("ts"),
                            delivered_at=datetime.now(timezone.utc),
                        )
                    self._failure_count += 1
                    return NotificationResult(
                        success=False,
                        channel_id=self.channel_id,
                        error=data.get("error", "Unknown Slack error"),
                    )
            finally:
                if session is not None:
                    await session.close()
        except ImportError:
            raise RuntimeError("aiohttp not installed")
        except Exception as e:
            self._failure_count += 1
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error=str(e),
            )


class WebhookChannel(NotificationChannel):
    def __init__(
        self,
        channel_id: str = "webhook",
        url: str = "",
        method: str = "POST",
        headers: Optional[Dict[str, str]] = None,
        timeout: float = 10.0,
    ):
        super().__init__(channel_id)
        if not url:
            raise ValueError("url cannot be empty")
        if not url.startswith(("http://", "https://")):
            raise ValueError(f"url must start with http:// or https://, got {url}")
        if method.upper() not in ("GET", "POST", "PUT", "PATCH", "DELETE"):
            raise ValueError(f"method must be one of GET, POST, PUT, PATCH, DELETE, got {method}")
        if timeout <= 0:
            raise ValueError(f"timeout must be positive, got {timeout}")
        self.url = url
        self.method = method.upper()
        self.headers = headers or {"Content-Type": "application/json"}
        self.timeout = timeout

    async def send(self, recipient: str, subject: str, content: str, **kwargs) -> NotificationResult:
        if self._closed:
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error="Channel closed",
            )
        if not self.enabled:
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error="Channel disabled",
            )
        try:
            import aiohttp
            payload = {
                "recipient": recipient or "",
                "subject": subject or "",
                "content": content or "",
                "metadata": kwargs,
                "timestamp": datetime.now(timezone.utc).isoformat(),
            }
            session = None
            try:
                session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=self.timeout))
                method_func = getattr(session, self.method.lower(), session.post)
                async with method_func(self.url, headers=self.headers, json=payload) as response:
                    if 200 <= response.status < 300:
                        self._message_count += 1
                        response_data = {}
                        try:
                            response_data = await response.json()
                        except Exception:
                            pass
                        return NotificationResult(
                            success=True,
                            channel_id=self.channel_id,
                            message_id=f"webhook_{self._message_count}",
                            delivered_at=datetime.now(timezone.utc),
                            metadata={"status": response.status, "response": response_data},
                        )
                    text = await response.text()
                    self._failure_count += 1
                    return NotificationResult(
                        success=False,
                        channel_id=self.channel_id,
                        error=f"Webhook failed: {response.status} - {text}",
                    )
            finally:
                if session is not None:
                    await session.close()
        except ImportError:
            raise RuntimeError("aiohttp not installed")
        except Exception as e:
            self._failure_count += 1
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error=str(e),
            )


class ConsoleChannel(NotificationChannel):
    def __init__(self, channel_id: str = "console", output_func=None):
        super().__init__(channel_id)
        self._output = output_func or print

    async def send(self, recipient: str, subject: str, content: str, **kwargs) -> NotificationResult:
        if self._closed:
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error="Channel closed",
            )
        if not self.enabled:
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error="Channel disabled",
            )
        self._output(f"[Console Notification] To: {recipient} | Subject: {subject}")
        self._output(f"  Content: {content}")
        if kwargs:
            self._output(f"  Metadata: {json.dumps(kwargs, indent=2, default=str)}")
        self._message_count += 1
        return NotificationResult(
            success=True,
            channel_id=self.channel_id,
            message_id=f"console_{self._message_count}",
            delivered_at=datetime.now(timezone.utc),
        )


class MultiChannel(NotificationChannel):
    def __init__(
        self,
        channel_id: str = "multi",
        channels: Optional[List[NotificationChannel]] = None,
        require_all: bool = False,
    ):
        super().__init__(channel_id)
        self._channels: List[NotificationChannel] = list(channels or [])
        self.require_all = require_all

    def add_channel(self, channel: NotificationChannel) -> None:
        if channel is None:
            raise ValueError("channel cannot be None")
        self._channels.append(channel)

    def remove_channel(self, channel_id: str) -> bool:
        for i, ch in enumerate(self._channels):
            if ch.channel_id == channel_id:
                del self._channels[i]
                return True
        return False

    async def close(self) -> None:
        for channel in self._channels:
            try:
                await channel.close()
            except Exception:
                pass
        self._channels.clear()
        await super().close()

    async def send(self, recipient: str, subject: str, content: str, **kwargs) -> NotificationResult:
        if self._closed:
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error="Channel closed",
            )
        if not self.enabled:
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error="Channel disabled",
            )
        if not self._channels:
            return NotificationResult(
                success=False,
                channel_id=self.channel_id,
                error="No channels configured",
            )
        results = []
        for channel in self._channels:
            result = await channel.send(recipient, subject, content, **kwargs)
            results.append(result)
        success_count = sum(1 for r in results if r.success)
        success = (success_count == len(self._channels)) if self.require_all else (success_count > 0)
        if success:
            self._message_count += 1
        else:
            self._failure_count += 1
        return NotificationResult(
            success=success,
            channel_id=self.channel_id,
            message_id=f"multi_{self._message_count}",
            delivered_at=datetime.now(timezone.utc),
            metadata={
                "results": [r.__dict__ for r in results],
                "success_count": success_count,
                "total_channels": len(self._channels),
            },
        )
