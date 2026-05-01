from app.core.config import settings
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.output_parsers import StrOutputParser
from typing import Optional, List, Dict, Any
import json


class AIService:
    def __init__(self):
        self.llm = ChatOpenAI(
            model=settings.OPENAI_MODEL,
            api_key=settings.OPENAI_API_KEY,
            base_url=settings.OPENAI_BASE_URL,
            streaming=True,
        )
        self.embeddings = OpenAIEmbeddings(
            api_key=settings.OPENAI_API_KEY,
            base_url=settings.OPENAI_BASE_URL,
        )
        
        # 续写提示词模板
        self.autocomplete_prompt = ChatPromptTemplate.from_messages([
            (
                "system",
                "你是一个专业的小说家，擅长创作引人入胜的故事。"
                "请根据提供的【背景设定】和【前文内容】进行续写，保持风格一致，"
                "逻辑连贯，人物性格鲜明。不要改变前文已有的设定。"
            ),
            (
                "human",
                "【背景设定】：{context}\n\n【前文内容】：{text}\n\n请继续续写："
            ),
        ])
        
        # 润色提示词模板
        self.polish_prompt = ChatPromptTemplate.from_messages([
            (
                "system",
                "你是一个资深的文学编辑，擅长润色文字，提升表达效果。"
                "请根据用户提供的文本进行润色，保持原意不变，但使语言更加流畅、"
                "生动、富有感染力。"
            ),
            (
                "human",
                "请润色以下文本：\n\n{text}\n\n润色后的版本："
            ),
        ])
    
    async def autocomplete(
        self,
        text: str,
        context: Optional[str] = None,
        max_tokens: int = 500,
        temperature: float = 0.7,
    ):
        """
        AI 续写功能
        
        Args:
            text: 当前文本内容
            context: 相关背景设定（从向量库检索）
            max_tokens: 最大生成 token 数
            temperature: 生成温度
        
        Yields:
            生成的文本片段（流式）
        """
        # 构建提示词
        formatted_prompt = self.autocomplete_prompt.format_messages(
            context=context or "无特殊背景设定",
            text=text,
        )
        
        # 配置 LLM 参数
        llm = self.llm.bind(
            max_tokens=max_tokens,
            temperature=temperature,
        )
        
        # 构建链
        chain = llm | StrOutputParser()
        
        # 流式生成
        async for chunk in chain.astream(formatted_prompt):
            yield chunk
    
    async def polish(
        self,
        text: str,
        max_tokens: int = 2000,
        temperature: float = 0.7,
    ):
        """
        AI 润色功能
        
        Args:
            text: 需要润色的文本
            max_tokens: 最大生成 token 数
            temperature: 生成温度
        
        Returns:
            润色后的文本
        """
        # 构建提示词
        formatted_prompt = self.polish_prompt.format_messages(
            text=text,
        )
        
        # 配置 LLM 参数
        llm = self.llm.bind(
            max_tokens=max_tokens,
            temperature=temperature,
        )
        
        # 构建链
        chain = llm | StrOutputParser()
        
        # 执行
        return await chain.ainvoke(formatted_prompt)
    
    async def get_embeddings(self, text: str) -> List[float]:
        """
        获取文本的向量嵌入
        
        Args:
            text: 输入文本
        
        Returns:
            向量嵌入列表
        """
        return await self.embeddings.aembed_query(text)
    
    async def get_embeddings_batch(self, texts: List[str]) -> List[List[float]]:
        """
        批量获取文本的向量嵌入
        
        Args:
            texts: 输入文本列表
        
        Returns:
            向量嵌入列表
        """
        return await self.embeddings.aembed_documents(texts)
