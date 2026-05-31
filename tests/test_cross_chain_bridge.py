import pytest
from unittest.mock import MagicMock, patch, AsyncMock
from datetime import datetime

from tests.utils.factories import TestDataFactory
from tests.utils.test_utils import TestUtils
from tests.conftest import CHAIN_IDS, DEFAULT_MULTISIG_OWNERS, DEFAULT_THRESHOLD


class TestCrossChainBridgeService:

    @pytest.fixture
    def factory(self):
        return TestDataFactory()

    @pytest.fixture
    def mock_prisma(self):
        mock = MagicMock()
        mock.crossChainTransfer = MagicMock()
        mock.crossChainTransfer.findUnique = AsyncMock()
        mock.crossChainTransfer.findMany = AsyncMock()
        mock.crossChainTransfer.count = AsyncMock()
        mock.crossChainTransfer.create = AsyncMock()
        mock.crossChainTransfer.update = AsyncMock()
        return mock

    @pytest.fixture
    def mock_cache(self):
        mock = MagicMock()
        mock.get = AsyncMock()
        mock.set = AsyncMock()
        mock.delete = AsyncMock()
        return mock

    @pytest.fixture
    def mock_crypto(self):
        mock = MagicMock()
        mock.deriveAddress = MagicMock()
        mock.isValidAddress = MagicMock(side_effect=lambda addr: addr.startswith('0x') and len(addr) == 42)
        mock.signMessage = MagicMock()
        mock.verifySignature = MagicMock()
        return mock

    @pytest.mark.asyncio
    async def test_initiate_transfer_success(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_pending_transfer()
        source_addr = TestUtils.generate_eth_address()
        target_addr = TestUtils.generate_eth_address()

        mock_prisma.crossChainTransfer.create.return_value = mock_transfer

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                request = {
                    'sourceChainId': CHAIN_IDS['ETHEREUM'],
                    'targetChainId': CHAIN_IDS['BSC'],
                    'sourceAddress': source_addr,
                    'targetAddress': target_addr,
                    'amount': '1000000000000000000',
                }

                result = await service.initiateTransfer(request)

                assert result['transfer']['id'] == mock_transfer['id']
                assert result['transfer']['status'] == 'PENDING'
                assert result['message'] is not None
                assert result['messageHash'] is not None
                mock_prisma.crossChainTransfer.create.assert_called_once()

    @pytest.mark.asyncio
    async def test_initiate_transfer_same_chain_error(self, factory, mock_prisma, mock_cache, mock_crypto):
        source_addr = TestUtils.generate_eth_address()
        target_addr = TestUtils.generate_eth_address()

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                request = {
                    'sourceChainId': CHAIN_IDS['ETHEREUM'],
                    'targetChainId': CHAIN_IDS['ETHEREUM'],
                    'sourceAddress': source_addr,
                    'targetAddress': target_addr,
                    'amount': '1000000000000000000',
                }

                with pytest.raises(ValidationError) as exc_info:
                    await service.initiateTransfer(request)

                assert 'different' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_initiate_transfer_zero_amount_error(self, factory, mock_prisma, mock_cache, mock_crypto):
        source_addr = TestUtils.generate_eth_address()
        target_addr = TestUtils.generate_eth_address()

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                request = {
                    'sourceChainId': CHAIN_IDS['ETHEREUM'],
                    'targetChainId': CHAIN_IDS['BSC'],
                    'sourceAddress': source_addr,
                    'targetAddress': target_addr,
                    'amount': '0',
                }

                with pytest.raises(ValidationError) as exc_info:
                    await service.initiateTransfer(request)

                assert 'positive' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_initiate_transfer_negative_amount_error(self, factory, mock_prisma, mock_cache, mock_crypto):
        source_addr = TestUtils.generate_eth_address()
        target_addr = TestUtils.generate_eth_address()

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                request = {
                    'sourceChainId': CHAIN_IDS['ETHEREUM'],
                    'targetChainId': CHAIN_IDS['BSC'],
                    'sourceAddress': source_addr,
                    'targetAddress': target_addr,
                    'amount': '-100',
                }

                with pytest.raises(ValidationError):
                    await service.initiateTransfer(request)

    @pytest.mark.asyncio
    async def test_initiate_transfer_invalid_source_address(self, factory, mock_prisma, mock_cache, mock_crypto):
        target_addr = TestUtils.generate_eth_address()

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                request = {
                    'sourceChainId': CHAIN_IDS['ETHEREUM'],
                    'targetChainId': CHAIN_IDS['BSC'],
                    'sourceAddress': 'invalid_address',
                    'targetAddress': target_addr,
                    'amount': '1000000000000000000',
                }

                with pytest.raises(ValidationError) as exc_info:
                    await service.initiateTransfer(request)

                assert 'source' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_initiate_transfer_invalid_target_address(self, factory, mock_prisma, mock_cache, mock_crypto):
        source_addr = TestUtils.generate_eth_address()

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                request = {
                    'sourceChainId': CHAIN_IDS['ETHEREUM'],
                    'targetChainId': CHAIN_IDS['BSC'],
                    'sourceAddress': source_addr,
                    'targetAddress': 'invalid_address',
                    'amount': '1000000000000000000',
                }

                with pytest.raises(ValidationError) as exc_info:
                    await service.initiateTransfer(request)

                assert 'target' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_initiate_transfer_large_amount(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_pending_transfer({'amount': '1000000000000000000000000000'})
        source_addr = TestUtils.generate_eth_address()
        target_addr = TestUtils.generate_eth_address()

        mock_prisma.crossChainTransfer.create.return_value = mock_transfer

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                request = {
                    'sourceChainId': CHAIN_IDS['ETHEREUM'],
                    'targetChainId': CHAIN_IDS['BSC'],
                    'sourceAddress': source_addr,
                    'targetAddress': target_addr,
                    'amount': '1000000000000000000000000000',
                }

                result = await service.initiateTransfer(request)

                assert result['transfer']['amount'] == mock_transfer['amount']

    @pytest.mark.asyncio
    async def test_confirm_lock_success(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_pending_transfer()
        updated = {**mock_transfer, 'status': 'LOCKED'}
        tx_hash = TestUtils.generate_transaction_hash()
        signatures = [
            {'signer': DEFAULT_MULTISIG_OWNERS[0], 'signature': TestUtils.generate_signature(), 'timestamp': datetime.now().timestamp() * 1000},
            {'signer': DEFAULT_MULTISIG_OWNERS[1], 'signature': TestUtils.generate_signature(), 'timestamp': datetime.now().timestamp() * 1000},
        ]

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer
        mock_crypto.verifySignature.return_value = True
        mock_prisma.crossChainTransfer.update.return_value = updated

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                result = await service.confirmLock(mock_transfer['id'], tx_hash, signatures)

                assert result['transfer']['status'] == 'LOCKED'
                assert result['canExecute'] == True

    @pytest.mark.asyncio
    async def test_confirm_lock_not_found(self, factory, mock_prisma, mock_cache, mock_crypto):
        tx_hash = TestUtils.generate_transaction_hash()
        signatures = [
            {'signer': DEFAULT_MULTISIG_OWNERS[0], 'signature': TestUtils.generate_signature(), 'timestamp': datetime.now().timestamp() * 1000},
        ]

        mock_prisma.crossChainTransfer.findUnique.return_value = None

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import NotFoundError
                service = CrossChainBridgeService()

                with pytest.raises(NotFoundError):
                    await service.confirmLock(TestUtils.generate_id('cl'), tx_hash, signatures)

    @pytest.mark.asyncio
    async def test_confirm_lock_already_locked(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_locked_transfer()
        tx_hash = TestUtils.generate_transaction_hash()
        signatures = [
            {'signer': DEFAULT_MULTISIG_OWNERS[0], 'signature': TestUtils.generate_signature(), 'timestamp': datetime.now().timestamp() * 1000},
        ]

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                with pytest.raises(Exception) as exc_info:
                    await service.confirmLock(mock_transfer['id'], tx_hash, signatures)

                assert 'pending' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_confirm_lock_insufficient_signatures(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_pending_transfer()
        tx_hash = TestUtils.generate_transaction_hash()
        signatures = [
            {'signer': DEFAULT_MULTISIG_OWNERS[0], 'signature': TestUtils.generate_signature(), 'timestamp': datetime.now().timestamp() * 1000},
        ]

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.confirmLock(mock_transfer['id'], tx_hash, signatures)

                assert 'Insufficient' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_validate_message_success(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_locked_transfer()
        updated = {**mock_transfer, 'status': 'VALIDATED'}
        proof = {'blockNumber': 123456, 'transactionIndex': 5, 'blockHash': TestUtils.generate_transaction_hash()}

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer
        mock_prisma.crossChainTransfer.update.return_value = updated

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                result = await service.validateMessage(mock_transfer['id'], proof)

                assert result['transfer']['status'] == 'VALIDATED'
                assert result['readyForMinting'] == True

    @pytest.mark.asyncio
    async def test_validate_message_not_locked(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_pending_transfer()
        proof = {'blockNumber': 123456, 'transactionIndex': 5}

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                with pytest.raises(Exception) as exc_info:
                    await service.validateMessage(mock_transfer['id'], proof)

                assert 'locked' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_validate_message_invalid_proof(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_locked_transfer()
        proof = {'transactionIndex': 5}

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                with pytest.raises(Exception) as exc_info:
                    await service.validateMessage(mock_transfer['id'], proof)

                assert 'proof' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_execute_mint_success(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_validated_transfer()

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer
        mock_prisma.crossChainTransfer.update.return_value = {
            **mock_transfer,
            'status': 'MINTED',
            'targetTxHash': TestUtils.generate_transaction_hash(),
        }

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                result = await service.executeMint(mock_transfer['id'])

                assert result['transfer']['status'] == 'MINTED'
                assert result['mintTransaction']['txHash'] is not None

    @pytest.mark.asyncio
    async def test_execute_mint_not_validated(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_locked_transfer()

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                with pytest.raises(Exception) as exc_info:
                    await service.executeMint(mock_transfer['id'])

                assert 'validated' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_confirm_transfer_success(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_minted_transfer()

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer
        mock_prisma.crossChainTransfer.update.return_value = {**mock_transfer, 'status': 'CONFIRMED'}

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                result = await service.confirmTransfer(mock_transfer['id'])

                assert result['transfer']['status'] == 'CONFIRMED'
                assert result['completed'] == True

    @pytest.mark.asyncio
    async def test_state_transition_pending_to_locked(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_pending_transfer()
        tx_hash = TestUtils.generate_transaction_hash()
        signatures = [
            {'signer': DEFAULT_MULTISIG_OWNERS[0], 'signature': TestUtils.generate_signature(), 'timestamp': datetime.now().timestamp() * 1000},
            {'signer': DEFAULT_MULTISIG_OWNERS[1], 'signature': TestUtils.generate_signature(), 'timestamp': datetime.now().timestamp() * 1000},
        ]

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer
        mock_crypto.verifySignature.return_value = True
        mock_prisma.crossChainTransfer.update.return_value = {**mock_transfer, 'status': 'LOCKED'}

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                result = await service.confirmLock(mock_transfer['id'], tx_hash, signatures)
                assert result['transfer']['status'] == 'LOCKED'

    @pytest.mark.asyncio
    async def test_state_transition_locked_to_validated(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_locked_transfer()
        proof = {'blockNumber': 123456, 'transactionIndex': 5}

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer
        mock_prisma.crossChainTransfer.update.return_value = {**mock_transfer, 'status': 'VALIDATED'}

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                result = await service.validateMessage(mock_transfer['id'], proof)
                assert result['transfer']['status'] == 'VALIDATED'

    @pytest.mark.asyncio
    async def test_state_transition_validated_to_minted(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_validated_transfer()

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer
        mock_prisma.crossChainTransfer.update.return_value = {
            **mock_transfer,
            'status': 'MINTED',
            'targetTxHash': TestUtils.generate_transaction_hash(),
        }

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                result = await service.executeMint(mock_transfer['id'])
                assert result['transfer']['status'] == 'MINTED'

    @pytest.mark.asyncio
    async def test_state_transition_minted_to_confirmed(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_minted_transfer()

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer
        mock_prisma.crossChainTransfer.update.return_value = {**mock_transfer, 'status': 'CONFIRMED'}

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                result = await service.confirmTransfer(mock_transfer['id'])
                assert result['transfer']['status'] == 'CONFIRMED'

    @pytest.mark.asyncio
    async def test_state_transition_skip_pending_to_minted_error(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_pending_transfer()

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                with pytest.raises(Exception):
                    await service.executeMint(mock_transfer['id'])

    @pytest.mark.asyncio
    async def test_get_transfers_with_filters(self, factory, mock_prisma, mock_cache, mock_crypto):
        transfers = factory.create_transfer_list(5)

        mock_prisma.crossChainTransfer.count.return_value = 5
        mock_prisma.crossChainTransfer.findMany.return_value = transfers

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                result = await service.getTransfers({
                    'sourceChainId': CHAIN_IDS['ETHEREUM'],
                    'targetChainId': CHAIN_IDS['BSC'],
                    'status': 'PENDING',
                })

                assert result['total'] == 5
                assert len(result['items']) == 5

    @pytest.mark.asyncio
    async def test_get_pending_transfers(self, factory, mock_prisma, mock_cache, mock_crypto):
        transfers = [
            factory.create_pending_transfer({'sourceChainId': CHAIN_IDS['ETHEREUM']}),
            factory.create_validated_transfer({'targetChainId': CHAIN_IDS['ETHEREUM']}),
        ]

        mock_prisma.crossChainTransfer.findMany.return_value = transfers

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                result = await service.getPendingTransfers(CHAIN_IDS['ETHEREUM'])

                assert len(result) == 2

    @pytest.mark.asyncio
    async def test_initiate_transfer_amount_too_long(self, factory, mock_prisma, mock_cache, mock_crypto):
        source_addr = TestUtils.generate_eth_address()
        target_addr = TestUtils.generate_eth_address()
        long_amount = '9' * 79

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                request = {
                    'sourceChainId': CHAIN_IDS['ETHEREUM'],
                    'targetChainId': CHAIN_IDS['BSC'],
                    'sourceAddress': source_addr,
                    'targetAddress': target_addr,
                    'amount': long_amount,
                }

                with pytest.raises(ValidationError) as exc_info:
                    await service.initiateTransfer(request)

                assert 'Amount cannot exceed' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_confirm_lock_empty_transfer_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        tx_hash = TestUtils.generate_transaction_hash()
        signatures = [
            {'signer': DEFAULT_MULTISIG_OWNERS[0], 'signature': TestUtils.generate_signature()},
        ]

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.confirmLock('', tx_hash, signatures)

                assert 'Transfer ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_confirm_lock_whitespace_transfer_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        tx_hash = TestUtils.generate_transaction_hash()
        signatures = [
            {'signer': DEFAULT_MULTISIG_OWNERS[0], 'signature': TestUtils.generate_signature()},
        ]

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.confirmLock('   ', tx_hash, signatures)

                assert 'Transfer ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_confirm_lock_empty_tx_hash(self, factory, mock_prisma, mock_cache, mock_crypto):
        signatures = [
            {'signer': DEFAULT_MULTISIG_OWNERS[0], 'signature': TestUtils.generate_signature()},
            {'signer': DEFAULT_MULTISIG_OWNERS[1], 'signature': TestUtils.generate_signature()},
        ]

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.confirmLock(TestUtils.generate_id('cl'), '', signatures)

                assert 'Transaction hash is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_confirm_lock_tx_hash_too_long(self, factory, mock_prisma, mock_cache, mock_crypto):
        long_tx_hash = '0x' + 'a' * 66
        signatures = [
            {'signer': DEFAULT_MULTISIG_OWNERS[0], 'signature': TestUtils.generate_signature()},
            {'signer': DEFAULT_MULTISIG_OWNERS[1], 'signature': TestUtils.generate_signature()},
        ]

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.confirmLock(TestUtils.generate_id('cl'), long_tx_hash, signatures)

                assert 'Transaction hash cannot exceed' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_confirm_lock_no_signatures(self, factory, mock_prisma, mock_cache, mock_crypto):
        tx_hash = TestUtils.generate_transaction_hash()

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.confirmLock(TestUtils.generate_id('cl'), tx_hash, [])

                assert 'At least one signature is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_confirm_lock_too_many_signatures(self, factory, mock_prisma, mock_cache, mock_crypto):
        tx_hash = TestUtils.generate_transaction_hash()
        signatures = [
            {'signer': TestUtils.generate_eth_address(), 'signature': TestUtils.generate_signature()}
            for _ in range(101)
        ]

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.confirmLock(TestUtils.generate_id('cl'), tx_hash, signatures)

                assert 'Signature count cannot exceed' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_confirm_lock_empty_signer(self, factory, mock_prisma, mock_cache, mock_crypto):
        tx_hash = TestUtils.generate_transaction_hash()
        signatures = [
            {'signer': '', 'signature': TestUtils.generate_signature()},
        ]

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.confirmLock(TestUtils.generate_id('cl'), tx_hash, signatures)

                assert 'Signer address is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_confirm_lock_empty_signature_value(self, factory, mock_prisma, mock_cache, mock_crypto):
        tx_hash = TestUtils.generate_transaction_hash()
        signatures = [
            {'signer': DEFAULT_MULTISIG_OWNERS[0], 'signature': ''},
        ]

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.confirmLock(TestUtils.generate_id('cl'), tx_hash, signatures)

                assert 'Signature value is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_confirm_lock_signature_too_long(self, factory, mock_prisma, mock_cache, mock_crypto):
        tx_hash = TestUtils.generate_transaction_hash()
        long_signature = '0x' + 'a' * 132
        signatures = [
            {'signer': DEFAULT_MULTISIG_OWNERS[0], 'signature': long_signature},
        ]

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.confirmLock(TestUtils.generate_id('cl'), tx_hash, signatures)

                assert 'Signature cannot exceed' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_confirm_lock_invalid_signer_address(self, factory, mock_prisma, mock_cache, mock_crypto):
        tx_hash = TestUtils.generate_transaction_hash()
        signatures = [
            {'signer': 'invalid_address', 'signature': TestUtils.generate_signature()},
        ]

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.confirmLock(TestUtils.generate_id('cl'), tx_hash, signatures)

                assert 'Invalid signer address format' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_validate_message_empty_transfer_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        proof = {'blockNumber': 123456, 'transactionIndex': 5}

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.validateMessage('', proof)

                assert 'Transfer ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_validate_message_null_proof(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.validateMessage(TestUtils.generate_id('cl'), None)

                assert 'Cross-chain proof is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_execute_mint_empty_transfer_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.executeMint('')

                assert 'Transfer ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_confirm_transfer_empty_transfer_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.confirmTransfer('')

                assert 'Transfer ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_transfer_empty_transfer_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.getTransfer('')

                assert 'Transfer ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_transfers_invalid_source_chain(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.getTransfers({'sourceChainId': 0})

                assert 'Source chain ID must be positive' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_transfers_invalid_target_chain(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.getTransfers({'targetChainId': 0})

                assert 'Target chain ID must be positive' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_transfers_invalid_status(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.getTransfers({'status': 'INVALID_STATUS'})

                assert 'Invalid transfer status' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_transfers_invalid_source_address(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.getTransfers({'sourceAddress': 'invalid_address'})

                assert 'Invalid source address format' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_transfers_invalid_target_address(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.getTransfers({'targetAddress': 'invalid_address'})

                assert 'Invalid target address format' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_pending_transfers_zero_chain_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.getPendingTransfers(0)

                assert 'Valid chain ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_pending_transfers_negative_chain_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import ValidationError
                service = CrossChainBridgeService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.getPendingTransfers(-1)

                assert 'Valid chain ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_validate_signatures_handles_individual_failures(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_pending_transfer()
        tx_hash = TestUtils.generate_transaction_hash()
        signatures = [
            {'signer': DEFAULT_MULTISIG_OWNERS[0], 'signature': 'valid_sig_1'},
            {'signer': DEFAULT_MULTISIG_OWNERS[1], 'signature': 'invalid_sig'},
            {'signer': DEFAULT_MULTISIG_OWNERS[2], 'signature': 'valid_sig_2'},
        ]

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer
        mock_crypto.verifySignature.side_effect = [True, False, True]
        mock_prisma.crossChainTransfer.update.return_value = {**mock_transfer, 'status': 'LOCKED'}

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                service = CrossChainBridgeService()

                result = await service.confirmLock(mock_transfer['id'], tx_hash, signatures)

                assert result['transfer']['status'] == 'LOCKED'
                assert result['canExecute'] == True

    @pytest.mark.asyncio
    async def test_verify_proof_with_null_block_number(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_locked_transfer()
        proof = {'blockNumber': None, 'transactionIndex': 5}

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import BridgeError
                service = CrossChainBridgeService()

                with pytest.raises(BridgeError) as exc_info:
                    await service.validateMessage(mock_transfer['id'], proof)

                assert 'proof' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_verify_proof_with_nan_block_number(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_locked_transfer()
        proof = {'blockNumber': 'not_a_number', 'transactionIndex': 5}

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import BridgeError
                service = CrossChainBridgeService()

                with pytest.raises(BridgeError) as exc_info:
                    await service.validateMessage(mock_transfer['id'], proof)

                assert 'proof' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_verify_proof_with_negative_block_number(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_locked_transfer()
        proof = {'blockNumber': -1, 'transactionIndex': 5}

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import BridgeError
                service = CrossChainBridgeService()

                with pytest.raises(BridgeError) as exc_info:
                    await service.validateMessage(mock_transfer['id'], proof)

                assert 'proof' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_confirm_lock_database_error_handled(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_transfer = factory.create_pending_transfer()
        tx_hash = TestUtils.generate_transaction_hash()
        signatures = [
            {'signer': DEFAULT_MULTISIG_OWNERS[0], 'signature': TestUtils.generate_signature()},
            {'signer': DEFAULT_MULTISIG_OWNERS[1], 'signature': TestUtils.generate_signature()},
        ]

        mock_prisma.crossChainTransfer.findUnique.return_value = mock_transfer
        mock_crypto.verifySignature.return_value = True
        mock_prisma.crossChainTransfer.update.side_effect = Exception('Database connection failed')

        with patch('src.modules.cross-chain-bridge.crossChainBridge.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.cross-chain-bridge.crossChainBridge.service.CryptoUtils', mock_crypto):
                from src.modules.cross-chain-bridge.crossChainBridge.service import CrossChainBridgeService
                from src.utils.errors import BridgeError
                service = CrossChainBridgeService()

                with pytest.raises(BridgeError) as exc_info:
                    await service.confirmLock(mock_transfer['id'], tx_hash, signatures)

                assert 'update transfer status' in str(exc_info.value).lower()
