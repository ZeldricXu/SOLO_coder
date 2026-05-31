import { Prisma, PrismaClient, Address, AddressTag } from '@prisma/client';
import { getPrismaClient } from '../../utils/database';
import { CryptoUtils } from '../../utils/crypto';
import { NotFoundError, ConflictError, ValidationError } from '../../utils/errors';
import { CreateAddressRequest, UpdateAddressRequest, Address as IAddress } from '../../types';
import { cacheService } from '../../utils/cache';

const PRISMA_UNIQUE_CONSTRAINT_ERROR = 'P2002';
const MAX_LABEL_LENGTH = 255;
const MAX_TAG_LENGTH = 100;
const MAX_ACCOUNT_INDEX = 2147483647;
const MAX_ADDRESS_INDEX = 2147483647;

export class AddressManagerService {
  private prisma: PrismaClient;
  private readonly CACHE_TTL = 3600;

  constructor() {
    this.prisma = getPrismaClient();
  }

  private getCacheKey(chainId: number, address: string): string {
    return `address:${chainId}:${address}`;
  }

  async createAddress(request: CreateAddressRequest): Promise<IAddress> {
    const { chainId, label, accountIndex = 0, addressIndex = 0 } = request;

    if (chainId <= 0) {
      throw new ValidationError('Chain ID must be positive');
    }

    if (accountIndex < 0 || accountIndex > MAX_ACCOUNT_INDEX) {
      throw new ValidationError(`Account index must be between 0 and ${MAX_ACCOUNT_INDEX}`);
    }

    if (addressIndex < 0 || addressIndex > MAX_ADDRESS_INDEX) {
      throw new ValidationError(`Address index must be between 0 and ${MAX_ADDRESS_INDEX}`);
    }

    if (label && label.length > MAX_LABEL_LENGTH) {
      throw new ValidationError(`Label cannot exceed ${MAX_LABEL_LENGTH} characters`);
    }

    const derived = CryptoUtils.deriveAddress(chainId, accountIndex, addressIndex);

    try {
      const address = await this.prisma.address.create({
        data: {
          address: derived.address,
          chainId,
          derivationPath: derived.path,
          walletType: 'hd',
          label,
        },
      });

      const cacheKey = this.getCacheKey(chainId, address.address);
      await cacheService.set(cacheKey, address, this.CACHE_TTL);

      return this.toDomainModel(address);
    } catch (error) {
      if (
        error instanceof Prisma.PrismaClientKnownRequestError &&
        error.code === PRISMA_UNIQUE_CONSTRAINT_ERROR
      ) {
        throw new ConflictError('Address already exists', {
          address: derived.address,
          chainId,
        });
      }
      throw error;
    }
  }

  async getAddress(addressId: string): Promise<IAddress> {
    const cacheKey = this.getCacheKey(0, addressId);
    const cached = await cacheService.get<IAddress>(cacheKey);
    
    if (cached) {
      return cached;
    }

    const address = await this.prisma.address.findUnique({
      where: { id: addressId },
      include: {
        AddressTag: true,
      },
    });

    if (!address) {
      throw new NotFoundError('Address not found');
    }

    const domainModel = this.toDomainModel(address);
    await cacheService.set(cacheKey, domainModel, this.CACHE_TTL);

    return domainModel;
  }

  async getAddresses(filters?: {
    chainId?: number;
    label?: string;
    isActive?: boolean;
    page?: number;
    pageSize?: number;
  }): Promise<{ items: IAddress[]; total: number }> {
    const { chainId, label, isActive, page = 1, pageSize = 20 } = filters || {};

    const validatedPage = Math.max(1, page);
    const validatedPageSize = Math.min(Math.max(1, pageSize), 100);

    if (chainId !== undefined && chainId <= 0) {
      throw new ValidationError('Chain ID must be positive');
    }

    if (label && label.length > MAX_LABEL_LENGTH) {
      throw new ValidationError(`Label search cannot exceed ${MAX_LABEL_LENGTH} characters`);
    }

    const where: any = {};

    if (chainId !== undefined) {
      where.chainId = chainId;
    }

    if (label) {
      where.label = {
        contains: label,
        mode: 'insensitive',
      };
    }

    if (isActive !== undefined) {
      where.isActive = isActive;
    }

    const [total, addresses] = await Promise.all([
      this.prisma.address.count({ where }),
      this.prisma.address.findMany({
        where,
        skip: (validatedPage - 1) * validatedPageSize,
        take: validatedPageSize,
        orderBy: { createdAt: 'desc' },
        include: { AddressTag: true },
      }),
    ]);

    return {
      items: addresses.map(a => this.toDomainModel(a)),
      total,
    };
  }

  async updateAddress(
    addressId: string,
    request: UpdateAddressRequest
  ): Promise<IAddress> {
    const address = await this.prisma.address.findUnique({
      where: { id: addressId },
    });

    if (!address) {
      throw new NotFoundError('Address not found');
    }

    const updated = await this.prisma.address.update({
      where: { id: addressId },
      data: {
        label: request.label,
        metadata: request.metadata,
        isActive: request.isActive,
      },
      include: { AddressTag: true },
    });

    const cacheKey = this.getCacheKey(address.chainId, address.address);
    await cacheService.delete(cacheKey);

    const cacheKeyById = this.getCacheKey(0, addressId);
    await cacheService.delete(cacheKeyById);

    return this.toDomainModel(updated);
  }

  async addTag(addressId: string, tag: string): Promise<IAddress> {
    if (!addressId || addressId.trim() === '') {
      throw new ValidationError('Address ID is required');
    }

    if (!tag || tag.trim() === '') {
      throw new ValidationError('Tag is required');
    }

    const trimmedTag = tag.trim();
    if (trimmedTag.length > MAX_TAG_LENGTH) {
      throw new ValidationError(`Tag cannot exceed ${MAX_TAG_LENGTH} characters`);
    }

    const address = await this.prisma.address.findUnique({
      where: { id: addressId },
    });

    if (!address) {
      throw new NotFoundError('Address not found');
    }

    try {
      await this.prisma.addressTag.create({
        data: {
          addressId,
          tag: trimmedTag,
        },
      });
    } catch (error) {
      if (
        error instanceof Prisma.PrismaClientKnownRequestError &&
        error.code === PRISMA_UNIQUE_CONSTRAINT_ERROR
      ) {
        throw new ConflictError('Tag already exists');
      }
      throw error;
    }

    const updated = await this.prisma.address.findUnique({
      where: { id: addressId },
      include: { AddressTag: true },
    });

    const cacheKey = this.getCacheKey(0, addressId);
    await cacheService.delete(cacheKey);

    return this.toDomainModel(updated!);
  }

  async removeTag(addressId: string, tag: string): Promise<IAddress> {
    const tagRecord = await this.prisma.addressTag.findUnique({
      where: {
        addressId_tag: {
          addressId,
          tag,
        },
      },
    });

    if (!tagRecord) {
      throw new NotFoundError('Tag not found');
    }

    await this.prisma.addressTag.delete({
      where: { id: tagRecord.id },
    });

    const address = await this.prisma.address.findUnique({
      where: { id: addressId },
      include: { AddressTag: true },
    });

    const cacheKey = this.getCacheKey(0, addressId);
    await cacheService.delete(cacheKey);

    return this.toDomainModel(address!);
  }

  async getByAddress(chainId: number, addressValue: string): Promise<IAddress> {
    const cacheKey = this.getCacheKey(chainId, addressValue);
    const cached = await cacheService.get<IAddress>(cacheKey);
    
    if (cached) {
      return cached;
    }

    const address = await this.prisma.address.findUnique({
      where: {
        address_chainId: {
          address: addressValue,
          chainId,
        },
      },
      include: { AddressTag: true },
    });

    if (!address) {
      throw new NotFoundError('Address not found');
    }

    const domainModel = this.toDomainModel(address);
    await cacheService.set(cacheKey, domainModel, this.CACHE_TTL);

    return domainModel;
  }

  async listByTag(tag: string, chainId?: number): Promise<IAddress[]> {
    const where: any = {
      tag,
    };

    if (chainId !== undefined) {
      where.address = {
        chainId,
      };
    }

    const tags = await this.prisma.addressTag.findMany({
      where,
      include: {
        address: {
          include: { AddressTag: true },
        },
      },
    });

    return tags.map(t => this.toDomainModel(t.address));
  }

  private toDomainModel(address: Address & { AddressTag?: AddressTag[] }): IAddress {
    const tags = (address.AddressTag || []).map(t => t.tag);
    return {
      id: address.id,
      address: address.address,
      chainId: address.chainId,
      derivationPath: address.derivationPath,
      walletType: address.walletType as any,
      label: address.label || undefined,
      metadata: address.metadata || undefined,
      isActive: address.isActive,
      createdAt: address.createdAt,
      updatedAt: address.updatedAt,
    };
  }
}

export const addressManagerService = new AddressManagerService();
export default addressManagerService;
