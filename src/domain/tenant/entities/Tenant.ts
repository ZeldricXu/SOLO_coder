import { AggregateRoot } from '../../shared/entities/AggregateRoot';
import { UniqueEntityID } from '../../shared/value-objects/UniqueEntityID';
import { TenantStatus, TenantStatusType } from '../value-objects/TenantStatus';
import { ResourceQuota } from '../value-objects/ResourceQuota';
import { BusinessRuleViolationError } from '../../shared/errors/DomainError';

export interface TenantProps {
  name: string;
  email: string;
  status?: TenantStatus;
  quota?: ResourceQuota;
  config?: Record<string, unknown>;
  createdAt?: Date;
  updatedAt?: Date;
}

export class Tenant extends AggregateRoot<UniqueEntityID> {
  private _name: string;
  private _email: string;
  private _status: TenantStatus;
  private _quota: ResourceQuota;
  private _config: Record<string, unknown>;

  private constructor(id: UniqueEntityID, props: TenantProps) {
    super(id);
    this._name = props.name;
    this._email = props.email;
    this._status = props.status || TenantStatus.create();
    this._quota = props.quota || ResourceQuota.createDefault();
    this._config = props.config || {};
    if (props.createdAt) this._createdAt = props.createdAt;
    if (props.updatedAt) this._updatedAt = props.updatedAt;
  }

  get name(): string {
    return this._name;
  }

  get email(): string {
    return this._email;
  }

  get status(): TenantStatus {
    return this._status;
  }

  get quota(): ResourceQuota {
    return this._quota;
  }

  get config(): Readonly<Record<string, unknown>> {
    return Object.freeze({ ...this._config });
  }

  updateName(name: string): void {
    if (!name || name.trim().length === 0) {
      throw new BusinessRuleViolationError('Tenant name cannot be empty', 'TENANT_NAME_REQUIRED');
    }
    this._name = name;
    this.touch();
  }

  updateEmail(email: string): void {
    if (!email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      throw new BusinessRuleViolationError('Invalid email format', 'TENANT_EMAIL_INVALID');
    }
    this._email = email;
    this.touch();
  }

  activate(): void {
    if (this._status.isActive()) return;
    this._status = TenantStatus.create('active');
    this.addSimpleDomainEvent('TENANT_ACTIVATED', { tenantId: this.id.value });
    this.touch();
  }

  suspend(reason: string): void {
    if (this._status.isSuspended()) return;
    this._status = TenantStatus.create('suspended');
    this.addSimpleDomainEvent('TENANT_SUSPENDED', { tenantId: this.id.value, reason });
    this.touch();
  }

  updateQuota(quota: ResourceQuota): void {
    this._quota = quota;
    this.addSimpleDomainEvent('TENANT_QUOTA_UPDATED', { tenantId: this.id.value, quota: quota.value });
    this.touch();
  }

  setConfig(key: string, value: unknown): void {
    this._config = { ...this._config, [key]: value };
    this.touch();
  }

  canAccessTenant(targetTenantId: UniqueEntityID): boolean {
    return this.id.equals(targetTenantId);
  }

  static create(props: Omit<TenantProps, 'status' | 'quota'> & { id?: string }): Tenant {
    const id = UniqueEntityID.create(props.id);
    const tenant = new Tenant(id, {
      ...props,
      status: TenantStatus.create('active')
    });
    tenant.addSimpleDomainEvent('TENANT_CREATED', {
      tenantId: id.value,
      name: props.name,
      email: props.email
    });
    return tenant;
  }

  static restore(id: string, props: TenantProps & { status: TenantStatusType }): Tenant {
    return new Tenant(UniqueEntityID.create(id), {
      ...props,
      status: TenantStatus.create(props.status)
    });
  }
}
