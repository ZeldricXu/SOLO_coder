import sys
import os
from pathlib import Path
from typing import List, Dict, Optional
import pytest
import numpy as np

project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(project_root))

from app.modules.preprocessing import TextPreprocessor, text_preprocessor
from app.modules.classifier import TextClassifier, text_classifier
from app.modules.sentiment_analyzer import SentimentAnalyzer, sentiment_analyzer
from app.modules.keyword_extractor import KeywordExtractor, keyword_extractor
from app.modules.trainer import Trainer, TrainerConfig, TrainingResult, trainer
from app.modules.evaluator import Evaluator, EvaluationConfig, EvaluationResult, evaluator
from app.modules.model_manager import ModelManager, model_manager
from app.modules.training_service import TrainingService, training_service
from app.core.database import Base, engine, SessionLocal
from app.core.config import settings
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker


TEST_DATABASE_URL = "sqlite:///:memory:"


class MockDataGenerator:
    @staticmethod
    def generate_sample_texts() -> List[str]:
        return [
            "这款产品质量很好，做工精细，使用效果非常棒。",
            "价格比其他店铺便宜很多，性价比很高，非常划算。",
            "客服态度很好，有问必答，解决问题非常及时。",
            "物流速度很快，当天就发货了，第二天就收到了。",
            "售后服务很好，有问题很快就解决了，退换货流程也很方便。",
        ]

    @staticmethod
    def generate_sentiment_texts() -> Dict[str, List[str]]:
        return {
            "positive": [
                "这款产品真的太好了，质量很棒，推荐购买！",
                "客服态度很好，非常满意这次购物体验。",
                "物流速度超快，包装也很仔细，非常满意。",
                "产品质量很好，价格也实惠，下次还会再来。",
                "售后服务很贴心，问题很快就解决了，点赞！"
            ],
            "negative": [
                "这款产品质量太差了，根本不能用，非常失望。",
                "客服态度很差，问半天没人理，再也不会来买了。",
                "物流太慢了，等了一个星期才收到，非常不满意。",
                "产品质量差，价格还贵，感觉被骗了。",
                "售后服务很差，问题迟迟得不到解决，太坑了。"
            ],
            "neutral": [
                "这款产品一般般，没有想象中的好。",
                "价格还行，质量也还可以。",
                "客服态度一般，不算好也不算差。",
                "物流速度一般，中规中矩。",
                "产品整体表现一般，不推荐也不排斥。"
            ]
        }

    @staticmethod
    def generate_training_data() -> List[Dict]:
        return [
            {"text": "这款产品质量很好，做工精细，使用效果非常棒。", "labels": ["产品质量"]},
            {"text": "价格比其他店铺便宜很多，性价比很高，非常划算。", "labels": ["价格"]},
            {"text": "客服态度很好，有问必答，解决问题非常及时。", "labels": ["客服服务"]},
            {"text": "物流速度很快，当天就发货了，第二天就收到了。", "labels": ["物流配送"]},
            {"text": "售后服务很好，有问题很快就解决了。", "labels": ["售后"]},
            {"text": "产品质量一般，做工比较粗糙，价格也偏高。", "labels": ["产品质量", "价格"]},
            {"text": "客服态度不错，价格有点贵，不过质量还可以。", "labels": ["客服服务", "价格", "产品质量"]},
            {"text": "物流太慢了，客服也不给力，问半天没人理。", "labels": ["物流配送", "客服服务"]},
            {"text": "产品质量很好，价格也实惠，物流也很快，客服也很好。", "labels": ["产品质量", "价格", "物流配送", "客服服务"]},
            {"text": "收到货发现有问题，联系售后很快就解决了。", "labels": ["售后", "客服服务"]},
        ]

    @staticmethod
    def generate_special_characters_texts() -> List[str]:
        return [
            "",
            "   ",
            "<p>这是一段包含HTML标签的文本</p>",
            "访问http://example.com了解更多信息",
            "联系我们：test@example.com",
            "!!!@@@###$$$%%%^&&*()",
            "    前后有空格的文本    ",
            "\n\n\n多行\n文本\n\n\n",
            "\t制表符\t文本\t",
            "emoji测试：😊😂❤️👍🔥",
        ]

    @staticmethod
    def generate_long_short_texts() -> Dict[str, str]:
        short_text = "产品很好。"
        long_text = """
        这款产品真的非常值得推荐。首先，产品质量非常好，做工精细，
        每一个细节都处理得很到位。其次，价格也非常实惠，相比其他品牌，
        性价比确实很高。另外，客服态度也很好，有问必答，解决问题非常及时。
        物流速度也很快，下单当天就发货了，第二天就收到了。包装也很仔细，
        完全不用担心会损坏。售后服务也很棒，有任何问题都能得到及时解决。
        总之，这是一次非常愉快的购物体验，强烈推荐给大家！
        """
        return {"short": short_text, "long": long_text}


@pytest.fixture(scope="session")
def test_db():
    test_engine = create_engine(TEST_DATABASE_URL, connect_args={"check_same_thread": False})
    Base.metadata.create_all(bind=test_engine)
    TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=test_engine)
    session = TestingSessionLocal()
    yield session
    session.close()
    Base.metadata.drop_all(bind=test_engine)


@pytest.fixture
def mock_data():
    return MockDataGenerator()


@pytest.fixture
def sample_texts():
    return MockDataGenerator.generate_sample_texts()


@pytest.fixture
def sentiment_texts():
    return MockDataGenerator.generate_sentiment_texts()


@pytest.fixture
def training_data():
    return MockDataGenerator.generate_training_data()


@pytest.fixture
def special_characters_texts():
    return MockDataGenerator.generate_special_characters_texts()


@pytest.fixture
def long_short_texts():
    return MockDataGenerator.generate_long_short_texts()


@pytest.fixture
def text_preprocessor_instance():
    return TextPreprocessor()


@pytest.fixture
def classifier_instance():
    return TextClassifier()


@pytest.fixture
def sentiment_analyzer_instance():
    return SentimentAnalyzer()


@pytest.fixture
def keyword_extractor_instance():
    return KeywordExtractor()


@pytest.fixture
def trainer_instance():
    return Trainer()


@pytest.fixture
def evaluator_instance():
    return Evaluator()


@pytest.fixture
def model_manager_instance():
    return ModelManager()


@pytest.fixture
def training_service_instance():
    return TrainingService()


@pytest.fixture
def mock_labels():
    return ["产品质量", "价格", "客服服务", "物流配送", "售后"]


@pytest.fixture
def mock_classification_labels():
    return [
        {"label": "产品质量", "confidence": 0.85},
        {"label": "客服服务", "confidence": 0.72},
    ]


@pytest.fixture
def mock_sentiment_result():
    return {
        "label": "positive",
        "confidence": 0.91
    }


@pytest.fixture
def mock_keywords():
    return ["质量", "客服", "服务态度"]


@pytest.fixture
def mock_classification_result():
    return {
        "result_id": "result_001",
        "request_id": "req_001",
        "text": "这款产品质量很好，客服服务态度也不错",
        "categories": [
            {"label": "产品质量", "confidence": 0.85},
            {"label": "客服服务", "confidence": 0.72}
        ],
        "sentiment": {"label": "positive", "confidence": 0.91},
        "keywords": ["质量", "客服", "服务态度"],
        "model_version": "v1.0.0",
        "confidence_threshold": 0.6,
        "classified_at": "2026-05-04T13:10:00Z"
    }
