import pytest
from unittest.mock import MagicMock, patch, AsyncMock
from datetime import datetime

from tests.utils.factories import TestDataFactory
from tests.utils.test_utils import TestUtils
from tests.conftest import CHAIN_IDS


class TestAddressManagerService:

    @pytest.fixture
    def factory(self):
        return TestDataFactory()

    @pytest.fixture
    def mock_prisma(self):
        mock = MagicMock()
        mock.address = MagicMock()
        mock.address.findUnique = AsyncMock()
        mock.address.findMany = AsyncMock()
        mock.address.count = AsyncMock()
        mock.address.create = AsyncMock()
        mock.address.update = AsyncMock()
        mock.addressTag = MagicMock()
        mock.addressTag.findUnique = AsyncMock()
        mock.addressTag.create = AsyncMock()
        mock.addressTag.delete = AsyncMock()
        mock.addressTag.findMany = AsyncMock()
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
        mock.isValidAddress = MagicMock()
        mock.signMessage = MagicMock()
        mock.verifySignature = MagicMock()
        return mock

    @pytest.mark.asyncio
    async def test_create_address_success(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_address = factory.create_address()
        derived_addr = TestUtils.generate_eth_address()

        mock_crypto.deriveAddress.return_value = {
            'address': derived_addr,
            'path': "m/44'/60'/0'/0/0"
        }
        mock_prisma.address.findUnique.return_value = None
        mock_prisma.address.create.return_value = mock_address

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                with patch('src.modules.address-manager.addressManager.service.CryptoUtils', mock_crypto):
                    from src.modules.address-manager.addressManager.service import AddressManagerService
                    service = AddressManagerService()

                    request = {
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'label': 'Test Label',
                        'accountIndex': 0,
                        'addressIndex': 0,
                    }

                    result = await service.createAddress(request)

                    assert result['id'] == mock_address['id']
                    assert result['address'] == mock_address['address']
                    mock_prisma.address.create.assert_called_once()
                    mock_cache.set.assert_called_once()

    @pytest.mark.asyncio
    async def test_create_address_conflict(self, factory, mock_prisma, mock_cache, mock_crypto):
        existing_address = factory.create_address()
        derived_addr = TestUtils.generate_eth_address()

        mock_crypto.deriveAddress.return_value = {
            'address': derived_addr,
            'path': "m/44'/60'/0'/0/0"
        }
        mock_prisma.address.findUnique.return_value = existing_address

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                with patch('src.modules.address-manager.addressManager.service.CryptoUtils', mock_crypto):
                    from src.modules.address-manager.addressManager.service import AddressManagerService
                    from src.utils.errors import ConflictError
                    service = AddressManagerService()

                    request = {
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'label': 'Test Label',
                    }

                    with pytest.raises(ConflictError) as exc_info:
                        await service.createAddress(request)

                    assert 'already exists' in str(exc_info.value).lower()
                    mock_prisma.address.create.assert_not_called()

    @pytest.mark.asyncio
    async def test_create_address_different_chain(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_address = factory.create_address({'chainId': CHAIN_IDS['POLYGON']})
        derived_addr = TestUtils.generate_eth_address()

        mock_crypto.deriveAddress.return_value = {
            'address': derived_addr,
            'path': "m/44'/966'/0'/0/0"
        }
        mock_prisma.address.findUnique.return_value = None
        mock_prisma.address.create.return_value = mock_address

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                with patch('src.modules.address-manager.addressManager.service.CryptoUtils', mock_crypto):
                    from src.modules.address-manager.addressManager.service import AddressManagerService
                    service = AddressManagerService()

                    request = {
                        'chainId': CHAIN_IDS['POLYGON'],
                        'label': 'Polygon Wallet',
                    }

                    result = await service.createAddress(request)

                    assert result['chainId'] == CHAIN_IDS['POLYGON']
                    mock_crypto.deriveAddress.assert_called_with(CHAIN_IDS['POLYGON'], 0, 0)

    @pytest.mark.asyncio
    async def test_create_address_custom_indices(self, factory, mock_prisma, mock_cache, mock_crypto):
        mock_address = factory.create_address()
        derived_addr = TestUtils.generate_eth_address()

        mock_crypto.deriveAddress.return_value = {
            'address': derived_addr,
            'path': "m/44'/60'/5'/0/10"
        }
        mock_prisma.address.findUnique.return_value = None
        mock_prisma.address.create.return_value = mock_address

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                with patch('src.modules.address-manager.addressManager.service.CryptoUtils', mock_crypto):
                    from src.modules.address-manager.addressManager.service import AddressManagerService
                    service = AddressManagerService()

                    request = {
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'accountIndex': 5,
                        'addressIndex': 10,
                    }

                    await service.createAddress(request)

                    mock_crypto.deriveAddress.assert_called_with(CHAIN_IDS['ETHEREUM'], 5, 10)

    @pytest.mark.asyncio
    async def test_get_address_from_cache(self, factory, mock_prisma, mock_cache):
        mock_address = factory.create_address()
        mock_cache.get.return_value = mock_address

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                service = AddressManagerService()

                result = await service.getAddress(mock_address['id'])

                assert result == mock_address
                mock_prisma.address.findUnique.assert_not_called()

    @pytest.mark.asyncio
    async def test_get_address_not_found(self, factory, mock_prisma, mock_cache):
        mock_cache.get.return_value = None
        mock_prisma.address.findUnique.return_value = None

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                from src.utils.errors import NotFoundError
                service = AddressManagerService()

                non_existent_id = TestUtils.generate_id('cl')

                with pytest.raises(NotFoundError):
                    await service.getAddress(non_existent_id)

    @pytest.mark.asyncio
    async def test_get_address_with_tags(self, factory, mock_prisma, mock_cache):
        mock_address = factory.create_address_with_tags(tags=['cold-wallet', 'multi-sig'])
        mock_cache.get.return_value = None
        mock_prisma.address.findUnique.return_value = mock_address

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                service = AddressManagerService()

                result = await service.getAddress(mock_address['id'])

                assert 'cold-wallet' in result['tags']
                assert 'multi-sig' in result['tags']

    @pytest.mark.asyncio
    async def test_get_addresses_paginated(self, factory, mock_prisma, mock_cache):
        addresses = factory.create_address_list(5)
        mock_prisma.address.count.return_value = 5
        mock_prisma.address.findMany.return_value = addresses

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                service = AddressManagerService()

                result = await service.getAddresses({'page': 1, 'pageSize': 10})

                assert result['total'] == 5
                assert len(result['items']) == 5

    @pytest.mark.asyncio
    async def test_get_addresses_filter_by_chain(self, factory, mock_prisma, mock_cache):
        addresses = factory.create_address_list(3, {'chainId': CHAIN_IDS['BSC']})
        mock_prisma.address.count.return_value = 3
        mock_prisma.address.findMany.return_value = addresses

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                service = AddressManagerService()

                await service.getAddresses({'chainId': CHAIN_IDS['BSC']})

                mock_prisma.address.count.assert_called_with({
                    'where': {'chainId': CHAIN_IDS['BSC']}
                })

    @pytest.mark.asyncio
    async def test_get_addresses_empty(self, factory, mock_prisma, mock_cache):
        mock_prisma.address.count.return_value = 0
        mock_prisma.address.findMany.return_value = []

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                service = AddressManagerService()

                result = await service.getAddresses({'chainId': 99999})

                assert result['total'] == 0
                assert result['items'] == []

    @pytest.mark.asyncio
    async def test_update_address_success(self, factory, mock_prisma, mock_cache):
        original = factory.create_address({'label': 'Old Label', 'isActive': True})
        updated = {**original, 'label': 'New Label', 'isActive': False, 'AddressTag': []}

        mock_prisma.address.findUnique.return_value = original
        mock_prisma.address.update.return_value = updated

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                service = AddressManagerService()

                result = await service.updateAddress(
                    original['id'],
                    {'label': 'New Label', 'isActive': False}
                )

                assert result['label'] == 'New Label'
                assert result['isActive'] == False
                assert mock_cache.delete.call_count == 2

    @pytest.mark.asyncio
    async def test_update_address_not_found(self, factory, mock_prisma, mock_cache):
        mock_prisma.address.findUnique.return_value = None

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                from src.utils.errors import NotFoundError
                service = AddressManagerService()

                non_existent_id = TestUtils.generate_id('cl')

                with pytest.raises(NotFoundError):
                    await service.updateAddress(non_existent_id, {'label': 'Test'})

    @pytest.mark.asyncio
    async def test_add_tag_success(self, factory, mock_prisma, mock_cache):
        mock_address = factory.create_address()
        updated_address = {
            **mock_address,
            'AddressTag': [{'tag': 'cold-storage'}],
        }

        mock_prisma.address.findUnique.side_effect = [mock_address, updated_address]
        mock_prisma.addressTag.findUnique.return_value = None
        mock_prisma.addressTag.create.return_value = {'id': TestUtils.generate_id('cl'), 'addressId': mock_address['id'], 'tag': 'cold-storage'}

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                service = AddressManagerService()

                result = await service.addTag(mock_address['id'], 'cold-storage')

                assert 'cold-storage' in result['tags']
                mock_prisma.addressTag.create.assert_called_once()

    @pytest.mark.asyncio
    async def test_add_tag_conflict(self, factory, mock_prisma, mock_cache):
        mock_address = factory.create_address()
        existing_tag = {'id': TestUtils.generate_id('cl'), 'addressId': mock_address['id'], 'tag': 'existing'}

        mock_prisma.address.findUnique.return_value = mock_address
        mock_prisma.addressTag.findUnique.return_value = existing_tag

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                from src.utils.errors import ConflictError
                service = AddressManagerService()

                with pytest.raises(ConflictError):
                    await service.addTag(mock_address['id'], 'existing')

    @pytest.mark.asyncio
    async def test_remove_tag_success(self, factory, mock_prisma, mock_cache):
        mock_address = factory.create_address_with_tags(tags=['keep-tag'])
        existing_tag = {'id': TestUtils.generate_id('cl'), 'addressId': mock_address['id'], 'tag': 'remove-tag'}

        mock_prisma.addressTag.findUnique.return_value = existing_tag
        mock_prisma.addressTag.delete.return_value = existing_tag
        mock_prisma.address.findUnique.return_value = mock_address

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                service = AddressManagerService()

                result = await service.removeTag(mock_address['id'], 'remove-tag')

                assert 'keep-tag' in result['tags']
                mock_prisma.addressTag.delete.assert_called_once()

    @pytest.mark.asyncio
    async def test_remove_tag_not_found(self, factory, mock_prisma, mock_cache):
        mock_prisma.addressTag.findUnique.return_value = None

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                from src.utils.errors import NotFoundError
                service = AddressManagerService()

                with pytest.raises(NotFoundError):
                    await service.removeTag(TestUtils.generate_id('cl'), 'non-existent')

    @pytest.mark.asyncio
    async def test_list_by_tag(self, factory, mock_prisma, mock_cache):
        addresses = factory.create_address_list(3)
        tags = [
            {
                'id': TestUtils.generate_id('cl'),
                'addressId': addr['id'],
                'tag': 'multi-sig',
                'address': {**addr, 'AddressTag': []},
            }
            for addr in addresses
        ]

        mock_prisma.addressTag.findMany.return_value = tags

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                service = AddressManagerService()

                result = await service.listByTag('multi-sig')

                assert len(result) == 3

    @pytest.mark.asyncio
    async def test_create_address_chain_id_zero(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                with patch('src.modules.address-manager.addressManager.service.CryptoUtils', mock_crypto):
                    from src.modules.address-manager.addressManager.service import AddressManagerService
                    from src.utils.errors import ValidationError
                    service = AddressManagerService()

                    request = {
                        'chainId': 0,
                        'label': 'Test Label',
                    }

                    with pytest.raises(ValidationError) as exc_info:
                        await service.createAddress(request)

                    assert 'Chain ID must be positive' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_create_address_chain_id_negative(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                with patch('src.modules.address-manager.addressManager.service.CryptoUtils', mock_crypto):
                    from src.modules.address-manager.addressManager.service import AddressManagerService
                    from src.utils.errors import ValidationError
                    service = AddressManagerService()

                    request = {
                        'chainId': -1,
                        'label': 'Test Label',
                    }

                    with pytest.raises(ValidationError) as exc_info:
                        await service.createAddress(request)

                    assert 'Chain ID must be positive' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_create_address_account_index_negative(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                with patch('src.modules.address-manager.addressManager.service.CryptoUtils', mock_crypto):
                    from src.modules.address-manager.addressManager.service import AddressManagerService
                    from src.utils.errors import ValidationError
                    service = AddressManagerService()

                    request = {
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'accountIndex': -1,
                    }

                    with pytest.raises(ValidationError) as exc_info:
                        await service.createAddress(request)

                    assert 'Account index must be between' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_create_address_address_index_negative(self, factory, mock_prisma, mock_cache, mock_crypto):
        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                with patch('src.modules.address-manager.addressManager.service.CryptoUtils', mock_crypto):
                    from src.modules.address-manager.addressManager.service import AddressManagerService
                    from src.utils.errors import ValidationError
                    service = AddressManagerService()

                    request = {
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'addressIndex': -1,
                    }

                    with pytest.raises(ValidationError) as exc_info:
                        await service.createAddress(request)

                    assert 'Address index must be between' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_create_address_label_too_long(self, factory, mock_prisma, mock_cache, mock_crypto):
        long_label = 'a' * 256

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                with patch('src.modules.address-manager.addressManager.service.CryptoUtils', mock_crypto):
                    from src.modules.address-manager.addressManager.service import AddressManagerService
                    from src.utils.errors import ValidationError
                    service = AddressManagerService()

                    request = {
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'label': long_label,
                    }

                    with pytest.raises(ValidationError) as exc_info:
                        await service.createAddress(request)

                    assert 'Label cannot exceed' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_add_tag_empty_address_id(self, factory, mock_prisma, mock_cache):
        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                from src.utils.errors import ValidationError
                service = AddressManagerService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.addTag('', 'test-tag')

                assert 'Address ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_add_tag_whitespace_address_id(self, factory, mock_prisma, mock_cache):
        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                from src.utils.errors import ValidationError
                service = AddressManagerService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.addTag('   ', 'test-tag')

                assert 'Address ID is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_add_tag_empty_tag(self, factory, mock_prisma, mock_cache):
        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                from src.utils.errors import ValidationError
                service = AddressManagerService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.addTag(TestUtils.generate_id('cl'), '')

                assert 'Tag is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_add_tag_whitespace_tag(self, factory, mock_prisma, mock_cache):
        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                from src.utils.errors import ValidationError
                service = AddressManagerService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.addTag(TestUtils.generate_id('cl'), '   ')

                assert 'Tag is required' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_add_tag_tag_too_long(self, factory, mock_prisma, mock_cache):
        long_tag = 'a' * 101

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                from src.utils.errors import ValidationError
                service = AddressManagerService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.addTag(TestUtils.generate_id('cl'), long_tag)

                assert 'Tag cannot exceed' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_addresses_invalid_chain_id(self, factory, mock_prisma, mock_cache):
        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                from src.utils.errors import ValidationError
                service = AddressManagerService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.getAddresses({'chainId': 0})

                assert 'Chain ID must be positive' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_get_addresses_label_too_long(self, factory, mock_prisma, mock_cache):
        long_label = 'a' * 256

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                from src.utils.errors import ValidationError
                service = AddressManagerService()

                with pytest.raises(ValidationError) as exc_info:
                    await service.getAddresses({'label': long_label})

                assert 'Label search cannot exceed' in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_create_address_concurrency_uses_unique_constraint(self, factory, mock_prisma, mock_cache, mock_crypto):
        derived_addr = TestUtils.generate_eth_address()

        class MockUniqueError(Exception):
            code = 'P2002'

        mock_crypto.deriveAddress.return_value = {
            'address': derived_addr,
            'path': "m/44'/60'/0'/0/0"
        }
        mock_prisma.address.create.side_effect = MockUniqueError()

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                with patch('src.modules.address-manager.addressManager.service.CryptoUtils', mock_crypto):
                    from src.modules.address-manager.addressManager.service import AddressManagerService
                    from src.utils.errors import ConflictError
                    service = AddressManagerService()

                    request = {
                        'chainId': CHAIN_IDS['ETHEREUM'],
                        'label': 'Test Label',
                    }

                    with pytest.raises(ConflictError) as exc_info:
                        await service.createAddress(request)

                    assert 'already exists' in str(exc_info.value).lower()

    @pytest.mark.asyncio
    async def test_add_tag_concurrency_uses_unique_constraint(self, factory, mock_prisma, mock_cache):
        mock_address = factory.create_address()

        class MockUniqueError(Exception):
            code = 'P2002'

        mock_prisma.address.findUnique.return_value = mock_address
        mock_prisma.addressTag.create.side_effect = MockUniqueError()

        with patch('src.modules.address-manager.addressManager.service.getPrismaClient', return_value=mock_prisma):
            with patch('src.modules.address-manager.addressManager.service.cacheService', mock_cache):
                from src.modules.address-manager.addressManager.service import AddressManagerService
                from src.utils.errors import ConflictError
                service = AddressManagerService()

                with pytest.raises(ConflictError) as exc_info:
                    await service.addTag(mock_address['id'], 'existing-tag')

                assert 'already exists' in str(exc_info.value).lower()
