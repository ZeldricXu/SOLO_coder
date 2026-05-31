import { ValueObject } from '../../shared/value-objects/ValueObject';

interface SLAProps {
  policyId: string;
  responseTimeMinutes: number;
  resolutionTimeMinutes: number;
  warningThreshold: number;
  createdAt: Date;
}

export type SLAValue = Readonly<SLAProps>;

export class SLA extends ValueObject<SLAProps, SLAValue> {
  private constructor(props: SLAProps) {
    super(props);
  }

  protected validate(props: SLAProps): void {
    if (props.responseTimeMinutes <= 0) {
      throw new Error('Response time must be greater than 0');
    }
    if (props.resolutionTimeMinutes <= 0) {
      throw new Error('Resolution time must be greater than 0');
    }
    if (props.responseTimeMinutes >= props.resolutionTimeMinutes) {
      throw new Error('Response time must be less than resolution time');
    }
    if (props.warningThreshold < 0 || props.warningThreshold > 1) {
      throw new Error('Warning threshold must be between 0 and 1');
    }
  }

  get value(): SLAValue {
    return this.props;
  }

  get policyId(): string { return this.props.policyId; }
  get responseTimeMinutes(): number { return this.props.responseTimeMinutes; }
  get resolutionTimeMinutes(): number { return this.props.resolutionTimeMinutes; }
  get warningThreshold(): number { return this.props.warningThreshold; }
  get createdAt(): Date { return this.props.createdAt; }

  isResponseTimeBreached(ticketCreatedAt: Date, currentTime: Date = new Date()): boolean {
    const elapsed = (currentTime.getTime() - ticketCreatedAt.getTime()) / (1000 * 60);
    return elapsed > this.responseTimeMinutes;
  }

  isResolutionTimeBreached(ticketCreatedAt: Date, currentTime: Date = new Date()): boolean {
    const elapsed = (currentTime.getTime() - ticketCreatedAt.getTime()) / (1000 * 60);
    return elapsed > this.resolutionTimeMinutes;
  }

  isWarning(ticketCreatedAt: Date, currentTime: Date = new Date()): boolean {
    const elapsed = (currentTime.getTime() - ticketCreatedAt.getTime()) / (1000 * 60);
    const warningTime = this.resolutionTimeMinutes * this.warningThreshold;
    return elapsed > warningTime && !this.isResolutionTimeBreached(ticketCreatedAt, currentTime);
  }

  getRemainingTime(ticketCreatedAt: Date, currentTime: Date = new Date()): number {
    const elapsed = (currentTime.getTime() - ticketCreatedAt.getTime()) / (1000 * 60);
    return Math.max(0, this.resolutionTimeMinutes - elapsed);
  }

  static create(props: Omit<SLAProps, 'createdAt'> & { createdAt?: Date }): SLA {
    return new SLA({
      ...props,
      createdAt: props.createdAt || new Date()
    });
  }
}
