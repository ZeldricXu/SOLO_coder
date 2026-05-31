import { ValueObject } from '../../shared/value-objects/ValueObject';

export interface ResourceQuotaProps {
  maxTicketsPerMonth: number;
  maxAgents: number;
  maxProcessDefinitions: number;
  maxStorageGB: number;
  maxApiCallsPerMinute: number;
}

export class ResourceQuota extends ValueObject<ResourceQuotaProps> {
  private constructor(props: ResourceQuotaProps) {
    super(props);
  }

  protected validate(props: ResourceQuotaProps): void {
    Object.values(props).forEach(value => {
      if (value < 0) {
        throw new Error('Resource quota values cannot be negative');
      }
    });
  }

  get maxTicketsPerMonth(): number {
    return this.props.maxTicketsPerMonth;
  }

  get maxAgents(): number {
    return this.props.maxAgents;
  }

  get maxProcessDefinitions(): number {
    return this.props.maxProcessDefinitions;
  }

  get maxStorageGB(): number {
    return this.props.maxStorageGB;
  }

  get maxApiCallsPerMinute(): number {
    return this.props.maxApiCallsPerMinute;
  }

  static create(props: ResourceQuotaProps): ResourceQuota {
    return new ResourceQuota(props);
  }

  static createDefault(): ResourceQuota {
    return new ResourceQuota({
      maxTicketsPerMonth: 1000,
      maxAgents: 10,
      maxProcessDefinitions: 20,
      maxStorageGB: 10,
      maxApiCallsPerMinute: 100
    });
  }

  static createPremium(): ResourceQuota {
    return new ResourceQuota({
      maxTicketsPerMonth: 10000,
      maxAgents: 100,
      maxProcessDefinitions: 100,
      maxStorageGB: 100,
      maxApiCallsPerMinute: 1000
    });
  }

  static createEnterprise(): ResourceQuota {
    return new ResourceQuota({
      maxTicketsPerMonth: 100000,
      maxAgents: 1000,
      maxProcessDefinitions: 500,
      maxStorageGB: 1000,
      maxApiCallsPerMinute: 10000
    });
  }
}
