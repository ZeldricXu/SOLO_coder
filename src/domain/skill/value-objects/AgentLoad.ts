import { ValueObject } from '../../shared/value-objects/ValueObject';

interface AgentLoadProps {
  currentLoad: number;
  maxLoad: number;
}

export class AgentLoad extends ValueObject<AgentLoadProps, Readonly<AgentLoadProps>> {
  private constructor(props: AgentLoadProps) {
    super(props);
  }

  protected validate(props: AgentLoadProps): void {
    if (props.currentLoad < 0) {
      throw new Error('Current load cannot be negative');
    }
    if (props.maxLoad <= 0) {
      throw new Error('Max load must be positive');
    }
    if (props.currentLoad > props.maxLoad) {
      throw new Error('Current load cannot exceed max load');
    }
  }

  get currentLoad(): number {
    return this.props.currentLoad;
  }

  get maxLoad(): number {
    return this.props.maxLoad;
  }

  get utilizationRate(): number {
    return this.props.maxLoad > 0 ? this.props.currentLoad / this.props.maxLoad : 0;
  }

  get loadFactor(): number {
    return this.utilizationRate;
  }

  get availableCapacity(): number {
    return this.props.maxLoad - this.props.currentLoad;
  }

  isOverloaded(): boolean {
    return this.utilizationRate >= 1;
  }

  canAcceptMoreWork(): boolean {
    return this.availableCapacity > 0;
  }

  increment(): AgentLoad {
    const newLoad = Math.min(this.props.currentLoad + 1, this.props.maxLoad);
    return new AgentLoad({ ...this.props, currentLoad: newLoad });
  }

  decrement(): AgentLoad {
    const newLoad = Math.max(this.props.currentLoad - 1, 0);
    return new AgentLoad({ ...this.props, currentLoad: newLoad });
  }

  getLoadScore(): number {
    return 1 - this.utilizationRate;
  }

  static create(currentLoad: number = 0, maxLoad: number = 10): AgentLoad {
    return new AgentLoad({ currentLoad, maxLoad });
  }
}
