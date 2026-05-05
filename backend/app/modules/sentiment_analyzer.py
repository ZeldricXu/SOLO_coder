import re
from typing import Dict, List, Optional
from app.core.config import settings


class SentimentAnalyzer:
    def __init__(self, model_name: Optional[str] = None):
        self.model_name = model_name or settings.SENTIMENT_MODEL_NAME
        self.model = None
        self.tokenizer = None
        self.pipeline = None
        self._fallback_enabled = True
        self._positive_keywords = [
            "好", "很好", "非常好", "棒", "很棒", "非常棒", "优秀", "出色",
            "满意", "很满意", "非常满意", "喜欢", "很喜欢", "非常喜欢",
            "开心", "高兴", "愉快", "快乐", "幸福", "满足",
            "便宜", "实惠", "划算", "超值", "值得",
            "快", "很快", "迅速", "及时", "准时",
            "热情", "耐心", "周到", "贴心", "专业",
            "不错", "可以", "还行", "挺好", "挺不错",
            "好评", "推荐", "会再来", "下次还买",
            "positive", "good", "great", "excellent", "wonderful",
            "amazing", "fantastic", "terrific", "superb", "perfect"
        ]
        self._negative_keywords = [
            "差", "很差", "非常差", "烂", "很烂", "非常烂",
            "糟糕", "很糟糕", "非常糟糕", "失望", "很失望", "非常失望",
            "不满意", "很不满意", "非常不满意", "讨厌", "很讨厌", "非常讨厌",
            "生气", "气愤", "愤怒", "恼火", "郁闷", "难过",
            "贵", "很贵", "非常贵", "不值", "不划算",
            "慢", "很慢", "非常慢", "迟到", "延误", "拖拉",
            "冷淡", "不耐烦", "敷衍", "不专业", "不负责任",
            "不好", "不行", "很差劲", "糟糕透了", "垃圾",
            "差评", "不推荐", "不会再来", "再也不买",
            "negative", "bad", "poor", "terrible", "awful",
            "horrible", "disappointing", "unsatisfactory", "worst"
        ]
        self._neutral_keywords = [
            "一般", "普通", "还行", "差不多", "还可以",
            "neutral", "okay", "fine", "alright", "so-so"
        ]

    def _load_huggingface_model(self):
        try:
            from transformers import pipeline, AutoTokenizer, AutoModelForSequenceClassification

            self.tokenizer = AutoTokenizer.from_pretrained(self.model_name)
            self.model = AutoModelForSequenceClassification.from_pretrained(self.model_name)
            self.pipeline = pipeline(
                "sentiment-analysis",
                model=self.model,
                tokenizer=self.tokenizer,
                truncation=True,
                max_length=512
            )
            return True
        except Exception as e:
            print(f"加载HuggingFace情感模型失败: {e}")
            return False

    def _analyze_with_huggingface(self, text: str) -> Dict:
        if not self.pipeline:
            if not self._load_huggingface_model():
                return None

        try:
            result = self.pipeline(text)[0]
            label = result["label"].lower()
            confidence = result["score"]

            sentiment_map = {
                "positive": "positive",
                "negative": "negative",
                "neutral": "neutral",
                "label_0": "negative",
                "label_1": "positive"
            }

            sentiment_label = sentiment_map.get(label, "neutral")

            return {
                "label": sentiment_label,
                "confidence": float(confidence),
                "method": "huggingface"
            }
        except Exception as e:
            print(f"HuggingFace情感分析失败: {e}")
            return None

    def _analyze_with_keywords(self, text: str) -> Dict:
        if not text or not isinstance(text, str):
            return {
                "label": "neutral",
                "confidence": 0.5,
                "method": "keyword"
            }

        text_lower = text.lower()

        positive_count = sum(1 for kw in self._positive_keywords if kw in text_lower)
        negative_count = sum(1 for kw in self._negative_keywords if kw in text_lower)
        neutral_count = sum(1 for kw in self._neutral_keywords if kw in text_lower)

        total = positive_count + negative_count + neutral_count

        if total == 0:
            return {
                "label": "neutral",
                "confidence": 0.5,
                "method": "keyword"
            }

        positive_ratio = positive_count / total
        negative_ratio = negative_count / total
        neutral_ratio = neutral_count / total

        if positive_ratio > negative_ratio and positive_ratio > neutral_ratio:
            return {
                "label": "positive",
                "confidence": float(positive_ratio),
                "method": "keyword"
            }
        elif negative_ratio > positive_ratio and negative_ratio > neutral_ratio:
            return {
                "label": "negative",
                "confidence": float(negative_ratio),
                "method": "keyword"
            }
        else:
            return {
                "label": "neutral",
                "confidence": float(max(neutral_ratio, 0.5)),
                "method": "keyword"
            }

    def analyze(self, text: str) -> Dict:
        if not text or not isinstance(text, str):
            return {
                "label": "neutral",
                "confidence": 0.5,
                "status": "error",
                "message": "文本为空或无效",
                "method": "none"
            }

        try:
            hf_result = self._analyze_with_huggingface(text)
            if hf_result:
                return {
                    **hf_result,
                    "status": "success",
                    "message": "情感分析成功"
                }

            kw_result = self._analyze_with_keywords(text)
            return {
                **kw_result,
                "status": "success",
                "message": "情感分析成功（使用关键词方法）"
            }

        except Exception as e:
            return {
                "label": "neutral",
                "confidence": 0.5,
                "status": "error",
                "message": f"情感分析异常: {str(e)}",
                "method": "none"
            }

    def analyze_batch(self, texts: List[str]) -> List[Dict]:
        results = []
        for text in texts:
            result = self.analyze(text)
            results.append(result)
        return results

    def get_available_methods(self) -> List[str]:
        methods = ["keyword"]
        if self.pipeline or self._load_huggingface_model():
            methods.append("huggingface")
        return methods


sentiment_analyzer = SentimentAnalyzer()
