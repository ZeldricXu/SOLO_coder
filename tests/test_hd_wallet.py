"""
Test suite for HD Wallet Address Derivation and Management Module
Focus: Concurrent operation safety and data consistency
"""
import asyncio
import concurrent.futures
import threading
import time
import random
import pytest
from unittest.mock import Mock, MagicMock, patch, AsyncMock

from tests.builders import BuilderFactory


class TestHdWalletNormalFlow:
    """Test suite for normal HD wallet operations."""

    @pytest.mark.unit
    @pytest.mark.hdwallet
    @pytest.mark.normal
    def test_derive_eth_address_success(self, mock_meter_registry):
        """Test successful Ethereum address derivation."""
        wallet_builder = BuilderFactory.hd_wallet().for_eth().with_index(0)
        request = wallet_builder.build_request_dict()

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()

        with patch('didauth.module.hdwallet.service.HdWalletMapper', return_value=mock_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=mock_mapper,
                addressBookMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.deriveAddress(request))

            assert result is not None
            assert result.address.startswith("0x")
            assert len(result.address) == 42
            assert result.chainType == "ETH"
            assert result.derivationPath == "m/44'/60'/0'/0/0"
            assert result.walletId is not None

            mock_mapper.insert.assert_called_once()

    @pytest.mark.unit
    @pytest.mark.hdwallet
    @pytest.mark.normal
    def test_derive_btc_address_success(self, mock_meter_registry):
        """Test successful Bitcoin address derivation."""
        wallet_builder = BuilderFactory.hd_wallet().for_btc().with_index(5)
        request = wallet_builder.build_request_dict()

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()

        with patch('didauth.module.hdwallet.service.HdWalletMapper', return_value=mock_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=mock_mapper,
                addressBookMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.deriveAddress(request))

            assert result.address.startswith("bc1")
            assert result.chainType == "BTC"

    @pytest.mark.unit
    @pytest.mark.hdwallet
    @pytest.mark.normal
    def test_derive_address_with_labels(self, mock_meter_registry):
        """Test address derivation with custom labels and tags."""
        wallet_builder = BuilderFactory.hd_wallet() \
            .for_eth() \
            .with_label("My DeFi Wallet") \
            .with_tags(["defi", "main", "trading"])
        request = wallet_builder.build_request_dict()

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()

        with patch('didauth.module.hdwallet.service.HdWalletMapper', return_value=mock_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=mock_mapper,
                addressBookMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.deriveAddress(request))

            assert result.label == "My DeFi Wallet"
            call_args = mock_mapper.insert.call_args[0][0]
            assert call_args.tags == "defi,main,trading"

    @pytest.mark.unit
    @pytest.mark.hdwallet
    @pytest.mark.normal
    def test_derive_hd_path_sequence(self, mock_meter_registry):
        """Test that sequential indices produce different addresses."""
        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()

        with patch('didauth.module.hdwallet.service.HdWalletMapper', return_value=mock_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=mock_mapper,
                addressBookMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            addresses = []
            for i in range(10):
                wallet_builder = BuilderFactory.hd_wallet().for_eth().with_index(i)
                request = wallet_builder.build_request_dict()
                result = asyncio.run(service.deriveAddress(request))
                addresses.append(result.address)

            assert len(addresses) == len(set(addresses)), "All derived addresses should be unique"

    @pytest.mark.unit
    @pytest.mark.hdwallet
    @pytest.mark.normal
    def test_get_wallet_details(self, mock_meter_registry):
        """Test retrieving wallet details by wallet ID."""
        wallet_data = BuilderFactory.hd_wallet().for_eth().build()

        mock_mapper = MagicMock()
        mock_mapper.selectOne = Mock(return_value=wallet_data)

        with patch('didauth.module.hdwallet.service.HdWalletMapper', return_value=mock_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=mock_mapper,
                addressBookMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.getWallet(wallet_data.wallet_id))

            assert result.wallet_id == wallet_data.wallet_id
            assert result.address == wallet_data.address
            mock_mapper.selectOne.assert_called_once()

    @pytest.mark.unit
    @pytest.mark.hdwallet
    @pytest.mark.normal
    def test_list_wallets_by_user(self, mock_meter_registry):
        """Test listing wallets for a specific user."""
        mock_mapper = MagicMock()
        mock_wallets = [BuilderFactory.hd_wallet().for_eth().build() for _ in range(5)]
        mock_mapper.selectList = Mock(return_value=mock_wallets)

        with patch('didauth.module.hdwallet.service.HdWalletMapper', return_value=mock_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=mock_mapper,
                addressBookMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.listWallets("user_test_001", "ETH"))

            assert len(result) == 5


class TestHdWalletConcurrentSafety:
    """Test suite for concurrent operation safety in HD wallet module."""

    @pytest.mark.concurrent
    @pytest.mark.hdwallet
    def test_concurrent_address_derivation(self, mock_meter_registry):
        """Test that concurrent address derivations are thread-safe."""
        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()

        with patch('didauth.module.hdwallet.service.HdWalletMapper', return_value=mock_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=mock_mapper,
                addressBookMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            async def derive_address(index):
                wallet_builder = BuilderFactory.hd_wallet() \
                    .for_eth() \
                    .with_index(index) \
                    .with_label(f"Wallet {index}")
                request = wallet_builder.build_request_dict()
                return await service.deriveAddress(request)

            async def concurrent_derive():
                tasks = [derive_address(i) for i in range(50)]
                return await asyncio.gather(*tasks)

            results = asyncio.run(concurrent_derive())

            assert len(results) == 50
            addresses = [r.address for r in results]
            wallet_ids = [r.walletId for r in results]

            assert len(addresses) == len(set(addresses)), "Concurrent derivations should produce unique addresses"
            assert len(wallet_ids) == len(set(wallet_ids)), "Concurrent derivations should produce unique wallet IDs"
            assert mock_mapper.insert.call_count == 50

    @pytest.mark.concurrent
    @pytest.mark.hdwallet
    def test_concurrent_address_book_additions(self, mock_meter_registry):
        """Test thread safety of concurrent address book additions."""
        mock_addressbook_mapper = MagicMock()
        mock_addressbook_mapper.selectOne = Mock(return_value=None)
        mock_addressbook_mapper.insert = Mock()

        with patch('didauth.module.hdwallet.service.AddressBookMapper', return_value=mock_addressbook_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=MagicMock(),
                addressBookMapper=mock_addressbook_mapper,
                meterRegistry=mock_meter_registry
            )

            async def add_address_book(index):
                builder = BuilderFactory.address_book() \
                    .for_eth() \
                    .with_whitelist(index % 2 == 0)
                request = builder.build_request_dict()
                return await service.addAddressBook(request)

            async def concurrent_add():
                tasks = [add_address_book(i) for i in range(30)]
                return await asyncio.gather(*tasks)

            results = asyncio.run(concurrent_add())

            assert len(results) == 30
            assert mock_addressbook_mapper.insert.call_count == 30

    @pytest.mark.concurrent
    @pytest.mark.hdwallet
    def test_concurrent_derivation_no_duplicate_paths(self, mock_meter_registry):
        """Test that concurrent derivations with same path are handled safely."""
        mock_mapper = MagicMock()
        inserted_records = []

        def track_insert(entity):
            inserted_records.append({
                "derivationPath": entity.derivationPath,
                "address": entity.address,
                "timestamp": time.time()
            })

        mock_mapper.insert = Mock(side_effect=track_insert)

        with patch('didauth.module.hdwallet.service.HdWalletMapper', return_value=mock_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=mock_mapper,
                addressBookMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            async def derive_same_path():
                wallet_builder = BuilderFactory.hd_wallet() \
                    .for_eth() \
                    .with_index(0)
                request = wallet_builder.build_request_dict()
                return await service.deriveAddress(request)

            async def concurrent_same_path():
                tasks = [derive_same_path() for _ in range(10)]
                return await asyncio.gather(*tasks)

            results = asyncio.run(concurrent_same_path())

            paths = [r.derivationPath for r in results]
            assert all(p == "m/44'/60'/0'/0/0" for p in paths)

            addresses = [r.address for r in results]
            assert len(set(addresses)) >= 1

    @pytest.mark.concurrent
    @pytest.mark.hdwallet
    def test_concurrent_crud_operations(self, mock_meter_registry):
        """Test concurrent CRUD operations on address book."""
        mock_addressbook_mapper = MagicMock()
        mock_addressbook_mapper.selectOne = Mock(return_value=None)
        mock_addressbook_mapper.insert = Mock()
        mock_addressbook_mapper.selectList = Mock(return_value=[])
        mock_addressbook_mapper.deleteById = Mock()

        with patch('didauth.module.hdwallet.service.AddressBookMapper', return_value=mock_addressbook_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=MagicMock(),
                addressBookMapper=mock_addressbook_mapper,
                meterRegistry=mock_meter_registry
            )

            async def mixed_operations(index):
                if index % 3 == 0:
                    builder = BuilderFactory.address_book().for_eth()
                    req = builder.build_request_dict()
                    return await service.addAddressBook(req)
                elif index % 3 == 1:
                    return await service.listAddressBook("user_test_001", "ETH")
                else:
                    return await service.deleteAddressBook(f"addr_{index}")

            async def concurrent_mixed():
                tasks = [mixed_operations(i) for i in range(30)]
                return await asyncio.gather(*tasks)

            results = asyncio.run(concurrent_mixed())

            assert len(results) == 30
            assert mock_addressbook_mapper.insert.call_count > 0
            assert mock_addressbook_mapper.selectList.call_count > 0
            assert mock_addressbook_mapper.deleteById.call_count > 0

    @pytest.mark.concurrent
    @pytest.mark.hdwallet
    def test_thread_safety_with_thread_pool(self, mock_meter_registry):
        """Test thread safety using ThreadPoolExecutor."""
        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()

        with patch('didauth.module.hdwallet.service.HdWalletMapper', return_value=mock_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=mock_mapper,
                addressBookMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            def derive_sync(index):
                loop = asyncio.new_event_loop()
                asyncio.set_event_loop(loop)
                try:
                    wallet_builder = BuilderFactory.hd_wallet().for_eth().with_index(index)
                    request = wallet_builder.build_request_dict()
                    return loop.run_until_complete(service.deriveAddress(request))
                finally:
                    loop.close()

            with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
                futures = [executor.submit(derive_sync, i) for i in range(20)]
                results = [f.result() for f in concurrent.futures.as_completed(futures)]

            assert len(results) == 20
            addresses = [r.address for r in results]
            assert len(addresses) == len(set(addresses))

    @pytest.mark.concurrent
    @pytest.mark.hdwallet
    def test_concurrent_derivation_memory_consistency(self, mock_meter_registry):
        """Test memory consistency during high concurrency."""
        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()

        with patch('didauth.module.hdwallet.service.HdWalletMapper', return_value=mock_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=mock_mapper,
                addressBookMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            async def stress_test(batch_size):
                tasks = []
                for i in range(batch_size):
                    builder = BuilderFactory.hd_wallet() \
                        .for_eth() \
                        .with_index(i)
                    req = builder.build_request_dict()
                    tasks.append(service.deriveAddress(req))
                return await asyncio.gather(*tasks, return_exceptions=True)

            results = asyncio.run(stress_test(100))

            exceptions = [r for r in results if isinstance(r, Exception)]
            assert len(exceptions) == 0, f"No exceptions expected, got: {exceptions}"

            successes = [r for r in results if not isinstance(r, Exception)]
            assert len(successes) == 100


class TestHdWalletEdgeCases:
    """Test suite for HD wallet edge cases."""

    @pytest.mark.unit
    @pytest.mark.hdwallet
    @pytest.mark.exception
    def test_derive_unknown_chain_type(self, mock_meter_registry):
        """Test that unknown chain types raise appropriate exception."""
        wallet_builder = BuilderFactory.hd_wallet()
        request = wallet_builder.build_request_dict()
        request["chainType"] = "UNKNOWN_CHAIN"

        mock_mapper = MagicMock()

        with patch('didauth.module.hdwallet.service.HdWalletMapper', return_value=mock_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=mock_mapper,
                addressBookMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            with pytest.raises(Exception):
                asyncio.run(service.deriveAddress(request))

    @pytest.mark.unit
    @pytest.mark.hdwallet
    @pytest.mark.exception
    def test_get_nonexistent_wallet(self, mock_meter_registry):
        """Test that querying non-existent wallet raises not found."""
        mock_mapper = MagicMock()
        mock_mapper.selectOne = Mock(return_value=None)

        with patch('didauth.module.hdwallet.service.HdWalletMapper', return_value=mock_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            from didauth.common.exception import BusinessException
            service = HdWalletService(
                hdWalletMapper=mock_mapper,
                addressBookMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            with pytest.raises(BusinessException) as exc_info:
                asyncio.run(service.getWallet("nonexistent_wallet"))

            assert exc_info.value.code == 404

    @pytest.mark.unit
    @pytest.mark.hdwallet
    @pytest.mark.exception
    def test_add_duplicate_address_book(self, mock_meter_registry):
        """Test that duplicate address book entries are rejected."""
        existing_entry = BuilderFactory.address_book().for_eth().build()

        mock_addressbook_mapper = MagicMock()
        mock_addressbook_mapper.selectOne = Mock(return_value=existing_entry)

        with patch('didauth.module.hdwallet.service.AddressBookMapper', return_value=mock_addressbook_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=MagicMock(),
                addressBookMapper=mock_addressbook_mapper,
                meterRegistry=mock_meter_registry
            )

            builder = BuilderFactory.address_book().for_eth()
            request = builder.build_request_dict()
            request["address"] = existing_entry.address

            with pytest.raises(Exception):
                asyncio.run(service.addAddressBook(request))

    @pytest.mark.unit
    @pytest.mark.hdwallet
    @pytest.mark.exception
    def test_derive_very_large_index(self, mock_meter_registry):
        """Test derivation with a very large derivation index."""
        wallet_builder = BuilderFactory.hd_wallet().for_eth().with_index(2**31 - 1)
        request = wallet_builder.build_request_dict()

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()

        with patch('didauth.module.hdwallet.service.HdWalletMapper', return_value=mock_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=mock_mapper,
                addressBookMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.deriveAddress(request))

            assert result is not None
            assert f"{2**31 - 1}" in result.derivationPath

    @pytest.mark.unit
    @pytest.mark.hdwallet
    def test_derive_all_supported_chains(self, mock_meter_registry):
        """Test address derivation across all supported chain types."""
        chains = ["ETH", "BTC", "POLYGON", "BSC", "ARBITRUM", "OPTIMISM"]

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()

        with patch('didauth.module.hdwallet.service.HdWalletMapper', return_value=mock_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=mock_mapper,
                addressBookMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            for chain in chains:
                builder = BuilderFactory.hd_wallet()
                builder._data["chain_type"] = chain
                request = builder.build_request_dict()
                request["chainType"] = chain

                result = asyncio.run(service.deriveAddress(request))

                assert result.chainType == chain
                assert result.address is not None


class TestAddressBookCrud:
    """Test suite for address book CRUD operations."""

    @pytest.mark.unit
    @pytest.mark.hdwallet
    def test_add_address_book_success(self, mock_meter_registry):
        """Test successful addition to address book."""
        mock_addressbook_mapper = MagicMock()
        mock_addressbook_mapper.selectOne = Mock(return_value=None)
        mock_addressbook_mapper.insert = Mock()

        with patch('didauth.module.hdwallet.service.AddressBookMapper', return_value=mock_addressbook_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=MagicMock(),
                addressBookMapper=mock_addressbook_mapper,
                meterRegistry=mock_meter_registry
            )

            builder = BuilderFactory.address_book() \
                .for_eth() \
                .with_whitelist(True)
            request = builder.build_request_dict()

            result = asyncio.run(service.addAddressBook(request))

            assert result is not None
            mock_addressbook_mapper.insert.assert_called_once()

    @pytest.mark.unit
    @pytest.mark.hdwallet
    def test_list_address_book_with_filters(self, mock_meter_registry):
        """Test listing address book with various filters."""
        mock_addressbook_mapper = MagicMock()
        mock_addressbook_mapper.selectList = Mock(return_value=[])

        with patch('didauth.module.hdwallet.service.AddressBookMapper', return_value=mock_addressbook_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=MagicMock(),
                addressBookMapper=mock_addressbook_mapper,
                meterRegistry=mock_meter_registry
            )

            asyncio.run(service.listAddressBook("user_test_001", "ETH"))

            call_args = mock_addressbook_mapper.selectList.call_args[0][0]
            assert call_args is not None

    @pytest.mark.unit
    @pytest.mark.hdwallet
    def test_delete_address_book(self, mock_meter_registry):
        """Test successful deletion from address book."""
        mock_addressbook_mapper = MagicMock()
        mock_addressbook_mapper.deleteById = Mock()

        with patch('didauth.module.hdwallet.service.AddressBookMapper', return_value=mock_addressbook_mapper):
            from didauth.module.hdwallet.service import HdWalletService
            service = HdWalletService(
                hdWalletMapper=MagicMock(),
                addressBookMapper=mock_addressbook_mapper,
                meterRegistry=mock_meter_registry
            )

            asyncio.run(service.deleteAddressBook("addr_test_001"))

            mock_addressbook_mapper.deleteById.assert_called_once_with("addr_test_001")
