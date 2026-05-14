import uuid
from datetime import datetime, timezone
from typing import Dict, List, Optional
from collections import defaultdict

from .base import (
    Knowledge, KnowledgeCreate, KnowledgeUpdate,
    QARecord, QARecordCreate,
    ReplyTemplate, ReplyTemplateCreate,
    Intent, IntentCreate,
    QAStats, QAStatsBase,
    RecommendRecord
)


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:8]}"


def get_today_str() -> str:
    return datetime.now().strftime("%Y-%m-%d")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class IndexMetadata:
    def __init__(self):
        self.last_update_time: Dict[str, str] = {}
        self.update_count: Dict[str, int] = defaultdict(int)
        self.activity_score: Dict[str, int] = defaultdict(int)
    
    def record_update(self, knowledge_id: str):
        self.last_update_time[knowledge_id] = utc_now()
        self.update_count[knowledge_id] += 1
    
    def record_activity(self, knowledge_id: str):
        self.activity_score[knowledge_id] += 1
    
    def get_last_update(self, knowledge_id: str) -> Optional[str]:
        return self.last_update_time.get(knowledge_id)
    
    def get_activity_score(self, knowledge_id: str) -> int:
        return self.activity_score.get(knowledge_id, 0)


class FeedbackAnalysisData:
    def __init__(self):
        self.feedback_history: List[Dict] = []
        self.knowledge_feedback_count: Dict[str, int] = defaultdict(int)
        self.knowledge_low_satisfaction_count: Dict[str, int] = defaultdict(int)
        self.question_keyword_frequency: Dict[str, Dict[str, int]] = defaultdict(lambda: defaultdict(int))
        self.optimization_history: List[Dict] = []
    
    def record_feedback(self, qa_record: QARecord, satisfaction: int):
        record = {
            "qa_id": qa_record.qa_id,
            "question": qa_record.question,
            "matched_knowledge": qa_record.matched_knowledge,
            "satisfaction": satisfaction,
            "timestamp": utc_now()
        }
        self.feedback_history.append(record)
        
        if qa_record.matched_knowledge:
            self.knowledge_feedback_count[qa_record.matched_knowledge] += 1
            if satisfaction <= 2:
                self.knowledge_low_satisfaction_count[qa_record.matched_knowledge] += 1
                keywords = self._extract_keywords(qa_record.question)
                for kw in keywords:
                    self.question_keyword_frequency[qa_record.matched_knowledge][kw] += 1
    
    def _extract_keywords(self, question: str) -> List[str]:
        stop_words = {"怎么", "如何", "为什么", "什么", "是", "的", "了", "吗", "呢", "啊", "呀", "吧", "我", "你", "他", "她"}
        keywords = []
        for word in question.split():
            if word and word not in stop_words and len(word) > 1:
                keywords.append(word)
        return keywords
    
    def get_low_satisfaction_keywords(self, knowledge_id: str, min_frequency: int = 2) -> List[str]:
        freq = self.question_keyword_frequency.get(knowledge_id, {})
        return [kw for kw, count in freq.items() if count >= min_frequency]
    
    def get_low_satisfaction_knowledge(self, min_count: int = 3) -> List[str]:
        result = []
        for kid, low_count in self.knowledge_low_satisfaction_count.items():
            total = self.knowledge_feedback_count.get(kid, 0)
            if low_count >= min_count and total > 0:
                if low_count / total >= 0.3:
                    result.append(kid)
        return result
    
    def record_optimization(self, knowledge_id: str, added_keywords: List[str], removed_keywords: List[str]):
        record = {
            "knowledge_id": knowledge_id,
            "added_keywords": added_keywords,
            "removed_keywords": removed_keywords,
            "timestamp": utc_now()
        }
        self.optimization_history.append(record)


class Database:
    _instance = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._init_storage()
        return cls._instance
    
    def _init_storage(self):
        self.knowledges: Dict[str, Knowledge] = {}
        self.qa_records: Dict[str, QARecord] = {}
        self.reply_templates: Dict[str, ReplyTemplate] = {}
        self.intents: Dict[str, Intent] = {}
        self.stats: Dict[str, QAStats] = {}
        self.recommends: Dict[str, RecommendRecord] = {}
        
        self.index_metadata: IndexMetadata = IndexMetadata()
        self.feedback_data: FeedbackAnalysisData = FeedbackAnalysisData()
        
        self._initialize_default_data()
    
    def _initialize_default_data(self):
        default_template = ReplyTemplate(
            template_id="template_default",
            template_name="默认回复模板",
            template_content="抱歉，未能找到匹配答案。您可以尝试换一种方式提问，或联系客服获取帮助。",
            template_type="default"
        )
        self.reply_templates[default_template.template_id] = default_template
        
        general_template = ReplyTemplate(
            template_id="template_general",
            template_name="通用回复模板",
            template_content="感谢您的提问，我来为您解答：{content}",
            template_type="general"
        )
        self.reply_templates[general_template.template_id] = general_template
        
        hybrid_template = ReplyTemplate(
            template_id="template_hybrid",
            template_name="混合回复模板",
            template_content="根据您的问题，为您找到以下解答：\n\n{content}\n\n如需更多帮助，请联系客服。",
            template_type="hybrid"
        )
        self.reply_templates[hybrid_template.template_id] = hybrid_template
        
        default_intents = [
            Intent(
                intent_id="intent_account",
                intent_name="账户问题",
                intent_keywords=["账户", "密码", "登录", "注册", "忘记密码", "重置密码"],
                intent_category="account"
            ),
            Intent(
                intent_id="intent_payment",
                intent_name="支付问题",
                intent_keywords=["支付", "付款", "订单", "退款", "金额"],
                intent_category="payment"
            ),
            Intent(
                intent_id="intent_product",
                intent_name="产品问题",
                intent_keywords=["产品", "功能", "使用", "操作", "设置"],
                intent_category="product"
            ),
            Intent(
                intent_id="intent_service",
                intent_name="服务问题",
                intent_keywords=["客服", "人工", "服务", "投诉"],
                intent_category="service"
            )
        ]
        for intent in default_intents:
            self.intents[intent.intent_id] = intent
        
        sample_knowledge = [
            {
                "title": "如何重置密码",
                "content": "请访问账户设置页面，点击'安全设置'，选择'修改密码'，输入当前密码后设置新密码。如果忘记密码，可以在登录页面点击'忘记密码'，通过邮箱或手机号验证后重置。",
                "category": "账户管理",
                "tags": ["密码", "账户"],
                "keywords": ["重置密码", "忘记密码", "修改密码", "找回密码"],
                "related": []
            },
            {
                "title": "如何登录账户",
                "content": "访问登录页面，输入您的用户名/邮箱和密码，点击登录按钮。如果启用了双重验证，请输入验证码。",
                "category": "账户管理",
                "tags": ["登录", "账户"],
                "keywords": ["登录", "登录账户", "用户登录"],
                "related": []
            },
            {
                "title": "如何申请退款",
                "content": "请进入订单管理页面，找到需要退款的订单，点击'申请退款'按钮，填写退款原因后提交。退款审核通常需要1-3个工作日。",
                "category": "支付管理",
                "tags": ["退款", "支付"],
                "keywords": ["退款", "申请退款", "订单退款"],
                "related": []
            },
            {
                "title": "支持哪些支付方式",
                "content": "我们支持支付宝、微信支付、银行卡支付等多种支付方式。请在支付页面选择您偏好的支付方式完成支付。",
                "category": "支付管理",
                "tags": ["支付", "支付方式"],
                "keywords": ["支付方式", "付款方式", "如何支付"],
                "related": []
            },
            {
                "title": "如何联系客服",
                "content": "您可以通过以下方式联系客服：1. 在线客服：点击页面右下角的客服图标；2. 电话客服：拨打400-xxx-xxxx；3. 邮箱客服：service@example.com。工作时间为周一至周五 9:00-18:00。",
                "category": "服务支持",
                "tags": ["客服", "联系"],
                "keywords": ["联系客服", "人工客服", "客服电话"],
                "related": []
            }
        ]
        
        for k in sample_knowledge:
            knowledge = Knowledge(
                knowledge_id=generate_id("knowledge"),
                knowledge_title=k["title"],
                knowledge_content=k["content"],
                knowledge_category=k["category"],
                knowledge_tags=k["tags"],
                knowledge_keywords=k["keywords"],
                related_knowledge=k["related"],
                view_count=10
            )
            self.knowledges[knowledge.knowledge_id] = knowledge
            self.index_metadata.record_update(knowledge.knowledge_id)
    
    def create_knowledge(self, data: KnowledgeCreate) -> Knowledge:
        knowledge = Knowledge(
            knowledge_id=generate_id("knowledge"),
            **data.model_dump()
        )
        self.knowledges[knowledge.knowledge_id] = knowledge
        self.index_metadata.record_update(knowledge.knowledge_id)
        return knowledge
    
    def get_knowledge(self, knowledge_id: str) -> Optional[Knowledge]:
        return self.knowledges.get(knowledge_id)
    
    def list_knowledges(self, category: Optional[str] = None) -> List[Knowledge]:
        result = list(self.knowledges.values())
        if category:
            result = [k for k in result if k.knowledge_category == category]
        return result
    
    def update_knowledge(self, knowledge_id: str, data: KnowledgeUpdate) -> Optional[Knowledge]:
        existing = self.knowledges.get(knowledge_id)
        if not existing:
            return None
        
        update_data = data.model_dump(exclude_unset=True)
        for key, value in update_data.items():
            setattr(existing, key, value)
        
        existing.updated_at = utc_now()
        existing.needs_update = False
        self.index_metadata.record_update(knowledge_id)
        return existing
    
    def increment_knowledge_view(self, knowledge_id: str):
        existing = self.knowledges.get(knowledge_id)
        if existing:
            existing.view_count += 1
            self.index_metadata.record_activity(knowledge_id)
    
    def mark_knowledge_needs_update(self, knowledge_id: str) -> bool:
        existing = self.knowledges.get(knowledge_id)
        if existing:
            existing.needs_update = True
            return True
        return False
    
    def record_feedback_analysis(self, qa_record: QARecord, satisfaction: int):
        self.feedback_data.record_feedback(qa_record, satisfaction)
    
    def get_low_satisfaction_keywords(self, knowledge_id: str) -> List[str]:
        return self.feedback_data.get_low_satisfaction_keywords(knowledge_id)
    
    def get_knowledge_needing_optimization(self) -> List[str]:
        return self.feedback_data.get_low_satisfaction_knowledge()
    
    def record_keyword_optimization(self, knowledge_id: str, added: List[str], removed: List[str]):
        self.feedback_data.record_optimization(knowledge_id, added, removed)
    
    def get_index_activity_score(self, knowledge_id: str) -> int:
        return self.index_metadata.get_activity_score(knowledge_id)
    
    def create_qa_record(self, data: QARecordCreate, **kwargs) -> QARecord:
        record = QARecord(
            qa_id=generate_id("qa"),
            **data.model_dump(),
            **kwargs
        )
        self.qa_records[record.qa_id] = record
        return record
    
    def get_qa_record(self, qa_id: str) -> Optional[QARecord]:
        return self.qa_records.get(qa_id)
    
    def list_qa_records(self, user_id: Optional[str] = None, limit: int = 100) -> List[QARecord]:
        result = list(self.qa_records.values())
        if user_id:
            result = [r for r in result if r.user_id == user_id]
        result.sort(key=lambda x: x.created_at, reverse=True)
        return result[:limit]
    
    def update_qa_satisfaction(self, qa_id: str, satisfaction: int) -> Optional[QARecord]:
        record = self.qa_records.get(qa_id)
        if record:
            record.satisfaction = satisfaction
            return record
        return None
    
    def get_reply_template(self, template_id: str) -> Optional[ReplyTemplate]:
        return self.reply_templates.get(template_id)
    
    def list_reply_templates(self) -> List[ReplyTemplate]:
        return list(self.reply_templates.values())
    
    def create_reply_template(self, data: ReplyTemplateCreate) -> ReplyTemplate:
        template = ReplyTemplate(
            template_id=generate_id("template"),
            **data.model_dump()
        )
        self.reply_templates[template.template_id] = template
        return template
    
    def list_intents(self) -> List[Intent]:
        return list(self.intents.values())
    
    def create_intent(self, data: IntentCreate) -> Intent:
        intent = Intent(
            intent_id=generate_id("intent"),
            **data.model_dump()
        )
        self.intents[intent.intent_id] = intent
        return intent
    
    def get_or_create_today_stats(self) -> QAStats:
        today = get_today_str()
        stats = self.stats.get(today)
        if not stats:
            stats = QAStats(
                stat_id=generate_id("stat"),
                stat_date=today
            )
            self.stats[today] = stats
        return stats
    
    def increment_total_questions(self):
        stats = self.get_or_create_today_stats()
        stats.total_questions += 1
    
    def increment_matched_questions(self):
        stats = self.get_or_create_today_stats()
        stats.matched_questions += 1
    
    def increment_unmatched_questions(self):
        stats = self.get_or_create_today_stats()
        stats.unmatched_questions += 1
    
    def add_satisfaction(self, score: int):
        stats = self.get_or_create_today_stats()
        stats.total_satisfaction_score += score
        stats.satisfaction_count += 1
    
    def get_stats(self, stat_date: Optional[str] = None) -> Optional[QAStats]:
        date = stat_date or get_today_str()
        return self.stats.get(date)
    
    def create_recommend_record(self, qa_id: str, knowledge_ids: List[str], recommend_type: str) -> RecommendRecord:
        record = RecommendRecord(
            recommend_id=generate_id("recommend"),
            qa_id=qa_id,
            recommend_knowledge=knowledge_ids,
            recommend_type=recommend_type
        )
        self.recommends[record.recommend_id] = record
        return record
