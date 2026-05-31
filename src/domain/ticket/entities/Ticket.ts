import { AggregateRoot } from '../../shared/entities/AggregateRoot';
import { UniqueEntityID } from '../../shared/value-objects/UniqueEntityID';
import { TicketStatus } from '../value-objects/TicketStatus';
import { TicketPriority } from '../value-objects/TicketPriority';
import { TicketType } from '../value-objects/TicketType';
import { SkillRequirement } from '../value-objects/SkillRequirement';
import { SLA } from '../value-objects/SLA';
import { TicketCreatedEvent } from '../events/TicketCreatedEvent';
import { TicketAssignedEvent } from '../events/TicketAssignedEvent';
import { BusinessRuleViolationError } from '../../shared/errors/DomainError';
import { randomUUID } from 'crypto';

export interface TicketProps {
  id?: string;
  title: string;
  description: string;
  type: TicketType;
  priority: TicketPriority;
  tenantId: UniqueEntityID;
  requiredSkills: SkillRequirement[];
  agentId?: UniqueEntityID;
  status?: TicketStatus;
  sla?: SLA;
  createdAt?: Date;
  updatedAt?: Date;
  resolvedAt?: Date;
  closedAt?: Date;
  metadata?: Record<string, unknown>;
}

export class Ticket extends AggregateRoot<UniqueEntityID> {
  private _title: string;
  private _description: string;
  private _type: TicketType;
  private _priority: TicketPriority;
  private _status: TicketStatus;
  private _tenantId: UniqueEntityID;
  private _requiredSkills: SkillRequirement[];
  private _agentId?: UniqueEntityID;
  private _sla?: SLA;
  private _resolvedAt?: Date;
  private _closedAt?: Date;
  private _metadata: Record<string, unknown>;

  private constructor(props: TicketProps) {
    const id = props.id ? UniqueEntityID.create(props.id) : UniqueEntityID.create(randomUUID());
    super(id);
    this._title = props.title;
    this._description = props.description;
    this._type = props.type;
    this._priority = props.priority;
    this._status = props.status || TicketStatus.create('open');
    this._tenantId = props.tenantId;
    this._requiredSkills = props.requiredSkills;
    this._agentId = props.agentId;
    this._sla = props.sla;
    this._metadata = props.metadata || {};
    if (props.createdAt) this._createdAt = props.createdAt;
    if (props.updatedAt) this._updatedAt = props.updatedAt;
    this._resolvedAt = props.resolvedAt;
    this._closedAt = props.closedAt;
  }

  public static create(props: TicketProps): Ticket {
    const ticket = new Ticket(props);

    if (!props.id) {
      ticket.addDomainEvent(TicketCreatedEvent.create({
        ticketId: ticket.id.value,
        tenantId: ticket.tenantId.value,
        title: ticket.title,
        priority: ticket.priority.value,
        requiredSkills: ticket.requiredSkills.map(s => ({
          skillId: s.skillId.value,
          minLevel: s.minLevel
        }))
      }));
    }

    return ticket;
  }

  public assignTo(agentId: UniqueEntityID, assignedBy: string, _score?: number): void {
    if (this.status.value === 'closed' || this.status.value === 'resolved') {
      throw new BusinessRuleViolationError('Cannot assign a closed or resolved ticket', {
        ticketId: this.id.value,
        currentStatus: this.status.value
      });
    }

    if (this._agentId && this._agentId.equals(agentId)) {
      return;
    }

    const previousAgentId = this._agentId?.value;
    this._agentId = agentId;
    this._status = TicketStatus.create('assigned');
    this._updatedAt = new Date();

    this.addDomainEvent(TicketAssignedEvent.create({
      ticketId: this.id.value,
      tenantId: this.tenantId.value,
      agentId: agentId.value,
      previousAgentId,
      assignedBy,
      priority: this.priority.value
    }));
  }

  public updateStatus(status: TicketStatus): void {
    this.validateStatusTransition(this._status, status);
    this._status = status;
    this._updatedAt = new Date();

    if (status.value === 'resolved') {
      this._resolvedAt = new Date();
    } else if (status.value === 'closed') {
      this._closedAt = new Date();
    }
  }

  private validateStatusTransition(current: TicketStatus, next: TicketStatus): void {
    const validTransitions: Record<string, string[]> = {
      'open': ['assigned', 'in_progress', 'resolved', 'closed'],
      'assigned': ['in_progress', 'open', 'resolved', 'closed'],
      'in_progress': ['assigned', 'resolved', 'closed', 'open'],
      'resolved': ['closed', 'reopened'],
      'reopened': ['assigned', 'in_progress', 'resolved', 'closed'],
      'closed': []
    };

    if (!validTransitions[current.value]?.includes(next.value)) {
      throw new BusinessRuleViolationError(
        `Invalid status transition from ${current.value} to ${next.value}`,
        { currentStatus: current.value, nextStatus: next.value }
      );
    }
  }

  public canAssign(): boolean {
    return !['closed', 'resolved'].includes(this.status.value);
  }

  get title(): string { return this._title; }
  get description(): string { return this._description; }
  get type(): TicketType { return this._type; }
  get priority(): TicketPriority { return this._priority; }
  get status(): TicketStatus { return this._status; }
  get tenantId(): UniqueEntityID { return this._tenantId; }
  get requiredSkills(): SkillRequirement[] { return this._requiredSkills; }
  get agentId(): UniqueEntityID | undefined { return this._agentId; }
  get sla(): SLA | undefined { return this._sla; }
  get resolvedAt(): Date | undefined { return this._resolvedAt; }
  get closedAt(): Date | undefined { return this._closedAt; }
  get metadata(): Record<string, unknown> { return this._metadata; }

  set title(title: string) {
    this._title = title;
    this._updatedAt = new Date();
  }

  set description(description: string) {
    this._description = description;
    this._updatedAt = new Date();
  }

  set priority(priority: TicketPriority) {
    this._priority = priority;
    this._updatedAt = new Date();
  }
}
