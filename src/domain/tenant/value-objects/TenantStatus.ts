import { ValueObject } from '../../shared/value-objects/ValueObject';

export type TenantStatusType = 'active' | 'inactive' | 'suspended' | 'pending';

interface TenantStatusProps {
  value: TenantStatusType;
}

export class TenantStatus extends ValueObject<TenantStatusProps, TenantStatusType> {
  private constructor(status: TenantStatusType) {
    super({ value: status });
  }

  protected validate(props: TenantStatusProps): void {
    const validStatuses: TenantStatusType[] = ['active', 'inactive', 'suspended', 'pending'];
    if (!validStatuses.includes(props.value)) {
      throw new Error(`Invalid tenant status: ${props.value}`);
    }
  }

  get value(): TenantStatusType {
    return this.props.value;
  }

  isActive(): boolean {
    return this.props.value === 'active';
  }

  isSuspended(): boolean {
    return this.props.value === 'suspended';
  }

  static create(status: TenantStatusType = 'pending'): TenantStatus {
    return new TenantStatus(status);
  }
}
