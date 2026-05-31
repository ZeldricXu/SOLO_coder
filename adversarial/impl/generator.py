from typing import List, Tuple
import asyncio
import time

from ..protocols import AttackGenerator
from ..schemas import (
    AdversarialAttackRequest,
    AdversarialAttackResponse,
    AttackResult,
    AdversarialExample,
)
from ..strategies import get_attack_strategy
from common.logger import get_logger
from common.utils import generate_id

logger = get_logger(__name__)


class ParallelAttackGenerator(AttackGenerator):
    def __init__(
        self,
        max_concurrent: int = 10,
    ):
        self.max_concurrent = max_concurrent

    async def generate(
        self,
        request: AdversarialAttackRequest,
    ) -> AdversarialAttackResponse:
        start_time = time.time()
        request_id = generate_id("req_adv_")

        logger.info(f"Starting adversarial attack generation for request {request_id}")

        strategy_tasks = []
        semaphore = asyncio.Semaphore(self.max_concurrent)

        async def _run_strategy(strategy_type) -> Tuple[str, AttackResult]:
            async with semaphore:
                return await self._execute_strategy(
                    strategy_type,
                    request,
                )

        for strategy_type in request.strategies:
            strategy_tasks.append(_run_strategy(strategy_type))

        results = await asyncio.gather(*strategy_tasks, return_exceptions=True)

        valid_results: List[AttackResult] = []
        all_examples: List[AdversarialExample] = []

        for result in results:
            if isinstance(result, Exception):
                logger.error(f"Strategy task failed: {str(result)}")
                continue
            strategy_type, attack_result = result
            valid_results.append(attack_result)
            all_examples.extend(attack_result.adversarial_prompts)

        return valid_results, all_examples, request_id, start_time

    async def _execute_strategy(
        self,
        strategy_type,
        request: AdversarialAttackRequest,
    ) -> Tuple[str, AttackResult]:
        strategy_start = time.time()
        try:
            strategy = get_attack_strategy(
                strategy_type, request.custom_attack_params
            )
            examples = strategy.generate(
                request.target_prompt,
                max_attempts=request.max_attempts,
                temperature=request.temperature,
            )

            success = len(examples) > 0
            confidence = (
                max(e.success_probability for e in examples) if examples else 0.0
            )

            result = AttackResult(
                strategy=strategy_type,
                success=success,
                confidence=confidence,
                adversarial_prompts=examples,
                attack_duration_ms=(time.time() - strategy_start) * 1000,
            )

            logger.info(
                f"Strategy {strategy_type} completed: {len(examples)} examples generated"
            )

            return strategy_type, result

        except Exception as e:
            logger.error(f"Strategy {strategy_type} failed: {str(e)}")
            return strategy_type, AttackResult(
                strategy=strategy_type,
                success=False,
                confidence=0.0,
                attack_duration_ms=(time.time() - strategy_start) * 1000,
                error_message=str(e),
            )
