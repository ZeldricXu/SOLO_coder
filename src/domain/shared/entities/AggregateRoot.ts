import { DomainEvent, DomainEventBase } from '../events/DomainEvent';

export abstract class AggregateRoot<TId> {
  protected readonly _id: TId;
  protected _createdAt: Date;
  protected _updatedAt: Date;
  private _domainEvents: DomainEvent<unknown>[] = [];

  protected constructor(id: TId) {
    this._id = id;
    this._createdAt = new Date();
    this._updatedAt = new Date();
  }

  get id(): TId {
    return this._id;
  }

  get createdAt(): Date {
    return this._createdAt;
  }

  get updatedAt(): Date {
    return this._updatedAt;
  }

  protected addDomainEvent(event: DomainEvent<unknown>): void {
    this._domainEvents.push(event);
  }

  protected addSimpleDomainEvent(type: string, data: unknown, aggregateType: string = this.constructor.name): void {
    const event = new (class extends DomainEventBase<unknown> {
      constructor(aggregateId: string, data: unknown) {
        super(type, aggregateId, aggregateType, data);
      }
    })(this._id instanceof Object ? (this._id as any).value : String(this._id), data);
    this._domainEvents.push(event);
  }

  clearDomainEvents(): void {
    this._domainEvents = [];
  }

  get domainEvents(): DomainEvent<unknown>[] {
    return [...this._domainEvents];
  }

  protected touch(): void {
    this._updatedAt = new Date();
  }

  equals(other: AggregateRoot<TId>): boolean {
    if (other === null || other === undefined) return false;
    if (this === other) return true;
    return this._id === other._id;
  }
}
