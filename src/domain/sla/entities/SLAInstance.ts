import { AggregateRoot } from '../../shared/entities/AggregateRoot';
import { UniqueEntityID } from '../../shared/value-objects/UniqueEntityID';
import { SLAPolicy } from './SLAPolicy';
import { SLAStatus, SLAStatusType } from '../value-objects/SLAStatus';

export interface SLAInstanceProps {
  ticketId: UniqueEntityID;
  policyId: UniqueEntityID;
  policy: SLAPolicy;
  responseDeadline: Date;
  resolutionDeadline: Date;
  status: SLAStatus;
  currentLevel: number;
  breachedAt?: Date | null;
  metAt?: Date | null;
  tenantId: UniqueEntityID;
  createdAt?: Date;
  updatedAt?: Date;
}

export class SLAInstance extends AggregateRoot<UniqueEntityID> {
  private _ticketId: UniqueEntityID;
  private _policyId: UniqueEntityID;
  private _policy: SLAPolicy;
  private _responseDeadline: Date;
  private _resolutionDeadline: Date;
  private _status: SLAStatus;
  private _currentLevel: number;
  private _breachedAt: Date | null;
  private _metAt: Date | null;
  private _tenantId: UniqueEntityID;

  private constructor(id: UniqueEntityID, props: SLAInstanceProps) {
    super(id);
    this._ticketId = props.ticketId;
    this._policyId = props.policyId;
    this._policy = props.policy;
    this._responseDeadline = props.responseDeadline;
    this._resolutionDeadline = props.resolutionDeadline;
    this._status = props.status;
    this._currentLevel = props.currentLevel;
    this._breachedAt = props.breachedAt ?? null;
    this._metAt = props.metAt ?? null;
    this._tenantId = props.tenantId;
    if (props.createdAt) this._createdAt = props.createdAt;
    if (props.updatedAt) this._updatedAt = props.updatedAt;
  }

  get ticketId(): UniqueEntityID { return this._ticketId; }
  get policyId(): UniqueEntityID { return this._policyId; }
  get policy(): SLAPolicy { return this._policy; }
  get responseDeadline(): Date { return this._responseDeadline; }
  get resolutionDeadline(): Date { return this._resolutionDeadline; }
  get status(): SLAStatus { return this._status; }
  get currentLevel(): number { return this._currentLevel; }
  get breachedAt(): Date | null { return this._breachedAt; }
  get metAt(): Date | null { return this._metAt; }
  get tenantId(): UniqueEntityID { return this._tenantId; }

  checkStatus(now: Date = new Date()): {
    status: SLAStatusType;
    responseProgress: number;
    resolutionProgress: number;
    timeRemaining: number;
    shouldEscalate: boolean;
    escalationLevel: unknown;
  } {
    const responseStatus = this._policy.calculateStatus(this._createdAt, now, 'response');
    const resolutionStatus = this._policy.calculateStatus(this._createdAt, now, 'resolution');

    const status = this._determineOverallStatus(responseStatus.status, resolutionStatus.status);
    const escalationLevel = this._policy.getEscalationLevel(resolutionStatus.progress);

    const shouldEscalate = escalationLevel !== null &&
      escalationLevel.level > this._currentLevel &&
      !this._status.isBreached() &&
      !this._status.isMet();

    return {
      status,
      responseProgress: responseStatus.progress,
      resolutionProgress: resolutionStatus.progress,
      timeRemaining: resolutionStatus.timeRemaining,
      shouldEscalate,
      escalationLevel
    };
  }

  private _determineOverallStatus(response: SLAStatusType, resolution: SLAStatusType): SLAStatusType {
    const priority: SLAStatusType[] = ['breached', 'warning', 'active', 'met'];
    const responseIndex = priority.indexOf(response);
    const resolutionIndex = priority.indexOf(resolution);
    return priority[Math.min(responseIndex, resolutionIndex)];
  }

  markBreached(now: Date = new Date()): void {
    if (this._status.isBreached()) return;
    this._status = SLAStatus.create('breached');
    this._breachedAt = now;
    this.addSimpleDomainEvent('SLA_BREACHED', {
      slaId: this.id.value,
      ticketId: this._ticketId.value,
      policyId: this._policyId.value
    });
    this.touch();
  }

  markMet(now: Date = new Date()): void {
    if (this._status.isMet()) return;
    this._status = SLAStatus.create('met');
    this._metAt = now;
    this.addSimpleDomainEvent('SLA_MET', {
      slaId: this.id.value,
      ticketId: this._ticketId.value,
      policyId: this._policyId.value
    });
    this.touch();
  }

  escalate(level: number): void {
    if (level <= this._currentLevel) return;
    this._currentLevel = level;
    this.addSimpleDomainEvent('SLA_ESCALATED', {
      slaId: this.id.value,
      ticketId: this._ticketId.value,
      level
    });
    this.touch();
  }

  static create(props: Omit<SLAInstanceProps, 'status' | 'currentLevel' | 'breachedAt' | 'metAt'> & {
    id?: string;
  }): SLAInstance {
    return new SLAInstance(UniqueEntityID.create(props.id), {
      ...props,
      status: SLAStatus.create('active'),
      currentLevel: 0,
      breachedAt: null,
      metAt: null
    });
  }
}
