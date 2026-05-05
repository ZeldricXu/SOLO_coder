import { createSlice, createSelector } from '@reduxjs/toolkit';

const getTodayDateString = () => new Date().toDateString();

const isTodaySubmission = (submittedAt) => {
  if (!submittedAt) return false;
  const submitDate = new Date(submittedAt);
  return submitDate.toDateString() === getTodayDateString();
};

const getValueKey = (value) => {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'object') {
    if (Array.isArray(value)) {
      return value.map(v => getValueKey(v)).join(',');
    }
    return JSON.stringify(value);
  }
  return String(value);
};

const isValidNumericValue = (value) => {
  if (value === null || value === undefined || value === '') {
    return false;
  }
  const num = Number(value);
  return !isNaN(num) && isFinite(num);
};

const createInitialFieldStatistics = () => ({
  total: 0,
  valueCounts: {},
  sum: 0,
  validNumericCount: 0,
});

const getFieldStats = (state, fieldId) => {
  if (!state.statistics.fieldStatistics[fieldId]) {
    state.statistics.fieldStatistics[fieldId] = createInitialFieldStatistics();
  }
  return state.statistics.fieldStatistics[fieldId];
};

const updateFieldStats = (fieldStats, value) => {
  fieldStats.total++;

  if (value !== null && value !== undefined && value !== '') {
    const valueKey = getValueKey(value);
    if (!fieldStats.valueCounts[valueKey]) {
      fieldStats.valueCounts[valueKey] = 0;
    }
    fieldStats.valueCounts[valueKey]++;
  }

  if (isValidNumericValue(value)) {
    const numValue = Number(value);
    fieldStats.sum += numValue;
    fieldStats.validNumericCount++;
  }
};

const decrementFieldStats = (fieldStats, value) => {
  fieldStats.total--;
  if (fieldStats.total < 0) {
    fieldStats.total = 0;
  }

  if (value !== null && value !== undefined && value !== '') {
    const valueKey = getValueKey(value);
    if (fieldStats.valueCounts[valueKey]) {
      fieldStats.valueCounts[valueKey]--;
      if (fieldStats.valueCounts[valueKey] <= 0) {
        delete fieldStats.valueCounts[valueKey];
      }
    }
  }

  if (isValidNumericValue(value)) {
    const numValue = Number(value);
    fieldStats.sum -= numValue;
    if (fieldStats.sum < 0) {
      fieldStats.sum = 0;
    }
    fieldStats.validNumericCount--;
    if (fieldStats.validNumericCount < 0) {
      fieldStats.validNumericCount = 0;
    }
  }
};

const calculateAverage = (sum, count) => {
  if (count === 0) return null;
  return sum / count;
};

const initialState = {
  formId: null,
  submissions: [],
  loading: false,
  error: null,
  statistics: {
    totalSubmissions: 0,
    todaySubmissions: 0,
    lastSubmitTime: null,
    fieldStatistics: {},
  },
  _internal: {
    todayDate: getTodayDateString(),
  },
};

const dataCollectorSlice = createSlice({
  name: 'dataCollector',
  initialState,
  reducers: {
    setFormId: (state, action) => {
      state.formId = action.payload;
    },

    addSubmission: (state, action) => {
      const submission = action.payload;

      const today = getTodayDateString();
      if (state._internal.todayDate !== today) {
        state._internal.todayDate = today;
        let todayCount = 0;
        state.submissions.forEach(s => {
          if (isTodaySubmission(s.submitted_at)) {
            todayCount++;
          }
        });
        if (isTodaySubmission(submission.submitted_at)) {
          todayCount++;
        }
        state.statistics.todaySubmissions = todayCount;
      } else {
        if (isTodaySubmission(submission.submitted_at)) {
          state.statistics.todaySubmissions++;
        }
      }

      state.submissions.unshift(submission);
      state.statistics.totalSubmissions++;

      state.statistics.lastSubmitTime = submission.submitted_at;

      if (submission.data) {
        Object.entries(submission.data).forEach(([fieldId, value]) => {
          const fieldStats = getFieldStats(state, fieldId);
          updateFieldStats(fieldStats, value);
        });
      }
    },

    addSubmissions: (state, action) => {
      const newSubmissions = action.payload;
      if (!newSubmissions || newSubmissions.length === 0) return;

      let newTodayCount = 0;
      const today = getTodayDateString();

      newSubmissions.forEach(submission => {
        if (isTodaySubmission(submission.submitted_at)) {
          newTodayCount++;
        }
      });

      if (state._internal.todayDate !== today) {
        state._internal.todayDate = today;
        let existingTodayCount = 0;
        state.submissions.forEach(s => {
          if (isTodaySubmission(s.submitted_at)) {
            existingTodayCount++;
          }
        });
        state.statistics.todaySubmissions = existingTodayCount + newTodayCount;
      } else {
        state.statistics.todaySubmissions += newTodayCount;
      }

      state.submissions = [...newSubmissions, ...state.submissions];
      state.statistics.totalSubmissions = state.submissions.length;

      if (state.submissions.length > 0) {
        const latestNewSubmission = newSubmissions.reduce((latest, current) => {
          if (!latest) return current;
          return new Date(current.submitted_at) > new Date(latest.submitted_at)
            ? current : latest;
        }, null);

        if (latestNewSubmission) {
          const currentLatest = state.statistics.lastSubmitTime
            ? new Date(state.statistics.lastSubmitTime)
            : null;
          const newLatest = new Date(latestNewSubmission.submitted_at);

          if (!currentLatest || newLatest > currentLatest) {
            state.statistics.lastSubmitTime = latestNewSubmission.submitted_at;
          }
        }
      }

      newSubmissions.forEach(submission => {
        if (submission.data) {
          Object.entries(submission.data).forEach(([fieldId, value]) => {
            const fieldStats = getFieldStats(state, fieldId);
            updateFieldStats(fieldStats, value);
          });
        }
      });
    },

    updateSubmission: (state, action) => {
      const { submissionId, updates } = action.payload;
      const index = state.submissions.findIndex(s => s.submission_id === submissionId);
      if (index !== -1) {
        const oldSubmission = state.submissions[index];
        const oldData = oldSubmission.data;

        if (oldData) {
          Object.entries(oldData).forEach(([fieldId, value]) => {
            if (state.statistics.fieldStatistics[fieldId]) {
              const fieldStats = state.statistics.fieldStatistics[fieldId];
              decrementFieldStats(fieldStats, value);
            }
          });
        }

        state.submissions[index] = { ...state.submissions[index], ...updates };

        if (updates.data) {
          Object.entries(updates.data).forEach(([fieldId, value]) => {
            const fieldStats = getFieldStats(state, fieldId);
            updateFieldStats(fieldStats, value);
          });
        }
      }
    },

    deleteSubmission: (state, action) => {
      const submissionId = action.payload;
      const index = state.submissions.findIndex(s => s.submission_id === submissionId);
      if (index !== -1) {
        const submission = state.submissions[index];

        if (isTodaySubmission(submission.submitted_at)) {
          state.statistics.todaySubmissions--;
          if (state.statistics.todaySubmissions < 0) {
            state.statistics.todaySubmissions = 0;
          }
        }

        if (submission.data) {
          Object.entries(submission.data).forEach(([fieldId, value]) => {
            if (state.statistics.fieldStatistics[fieldId]) {
              const fieldStats = state.statistics.fieldStatistics[fieldId];
              decrementFieldStats(fieldStats, value);
            }
          });
        }

        state.submissions.splice(index, 1);
        state.statistics.totalSubmissions = state.submissions.length;

        if (state.submissions.length > 0) {
          state.statistics.lastSubmitTime = state.submissions[0].submitted_at;
        } else {
          state.statistics.lastSubmitTime = null;
        }
      }
    },

    clearSubmissions: (state) => {
      state.submissions = [];
      state.statistics = {
        totalSubmissions: 0,
        todaySubmissions: 0,
        lastSubmitTime: null,
        fieldStatistics: {},
      };
    },

    setLoading: (state, action) => {
      state.loading = action.payload;
    },

    setError: (state, action) => {
      state.error = action.payload;
      state.loading = false;
    },

    reset: (state) => {
      return initialState;
    },

    refreshTodayStatistics: (state) => {
      const today = getTodayDateString();
      state._internal.todayDate = today;

      let todayCount = 0;
      state.submissions.forEach(s => {
        if (isTodaySubmission(s.submitted_at)) {
          todayCount++;
        }
      });
      state.statistics.todaySubmissions = todayCount;
    },

    recalculateFieldStatistics: (state) => {
      state.statistics.fieldStatistics = {};

      state.submissions.forEach(submission => {
        if (submission.data) {
          Object.entries(submission.data).forEach(([fieldId, value]) => {
            const fieldStats = getFieldStats(state, fieldId);
            updateFieldStats(fieldStats, value);
          });
        }
      });
    },
  },
});

export const {
  setFormId,
  addSubmission,
  addSubmissions,
  updateSubmission,
  deleteSubmission,
  clearSubmissions,
  setLoading,
  setError,
  reset,
  refreshTodayStatistics,
  recalculateFieldStatistics,
} = dataCollectorSlice.actions;

export const selectSubmissions = (state) => state.dataCollector.submissions;
export const selectTotalSubmissions = (state) => state.dataCollector.statistics.totalSubmissions;
export const selectTodaySubmissions = (state) => state.dataCollector.statistics.todaySubmissions;
export const selectLastSubmitTime = (state) => state.dataCollector.statistics.lastSubmitTime;
export const selectFieldStatistics = (state) => state.dataCollector.statistics.fieldStatistics;
export const selectLoading = (state) => state.dataCollector.loading;
export const selectError = (state) => state.dataCollector.error;
export const selectFormId = (state) => state.dataCollector.formId;

export const selectRecentSubmissions = createSelector(
  [selectSubmissions],
  (submissions) => submissions.slice(0, 10)
);

export const selectSubmissionById = createSelector(
  [selectSubmissions, (_, submissionId) => submissionId],
  (submissions, submissionId) => submissions.find(s => s.submission_id === submissionId)
);

export const selectFieldValueCounts = createSelector(
  [selectFieldStatistics, (_, fieldId) => fieldId],
  (fieldStatistics, fieldId) => {
    const stats = fieldStatistics[fieldId];
    if (!stats || !stats.valueCounts) return {};

    return { ...stats.valueCounts };
  }
);

export const selectFieldTotal = createSelector(
  [selectFieldStatistics, (_, fieldId) => fieldId],
  (fieldStatistics, fieldId) => {
    const stats = fieldStatistics[fieldId];
    return stats?.total || 0;
  }
);

export const selectFieldSum = createSelector(
  [selectFieldStatistics, (_, fieldId) => fieldId],
  (fieldStatistics, fieldId) => {
    const stats = fieldStatistics[fieldId];
    return stats?.sum ?? 0;
  }
);

export const selectFieldValidNumericCount = createSelector(
  [selectFieldStatistics, (_, fieldId) => fieldId],
  (fieldStatistics, fieldId) => {
    const stats = fieldStatistics[fieldId];
    return stats?.validNumericCount ?? 0;
  }
);

export const selectFieldAverage = createSelector(
  [selectFieldStatistics, (_, fieldId) => fieldId],
  (fieldStatistics, fieldId) => {
    const stats = fieldStatistics[fieldId];
    if (!stats) return null;

    const { sum, validNumericCount } = stats;
    return calculateAverage(sum, validNumericCount);
  }
);

export const selectFieldStats = createSelector(
  [selectFieldStatistics, (_, fieldId) => fieldId],
  (fieldStatistics, fieldId) => {
    const stats = fieldStatistics[fieldId];
    if (!stats) {
      return {
        total: 0,
        valueCounts: {},
        sum: 0,
        validNumericCount: 0,
        average: null,
      };
    }

    return {
      total: stats.total,
      valueCounts: { ...stats.valueCounts },
      sum: stats.sum,
      validNumericCount: stats.validNumericCount,
      average: calculateAverage(stats.sum, stats.validNumericCount),
    };
  }
);

export const selectAllFieldAverages = createSelector(
  [selectFieldStatistics],
  (fieldStatistics) => {
    const result = {};
    Object.entries(fieldStatistics).forEach(([fieldId, stats]) => {
      result[fieldId] = {
        sum: stats.sum,
        validNumericCount: stats.validNumericCount,
        average: calculateAverage(stats.sum, stats.validNumericCount),
      };
    });
    return result;
  }
);

export { isValidNumericValue, calculateAverage };

export default dataCollectorSlice.reducer;
