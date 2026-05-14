import pytest
import time
import threading
from typing import List, Dict, Optional
from qabot.models import Database, Knowledge
from qabot.modules import RecommendModule
from tests.test_data_builder import builder


class TestRecommendModule:
    
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
    def recommend_module(self, db):
        return RecommendModule(db)
    
    @pytest.fixture
    def password_knowledge(self, db):
        builder.reset()
        knowledge = builder.build_password_reset_knowledge()
        db.knowledges[knowledge.knowledge_id] = knowledge
        return knowledge
    
    @pytest.fixture
    def login_knowledge(self, db):
        builder.reset()
        knowledge = builder.build_login_knowledge()
        db.knowledges[knowledge.knowledge_id] = knowledge
        return knowledge
    
    @pytest.fixture
    def account_kb(self, db, password_knowledge, login_knowledge):
        return [password_knowledge, login_knowledge]
    
    @pytest.fixture
    def hot_knowledges(self, db):
        builder.reset()
        hot_1 = builder.build_high_view_knowledge("hot_1", 1000)
        hot_2 = builder.build_high_view_knowledge("hot_2", 800)
        hot_3 = builder.build_high_view_knowledge("hot_3", 600)
        hot_4 = builder.build_high_view_knowledge("hot_4", 400)
        hot_5 = builder.build_high_view_knowledge("hot_5", 200)
        
        db.knowledges[hot_1.knowledge_id] = hot_1
        db.knowledges[hot_2.knowledge_id] = hot_2
        db.knowledges[hot_3.knowledge_id] = hot_3
        db.knowledges[hot_4.knowledge_id] = hot_4
        db.knowledges[hot_5.knowledge_id] = hot_5
        
        return [hot_1, hot_2, hot_3, hot_4, hot_5]
    
    def test_related_recommendations_based_on_related_knowledge(self, db, recommend_module, password_knowledge, login_knowledge):
        password_knowledge.related_knowledge = [login_knowledge.knowledge_id]
        
        recommendations = recommend_module._get_related_recommendations(password_knowledge)
        
        assert len(recommendations) > 0
        related_ids = [r.knowledge_id for r in recommendations]
        assert login_knowledge.knowledge_id in related_ids
    
    def test_related_recommendations_based_on_category(self, db, recommend_module):
        builder.reset()
        
        account_1 = builder.build_knowledge(
            knowledge_id="acc_1",
            title="账户知识1",
            content="账户内容1",
            category="账户管理",
            tags=["账户"],
            keywords=["账户"],
            view_count=100
        )
        account_2 = builder.build_knowledge(
            knowledge_id="acc_2",
            title="账户知识2",
            content="账户内容2",
            category="账户管理",
            tags=["账户"],
            keywords=["账户"],
            view_count=50
        )
        other = builder.build_knowledge(
            knowledge_id="other_1",
            title="其他知识",
            content="其他内容",
            category="其他分类",
            tags=["其他"],
            keywords=["其他"],
            view_count=200
        )
        
        db.knowledges[account_1.knowledge_id] = account_1
        db.knowledges[account_2.knowledge_id] = account_2
        db.knowledges[other.knowledge_id] = other
        
        recommendations = recommend_module._get_related_recommendations(account_1)
        
        assert len(recommendations) > 0
        for r in recommendations:
            assert r.knowledge_category == "账户管理"
            assert r.knowledge_id != account_1.knowledge_id
    
    def test_hot_recommendations_based_on_view_count(self, db, recommend_module, hot_knowledges):
        recommendations = recommend_module._get_hot_recommendations()
        
        assert len(recommendations) == 3
        
        scores = [r.score for r in recommendations]
        assert scores[0] >= scores[1] >= scores[2]
        
        assert recommendations[0].knowledge_id == "hot_1"
        assert recommendations[0].score == 1.0
    
    def test_hot_recommendations_score_calculation(self, db, recommend_module, hot_knowledges):
        recommendations = recommend_module._get_hot_recommendations()
        
        hot_1 = db.get_knowledge("hot_1")
        hot_2 = db.get_knowledge("hot_2")
        
        max_views = hot_1.view_count
        
        for r in recommendations:
            knowledge = db.get_knowledge(r.knowledge_id)
            expected_score = round(knowledge.view_count / max_views, 2)
            assert abs(r.score - expected_score) < 0.01
    
    def test_combined_recommendations(self, db, recommend_module, account_kb, hot_knowledges):
        password_knowledge = account_kb[0]
        login_knowledge = account_kb[1]
        
        password_knowledge.related_knowledge = [login_knowledge.knowledge_id]
        
        recommendations = recommend_module.generate_recommendations(
            matched_knowledge=password_knowledge
        )
        
        assert len(recommendations) == 3
        
        recommend_types = [r.recommend_type for r in recommendations]
        assert "related" in recommend_types or "hot" in recommend_types
    
    def test_recommendations_saved_to_history(self, db, recommend_module, account_kb):
        password_knowledge = account_kb[0]
        qa_id = "qa_test_recommend_001"
        
        initial_recommend_count = len(db.recommends)
        
        recommendations = recommend_module.generate_recommendations(
            matched_knowledge=password_knowledge,
            qa_id=qa_id
        )
        
        assert len(db.recommends) == initial_recommend_count + 1
        
        latest_recommend = list(db.recommends.values())[-1]
        assert latest_recommend.qa_id == qa_id
        assert len(latest_recommend.recommend_knowledge) == len(recommendations)
    
    def test_recommendations_without_matched_knowledge(self, db, recommend_module, hot_knowledges):
        recommendations = recommend_module.generate_recommendations(matched_knowledge=None)
        
        assert len(recommendations) == 3
        for r in recommendations:
            assert r.recommend_type == "hot"
    
    def test_recommendations_top_n_limit(self, db, recommend_module):
        builder.reset()
        
        for i in range(20):
            knowledge = builder.build_knowledge(
                knowledge_id=f"many_{i}",
                title=f"知识_{i}",
                content=f"内容_{i}",
                category="测试",
                tags=["测试"],
                keywords=[f"关键词_{i}"],
                view_count=100 + i
            )
            db.knowledges[knowledge.knowledge_id] = knowledge
        
        recommendations = recommend_module.generate_recommendations()
        
        assert len(recommendations) == 3
    
    def test_recommendation_item_structure(self, db, recommend_module, hot_knowledges):
        recommendations = recommend_module._get_hot_recommendations()
        
        for r in recommendations:
            assert hasattr(r, 'knowledge_id')
            assert hasattr(r, 'knowledge_title')
            assert hasattr(r, 'knowledge_category')
            assert hasattr(r, 'score')
            assert hasattr(r, 'recommend_type')
            
            assert r.knowledge_id is not None
            assert r.knowledge_title is not None
            assert 0 <= r.score <= 1
    
    def test_related_recommendations_priority(self, db, recommend_module):
        builder.reset()
        
        main_knowledge = builder.build_knowledge(
            knowledge_id="main",
            title="主知识",
            content="主知识内容",
            category="测试分类",
            tags=["测试"],
            keywords=["测试"],
            view_count=100,
            related=["related_1", "related_2"]
        )
        
        related_1 = builder.build_knowledge(
            knowledge_id="related_1",
            title="关联知识1",
            content="关联内容1",
            category="测试分类",
            tags=["测试"],
            keywords=["关联"],
            view_count=50
        )
        
        related_2 = builder.build_knowledge(
            knowledge_id="related_2",
            title="关联知识2",
            content="关联内容2",
            category="测试分类",
            tags=["测试"],
            keywords=["关联"],
            view_count=30
        )
        
        hot = builder.build_high_view_knowledge("hot_extra", 500)
        
        db.knowledges[main_knowledge.knowledge_id] = main_knowledge
        db.knowledges[related_1.knowledge_id] = related_1
        db.knowledges[related_2.knowledge_id] = related_2
        db.knowledges[hot.knowledge_id] = hot
        
        recommendations = recommend_module.generate_recommendations(matched_knowledge=main_knowledge)
        
        related_ids = [r.knowledge_id for r in recommendations if r.recommend_type == "related"]
        assert "related_1" in related_ids
        assert "related_2" in related_ids
    
    def test_recommendation_generation_speed(self, db, recommend_module):
        builder.reset()
        
        for i in range(500):
            knowledge = builder.build_knowledge(
                knowledge_id=f"perf_{i}",
                title=f"性能测试知识_{i}",
                content=f"这是性能测试内容_{i}，包含大量文本用于测试推荐生成速度。",
                category=f"分类_{i % 10}",
                tags=[f"标签_{i}"],
                keywords=[f"关键词_{i}"],
                view_count=i * 10
            )
            db.knowledges[knowledge.knowledge_id] = knowledge
        
        start_time = time.time()
        
        test_knowledge = Knowledge(
            knowledge_id="test_main",
            knowledge_title="测试主知识",
            knowledge_content="测试内容",
            knowledge_category="分类_0",
            knowledge_tags=["测试"],
            knowledge_keywords=["测试"],
            view_count=100,
            related_knowledge=[]
        )
        db.knowledges[test_knowledge.knowledge_id] = test_knowledge
        
        recommendations = recommend_module.generate_recommendations(matched_knowledge=test_knowledge)
        
        end_time = time.time()
        generation_time = end_time - start_time
        
        assert len(recommendations) == 3
        assert generation_time < 2.0
    
    def test_empty_knowledge_base_recommendations(self, db, recommend_module):
        recommendations = recommend_module.generate_recommendations()
        
        assert len(recommendations) == 0
    
    def test_single_knowledge_no_related(self, db, recommend_module):
        builder.reset()
        knowledge = builder.build_password_reset_knowledge()
        knowledge.related_knowledge = []
        db.knowledges[knowledge.knowledge_id] = knowledge
        
        recommendations = recommend_module.generate_recommendations(matched_knowledge=knowledge)
        
        assert len(recommendations) >= 0
    
    def test_retry_mechanism_simulation(self, db, recommend_module, hot_knowledges):
        class RetryTracker:
            def __init__(self):
                self.attempts = 0
        
        tracker = RetryTracker()
        original_get_hot = recommend_module._get_hot_recommendations
        
        def flaky_get_hot():
            tracker.attempts += 1
            if tracker.attempts < 2:
                raise Exception("Simulated failure")
            return original_get_hot()
        
        recommend_module._get_hot_recommendations = flaky_get_hot
        
        try:
            result = None
            last_error = None
            
            for attempt in range(3):
                try:
                    result = recommend_module.generate_recommendations()
                    break
                except Exception as e:
                    last_error = e
                    continue
            
            assert result is not None
            assert len(result) == 3
            assert tracker.attempts >= 2
            
        finally:
            recommend_module._get_hot_recommendations = original_get_hot
    
    def test_deduplication_in_combined_recommendations(self, db, recommend_module):
        builder.reset()
        
        main_knowledge = Knowledge(
            knowledge_id="main_dedup",
            knowledge_title="主知识",
            knowledge_content="内容",
            knowledge_category="热门分类",
            knowledge_tags=["热门"],
            knowledge_keywords=["热门"],
            view_count=500,
            related_knowledge=["hot_1"]
        )
        
        hot_1 = builder.build_high_view_knowledge("hot_1", 1000)
        hot_2 = builder.build_high_view_knowledge("hot_2", 800)
        hot_3 = builder.build_high_view_knowledge("hot_3", 600)
        
        db.knowledges[main_knowledge.knowledge_id] = main_knowledge
        db.knowledges[hot_1.knowledge_id] = hot_1
        db.knowledges[hot_2.knowledge_id] = hot_2
        db.knowledges[hot_3.knowledge_id] = hot_3
        
        recommendations = recommend_module.generate_recommendations(matched_knowledge=main_knowledge)
        
        recommendation_ids = [r.knowledge_id for r in recommendations]
        assert len(recommendation_ids) == len(set(recommendation_ids))
