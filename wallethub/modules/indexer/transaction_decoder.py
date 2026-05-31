from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
import json

from eth_utils import to_checksum_address, function_abi_to_4byte_selector
from hexbytes import HexBytes

from wallethub.core import IndexerError


@dataclass
class DecodedParam:
    name: str
    type: str
    value: Any


@dataclass
class DecodedTransaction:
    tx_hash: str
    to_address: str
    function_name: str
    function_signature: str
    function_selector: str
    params: List[DecodedParam] = field(default_factory=list)
    value: int = 0
    eth_value: float = 0.0


class TransactionDecoder:
    def __init__(self):
        self._contract_abis: Dict[str, List[Dict[str, Any]]] = {}
        self._function_selectors: Dict[str, Dict[str, Any]] = {}
        self._known_selectors: Dict[str, Dict[str, Any]] = {}

        self._init_standard_abis()

    def _init_standard_abis(self) -> None:
        erc20_abi = [
            {
                "constant": False,
                "inputs": [
                    {"name": "_to", "type": "address"},
                    {"name": "_value", "type": "uint256"},
                ],
                "name": "transfer",
                "outputs": [{"name": "", "type": "bool"}],
                "payable": False,
                "stateMutability": "nonpayable",
                "type": "function",
            },
            {
                "constant": False,
                "inputs": [
                    {"name": "_from", "type": "address"},
                    {"name": "_to", "type": "address"},
                    {"name": "_value", "type": "uint256"},
                ],
                "name": "transferFrom",
                "outputs": [{"name": "", "type": "bool"}],
                "payable": False,
                "stateMutability": "nonpayable",
                "type": "function",
            },
            {
                "constant": False,
                "inputs": [
                    {"name": "_spender", "type": "address"},
                    {"name": "_value", "type": "uint256"},
                ],
                "name": "approve",
                "outputs": [{"name": "", "type": "bool"}],
                "payable": False,
                "stateMutability": "nonpayable",
                "type": "function",
            },
            {
                "constant": True,
                "inputs": [{"name": "_owner", "type": "address"}],
                "name": "balanceOf",
                "outputs": [{"name": "balance", "type": "uint256"}],
                "payable": False,
                "stateMutability": "view",
                "type": "function",
            },
        ]

        self.register_abi("ERC20", erc20_abi)

        weth_abi = [
            {
                "constant": False,
                "inputs": [],
                "name": "deposit",
                "outputs": [],
                "payable": True,
                "stateMutability": "payable",
                "type": "function",
            },
            {
                "constant": False,
                "inputs": [{"name": "wad", "type": "uint256"}],
                "name": "withdraw",
                "outputs": [],
                "payable": False,
                "stateMutability": "nonpayable",
                "type": "function",
            },
        ]

        self.register_abi("WETH", weth_abi)

    def register_abi(self, contract_address: str, abi: List[Dict[str, Any]]) -> None:
        try:
            checksum_address = to_checksum_address(contract_address)
            self._contract_abis[checksum_address] = abi
            self._build_function_selectors(checksum_address, abi)
        except Exception as e:
            raise IndexerError(f"Failed to register ABI: {str(e)}")

    def _build_function_selectors(self, address: str, abi: List[Dict[str, Any]]) -> None:
        for entry in abi:
            if entry.get("type") == "function":
                try:
                    selector = function_abi_to_4byte_selector(entry).hex()
                    signature = self._get_function_signature(entry)
                    self._function_selectors[f"{address}:{selector}"] = {
                        "abi": entry,
                        "name": entry.get("name"),
                        "signature": signature,
                    }
                    self._known_selectors[selector] = {
                        "name": entry.get("name"),
                        "signature": signature,
                        "abi": entry,
                    }
                except Exception:
                    pass

    @staticmethod
    def _get_function_signature(abi: Dict[str, Any]) -> str:
        name = abi.get("name", "")
        inputs = abi.get("inputs", [])
        param_types = ",".join([i.get("type", "") for i in inputs])
        return f"{name}({param_types})"

    def decode_transaction(
        self,
        tx_data: Dict[str, Any],
        contract_abi: Optional[List[Dict[str, Any]]] = None,
    ) -> Optional[DecodedTransaction]:
        input_data = tx_data.get("input", "")
        if not input_data or input_data == "0x":
            return None

        if len(input_data) < 10:
            return None

        selector = input_data[2:10].lower()
        calldata = input_data[10:]

        to_address = tx_data.get("to", "")

        abi_entry = None
        function_name = "unknown"
        function_signature = f"0x{selector}"

        if to_address:
            try:
                checksum_to = to_checksum_address(to_address)
                key = f"{checksum_to}:{selector}"
                if key in self._function_selectors:
                    abi_entry = self._function_selectors[key]["abi"]
                    function_name = self._function_selectors[key]["name"]
                    function_signature = self._function_selectors[key]["signature"]
            except Exception:
                pass

        if abi_entry is None and selector in self._known_selectors:
            abi_entry = self._known_selectors[selector]["abi"]
            function_name = self._known_selectors[selector]["name"]
            function_signature = self._known_selectors[selector]["signature"]

        if abi_entry is None and contract_abi:
            for entry in contract_abi:
                if entry.get("type") == "function":
                    try:
                        entry_selector = function_abi_to_4byte_selector(entry).hex()
                        if entry_selector == selector:
                            abi_entry = entry
                            function_name = entry.get("name", "unknown")
                            function_signature = self._get_function_signature(entry)
                            break
                    except Exception:
                        continue

        decoded_params = []
        if abi_entry:
            decoded_params = self._decode_params(abi_entry.get("inputs", []), calldata)

        value = int(tx_data.get("value", 0))
        eth_value = value / 1e18

        return DecodedTransaction(
            tx_hash=tx_data.get("hash", ""),
            to_address=to_address,
            function_name=function_name,
            function_signature=function_signature,
            function_selector=f"0x{selector}",
            params=decoded_params,
            value=value,
            eth_value=eth_value,
        )

    def _decode_params(
        self,
        inputs: List[Dict[str, Any]],
        calldata: str,
    ) -> List[DecodedParam]:
        params = []
        offset = 0

        for input_def in inputs:
            param_type = input_def.get("type", "")
            param_name = input_def.get("name", "")

            try:
                if param_type == "address":
                    value = "0x" + calldata[offset + 24:offset + 64]
                    value = to_checksum_address(value)
                    params.append(DecodedParam(param_name, param_type, value))
                    offset += 64
                elif param_type == "uint256":
                    value = int(calldata[offset:offset + 64], 16)
                    params.append(DecodedParam(param_name, param_type, value))
                    offset += 64
                elif param_type == "bool":
                    value = calldata[offset + 63:offset + 64] == "1"
                    params.append(DecodedParam(param_name, param_type, value))
                    offset += 64
                elif param_type == "string":
                    data_offset = int(calldata[offset:offset + 64], 16) * 2
                    length = int(calldata[data_offset:data_offset + 64], 16) * 2
                    value = bytes.fromhex(calldata[data_offset + 64:data_offset + 64 + length]).decode("utf-8", errors="replace")
                    params.append(DecodedParam(param_name, param_type, value))
                    offset += 64
                else:
                    params.append(DecodedParam(param_name, param_type, f"0x{calldata[offset:offset + 64]}"))
                    offset += 64
            except Exception:
                params.append(DecodedParam(param_name, param_type, None))
                offset += 64

        return params

    def decode_transaction_batch(
        self,
        transactions: List[Dict[str, Any]],
    ) -> List[DecodedTransaction]:
        decoded = []
        for tx in transactions:
            result = self.decode_transaction(tx)
            if result:
                decoded.append(result)
        return decoded

    def get_known_functions(self) -> Dict[str, str]:
        return {
            f"0x{selector}": data["signature"]
            for selector, data in self._known_selectors.items()
        }

    def load_abi_from_json(self, contract_address: str, json_path: str) -> None:
        with open(json_path, "r") as f:
            abi = json.load(f)
        self.register_abi(contract_address, abi)
