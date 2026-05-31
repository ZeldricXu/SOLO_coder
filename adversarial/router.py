from fastapi import APIRouter, HTTPException
from typing import List

from .schemas import (
    AdversarialAttackRequest,
    AdversarialAttackResponse,
    SecurityAssessmentRequest,
    SecurityAssessmentResponse,
    AdversarialExample,
    AttackStrategy,
)
from .service import adversarial_service
from common.schemas import BaseResponse
from common.logger import get_logger

logger = get_logger(__name__)

router = APIRouter(prefix="/api/v1/adversarial", tags=["对抗样本生成"])


@router.post("/attack", response_model=BaseResponse[AdversarialAttackResponse])
async def generate_adversarial_attack(request: AdversarialAttackRequest):
    """生成对抗样本，使用多种攻击策略"""
    try:
        result = await adversarial_service.generate_adversarial_examples(request)
        return BaseResponse(data=result, message="对抗样本生成成功")
    except Exception as e:
        logger.error(f"Failed to generate adversarial examples: {str(e)}")
        raise HTTPException(status_code=500, detail=f"生成对抗样本失败: {str(e)}")


@router.post("/security-assessment", response_model=BaseResponse[SecurityAssessmentResponse])
async def run_security_assessment(request: SecurityAssessmentRequest):
    """运行模型安全性评估"""
    try:
        result = await adversarial_service.run_security_assessment(request)
        return BaseResponse(data=result, message="安全性评估完成")
    except Exception as e:
        logger.error(f"Failed to run security assessment: {str(e)}")
        raise HTTPException(status_code=500, detail=f"安全性评估失败: {str(e)}")


@router.get("/attack/{request_id}", response_model=BaseResponse[List[AdversarialExample]])
async def get_attack_result(request_id: str):
    """获取指定请求的攻击历史"""
    try:
        result = adversarial_service.get_attack_history(request_id)
        if not result:
            raise HTTPException(status_code=404, detail=f"请求 {request_id} 未找到")
        return BaseResponse(data=result, message="获取成功")
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get attack result: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取攻击结果失败: {str(e)}")


@router.get("/assessment/{assessment_id}", response_model=BaseResponse[SecurityAssessmentResponse])
async def get_assessment_result(assessment_id: str):
    """获取指定评估的结果"""
    try:
        result = adversarial_service.get_assessment(assessment_id)
        if not result:
            raise HTTPException(status_code=404, detail=f"评估 {assessment_id} 未找到")
        return BaseResponse(data=result, message="获取成功")
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get assessment result: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取评估结果失败: {str(e)}")


@router.get("/strategies", response_model=BaseResponse[List[dict]])
async def list_attack_strategies():
    """列出所有可用的攻击策略"""
    strategies = []
    for strategy in AttackStrategy:
        description_map = {
            AttackStrategy.PROMPT_INJECTION: "提示注入攻击，在用户输入中嵌入隐藏指令",
            AttackStrategy.JAILBREAK: "越狱攻击，尝试绕过模型的安全限制",
            AttackStrategy.ROLE_PLAYING: "角色扮演攻击，诱导模型扮演不受限制的角色",
            AttackStrategy.OBFUSCATION: "混淆攻击，通过编码、变形等方式隐藏恶意意图",
            AttackStrategy.FEW_SHOT: "少样本对抗攻击，通过示例引导模型输出有害内容",
            AttackStrategy.MULTI_MODAL: "多模态对抗攻击，针对多模态模型的攻击策略",
            AttackStrategy.TREE_OF_THOUGHT: "思维树攻击，通过多步推理绕过限制",
        }
        strategies.append({
            "name": strategy.value,
            "description": description_map.get(strategy, ""),
        })
    return BaseResponse(data=strategies, message="获取成功")
