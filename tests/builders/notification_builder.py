"""
通知模块测试数据构建器
负责构造通知相关的测试数据，包括：
- 各种通知类型（EMAIL、SMS、WEBHOOK）
- 不同状态的通知（PENDING、DELIVERED、FAILED、RETRYING）
- 事务回滚相关的边界场景
"""
from typing import Any, Dict, List
from .base_builder import BaseTestDataBuilder
from datetime import datetime, timedelta
import uuid


class NotificationTestDataBuilder(BaseTestDataBuilder[Dict[str, Any]]):
    """通知模块测试数据构建器"""

    NOTIFICATION_TYPES = ['EMAIL', 'SMS', 'WEBHOOK']
    NOTIFICATION_STATUSES = ['PENDING', 'DELIVERED', 'FAILED', 'RETRYING']

    def _reset(self) -> None:
        """重置构建器状态"""
        self._data = {
            'notificationId': f"notif_{uuid.uuid4().hex[:8]}",
            'type': 'EMAIL',
            'recipient': self._fake.email(),
            'content': self._fake.sentence(),
            'status': 'PENDING',
            'retryCount': 0,
            'maxRetries': 3,
            'nextRetryAt': None,
            'lastError': None,
            'deliveredAt': None,
            'createdAt': datetime.now(),
            'updatedAt': datetime.now()
        }

    def with_notification_id(self, notification_id: str) -> 'NotificationTestDataBuilder':
        """设置通知ID"""
        self._data['notificationId'] = notification_id
        return self

    def with_type(self, notification_type: str) -> 'NotificationTestDataBuilder':
        """设置通知类型"""
        assert notification_type in self.NOTIFICATION_TYPES, f"无效的通知类型: {notification_type}"
        self._data['type'] = notification_type
        return self

    def with_email_type(self) -> 'NotificationTestDataBuilder':
        """设置为邮件类型"""
        self._data['type'] = 'EMAIL'
        self._data['recipient'] = self._fake.email()
        return self

    def with_sms_type(self) -> 'NotificationTestDataBuilder':
        """设置为短信类型"""
        self._data['type'] = 'SMS'
        self._data['recipient'] = self._fake.phone_number()
        return self

    def with_webhook_type(self) -> 'NotificationTestDataBuilder':
        """设置为Webhook类型"""
        self._data['type'] = 'WEBHOOK'
        self._data['recipient'] = f"https://{self._fake.domain_name()}/webhook"
        return self

    def with_recipient(self, recipient: str) -> 'NotificationTestDataBuilder':
        """设置接收者"""
        self._data['recipient'] = recipient
        return self

    def with_content(self, content: str) -> 'NotificationTestDataBuilder':
        """设置通知内容"""
        self._data['content'] = content
        return self

    def with_long_content(self, length: int = 10000) -> 'NotificationTestDataBuilder':
        """设置长内容（边界场景）"""
        self._data['content'] = 'x' * length
        return self

    def with_empty_content(self) -> 'NotificationTestDataBuilder':
        """设置空内容（边界场景）"""
        self._data['content'] = ''
        return self

    def with_status(self, status: str) -> 'NotificationTestDataBuilder':
        """设置通知状态"""
        assert status in self.NOTIFICATION_STATUSES, f"无效的状态: {status}"
        self._data['status'] = status
        return self

    def with_pending_status(self) -> 'NotificationTestDataBuilder':
        """设置为待发送状态"""
        self._data['status'] = 'PENDING'
        self._data['retryCount'] = 0
        self._data['nextRetryAt'] = datetime.now()
        self._data['deliveredAt'] = None
        return self

    def with_delivered_status(self) -> 'NotificationTestDataBuilder':
        """设置为已送达状态"""
        self._data['status'] = 'DELIVERED'
        self._data['retryCount'] = 0
        self._data['deliveredAt'] = datetime.now()
        self._data['nextRetryAt'] = None
        return self

    def with_failed_status(self, error_message: str = "发送失败") -> 'NotificationTestDataBuilder':
        """设置为失败状态"""
        self._data['status'] = 'FAILED'
        self._data['retryCount'] = self._data.get('maxRetries', 3)
        self._data['lastError'] = error_message
        self._data['nextRetryAt'] = None
        return self

    def with_retrying_status(self, retry_count: int = 1) -> 'NotificationTestDataBuilder':
        """设置为重试中状态"""
        self._data['status'] = 'RETRYING'
        self._data['retryCount'] = retry_count
        self._data['nextRetryAt'] = datetime.now() + timedelta(minutes=5)
        return self

    def with_max_retries(self, max_retries: int) -> 'NotificationTestDataBuilder':
        """设置最大重试次数"""
        self._data['maxRetries'] = max_retries
        return self

    def with_retry_count(self, retry_count: int) -> 'NotificationTestDataBuilder':
        """设置当前重试次数"""
        self._data['retryCount'] = retry_count
        return self

    def with_last_error(self, error_message: str) -> 'NotificationTestDataBuilder':
        """设置上次错误信息"""
        self._data['lastError'] = error_message
        return self

    def with_next_retry_immediately(self) -> 'NotificationTestDataBuilder':
        """设置下次立即重试"""
        self._data['nextRetryAt'] = datetime.now()
        return self

    def with_next_retry_future(self, minutes: int = 30) -> 'NotificationTestDataBuilder':
        """设置未来重试"""
        self._data['nextRetryAt'] = datetime.now() + timedelta(minutes=minutes)
        return self

    def with_next_retry_past(self) -> 'NotificationTestDataBuilder':
        """设置过去的重试时间（过期场景）"""
        self._data['nextRetryAt'] = datetime.now() - timedelta(minutes=30)
        return self

    def with_transaction_scenario(self) -> 'NotificationTestDataBuilder':
        """设置事务回滚测试场景"""
        self._data['type'] = 'EMAIL'
        self._data['recipient'] = 'test@example.com'
        self._data['content'] = '事务测试通知'
        self._data['status'] = 'PENDING'
        self._data['retryCount'] = 0
        self._data['maxRetries'] = 3
        return self

    def with_invalid_recipient(self) -> 'NotificationTestDataBuilder':
        """设置无效的接收者（异常场景）"""
        self._data['type'] = 'EMAIL'
        self._data['recipient'] = 'invalid-email'
        return self

    def with_empty_recipient(self) -> 'NotificationTestDataBuilder':
        """设置空接收者（异常场景）"""
        self._data['recipient'] = ''
        return self

    def build_create_request(self) -> Dict[str, Any]:
        """构建创建通知的API请求体"""
        return {
            'type': self._data['type'],
            'recipient': self._data['recipient'],
            'content': self._data['content']
        }

    def build(self) -> Dict[str, Any]:
        """构建完整的通知实体数据"""
        return dict(self._data)

    @staticmethod
    def create_email_notification(recipient: str = None, content: str = None) -> Dict[str, Any]:
        """静态工厂：创建邮件通知"""
        builder = NotificationTestDataBuilder()
        if recipient:
            builder.with_recipient(recipient)
        if content:
            builder.with_content(content)
        return builder.with_email_type().build_create_request()

    @staticmethod
    def create_sms_notification(recipient: str = None, content: str = None) -> Dict[str, Any]:
        """静态工厂：创建短信通知"""
        builder = NotificationTestDataBuilder()
        if recipient:
            builder.with_recipient(recipient)
        if content:
            builder.with_content(content)
        return builder.with_sms_type().build_create_request()

    @staticmethod
    def create_webhook_notification(url: str = None, content: str = None) -> Dict[str, Any]:
        """静态工厂：创建Webhook通知"""
        builder = NotificationTestDataBuilder()
        if url:
            builder.with_recipient(url)
        if content:
            builder.with_content(content)
        return builder.with_webhook_type().build_create_request()

    @staticmethod
    def create_batch_notifications(count: int, notification_type: str = 'EMAIL') -> List[Dict[str, Any]]:
        """静态工厂：批量创建通知"""
        notifications = []
        for _ in range(count):
            builder = NotificationTestDataBuilder()
            builder.with_type(notification_type)
            notifications.append(builder.build_create_request())
        return notifications
