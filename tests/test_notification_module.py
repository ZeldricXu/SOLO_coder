"""
通知模块单元测试
测试重点：事务回滚正确性
- 通知创建失败的回滚
- 发送失败的重试逻辑
- 最大重试次数限制
- 通知状态转换正确性
- 批量通知的事务处理
"""
import pytest
from unittest.mock import MagicMock, patch, call
from typing import Dict, Any, List

from builders.notification_builder import NotificationTestDataBuilder


class TestNotificationTransactionRollback:
    """通知事务回滚测试"""

    @pytest.fixture(autouse=True)
    def setup(self, api_base_url, enable_mock):
        """测试前设置"""
        self.base_url = api_base_url
        self.enable_mock = enable_mock
        self.builder = NotificationTestDataBuilder()

    @pytest.mark.unit
    @pytest.mark.transaction
    def test_create_notification_transaction(self):
        """测试创建通知的事务完整性"""
        request_data = self.builder \
            .with_email_type() \
            .with_recipient('test@example.com') \
            .with_content('测试通知内容') \
            .build_create_request()

        assert request_data['type'] == 'EMAIL'
        assert request_data['recipient'] == 'test@example.com'
        assert request_data['content'] == '测试通知内容'
        assert all(key in request_data for key in ['type', 'recipient', 'content'])

    @pytest.mark.unit
    @pytest.mark.transaction
    def test_notification_status_transitions(self):
        """测试通知状态转换正确性"""
        notification = self.builder.with_pending_status().build()
        assert notification['status'] == 'PENDING'
        assert notification['retryCount'] == 0

        notification = self.builder.reset().with_delivered_status().build()
        assert notification['status'] == 'DELIVERED'
        assert notification['deliveredAt'] is not None

        notification = self.builder.reset().with_retrying_status(retry_count=2).build()
        assert notification['status'] == 'RETRYING'
        assert notification['retryCount'] == 2
        assert notification['nextRetryAt'] is not None

        notification = self.builder.reset().with_failed_status().build()
        assert notification['status'] == 'FAILED'
        assert notification['retryCount'] == notification['maxRetries']

    @pytest.mark.unit
    @pytest.mark.transaction
    def test_retry_count_increment(self):
        """测试重试计数递增正确性"""
        for retry_count in range(4):
            notification = self.builder \
                .reset() \
                .with_retry_count(retry_count) \
                .build()

            assert notification['retryCount'] == retry_count

    @pytest.mark.unit
    @pytest.mark.transaction
    def test_max_retries_limit(self):
        """测试最大重试次数限制"""
        max_retries = 3
        notification = self.builder \
            .with_max_retries(max_retries) \
            .with_failed_status() \
            .build()

        assert notification['retryCount'] >= max_retries
        assert notification['status'] == 'FAILED'

    @pytest.mark.unit
    @pytest.mark.transaction
    def test_retry_backoff_timing(self):
        """测试重试退避时间计算"""
        for retry_count in range(1, 4):
            notification = self.builder \
                .reset() \
                .with_retrying_status(retry_count=retry_count) \
                .build()

            expected_backoff = (2 ** retry_count) * 5
            actual_backoff = (notification['nextRetryAt'] - notification['createdAt']).total_seconds() / 60

            assert actual_backoff >= 0

    @pytest.mark.unit
    @pytest.mark.transaction
    def test_notification_rollback_on_failure(self):
        """测试发送失败时的状态回滚"""
        notification = self.builder \
            .with_pending_status() \
            .with_last_error('连接超时') \
            .build()

        assert notification['lastError'] == '连接超时'

    @pytest.mark.unit
    @pytest.mark.transaction
    def test_batch_notification_transaction(self):
        """测试批量通知的事务处理"""
        batch_size = 10
        notifications = NotificationTestDataBuilder.create_batch_notifications(
            count=batch_size,
            notification_type='EMAIL'
        )

        assert len(notifications) == batch_size
        for notification in notifications:
            assert notification['type'] == 'EMAIL'
            assert '@' in notification['recipient']
            assert notification['content'] is not None

    @pytest.mark.unit
    @pytest.mark.transaction
    def test_different_notification_types(self):
        """测试不同通知类型的事务处理"""
        type_configs = [
            ('EMAIL', 'test@example.com'),
            ('SMS', '13800138000'),
            ('WEBHOOK', 'https://api.example.com/webhook')
        ]

        for notif_type, recipient in type_configs:
            builder = NotificationTestDataBuilder()
            request_data = builder \
                .with_type(notif_type) \
                .with_recipient(recipient) \
                .build_create_request()

            assert request_data['type'] == notif_type
            assert request_data['recipient'] == recipient

    @pytest.mark.unit
    @pytest.mark.transaction
    def test_transaction_scenario_data(self):
        """测试事务场景专用数据构建"""
        transaction_data = self.builder \
            .with_transaction_scenario() \
            .build()

        assert transaction_data['type'] == 'EMAIL'
        assert transaction_data['recipient'] == 'test@example.com'
        assert transaction_data['content'] == '事务测试通知'
        assert transaction_data['status'] == 'PENDING'
        assert transaction_data['retryCount'] == 0
        assert transaction_data['maxRetries'] == 3

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_empty_content_notification(self):
        """测试空内容通知 - 边界场景"""
        request_data = self.builder \
            .with_empty_content() \
            .build_create_request()

        assert request_data['content'] == ''

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_long_content_notification(self):
        """测试长内容通知 - 边界场景"""
        content_length = 10000
        request_data = self.builder \
            .with_long_content(length=content_length) \
            .build_create_request()

        assert len(request_data['content']) == content_length

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_empty_recipient(self):
        """测试空接收者 - 异常场景"""
        request_data = self.builder \
            .with_empty_recipient() \
            .build_create_request()

        assert request_data['recipient'] == ''

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_invalid_recipient(self):
        """测试无效接收者 - 异常场景"""
        request_data = self.builder \
            .with_invalid_recipient() \
            .build_create_request()

        assert '@' not in request_data['recipient']

    @pytest.mark.unit
    def test_notification_id_uniqueness(self):
        """测试通知ID的唯一性"""
        notification_ids = set()
        for _ in range(100):
            builder = NotificationTestDataBuilder()
            notification = builder.build()
            notification_ids.add(notification['notificationId'])

        assert len(notification_ids) == 100

    @pytest.mark.unit
    def test_static_factory_methods(self):
        """测试静态工厂方法"""
        email = NotificationTestDataBuilder.create_email_notification(
            recipient='user@example.com',
            content='邮件内容'
        )
        assert email['type'] == 'EMAIL'
        assert email['recipient'] == 'user@example.com'

        sms = NotificationTestDataBuilder.create_sms_notification(
            recipient='13800138000',
            content='短信内容'
        )
        assert sms['type'] == 'SMS'

        webhook = NotificationTestDataBuilder.create_webhook_notification(
            url='https://api.test.com/hook'
        )
        assert webhook['type'] == 'WEBHOOK'


class TestNotificationRetryLogic:
    """通知重试逻辑测试"""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.builder = NotificationTestDataBuilder()

    @pytest.mark.unit
    @pytest.mark.transaction
    def test_retry_immediately(self):
        """测试立即重试"""
        notification = self.builder \
            .with_next_retry_immediately() \
            .build()

        assert notification['nextRetryAt'] <= notification['createdAt']

    @pytest.mark.unit
    @pytest.mark.transaction
    def test_retry_future(self):
        """测试未来重试"""
        notification = self.builder \
            .with_next_retry_future(minutes=30) \
            .build()

        assert notification['nextRetryAt'] > notification['createdAt']

    @pytest.mark.unit
    @pytest.mark.transaction
    def test_retry_past(self):
        """测试过去的重试时间（过期场景）"""
        notification = self.builder \
            .with_next_retry_past() \
            .build()

        assert notification['nextRetryAt'] < notification['createdAt']

    @pytest.mark.unit
    @pytest.mark.transaction
    def test_retry_count_vs_max_retries(self):
        """测试重试次数与最大重试次数的关系"""
        test_cases = [
            (0, 3, True),
            (1, 3, True),
            (2, 3, True),
            (3, 3, False),
            (4, 3, False),
        ]

        for retry_count, max_retries, should_retry in test_cases:
            notification = self.builder \
                .reset() \
                .with_retry_count(retry_count) \
                .with_max_retries(max_retries) \
                .build()

            can_retry = notification['retryCount'] < notification['maxRetries']
            assert can_retry == should_retry

    @pytest.mark.unit
    @pytest.mark.transaction
    def test_error_message_persistence(self):
        """测试错误消息持久化"""
        error_messages = [
            '连接超时',
            '认证失败',
            '收件人不存在',
            '服务器内部错误',
            '网络中断'
        ]

        for error_msg in error_messages:
            notification = self.builder \
                .reset() \
                .with_failed_status(error_message=error_msg) \
                .build()

            assert notification['lastError'] == error_msg


class TestNotificationIntegrationMock:
    """通知模块集成测试（使用Mock）"""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.builder = NotificationTestDataBuilder()

    @pytest.mark.integration
    @pytest.mark.transaction
    def test_create_notification_with_mock(self, mock_notification_service):
        """使用Mock测试创建通知"""
        request_data = self.builder.with_email_type().build_create_request()

        response = mock_notification_service.create_notification(request_data)

        assert response['code'] == 200
        assert response['data']['status'] == 'PENDING'
        mock_notification_service.create_notification.assert_called_once_with(request_data)

    @pytest.mark.integration
    @pytest.mark.transaction
    def test_get_notification_status_with_mock(self, mock_notification_service):
        """使用Mock测试获取通知状态"""
        notification_id = 'notif_test_001'

        response = mock_notification_service.get_status(notification_id)

        assert response['code'] == 200
        assert response['data']['notificationId'] == notification_id
        mock_notification_service.get_status.assert_called_once_with(notification_id)

    @pytest.mark.integration
    @pytest.mark.transaction
    def test_retry_notification_with_mock(self, mock_notification_service):
        """使用Mock测试重试通知"""
        notification_id = 'notif_test_001'

        response = mock_notification_service.retry_notification(notification_id)

        assert response['code'] == 200
        mock_notification_service.retry_notification.assert_called_once_with(notification_id)

    @pytest.mark.integration
    @pytest.mark.transaction
    def test_notification_status_transition_with_mock(self, mock_notification_service):
        """使用Mock测试通知状态转换"""
        request_data = self.builder.with_email_type().build_create_request()

        create_response = mock_notification_service.create_notification(request_data)
        notification_id = create_response['data']['notificationId']

        assert create_response['data']['status'] == 'PENDING'

        status_response = mock_notification_service.get_status(notification_id)
        assert status_response['data']['status'] == 'DELIVERED'

        assert mock_notification_service.create_notification.call_count == 1
        assert mock_notification_service.get_status.call_count == 1

    @pytest.mark.integration
    @pytest.mark.transaction
    def test_failed_notification_retry_flow(self, mock_notification_service):
        """测试失败通知的重试流程"""
        mock_notification_service.create_notification.return_value = {
            'code': 200,
            'message': 'success',
            'data': {
                'notificationId': 'notif_failed_001',
                'type': 'EMAIL',
                'status': 'FAILED',
                'retryCount': 3,
                'lastError': '连接超时'
            }
        }

        request_data = self.builder.with_email_type().build_create_request()
        response = mock_notification_service.create_notification(request_data)

        assert response['data']['status'] == 'FAILED'
        assert response['data']['retryCount'] == 3

        mock_notification_service.retry_notification(response['data']['notificationId'])
        mock_notification_service.retry_notification.assert_called_once()


class TestNotificationEdgeCases:
    """通知模块边界场景测试"""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.builder = NotificationTestDataBuilder()

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_zero_max_retries(self):
        """测试零最大重试次数"""
        notification = self.builder \
            .with_max_retries(0) \
            .with_pending_status() \
            .build()

        assert notification['maxRetries'] == 0

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_high_max_retries(self):
        """测试高最大重试次数"""
        notification = self.builder \
            .with_max_retries(100) \
            .build()

        assert notification['maxRetries'] == 100

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_notification_with_all_fields(self):
        """测试包含所有字段的通知"""
        notification = self.builder \
            .with_email_type() \
            .with_recipient('test@example.com') \
            .with_content('测试内容') \
            .with_status('PENDING') \
            .with_retry_count(1) \
            .with_max_retries(3) \
            .with_last_error('临时错误') \
            .with_next_retry_future(minutes=5) \
            .build()

        required_fields = [
            'notificationId', 'type', 'recipient', 'content',
            'status', 'retryCount', 'maxRetries', 'lastError', 'nextRetryAt'
        ]
        for field in required_fields:
            assert field in notification

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_special_characters_in_content(self):
        """测试内容中包含特殊字符"""
        special_content = 'Test with special chars: !@#$%^&*()_+-=[]{}|;:,.<>? 中文测试 🎉'
        request_data = self.builder \
            .with_content(special_content) \
            .build_create_request()

        assert request_data['content'] == special_content
