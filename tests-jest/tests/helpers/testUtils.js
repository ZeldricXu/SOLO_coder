const validateResponseStructure = (response, expectedCode = 200) => {
  expect(response.status).toBe(expectedCode);
  expect(response.body).toHaveProperty('code');
  expect(response.body).toHaveProperty('message');
  expect(response.body).toHaveProperty('data');
};

const validateErrorResponse = (response, expectedStatusCode, expectedMessagePattern) => {
  expect(response.status).toBe(expectedStatusCode);
  if (expectedMessagePattern) {
    expect(response.body.message || response.body.error).toMatch(expectedMessagePattern);
  }
};

const measureExecutionTime = async (fn) => {
  const start = Date.now();
  const result = await fn();
  const duration = Date.now() - start;
  return { result, durationMs: duration };
};

const retryOperation = async (operation, maxRetries = 3, delayMs = 1000) => {
  let lastError;
  for (let attempt = 0; attempt < maxRetries; attempt += 1) {
    try {
      return await operation();
    } catch (error) {
      lastError = error;
      if (attempt < maxRetries - 1) {
        await new Promise(resolve => setTimeout(resolve, delayMs));
      }
    }
  }
  throw lastError;
};

const isServiceAvailable = async (apiClient) => {
  try {
    const response = await apiClient.health.check();
    return response.status === 200;
  } catch (error) {
    return false;
  }
};

const skipIfServiceUnavailable = (apiClient) => {
  const checkAvailability = async () => {
    const available = await isServiceAvailable(apiClient);
    if (!available) {
      console.warn('⚠️  API服务不可用，跳过测试');
      return true;
    }
    return false;
  };
  return { checkAvailability };
};

const createRandomId = (prefix = 'id') => {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
};

const generateEmail = () => {
  return `test_${Date.now()}@example.com`;
};

const deepClone = (obj) => {
  return JSON.parse(JSON.stringify(obj));
};

const omitFields = (obj, fields) => {
  const result = { ...obj };
  fields.forEach(field => delete result[field]);
  return result;
};

const pickFields = (obj, fields) => {
  const result = {};
  fields.forEach(field => {
    if (Object.prototype.hasOwnProperty.call(obj, field)) {
      result[field] = obj[field];
    }
  });
  return result;
};

const waitForCondition = async (conditionFn, timeoutMs = 10000, intervalMs = 100) => {
  const startTime = Date.now();
  while (Date.now() - startTime < timeoutMs) {
    if (await conditionFn()) {
      return true;
    }
    await new Promise(resolve => setTimeout(resolve, intervalMs));
  }
  return false;
};

const compareObjects = (actual, expected) => {
  const differences = [];
  const compare = (a, b, path = '') => {
    if (typeof a !== typeof b) {
      differences.push({ path, expected: typeof b, actual: typeof a });
      return;
    }
    if (typeof a === 'object' && a !== null && b !== null) {
      const allKeys = new Set([...Object.keys(a), ...Object.keys(b)]);
      allKeys.forEach(key => {
        compare(a[key], b[key], path ? `${path}.${key}` : key);
      });
    } else if (a !== b) {
      differences.push({ path, expected: b, actual: a });
    }
  };
  compare(actual, expected);
  return { match: differences.length === 0, differences };
};

const expectToBeBetween = (value, min, max) => {
  expect(value).toBeGreaterThanOrEqual(min);
  expect(value).toBeLessThanOrEqual(max);
};

const expectArrayToHaveUniqueValues = (array) => {
  const unique = [...new Set(array)];
  expect(array.length).toBe(unique.length);
};

const expectArrayToContainAll = (array, expectedValues) => {
  expectedValues.forEach(value => {
    expect(array).toContain(value);
  });
};

module.exports = {
  validateResponseStructure,
  validateErrorResponse,
  measureExecutionTime,
  retryOperation,
  isServiceAvailable,
  skipIfServiceUnavailable,
  createRandomId,
  generateEmail,
  deepClone,
  omitFields,
  pickFields,
  waitForCondition,
  compareObjects,
  expectToBeBetween,
  expectArrayToHaveUniqueValues,
  expectArrayToContainAll,
};
