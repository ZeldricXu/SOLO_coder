import re
import jieba
from typing import List, Optional
from pathlib import Path
from app.core.config import settings


class TextPreprocessor:
    def __init__(self, stopwords_path: Optional[Path] = None):
        self.stopwords_path = stopwords_path or settings.STOPWORDS_DIR
        self.stopwords = self._load_stopwords()

    def _load_stopwords(self) -> set:
        stopwords = set()
        default_stopwords = {
            "的", "了", "是", "在", "我", "有", "和", "就",
            "不", "人", "都", "一", "一个", "上", "也", "很",
            "到", "说", "要", "去", "你", "会", "着", "没有",
            "看", "好", "自己", "这", "那", "他", "她", "它",
            "们", "什么", "怎么", "为什么", "哪", "哪里", "谁",
            "多少", "几", "啊", "吧", "呢", "吗", "呀", "哦",
            "嗯", "哈", "啦", "呗", "嘛", "啊", "吧", "呢",
            "的话", "的是", "的了", "的话", "的啊", "的吧",
            "是吧", "是啊", "是呢", "是吗", "是嘛", "是呀",
            "在的", "在啊", "在吧", "在呢", "在吗", "在嘛",
            "我的", "你的", "他的", "她的", "它的", "我们的",
            "你们的", "他们的", "她们的", "它们的",
            " ", "　", "\t", "\n", "\r",
            ",", ".", "!", "?", ";", ":", "\"", "'", "`",
            "，", "。", "！", "？", "；", "：", "“", "”",
            "‘", "’", "（", "）", "【", "】", "《", "》",
            "—", "…", "·", "、", "／", "＼", "［", "］",
            "｛", "｝", "｜", "～", "！", "＠", "＃", "￥",
            "％", "＾", "＆", "＊", "（", "）", "＿", "＋",
            "－", "＝", "｜", "＼", "［", "］", "｛", "｝",
            "；", "：", "＂", "＇", "，", "．", "／", "？",
            "～", "！", "＠", "＃", "￥", "％", "＾", "＆",
            "＊", "（", "）", "＿", "＋", "－", "＝", "｜",
            "＼", "［", "］", "｛", "｝", "；", "：", "＂",
            "＇", "，", "．", "／", "？", "～", "！", "＠",
            "＃", "￥", "％", "＾", "＆", "＊", "（", "）",
            "a", "an", "the", "and", "or", "but", "if",
            "because", "as", "until", "while", "of", "at",
            "by", "for", "with", "about", "against", "between",
            "into", "through", "during", "before", "after",
            "above", "below", "to", "from", "up", "down",
            "in", "out", "on", "off", "over", "under", "again",
            "further", "then", "once", "here", "there", "when",
            "where", "why", "how", "all", "each", "few", "more",
            "most", "other", "some", "such", "no", "nor", "not",
            "only", "own", "same", "so", "than", "too", "very",
            "s", "t", "can", "will", "just", "don", "should", "now"
        }
        stopwords.update(default_stopwords)

        stopwords_file = self.stopwords_path / "stopwords.txt"
        if stopwords_file.exists():
            try:
                with open(stopwords_file, "r", encoding="utf-8") as f:
                    for line in f:
                        word = line.strip()
                        if word:
                            stopwords.add(word)
            except Exception:
                pass

        return stopwords

    def clean_text(self, text: str) -> str:
        if not text or not isinstance(text, str):
            return ""

        text = re.sub(r'<[^>]+>', '', text)

        text = re.sub(r'http[s]?://(?:[a-zA-Z]|[0-9]|[$-_@.&+]|[!*\\(\\),]|(?:%[0-9a-fA-F][0-9a-fA-F]))+', '', text)

        text = re.sub(r'www\.[a-zA-Z0-9][-a-zA-Z0-9]{0,62}(\.[a-zA-Z0-9][-a-zA-Z0-9]{0,62})+', '', text)

        text = re.sub(r'[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}', '', text)

        text = re.sub(r'\s+', ' ', text)

        text = text.strip()

        return text

    def tokenize(self, text: str, use_paddle: bool = False) -> List[str]:
        if not text or not isinstance(text, str):
            return []

        if use_paddle:
            try:
                jieba.enable_paddle()
            except Exception:
                pass

        tokens = list(jieba.cut(text))
        return tokens

    def filter_stopwords(self, tokens: List[str]) -> List[str]:
        if not tokens:
            return []

        filtered = [token for token in tokens if token and token.lower() not in self.stopwords]
        return filtered

    def preprocess(self, text: str, use_paddle: bool = False) -> dict:
        if not text or not isinstance(text, str):
            return {
                "original_text": "",
                "cleaned_text": "",
                "tokens": [],
                "filtered_tokens": [],
                "status": "error",
                "message": "文本为空或无效"
            }

        try:
            cleaned_text = self.clean_text(text)
            if not cleaned_text:
                return {
                    "original_text": text,
                    "cleaned_text": "",
                    "tokens": [],
                    "filtered_tokens": [],
                    "status": "error",
                    "message": "文本清洗后为空"
                }

            tokens = self.tokenize(cleaned_text, use_paddle=use_paddle)
            if not tokens:
                return {
                    "original_text": text,
                    "cleaned_text": cleaned_text,
                    "tokens": [],
                    "filtered_tokens": [],
                    "status": "error",
                    "message": "分词失败"
                }

            filtered_tokens = self.filter_stopwords(tokens)

            return {
                "original_text": text,
                "cleaned_text": cleaned_text,
                "tokens": tokens,
                "filtered_tokens": filtered_tokens,
                "status": "success",
                "message": "预处理成功"
            }

        except Exception as e:
            return {
                "original_text": text,
                "cleaned_text": "",
                "tokens": [],
                "filtered_tokens": [],
                "status": "error",
                "message": f"预处理异常: {str(e)}"
            }

    def preprocess_batch(self, texts: List[str], use_paddle: bool = False) -> List[dict]:
        results = []
        for text in texts:
            result = self.preprocess(text, use_paddle=use_paddle)
            results.append(result)
        return results


text_preprocessor = TextPreprocessor()
