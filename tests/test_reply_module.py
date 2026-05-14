import pytest
from typing import Optional
from qabot.models import Database, Knowledge, ReplyTemplate
from qabot.modules import ReplyModule, EvaluationModule, RetrievalModule, UpdateModule
from tests.test_data_builder import builder


class TestReplyModule:
    
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
    def reply_module(self, db):
        return ReplyModule(db)
    
    @pytest.fixture
    def evaluation_module(self, db):
        return EvaluationModule(db)
    
    @pytest.fixture
    def retrieval_module(self, db):
        return RetrievalModule(db)
    
    @pytest.fixture
    def update_module(self, db):
        return UpdateModule(db)
    
    @pytest.fixture
    def default_templates(self, db):
        builder.reset()
        default_template = builder.build_default_template()
        general_template = builder.build_general_template()
        db.reply_templates[default_template.template_id] = default_template
        db.reply_templates[general_template.template_id] = general_template
        return [default_template, general_template]
    
    @pytest.fixture
    def password_knowledge(self, db):
        builder.reset()
        knowledge = builder.build_password_reset_knowledge()
        db.knowledges[knowledge.knowledge_id] = knowledge
        return knowledge
    
    @pytest.fixture
    def qa_record(self, db, password_knowledge):
        builder.reset()
        qa = builder.build_qa_record(
            qa_id="qa_test_001",
            user_id="user_001",
            question="忘记密码怎么办",
            matched_knowledge=password_knowledge.knowledge_id,
            reply_content=password_knowledge.knowledge_content,
            reply_type="knowledge_match",
            match_score=0.85,
            intent_category="account"
        )
        db.qa_records[qa.qa_id] = qa
        return qa
    
    def test_default_reply_generation(self, reply_module, default_templates):
        reply, reply_type = reply_module.generate_reply(None)
        
        assert reply_type == "default"
        assert "抱歉" in reply or "未能找到" in reply
    
    def test_default_template_id_exists(self, db, default_templates):
        from qabot.config import settings
        template = db.get_reply_template(settings.DEFAULT_TEMPLATE_ID)
        assert template is not None
        assert template.template_type == "default"
    
    def test_knowledge_based_reply_generation(self, reply_module, password_knowledge, default_templates):
        reply, reply_type = reply_module.generate_reply(password_knowledge)
        
        assert reply_type == "knowledge_match"
        assert password_knowledge.knowledge_content in reply
    
    def test_general_template_formatting(self, db, password_knowledge):
        general_template = builder.build_general_template()
        db.reply_templates[general_template.template_id] = general_template
        
        reply_module = ReplyModule(db)
        reply, reply_type = reply_module.generate_reply(password_knowledge)
        
        assert "感谢您的提问" in reply
        assert password_knowledge.knowledge_content in reply
    
    def test_list_templates(self, reply_module, default_templates):
        templates = reply_module.list_templates()
        
        assert len(templates) == 2
        template_ids = {t.template_id for t in templates}
        assert "template_default" in template_ids
        assert "template_general" in template_ids
    
    def test_create_custom_template(self, reply_module):
        from qabot.models import ReplyTemplateCreate
        
        template_data = ReplyTemplateCreate(
            template_name="自定义问候模板",
            template_content="您好，有什么可以帮助您的？",
            template_type="greeting"
        )
        
        template = reply_module.create_template(template_data)
        
        assert template.template_id is not None
        assert template.template_name == "自定义问候模板"
        assert template.template_type == "greeting"
    
    def test_feedback_recording_correctness(self, db, evaluation_module, qa_record):
        satisfaction_score = 5
        
        updated_record = evaluation_module.evaluate_quality(qa_record.qa_id, satisfaction_score)
        
        assert updated_record is not None
        assert updated_record.satisfaction == satisfaction_score
        assert db.qa_records[qa_record.qa_id].satisfaction == satisfaction_score
    
    def test_feedback_score_validation(self, evaluation_module, qa_record):
        invalid_high_score = 6
        result_high = evaluation_module.evaluate_quality(qa_record.qa_id, invalid_high_score)
        assert result_high is None
        
        invalid_low_score = 0
        result_low = evaluation_module.evaluate_quality(qa_record.qa_id, invalid_low_score)
        assert result_low is None
        
        result = evaluation_module.evaluate_quality(qa_record.qa_id, -1)
        assert result is None
    
    def test_low_satisfaction_marks_knowledge_for_update(self, db, evaluation_module, update_module, qa_record, password_knowledge):
        low_satisfaction = 1
        
        assert password_knowledge.needs_update is False
        
        evaluation_module.evaluate_quality(qa_record.qa_id, low_satisfaction)
        
        assert db.knowledges[password_knowledge.knowledge_id].needs_update is True
    
    def test_high_satisfaction_no_update_mark(self, db, evaluation_module, update_module, qa_record, password_knowledge):
        high_satisfaction = 5
        
        assert password_knowledge.needs_update is False
        
        evaluation_module.evaluate_quality(qa_record.qa_id, high_satisfaction)
        
        assert db.knowledges[password_knowledge.knowledge_id].needs_update is False
    
    def test_feedback_updates_statistics(self, db, evaluation_module, qa_record):
        initial_stats = db.get_or_create_today_stats()
        initial_score = initial_stats.total_satisfaction_score
        initial_count = initial_stats.satisfaction_count
        
        evaluation_module.evaluate_quality(qa_record.qa_id, 4)
        
        updated_stats = db.get_or_create_today_stats()
        assert updated_stats.total_satisfaction_score == initial_score + 4
        assert updated_stats.satisfaction_count == initial_count + 1
    
    def test_feedback_optimization_improves_retrieval(self, db, retrieval_module, update_module):
        builder.reset()
        
        initial_knowledge = Knowledge(
            knowledge_id="knowledge_initial",
            knowledge_title="密码相关问题",
            knowledge_content="关于密码的一些内容。",
            knowledge_category="账户管理",
            knowledge_tags=["密码"],
            knowledge_keywords=["密码"],
            view_count=10
        )
        db.knowledges[initial_knowledge.knowledge_id] = initial_knowledge
        
        initial_results = retrieval_module.retrieve("忘记密码怎么办")
        initial_score = initial_results[0].combined_score if initial_results else 0
        
        from qabot.models import KnowledgeUpdate
        updated_data = KnowledgeUpdate(
            knowledge_keywords=["密码", "忘记密码", "重置密码"]
        )
        update_module.update_knowledge(initial_knowledge.knowledge_id, updated_data)
        
        updated_results = retrieval_module.retrieve("忘记密码怎么办")
        updated_score = updated_results[0].combined_score if updated_results else 0
        
        assert updated_score >= initial_score
    
    def test_feedback_info_retrieval(self, evaluation_module, qa_record):
        evaluation_module.evaluate_quality(qa_record.qa_id, 4)
        
        feedback_info = evaluation_module.get_feedback_info(qa_record.qa_id)
        
        assert feedback_info is not None
        assert feedback_info["qa_id"] == qa_record.qa_id
        assert feedback_info["question"] == qa_record.question
        assert feedback_info["satisfaction"] == 4
        assert feedback_info["matched_knowledge"] == qa_record.matched_knowledge
    
    def test_nonexistent_feedback_info(self, evaluation_module):
        info = evaluation_module.get_feedback_info("nonexistent_qa_id")
        assert info is None
    
    def test_default_reply_fallback_when_no_match(self, reply_module, default_templates):
        reply, reply_type = reply_module.generate_reply(None)
        
        assert reply_type == "default"
        assert len(reply) > 0
    
    def test_multiple_templates_management(self, db, reply_module, default_templates):
        from qabot.models import ReplyTemplateCreate
        
        custom1 = ReplyTemplateCreate(
            template_name="技术支持模板",
            template_content="技术支持：{content}",
            template_type="support"
        )
        custom2 = ReplyTemplateCreate(
            template_name="销售咨询模板",
            template_content="销售咨询：{content}",
            template_type="sales"
        )
        
        t1 = reply_module.create_template(custom1)
        t2 = reply_module.create_template(custom2)
        
        all_templates = reply_module.list_templates()
        
        assert len(all_templates) == 4
        template_types = {t.template_type for t in all_templates}
        assert "support" in template_types
        assert "sales" in template_types
    
    def test_reply_type_classification(self, reply_module, password_knowledge, default_templates):
        matched_reply, matched_type = reply_module.generate_reply(password_knowledge)
        default_reply, default_type = reply_module.generate_reply(None)
        
        assert matched_type == "knowledge_match"
        assert default_type == "default"
        assert matched_reply != default_reply
    
    def test_knowledge_content_in_reply(self, reply_module, password_knowledge, default_templates):
        reply, _ = reply_module.generate_reply(password_knowledge)
        
        assert "账户设置页面" in reply or "忘记密码" in reply
