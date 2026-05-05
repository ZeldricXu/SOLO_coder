import pytest
import numpy as np
from typing import List, Dict
from pathlib import Path


class TestTextPreprocessor:
    def test_clean_text_removes_html_tags(self, text_preprocessor_instance):
        html_text = "<p>这是一段包含HTML标签的文本</p>"
        result = text_preprocessor_instance.clean_text(html_text)
        assert "<p>" not in result
        assert "</p>" not in result
        assert "这是一段包含HTML标签的文本" in result

    def test_clean_text_removes_urls(self, text_preprocessor_instance):
        url_text = "访问http://example.com了解更多信息"
        result = text_preprocessor_instance.clean_text(url_text)
        assert "http://" not in result

    def test_clean_text_removes_emails(self, text_preprocessor_instance):
        email_text = "联系我们：test@example.com获取更多信息"
        result = text_preprocessor_instance.clean_text(email_text)
        assert "@" not in result or "test@example.com" not in result

    def test_tokenize_produces_tokens(self, text_preprocessor_instance):
        text = "这款产品质量很好"
        tokens = text_preprocessor_instance.tokenize(text)
        assert isinstance(tokens, list)
        assert len(tokens) > 0

    def test_filter_stopwords_removes_common_words(self, text_preprocessor_instance):
        tokens = ["的", "产品", "质量", "很好", "了"]
        filtered = text_preprocessor_instance.filter_stopwords(tokens)
        assert "的" not in filtered
        assert "了" not in filtered
        assert "产品" in filtered
        assert "质量" in filtered

    def test_preprocess_returns_correct_structure(self, text_preprocessor_instance):
        text = "这款产品质量很好"
        result = text_preprocessor_instance.preprocess(text)
        assert "original_text" in result
        assert "cleaned_text" in result
        assert "tokens" in result
        assert "filtered_tokens" in result
        assert "status" in result
        assert "message" in result

    def test_preprocess_handles_empty_text(self, text_preprocessor_instance):
        result = text_preprocessor_instance.preprocess("")
        assert result["status"] == "error"
        assert len(result["filtered_tokens"]) == 0

    def test_preprocess_handles_none(self, text_preprocessor_instance):
        result = text_preprocessor_instance.preprocess(None)
        assert result["status"] == "error"


class TestTextClassifier:
    def test_classifier_initialization(self, classifier_instance):
        assert classifier_instance.model is None or classifier_instance.model is not None
        assert classifier_instance.labels is not None
        assert isinstance(classifier_instance.labels, list)

    def test_load_model_creates_default_if_not_exists(self, classifier_instance):
        result = classifier_instance.load_model()
        assert result is True
        assert classifier_instance.model is not None
        assert classifier_instance.vectorizer is not None

    def test_predict_returns_correct_structure(self, classifier_instance):
        classifier_instance.load_model()
        text = "这款产品质量很好"
        result = classifier_instance.predict(text)
        assert "categories" in result
        assert "status" in result
        assert "message" in result
        assert "model_version" in result
        assert "confidence_threshold" in result

    def test_predict_returns_list_of_categories(self, classifier_instance):
        classifier_instance.load_model()
        text = "这款产品质量很好"
        result = classifier_instance.predict(text)
        assert isinstance(result["categories"], list)
        for category in result["categories"]:
            assert "label" in category
            assert "confidence" in category
            assert isinstance(category["confidence"], (int, float))

    def test_predict_handles_empty_text(self, classifier_instance):
        classifier_instance.load_model()
        result = classifier_instance.predict("")
        assert result["status"] == "error"
        assert len(result["categories"]) == 0

    def test_predict_handles_none(self, classifier_instance):
        classifier_instance.load_model()
        result = classifier_instance.predict(None)
        assert result["status"] == "error"

    def test_confidence_threshold_filtering(self, classifier_instance):
        classifier_instance.load_model()
        classifier_instance.labels = ["产品质量", "价格", "客服服务", "物流配送", "售后"]
        text = "这款产品质量很好"
        high_threshold = 0.99
        low_threshold = 0.01

        result_high = classifier_instance.predict(text, confidence_threshold=high_threshold)
        result_low = classifier_instance.predict(text, confidence_threshold=low_threshold)

        assert len(result_high["categories"]) <= len(result_low["categories"])

    def test_predict_batch_returns_correct_structure(self, classifier_instance, sample_texts):
        classifier_instance.load_model()
        result = classifier_instance.predict_batch(sample_texts)
        assert result.success is True
        assert isinstance(result.results, list)
        assert result.total_count == len(sample_texts)
        assert result.success_count + result.failed_count == result.total_count

    def test_predict_batch_contains_all_inputs(self, classifier_instance, sample_texts):
        classifier_instance.load_model()
        result = classifier_instance.predict_batch(sample_texts)
        assert len(result.results) == len(sample_texts)
        for i, r in enumerate(result.results):
            assert r["text"] == sample_texts[i]

    def test_predict_batch_result_structure(self, classifier_instance, sample_texts):
        classifier_instance.load_model()
        result = classifier_instance.predict_batch(sample_texts)
        for r in result.results:
            assert "index" in r
            assert "text" in r
            assert "categories" in r
            assert "status" in r
            assert "message" in r
            assert "model_version" in r
            assert "confidence_threshold" in r

    def test_predict_batch_with_empty_list(self, classifier_instance):
        classifier_instance.load_model()
        result = classifier_instance.predict_batch([])
        assert result.success is False or (result.success and result.total_count == 0)

    def test_predict_batch_with_special_characters(self, classifier_instance, special_characters_texts):
        classifier_instance.load_model()
        result = classifier_instance.predict_batch(special_characters_texts)
        assert len(result.results) == len(special_characters_texts)

    def test_predict_calls_predict_batch_internally(self, classifier_instance, monkeypatch):
        called = False
        original_predict_batch = classifier_instance.predict_batch

        def mock_predict_batch(texts, *args, **kwargs):
            nonlocal called
            called = True
            return original_predict_batch(texts, *args, **kwargs)

        monkeypatch.setattr(classifier_instance, "predict_batch", mock_predict_batch)
        classifier_instance.load_model()
        classifier_instance.predict("测试文本")
        assert called is True

    def test_batch_inference_merges_tokens_correctly(self, classifier_instance, sample_texts):
        classifier_instance.load_model()
        result = classifier_instance.predict_batch(sample_texts)
        assert result.success_count > 0
        for r in result.results:
            if r["status"] == "success":
                assert isinstance(r["categories"], list)

    def test_batch_inference_splits_results_correctly(self, classifier_instance, sample_texts):
        classifier_instance.load_model()
        result = classifier_instance.predict_batch(sample_texts)
        for i, r in enumerate(result.results):
            assert r["index"] == i
            assert r["text"] == sample_texts[i]

    def test_get_labels_returns_copy(self, classifier_instance):
        original_labels = classifier_instance.get_labels()
        modified_labels = classifier_instance.get_labels()
        modified_labels.append("新标签")
        assert "新标签" not in classifier_instance.get_labels()

    def test_set_labels_updates_labels(self, classifier_instance):
        new_labels = ["标签A", "标签B", "标签C"]
        classifier_instance.set_labels(new_labels)
        assert classifier_instance.get_labels() == new_labels

    def test_get_model_info_returns_correct_info(self, classifier_instance):
        classifier_instance.load_model()
        info = classifier_instance.get_model_info()
        assert "model_version" in info
        assert "labels" in info
        assert "model_path" in info
        assert "vectorizer_path" in info
        assert "is_loaded" in info

    def test_inference_count_tracking(self, classifier_instance):
        classifier_instance.load_model()
        initial_count = classifier_instance._inference_count
        batch_count = classifier_instance._batch_inference_count
        classifier_instance.predict("测试文本1")
        classifier_instance.predict("测试文本2")
        classifier_instance.predict_batch(["批量文本1", "批量文本2", "批量文本3"])
        assert classifier_instance._inference_count >= initial_count + 5
        assert classifier_instance._batch_inference_count >= batch_count + 1


class TestBatchPreprocessing:
    def test_batch_preprocess_returns_valid_indices(self, classifier_instance, sample_texts):
        classifier_instance.load_model()
        preprocess_results, valid_tokens, valid_indices = classifier_instance._preprocess_batch(sample_texts)
        assert len(preprocess_results) == len(sample_texts)
        assert len(valid_tokens) == len(valid_indices)
        for idx in valid_indices:
            assert 0 <= idx < len(sample_texts)

    def test_batch_preprocess_handles_empty_texts(self, classifier_instance, special_characters_texts):
        classifier_instance.load_model()
        preprocess_results, valid_tokens, valid_indices = classifier_instance._preprocess_batch(
            special_characters_texts
        )
        assert len(preprocess_results) == len(special_characters_texts)

    def test_batch_vectorize_returns_sparse_matrix(self, classifier_instance, sample_texts):
        classifier_instance.load_model()
        preprocess_results, valid_tokens, valid_indices = classifier_instance._preprocess_batch(sample_texts)
        if valid_tokens:
            X = classifier_instance._vectorize_batch(valid_tokens)
            assert X is not None

    def test_batch_probability_parsing(self, classifier_instance):
        classifier_instance.load_model()
        classifier_instance.labels = ["产品质量", "价格", "客服服务", "物流配送", "售后"]
        num_samples = 3
        mock_probabilities = [
            np.array([[0.1, 0.9], [0.8, 0.2], [0.3, 0.7]]),
            np.array([[0.2, 0.8], [0.7, 0.3], [0.4, 0.6]]),
            np.array([[0.3, 0.7], [0.6, 0.4], [0.5, 0.5]]),
            np.array([[0.4, 0.6], [0.5, 0.5], [0.6, 0.4]]),
            np.array([[0.5, 0.5], [0.4, 0.6], [0.7, 0.3]]),
        ]
        threshold = 0.5
        categories = classifier_instance._parse_batch_probabilities(
            mock_probabilities, num_samples, threshold
        )
        assert len(categories) == num_samples
        for cat_list in categories:
            assert isinstance(cat_list, list)
            for cat in cat_list:
                assert "label" in cat
                assert "confidence" in cat
                assert cat["confidence"] >= threshold


class TestSpecialCharacterHandling:
    @pytest.mark.parametrize("special_text", [
        "",
        "   ",
        "\n\n\n",
        "\t\t\t",
    ])
    def test_empty_and_whitespace_texts(self, classifier_instance, special_text):
        classifier_instance.load_model()
        result = classifier_instance.predict(special_text)
        assert result["status"] == "error"

    def test_html_handling_in_prediction(self, classifier_instance):
        classifier_instance.load_model()
        html_text = "<div><p>产品质量很好</p></div>"
        result = classifier_instance.predict(html_text)
        assert isinstance(result["categories"], list)

    def test_url_handling_in_prediction(self, classifier_instance):
        classifier_instance.load_model()
        url_text = "产品质量很好，详情见http://example.com"
        result = classifier_instance.predict(url_text)
        assert isinstance(result["categories"], list)

    def test_emoji_handling_in_prediction(self, classifier_instance):
        classifier_instance.load_model()
        emoji_text = "产品质量很好😊👍🔥"
        result = classifier_instance.predict(emoji_text)
        assert isinstance(result["categories"], list)

    def test_special_symbols_handling(self, classifier_instance):
        classifier_instance.load_model()
        symbol_text = "产品质量很好!!!@@@###$$$%%%"
        result = classifier_instance.predict(symbol_text)
        assert isinstance(result["categories"], list)
