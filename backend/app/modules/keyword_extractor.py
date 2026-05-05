import math
from collections import Counter
from typing import List, Dict, Optional
from app.core.config import settings
from app.modules.preprocessing import text_preprocessor


class KeywordExtractor:
    def __init__(
        self,
        max_keywords: int = None,
        min_keyword_length: int = None
    ):
        self.max_keywords = max_keywords or settings.MAX_KEYWORDS_COUNT
        self.min_keyword_length = min_keyword_length or settings.MIN_KEYWORD_LENGTH
        self._idf_cache = {}
        self._document_count = 0

    def _calculate_tf(self, tokens: List[str]) -> Dict[str, float]:
        if not tokens:
            return {}

        word_counts = Counter(tokens)
        total_words = len(tokens)

        tf = {}
        for word, count in word_counts.items():
            if len(word) >= self.min_keyword_length:
                tf[word] = count / total_words

        return tf

    def _calculate_idf(self, documents: List[List[str]]) -> Dict[str, float]:
        if not documents:
            return {}

        doc_count = len(documents)
        word_doc_count = {}

        for doc_tokens in documents:
            unique_words = set(doc_tokens)
            for word in unique_words:
                if len(word) >= self.min_keyword_length:
                    word_doc_count[word] = word_doc_count.get(word, 0) + 1

        idf = {}
        for word, doc_appearances in word_doc_count.items():
            idf[word] = math.log((doc_count + 1) / (doc_appearances + 1)) + 1

        return idf

    def _calculate_tfidf(
        self,
        tf: Dict[str, float],
        idf: Dict[str, float]
    ) -> Dict[str, float]:
        tfidf = {}
        for word, tf_value in tf.items():
            idf_value = idf.get(word, 1.0)
            tfidf[word] = tf_value * idf_value
        return tfidf

    def extract(
        self,
        text: str,
        max_keywords: Optional[int] = None,
        use_paddle: bool = False
    ) -> Dict:
        if not text or not isinstance(text, str):
            return {
                "keywords": [],
                "status": "error",
                "message": "文本为空或无效"
            }

        try:
            preprocess_result = text_preprocessor.preprocess(text, use_paddle=use_paddle)
            if preprocess_result["status"] != "success":
                return {
                    "keywords": [],
                    "status": "error",
                    "message": preprocess_result["message"]
                }

            filtered_tokens = preprocess_result["filtered_tokens"]
            if not filtered_tokens:
                return {
                    "keywords": [],
                    "status": "error",
                    "message": "文本预处理后无有效词汇"
                }

            tf = self._calculate_tf(filtered_tokens)

            if not self._idf_cache:
                idf = {word: 1.0 for word in tf.keys()}
            else:
                idf = self._idf_cache

            tfidf = self._calculate_tfidf(tf, idf)

            sorted_keywords = sorted(tfidf.items(), key=lambda x: x[1], reverse=True)

            max_kw = max_keywords or self.max_keywords
            top_keywords = sorted_keywords[:max_kw]

            keywords = []
            for word, score in top_keywords:
                keywords.append({
                    "word": word,
                    "score": float(score)
                })

            keyword_words = [kw["word"] for kw in keywords]

            return {
                "keywords": keyword_words,
                "keywords_with_scores": keywords,
                "status": "success",
                "message": "关键词提取成功",
                "total_tokens": len(filtered_tokens),
                "unique_tokens": len(set(filtered_tokens))
            }

        except Exception as e:
            return {
                "keywords": [],
                "status": "error",
                "message": f"关键词提取异常: {str(e)}"
            }

    def extract_batch(
        self,
        texts: List[str],
        max_keywords: Optional[int] = None,
        use_paddle: bool = False
    ) -> List[Dict]:
        results = []
        for text in texts:
            result = self.extract(
                text=text,
                max_keywords=max_keywords,
                use_paddle=use_paddle
            )
            results.append(result)
        return results

    def train_idf(self, documents: List[str], use_paddle: bool = False):
        if not documents:
            return False

        processed_documents = []
        for doc in documents:
            preprocess_result = text_preprocessor.preprocess(doc, use_paddle=use_paddle)
            if preprocess_result["status"] == "success":
                processed_documents.append(preprocess_result["filtered_tokens"])

        if not processed_documents:
            return False

        self._idf_cache = self._calculate_idf(processed_documents)
        self._document_count = len(processed_documents)

        return True

    def get_idf_cache(self) -> Dict[str, float]:
        return self._idf_cache.copy()

    def get_document_count(self) -> int:
        return self._document_count

    def clear_idf_cache(self):
        self._idf_cache = {}
        self._document_count = 0


keyword_extractor = KeywordExtractor()
