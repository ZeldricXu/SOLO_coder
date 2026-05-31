export abstract class Entity<TId> {
  protected readonly _id: TId;
  protected _createdAt: Date;
  protected _updatedAt: Date;

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

  protected touch(): void {
    this._updatedAt = new Date();
  }

  equals(other: Entity<TId>): boolean {
    if (other === null || other === undefined) return false;
    if (this === other) return true;
    return this._id === other._id;
  }
}
