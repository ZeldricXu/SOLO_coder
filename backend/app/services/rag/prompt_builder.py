from typing import List, Dict, Any, Optional
from dataclasses import dataclass


@dataclass
class ContextChunk:
    content: str
    score: float
    metadata: Dict[str, Any]


@dataclass
class PromptContext:
    user_query: str
    context_chunks: List[ContextChunk]
    original_messages: List[Dict[str, str]]


class PromptBuilder:
    DEFAULT_SYSTEM_PROMPT = """你是一个专业的AI助手，请基于以下提供的上下文信息回答用户问题。

回答要求：
1. 严格基于提供的上下文信息回答，不要编造信息
2. 如果上下文中没有相关信息，请明确告知用户
3. 回答要简洁、准确、有条理
4. 可以适当引用上下文内容，但不要直接复制整个段落

以下是上下文信息："""

    def __init__(
        self,
        system_prompt: Optional[str] = None,
        include_sources: bool = True
    ):
        self._system_prompt = system_prompt or self.DEFAULT_SYSTEM_PROMPT
        self._include_sources = include_sources

    def build_context_section(self, chunks: List[ContextChunk]) -> str:
        if not chunks:
            return ""

        context_parts = []
        for idx, chunk in enumerate(chunks):
            score_pct = chunk.score * 100
            source_info = ""
            if self._include_sources:
                source_file = chunk.metadata.get("source_file", "unknown")
                source_info = f" [来源: {source_file}]"
            
            context_parts.append(
                f"[相关文档 {idx + 1} (相似度: {score_pct:.1f}%){source_info}]\n{chunk.content}"
            )

        return "\n\n".join(context_parts)

    def build_prompt(
        self,
        user_query: str,
        context_chunks: List[ContextChunk]
    ) -> str:
        if not context_chunks:
            return user_query

        context_section = self.build_context_section(context_chunks)
        full_prompt = (
            f"{self._system_prompt}\n"
            f"{context_section}\n\n"
            f"用户问题：{user_query}"
        )
        return full_prompt

    def enhance_messages(
        self,
        messages: List[Dict[str, str]],
        context_chunks: List[ContextChunk]
    ) -> List[Dict[str, str]]:
        if not context_chunks or not messages:
            return messages.copy()

        processed_messages = []
        for i, msg in enumerate(messages):
            if i == len(messages) - 1 and msg.get("role") == "user":
                user_query = msg.get("content", "")
                enhanced_content = self.build_prompt(user_query, context_chunks)
                processed_messages.append({"role": "user", "content": enhanced_content})
            else:
                processed_messages.append(msg.copy())

        return processed_messages
