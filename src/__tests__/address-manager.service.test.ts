import { AddressManagerService } from '../../modules/address-manager/addressManager.service';
import { testDataFactory } from '../factories';
import { generateAddress, CHAIN_IDS, generateCuid } from '../test-utils';
import { NotFoundError, ConflictError, ValidationError } from '../../utils/errors';

jest.mock('../../utils/database', () => ({
  getPrismaClient: jest.fn(),
}));

jest.mock('../../utils/cache', () => ({
  cacheService: {
    get: jest.fn(),
    set: jest.fn(),
    delete: jest.fn(),
  },
}));

jest.mock('../../utils/crypto', () => ({
  CryptoUtils: {
    deriveAddress: jest.fn(),
    isValidAddress: jest.fn(),
    signMessage: jest.fn(),
    verifySignature: jest.fn(),
  },
}));

describe('AddressManagerService', () => {
  let service: AddressManagerService;
  let mockPrisma: any;
  let mockCache: any;
  let mockCrypto: any;

  beforeEach(() => {
    mockPrisma = {
      address: {
        findUnique: jest.fn(),
        findMany: jest.fn(),
        count: jest.fn(),
        create: jest.fn(),
        update: jest.fn(),
      },
      addressTag: {
        findUnique: jest.fn(),
        create: jest.fn(),
        delete: jest.fn(),
        findMany: jest.fn(),
      },
    };

    require('../../utils/database').getPrismaClient.mockReturnValue(mockPrisma);
    mockCache = require('../../utils/cache').cacheService;
    mockCrypto = require('../../utils/crypto').CryptoUtils;

    service = new AddressManagerService();
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  describe('createAddress', () => {
    it('should create address successfully when no conflict exists', async () => {
      const mockAddress = testDataFactory.createAddress();
      const derivedAddress = generateAddress();

      mockCrypto.deriveAddress.mockReturnValue({
        address: derivedAddress,
        path: "m/44'/60'/0'/0/0",
      });
      mockPrisma.address.findUnique.mockResolvedValue(null);
      mockPrisma.address.create.mockResolvedValue(mockAddress);
      mockCache.set.mockResolvedValue(true);

      const request = {
        chainId: CHAIN_IDS.ETHEREUM,
        label: 'Test Label',
        accountIndex: 0,
        addressIndex: 0,
      };

      const result = await service.createAddress(request);

      expect(result.id).toBe(mockAddress.id);
      expect(result.address).toBe(mockAddress.address);
      expect(mockPrisma.address.create).toHaveBeenCalledWith({
        data: {
          address: derivedAddress,
          chainId: request.chainId,
          derivationPath: "m/44'/60'/0'/0/0",
          walletType: 'hd',
          label: request.label,
        },
      });
      expect(mockCache.set).toHaveBeenCalled();
    });

    it('should throw ConflictError when address already exists', async () => {
      const existingAddress = testDataFactory.createAddress();

      mockCrypto.deriveAddress.mockReturnValue({
        address: existingAddress.address,
        path: existingAddress.derivationPath,
      });
      mockPrisma.address.findUnique.mockResolvedValue(existingAddress);

      const request = {
        chainId: CHAIN_IDS.ETHEREUM,
        accountIndex: 0,
        addressIndex: 0,
      };

      await expect(service.createAddress(request)).rejects.toThrow(ConflictError);
      expect(mockPrisma.address.create).not.toHaveBeenCalled();
    });

    it('should handle different chain IDs correctly', async () => {
      const mockAddress = testDataFactory.createAddress({ chainId: CHAIN_IDS.POLYGON });
      const derivedAddress = generateAddress();

      mockCrypto.deriveAddress.mockReturnValue({
        address: derivedAddress,
        path: "m/44'/966'/0'/0/0",
      });
      mockPrisma.address.findUnique.mockResolvedValue(null);
      mockPrisma.address.create.mockResolvedValue(mockAddress);
      mockCache.set.mockResolvedValue(true);

      const request = {
        chainId: CHAIN_IDS.POLYGON,
        label: 'Polygon Wallet',
      };

      const result = await service.createAddress(request);

      expect(mockCrypto.deriveAddress).toHaveBeenCalledWith(CHAIN_IDS.POLYGON, 0, 0);
      expect(result.chainId).toBe(CHAIN_IDS.POLYGON);
    });

    it('should use default indices when not provided', async () => {
      const mockAddress = testDataFactory.createAddress();
      const derivedAddress = generateAddress();

      mockCrypto.deriveAddress.mockReturnValue({
        address: derivedAddress,
        path: "m/44'/60'/0'/0/0",
      });
      mockPrisma.address.findUnique.mockResolvedValue(null);
      mockPrisma.address.create.mockResolvedValue(mockAddress);
      mockCache.set.mockResolvedValue(true);

      const request = {
        chainId: CHAIN_IDS.ETHEREUM,
      };

      await service.createAddress(request);

      expect(mockCrypto.deriveAddress).toHaveBeenCalledWith(CHAIN_IDS.ETHEREUM, 0, 0);
    });

    it('should allow custom indices', async () => {
      const mockAddress = testDataFactory.createAddress();
      const derivedAddress = generateAddress();

      mockCrypto.deriveAddress.mockReturnValue({
        address: derivedAddress,
        path: "m/44'/60'/5'/0/10",
      });
      mockPrisma.address.findUnique.mockResolvedValue(null);
      mockPrisma.address.create.mockResolvedValue(mockAddress);
      mockCache.set.mockResolvedValue(true);

      const request = {
        chainId: CHAIN_IDS.ETHEREUM,
        accountIndex: 5,
        addressIndex: 10,
      };

      await service.createAddress(request);

      expect(mockCrypto.deriveAddress).toHaveBeenCalledWith(CHAIN_IDS.ETHEREUM, 5, 10);
    });

    it('should handle database errors gracefully', async () => {
      const derivedAddress = generateAddress();

      mockCrypto.deriveAddress.mockReturnValue({
        address: derivedAddress,
        path: "m/44'/60'/0'/0/0",
      });
      mockPrisma.address.findUnique.mockResolvedValue(null);
      mockPrisma.address.create.mockRejectedValue(new Error('Database connection failed'));

      const request = {
        chainId: CHAIN_IDS.ETHEREUM,
        label: 'Test Label',
      };

      await expect(service.createAddress(request)).rejects.toThrow('Database connection failed');
    });
  });

  describe('getAddress', () => {
    it('should return address from cache when available', async () => {
      const mockAddress = testDataFactory.createAddress();

      mockCache.get.mockResolvedValue(mockAddress);

      const result = await service.getAddress(mockAddress.id);

      expect(result).toEqual(mockAddress);
      expect(mockPrisma.address.findUnique).not.toHaveBeenCalled();
      expect(mockCache.get).toHaveBeenCalledWith(`address:0:${mockAddress.id}`);
    });

    it('should fetch from database when cache miss and update cache', async () => {
      const mockAddress = testDataFactory.createAddressWithTags({
        address: generateAddress(),
      }, ['test', 'wallet']);

      mockCache.get.mockResolvedValue(null);
      mockPrisma.address.findUnique.mockResolvedValue(mockAddress);
      mockCache.set.mockResolvedValue(true);

      const result = await service.getAddress(mockAddress.id);

      expect(result.id).toBe(mockAddress.id);
      expect(result.tags).toEqual(['test', 'wallet']);
      expect(mockPrisma.address.findUnique).toHaveBeenCalledWith({
        where: { id: mockAddress.id },
        include: { AddressTag: true },
      });
      expect(mockCache.set).toHaveBeenCalled();
    });

    it('should throw NotFoundError when address does not exist', async () => {
      const nonExistentId = generateCuid();

      mockCache.get.mockResolvedValue(null);
      mockPrisma.address.findUnique.mockResolvedValue(null);

      await expect(service.getAddress(nonExistentId)).rejects.toThrow(NotFoundError);
    });

    it('should return tags correctly', async () => {
      const mockAddress = testDataFactory.createAddressWithTags({}, ['cold-wallet', 'multi-sig']);

      mockCache.get.mockResolvedValue(null);
      mockPrisma.address.findUnique.mockResolvedValue(mockAddress);
      mockCache.set.mockResolvedValue(true);

      const result = await service.getAddress(mockAddress.id);

      expect(result.tags).toContain('cold-wallet');
      expect(result.tags).toContain('multi-sig');
    });
  });

  describe('getAddresses', () => {
    it('should return paginated addresses', async () => {
      const addresses = testDataFactory.createAddressList(5);

      mockPrisma.address.count.mockResolvedValue(5);
      mockPrisma.address.findMany.mockResolvedValue(addresses);

      const result = await service.getAddresses({ page: 1, pageSize: 10 });

      expect(result.total).toBe(5);
      expect(result.items.length).toBe(5);
      expect(mockPrisma.address.findMany).toHaveBeenCalledWith({
        where: {},
        skip: 0,
        take: 10,
        orderBy: { createdAt: 'desc' },
        include: { AddressTag: true },
      });
    });

    it('should filter by chainId', async () => {
      const addresses = testDataFactory.createAddressList(3, { chainId: CHAIN_IDS.BSC });

      mockPrisma.address.count.mockResolvedValue(3);
      mockPrisma.address.findMany.mockResolvedValue(addresses);

      await service.getAddresses({ chainId: CHAIN_IDS.BSC });

      expect(mockPrisma.address.count).toHaveBeenCalledWith({
        where: { chainId: CHAIN_IDS.BSC },
      });
    });

    it('should filter by label (case insensitive)', async () => {
      const addresses = testDataFactory.createAddressList(2, { label: 'Trading Wallet' });

      mockPrisma.address.count.mockResolvedValue(2);
      mockPrisma.address.findMany.mockResolvedValue(addresses);

      await service.getAddresses({ label: 'trading' });

      expect(mockPrisma.address.count).toHaveBeenCalledWith({
        where: {
          label: {
            contains: 'trading',
            mode: 'insensitive',
          },
        },
      });
    });

    it('should filter by active status', async () => {
      const addresses = testDataFactory.createAddressList(4, { isActive: true });

      mockPrisma.address.count.mockResolvedValue(4);
      mockPrisma.address.findMany.mockResolvedValue(addresses);

      await service.getAddresses({ isActive: true });

      expect(mockPrisma.address.count).toHaveBeenCalledWith({
        where: { isActive: true },
      });
    });

    it('should handle empty results correctly', async () => {
      mockPrisma.address.count.mockResolvedValue(0);
      mockPrisma.address.findMany.mockResolvedValue([]);

      const result = await service.getAddresses({ chainId: 99999 });

      expect(result.total).toBe(0);
      expect(result.items).toEqual([]);
    });

    it('should handle pagination correctly', async () => {
      const addresses = testDataFactory.createAddressList(5);

      mockPrisma.address.count.mockResolvedValue(15);
      mockPrisma.address.findMany.mockResolvedValue(addresses);

      await service.getAddresses({ page: 2, pageSize: 5 });

      expect(mockPrisma.address.findMany).toHaveBeenCalledWith(
        expect.objectContaining({
          skip: 5,
          take: 5,
        })
      );
    });

    it('should handle page 0 edge case', async () => {
      const addresses = testDataFactory.createAddressList(10);

      mockPrisma.address.count.mockResolvedValue(10);
      mockPrisma.address.findMany.mockResolvedValue(addresses);

      await service.getAddresses({ page: 0, pageSize: 10 });

      expect(mockPrisma.address.findMany).toHaveBeenCalledWith(
        expect.objectContaining({
          skip: 0,
        })
      );
    });
  });

  describe('updateAddress', () => {
    it('should update address successfully and invalidate cache', async () => {
      const originalAddress = testDataFactory.createAddress({
        label: 'Old Label',
        isActive: true,
      });
      const updatedAddress = {
        ...originalAddress,
        label: 'New Label',
        isActive: false,
        AddressTag: [],
      };

      mockPrisma.address.findUnique.mockResolvedValue(originalAddress);
      mockPrisma.address.update.mockResolvedValue(updatedAddress);
      mockCache.delete.mockResolvedValue(true);

      const result = await service.updateAddress(originalAddress.id, {
        label: 'New Label',
        isActive: false,
      });

      expect(result.label).toBe('New Label');
      expect(result.isActive).toBe(false);
      expect(mockCache.delete).toHaveBeenCalledTimes(2);
    });

    it('should throw NotFoundError when address does not exist', async () => {
      const nonExistentId = generateCuid();

      mockPrisma.address.findUnique.mockResolvedValue(null);

      await expect(
        service.updateAddress(nonExistentId, { label: 'Test' })
      ).rejects.toThrow(NotFoundError);
    });

    it('should update only provided fields', async () => {
      const originalAddress = testDataFactory.createAddress({
        label: 'Original',
        isActive: true,
        metadata: { key: 'value' },
      });

      mockPrisma.address.findUnique.mockResolvedValue(originalAddress);
      mockPrisma.address.update.mockResolvedValue({
        ...originalAddress,
        label: 'Updated',
        AddressTag: [],
      });
      mockCache.delete.mockResolvedValue(true);

      await service.updateAddress(originalAddress.id, {
        label: 'Updated',
      });

      expect(mockPrisma.address.update).toHaveBeenCalledWith({
        where: { id: originalAddress.id },
        data: {
          label: 'Updated',
          metadata: undefined,
          isActive: undefined,
        },
        include: { AddressTag: true },
      });
    });

    it('should handle partial updates correctly', async () => {
      const originalAddress = testDataFactory.createAddress({
        label: 'Wallet',
        isActive: true,
      });

      mockPrisma.address.findUnique.mockResolvedValue(originalAddress);
      mockPrisma.address.update.mockResolvedValue({
        ...originalAddress,
        isActive: false,
        AddressTag: [],
      });
      mockCache.delete.mockResolvedValue(true);

      await service.updateAddress(originalAddress.id, {
        isActive: false,
      });

      expect(mockPrisma.address.update).toHaveBeenCalledWith(
        expect.objectContaining({
          data: {
            label: undefined,
            isActive: false,
            metadata: undefined,
          },
        })
      );
    });
  });

  describe('addTag', () => {
    it('should add tag successfully', async () => {
      const mockAddress = testDataFactory.createAddress();
      const updatedAddress = {
        ...mockAddress,
        AddressTag: [
          { id: generateCuid(), addressId: mockAddress.id, tag: 'cold-storage', createdAt: new Date() },
        ],
      };

      mockPrisma.address.findUnique.mockResolvedValue(mockAddress);
      mockPrisma.addressTag.findUnique.mockResolvedValue(null);
      mockPrisma.addressTag.create.mockResolvedValue({
        id: generateCuid(),
        addressId: mockAddress.id,
        tag: 'cold-storage',
      });
      mockPrisma.address.findUnique.mockResolvedValueOnce(mockAddress);
      mockPrisma.address.findUnique.mockResolvedValue(updatedAddress);
      mockCache.delete.mockResolvedValue(true);

      const result = await service.addTag(mockAddress.id, 'cold-storage');

      expect(result.tags).toContain('cold-storage');
      expect(mockPrisma.addressTag.create).toHaveBeenCalled();
    });

    it('should throw ConflictError when tag already exists', async () => {
      const mockAddress = testDataFactory.createAddress();
      const existingTag = {
        id: generateCuid(),
        addressId: mockAddress.id,
        tag: 'existing-tag',
      };

      mockPrisma.address.findUnique.mockResolvedValue(mockAddress);
      mockPrisma.addressTag.findUnique.mockResolvedValue(existingTag);

      await expect(
        service.addTag(mockAddress.id, 'existing-tag')
      ).rejects.toThrow(ConflictError);
    });

    it('should throw NotFoundError when address does not exist', async () => {
      const nonExistentId = generateCuid();

      mockPrisma.address.findUnique.mockResolvedValue(null);

      await expect(
        service.addTag(nonExistentId, 'test-tag')
      ).rejects.toThrow(NotFoundError);
    });

    it('should invalidate cache after adding tag', async () => {
      const mockAddress = testDataFactory.createAddress();

      mockPrisma.address.findUnique.mockResolvedValue(mockAddress);
      mockPrisma.addressTag.findUnique.mockResolvedValue(null);
      mockPrisma.addressTag.create.mockResolvedValue({
        id: generateCuid(),
        addressId: mockAddress.id,
        tag: 'new-tag',
      });
      mockPrisma.address.findUnique.mockResolvedValue({
        ...mockAddress,
        AddressTag: [],
      });
      mockCache.delete.mockResolvedValue(true);

      await service.addTag(mockAddress.id, 'new-tag');

      expect(mockCache.delete).toHaveBeenCalled();
    });
  });

  describe('removeTag', () => {
    it('should remove tag successfully', async () => {
      const mockAddress = testDataFactory.createAddressWithTags({}, ['keep-tag']);
      const existingTag = {
        id: generateCuid(),
        addressId: mockAddress.id,
        tag: 'remove-tag',
      };

      mockPrisma.addressTag.findUnique.mockResolvedValue(existingTag);
      mockPrisma.addressTag.delete.mockResolvedValue(existingTag);
      mockPrisma.address.findUnique.mockResolvedValue(mockAddress);
      mockCache.delete.mockResolvedValue(true);

      const result = await service.removeTag(mockAddress.id, 'remove-tag');

      expect(result.tags).toEqual(['keep-tag']);
      expect(mockPrisma.addressTag.delete).toHaveBeenCalled();
    });

    it('should throw NotFoundError when tag does not exist', async () => {
      const mockAddress = testDataFactory.createAddress();

      mockPrisma.addressTag.findUnique.mockResolvedValue(null);

      await expect(
        service.removeTag(mockAddress.id, 'non-existent-tag')
      ).rejects.toThrow(NotFoundError);
    });

    it('should invalidate cache after removing tag', async () => {
      const mockAddress = testDataFactory.createAddress();
      const existingTag = {
        id: generateCuid(),
        addressId: mockAddress.id,
        tag: 'old-tag',
      };

      mockPrisma.addressTag.findUnique.mockResolvedValue(existingTag);
      mockPrisma.addressTag.delete.mockResolvedValue(existingTag);
      mockPrisma.address.findUnique.mockResolvedValue(mockAddress);
      mockCache.delete.mockResolvedValue(true);

      await service.removeTag(mockAddress.id, 'old-tag');

      expect(mockCache.delete).toHaveBeenCalled();
    });
  });

  describe('getByAddress', () => {
    it('should return address from cache when available', async () => {
      const mockAddress = testDataFactory.createAddress();

      mockCache.get.mockResolvedValue(mockAddress);

      const result = await service.getByAddress(mockAddress.chainId, mockAddress.address);

      expect(result).toEqual(mockAddress);
      expect(mockCache.get).toHaveBeenCalledWith(
        `address:${mockAddress.chainId}:${mockAddress.address}`
      );
    });

    it('should fetch from database when cache miss', async () => {
      const mockAddress = testDataFactory.createAddressWithTags();

      mockCache.get.mockResolvedValue(null);
      mockPrisma.address.findUnique.mockResolvedValue(mockAddress);
      mockCache.set.mockResolvedValue(true);

      const result = await service.getByAddress(mockAddress.chainId, mockAddress.address);

      expect(result.id).toBe(mockAddress.id);
      expect(mockPrisma.address.findUnique).toHaveBeenCalledWith({
        where: {
          address_chainId: {
            address: mockAddress.address,
            chainId: mockAddress.chainId,
          },
        },
        include: { AddressTag: true },
      });
    });

    it('should throw NotFoundError when address not found', async () => {
      const nonExistentAddress = generateAddress();

      mockCache.get.mockResolvedValue(null);
      mockPrisma.address.findUnique.mockResolvedValue(null);

      await expect(
        service.getByAddress(CHAIN_IDS.ETHEREUM, nonExistentAddress)
      ).rejects.toThrow(NotFoundError);
    });
  });

  describe('listByTag', () => {
    it('should list addresses by tag', async () => {
      const addresses = testDataFactory.createAddressList(3);
      const tags = addresses.map((addr, index) => ({
        id: generateCuid(),
        addressId: addr.id,
        tag: 'multi-sig',
        address: { ...addr, AddressTag: [] },
      }));

      mockPrisma.addressTag.findMany.mockResolvedValue(tags);

      const result = await service.listByTag('multi-sig');

      expect(result.length).toBe(3);
      expect(mockPrisma.addressTag.findMany).toHaveBeenCalledWith({
        where: { tag: 'multi-sig' },
        include: {
          address: {
            include: { AddressTag: true },
          },
        },
      });
    });

    it('should filter by chainId when provided', async () => {
      mockPrisma.addressTag.findMany.mockResolvedValue([]);

      await service.listByTag('hot-wallet', CHAIN_IDS.BSC);

      expect(mockPrisma.addressTag.findMany).toHaveBeenCalledWith({
        where: {
          tag: 'hot-wallet',
          address: {
            chainId: CHAIN_IDS.BSC,
          },
        },
        include: expect.any(Object),
      });
    });

    it('should return empty array when no addresses match tag', async () => {
      mockPrisma.addressTag.findMany.mockResolvedValue([]);

      const result = await service.listByTag('non-existent-tag');

      expect(result).toEqual([]);
    });
  });
});
