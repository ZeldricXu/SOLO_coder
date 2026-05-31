import { ValueObject } from '../../shared/value-objects/ValueObject';

export type TicketTypeValue = 'incident' | 'service_request' | 'problem' | 'change' | 'task';

interface TicketTypeProps {
  value: TicketTypeValue;
}

export class TicketType extends ValueObject<TicketTypeProps, TicketTypeValue> {
  private constructor(value: TicketTypeValue) {
    super({ value });
  }

  protected validate(props: TicketTypeProps): void {
    const validTypes: TicketTypeValue[] = ['incident', 'service_request', 'problem', 'change', 'task'];
    if (!validTypes.includes(props.value)) {
      throw new Error(`Invalid ticket type: ${props.value}`);
    }
  }

  get value(): TicketTypeValue {
    return this.props.value;
  }

  static create(value: string): TicketType {
    return new TicketType(value as TicketTypeValue);
  }

  static INCIDENT: TicketTypeValue = 'incident';
  static SERVICE_REQUEST: TicketTypeValue = 'service_request';
  static PROBLEM: TicketTypeValue = 'problem';
  static CHANGE: TicketTypeValue = 'change';
  static TASK: TicketTypeValue = 'task';
}
