"""
Builder Module
==============

This module provides a comprehensive set of test data builders using the Builder pattern.
All test data construction should be done through these builders to ensure consistency
and maintainability across the test suite.

Available Builders:
- ZkpProofBuilder: For ZKP proof test data
- HdWalletBuilder: For HD wallet test data
- AddressBookBuilder: For address book test data
- BlockDataBuilder: For block indexer test data

Usage Example:
    from tests.builders import BuilderFactory

    # Build a valid ZKP proof
    proof = BuilderFactory.zkp_proof().with_valid_proof().build()

    # Build 10 concurrent wallet derivation requests
    wallets = BuilderFactory.bulk_build(
        BuilderFactory.hd_wallet().for_eth(),
        count=10,
        modifier=lambda i, b: b.with_index(i).with_label(f"Wallet {i}")
    )
"""

from . import (
    BaseBuilder,
    ZkpProofBuilder,
    ZkpProofData,
    HdWalletBuilder,
    HdWalletData,
    AddressBookBuilder,
    AddressBookData,
    BlockDataBuilder,
    BlockData,
    TransactionData,
    BuilderFactory,
)

__all__ = [
    "BaseBuilder",
    "ZkpProofBuilder",
    "ZkpProofData",
    "HdWalletBuilder",
    "HdWalletData",
    "AddressBookBuilder",
    "AddressBookData",
    "BlockDataBuilder",
    "BlockData",
    "TransactionData",
    "BuilderFactory",
]
