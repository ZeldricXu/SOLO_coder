from typing import List, Dict, Optional
from dataclasses import dataclass, field
from qabot.models import (
    Knowledge, KnowledgeCreate, KnowledgeUpdate,
    QARecord, QARecordCreate,
    ReplyTemplate, ReplyTemplateCreate,
    Intent, IntentCreate,
    QAStats
)


@dataclass
class TestDataBuilder:
    _knowledge_counter: int = 0
    _qa_counter: int = 0
    _template_counter: int = 0
    _intent_counter: int = 0
    
    def reset(self):
        self._knowledge_counter = 0
        self._qa_counter = 0
        self._template_counter = 0
        self._intent_counter = 0
    
    def build_knowledge_create(
        self,
        title: Optional[str] = None,
        content: Optional[str] = None,
        category: Optional[str] = None,
        tags: Optional[List[str]] = None,
        keywords: Optional[List[str]] = None,
        related: Optional[List[str]] = None
    ) -> KnowledgeCreate:
        self._knowledge_counter += 1
        return KnowledgeCreate(
            knowledge_title=title or f"测试知识标题_{self._knowledge_counter}",
            knowledge_content=content or f"这是测试知识的详细内容_{self._knowledge_counter}。该知识包含了完整的问题解答步骤和相关说明。",
            knowledge_category=category or "测试分类",
            knowledge_tags=tags or [f"标签_{self._knowledge_counter}"],
            knowledge_keywords=keywords or [f"关键词_{self._knowledge_counter}"]
        )
    
    def build_knowledge(
        self,
        knowledge_id: Optional[str] = None,
        title: Optional[str] = None,
        content: Optional[str] = None,
        category: Optional[str] = None,
        tags: Optional[List[str]] = None,
        keywords: Optional[List[str]] = None,
        view_count: int = 0,
        related: Optional[List[str]] = None,
        needs_update: bool = False
    ) -> Knowledge:
        create_data = self.build_knowledge_create(title, content, category, tags, keywords)
        return Knowledge(
            knowledge_id=knowledge_id or f"knowledge_test_{self._knowledge_counter}",
            knowledge_title=create_data.knowledge_title,
            knowledge_content=create_data.knowledge_content,
            knowledge_category=create_data.knowledge_category,
            knowledge_tags=create_data.knowledge_tags,
            knowledge_keywords=create_data.knowledge_keywords,
            view_count=view_count,
            related_knowledge=related or [],
            needs_update=needs_update,
            created_at="2026-05-10T00:00:00Z",
            updated_at="2026-05-10T00:00:00Z"
        )
    
    def build_password_reset_knowledge(self, knowledge_id: Optional[str] = None) -> Knowledge:
        return Knowledge(
            knowledge_id=knowledge_id or "knowledge_password_reset",
            knowledge_title="如何重置密码",
            knowledge_content="请访问账户设置页面，点击'安全设置'，选择'修改密码'，输入当前密码后设置新密码。如果忘记密码，可以在登录页面点击'忘记密码'，通过邮箱或手机号验证后重置。",
            knowledge_category="账户管理",
            knowledge_tags=["密码", "账户"],
            knowledge_keywords=["重置密码", "忘记密码", "修改密码", "找回密码"],
            view_count=150,
            related_knowledge=["knowledge_login"],
            created_at="2026-05-10T00:00:00Z",
            updated_at="2026-05-10T00:00:00Z"
        )
    
    def build_login_knowledge(self, knowledge_id: Optional[str] = None) -> Knowledge:
        return Knowledge(
            knowledge_id=knowledge_id or "knowledge_login",
            knowledge_title="如何登录账户",
            knowledge_content="访问登录页面，输入您的用户名/邮箱和密码，点击登录按钮。如果启用了双重验证，请输入验证码。",
            knowledge_category="账户管理",
            knowledge_tags=["登录", "账户"],
            knowledge_keywords=["登录", "登录账户", "用户登录"],
            view_count=120,
            related_knowledge=["knowledge_password_reset"],
            created_at="2026-05-10T00:00:00Z",
            updated_at="2026-05-10T00:00:00Z"
        )
    
    def build_refund_knowledge(self, knowledge_id: Optional[str] = None) -> Knowledge:
        return Knowledge(
            knowledge_id=knowledge_id or "knowledge_refund",
            knowledge_title="如何申请退款",
            knowledge_content="请进入订单管理页面，找到需要退款的订单，点击'申请退款'按钮，填写退款原因后提交。退款审核通常需要1-3个工作日。",
            knowledge_category="支付管理",
            knowledge_tags=["退款", "支付"],
            knowledge_keywords=["退款", "申请退款", "订单退款"],
            view_count=80,
            related_knowledge=[],
            created_at="2026-05-10T00:00:00Z",
            updated_at="2026-05-10T00:00:00Z"
        )
    
    def build_payment_knowledge(self, knowledge_id: Optional[str] = None) -> Knowledge:
        return Knowledge(
            knowledge_id=knowledge_id or "knowledge_payment",
            knowledge_title="支持哪些支付方式",
            knowledge_content="我们支持支付宝、微信支付、银行卡支付等多种支付方式。请在支付页面选择您偏好的支付方式完成支付。",
            knowledge_category="支付管理",
            knowledge_tags=["支付", "支付方式"],
            knowledge_keywords=["支付方式", "付款方式", "如何支付"],
            view_count=60,
            related_knowledge=[],
            created_at="2026-05-10T00:00:00Z",
            updated_at="2026-05-10T00:00:00Z"
        )
    
    def build_customer_service_knowledge(self, knowledge_id: Optional[str] = None) -> Knowledge:
        return Knowledge(
            knowledge_id=knowledge_id or "knowledge_customer_service",
            knowledge_title="如何联系客服",
            knowledge_content="您可以通过以下方式联系客服：1. 在线客服：点击页面右下角的客服图标；2. 电话客服：拨打400-xxx-xxxx；3. 邮箱客服：service@example.com。",
            knowledge_category="服务支持",
            knowledge_tags=["客服", "联系"],
            knowledge_keywords=["联系客服", "人工客服", "客服电话"],
            view_count=200,
            related_knowledge=[],
            created_at="2026-05-10T00:00:00Z",
            updated_at="2026-05-10T00:00:00Z"
        )
    
    def build_high_view_knowledge(self, knowledge_id: str, view_count: int) -> Knowledge:
        return Knowledge(
            knowledge_id=knowledge_id,
            knowledge_title=f"热门知识_{knowledge_id}",
            knowledge_content=f"这是热门知识的详细内容，浏览量为{view_count}。",
            knowledge_category="热门分类",
            knowledge_tags=["热门"],
            knowledge_keywords=[f"热门关键词_{knowledge_id}"],
            view_count=view_count,
            related_knowledge=[],
            created_at="2026-05-10T00:00:00Z",
            updated_at="2026-05-10T00:00:00Z"
        )
    
    def build_large_knowledge_set(self, count: int) -> List[Knowledge]:
        knowledges = []
        for i in range(count):
            knowledge = Knowledge(
                knowledge_id=f"knowledge_batch_{i}",
                knowledge_title=f"批量知识标题_{i}",
                knowledge_content=f"这是批量知识的详细内容_{i}。包含了关于产品功能、使用方法、常见问题等内容。",
                knowledge_category=f"分类_{i % 5}",
                knowledge_tags=[f"标签_{i}"],
                knowledge_keywords=[f"关键词_{i}", f"搜索词_{i}"],
                view_count=i * 10,
                related_knowledge=[],
                created_at="2026-05-10T00:00:00Z",
                updated_at="2026-05-10T00:00:00Z"
            )
            knowledges.append(knowledge)
        return knowledges
    
    def build_qa_record_create(
        self,
        user_id: Optional[str] = None,
        question: Optional[str] = None
    ) -> QARecordCreate:
        self._qa_counter += 1
        return QARecordCreate(
            user_id=user_id or f"user_test_{self._qa_counter}",
            question=question or f"测试问题_{self._qa_counter}"
        )
    
    def build_qa_record(
        self,
        qa_id: Optional[str] = None,
        user_id: Optional[str] = None,
        question: Optional[str] = None,
        matched_knowledge: Optional[str] = None,
        reply_content: Optional[str] = None,
        reply_type: Optional[str] = None,
        match_score: Optional[float] = None,
        intent_category: Optional[str] = None,
        satisfaction: Optional[int] = None
    ) -> QARecord:
        create_data = self.build_qa_record_create(user_id, question)
        return QARecord(
            qa_id=qa_id or f"qa_test_{self._qa_counter}",
            user_id=create_data.user_id,
            question=create_data.question,
            matched_knowledge=matched_knowledge,
            reply_content=reply_content or "这是测试回复内容",
            reply_type=reply_type or "knowledge_match",
            match_score=match_score,
            intent_category=intent_category,
            satisfaction=satisfaction,
            created_at="2026-05-10T00:00:00Z"
        )
    
    def build_default_template(self) -> ReplyTemplate:
        return ReplyTemplate(
            template_id="template_default",
            template_name="默认回复模板",
            template_content="抱歉，未能找到匹配答案。您可以尝试换一种方式提问，或联系客服获取帮助。",
            template_type="default",
            created_at="2026-05-10T00:00:00Z"
        )
    
    def build_general_template(self) -> ReplyTemplate:
        return ReplyTemplate(
            template_id="template_general",
            template_name="通用回复模板",
            template_content="感谢您的提问，我来为您解答：{content}",
            template_type="general",
            created_at="2026-05-10T00:00:00Z"
        )
    
    def build_custom_template(self, template_id: str, name: str, content: str, template_type: str = "custom") -> ReplyTemplate:
        return ReplyTemplate(
            template_id=template_id,
            template_name=name,
            template_content=content,
            template_type=template_type,
            created_at="2026-05-10T00:00:00Z"
        )
    
    def build_account_intent(self) -> Intent:
        return Intent(
            intent_id="intent_account",
            intent_name="账户问题",
            intent_keywords=["账户", "密码", "登录", "注册", "忘记密码", "重置密码"],
            intent_category="account"
        )
    
    def build_payment_intent(self) -> Intent:
        return Intent(
            intent_id="intent_payment",
            intent_name="支付问题",
            intent_keywords=["支付", "付款", "订单", "退款", "金额"],
            intent_category="payment"
        )
    
    def build_product_intent(self) -> Intent:
        return Intent(
            intent_id="intent_product",
            intent_name="产品问题",
            intent_keywords=["产品", "功能", "使用", "操作", "设置"],
            intent_category="product"
        )
    
    def build_service_intent(self) -> Intent:
        return Intent(
            intent_id="intent_service",
            intent_name="服务问题",
            intent_keywords=["客服", "人工", "服务", "投诉"],
            intent_category="service"
        )
    
    def build_custom_intent(self, intent_id: str, name: str, keywords: List[str], category: str) -> Intent:
        return Intent(
            intent_id=intent_id,
            intent_name=name,
            intent_keywords=keywords,
            intent_category=category
        )
    
    def build_all_default_intents(self) -> List[Intent]:
        return [
            self.build_account_intent(),
            self.build_payment_intent(),
            self.build_product_intent(),
            self.build_service_intent()
        ]
    
    def build_standard_knowledge_base(self) -> List[Knowledge]:
        return [
            self.build_password_reset_knowledge(),
            self.build_login_knowledge(),
            self.build_refund_knowledge(),
            self.build_payment_knowledge(),
            self.build_customer_service_knowledge()
        ]
    
    def get_test_questions(self) -> Dict[str, str]:
        return {
            "password_reset": "忘记密码怎么办",
            "login": "如何登录账户",
            "refund": "我要申请退款",
            "payment": "支持什么支付方式",
            "service": "怎么联系客服",
            "general": "这是一个测试问题",
            "password_login": "忘记密码怎么登录"
        }
    
    def get_expected_intents(self) -> Dict[str, str]:
        return {
            "忘记密码怎么办": "account",
            "如何登录账户": "account",
            "我要申请退款": "payment",
            "支持什么支付方式": "payment",
            "怎么联系客服": "service",
            "这是一个测试问题": "general",
            "产品功能有哪些": "product"
        }


builder = TestDataBuilder()

__all__ = ["TestDataBuilder", "builder"]
