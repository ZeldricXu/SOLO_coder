import logging
import jieba
from typing import List, Dict, Any, Optional, Set
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from searchengine.modules.query_processor import query_processor


class KeywordModule:
    def __init__(self):
        self.logger = logging.getLogger(__name__)
        self._keyword_counts: Dict[str, int] = defaultdict(int)
        self._keyword_clicks: Dict[str, int] = defaultdict(int)
        self._stopwords: Set[str] = self._load_default_stopwords()
    
    def _load_default_stopwords(self) -> Set[str]:
        return {
            "的", "了", "是", "在", "我", "有", "和", "就",
            "不", "人", "都", "一", "一个", "上", "也", "很",
            "到", "说", "要", "去", "你", "会", "着", "没有",
            "看", "好", "自己", "这", "那", "他", "她", "它",
            "吗", "呢", "啊", "吧", "呀", "哦", "嗯", "哈",
            "the", "a", "an", "is", "are", "was", "were",
            "be", "been", "being", "have", "has", "had",
            "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "must", "shall",
            "i", "you", "he", "she", "it", "we", "they",
            "me", "him", "her", "us", "them", "my", "your",
            "his", "its", "our", "their", "this", "that",
            "these", "those", "what", "which", "who", "whom",
            "whose", "how", "when", "where", "why", "if",
            "then", "else", "and", "or", "but", "not", "so",
            "too", "very", "just", "also", "now", "here",
            "there", "than", "then", "as", "of", "to",
            "in", "for", "on", "with", "at", "by", "from",
            "up", "out", "about", "into", "over", "under",
            "again", "further", "once", "more", "most",
            "other", "some", "such", "no", "nor", "only",
            "own", "same", "all", "any", "both", "each",
            "few", "more", "most", "other", "some", "such"
        }
    
    def analyze_keyword(self, text: str) -> Dict[str, Any]:
        tokens = query_processor.extract_keywords(text)
        
        filtered_tokens = [
            token for token in tokens
            if token and token.strip() and len(token) > 1
            and token.lower() not in self._stopwords
        ]
        
        token_counts = Counter(filtered_tokens)
        
        total_count = sum(token_counts.values())
        frequencies = {}
        for token, count in token_counts.items():
            frequencies[token] = count / total_count if total_count > 0 else 0
        
        sorted_keywords = sorted(
            frequencies.items(),
            key=lambda x: (-x[1], len(x[0]))
        )
        
        return {
            "original_text": text,
            "tokens": tokens,
            "filtered_tokens": filtered_tokens,
            "token_counts": dict(token_counts),
            "frequencies": frequencies,
            "top_keywords": [kw for kw, _ in sorted_keywords[:10]],
            "unique_keywords": list(set(filtered_tokens))
        }
    
    def record_search_keyword(self, keyword: str) -> None:
        analysis = self.analyze_keyword(keyword)
        for kw in analysis["unique_keywords"]:
            self._keyword_counts[kw] += 1
        self.logger.debug(f"Recorded search keyword: {keyword}")
    
    def record_click_keyword(self, keyword: str) -> None:
        analysis = self.analyze_keyword(keyword)
        for kw in analysis["unique_keywords"]:
            self._keyword_clicks[kw] += 1
        self.logger.debug(f"Recorded click keyword: {keyword}")
    
    def get_hot_keywords(self, top_n: int = 20) -> List[Dict[str, Any]]:
        sorted_keywords = sorted(
            self._keyword_counts.items(),
            key=lambda x: (-x[1], x[0])
        )
        
        hot_keywords = []
        for keyword, count in sorted_keywords[:top_n]:
            click_count = self._keyword_clicks.get(keyword, 0)
            ctr = click_count / count if count > 0 else 0.0
            hot_keywords.append({
                "keyword": keyword,
                "search_count": count,
                "click_count": click_count,
                "ctr": round(ctr, 4)
            })
        
        return hot_keywords
    
    def get_keyword_stats(self, keyword: str) -> Dict[str, Any]:
        analysis = self.analyze_keyword(keyword)
        search_count = 0
        click_count = 0
        
        for kw in analysis["unique_keywords"]:
            search_count += self._keyword_counts.get(kw, 0)
            click_count += self._keyword_clicks.get(kw, 0)
        
        ctr = click_count / search_count if search_count > 0 else 0.0
        
        return {
            "keyword": keyword,
            "analyzed_tokens": analysis["unique_keywords"],
            "search_count": search_count,
            "click_count": click_count,
            "ctr": round(ctr, 4)
        }
    
    def suggest_related_keywords(self, keyword: str, limit: int = 10) -> List[str]:
        analysis = self.analyze_keyword(keyword)
        input_keywords = set(analysis["unique_keywords"])
        
        if not input_keywords:
            return []
        
        all_searched = list(self._keyword_counts.keys())
        suggestions = []
        
        for kw in all_searched:
            if kw in input_keywords:
                continue
            
            kw_analysis = self.analyze_keyword(kw)
            kw_tokens = set(kw_analysis["unique_keywords"])
            
            overlap = input_keywords & kw_tokens
            if overlap:
                score = (
                    len(overlap) / len(input_keywords) * 0.5 +
                    self._keyword_counts.get(kw, 0) / 100.0 * 0.3 +
                    self._keyword_clicks.get(kw, 0) / 50.0 * 0.2
                )
                suggestions.append((kw, score))
        
        suggestions.sort(key=lambda x: x[1], reverse=True)
        return [kw for kw, _ in suggestions[:limit]]
    
    def extract_keywords_from_content(self, content: str, title: str = "", limit: int = 10) -> List[str]:
        title_tokens = query_processor.extract_keywords(title)
        content_tokens = query_processor.extract_keywords(content)
        
        all_tokens = title_tokens * 2 + content_tokens
        
        filtered_tokens = [
            token for token in all_tokens
            if token and token.strip() and len(token) > 1
            and token.lower() not in self._stopwords
        ]
        
        token_counts = Counter(filtered_tokens)
        
        sorted_tokens = sorted(
            token_counts.items(),
            key=lambda x: (-x[1], -len(x[0]))
        )
        
        return [token for token, _ in sorted_tokens[:limit]]
    
    def get_all_keywords(self) -> List[str]:
        return list(self._keyword_counts.keys())
    
    def clear_keyword_stats(self) -> None:
        self._keyword_counts.clear()
        self._keyword_clicks.clear()
        self.logger.info("Cleared all keyword statistics")
    
    def merge_keyword_stats(self, other: "KeywordModule") -> None:
        for kw, count in other._keyword_counts.items():
            self._keyword_counts[kw] += count
        for kw, count in other._keyword_clicks.items():
            self._keyword_clicks[kw] += count
    
    def add_stopword(self, word: str) -> None:
        self._stopwords.add(word.lower())
    
    def remove_stopword(self, word: str) -> None:
        word_lower = word.lower()
        if word_lower in self._stopwords:
            self._stopwords.remove(word_lower)
    
    def get_stopwords(self) -> Set[str]:
        return self._stopwords.copy()


keyword_module = KeywordModule()
