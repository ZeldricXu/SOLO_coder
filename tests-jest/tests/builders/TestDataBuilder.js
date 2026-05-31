class TestDataBuilder {
  constructor() {
    this.counter = 0;
  }

  generateId(prefix = 'id') {
    this.counter += 1;
    return `${prefix}_${Date.now()}_${this.counter}`;
  }

  randomString(length = 10) {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    let result = '';
    for (let i = 0; i < length; i += 1) {
      result += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return result;
  }

  randomInt(min = 1, max = 100) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
  }

  randomFloat(min = 0, max = 1) {
    return Math.random() * (max - min) + min;
  }

  randomBoolean() {
    return Math.random() > 0.5;
  }

  randomFromArray(array) {
    return array[Math.floor(Math.random() * array.length)];
  }
}

const baseBuilder = new TestDataBuilder();
module.exports = { TestDataBuilder, baseBuilder };
