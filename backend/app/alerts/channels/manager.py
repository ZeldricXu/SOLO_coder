from typing import Dict, Any, Optional, List
import logging

from app.alerts.channels.base import NotificationChannel
from app.alerts.channels.slack_channel import SlackChannel
from app.alerts.channels.email_channel import EmailChannel
from app.core.models import (
    AlertNotification,
    NotificationChannelType,
    ChannelConfig
)

logger = logging.getLogger(__name__)


class ChannelManager:
    def __init__(self):
        self._channels: Dict[str, NotificationChannel] = {}
        self._channel_types: Dict[NotificationChannelType, type] = {
            NotificationChannelType.SLACK: SlackChannel,
            NotificationChannelType.EMAIL: EmailChannel
        }

    def register_channel(self, name: str, channel: NotificationChannel) -> bool:
        if name in self._channels:
            logger.warning(f"Channel '{name}' already registered")
            return False

        self._channels[name] = channel
        logger.info(f"Registered channel: {name}")
        return True

    def get_channel(self, name: str) -> Optional[NotificationChannel]:
        return self._channels.get(name)

    def get_channel_by_type(self, channel_type: NotificationChannelType) -> Optional[NotificationChannel]:
        for channel in self._channels.values():
            if hasattr(channel, 'channel_type') and channel.channel_type == channel_type:
                return channel
        return None

    async def initialize_default_channels(self):
        slack_channel = SlackChannel()
        email_channel = EmailChannel()

        self._channels['slack'] = slack_channel
        self._channels['email'] = email_channel

        for name, channel in self._channels.items():
            try:
                await channel.initialize()
                if channel.is_available:
                    logger.info(f"Default channel '{name}' initialized and available")
                else:
                    logger.warning(f"Default channel '{name}' initialized but not available")
            except Exception as e:
                logger.error(f"Failed to initialize default channel '{name}': {e}")

    async def send_to_channel(
        self,
        notification: AlertNotification,
        channel_name: str = None,
        channel_type: NotificationChannelType = None
    ) -> bool:
        channel = None

        if channel_name:
            channel = self._channels.get(channel_name)
        elif channel_type:
            channel = self.get_channel_by_type(channel_type)

        if not channel:
            logger.warning(
                f"Channel not found: name={channel_name}, type={channel_type}"
            )
            return False

        return await channel.send_with_retry(notification)

    async def send_to_channels(
        self,
        notification: AlertNotification,
        channel_names: List[str] = None,
        channel_types: List[NotificationChannelType] = None
    ) -> Dict[str, bool]:
        results = {}

        if channel_names:
            for name in channel_names:
                success = await self.send_to_channel(notification, channel_name=name)
                results[name] = success

        if channel_types:
            for ctype in channel_types:
                channel = self.get_channel_by_type(ctype)
                if channel:
                    success = await channel.send_with_retry(notification)
                    results[f"{ctype.value}"] = success

        return results

    async def close_all(self):
        for name, channel in self._channels.items():
            try:
                await channel.close()
                logger.info(f"Closed channel: {name}")
            except Exception as e:
                logger.error(f"Error closing channel '{name}': {e}")

        self._channels.clear()

    def get_all_status(self) -> Dict[str, Dict[str, Any]]:
        return {
            name: channel.get_status()
            for name, channel in self._channels.items()
        }

    def get_available_channels(self) -> List[str]:
        return [
            name
            for name, channel in self._channels.items()
            if channel.is_available
        ]


channel_manager = ChannelManager()
