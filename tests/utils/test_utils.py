import random
import string
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional


class TestUtils:

    @staticmethod
    def generate_id(prefix: str = "id") -> str:
        chars = string.ascii_lowercase + string.digits
        random_part = ''.join(random.choices(chars, k=24))
        return f"{prefix}_{random_part}"

    @staticmethod
    def generate_eth_address() -> str:
        chars = string.hexdigits.lower()
        return "0x" + ''.join(random.choices(chars, k=40))

    @staticmethod
    def generate_transaction_hash() -> str:
        chars = string.hexdigits.lower()
        return "0x" + ''.join(random.choices(chars, k=64))

    @staticmethod
    def generate_signature() -> str:
        chars = string.hexdigits.lower()
        return "0x" + ''.join(random.choices(chars, k=130))

    @staticmethod
    def generate_timestamp(days_ago: int = 0, hours_ago: int = 0, minutes_ago: int = 0) -> datetime:
        now = datetime.now()
        delta = timedelta(days=days_ago, hours=hours_ago, minutes=minutes_ago)
        return now - delta

    @staticmethod
    def generate_amount(decimal_places: int = 18) -> str:
        value = random.randint(1, 10**9)
        return str(value * (10 ** decimal_places))

    @staticmethod
    def random_choice(items: List[Any]) -> Any:
        return random.choice(items)
