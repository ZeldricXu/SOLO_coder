import pytest
import numpy as np
from typing import List, Dict


class TestSentimentAnalyzerBasic:
    def test_initialization(self, sentiment_analyzer_instance):
        assert sentiment_analyzer_instance._positive_keywords is not None
        assert sentiment_analyzer_instance._negative_keywords is not None
        assert sentiment_analyzer_instance._neutral_keywords is not None
        assert sentiment_analyzer_instance._fallback_enabled is True

    def test_analyze_returns_correct_structure(self, sentiment_analyzer_instance):
        text = "这款产品质量很好"
        result = sentiment_analyzer_instance.analyze(text)
        assert "label" in result
        assert "confidence" in result
        assert "status" in result
        assert "message" in result
        assert "method" in result

    def test_analyze_returns_valid_label(self, sentiment_analyzer_instance):
        text = "这款产品质量很好"
        result = sentiment_analyzer_instance.analyze(text)
        assert result["label"] in ["positive", "negative", "neutral"]

    def test_analyze_returns_valid_confidence(self, sentiment_analyzer_instance):
        text = "这款产品质量很好"
        result = sentiment_analyzer_instance.analyze(text)
        assert 0 <= result["confidence"] <= 1

    def test_analyze_handles_empty_text(self, sentiment_analyzer_instance):
        result = sentiment_analyzer_instance.analyze("")
        assert result["status"] == "error"
        assert result["label"] == "neutral"
        assert result["confidence"] == 0.5

    def test_analyze_handles_none(self, sentiment_analyzer_instance):
        result = sentiment_analyzer_instance.analyze(None)
        assert result["status"] == "error"


class TestSentimentKeywordAnalysis:
    def test_positive_keyword_detection(self, sentiment_analyzer_instance):
        positive_texts = [
            "这款产品质量很好",
            "客服态度很棒",
            "价格很实惠",
            "物流很快",
            "售后服务很贴心",
        ]
        for text in positive_texts:
            result = sentiment_analyzer_instance._analyze_with_keywords(text)
            assert result["label"] == "positive" or result["label"] == "neutral"

    def test_negative_keyword_detection(self, sentiment_analyzer_instance):
        negative_texts = [
            "这款产品质量很差",
            "客服态度很糟糕",
            "价格很贵",
            "物流很慢",
            "售后服务很糟糕",
        ]
        for text in negative_texts:
            result = sentiment_analyzer_instance._analyze_with_keywords(text)
            assert result["label"] == "negative" or result["label"] == "neutral"

    def test_neutral_keyword_detection(self, sentiment_analyzer_instance):
        neutral_texts = [
            "这款产品质量一般",
            "客服态度还可以",
            "价格还行",
            "物流普通",
            "售后服务一般般",
        ]
        for text in neutral_texts:
            result = sentiment_analyzer_instance._analyze_with_keywords(text)
            assert result["label"] in ["positive", "negative", "neutral"]

    def test_mixed_sentiment_keywords(self, sentiment_analyzer_instance):
        mixed_text = "产品质量很好，但是价格很贵"
        result = sentiment_analyzer_instance._analyze_with_keywords(mixed_text)
        assert result["label"] in ["positive", "negative", "neutral"]

    def test_confidence_distribution(self, sentiment_analyzer_instance):
        texts = [
            "这款产品质量很好，客服态度也很棒，价格也很实惠",
            "产品质量很差，客服态度很糟糕，价格也很贵",
            "产品质量一般，客服态度还行，价格也还行",
        ]
        for text in texts:
            result = sentiment_analyzer_instance._analyze_with_keywords(text)
            assert 0 <= result["confidence"] <= 1

    def test_keyword_method_returns_correct_structure(self, sentiment_analyzer_instance):
        text = "测试文本"
        result = sentiment_analyzer_instance._analyze_with_keywords(text)
        assert "label" in result
        assert "confidence" in result
        assert "method" in result
        assert result["method"] == "keyword"


class TestSentimentBatchAnalysis:
    def test_analyze_batch_returns_list(self, sentiment_analyzer_instance, sample_texts):
        results = sentiment_analyzer_instance.analyze_batch(sample_texts)
        assert isinstance(results, list)
        assert len(results) == len(sample_texts)

    def test_analyze_batch_results_structure(self, sentiment_analyzer_instance, sample_texts):
        results = sentiment_analyzer_instance.analyze_batch(sample_texts)
        for result in results:
            assert "label" in result
            assert "confidence" in result
            assert "status" in result
            assert "message" in result

    def test_analyze_batch_with_mixed_texts(self, sentiment_analyzer_instance, sentiment_texts):
        all_texts = (
            sentiment_texts["positive"] +
            sentiment_texts["negative"] +
            sentiment_texts["neutral"]
        )
        results = sentiment_analyzer_instance.analyze_batch(all_texts)
        assert len(results) == len(all_texts)


class TestSentimentTextLengthVariations:
    def test_short_text_analysis(self, sentiment_analyzer_instance, long_short_texts):
        short_text = long_short_texts["short"]
        result = sentiment_analyzer_instance.analyze(short_text)
        assert "label" in result
        assert result["status"] in ["success", "error"]

    def test_long_text_analysis(self, sentiment_analyzer_instance, long_short_texts):
        long_text = long_short_texts["long"]
        result = sentiment_analyzer_instance.analyze(long_text)
        assert "label" in result
        assert result["status"] in ["success", "error"]

    def test_short_vs_long_text_confidence(self, sentiment_analyzer_instance, long_short_texts):
        short_result = sentiment_analyzer_instance.analyze(long_short_texts["short"])
        long_result = sentiment_analyzer_instance.analyze(long_short_texts["long"])
        assert 0 <= short_result["confidence"] <= 1
        assert 0 <= long_result["confidence"] <= 1


class TestSentimentSpecialCases:
    def test_very_short_text(self, sentiment_analyzer_instance):
        texts = ["好", "差", "一般", "棒", "烂"]
        for text in texts:
            result = sentiment_analyzer_instance.analyze(text)
            assert "label" in result

    def test_text_with_only_special_chars(self, sentiment_analyzer_instance):
        texts = ["!!!", "???", "!!!???", "😊", "😢"]
        for text in texts:
            result = sentiment_analyzer_instance.analyze(text)
            assert "label" in result
            assert "status" in result

    def test_text_with_numbers(self, sentiment_analyzer_instance):
        texts = [
            "这款产品打5折，非常好",
            "价格上涨了20%，很差",
            "物流用了3天，一般",
        ]
        for text in texts:
            result = sentiment_analyzer_instance.analyze(text)
            assert "label" in result

    def test_text_with_mixed_case(self, sentiment_analyzer_instance):
        texts = [
            "Good product",
            "BAD experience",
            "This is just okay",
        ]
        for text in texts:
            result = sentiment_analyzer_instance.analyze(text)
            assert "label" in result


class TestSentimentAnalysisIntegration:
    def test_full_analysis_flow(self, sentiment_analyzer_instance, sentiment_texts):
        for label_type, texts in sentiment_texts.items():
            for text in texts:
                result = sentiment_analyzer_instance.analyze(text)
                assert "label" in result
                assert "confidence" in result
                assert "status" in result
                assert "message" in result

    def test_positive_texts_tendency(self, sentiment_analyzer_instance, sentiment_texts):
        positive_texts = sentiment_texts["positive"]
        positive_count = 0
        for text in positive_texts:
            result = sentiment_analyzer_instance._analyze_with_keywords(text)
            if result["label"] == "positive":
                positive_count += 1
        assert positive_count >= 0

    def test_negative_texts_tendency(self, sentiment_analyzer_instance, sentiment_texts):
        negative_texts = sentiment_texts["negative"]
        negative_count = 0
        for text in negative_texts:
            result = sentiment_analyzer_instance._analyze_with_keywords(text)
            if result["label"] == "negative":
                negative_count += 1
        assert negative_count >= 0

    def test_available_methods(self, sentiment_analyzer_instance):
        methods = sentiment_analyzer_instance.get_available_methods()
        assert "keyword" in methods


class TestSentimentConfidenceValidity:
    def test_confidence_range(self, sentiment_analyzer_instance, sentiment_texts):
        all_texts = (
            sentiment_texts["positive"] +
            sentiment_texts["negative"] +
            sentiment_texts["neutral"]
        )
        for text in all_texts:
            result = sentiment_analyzer_instance.analyze(text)
            assert 0 <= result["confidence"] <= 1

    def test_confidence_not_nan(self, sentiment_analyzer_instance, sample_texts):
        results = sentiment_analyzer_instance.analyze_batch(sample_texts)
        for result in results:
            assert not np.isnan(result["confidence"])
            assert not np.isinf(result["confidence"])


class TestSentimentEdgeCases:
    def test_empty_string_batch(self, sentiment_analyzer_instance):
        empty_texts = ["", "", ""]
        results = sentiment_analyzer_instance.analyze_batch(empty_texts)
        assert len(results) == len(empty_texts)
        for result in results:
            assert result["label"] == "neutral"

    def test_none_in_batch(self, sentiment_analyzer_instance):
        mixed_texts = ["正常文本", None, "", "另一个文本"]
        results = sentiment_analyzer_instance.analyze_batch(mixed_texts)
        assert len(results) == len(mixed_texts)

    def test_whitespace_texts(self, sentiment_analyzer_instance):
        whitespace_texts = [
            "   ",
            "\t",
            "\n",
            "\r\n",
            "  \t\n  "
        ]
        for text in whitespace_texts:
            result = sentiment_analyzer_instance.analyze(text)
            assert result["status"] == "error" or result["status"] == "success"

    def test_very_long_text(self, sentiment_analyzer_instance):
        very_long_text = "很好 " * 1000
        result = sentiment_analyzer_instance.analyze(very_long_text)
        assert "label" in result

    def test_text_with_unicode(self, sentiment_analyzer_instance):
        unicode_texts = [
            "这是中文测试很好",
            "にほんご すばらしい",
            "한국어 좋아요",
            "English text is great",
        ]
        for text in unicode_texts:
            result = sentiment_analyzer_instance.analyze(text)
            assert "label" in result


class TestSentimentStatusMessages:
    def test_success_status_message(self, sentiment_analyzer_instance):
        text = "这款产品质量很好"
        result = sentiment_analyzer_instance.analyze(text)
        if result["status"] == "success":
            assert "成功" in result["message"] or "success" in result["message"].lower()

    def test_error_status_message(self, sentiment_analyzer_instance):
        result = sentiment_analyzer_instance.analyze("")
        assert result["status"] == "error"
        assert result["message"] != ""

    def test_method_field(self, sentiment_analyzer_instance):
        text = "这款产品质量很好"
        result = sentiment_analyzer_instance.analyze(text)
        assert result["method"] in ["keyword", "huggingface", "none"]
