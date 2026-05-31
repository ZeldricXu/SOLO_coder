#!/usr/bin/env python
"""
使用示例：区块链基础设施平台
演示各个核心模块的基本用法
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path
from typing import Any, Dict

sys.path.insert(0, str(Path(__file__).parent.parent))

from src.core.usecases.address_manager_usecase import AddressBookService, HDWalletService
from src.core.usecases.zkp_verifier_usecase import ZKPVerifierService
from src.shared.types import Chain, ZKPProof


async def example_wallet_usage():
    """演示HD钱包和地址簿的使用"""
    print("=" * 60)
    print("示例1: HD钱包与地址管理")
    print("=" * 60)

    wallet = HDWalletService()
    address_book = AddressBookService()

    try:
        mnemonic = await wallet.generate_mnemonic()
        print(f"生成助记词: {mnemonic}")

        await wallet.create_wallet_from_mnemonic(mnemonic)
        print("钱包创建成功")

        account = await wallet.derive_address(0, label="Primary Account", tags=["main", "hot"])
        print(f"派生地址索引 0: {account.address}")
        print(f"  路径: {account.path}")
        print(f"  标签: {account.tags}")

        account2 = await wallet.derive_next_address(label="Secondary Account")
        print(f"派生下一个地址: {account2.address}")

        key = await address_book.add_address(
            address=account.address,
            name="Main Wallet",
            chain=Chain.ETHEREUM,
            labels=["personal", "primary"],
            notes="主要使用的钱包地址",
        )
        print(f"地址已添加到地址簿, key: {key}")

        entry = await address_book.get_address(account.address)
        print(f"查询地址信息: {entry['name']} - {entry['address']}")

        addresses = await wallet.list_addresses()
        print(f"钱包中共有 {len(addresses)} 个地址")

    except Exception as e:
        print(f"钱包示例跳过 (缺少依赖): {e}")


async def example_zkp_usage():
    """演示零知识证明验证的使用"""
    print("\n" + "=" * 60)
    print("示例2: 零知识证明验证")
    print("=" * 60)

    verifier = ZKPVerifierService()

    circuit_id = "my_circuit_v1"
    verification_key = "0x" + "a" * 128

    await verifier.register_circuit(
        circuit_id=circuit_id,
        verification_key=verification_key,
        circuit_type="groth16",
        metadata={"description": "示例电路"},
    )
    print(f"电路已注册: {circuit_id}")

    circuits = await verifier.get_registered_circuits()
    print(f"已注册电路: {circuits}")

    proof = ZKPProof(
        proof_type="groth16",
        circuit_id=circuit_id,
        proof_data="0x" + "b" * 256,
        public_inputs=["0x1234", "0x5678"],
    )

    result = await verifier.verify_proof(proof)
    print(f"证明验证结果:")
    print(f"  验证通过: {result.verified}")
    print(f"  耗时: {result.verification_time_ms}ms")
    print(f"  电路ID: {result.circuit_id}")
    if result.error:
        print(f"  错误: {result.error}")


async def example_address_book_search():
    """演示地址簿搜索功能"""
    print("\n" + "=" * 60)
    print("示例3: 地址簿搜索与标签管理")
    print("=" * 60)

    address_book = AddressBookService()

    test_addresses = [
        ("0x" + "1" * 40, "Alice", ["user", "verified"], Chain.ETHEREUM),
        ("0x" + "2" * 40, "Bob Exchange", ["exchange", "dex"], Chain.ETHEREUM),
        ("0x" + "3" * 40, "Charlie DeFi", ["defi", "lending"], Chain.POLYGON),
        ("0x" + "4" * 40, "Alice Backup", ["user", "backup"], Chain.ETHEREUM),
    ]

    for addr, name, labels, chain in test_addresses:
        await address_book.add_address(addr, name, chain, labels=labels)
        print(f"已添加: {name} ({chain.value})")

    results = await address_book.search_addresses("Alice")
    print(f"\n搜索 'Alice' 找到 {len(results)} 个结果:")
    for r in results:
        print(f"  - {r['name']}: {r['address'][:12]}...")

    results = await address_book.list_addresses(chain=Chain.ETHEREUM)
    print(f"\n以太坊链上共有 {len(results)} 个地址")

    results = await address_book.list_addresses(labels=["user"])
    print(f"标签 'user' 共有 {len(results)} 个地址")


async def main():
    """运行所有示例"""
    print("\n区块链基础设施平台 - 使用示例")
    print("=" * 60 + "\n")

    await example_wallet_usage()
    await example_zkp_usage()
    await example_address_book_search()

    print("\n" + "=" * 60)
    print("示例运行完成!")
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
