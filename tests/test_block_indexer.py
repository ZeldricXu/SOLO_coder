"""
Test suite for Block Data Indexer Module
Focus: Resource release completeness and memory management
"""
import asyncio
import gc
import os
import psutil
import sys
import time
import weakref
import pytest
from unittest.mock import Mock, MagicMock, patch, AsyncMock, call

from tests.builders import BuilderFactory


class TestBlockIndexerNormalFlow:
    """Test suite for normal block indexing operations."""

    @pytest.mark.unit
    @pytest.mark.indexer
    @pytest.mark.normal
    def test_parse_block_success(self, mock_meter_registry):
        """Test successful block parsing and indexing."""
        block_builder = BuilderFactory.block_data() \
            .for_eth() \
            .with_block_number(1000001) \
            .with_transaction_count(15)
        request = block_builder.build_request_dict()

        mock_block_mapper = MagicMock()
        mock_block_mapper.selectOne = Mock(return_value=None)
        mock_block_mapper.insert = Mock()

        mock_tx_mapper = MagicMock()
        mock_tx_mapper.selectOne = Mock(return_value=None)
        mock_tx_mapper.insert = Mock()

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=mock_block_mapper), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=mock_tx_mapper):
            from didauth.module.indexer.service import BlockIndexerService
            service = BlockIndexerService(
                blockIndexMapper=mock_block_mapper,
                transactionIndexMapper=mock_tx_mapper,
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.parseAndIndexBlock(request))

            assert result is not None
            assert mock_block_mapper.insert.call_count == 1
            assert mock_tx_mapper.insert.call_count == 15

    @pytest.mark.unit
    @pytest.mark.indexer
    @pytest.mark.normal
    def test_parse_empty_block(self, mock_meter_registry):
        """Test parsing a block with no transactions."""
        block_builder = BuilderFactory.block_data() \
            .for_eth() \
            .with_empty_transactions()
        request = block_builder.build_request_dict()

        mock_block_mapper = MagicMock()
        mock_block_mapper.selectOne = Mock(return_value=None)
        mock_block_mapper.insert = Mock()

        mock_tx_mapper = MagicMock()

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=mock_block_mapper), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=mock_tx_mapper):
            from didauth.module.indexer.service import BlockIndexerService
            service = BlockIndexerService(
                blockIndexMapper=mock_block_mapper,
                transactionIndexMapper=mock_tx_mapper,
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.parseAndIndexBlock(request))

            assert result is not None
            assert mock_block_mapper.insert.call_count == 1
            assert mock_tx_mapper.insert.call_count == 0

    @pytest.mark.unit
    @pytest.mark.indexer
    @pytest.mark.normal
    def test_get_block_by_number(self, mock_meter_registry):
        """Test retrieving block by number."""
        block_data = BuilderFactory.block_data().for_eth().with_block_number(1000000).build()

        mock_block_mapper = MagicMock()
        mock_block_mapper.selectOne = Mock(return_value=block_data)

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=mock_block_mapper), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=MagicMock()):
            from didauth.module.indexer.service import BlockIndexerService
            service = BlockIndexerService(
                blockIndexMapper=mock_block_mapper,
                transactionIndexMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.getBlockByNumber("ETH", 1000000))

            assert result.block_number == 1000000
            mock_block_mapper.selectOne.assert_called_once()

    @pytest.mark.unit
    @pytest.mark.indexer
    @pytest.mark.normal
    def test_get_transaction_by_hash(self, mock_meter_registry):
        """Test retrieving transaction by hash."""
        block_data = BuilderFactory.block_data().for_eth().with_transaction_count(1).build()
        tx_data = block_data.transactions[0] if block_data.transactions else None

        mock_tx_mapper = MagicMock()
        mock_tx_mapper.selectOne = Mock(return_value=MagicMock(
            txHash=tx_data["txHash"],
            fromAddress=tx_data["fromAddress"],
            toAddress=tx_data["toAddress"]
        ))

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=MagicMock()), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=mock_tx_mapper):
            from didauth.module.indexer.service import BlockIndexerService
            service = BlockIndexerService(
                blockIndexMapper=MagicMock(),
                transactionIndexMapper=mock_tx_mapper,
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.getTransactionByHash("ETH", tx_data["txHash"]))

            assert result.tx_hash == tx_data["txHash"]

    @pytest.mark.unit
    @pytest.mark.indexer
    @pytest.mark.normal
    def test_get_latest_blocks(self, mock_meter_registry):
        """Test retrieving latest blocks."""
        mock_blocks = [
            BuilderFactory.block_data().for_eth().with_block_number(1000000 + i).build()
            for i in range(10)
        ]

        mock_block_mapper = MagicMock()
        mock_block_mapper.selectList = Mock(return_value=mock_blocks)

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=mock_block_mapper), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=MagicMock()):
            from didauth.module.indexer.service import BlockIndexerService
            service = BlockIndexerService(
                blockIndexMapper=mock_block_mapper,
                transactionIndexMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.getLatestBlocks("ETH", 10))

            assert len(result) == 10
            mock_block_mapper.selectList.assert_called_once()


class TestBlockIndexerResourceRelease:
    """Test suite for resource release completeness in block indexer."""

    @pytest.mark.resource
    @pytest.mark.indexer
    def test_large_block_memory_release(self, mock_meter_registry):
        """Test that memory is properly released after processing large blocks."""
        initial_memory = psutil.Process(os.getpid()).memory_info().rss

        mock_block_mapper = MagicMock()
        mock_block_mapper.selectOne = Mock(return_value=None)
        mock_block_mapper.insert = Mock()

        mock_tx_mapper = MagicMock()
        mock_tx_mapper.selectOne = Mock(return_value=None)
        mock_tx_mapper.insert = Mock()

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=mock_block_mapper), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=mock_tx_mapper):
            from didauth.module.indexer.service import BlockIndexerService
            service = BlockIndexerService(
                blockIndexMapper=mock_block_mapper,
                transactionIndexMapper=mock_tx_mapper,
                meterRegistry=mock_meter_registry
            )

            block_builder = BuilderFactory.block_data() \
                .for_eth() \
                .with_large_block(tx_count=500)
            request = block_builder.build_request_dict()

            result = asyncio.run(service.parseAndIndexBlock(request))

            del request
            del result
            gc.collect()

            final_memory = psutil.Process(os.getpid()).memory_info().rss
            memory_diff = final_memory - initial_memory

            max_allowed_increase = 50 * 1024 * 1024
            assert memory_diff < max_allowed_increase, \
                f"Memory leak detected: increased by {memory_diff / 1024 / 1024:.2f}MB"

    @pytest.mark.resource
    @pytest.mark.indexer
    def test_concurrent_block_indexing_resource_usage(self, mock_meter_registry):
        """Test resource usage during concurrent block indexing."""
        mock_block_mapper = MagicMock()
        mock_block_mapper.selectOne = Mock(return_value=None)
        mock_block_mapper.insert = Mock()

        mock_tx_mapper = MagicMock()
        mock_tx_mapper.selectOne = Mock(return_value=None)
        mock_tx_mapper.insert = Mock()

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=mock_block_mapper), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=mock_tx_mapper):
            from didauth.module.indexer.service import BlockIndexerService
            service = BlockIndexerService(
                blockIndexMapper=mock_block_mapper,
                transactionIndexMapper=mock_tx_mapper,
                meterRegistry=mock_meter_registry
            )

            async def index_block(block_num):
                builder = BuilderFactory.block_data() \
                    .for_eth() \
                    .with_block_number(block_num) \
                    .with_transaction_count(10)
                request = builder.build_request_dict()
                return await service.parseAndIndexBlock(request)

            async def concurrent_index():
                tasks = [index_block(1000000 + i) for i in range(20)]
                return await asyncio.gather(*tasks)

            initial_fds = len(psutil.Process(os.getpid()).open_files())

            results = asyncio.run(concurrent_index())

            del results
            gc.collect()

            final_fds = len(psutil.Process(os.getpid()).open_files())
            fd_diff = final_fds - initial_fds

            assert fd_diff <= 0, f"File descriptor leak detected: {fd_diff} descriptors not released"

    @pytest.mark.resource
    @pytest.mark.indexer
    def test_weak_reference_cleanup(self, mock_meter_registry):
        """Test that objects are properly garbage collected after use."""
        mock_block_mapper = MagicMock()
        mock_block_mapper.selectOne = Mock(return_value=None)
        mock_block_mapper.insert = Mock()

        mock_tx_mapper = MagicMock()
        mock_tx_mapper.selectOne = Mock(return_value=None)
        mock_tx_mapper.insert = Mock()

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=mock_block_mapper), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=mock_tx_mapper):
            from didauth.module.indexer.service import BlockIndexerService
            service = BlockIndexerService(
                blockIndexMapper=mock_block_mapper,
                transactionIndexMapper=mock_tx_mapper,
                meterRegistry=mock_meter_registry
            )

            block_builder = BuilderFactory.block_data() \
                .for_eth() \
                .with_block_number(1000001) \
                .with_transaction_count(5)
            request = block_builder.build_request_dict()

            result = asyncio.run(service.parseAndIndexBlock(request))

            weak_result = weakref.ref(result)

            del result
            gc.collect()

            assert weak_result() is None, "Result object was not garbage collected"

    @pytest.mark.resource
    @pytest.mark.indexer
    def test_database_connection_release(self, mock_meter_registry):
        """Test that database connections are properly released after use."""
        connection_count = 0
        max_connections = 0
        connection_refs = []

        def track_insert(entity):
            nonlocal connection_count, max_connections
            connection_count += 1
            connection_refs.append(weakref.ref(entity))
            max_connections = max(max_connections, connection_count)

        def track_insert_done(*args, **kwargs):
            nonlocal connection_count
            connection_count -= 1

        mock_block_mapper = MagicMock()
        mock_block_mapper.selectOne = Mock(return_value=None)
        mock_block_mapper.insert = Mock(side_effect=track_insert)

        mock_tx_mapper = MagicMock()
        mock_tx_mapper.selectOne = Mock(return_value=None)
        mock_tx_mapper.insert = Mock(side_effect=track_insert)

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=mock_block_mapper), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=mock_tx_mapper):
            from didauth.module.indexer.service import BlockIndexerService
            service = BlockIndexerService(
                blockIndexMapper=mock_block_mapper,
                transactionIndexMapper=mock_tx_mapper,
                meterRegistry=mock_meter_registry
            )

            for i in range(10):
                builder = BuilderFactory.block_data() \
                    .for_eth() \
                    .with_block_number(1000000 + i) \
                    .with_transaction_count(5)
                request = builder.build_request_dict()
                asyncio.run(service.parseAndIndexBlock(request))

            gc.collect()

            live_refs = [ref() for ref in connection_refs if ref() is not None]
            assert len(live_refs) == 0, f"{len(live_refs)} database entities not garbage collected"

    @pytest.mark.resource
    @pytest.mark.indexer
    def test_transaction_batch_memory_release(self, mock_meter_registry):
        """Test memory release when processing batches of transactions."""
        mock_block_mapper = MagicMock()
        mock_block_mapper.selectOne = Mock(return_value=None)
        mock_block_mapper.insert = Mock()

        mock_tx_mapper = MagicMock()
        mock_tx_mapper.selectOne = Mock(return_value=None)
        mock_tx_mapper.insert = Mock()

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=mock_block_mapper), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=mock_tx_mapper):
            from didauth.module.indexer.service import BlockIndexerService
            service = BlockIndexerService(
                blockIndexMapper=mock_block_mapper,
                transactionIndexMapper=mock_tx_mapper,
                meterRegistry=mock_meter_registry
            )

            block_builder = BuilderFactory.block_data() \
                .for_eth() \
                .with_large_block(tx_count=1000)
            request = block_builder.build_request_dict()

            memory_samples = []
            for i in range(5):
                result = asyncio.run(service.parseAndIndexBlock(request))
                gc.collect()
                memory_samples.append(psutil.Process(os.getpid()).memory_info().rss)

            memory_increase = max(memory_samples) - memory_samples[0]
            assert memory_increase < 20 * 1024 * 1024, \
                f"Memory not properly released between batches: {memory_increase / 1024 / 1024:.2f}MB increase"

    @pytest.mark.resource
    @pytest.mark.indexer
    def test_block_duplicate_no_resource_leak(self, mock_meter_registry):
        """Test that indexing duplicate blocks doesn't cause resource leaks."""
        existing_block = BuilderFactory.block_data().for_eth().with_block_number(1000000).build()

        mock_block_mapper = MagicMock()
        mock_block_mapper.selectOne = Mock(return_value=existing_block)

        mock_tx_mapper = MagicMock()

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=mock_block_mapper), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=mock_tx_mapper):
            from didauth.module.indexer.service import BlockIndexerService
            service = BlockIndexerService(
                blockIndexMapper=mock_block_mapper,
                transactionIndexMapper=mock_tx_mapper,
                meterRegistry=mock_meter_registry
            )

            block_builder = BuilderFactory.block_data() \
                .for_eth() \
                .with_block_number(1000000) \
                .with_transaction_count(10)
            request = block_builder.build_request_dict()

            initial_memory = psutil.Process(os.getpid()).memory_info().rss

            for _ in range(100):
                asyncio.run(service.parseAndIndexBlock(request))

            gc.collect()
            final_memory = psutil.Process(os.getpid()).memory_info().rss

            memory_diff = final_memory - initial_memory
            assert memory_diff < 10 * 1024 * 1024, \
                f"Resource leak on duplicate block indexing: {memory_diff / 1024 / 1024:.2f}MB"


class TestBlockIndexerEdgeCases:
    """Test suite for block indexer edge cases."""

    @pytest.mark.unit
    @pytest.mark.indexer
    @pytest.mark.exception
    def test_get_nonexistent_block(self, mock_meter_registry):
        """Test that querying non-existent block raises not found."""
        mock_block_mapper = MagicMock()
        mock_block_mapper.selectOne = Mock(return_value=None)

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=mock_block_mapper), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=MagicMock()):
            from didauth.module.indexer.service import BlockIndexerService
            from didauth.common.exception import BusinessException
            service = BlockIndexerService(
                blockIndexMapper=mock_block_mapper,
                transactionIndexMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            with pytest.raises(BusinessException) as exc_info:
                asyncio.run(service.getBlockByNumber("ETH", 999999999))

            assert exc_info.value.code == 404

    @pytest.mark.unit
    @pytest.mark.indexer
    @pytest.mark.exception
    def test_get_nonexistent_transaction(self, mock_meter_registry):
        """Test that querying non-existent transaction raises not found."""
        mock_tx_mapper = MagicMock()
        mock_tx_mapper.selectOne = Mock(return_value=None)

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=MagicMock()), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=mock_tx_mapper):
            from didauth.module.indexer.service import BlockIndexerService
            from didauth.common.exception import BusinessException
            service = BlockIndexerService(
                blockIndexMapper=MagicMock(),
                transactionIndexMapper=mock_tx_mapper,
                meterRegistry=mock_meter_registry
            )

            with pytest.raises(BusinessException) as exc_info:
                asyncio.run(service.getTransactionByHash("ETH", "0x_nonexistent"))

            assert exc_info.value.code == 404

    @pytest.mark.unit
    @pytest.mark.indexer
    def test_index_duplicate_block(self, mock_meter_registry):
        """Test that indexing an already indexed block returns existing ID."""
        existing_block = BuilderFactory.block_data().for_eth().with_block_number(1000000).build()
        existing_block.id = "existing_block_id"

        mock_block_mapper = MagicMock()
        mock_block_mapper.selectOne = Mock(return_value=existing_block)

        mock_tx_mapper = MagicMock()

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=mock_block_mapper), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=mock_tx_mapper):
            from didauth.module.indexer.service import BlockIndexerService
            service = BlockIndexerService(
                blockIndexMapper=mock_block_mapper,
                transactionIndexMapper=mock_tx_mapper,
                meterRegistry=mock_meter_registry
            )

            block_builder = BuilderFactory.block_data() \
                .for_eth() \
                .with_block_number(1000000)
            request = block_builder.build_request_dict()

            result = asyncio.run(service.parseAndIndexBlock(request))

            assert result == "existing_block_id"
            mock_block_mapper.insert.assert_not_called()

    @pytest.mark.unit
    @pytest.mark.indexer
    @pytest.mark.exception
    def test_index_unknown_chain(self, mock_meter_registry):
        """Test that indexing for unknown chain raises exception."""
        block_builder = BuilderFactory.block_data()
        request = block_builder.build_request_dict()
        request["chainType"] = "UNKNOWN"

        mock_block_mapper = MagicMock()

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=mock_block_mapper), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=MagicMock()):
            from didauth.module.indexer.service import BlockIndexerService
            service = BlockIndexerService(
                blockIndexMapper=mock_block_mapper,
                transactionIndexMapper=MagicMock(),
                meterRegistry=mock_meter_registry
            )

            with pytest.raises(Exception):
                asyncio.run(service.parseAndIndexBlock(request))

    @pytest.mark.unit
    @pytest.mark.indexer
    def test_get_transactions_by_address_pagination(self, mock_meter_registry):
        """Test transaction pagination by address."""
        mock_txs = [MagicMock(txHash=f"0x_tx_{i}") for i in range(20)]

        mock_tx_mapper = MagicMock()
        mock_tx_mapper.selectList = Mock(return_value=mock_txs)

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=MagicMock()), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=mock_tx_mapper):
            from didauth.module.indexer.service import BlockIndexerService
            service = BlockIndexerService(
                blockIndexMapper=MagicMock(),
                transactionIndexMapper=mock_tx_mapper,
                meterRegistry=mock_meter_registry
            )

            results = asyncio.run(
                service.getTransactionsByAddress("ETH", "0x_test_address", 10, 0).collect_list()
            )

            assert len(results) == 20
            call_args = mock_tx_mapper.selectList.call_args[0][0]
            assert call_args is not None


class TestBlockIndexerDataIntegrity:
    """Test suite for block indexer data integrity."""

    @pytest.mark.unit
    @pytest.mark.indexer
    def test_transaction_index_data_integrity(self, mock_meter_registry):
        """Test that indexed transactions maintain data integrity."""
        captured_tx_data = []

        def capture_tx(entity):
            captured_tx_data.append({
                "txHash": entity.tx_hash,
                "fromAddress": entity.from_address,
                "toAddress": entity.to_address,
                "value": entity.value,
            })

        mock_block_mapper = MagicMock()
        mock_block_mapper.selectOne = Mock(return_value=None)
        mock_block_mapper.insert = Mock()

        mock_tx_mapper = MagicMock()
        mock_tx_mapper.selectOne = Mock(return_value=None)
        mock_tx_mapper.insert = Mock(side_effect=capture_tx)

        with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=mock_block_mapper), \
             patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=mock_tx_mapper):
            from didauth.module.indexer.service import BlockIndexerService
            service = BlockIndexerService(
                blockIndexMapper=mock_block_mapper,
                transactionIndexMapper=mock_tx_mapper,
                meterRegistry=mock_meter_registry
            )

            block_builder = BuilderFactory.block_data() \
                .for_eth() \
                .with_block_number(1000001) \
                .with_transaction_count(5)
            request = block_builder.build_request_dict()

            original_txs = {tx["txHash"]: tx for tx in request["transactions"]}

            asyncio.run(service.parseAndIndexBlock(request))

            assert len(captured_tx_data) == 5

            for captured in captured_tx_data:
                original = original_txs[captured["txHash"]]
                assert captured["fromAddress"] == original["fromAddress"]
                assert captured["toAddress"] == original["toAddress"]
                assert captured["value"] == original["value"]

    @pytest.mark.unit
    @pytest.mark.indexer
    def test_block_transaction_count_consistency(self, mock_meter_registry):
        """Test that block transaction count matches actual indexed transactions."""
        tx_counts = [0, 1, 10, 50, 100]

        for expected_count in tx_counts:
            mock_block_mapper = MagicMock()
            mock_block_mapper.selectOne = Mock(return_value=None)

            captured_block = None

            def capture_block(entity):
                nonlocal captured_block
                captured_block = entity

            mock_block_mapper.insert = Mock(side_effect=capture_block)

            mock_tx_mapper = MagicMock()
            mock_tx_mapper.selectOne = Mock(return_value=None)
            mock_tx_mapper.insert = Mock()

            with patch('didauth.module.indexer.service.BlockIndexMapper', return_value=mock_block_mapper), \
                 patch('didauth.module.indexer.service.TransactionIndexMapper', return_value=mock_tx_mapper):
                from didauth.module.indexer.service import BlockIndexerService
                service = BlockIndexerService(
                    blockIndexMapper=mock_block_mapper,
                    transactionIndexMapper=mock_tx_mapper,
                    meterRegistry=mock_meter_registry
                )

                block_builder = BuilderFactory.block_data() \
                    .for_eth() \
                    .with_transaction_count(expected_count)
                request = block_builder.build_request_dict()

                asyncio.run(service.parseAndIndexBlock(request))

                assert captured_block.transaction_count == expected_count
                assert mock_tx_mapper.insert.call_count == expected_count
