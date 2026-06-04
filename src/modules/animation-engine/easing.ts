export type EasingType = 'linear' | 'smoothstep';

export function applyEasing(t: number, easing: EasingType): number {
  switch (easing) {
    case 'linear':
      return t;
    case 'smoothstep':
      return 3 * t * t - 2 * t * t * t;
  }
}
