declare global {
  interface BigInt {
    toJSON(): string;
  }
}

(BigInt.prototype as any).toJSON = function () {
  return this.toString();
};

export {};
