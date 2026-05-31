import pytest

from platform_engineer.notification import ConsoleChannel, MultiChannel


class TestNotificationChannelLifecycle:
    @pytest.mark.asyncio
    async def test_channel_close(self):
        channel = ConsoleChannel()
        assert not channel._closed

        result = await channel.send("test@example.com", "Subject", "Content")
        assert result.success is True
        assert channel.get_stats()["closed"] is False

        await channel.close()
        assert channel._closed is True
        assert channel.get_stats()["closed"] is True

        result2 = await channel.send("test@example.com", "Subject", "Content")
        assert result2.success is False
        assert result2.error == "Channel closed"

    @pytest.mark.asyncio
    async def test_channel_send_after_close(self):
        channel = ConsoleChannel()
        await channel.close()

        result = await channel.send("test@example.com", "Subject", "Content")
        assert result.success is False
        assert result.error == "Channel closed"

    @pytest.mark.asyncio
    async def test_multi_channel_close_closes_all(self):
        channel1 = ConsoleChannel(channel_id="console1")
        channel2 = ConsoleChannel(channel_id="console2")

        multi = MultiChannel(channels=[channel1, channel2])

        await multi.send("test@example.com", "Subject", "Content")

        assert channel1._closed is False
        assert channel2._closed is False

        await multi.close()

        assert channel1._closed is True
        assert channel2._closed is True
        assert multi._closed is True


class TestNotificationChannelValidation:
    @pytest.mark.asyncio
    async def test_console_channel_empty_recipient(self):
        channel = ConsoleChannel()

        result = await channel.send("", "Subject", "Content")
        assert result.success is True

    @pytest.mark.asyncio
    async def test_console_channel_empty_content(self):
        channel = ConsoleChannel()

        result = await channel.send("test@example.com", "Subject", "")
        assert result.success is True

    @pytest.mark.asyncio
    async def test_multi_channel_no_channels(self):
        multi = MultiChannel()

        result = await multi.send("test@example.com", "Subject", "Content")
        assert result.success is False
        assert "No channels configured" in result.error

    def test_multi_channel_add_none(self):
        multi = MultiChannel()

        with pytest.raises(ValueError, match="cannot be None"):
            multi.add_channel(None)


class TestWebhookChannelValidation:
    def test_webhook_channel_empty_url(self):
        from platform_engineer.notification.channels import WebhookChannel

        with pytest.raises(ValueError, match="url cannot be empty"):
            WebhookChannel(url="")

    def test_webhook_channel_invalid_url(self):
        from platform_engineer.notification.channels import WebhookChannel

        with pytest.raises(ValueError, match="must start with http"):
            WebhookChannel(url="ftp://example.com")

    def test_webhook_channel_invalid_method(self):
        from platform_engineer.notification.channels import WebhookChannel

        with pytest.raises(ValueError, match="method must be one of"):
            WebhookChannel(url="http://example.com", method="INVALID")

    def test_webhook_channel_negative_timeout(self):
        from platform_engineer.notification.channels import WebhookChannel

        with pytest.raises(ValueError, match="timeout must be positive"):
            WebhookChannel(url="http://example.com", timeout=-1)

    def test_webhook_channel_zero_timeout(self):
        from platform_engineer.notification.channels import WebhookChannel

        with pytest.raises(ValueError, match="timeout must be positive"):
            WebhookChannel(url="http://example.com", timeout=0)

    def test_webhook_channel_valid_url(self):
        from platform_engineer.notification.channels import WebhookChannel

        channel = WebhookChannel(url="http://example.com/webhook")
        assert channel.url == "http://example.com/webhook"
        assert channel.method == "POST"

        channel2 = WebhookChannel(url="https://example.com/webhook", method="GET")
        assert channel2.url == "https://example.com/webhook"
        assert channel2.method == "GET"

    def test_webhook_channel_method_normalization(self):
        from platform_engineer.notification.channels import WebhookChannel

        channel = WebhookChannel(url="http://example.com", method="post")
        assert channel.method == "POST"


class TestEmailChannelValidation:
    def test_email_channel_empty_host(self):
        from platform_engineer.notification.channels import EmailChannel

        with pytest.raises(ValueError, match="smtp_host cannot be empty"):
            EmailChannel(smtp_host="")

    def test_email_channel_invalid_port(self):
        from platform_engineer.notification.channels import EmailChannel

        with pytest.raises(ValueError, match="smtp_port must be between"):
            EmailChannel(smtp_port=0)

        with pytest.raises(ValueError, match="smtp_port must be between"):
            EmailChannel(smtp_port=70000)

    def test_email_channel_negative_timeout(self):
        from platform_engineer.notification.channels import EmailChannel

        with pytest.raises(ValueError, match="timeout must be positive"):
            EmailChannel(timeout=-1)

    def test_email_channel_valid(self):
        from platform_engineer.notification.channels import EmailChannel

        channel = EmailChannel(
            smtp_host="smtp.example.com",
            smtp_port=587,
            username="user",
            password="pass",
        )
        assert channel.smtp_host == "smtp.example.com"
        assert channel.smtp_port == 587


class TestSlackChannelValidation:
    def test_slack_channel_negative_timeout(self):
        from platform_engineer.notification.channels import SlackChannel

        with pytest.raises(ValueError, match="timeout must be positive"):
            SlackChannel(timeout=-1)

    def test_slack_channel_no_config(self):
        from platform_engineer.notification.channels import SlackChannel
        import asyncio

        async def test():
            channel = SlackChannel()
            result = await channel.send("#channel", "Subject", "Content")
            return result

        result = asyncio.run(test())
        assert result.success is False
        assert "No webhook_url or bot_token configured" in result.error
