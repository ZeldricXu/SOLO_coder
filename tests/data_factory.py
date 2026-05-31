"""
测试数据工厂模块
集中管理所有测试数据的生成，确保测试数据的一致性和可复用性
"""
from __future__ import annotations

import random
import string
from dataclasses import dataclass, field, asdict
from typing import Dict, Any, List, Optional
from faker import Faker
from datetime import datetime, timedelta
import json

fake = Faker('zh_CN')


@dataclass
class FeatureData:
    """特征存储测试数据"""
    feature_name: str
    feature_type: str
    entity: str
    value_type: str
    description: str
    ttl: int
    tags: Dict[str, str]
    owner: str

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass
class FeatureValueData:
    """特征值测试数据"""
    feature_id: str
    entity_key: str
    value: Dict[str, Any]
    source: str

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass
class DocumentData:
    """文档测试数据"""
    title: str
    file_name: str
    file_type: str
    file_size: int
    content: str
    charset: str
    language: str
    metadata: Dict[str, Any]
    created_by: str

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass
class ModelData:
    """模型测试数据"""
    model_name: str
    model_type: str
    provider: str
    description: str
    task_type: str
    base_model: str
    license: str
    tags: Dict[str, str]
    owner: str

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass
class ModelVersionData:
    """模型版本测试数据"""
    model_id: str
    version: str
    description: str
    artifact_path: str
    metrics: Dict[str, Any]
    parameters: Dict[str, Any]
    dataset: str
    commit_hash: str
    created_by: str

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


class TestDataFactory:
    """测试数据工厂类"""

    def __init__(self, seed: int = 42):
        self.seed = seed
        random.seed(seed)
        Faker.seed(seed)
        self._counter = 0

    def _next_id(self, prefix: str) -> str:
        """生成递增的测试ID"""
        self._counter += 1
        return f"{prefix}_{self._counter:06d}"

    # ========== 特征存储测试数据 ==========

    def create_feature_data(
        self,
        feature_type: Optional[str] = None,
        entity: Optional[str] = None,
        value_type: Optional[str] = None
    ) -> FeatureData:
        """创建特征注册数据"""
        feature_types = ['categorical', 'numerical', 'embedding', 'sequence', 'text']
        entities = ['user', 'item', 'session', 'context', 'query']
        value_types = ['int', 'float', 'string', 'bool', 'json', 'vector']

        return FeatureData(
            feature_name=f"feature_{fake.word()}_{self._counter:04d}",
            feature_type=feature_type or random.choice(feature_types),
            entity=entity or random.choice(entities),
            value_type=value_type or random.choice(value_types),
            description=fake.text(max_nb_chars=100),
            ttl=random.choice([3600, 86400, 604800, 2592000]),
            tags={
                "domain": random.choice(["recommendation", "search", "nlp", "cv"]),
                "priority": random.choice(["high", "medium", "low"]),
                "env": "test"
            },
            owner=fake.user_name()
        )

    def create_feature_value_data(
        self,
        feature_id: str,
        entity_key: Optional[str] = None
    ) -> FeatureValueData:
        """创建特征值数据"""
        value_types = [
            {"score": random.uniform(0, 1), "rank": random.randint(1, 100)},
            {"embedding": [random.uniform(-1, 1) for _ in range(16)]},
            {"category": fake.word(), "confidence": random.uniform(0.5, 0.99)},
            {"flags": {"is_new": random.choice([True, False]), "is_active": True}},
            {"text": fake.sentence(), "tokens": [fake.word() for _ in range(5)]}
        ]

        return FeatureValueData(
            feature_id=feature_id,
            entity_key=entity_key or f"user_{random.randint(1000, 999999)}",
            value=random.choice(value_types),
            source=random.choice(["offline_batch", "online_stream", "manual_import", "realtime_api"])
        )

    def create_backfill_job_data(self, feature_id: str) -> Dict[str, Any]:
        """创建回填任务数据"""
        end_time = datetime.now()
        start_time = end_time - timedelta(days=random.randint(1, 30))
        return {
            "feature_id": feature_id,
            "start_time": start_time.isoformat(),
            "end_time": end_time.isoformat(),
            "created_by": fake.user_name()
        }

    # ========== 文档解析测试数据 ==========

    def create_document_data(
        self,
        file_type: Optional[str] = None,
        with_content: bool = True
    ) -> DocumentData:
        """创建文档数据"""
        file_types = ['txt', 'md', 'pdf', 'docx', 'html', 'json']
        selected_type = file_type or random.choice(file_types)

        content = ""
        if with_content:
            paragraphs = [fake.paragraph(nb_sentences=random.randint(5, 15)) for _ in range(random.randint(3, 10))]
            content = "\n\n".join(paragraphs)

        file_name = f"{fake.slug()}.{selected_type}"

        return DocumentData(
            title=fake.sentence(nb_words=random.randint(3, 10)),
            file_name=file_name,
            file_type=selected_type,
            file_size=len(content.encode('utf-8')) if content else random.randint(100, 1000000),
            content=content,
            charset=random.choice(['UTF-8', 'GBK', 'ASCII']),
            language=random.choice(['zh-CN', 'en-US', 'ja-JP']),
            metadata={
                "source": random.choice(["upload", "crawler", "import"]),
                "author": fake.name(),
                "category": random.choice(["tech", "business", "research", "news"]),
                "confidential": random.choice(["public", "internal", "confidential"])
            },
            created_by=fake.user_name()
        )

    def create_parse_config(self) -> Dict[str, Any]:
        """创建解析配置"""
        return {
            "pipeline_id": self._next_id("pipeline"),
            "chunk_size": random.choice([200, 500, 1000, 2000]),
            "chunk_overlap": random.choice([20, 50, 100, 200]),
            "separator": random.choice(["\n", "\n\n", ". ", "。"]),
            "embedding_model": random.choice(["text-embedding-ada-002", "bge-large-zh", "text2vec-base"]),
            "enable_embedding": random.choice([True, False])
        }

    # ========== 模型注册测试数据 ==========

    def create_model_data(
        self,
        model_type: Optional[str] = None,
        provider: Optional[str] = None
    ) -> ModelData:
        """创建模型注册数据"""
        model_types = ['llm', 'embedding', 'rerank', 'vision', 'multimodal']
        providers = ['openai', 'anthropic', 'zhipuai', 'qwen', 'local', 'custom']
        task_types = ['chat', 'completion', 'embedding', 'classification', 'summarization']

        return ModelData(
            model_name=f"model_{fake.word()}_{self._counter:04d}",
            model_type=model_type or random.choice(model_types),
            provider=provider or random.choice(providers),
            description=fake.text(max_nb_chars=200),
            task_type=random.choice(task_types),
            base_model=random.choice(["gpt-3.5-turbo", "llama2-7b", "qwen-7b", "bge-large"]),
            license=random.choice(["MIT", "Apache-2.0", "GPL", "commercial"]),
            tags={
                "domain": random.choice(["nlp", "vision", "multimodal"]),
                "use_case": random.choice(["chatbot", "search", "analytics"]),
                "size": random.choice(["small", "medium", "large"])
            },
            owner=fake.user_name()
        )

    def create_model_version_data(
        self,
        model_id: str,
        version: Optional[str] = None
    ) -> ModelVersionData:
        """创建模型版本数据"""
        if version is None:
            major = random.randint(0, 3)
            minor = random.randint(0, 10)
            patch = random.randint(0, 20)
            version = f"v{major}.{minor}.{patch}"

        return ModelVersionData(
            model_id=model_id,
            version=version,
            description=fake.text(max_nb_chars=100),
            artifact_path=f"s3://models/{model_id}/{version}/model.pt",
            metrics={
                "accuracy": random.uniform(0.7, 0.99),
                "precision": random.uniform(0.7, 0.99),
                "recall": random.uniform(0.7, 0.99),
                "f1_score": random.uniform(0.7, 0.99),
                "latency_p99": random.uniform(50, 500)
            },
            parameters={
                "batch_size": random.choice([1, 4, 8, 16, 32]),
                "max_length": random.choice([512, 1024, 2048, 4096]),
                "temperature": random.uniform(0.1, 1.0),
                "top_p": random.uniform(0.5, 1.0)
            },
            dataset=random.choice(["wikipedia-zh", "c4-zh", "custom-dataset", "rmrb"]),
            commit_hash=''.join(random.choices(string.hexdigits.lower(), k=40)),
            created_by=fake.user_name()
        )

    def create_stage_transition_data(
        self,
        version_id: str,
        to_stage: Optional[str] = None
    ) -> Dict[str, Any]:
        """创建阶段流转数据"""
        stages = ["development", "staging", "production", "archived"]
        return {
            "version_id": version_id,
            "to_stage": to_stage or random.choice(stages),
            "reason": fake.text(max_nb_chars=50),
            "operated_by": fake.user_name()
        }

    # ========== 通用测试数据生成方法 ==========

    def create_batch(self, create_func, count: int, **kwargs) -> List[Any]:
        """批量创建测试数据"""
        return [create_func(**kwargs) for _ in range(count)]

    def create_random_string(self, length: int = 10) -> str:
        """生成随机字符串"""
        return ''.join(random.choices(string.ascii_letters + string.digits, k=length))

    def create_random_dict(self, depth: int = 2, max_keys: int = 5) -> Dict[str, Any]:
        """生成随机字典"""
        if depth <= 0:
            return random.choice([fake.word(), random.randint(0, 100), random.uniform(0, 1)])

        result = {}
        for _ in range(random.randint(1, max_keys)):
            key = fake.word()
            if random.random() > 0.7:
                result[key] = self.create_random_dict(depth - 1, max_keys // 2)
            else:
                result[key] = random.choice([
                    fake.word(),
                    random.randint(0, 1000),
                    random.uniform(0, 1),
                    fake.boolean(),
                    None
                ])
        return result


# 全局单例工厂
_factory = TestDataFactory()


def get_factory(seed: int = 42) -> TestDataFactory:
    """获取测试数据工厂单例"""
    global _factory
    if seed != 42:
        _factory = TestDataFactory(seed)
    return _factory
