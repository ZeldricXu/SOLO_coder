from datetime import datetime
from typing import Optional, List
import time

from sqlalchemy.orm import Session

from app.core.cache import cache
from app.models.batch import Batch
from app.models.sku import SKU
from app.models.supplier import Supplier
from app.models.warehouse import Warehouse
from app.schemas.batch import BatchGenerateRuleEnum
from app.utils.exceptions import InventoryException

BATCH_GENERATOR_LOCK_PREFIX = "batch:generator:lock:"
BATCH_GENERATOR_SEQUENCE_PREFIX = "batch:generator:sequence:"
BATCH_GENERATOR_CACHE_TTL = 86400


class BatchNumberGenerator:
    def __init__(self, db: Session):
        self.db = db
        self.lock_timeout = 10

    def _acquire_lock(self, lock_key: str) -> bool:
        try:
            result = cache._get_client().set(
                lock_key,
                "locked",
                nx=True,
                ex=self.lock_timeout
            )
            return result is not None
        except Exception as e:
            logger.warning(f"Failed to acquire lock {lock_key}: {e}")
            return False

    def _release_lock(self, lock_key: str) -> None:
        try:
            cache.delete(lock_key)
        except Exception as e:
            logger.warning(f"Failed to release lock {lock_key}: {e}")

    def _get_next_sequence(self, sequence_key: str, reset_daily: bool = True) -> int:
        client = cache._get_client()
        try:
            if reset_daily:
                date_str = datetime.utcnow().strftime("%Y%m%d")
                full_key = f"{sequence_key}:{date_str}"
            else:
                full_key = sequence_key

            current = client.incr(full_key)
            if current == 1:
                client.expire(full_key, BATCH_GENERATOR_CACHE_TTL)

            return current
        except Exception as e:
            logger.error(f"Failed to get sequence for {sequence_key}: {e}")
            return int(time.time() * 1000) % 1000000

    def _get_sku_code(self, sku_id: int) -> str:
        sku = self.db.get(SKU, sku_id)
        return sku.sku_code[:8] if sku and sku.sku_code else f"SKU{sku_id}"

    def _get_supplier_code(self, supplier_id: Optional[int]) -> str:
        if not supplier_id:
            return "NONE"
        supplier = self.db.get(Supplier, supplier_id)
        return supplier.code[:8] if supplier and supplier.code else f"SP{supplier_id}"

    def _get_warehouse_code(self, warehouse_id: int) -> str:
        warehouse = self.db.get(Warehouse, warehouse_id)
        return warehouse.code[:8] if warehouse and warehouse.code else f"WH{warehouse_id}"

    def _format_sequence(self, seq: int, length: int = 6) -> str:
        return str(seq).zfill(length)

    def _check_duplicate(self, batch_no: str) -> bool:
        cache_key = f"batch:exists:{batch_no}"
        try:
            if cache.exists(cache_key):
                return True
        except Exception:
            pass

        exists = (
            self.db.query(Batch)
            .filter(Batch.batch_no == batch_no)
            .first() is not None
        )

        if exists:
            try:
                cache.set(cache_key, "1", ttl=3600)
            except Exception:
                pass

        return exists

    def generate_single(
        self,
        sku_id: int,
        warehouse_id: int,
        supplier_id: Optional[int] = None,
        rule: BatchGenerateRuleEnum = BatchGenerateRuleEnum.DATE_SEQUENCE,
        prefix: Optional[str] = None,
    ) -> str:
        lock_key = f"{BATCH_GENERATOR_LOCK_PREFIX}single:{sku_id}:{warehouse_id}"

        if not self._acquire_lock(lock_key):
            raise InventoryException(
                "系统繁忙，请稍后重试",
                code=503,
                details={"reason": "batch_generator_lock_contention"}
            )

        try:
            max_attempts = 10
            for attempt in range(max_attempts):
                batch_no = self._generate_by_rule(
                    sku_id=sku_id,
                    warehouse_id=warehouse_id,
                    supplier_id=supplier_id,
                    rule=rule,
                    prefix=prefix,
                    attempt=attempt
                )

                if not self._check_duplicate(batch_no):
                    try:
                        cache.set(f"batch:exists:{batch_no}", "1", ttl=3600)
                    except Exception:
                        pass
                    return batch_no

            raise InventoryException(
                "生成批次号失败，已达到最大重试次数",
                code=500,
                details={"rule": rule.value, "attempts": max_attempts}
            )
        finally:
            self._release_lock(lock_key)

    def _generate_by_rule(
        self,
        sku_id: int,
        warehouse_id: int,
        supplier_id: Optional[int],
        rule: BatchGenerateRuleEnum,
        prefix: Optional[str],
        attempt: int = 0
    ) -> str:
        now = datetime.utcnow()
        date_str = now.strftime("%Y%m%d")
        time_str = now.strftime("%H%M%S")

        custom_prefix = prefix or ""

        if rule == BatchGenerateRuleEnum.DATE_SEQUENCE:
            seq_key = f"{BATCH_GENERATOR_SEQUENCE_PREFIX}date:{sku_id}:{warehouse_id}"
            seq = self._get_next_sequence(seq_key) + attempt
            return f"{custom_prefix}{date_str}-{self._format_sequence(seq)}"

        elif rule == BatchGenerateRuleEnum.SUPPLIER_DATE:
            supplier_code = self._get_supplier_code(supplier_id)
            seq_key = f"{BATCH_GENERATOR_SEQUENCE_PREFIX}supplier:{supplier_id}:{date_str}"
            seq = self._get_next_sequence(seq_key) + attempt
            return f"{custom_prefix}{supplier_code}-{date_str}-{self._format_sequence(seq)}"

        elif rule == BatchGenerateRuleEnum.PRODUCT_DATE:
            sku_code = self._get_sku_code(sku_id)
            seq_key = f"{BATCH_GENERATOR_SEQUENCE_PREFIX}product:{sku_id}:{date_str}"
            seq = self._get_next_sequence(seq_key) + attempt
            return f"{custom_prefix}{sku_code}-{date_str}-{self._format_sequence(seq)}"

        elif rule == BatchGenerateRuleEnum.WAREHOUSE_DATE:
            warehouse_code = self._get_warehouse_code(warehouse_id)
            seq_key = f"{BATCH_GENERATOR_SEQUENCE_PREFIX}warehouse:{warehouse_id}:{date_str}"
            seq = self._get_next_sequence(seq_key) + attempt
            return f"{custom_prefix}{warehouse_code}-{date_str}-{self._format_sequence(seq)}"

        elif rule == BatchGenerateRuleEnum.CUSTOM:
            seq_key = f"{BATCH_GENERATOR_SEQUENCE_PREFIX}custom:{date_str}"
            seq = self._get_next_sequence(seq_key) + attempt
            timestamp = int(now.timestamp() * 1000) % 10000
            return f"{custom_prefix}{date_str}{time_str}-{timestamp}-{self._format_sequence(seq, 4)}"

        else:
            seq_key = f"{BATCH_GENERATOR_SEQUENCE_PREFIX}default:{date_str}"
            seq = self._get_next_sequence(seq_key) + attempt
            return f"{custom_prefix}BATCH-{date_str}-{self._format_sequence(seq)}"

    def generate_batch(
        self,
        count: int,
        sku_id: int,
        warehouse_id: int,
        supplier_id: Optional[int] = None,
        rule: BatchGenerateRuleEnum = BatchGenerateRuleEnum.DATE_SEQUENCE,
        prefix: Optional[str] = None,
    ) -> List[str]:
        if count <= 0:
            raise InventoryException("生成数量必须大于0", code=400)

        if count > 100:
            raise InventoryException("单次生成数量不能超过100", code=400)

        lock_key = f"{BATCH_GENERATOR_LOCK_PREFIX}batch:{sku_id}:{warehouse_id}:{count}"

        if not self._acquire_lock(lock_key):
            raise InventoryException(
                "系统繁忙，请稍后重试",
                code=503,
                details={"reason": "batch_generator_lock_contention"}
            )

        try:
            batch_numbers: List[str] = []
            generated = set()

            max_total_attempts = count * 10
            attempts = 0

            while len(batch_numbers) < count and attempts < max_total_attempts:
                attempts += 1
                batch_no = self._generate_by_rule(
                    sku_id=sku_id,
                    warehouse_id=warehouse_id,
                    supplier_id=supplier_id,
                    rule=rule,
                    prefix=prefix,
                    attempt=len(batch_numbers) + attempts
                )

                if batch_no not in generated and not self._check_duplicate(batch_no):
                    generated.add(batch_no)
                    batch_numbers.append(batch_no)
                    try:
                        cache.set(f"batch:exists:{batch_no}", "1", ttl=3600)
                    except Exception:
                        pass

            if len(batch_numbers) < count:
                raise InventoryException(
                    f"生成批次号失败，仅生成{len(batch_numbers)}个，需要{count}个",
                    code=500,
                    details={"generated": len(batch_numbers), "required": count}
                )

            return batch_numbers
        finally:
            self._release_lock(lock_key)


def create_batch_number_generator(db: Session) -> BatchNumberGenerator:
    return BatchNumberGenerator(db)
