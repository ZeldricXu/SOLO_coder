from __future__ import annotations

import hashlib
import json
from typing import Any, Dict, List, Optional, Tuple


def get_abi_element(
    abi: List[Dict[str, Any]],
    element_type: str,
    name: Optional[str] = None,
) -> Dict[str, Any]:
    for element in abi:
        if element.get("type") == element_type:
            if name is None or element.get("name") == name:
                return element
    raise ValueError(f"ABI element not found: type={element_type}, name={name}")


def encode_function_call(
    abi_or_func_abi,
    args_or_function_name,
    args: Optional[Tuple[Any, ...]] = None,
) -> str:
    try:
        from web3 import Web3

        w3 = Web3()

        if isinstance(abi_or_func_abi, list):
            abi = abi_or_func_abi
            function_name = args_or_function_name
            actual_args = args
            contract = w3.eth.contract(abi=abi)
            if function_name == "constructor":
                return contract.constructor(*(actual_args or ())).data_in_transaction
            else:
                return contract.encode_abi(function_name, actual_args or ())
        else:
            func_abi = abi_or_func_abi
            actual_args = args_or_function_name
            abi = [func_abi]
            contract = w3.eth.contract(abi=abi)
            if func_abi.get("type") == "constructor":
                return contract.constructor(*(actual_args or ())).data_in_transaction
            else:
                return contract.encode_abi(func_abi["name"], actual_args or ())
    except ImportError:
        if isinstance(abi_or_func_abi, list):
            func_abi = get_abi_element(abi_or_func_abi, "function", args_or_function_name)
        else:
            func_abi = abi_or_func_abi
        func_signature = f"{func_abi['name']}({','.join([i['type'] for i in func_abi['inputs']])})"
        selector = hashlib.keccak256(func_signature.encode()).hexdigest()[:8]
        return "0x" + selector


def decode_function_input(
    contract_or_abi,
    data: str,
) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    try:
        from web3 import Web3

        if hasattr(contract_or_abi, 'decode_function_input'):
            contract = contract_or_abi
            return contract.decode_function_input(data)
        else:
            abi = contract_or_abi
            w3 = Web3()
            contract = w3.eth.contract(abi=abi)
            return contract.decode_function_input(data)
    except ImportError:
        return {}, {}


def get_event_data(
    abi: List[Dict[str, Any]],
    log: Dict[str, Any],
    event_name: Optional[str] = None,
) -> Dict[str, Any]:
    try:
        from web3 import Web3
        from web3._utils.events import get_event_data as _get_event_data
        from eth_utils import event_abi_to_log_topic

        w3 = Web3()

        if event_name is None:
            topic0 = log["topics"][0] if log.get("topics") else None
            if topic0:
                for element in abi:
                    if element.get("type") == "event":
                        try:
                            element_topic = event_abi_to_log_topic(element)
                            if element_topic == topic0 or element_topic.hex() == topic0.hex() if hasattr(topic0, 'hex') else element_topic.hex() == topic0:
                                event_abi = element
                                break
                        except Exception:
                            continue
                else:
                    raise ValueError("Could not find matching event ABI")
            else:
                raise ValueError("No event name provided and no topics in log")
        else:
            event_abi = get_abi_element(abi, "event", event_name)

        return _get_event_data(w3.codec, event_abi, log)
    except ImportError:
        return {}
