import { DataRecord, DataSourceType } from '../../core/ports';
import { generateId } from '../../common';

export class DataRecordBuilder {
  private id: string = generateId('entity');
  private sourceType: DataSourceType = 'json';
  private rawData: unknown = {};
  private metadata: Record<string, unknown> = {};
  private createdAt: string = new Date().toISOString();

  withId(id: string): DataRecordBuilder {
    this.id = id;
    return this;
  }

  withSourceType(sourceType: DataSourceType): DataRecordBuilder {
    this.sourceType = sourceType;
    return this;
  }

  withRawData(rawData: unknown): DataRecordBuilder {
    this.rawData = rawData;
    return this;
  }

  withJsonData(data: Record<string, unknown>): DataRecordBuilder {
    this.sourceType = 'json';
    this.rawData = data;
    return this;
  }

  withMetadata(metadata: Record<string, unknown>): DataRecordBuilder {
    this.metadata = { ...this.metadata, ...metadata };
    return this;
  }

  withCreatedAt(createdAt: string): DataRecordBuilder {
    this.createdAt = createdAt;
    return this;
  }

  build(): DataRecord {
    return {
      id: this.id,
      sourceType: this.sourceType,
      rawData: this.rawData,
      metadata: this.metadata,
      created_at: this.createdAt
    };
  }

  static create(): DataRecordBuilder {
    return new DataRecordBuilder();
  }

  static createUserRecord(overrides: Partial<Record<string, unknown>> = {}): DataRecord {
    return new DataRecordBuilder()
      .withJsonData({
        userId: 'user_123',
        userName: 'John Doe',
        email: 'john@example.com',
        age: 30,
        isActive: true,
        ...overrides
      })
      .withMetadata({ source: 'user_service', batch: 'batch_001' })
      .build();
  }

  static createOrderRecord(overrides: Partial<Record<string, unknown>> = {}): DataRecord {
    return new DataRecordBuilder()
      .withJsonData({
        orderId: 'order_456',
        userId: 'user_123',
        amount: 99.99,
        currency: 'USD',
        status: 'pending',
        items: ['item1', 'item2'],
        ...overrides
      })
      .withMetadata({ source: 'order_service' })
      .build();
  }

  static createBatch(count: number, factory: () => DataRecord = () => DataRecordBuilder.createUserRecord()): DataRecord[] {
    return Array.from({ length: count }, factory);
  }

  static createInvalidRecord(): DataRecord {
    return new DataRecordBuilder()
      .withJsonData({
        userId: null,
        userName: '',
        email: 'invalid-email'
      })
      .withMetadata({ validation: 'should_fail' })
      .build();
  }
}
