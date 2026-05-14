import pytest
from typing import List, Dict
from qabot.models import Database, Intent
from qabot.modules import IntentModule
from tests.test_data_builder import builder


class TestIntentModule:
    
    @pytest.fixture
    def db(self):
        db = Database()
        db.knowledges = {}
        db.qa_records = {}
        db.reply_templates = {}
        db.intents = {}
        db.stats = {}
        db.recommends = {}
        return db
    
    @pytest.fixture
    def intent_module(self, db):
        return IntentModule(db)
    
    @pytest.fixture
    def default_intents(self, db):
        builder.reset()
        intents = builder.build_all_default_intents()
        for intent in intents:
            db.intents[intent.intent_id] = intent
        return intents
    
    def test_account_intent_recognition(self, intent_module, default_intents):
        questions = [
            "忘记密码怎么办",
            "如何登录账户",
            "怎么注册账号",
            "我的密码是什么",
            "账户怎么激活"
        ]
        
        for question in questions:
            intent = intent_module.recognize_intent(question)
            assert intent == "account", f"Question '{question}' should be 'account', got '{intent}'"
    
    def test_payment_intent_recognition(self, intent_module, default_intents):
        questions = [
            "怎么申请退款",
            "订单金额是多少",
            "付款失败怎么办",
            "支持什么支付方式",
            "订单什么时候付款"
        ]
        
        for question in questions:
            intent = intent_module.recognize_intent(question)
            assert intent == "payment", f"Question '{question}' should be 'payment', got '{intent}'"
    
    def test_product_intent_recognition(self, intent_module, default_intents):
        questions = [
            "这个功能怎么用",
            "产品有哪些功能",
            "如何操作",
            "设置在哪里",
            "怎么使用这个产品"
        ]
        
        for question in questions:
            intent = intent_module.recognize_intent(question)
            assert intent == "product", f"Question '{question}' should be 'product', got '{intent}'"
    
    def test_service_intent_recognition(self, intent_module, default_intents):
        questions = [
            "怎么联系客服",
            "我要投诉",
            "人工客服在哪里",
            "服务态度不好",
            "需要人工服务"
        ]
        
        for question in questions:
            intent = intent_module.recognize_intent(question)
            assert intent == "service", f"Question '{question}' should be 'service', got '{intent}'"
    
    def test_general_intent_fallback(self, intent_module, default_intents):
        questions = [
            "今天天气怎么样",
            "你好",
            "这是什么",
            "随便问个问题",
            "测试测试"
        ]
        
        for question in questions:
            intent = intent_module.recognize_intent(question)
            assert intent == "general", f"Question '{question}' should be 'general', got '{intent}'"
    
    def test_keyword_matching_case_insensitive(self, intent_module, default_intents):
        question_variations = [
            "忘记密码怎么办",
            "忘 记 密 码 怎 么 办",
            "忘记密码怎么办？",
            "请问忘记密码怎么办"
        ]
        
        for question in question_variations:
            intent = intent_module.recognize_intent(question)
            assert intent == "account"
    
    def test_partial_keyword_matching(self, intent_module, default_intents):
        questions = [
            "密码",
            "登录",
            "退款",
            "客服",
            "支付"
        ]
        
        expected_intents = ["account", "account", "payment", "service", "payment"]
        
        for question, expected in zip(questions, expected_intents):
            intent = intent_module.recognize_intent(question)
            assert intent == expected, f"Question '{question}' should be '{expected}', got '{intent}'"
    
    def test_list_all_intents(self, intent_module, default_intents):
        intents = intent_module.list_intents()
        
        assert len(intents) == 4
        
        intent_ids = {i.intent_id for i in intents}
        assert "intent_account" in intent_ids
        assert "intent_payment" in intent_ids
        assert "intent_product" in intent_ids
        assert "intent_service" in intent_ids
    
    def test_create_custom_intent(self, intent_module, db):
        from qabot.models import IntentCreate
        
        custom_intent_data = IntentCreate(
            intent_name="物流问题",
            intent_keywords=["物流", "快递", "配送", "发货"],
            intent_category="logistics"
        )
        
        created_intent = intent_module.create_intent(custom_intent_data)
        
        assert created_intent.intent_id is not None
        assert created_intent.intent_name == "物流问题"
        assert created_intent.intent_category == "logistics"
        assert "物流" in created_intent.intent_keywords
    
    def test_custom_intent_recognition(self, intent_module, db):
        from qabot.models import IntentCreate
        
        custom_intent_data = IntentCreate(
            intent_name="物流问题",
            intent_keywords=["物流", "快递", "配送", "发货"],
            intent_category="logistics"
        )
        
        intent_module.create_intent(custom_intent_data)
        
        logistics_questions = [
            "物流什么时候到",
            "快递还没收到",
            "配送需要几天",
            "什么时候发货"
        ]
        
        for question in logistics_questions:
            intent = intent_module.recognize_intent(question)
            assert intent == "logistics", f"Question '{question}' should be 'logistics', got '{intent}'"
    
    def test_empty_intent_list_general_fallback(self, intent_module):
        questions = [
            "忘记密码怎么办",
            "怎么申请退款",
            "联系客服"
        ]
        
        for question in questions:
            intent = intent_module.recognize_intent(question)
            assert intent == "general"
    
    def test_multiple_keyword_intent_priority(self, intent_module, default_intents):
        question = "忘记密码后如何登录"
        
        intent = intent_module.recognize_intent(question)
        
        assert intent in ["account"]
    
    def test_intent_classification_accuracy(self, intent_module, default_intents):
        test_cases = builder.get_expected_intents()
        
        correct_count = 0
        total_count = len(test_cases)
        
        for question, expected_intent in test_cases.items():
            actual_intent = intent_module.recognize_intent(question)
            if actual_intent == expected_intent:
                correct_count += 1
        
        accuracy = correct_count / total_count
        
        assert accuracy >= 0.7, f"Accuracy {accuracy:.2%} is below threshold 70%"
    
    def test_special_characters_handling(self, intent_module, default_intents):
        questions = [
            "忘记密码？怎么办",
            "如何登录？？",
            "退款！！",
            "客服？？？"
        ]
        
        expected = ["account", "account", "payment", "service"]
        
        for q, e in zip(questions, expected):
            intent = intent_module.recognize_intent(q)
            assert intent == e
    
    def test_long_question_intent_recognition(self, intent_module, default_intents):
        long_question = (
            "我昨天在网站上购买了一个产品，但是现在我忘记了我的账户密码，"
            "我想知道怎么重置密码，因为我需要登录账户来查看我的订单状态，"
            "请问有什么方法可以帮助我找回密码吗？"
        )
        
        intent = intent_module.recognize_intent(long_question)
        
        assert intent == "account"
    
    def test_no_keyword_overlap(self, intent_module, default_intents):
        unrelated_question = "火星上有外星人吗"
        
        intent = intent_module.recognize_intent(unrelated_question)
        
        assert intent == "general"
    
    def test_multiple_intent_keywords(self, db, intent_module):
        from qabot.models import IntentCreate
        
        overlap_intent = IntentCreate(
            intent_name="测试重叠",
            intent_keywords=["密码", "登录", "账户"],
            intent_category="overlap"
        )
        
        intent_module.create_intent(overlap_intent)
        
        result = intent_module.recognize_intent("密码")
        
        assert result is not None
