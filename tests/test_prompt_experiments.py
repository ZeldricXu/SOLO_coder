import pytest
from prompt_experiments import (
    PromptCreateRequest,
    ExperimentCreateRequest,
    VariantConfig,
    TrafficSplitType,
    prompt_experiment_service,
)


@pytest.mark.asyncio
async def test_create_and_list_prompts():
    request = PromptCreateRequest(
        name="test-prompt",
        content="你是一个友好的助手，用户说：{{user_input}}",
        variables=["user_input"],
        description="测试Prompt",
    )
    prompt = await prompt_experiment_service.create_prompt(request)
    assert prompt.name == "test-prompt"

    prompts = await prompt_experiment_service.list_prompts(limit=10)
    assert len(prompts) >= 1


@pytest.mark.asyncio
async def test_create_experiment():
    prompt_req = PromptCreateRequest(
        name="exp-prompt-v1",
        content="你是助手：{{input}}",
        variables=["input"],
    )
    prompt1 = await prompt_experiment_service.create_prompt(prompt_req)

    prompt_req2 = PromptCreateRequest(
        name="exp-prompt-v2",
        content="请回答：{{input}}",
        variables=["input"],
    )
    prompt2 = await prompt_experiment_service.create_prompt(prompt_req2)

    exp_req = ExperimentCreateRequest(
        name="test-ab-experiment",
        variants=[
            VariantConfig(variant_id="A", prompt_id=prompt1.prompt_id, traffic_weight=50),
            VariantConfig(variant_id="B", prompt_id=prompt2.prompt_id, traffic_weight=50),
        ],
        traffic_split_type=TrafficSplitType.RANDOM,
        description="AB测试实验",
    )
    experiment = await prompt_experiment_service.create_experiment(exp_req)
    assert experiment.name == "test-ab-experiment"
    assert len(experiment.variants) == 2
