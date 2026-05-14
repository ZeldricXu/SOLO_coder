import sys
from pathlib import Path
from datetime import datetime, timedelta
from typing import List, Dict, Any, Optional

project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

from searchengine.models.base import (
    SearchIndex,
    SearchRequest,
    SearchResult,
    IndexUpdateRequest,
    RecommendRequest,
    RecommendResult,
    SortStrategy,
    SearchLog,
    SearchStats
)


class TestDataBuilder:
    def __init__(self):
        self._content_id_counter = 0
        self._request_id_counter = 0
    
    def create_index_update_request(
        self,
        content_id: Optional[str] = None,
        title: str = "测试文章标题",
        content: str = "这是测试文章的内容",
        keywords: Optional[List[str]] = None,
        category: str = "技术",
        author: str = "author_001",
        content_type: str = "article"
    ) -> IndexUpdateRequest:
        if content_id is None:
            self._content_id_counter += 1
            content_id = f"content_{self._content_id_counter:03d}"
        
        if keywords is None:
            keywords = ["测试", "示例"]
        
        return IndexUpdateRequest(
            content_id=content_id,
            title=title,
            content=content,
            keywords=keywords,
            category=category,
            author=author,
            content_type=content_type
        )
    
    def create_search_index(
        self,
        content_id: Optional[str] = None,
        title: str = "Python编程入门",
        content: str = "Python是一种简单易学的编程语言",
        keywords: Optional[List[str]] = None,
        category: str = "技术",
        author: str = "author_001",
        click_count: int = 0,
        days_ago: int = 0
    ) -> SearchIndex:
        if content_id is None:
            self._content_id_counter += 1
            content_id = f"content_{self._content_id_counter:03d}"
        
        if keywords is None:
            keywords = ["Python", "编程"]
        
        publish_time = datetime.utcnow() - timedelta(days=days_ago)
        
        return SearchIndex(
            index_id=f"idx_{content_id}",
            content_id=content_id,
            title=title,
            content=content,
            keywords=keywords,
            category=category,
            author=author,
            click_count=click_count,
            publish_time=publish_time,
            index_time=publish_time
        )
    
    def create_search_request(
        self,
        keyword: str = "Python编程",
        filters: Optional[Dict[str, Any]] = None,
        sort_type: str = "relevance",
        page: int = 1,
        page_size: int = 10,
        user_id: Optional[str] = None
    ) -> SearchRequest:
        if filters is None:
            filters = {}
        
        return SearchRequest(
            request_id=f"req_{self._request_id_counter:03d}",
            user_id=user_id,
            keyword=keyword,
            filters=filters,
            sort_type=sort_type,
            page=page,
            page_size=page_size
        )
    
    def create_recommend_request(
        self,
        user_id: Optional[str] = None,
        content_id: Optional[str] = None,
        recommend_type: str = "related",
        limit: int = 10
    ) -> RecommendRequest:
        return RecommendRequest(
            user_id=user_id,
            content_id=content_id,
            recommend_type=recommend_type,
            limit=limit
        )
    
    def create_sort_strategy(
        self,
        strategy_id: str = "strategy_custom",
        strategy_name: str = "自定义排序策略",
        strategy_type: str = "custom_weighted",
        config: Optional[Dict[str, Any]] = None,
        enabled: bool = True
    ) -> SortStrategy:
        if config is None:
            config = {
                "weight_title": 0.5,
                "weight_content": 0.3,
                "weight_click": 0.2
            }
        
        return SortStrategy(
            strategy_id=strategy_id,
            strategy_name=strategy_name,
            strategy_type=strategy_type,
            strategy_config=config,
            enabled=enabled
        )
    
    def create_batch_index_data(
        self,
        count: int = 10,
        base_title: str = "文章",
        category: str = "技术"
    ) -> List[IndexUpdateRequest]:
        requests = []
        for i in range(count):
            requests.append(self.create_index_update_request(
                title=f"{base_title}_{i+1}",
                content=f"这是{base_title}_{i+1}的详细内容",
                keywords=[base_title, f"关键词{i+1}"],
                category=category
            ))
        return requests
    
    def create_python_article_indexes(self, count: int = 5) -> List[SearchIndex]:
        titles = [
            "Python编程入门教程",
            "Python高级编程技巧",
            "Python数据分析实战",
            "Python Web开发指南",
            "Python机器学习入门",
            "Python网络编程详解",
            "Python并发编程实战",
            "Python数据库操作",
            "Python测试开发指南",
            "Python爬虫技术实战"
        ]
        
        contents = [
            "学习Python编程语言的基础知识，包括变量、数据类型、函数等",
            "掌握Python高级特性：装饰器、生成器、上下文管理器等",
            "使用pandas和numpy进行数据分析的实战教程",
            "Django和Flask框架的Web开发完整指南",
            "机器学习算法的Python实现，从入门到精通",
            "socket编程、HTTP请求、异步IO等网络编程技术",
            "多线程、多进程、协程的并发编程实践",
            "SQLAlchemy ORM和原生SQL的数据库操作",
            "pytest单元测试框架的使用和最佳实践",
            "requests、BeautifulSoup、Scrapy等爬虫技术"
        ]
        
        keywords_list = [
            ["Python", "编程", "入门"],
            ["Python", "高级", "装饰器"],
            ["Python", "数据分析", "pandas"],
            ["Python", "Web", "Django"],
            ["Python", "机器学习", "AI"],
            ["Python", "网络", "socket"],
            ["Python", "并发", "多线程"],
            ["Python", "数据库", "SQL"],
            ["Python", "测试", "pytest"],
            ["Python", "爬虫", "Scrapy"]
        ]
        
        indexes = []
        for i in range(min(count, len(titles))):
            indexes.append(self.create_search_index(
                title=titles[i],
                content=contents[i],
                keywords=keywords_list[i],
                click_count=(count - i) * 10,
                days_ago=i
            ))
        return indexes
    
    def create_java_article_indexes(self, count: int = 5) -> List[SearchIndex]:
        indexes = []
        for i in range(count):
            indexes.append(self.create_search_index(
                title=f"Java编程技术_{i+1}",
                content=f"Java编程语言的高级特性和实战内容_{i+1}",
                keywords=["Java", "编程", f"关键词{i+1}"],
                category="技术",
                click_count=i * 20,
                days_ago=i * 3
            ))
        return indexes
    
    def create_mixed_category_indexes(self) -> List[SearchIndex]:
        return [
            self.create_search_index(
                title="Python入门教程",
                content="Python编程基础教程",
                keywords=["Python", "编程"],
                category="技术",
                click_count=100
            ),
            self.create_search_index(
                title="Java开发指南",
                content="Java企业级应用开发",
                keywords=["Java", "开发"],
                category="技术",
                click_count=200
            ),
            self.create_search_index(
                title="健康生活方式",
                content="健康饮食和运动的重要性",
                keywords=["健康", "生活"],
                category="生活",
                click_count=50
            ),
            self.create_search_index(
                title="财经新闻分析",
                content="股票市场和投资策略",
                keywords=["财经", "投资"],
                category="财经",
                click_count=150
            ),
            self.create_search_index(
                title="娱乐八卦新闻",
                content="娱乐圈最新动态",
                keywords=["娱乐", "明星"],
                category="娱乐",
                click_count=300
            )
        ]
    
    def create_click_gradient_indexes(self, count: int = 10) -> List[SearchIndex]:
        indexes = []
        for i in range(count):
            indexes.append(self.create_search_index(
                title=f"热门文章_{i+1}",
                content=f"这是第{i+1}篇热门文章的内容",
                keywords=["热门", f"文章{i+1}"],
                click_count=(count - i) * 100
            ))
        return indexes
    
    def create_time_gradient_indexes(self, count: int = 10) -> List[SearchIndex]:
        indexes = []
        for i in range(count):
            indexes.append(self.create_search_index(
                title=f"时间文章_{i+1}",
                content=f"这是{i}天前发布的文章",
                keywords=["时间", f"文章{i+1}"],
                days_ago=i,
                click_count=100
            ))
        return indexes
    
    def create_user_search_history(self, user_id: str, keywords: List[str]) -> List[SearchRequest]:
        requests = []
        for keyword in keywords:
            self._request_id_counter += 1
            requests.append(self.create_search_request(
                keyword=keyword,
                user_id=user_id
            ))
        return requests
    
    def reset_counters(self):
        self._content_id_counter = 0
        self._request_id_counter = 0


test_data_builder = TestDataBuilder()
