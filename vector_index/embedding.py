"""
Embedding向量处理器，支持多种Embedding模型接口
"""
from abc import ABC, abstractmethod
from typing import List, Dict, Any, Optional, Union
import numpy as np
from enum import Enum


class EmbeddingModelType(str, Enum):
    OPENAI = "openai"
    HUGGINGFACE = "huggingface"
    SENTENCE_TRANSFORMER = "sentence_transformer"
    CUSTOM = "custom"
    COHERE = "cohere"
    BGE = "bge"


class BaseEmbeddingProvider(ABC):
    @abstractmethod
    def encode(
        self,
        texts: List[str],
        batch_size: int = 32,
        **kwargs: Any,
    ) -> np.ndarray:
        pass

    @abstractmethod
    def get_dimension(self) -> int:
        pass


class OpenAIEmbeddingProvider(BaseEmbeddingProvider):
    def __init__(
        self,
        model: str = "text-embedding-ada-002",
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        **kwargs: Any,
    ):
        self.model = model
        self.api_key = api_key
        self.base_url = base_url
        self._dimension = None
        try:
            import openai
            self._client = openai.OpenAI(api_key=api_key, base_url=base_url)
        except ImportError:
            self._client = None

    def _get_dimension_from_model(self) -> int:
        dimension_map = {
            "text-embedding-ada-002": 1536,
            "text-embedding-3-small": 1536,
            "text-embedding-3-large": 3072,
        }
        return dimension_map.get(self.model, 1536)

    def get_dimension(self) -> int:
        if self._dimension is None:
            self._dimension = self._get_dimension_from_model()
        return self._dimension

    def encode(
        self,
        texts: List[str],
        batch_size: int = 32,
        **kwargs: Any,
    ) -> np.ndarray:
        if self._client is None:
            raise ImportError("OpenAI SDK not installed")
        embeddings = []
        for i in range(0, len(texts), batch_size):
            batch = texts[i : i + batch_size]
            response = self._client.embeddings.create(
                input=batch,
                model=self.model,
                **kwargs,
            )
            batch_embeddings = [item.embedding for item in response.data]
            embeddings.extend(batch_embeddings)
        return np.array(embeddings, dtype=np.float32)


class SentenceTransformerProvider(BaseEmbeddingProvider):
    def __init__(
        self,
        model_name: str = "all-MiniLM-L6-v2",
        device: str = "cpu",
        **kwargs: Any,
    ):
        self.model_name = model_name
        self.device = device
        self._model = None
        self._dimension = None
        try:
            from sentence_transformers import SentenceTransformer
            self._model = SentenceTransformer(model_name, device=device)
            self._dimension = self._model.get_sentence_embedding_dimension()
        except ImportError:
            self._model = None

    def get_dimension(self) -> int:
        if self._dimension is None:
            raise RuntimeError("SentenceTransformer not initialized")
        return self._dimension

    def encode(
        self,
        texts: List[str],
        batch_size: int = 32,
        **kwargs: Any,
    ) -> np.ndarray:
        if self._model is None:
            raise ImportError("sentence-transformers not installed")
        show_progress = kwargs.get("show_progress_bar", False)
        normalize = kwargs.get("normalize_embeddings", True)
        embeddings = self._model.encode(
            texts,
            batch_size=batch_size,
            show_progress_bar=show_progress,
            normalize_embeddings=normalize,
            convert_to_numpy=True,
        )
        return embeddings.astype(np.float32)


class HuggingFaceEmbeddingProvider(BaseEmbeddingProvider):
    def __init__(
        self,
        model_name: str = "bert-base-uncased",
        api_key: Optional[str] = None,
        use_api: bool = False,
        **kwargs: Any,
    ):
        self.model_name = model_name
        self.api_key = api_key
        self.use_api = use_api
        self._model = None
        self._tokenizer = None
        self._dimension = None
        if not use_api:
            try:
                from transformers import AutoTokenizer, AutoModel
                self._tokenizer = AutoTokenizer.from_pretrained(model_name)
                self._model = AutoModel.from_pretrained(model_name)
                self._dimension = self._model.config.hidden_size
            except ImportError:
                self._model = None
                self._tokenizer = None

    def get_dimension(self) -> int:
        if self._dimension is None:
            raise RuntimeError("Model not initialized")
        return self._dimension

    def _mean_pooling(
        self,
        model_output: Any,
        attention_mask: np.ndarray,
    ) -> np.ndarray:
        token_embeddings = model_output[0]
        input_mask = np.expand_dims(attention_mask, -1).astype(np.float32)
        return np.sum(token_embeddings * input_mask, axis=1) / np.clip(
            np.sum(input_mask, axis=1), a_min=1e-9, a_max=None
        )

    def encode(
        self,
        texts: List[str],
        batch_size: int = 32,
        **kwargs: Any,
    ) -> np.ndarray:
        if self.use_api:
            return self._encode_via_api(texts, **kwargs)
        if self._model is None or self._tokenizer is None:
            raise ImportError("transformers not installed")
        import torch
        embeddings = []
        for i in range(0, len(texts), batch_size):
            batch = texts[i : i + batch_size]
            encoded = self._tokenizer(
                batch,
                padding=True,
                truncation=True,
                max_length=kwargs.get("max_length", 512),
                return_tensors="pt",
            )
            with torch.no_grad():
                model_output = self._model(**encoded)
            batch_embeddings = self._mean_pooling(
                model_output, encoded["attention_mask"].numpy()
            )
            if kwargs.get("normalize", True):
                norms = np.linalg.norm(batch_embeddings, axis=1, keepdims=True)
                batch_embeddings = batch_embeddings / np.maximum(norms, 1e-10)
            embeddings.extend(batch_embeddings)
        return np.array(embeddings, dtype=np.float32)

    def _encode_via_api(
        self,
        texts: List[str],
        **kwargs: Any,
    ) -> np.ndarray:
        import requests
        api_url = f"https://api-inference.huggingface.co/pipeline/feature-extraction/{self.model_name}"
        headers = {"Authorization": f"Bearer {self.api_key}"}
        embeddings = []
        for text in texts:
            response = requests.post(
                api_url,
                headers=headers,
                json={"inputs": text},
            )
            response.raise_for_status()
            embeddings.append(response.json())
        return np.array(embeddings, dtype=np.float32)


class CohereEmbeddingProvider(BaseEmbeddingProvider):
    def __init__(
        self,
        api_key: str,
        model: str = "embed-english-v2.0",
        **kwargs: Any,
    ):
        self.api_key = api_key
        self.model = model
        self._client = None
        self._dimension = None
        try:
            import cohere
            self._client = cohere.Client(api_key)
        except ImportError:
            self._client = None

    def get_dimension(self) -> int:
        if self._dimension is None:
            dimension_map = {
                "embed-english-v2.0": 4096,
                "embed-english-light-v2.0": 1024,
                "embed-multilingual-v2.0": 768,
            }
            self._dimension = dimension_map.get(self.model, 1024)
        return self._dimension

    def encode(
        self,
        texts: List[str],
        batch_size: int = 96,
        **kwargs: Any,
    ) -> np.ndarray:
        if self._client is None:
            raise ImportError("cohere SDK not installed")
        embeddings = []
        for i in range(0, len(texts), batch_size):
            batch = texts[i : i + batch_size]
            response = self._client.embed(
                texts=batch,
                model=self.model,
                input_type=kwargs.get("input_type", "search_document"),
            )
            embeddings.extend(response.embeddings)
        return np.array(embeddings, dtype=np.float32)


class CustomEmbeddingProvider(BaseEmbeddingProvider):
    def __init__(
        self,
        encode_func: callable,
        dimension: int,
        **kwargs: Any,
    ):
        self._encode_func = encode_func
        self._dimension = dimension

    def get_dimension(self) -> int:
        return self._dimension

    def encode(
        self,
        texts: List[str],
        batch_size: int = 32,
        **kwargs: Any,
    ) -> np.ndarray:
        embeddings = []
        for i in range(0, len(texts), batch_size):
            batch = texts[i : i + batch_size]
            batch_embeddings = self._encode_func(batch, **kwargs)
            if isinstance(batch_embeddings, list):
                batch_embeddings = np.array(batch_embeddings)
            embeddings.extend(batch_embeddings)
        return np.array(embeddings, dtype=np.float32)


class EmbeddingProcessor:
    def __init__(
        self,
        provider: Optional[BaseEmbeddingProvider] = None,
        provider_type: EmbeddingModelType = EmbeddingModelType.SENTENCE_TRANSFORMER,
        **provider_kwargs: Any,
    ):
        if provider is not None:
            self._provider = provider
        else:
            self._provider = self._create_provider(provider_type, **provider_kwargs)

    @staticmethod
    def _create_provider(
        provider_type: EmbeddingModelType,
        **kwargs: Any,
    ) -> BaseEmbeddingProvider:
        if provider_type == EmbeddingModelType.OPENAI:
            return OpenAIEmbeddingProvider(**kwargs)
        elif provider_type == EmbeddingModelType.SENTENCE_TRANSFORMER:
            return SentenceTransformerProvider(**kwargs)
        elif provider_type == EmbeddingModelType.HUGGINGFACE:
            return HuggingFaceEmbeddingProvider(**kwargs)
        elif provider_type == EmbeddingModelType.COHERE:
            return CohereEmbeddingProvider(**kwargs)
        elif provider_type == EmbeddingModelType.CUSTOM:
            return CustomEmbeddingProvider(**kwargs)
        else:
            raise ValueError(f"Unsupported provider type: {provider_type}")

    def get_dimension(self) -> int:
        return self._provider.get_dimension()

    def encode(
        self,
        texts: Union[str, List[str]],
        batch_size: int = 32,
        **kwargs: Any,
    ) -> np.ndarray:
        if isinstance(texts, str):
            texts = [texts]
        return self._provider.encode(texts, batch_size=batch_size, **kwargs)

    def encode_single(
        self,
        text: str,
        **kwargs: Any,
    ) -> np.ndarray:
        return self.encode([text], batch_size=1, **kwargs)[0]

    @staticmethod
    def create(
        provider_type: EmbeddingModelType,
        **kwargs: Any,
    ) -> "EmbeddingProcessor":
        return EmbeddingProcessor(provider_type=provider_type, **kwargs)
