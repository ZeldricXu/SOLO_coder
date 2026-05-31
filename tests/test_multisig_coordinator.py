import pytest
from unittest.mock import MagicMock, patch, AsyncMock
from datetime import datetime

from tests.utils.factories import TestDataFactory
from tests.utils.test_utils import TestUtils
from tests.conftest import CHAIN_IDS, DEFAULT_MULTISIG_OWNERS, DEFAULT_THRESHOLD


class TestMultisigCoordinatorService:

    @pytest.fixture
    def factory(self):
        return TestDataFactory()

    @pytest.fixture
    def mock_prisma(self):
        mock = MagicMock()
        mock.multisigProposal = MagicMock()
        mock.multisigProposal.findUnique = AsyncMock()
        mock.multisigProposal.findMany = AsyncMock()
        mock.multisigProposal.count = AsyncMock()
        mock.multisigProposal.create = AsyncMock()
        mock.multisigProposal.update = AsyncMock()
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
    async def test_create_proposal_success(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_proposal = factory.create_pending_proposal()
        to_addr = TestUtils.generate_eth_address()

        mock_prisma.multisigProposal.findMany.return_value = []
        mock_prisma.multisigProposal.create.return_value = mock_proposal

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    request = {
                        'walletId': mock_proposal['walletId'],
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'type': 'TRANSFER',
                        'data': {
                            'to': to_addr,
                            'value': '1000000000000000000',
                            'data': '0x',
                            'operation': 0,
                        },
                    }

                    result = await service.createProposal(request)

                    assert result['id'] == mock_proposal['id']
                    assert result['status'] == 'PENDING'
                    assert result['nonce'] == 0
                    mock_prisma.multisigProposal.create.assert_called_once()

    @pytest.mark.asyncio
    async def test_create_proposal_increment_nonce(self, factory, mock_prisma, mock_cache, mock_crypto):
        last_proposal = factory.create_pending_proposal({'nonce': 5})
        new_proposal = factory.create_pending_proposal({'nonce': 6})
        to_addr = TestUtils.generate_eth_address()

        mock_prisma.multisigProposal.findMany.return_value = [last_proposal]
        mock_prisma.multisigProposal.create.return_value = new_proposal

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    request = {
                        'walletId': last_proposal['walletId'],
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'type': 'TRANSFER',
                        'data': {
                            'to': to_addr,
                            'value': '1000000000000000000',
                        },
                    }

                    await service.createProposal(request)

                    call_kwargs = mock_prisma.multisigProposal.create.call_args
                    assert call_kwargs[1]['data']['nonce'] == 6

    @pytest.mark.asyncio
    async def test_create_proposal_missing_wallet_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        to_addr = TestUtils.generate_eth_address()

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    request = {
                        'walletId': '',
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'type': 'TRANSFER',
                        'data': {
                            'to': to_addr,
                            'value': '1000000000000000000',
                        },
                    }

                    with pytest.raises(ValidationError):
                        await service.createProposal(request)

    @pytest.mark.asyncio
    async def test_create_proposal_invalid_chain_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        to_addr = TestUtils.generate_eth_address()

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    request = {
                        'walletId': TestUtils.generate_id('wallet'),
                        'chainId': 0,
                        'type': 'TRANSFER',
                        'data': {
                            'to': to_addr,
                            'value': '1000000000000000000',
                        },
                    }

                    with pytest.raises(ValidationError):
                        await service.createProposal(request)

    @pytest.mark.asyncio
    async def test_create_proposal_invalid_recipient(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    request = {
                        'walletId': TestUtils.generate_id('wallet'),
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'type': 'TRANSFER',
                        'data': {
                            'to': 'invalid_address',
                            'value': '1000000000000000000',
                        },
                    }

                    with pytest.raises(ValidationError):
                        await service.createProposal(request)

    @pytest.mark.asyncio
    async def test_sign_proposal_success(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_proposal = factory.create_pending_proposal()
        signer = DEFAULT_MULTISIG_OWNERS[0]
        signature = TestUtils.generate_signature()

        mock_prisma.multisigProposal.findUnique.return_value = mock_proposal
        mock_crypto.verifySignature.return_value = True
        mock_prisma.multisigProposal.update.return_value = {
            **mock_proposal,
            'signatures': [{'signer': signer, 'signature': signature, 'timestamp': datetime.now().timestamp() * 1000}],
        }

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    request = {
                        'proposalId': mock_proposal['id'],
                        'signer': signer,
                        'signature': signature,
                    }

                    result = await service.signProposal(request)

                    assert len(result['signatures']) == 1
                    assert result['status'] == 'PENDING'

    @pytest.mark.asyncio
    async def test_sign_proposal_threshold_met(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_proposal = factory.create_partially_signed_proposal({}, 1)
        signer = DEFAULT_MULTISIG_OWNERS[1]
        signature = TestUtils.generate_signature()

        mock_prisma.multisigProposal.findUnique.return_value = mock_proposal
        mock_crypto.verifySignature.return_value = True
        mock_prisma.multisigProposal.update.return_value = {
            **mock_proposal,
            'status': 'APPROVED',
            'signatures': [
                *mock_proposal['signatures'],
                {'signer': signer, 'signature': signature, 'timestamp': datetime.now().timestamp() * 1000},
            ],
        }

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    request = {
                        'proposalId': mock_proposal['id'],
                        'signer': signer,
                        'signature': signature,
                    }

                    result = await service.signProposal(request)

                    assert result['status'] == 'APPROVED'
                    assert len(result['signatures']) == 2

    @pytest.mark.asyncio
    async def test_sign_proposal_not_found(self, factory, mock_prisma, mock_cache, mock_crypto):
        signer = DEFAULT_MULTISIG_OWNERS[0]
        signature = TestUtils.generate_signature()

        mock_prisma.multisigProposal.findUnique.return_value = None

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import NotFoundError
                    service = MultisigCoordinatorService()

                    request = {
                        'proposalId': TestUtils.generate_id('cl'),
                        'signer': signer,
                        'signature': signature,
                    }

                    with pytest.raises(NotFoundError):
                        await service.signProposal(request)

    @pytest.mark.asyncio
    async def test_sign_proposal_already_signed(self, factory, mock_prisma, mock_cache, mock_crypto):
        existing_signer = DEFAULT_MULTISIG_OWNERS[0]
        mock_proposal = factory.create_partially_signed_proposal({}, 1)
        signature = TestUtils.generate_signature()

        mock_prisma.multisigProposal.findUnique.return_value = mock_proposal

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    request = {
                        'proposalId': mock_proposal['id'],
                        'signer': existing_signer,
                        'signature': signature,
                    }

                    with pytest.raises(Exception) as exc_info:
                        await service.signProposal(request)

                    assert 'already signed' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_sign_proposal_unauthorized_signer(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_proposal = factory.create_pending_proposal()
        unauthorized_signer = TestUtils.generate_eth_address()
        signature = TestUtils.generate_signature()

        mock_prisma.multisigProposal.findUnique.return_value = mock_proposal

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    request = {
                        'proposalId': mock_proposal['id'],
                        'signer': unauthorized_signer,
                        'signature': signature,
                    }

                    with pytest.raises(Exception) as exc_info:
                        await service.signProposal(request)

                    assert 'authorized' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_sign_proposal_invalid_signature(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_proposal = factory.create_pending_proposal()
        signer = DEFAULT_MULTISIG_OWNERS[0]
        signature = TestUtils.generate_signature()

        mock_prisma.multisigProposal.findUnique.return_value = mock_proposal
        mock_crypto.verifySignature.return_value = False

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    request = {
                        'proposalId': mock_proposal['id'],
                        'signer': signer,
                        'signature': signature,
                    }

                    with pytest.raises(ValidationError):
                        await service.signProposal(request)

    @pytest.mark.asyncio
    async def test_execute_proposal_success(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_proposal = factory.create_approved_proposal()

        mock_prisma.multisigProposal.findUnique.return_value = mock_proposal
        mock_prisma.multisigProposal.update.return_value = {
            **mock_proposal,
            'status': 'EXECUTED',
            'executedTxHash': TestUtils.generate_transaction_hash(),
        }

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    result = await service.executeProposal({'proposalId': mock_proposal['id']})

                    assert result['proposal']['status'] == 'EXECUTED'
                    assert result['txHash'] is not None

    @pytest.mark.asyncio
    async def test_execute_proposal_not_approved(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_proposal = factory.create_pending_proposal()

        mock_prisma.multisigProposal.findUnique.return_value = mock_proposal

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    with pytest.raises(Exception) as exc_info:
                        await service.executeProposal({'proposalId': mock_proposal['id']})

                    assert 'approved' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_reject_proposal_from_pending(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_proposal = factory.create_pending_proposal()

        mock_prisma.multisigProposal.findUnique.return_value = mock_proposal
        mock_prisma.multisigProposal.update.return_value = {**mock_proposal, 'status': 'REJECTED'}

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    result = await service.rejectProposal(mock_proposal['id'])

                    assert result['status'] == 'REJECTED'

    @pytest.mark.asyncio
    async def test_reject_proposal_from_approved(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_proposal = factory.create_approved_proposal()

        mock_prisma.multisigProposal.findUnique.return_value = mock_proposal
        mock_prisma.multisigProposal.update.return_value = {**mock_proposal, 'status': 'REJECTED'}

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    result = await service.rejectProposal(mock_proposal['id'])

                    assert result['status'] == 'REJECTED'

    @pytest.mark.asyncio
    async def test_reject_proposal_from_executed_error(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_proposal = factory.create_executed_proposal()

        mock_prisma.multisigProposal.findUnique.return_value = mock_proposal

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    with pytest.raises(Exception) as exc_info:
                        await service.rejectProposal(mock_proposal['id'])

                    assert 'rejected' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_state_transition_pending_to_approved(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_proposal = factory.create_partially_signed_proposal({}, 1)
        signer = DEFAULT_MULTISIG_OWNERS[1]
        signature = TestUtils.generate_signature()

        mock_prisma.multisigProposal.findUnique.return_value = mock_proposal
        mock_crypto.verifySignature.return_value = True
        mock_prisma.multisigProposal.update.return_value = {
            **mock_proposal,
            'status': 'APPROVED',
            'signatures': [
                *mock_proposal['signatures'],
                {'signer': signer, 'signature': signature, 'timestamp': datetime.now().timestamp() * 1000},
            ],
        }

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    result = await service.signProposal({
                        'proposalId': mock_proposal['id'],
                        'signer': signer,
                        'signature': signature,
                    })

                    assert result['status'] == 'APPROVED'

    @pytest.mark.asyncio
    async def test_state_transition_approved_to_executed(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_proposal = factory.create_approved_proposal()

        mock_prisma.multisigProposal.findUnique.return_value = mock_proposal
        mock_prisma.multisigProposal.update.return_value = {
            **mock_proposal,
            'status': 'EXECUTED',
            'executedTxHash': TestUtils.generate_transaction_hash(),
        }

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    result = await service.executeProposal({'proposalId': mock_proposal['id']})

                    assert result['proposal']['status'] == 'EXECUTED'

    @pytest.mark.asyncio
    async def test_can_execute_true(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_proposal = factory.create_approved_proposal()

        mock_prisma.multisigProposal.findUnique.return_value = mock_proposal

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    result = await service.canExecute(mock_proposal['id'])

                    assert result == True

    @pytest.mark.asyncio
    async def test_can_execute_false(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_proposal = factory.create_partially_signed_proposal({}, 1)

        mock_prisma.multisigProposal.findUnique.return_value = mock_proposal

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    result = await service.canExecute(mock_proposal['id'])

                    assert result == False

    @pytest.mark.asyncio
    async def test_get_proposals_with_filters(self, factory, mock_prisma, mock_cache, mock_crypto):
        proposals = factory.create_proposal_list(5)

        mock_prisma.multisigProposal.count.return_value = 5
        mock_prisma.multisigProposal.findMany.return_value = proposals

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    result = await service.getProposals({
                        'walletId': TestUtils.generate_id('wallet'),
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'status': 'PENDING',
                    })

                    assert result['total'] == 5
                    assert len(result['items']) == 5

    @pytest.mark.asyncio
    async def test_get_pending_proposals(self, factory, mock_prisma, mock_cache, mock_crypto):
        wallet_id = TestUtils.generate_id('wallet')
        proposals = factory.create_proposal_list(3, {'walletId': wallet_id, 'status': 'PENDING'})

        mock_prisma.multisigProposal.findMany.return_value = proposals

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    result = await service.getPendingProposals(wallet_id)

                    assert len(result) == 3

    @pytest.mark.asyncio
    async def test_create_proposal_wallet_id_too_long(self, factory, mock_prisma, mock_cache, mock_crypto):
        to_addr = TestUtils.generate_eth_address()
        long_wallet_id = 'a' * 101

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    request = {
                        'walletId': long_wallet_id,
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'type': 'TRANSFER',
                        'data': {
                            'to': to_addr,
                            'value': '1000000000000000000',
                            'data': '0x',
                            'operation': 0,
                        },
                    }

                    with pytest.raises(ValidationError) as exc_info:
                        await service.createProposal(request)

                    assert 'Wallet ID cannot exceed' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_create_proposal_invalid_type(self, factory, mock_prisma, mock_cache, mock_crypto):
        to_addr = TestUtils.generate_eth_address()

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    request = {
                        'walletId': TestUtils.generate_id('wallet'),
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'type': 'INVALID_TYPE',
                        'data': {
                            'to': to_addr,
                            'value': '1000000000000000000',
                            'data': '0x',
                            'operation': 0,
                        },
                    }

                    with pytest.raises(ValidationError) as exc_info:
                        await service.createProposal(request)

                    assert 'Invalid proposal type' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_create_proposal_value_too_long(self, factory, mock_prisma, mock_cache, mock_crypto):
        to_addr = TestUtils.generate_eth_address()
        long_value = '9' * 79

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    request = {
                        'walletId': TestUtils.generate_id('wallet'),
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'type': 'TRANSFER',
                        'data': {
                            'to': to_addr,
                            'value': long_value,
                            'data': '0x',
                            'operation': 0,
                        },
                    }

                    with pytest.raises(ValidationError) as exc_info:
                        await service.createProposal(request)

                    assert 'Value cannot exceed' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_create_proposal_invalid_operation(self, factory, mock_prisma, mock_cache, mock_crypto):
        to_addr = TestUtils.generate_eth_address()

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    request = {
                        'walletId': TestUtils.generate_id('wallet'),
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'type': 'TRANSFER',
                        'data': {
                            'to': to_addr,
                            'value': '1000000000000000000',
                            'data': '0x',
                            'operation': 2,
                        },
                    }

                    with pytest.raises(ValidationError) as exc_info:
                        await service.createProposal(request)

                    assert 'Operation must be' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_create_proposal_invalid_value_format(self, factory, mock_prisma, mock_cache, mock_crypto):
        to_addr = TestUtils.generate_eth_address()

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    request = {
                        'walletId': TestUtils.generate_id('wallet'),
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'type': 'TRANSFER',
                        'data': {
                            'to': to_addr,
                            'value': 'abc123',
                            'data': '0x',
                            'operation': 0,
                        },
                    }

                    with pytest.raises(ValidationError) as exc_info:
                        await service.createProposal(request)

                    assert 'non-negative integer' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_sign_proposal_empty_proposal_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        signer = DEFAULT_MULTISIG_OWNERS[0]
        signature = TestUtils.generate_signature()

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    request = {
                        'proposalId': '',
                        'signer': signer,
                        'signature': signature,
                    }

                    with pytest.raises(ValidationError) as exc_info:
                        await service.signProposal(request)

                    assert 'Proposal ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_sign_proposal_empty_signer(self, factory, mock_prisma, mock_cache, mock_crypto):
        signature = TestUtils.generate_signature()

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    request = {
                        'proposalId': TestUtils.generate_id('cl'),
                        'signer': '   ',
                        'signature': signature,
                    }

                    with pytest.raises(ValidationError) as exc_info:
                        await service.signProposal(request)

                    assert 'Signer address is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_sign_proposal_empty_signature(self, factory, mock_prisma, mock_cache, mock_crypto):
        signer = DEFAULT_MULTISIG_OWNERS[0]

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    request = {
                        'proposalId': TestUtils.generate_id('cl'),
                        'signer': signer,
                        'signature': '',
                    }

                    with pytest.raises(ValidationError) as exc_info:
                        await service.signProposal(request)

                    assert 'Signature is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_sign_proposal_signature_too_long(self, factory, mock_prisma, mock_cache, mock_crypto):
        signer = DEFAULT_MULTISIG_OWNERS[0]
        long_signature = '0x' + 'a' * 132

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    request = {
                        'proposalId': TestUtils.generate_id('cl'),
                        'signer': signer,
                        'signature': long_signature,
                    }

                    with pytest.raises(ValidationError) as exc_info:
                        await service.signProposal(request)

                    assert 'Signature cannot exceed' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_execute_proposal_empty_proposal_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    with pytest.raises(ValidationError) as exc_info:
                        await service.executeProposal({'proposalId': ''})

                    assert 'Proposal ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_proposal_empty_proposal_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    with pytest.raises(ValidationError) as exc_info:
                        await service.getProposal('')

                    assert 'Proposal ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_reject_proposal_empty_proposal_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    with pytest.raises(ValidationError) as exc_info:
                        await service.rejectProposal('')

                    assert 'Proposal ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_proposal_signatures_empty_proposal_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    with pytest.raises(ValidationError) as exc_info:
                        await service.getProposalSignatures('')

                    assert 'Proposal ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_can_execute_empty_proposal_id_returns_false(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    service = MultisigCoordinatorService()

                    result = await service.canExecute('')

                    assert result == False

    @pytest.mark.asyncio
    async def test_get_proposals_invalid_status(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    with pytest.raises(ValidationError) as exc_info:
                        await service.getProposals({'status': 'INVALID_STATUS'})

                    assert 'Invalid proposal status' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_proposals_invalid_type(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    with pytest.raises(ValidationError) as exc_info:
                        await service.getProposals({'type': 'INVALID_TYPE'})

                    assert 'Invalid proposal type' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_proposals_invalid_chain_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    with pytest.raises(ValidationError) as exc_info:
                        await service.getProposals({'chainId': 0})

                    assert 'Chain ID must be positive' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_proposals_wallet_id_too_long(self, factory, mock_prisma, mock_cache, mock_crypto):
        long_wallet_id = 'a' * 101

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    with pytest.raises(ValidationError) as exc_info:
                        await service.getProposals({'walletId': long_wallet_id})

                    assert 'Wallet ID cannot exceed' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_pending_proposals_empty_wallet_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    with pytest.raises(ValidationError) as exc_info:
                        await service.getPendingProposals('')

                    assert 'Wallet ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_pending_proposals_wallet_id_too_long(self, factory, mock_prisma, mock_cache, mock_crypto):
        long_wallet_id = 'a' * 101

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    with pytest.raises(ValidationError) as exc_info:
                        await service.getPendingProposals(long_wallet_id)

                    assert 'Wallet ID cannot exceed' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_approved_proposals_empty_wallet_id(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    with pytest.raises(ValidationError) as exc_info:
                        await service.getApprovedProposals('')

                    assert 'Wallet ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_approved_proposals_wallet_id_too_long(self, factory, mock_prisma, mock_cache, mock_crypto):
        long_wallet_id = 'a' * 101

        with patch('src.modules.multisig-coordinator.multisigCoordinator.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.multisig-coordinator.multisigCoordinator.service.cacheService', mock_cache):
                with patch('src.modules.multisig-coordinator.multisigCoordinator.service.CryptoUtils', mock_crypto):
                    from src.modules.multisig-coordinator.multisigCoordinator.service import MultisigCoordinatorService
                    from src.utils.errors import ValidationError
                    service = MultisigCoordinatorService()

                    with pytest.raises(ValidationError) as exc_info:
                        await service.getApprovedProposals(long_wallet_id)

                    assert 'Wallet ID cannot exceed' in str(exc_info.value)
