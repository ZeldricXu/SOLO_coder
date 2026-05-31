import { ValueObject } from '../../shared/value-objects/ValueObject';

export type SLAStatusType = 'active' | 'warning' | 'breached' | 'met';

interface SLAStatusProps {
  value: SLAStatusType;
}

export class SLAStatus extends ValueObject<SLAStatusProps, SLAStatusType> {
  private constructor(status: SLAStatusType) {
    super({ value: status });
  }

  protected validate(props: SLAStatusProps): void {
    const validStatuses: SLAStatusType[] = ['active', 'warning', 'breached', 'met'];
    if (!validStatuses.includes(props.value)) {
      throw new Error(`Invalid SLA status: ${props.value}`);
    }
  }

  get value(): SLAStatusType {
    return this.props.value;
  }

  isBreached(): boolean {
    return this.props.value === 'breached';
  }

  isWarning(): boolean {
    return this.props.value === 'warning';
  }

  isMet(): boolean {
    return this.props.value === 'met';
  }

  static create(status: SLAStatusType = 'active'): SLAStatus {
    return new SLAStatus(status);
  }
}
