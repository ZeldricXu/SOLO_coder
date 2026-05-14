import pytest
import time
from typing import List, Dict
from qabot.models import Database, Knowledge
from qabot.modules import RetrievalModule
from tests.test_data_builder import builder


class TestRetrievalModule:
    
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
    def retrieval_module(self, db):
        return RetrievalModule(db)
    
    @pytest.fixture
    def standard_kb(self, db):
        builder.reset()
        knowledges = builder.build_standard_knowledge_base()
        for k in knowledges:
            db.knowledges[k.knowledge_id] = k
        return knowledges
    
    @pytest.fixture
    def account_kb(self, db):
        builder.reset()
        password_knowledge = builder.build_password_reset_knowledge()
        login_knowledge = builder.build_login_knowledge()
        db.knowledges[password_knowledge.knowledge_id] = password_knowledge
        db.knowledges[login_knowledge.knowledge_id] = login_knowledge
        return [password_knowledge, login_knowledge]
    
    def test_retrieve_empty_knowledge_base(self, retrieval_module):
        results = retrieval_module.retrieve("忘记密码怎么办")
        assert len(results) == 0
    
    def test_index_building_and_query_correctness(self, retrieval_module, standard_kb):
        results = retrieval_module.retrieve("忘记密码怎么办")
        assert len(results) > 0
        assert results[0].knowledge.knowledge_title == "如何重置密码"
        assert results[0].combined_score > 0
    
    def test_keyword_match_correctness(self, retrieval_module, account_kb):
        password_knowledge = account_kb[0]
        results = retrieval_module.retrieve("忘记密码怎么办")
        
        assert len(results) > 0
        assert results[0].knowledge.knowledge_id == password_knowledge.knowledge_id
        assert results[0].keyword_score > 0
    
    def test_semantic_match_correctness(self, retrieval_module, account_kb):
        password_knowledge = account_kb[0]
        results = retrieval_module.retrieve("忘记密码怎么办")
        
        assert len(results) > 0
        best_result = results[0]
        assert best_result.semantic_score >= 0
        assert best_result.combined_score == (
            best_result.keyword_score * 0.6 + best_result.semantic_score * 0.4
        )
    
    def test_index_update_timeliness(self, db, retrieval_module):
        builder.reset()
        
        results_before = retrieval_module.retrieve("如何申请退款")
        assert len(results_before) == 0
        
        refund_knowledge = builder.build_refund_knowledge()
        db.knowledges[refund_knowledge.knowledge_id] = refund_knowledge
        
        results_after = retrieval_module.retrieve("如何申请退款")
        assert len(results_after) > 0
        assert results_after[0].knowledge.knowledge_id == refund_knowledge.knowledge_id
    
    def test_result_sorting_by_combined_score(self, db, retrieval_module):
        builder.reset()
        
        high_match_knowledge = Knowledge(
            knowledge_id="high_match",
            knowledge_title="如何重置密码",
            knowledge_content="这是关于重置密码的详细内容，包含忘记密码、修改密码等操作说明。",
            knowledge_category="账户管理",
            knowledge_tags=["密码"],
            knowledge_keywords=["重置密码", "忘记密码", "修改密码"],
            view_count=100
        )
        
        medium_match_knowledge = Knowledge(
            knowledge_id="medium_match",
            knowledge_title="如何管理账户",
            knowledge_content="这是关于账户管理的内容，包含密码设置等。",
            knowledge_category="账户管理",
            knowledge_tags=["账户"],
            knowledge_keywords=["账户", "管理"],
            view_count=50
        )
        
        low_match_knowledge = Knowledge(
            knowledge_id="low_match",
            knowledge_title="产品介绍",
            knowledge_content="这是产品功能介绍。",
            knowledge_category="产品",
            knowledge_tags=["产品"],
            knowledge_keywords=["产品", "功能"],
            view_count=10
        )
        
        db.knowledges[high_match_knowledge.knowledge_id] = high_match_knowledge
        db.knowledges[medium_match_knowledge.knowledge_id] = medium_match_knowledge
        db.knowledges[low_match_knowledge.knowledge_id] = low_match_knowledge
        
        results = retrieval_module.retrieve("忘记密码怎么办")
        
        assert len(results) >= 2
        assert results[0].combined_score >= results[1].combined_score
        assert results[0].knowledge.knowledge_id == "high_match"
    
    def test_top_k_retrieval_limit(self, db, retrieval_module):
        builder.reset()
        
        for i in range(10):
            knowledge = Knowledge(
                knowledge_id=f"k_{i}",
                knowledge_title=f"知识_{i} 密码重置",
                knowledge_content=f"关于密码重置的内容_{i}",
                knowledge_category="账户管理",
                knowledge_tags=["密码"],
                knowledge_keywords=["重置密码", "忘记密码"],
                view_count=i
            )
            db.knowledges[knowledge.knowledge_id] = knowledge
        
        results = retrieval_module.retrieve("忘记密码")
        
        assert len(results) <= 5
    
    def test_intent_based_retrieval_filtering(self, retrieval_module, standard_kb):
        results_without_intent = retrieval_module.retrieve("忘记密码怎么办")
        results_with_intent = retrieval_module.retrieve("忘记密码怎么办", intent_category="account")
        
        assert len(results_with_intent) > 0
        for r in results_with_intent:
            assert r.knowledge.knowledge_category == "账户管理"
    
    def test_keyword_weight_calculation(self, retrieval_module, account_kb):
        password_knowledge = account_kb[0]
        results = retrieval_module.retrieve("忘记密码怎么办")
        
        best_result = results[0]
        expected_combined = (
            best_result.keyword_score * 0.6 + best_result.semantic_score * 0.4
        )
        
        assert abs(best_result.combined_score - expected_combined) < 0.001
    
    def test_multiple_keyword_matching(self, retrieval_module, account_kb):
        login_knowledge = account_kb[1]
        
        results = retrieval_module.retrieve("用户登录账户")
        
        assert len(results) > 0
        best_result = results[0]
        assert best_result.knowledge.knowledge_id == login_knowledge.knowledge_id
        assert best_result.keyword_score > 0
    
    def test_partial_keyword_matching(self, retrieval_module, standard_kb):
        results = retrieval_module.retrieve("密码")
        
        assert len(results) > 0
        for r in results:
            assert any("密码" in kw for kw in r.knowledge.knowledge_keywords) or "密码" in r.knowledge.knowledge_content
    
    def test_large_knowledge_base_performance(self, db, retrieval_module):
        builder.reset()
        large_knowledges = builder.build_large_knowledge_set(1000)
        for k in large_knowledges:
            db.knowledges[k.knowledge_id] = k
        
        start_time = time.time()
        results = retrieval_module.retrieve("关键词_500 搜索词_500")
        end_time = time.time()
        
        retrieval_time = end_time - start_time
        
        assert retrieval_time < 5.0
        assert len(results) >= 0
    
    def test_score_ranking_consistency(self, retrieval_module, standard_kb):
        results1 = retrieval_module.retrieve("忘记密码怎么办")
        results2 = retrieval_module.retrieve("忘记密码怎么办")
        
        assert len(results1) == len(results2)
        if results1 and results2:
            assert results1[0].knowledge.knowledge_id == results2[0].knowledge.knowledge_id
            assert abs(results1[0].combined_score - results2[0].combined_score) < 0.001
    
    def test_no_match_below_threshold(self, db, retrieval_module):
        builder.reset()
        
        knowledge = Knowledge(
            knowledge_id="test_1",
            knowledge_title="完全不相关的知识",
            knowledge_content="这是关于天文学的知识内容。",
            knowledge_category="其他",
            knowledge_tags=["天文"],
            knowledge_keywords=["星球", "宇宙"],
            view_count=10
        )
        db.knowledges[knowledge.knowledge_id] = knowledge
        
        results = retrieval_module.retrieve("忘记密码怎么办")
        
        assert len(results) == 0 or results[0].combined_score < 0.1
    
    def test_case_insensitive_matching(self, retrieval_module, account_kb):
        results1 = retrieval_module.retrieve("忘记密码怎么办")
        results2 = retrieval_module.retrieve("忘 记 密 码 怎 么 办")
        
        assert len(results1) == len(results2)
        if results1 and results2:
            assert results1[0].knowledge.knowledge_id == results2[0].knowledge.knowledge_id
    
    def test_content_based_matching(self, db, retrieval_module):
        builder.reset()
        
        knowledge = Knowledge(
            knowledge_id="content_test",
            knowledge_title="测试标题",
            knowledge_content="这里提到了忘记密码时应该怎么办的处理方法。",
            knowledge_category="测试",
            knowledge_tags=["测试"],
            knowledge_keywords=["测试"],
            view_count=0
        )
        db.knowledges[knowledge.knowledge_id] = knowledge
        
        results = retrieval_module.retrieve("忘记密码怎么办")
        
        assert len(results) > 0
        assert results[0].knowledge.knowledge_id == "content_test"
        assert results[0].semantic_score > 0
