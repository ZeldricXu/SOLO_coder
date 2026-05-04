import os
import time
import logging
import requests

import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from config import (
    LLM_API_ENDPOINT, 
    MAX_RETRIES, 
    RETRY_DELAY,
    MAX_SUMMARY_LENGTH
)

logger = logging.getLogger(__name__)


class LLMServiceError(Exception):
    pass


class LLMService:
    def __init__(self, api_endpoint=None, max_retries=None, retry_delay=None):
        self.api_endpoint = api_endpoint or LLM_API_ENDPOINT
        self.max_retries = max_retries if max_retries is not None else MAX_RETRIES
        self.retry_delay = retry_delay or RETRY_DELAY
        self.max_summary_length = MAX_SUMMARY_LENGTH
    
    def _build_prompt(self, content):
        prompt = f"""请对以下文本内容进行摘要提炼，要求：
1. 摘要长度不超过{self.max_summary_length}字
2. 保留核心信息和关键要点
3. 语言简洁明了

文本内容：
{content}

请直接输出摘要内容："""
        return prompt
    
    def _call_api(self, content):
        request_body = {
            "file_content": content,
            "max_length": self.max_summary_length
        }
        
        try:
            response = requests.post(
                self.api_endpoint,
                json=request_body,
                timeout=60,
                headers={
                    "Content-Type": "application/json"
                }
            )
            
            response.raise_for_status()
            result = response.json()
            
            if result.get("code") != 200:
                raise LLMServiceError(f"API返回错误: {result.get('message', 'Unknown error')}")
            
            data = result.get("data", {})
            summary = data.get("summary", "")
            usage = data.get("usage", {})
            
            return {
                "summary": summary,
                "tokens": usage.get("tokens", 0)
            }
            
        except requests.exceptions.RequestException as e:
            raise LLMServiceError(f"API请求失败: {str(e)}")
        except (KeyError, ValueError) as e:
            raise LLMServiceError(f"API响应解析失败: {str(e)}")
    
    def generate_summary(self, content):
        if not content or not content.strip():
            raise ValueError("内容不能为空")
        
        prompt = self._build_prompt(content)
        
        last_error = None
        for attempt in range(self.max_retries + 1):
            try:
                result = self._call_api(prompt)
                logger.info(f"摘要生成成功，使用tokens: {result.get('tokens', 0)}")
                return result["summary"]
                
            except LLMServiceError as e:
                last_error = e
                if attempt < self.max_retries:
                    wait_time = self.retry_delay * (attempt + 1)
                    logger.warning(f"第{attempt + 1}次尝试失败，{wait_time}秒后重试: {str(e)}")
                    time.sleep(wait_time)
                else:
                    logger.error(f"所有重试都失败了: {str(e)}")
        
        raise last_error
