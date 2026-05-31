import pytest
from adversarial import (
    AdversarialAttackRequest,
    AttackType,
    adversarial_service,
)


@pytest.mark.asyncio
async def test_prompt_injection_attack():
    request = AdversarialAttackRequest(
        attack_type=AttackType.PROMPT_INJECTION,
        target_prompt="请帮我写一封邮件",
        target_model="gpt-3.5-turbo",
        num_samples=3,
    )
    result = await adversarial_service.generate_adversarial_samples(request)
    assert result.total_samples == 3
    assert all(s.attack_type == AttackType.PROMPT_INJECTION for s in result.samples)


@pytest.mark.asyncio
async def test_jailbreak_attack():
    request = AdversarialAttackRequest(
        attack_type=AttackType.JAILBREAK,
        target_prompt="你好",
        target_model="gpt-3.5-turbo",
        num_samples=2,
    )
    result = await adversarial_service.generate_adversarial_samples(request)
    assert result.total_samples == 2


@pytest.mark.asyncio
async def test_batch_attack():
    result = await adversarial_service.batch_attack(
        prompts=["你好", "帮我写代码"],
        attack_types=[AttackType.PROMPT_INJECTION, AttackType.JAILBREAK],
        target_model="gpt-3.5-turbo",
    )
    assert result.total_tasks == 2
    assert len(result.results) == 2
