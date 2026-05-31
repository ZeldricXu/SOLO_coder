/**
 * Jest Custom Matchers for API Testing
 */

expect.extend({
  toBeSuccessful(response) {
    const pass = response.status >= 200 && response.status < 300;
    return {
      pass,
      message: () =>
        pass
          ? `expected status not to be successful, got ${response.status}`
          : `expected status to be successful (2xx), got ${response.status}`,
    };
  },

  toBeBadRequest(response) {
    const pass = response.status === 400;
    return {
      pass,
      message: () =>
        pass
          ? `expected status not to be 400, got ${response.status}`
          : `expected status to be 400, got ${response.status}`,
    };
  },

  toBeNotFound(response) {
    const pass = response.status === 404;
    return {
      pass,
      message: () =>
        pass
          ? `expected status not to be 404, got ${response.status}`
          : `expected status to be 404, got ${response.status}`,
    };
  },

  toHaveValidResponseStructure(response) {
    const hasCode = response.data && typeof response.data.code === 'number';
    const hasData = response.data && 'data' in response.data;
    const pass = hasCode && hasData;
    return {
      pass,
      message: () =>
        pass
          ? 'expected response not to have valid structure'
          : 'expected response to have code and data fields',
    };
  },

  toHavePagination(data) {
    const pass =
      data &&
      Array.isArray(data.items) &&
      typeof data.total === 'number' &&
      typeof data.page === 'number' &&
      typeof data.size === 'number';
    return {
      pass,
      message: () =>
        pass
          ? 'expected data not to have pagination structure'
          : 'expected data to have items, total, page, size',
    };
  },

  toBeWithinRange(actual, min, max) {
    const pass = actual >= min && actual <= max;
    return {
      pass,
      message: () =>
        pass
          ? `expected ${actual} not to be within range ${min}-${max}`
          : `expected ${actual} to be within range ${min}-${max}`,
    };
  },
});

module.exports = {};
