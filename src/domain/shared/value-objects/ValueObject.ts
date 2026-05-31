export abstract class ValueObject<TProps, TValue = Readonly<TProps>> {
  protected readonly props: TProps;

  protected constructor(props: TProps) {
    this.validate(props);
    this.props = Object.freeze({ ...props });
  }

  protected abstract validate(props: TProps): void;

  equals(other: ValueObject<TProps, TValue>): boolean {
    if (other === null || other === undefined) return false;
    if (other.constructor.name !== this.constructor.name) return false;
    return JSON.stringify(this.props) === JSON.stringify(other.props);
  }

  get value(): TValue {
    return this.props as unknown as TValue;
  }
}
