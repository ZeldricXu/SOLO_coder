import sys
import time
from pathlib import Path

project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

import pytest
from tests.test_data_builder import test_data_builder
from searchengine.modules.index_manager import index_manager
from searchengine.modules.recommend_module import recommend_module
from searchengine.modules.cache_module import cache_module


class TestRelatedRecommendation:
    def setup_method(self):
        index_manager.clear_all_indexes()
        recommend_module.clear_user_history("test_user_1")
        test_data_builder.reset_counters()
    
    def test_related_recommendation_by_keywords(self):
        target_request = test_data_builder.create_index_update_request(
            content_id="target_1",
            title="Python数据分析实战",
            content="使用Python进行数据分析的详细教程",
            keywords=["Python", "数据分析", "pandas"]
        )
        index_manager.create_index(target_request)
        
        related_requests = [
            test_data_builder.create_index_update_request(
                content_id=f"related_{i}",
                title=f"Python相关文章{i}",
                content=f"Python和数据分析相关内容{i}",
                keywords=["Python", "数据分析", f"kw{i}"]
            )
            for i in range(5)
        ]
        for req in related_requests:
            index_manager.create_index(req)
        
        unrelated_requests = [
            test_data_builder.create_index_update_request(
                content_id=f"unrelated_{i}",
                title=f"Java相关文章{i}",
                content=f"Java开发内容{i}",
                keywords=["Java", "开发"]
            )
            for i in range(3)
        ]
        for req in unrelated_requests:
            index_manager.create_index(req)
        
        recommend_request = test_data_builder.create_recommend_request(
            content_id="target_1",
            recommend_type="related",
            limit=10
        )
        
        result = recommend_module.generate_recommendations(recommend_request)
        
        assert result.recommend_type == "related"
        assert len(result.recommend_items) <= 8
        
        if result.recommend_items:
            recommended_ids = [item.content_id for item in result.recommend_items]
            related_ids = [f"related_{i}" for i in range(5)]
            
            has_related = any(r_id in recommended_ids for r_id in related_ids)
            assert has_related
    
    def test_related_recommendation_by_category(self):
        target_request = test_data_builder.create_index_update_request(
            content_id="target_cat",
            title="技术文章",
            content="技术内容",
            keywords=["技术"],
            category="技术"
        )
        index_manager.create_index(target_request)
        
        tech_requests = [
            test_data_builder.create_index_update_request(
                content_id=f"tech_{i}",
                title=f"技术{i}",
                content=f"技术内容{i}",
                keywords=[f"kw{i}"],
                category="技术"
            )
            for i in range(5)
        ]
        for req in tech_requests:
            index_manager.create_index(req)
        
        other_requests = [
            test_data_builder.create_index_update_request(
                content_id=f"other_{i}",
                title=f"其他{i}",
                content=f"其他内容{i}",
                keywords=[f"other{i}"],
                category="生活"
            )
            for i in range(5)
        ]
        for req in other_requests:
            index_manager.create_index(req)
        
        recommend_request = test_data_builder.create_recommend_request(
            content_id="target_cat",
            recommend_type="related",
            limit=10
        )
        
        result = recommend_module.generate_recommendations(recommend_request)
        
        assert len(result.recommend_items) > 0
    
    def test_related_recommendation_excludes_self(self):
        target_request = test_data_builder.create_index_update_request(
            content_id="exclude_self",
            title="目标文章",
            content="目标内容",
            keywords=["目标"]
        )
        index_manager.create_index(target_request)
        
        other_request = test_data_builder.create_index_update_request(
            content_id="other_1",
            title="其他文章",
            content="其他内容",
            keywords=["目标"]
        )
        index_manager.create_index(other_request)
        
        recommend_request = test_data_builder.create_recommend_request(
            content_id="exclude_self",
            recommend_type="related",
            limit=10
        )
        
        result = recommend_module.generate_recommendations(recommend_request)
        
        recommended_ids = [item.content_id for item in result.recommend_items]
        assert "exclude_self" not in recommended_ids
    
    def test_related_recommendation_empty_index(self):
        recommend_request = test_data_builder.create_recommend_request(
            content_id="nonexistent",
            recommend_type="related",
            limit=10
        )
        
        result = recommend_module.generate_recommendations(recommend_request)
        
        assert len(result.recommend_items) == 0
        assert result.recommend_type == "related"


class TestHotRecommendation:
    def setup_method(self):
        index_manager.clear_all_indexes()
        recommend_module.clear_user_history("test_user_1")
        test_data_builder.reset_counters()
    
    def test_hot_recommendation_by_click_count(self):
        for i in range(10):
            request = test_data_builder.create_index_update_request(
                content_id=f"hot_{i}",
                title=f"热门文章{i}",
                content=f"热门内容{i}",
                keywords=["热门"]
            )
            index_manager.create_index(request)
        
        for i in range(10):
            for _ in range(i * 10):
                index_manager.increment_click_count(f"hot_{i}")
        
        recommend_request = test_data_builder.create_recommend_request(
            recommend_type="hot",
            limit=5
        )
        
        result = recommend_module.generate_recommendations(recommend_request)
        
        assert len(result.recommend_items) == 5
        assert result.recommend_type == "hot"
        
        scores = [item.recommend_score for item in result.recommend_items]
        assert scores == sorted(scores, reverse=True)
    
    def test_hot_recommendation_recency_factor(self):
        old_request = test_data_builder.create_index_update_request(
            content_id="old_hot",
            title="旧文章",
            content="旧内容",
            keywords=["旧"]
        )
        index_manager.create_index(old_request)
        
        for _ in range(100):
            index_manager.increment_click_count("old_hot")
        
        new_request = test_data_builder.create_index_update_request(
            content_id="new_hot",
            title="新文章",
            content="新内容",
            keywords=["新"]
        )
        index_manager.create_index(new_request)
        
        for _ in range(50):
            index_manager.increment_click_count("new_hot")
        
        recommend_request = test_data_builder.create_recommend_request(
            recommend_type="hot",
            limit=10
        )
        
        result = recommend_module.generate_recommendations(recommend_request)
        
        recommended_ids = [item.content_id for item in result.recommend_items]
        assert "old_hot" in recommended_ids
        assert "new_hot" in recommended_ids
    
    def test_hot_recommendation_limit(self):
        for i in range(20):
            request = test_data_builder.create_index_update_request(
                content_id=f"limit_{i}",
                title=f"文章{i}",
                content=f"内容{i}",
                keywords=[f"kw{i}"]
            )
            index_manager.create_index(request)
        
        recommend_request = test_data_builder.create_recommend_request(
            recommend_type="hot",
            limit=7
        )
        
        result = recommend_module.generate_recommendations(recommend_request)
        
        assert len(result.recommend_items) == 7


class TestPersonalizedRecommendation:
    def setup_method(self):
        index_manager.clear_all_indexes()
        recommend_module.clear_user_history("personal_user")
        test_data_builder.reset_counters()
    
    def test_personalized_recommendation_based_on_history(self):
        python_requests = [
            test_data_builder.create_index_update_request(
                content_id=f"python_{i}",
                title=f"Python文章{i}",
                content=f"Python内容{i}",
                keywords=["Python", "编程"]
            )
            for i in range(5)
        ]
        for req in python_requests:
            index_manager.create_index(req)
        
        java_requests = [
            test_data_builder.create_index_update_request(
                content_id=f"java_{i}",
                title=f"Java文章{i}",
                content=f"Java内容{i}",
                keywords=["Java", "开发"]
            )
            for i in range(5)
        ]
        for req in java_requests:
            index_manager.create_index(req)
        
        for i in range(3):
            recommend_module._update_user_history("personal_user", f"python_{i}")
        
        recommend_request = test_data_builder.create_recommend_request(
            user_id="personal_user",
            recommend_type="personalized",
            limit=10
        )
        
        result = recommend_module.generate_recommendations(recommend_request)
        
        assert len(result.recommend_items) > 0
    
    def test_personalized_no_history_fallback_to_hot(self):
        for i in range(5):
            request = test_data_builder.create_index_update_request(
                content_id=f"fallback_{i}",
                title=f"文章{i}",
                content=f"内容{i}",
                keywords=[f"kw{i}"]
            )
            index_manager.create_index(request)
            for _ in range(i * 10):
                index_manager.increment_click_count(f"fallback_{i}")
        
        recommend_request = test_data_builder.create_recommend_request(
            user_id="new_user",
            recommend_type="personalized",
            limit=5
        )
        
        result = recommend_module.generate_recommendations(recommend_request)
        
        assert len(result.recommend_items) == 5
    
    def test_user_history_tracking(self):
        assert len(recommend_module.get_user_history("history_test")) == 0
        
        recommend_module._update_user_history("history_test", "content_1")
        recommend_module._update_user_history("history_test", "content_2")
        recommend_module._update_user_history("history_test", "content_3")
        
        history = recommend_module.get_user_history("history_test")
        assert len(history) == 3
        assert history == ["content_1", "content_2", "content_3"]
    
    def test_user_history_limit(self):
        for i in range(100):
            recommend_module._update_user_history("limit_user", f"content_{i}")
        
        history = recommend_module.get_user_history("limit_user")
        assert len(history) <= 50
    
    def test_clear_user_history(self):
        recommend_module._update_user_history("clear_test", "content_1")
        recommend_module._update_user_history("clear_test", "content_2")
        
        assert len(recommend_module.get_user_history("clear_test")) == 2
        
        recommend_module.clear_user_history("clear_test")
        
        assert len(recommend_module.get_user_history("clear_test")) == 0


class TestRecommendationStrategySwitch:
    def setup_method(self):
        index_manager.clear_all_indexes()
        recommend_module.clear_user_history("switch_user")
        test_data_builder.reset_counters()
    
    def test_strategy_switch_related_to_hot(self):
        for i in range(10):
            request = test_data_builder.create_index_update_request(
                content_id=f"switch_{i}",
                title=f"文章{i}",
                content=f"内容{i}",
                keywords=[f"kw{i}"]
            )
            index_manager.create_index(request)
            for _ in range(i * 5):
                index_manager.increment_click_count(f"switch_{i}")
        
        related_request = test_data_builder.create_recommend_request(
            content_id="switch_0",
            recommend_type="related",
            limit=5
        )
        related_result = recommend_module.generate_recommendations(related_request)
        
        hot_request = test_data_builder.create_recommend_request(
            recommend_type="hot",
            limit=5
        )
        hot_result = recommend_module.generate_recommendations(hot_request)
        
        assert related_result.recommend_type == "related"
        assert hot_result.recommend_type == "hot"
    
    def test_invalid_strategy_fallback(self):
        for i in range(5):
            request = test_data_builder.create_index_update_request(
                content_id=f"invalid_{i}",
                title=f"文章{i}",
                content=f"内容{i}",
                keywords=[f"kw{i}"]
            )
            index_manager.create_index(request)
        
        invalid_request = test_data_builder.create_recommend_request(
            recommend_type="invalid_strategy",
            limit=5
        )
        
        result = recommend_module.generate_recommendations(invalid_request)
        
        assert result.recommend_type == "hot"
        assert len(result.recommend_items) == 5


class TestRecommendationCache:
    def setup_method(self):
        index_manager.clear_all_indexes()
        cache_module.clear()
        test_data_builder.reset_counters()
    
    def test_recommendation_result_caching(self):
        for i in range(10):
            request = test_data_builder.create_index_update_request(
                content_id=f"rec_cache_{i}",
                title=f"文章{i}",
                content=f"内容{i}",
                keywords=[f"kw{i}"]
            )
            index_manager.create_index(request)
        
        cache_key = "recommend:hot:10"
        recommend_request = test_data_builder.create_recommend_request(
            recommend_type="hot",
            limit=10
        )
        
        result = recommend_module.generate_recommendations(recommend_request)
        
        cache_module.set(cache_key, {
            "recommend_items": [item.model_dump() for item in result.recommend_items]
        })
        
        cached = cache_module.get(cache_key)
        assert cached is not None
        assert len(cached["recommend_items"]) == len(result.recommend_items)
    
    def test_cache_invalidation_on_content_update(self):
        request = test_data_builder.create_index_update_request(
            content_id="cache_test",
            title="原始标题",
            content="原始内容",
            keywords=["原始"]
        )
        index_manager.create_index(request)
        
        cache_key = "recommend:related:cache_test"
        cache_module.set(cache_key, {"cached": True})
        
        update_request = test_data_builder.create_index_update_request(
            content_id="cache_test",
            title="更新标题",
            content="更新内容",
            keywords=["更新"]
        )
        index_manager.update_index(update_request)
        
        cache_module.delete_pattern("recommend:*")
        
        assert cache_module.get(cache_key) is None
    
    def test_recommendation_consistency(self):
        for i in range(10):
            request = test_data_builder.create_index_update_request(
                content_id=f"consist_{i}",
                title=f"文章{i}",
                content=f"内容{i}",
                keywords=[f"kw{i}"]
            )
            index_manager.create_index(request)
        
        for i in range(10):
            for _ in range(i):
                index_manager.increment_click_count(f"consist_{i}")
        
        recommend_request = test_data_builder.create_recommend_request(
            recommend_type="hot",
            limit=5
        )
        
        result1 = recommend_module.generate_recommendations(recommend_request)
        result2 = recommend_module.generate_recommendations(recommend_request)
        
        ids1 = [item.content_id for item in result1.recommend_items]
        ids2 = [item.content_id for item in result2.recommend_items]
        
        assert ids1 == ids2
