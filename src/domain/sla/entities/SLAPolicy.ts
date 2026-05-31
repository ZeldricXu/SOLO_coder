import { AggregateRoot } from '../../shared/entities/AggregateRoot';
import { UniqueEntityID } from '../../shared/value-objects/UniqueEntityID';
import { SLAStatusType } from '../value-objects/SLAStatus';

export interface EscalationLevel {
  level: number;
  threshold: number;
  action: 'notify' | 'escalate' | 'reassign';
  targetRole?: string;
  message?: string;
}

export interface SLAPolicyProps {
  name: string;
  description: string;
  ticketType: string;
  priority: string;
  responseTimeSeconds: number;
  resolutionTimeSeconds: number;
  warningThreshold: number;
  escalationLevels: EscalationLevel[];
  tenantId: UniqueEntityID;
  enabled: boolean;
  createdAt?: Date;
  updatedAt?: Date;
}

export class SLAPolicy extends AggregateRoot<UniqueEntityID> {
  private _name: string;
  private _description: string;
  private _ticketType: string;
  private _priority: string;
  private _responseTimeSeconds: number;
  private _resolutionTimeSeconds: number;
  private _warningThreshold: number;
  private _escalationLevels: EscalationLevel[];
  private _tenantId: UniqueEntityID;
  private _enabled: boolean;

  private constructor(id: UniqueEntityID, props: SLAPolicyProps) {
    super(id);
    this._name = props.name;
    this._description = props.description;
    this._ticketType = props.ticketType;
    this._priority = props.priority;
    this._responseTimeSeconds = props.responseTimeSeconds;
    this._resolutionTimeSeconds = props.resolutionTimeSeconds;
    this._warningThreshold = props.warningThreshold;
    this._escalationLevels = props.escalationLevels;
    this._tenantId = props.tenantId;
    this._enabled = props.enabled;
    if (props.createdAt) this._createdAt = props.createdAt;
    if (props.updatedAt) this._updatedAt = props.updatedAt;
  }

  get name(): string { return this._name; }
  get description(): string { return this._description; }
  get ticketType(): string { return this._ticketType; }
  get priority(): string { return this._priority; }
  get responseTimeSeconds(): number { return this._responseTimeSeconds; }
  get resolutionTimeSeconds(): number { return this._resolutionTimeSeconds; }
  get warningThreshold(): number { return this._warningThreshold; }
  get escalationLevels(): EscalationLevel[] { return [...this._escalationLevels]; }
  get tenantId(): UniqueEntityID { return this._tenantId; }
  get enabled(): boolean { return this._enabled; }

  calculateStatus(
    startTime: Date,
    now: Date,
    type: 'response' | 'resolution'
  ): { status: SLAStatusType; progress: number; timeRemaining: number } {
    const totalSeconds = type === 'response' ? this._responseTimeSeconds : this._resolutionTimeSeconds;
    const elapsedSeconds = (now.getTime() - startTime.getTime()) / 1000;
    const progress = totalSeconds > 0 ? elapsedSeconds / totalSeconds : 1;
    const timeRemaining = Math.max(0, totalSeconds - elapsedSeconds);

    let status: SLAStatusType = 'active';
    if (progress >= 1) {
      status = 'breached';
    } else if (progress >= this._warningThreshold) {
      status = 'warning';
    }

    return { status, progress, timeRemaining };
  }

  getEscalationLevel(progress: number): EscalationLevel | null {
    const sortedLevels = [...this._escalationLevels].sort((a, b) => b.level - a.level);
    return sortedLevels.find(level => progress >= level.threshold) || null;
  }

  enable(): void {
    this._enabled = true;
    this.touch();
  }

  disable(): void {
    this._enabled = false;
    this.touch();
  }

  static create(props: Omit<SLAPolicyProps, 'enabled' | 'warningThreshold'> & {
    id?: string;
    warningThreshold?: number;
    enabled?: boolean;
  }): SLAPolicy {
    return new SLAPolicy(UniqueEntityID.create(props.id), {
      ...props,
      warningThreshold: props.warningThreshold ?? 0.75,
      enabled: props.enabled ?? true
    });
  }
}
