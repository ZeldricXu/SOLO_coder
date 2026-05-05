import React, { createContext, useContext, useReducer, useCallback } from 'react';

const FlowContext = createContext(null);

const FLOW_ACTION = {
  INITIALIZE: 'INITIALIZE',
  GO_TO_STEP: 'GO_TO_STEP',
  NEXT_STEP: 'NEXT_STEP',
  PREVIOUS_STEP: 'PREVIOUS_STEP',
  GO_TO_FIRST_STEP: 'GO_TO_FIRST_STEP',
  GO_TO_LAST_STEP: 'GO_TO_LAST_STEP',
  SET_VISITED_STEPS: 'SET_VISITED_STEPS',
  SET_STEP_DATA: 'SET_STEP_DATA',
  RESET: 'RESET',
};

const initialFlowState = {
  steps: [],
  currentStepIndex: 0,
  currentStepId: null,
  visitedSteps: [],
  stepData: {},
  isInitialized: false,
};

const flowReducer = (state, action) => {
  switch (action.type) {
    case FLOW_ACTION.INITIALIZE: {
      const { steps } = action.payload;
      const firstStep = steps[0];
      return {
        ...state,
        steps,
        currentStepIndex: 0,
        currentStepId: firstStep?.step_id || null,
        visitedSteps: firstStep ? [firstStep.step_id] : [],
        stepData: {},
        isInitialized: true,
      };
    }

    case FLOW_ACTION.GO_TO_STEP: {
      const { stepId } = action.payload;
      const stepIndex = state.steps.findIndex((s) => s.step_id === stepId);
      if (stepIndex === -1) return state;

      const step = state.steps[stepIndex];
      const newVisitedSteps = state.visitedSteps.includes(stepId)
        ? state.visitedSteps
        : [...state.visitedSteps, stepId];

      return {
        ...state,
        currentStepIndex: stepIndex,
        currentStepId: stepId,
        visitedSteps: newVisitedSteps,
      };
    }

    case FLOW_ACTION.NEXT_STEP: {
      if (state.currentStepIndex >= state.steps.length - 1) {
        return state;
      }

      const nextIndex = state.currentStepIndex + 1;
      const nextStep = state.steps[nextIndex];
      const newVisitedSteps = state.visitedSteps.includes(nextStep.step_id)
        ? state.visitedSteps
        : [...state.visitedSteps, nextStep.step_id];

      return {
        ...state,
        currentStepIndex: nextIndex,
        currentStepId: nextStep.step_id,
        visitedSteps: newVisitedSteps,
      };
    }

    case FLOW_ACTION.PREVIOUS_STEP: {
      if (state.currentStepIndex <= 0) {
        return state;
      }

      const prevIndex = state.currentStepIndex - 1;
      const prevStep = state.steps[prevIndex];

      return {
        ...state,
        currentStepIndex: prevIndex,
        currentStepId: prevStep.step_id,
      };
    }

    case FLOW_ACTION.GO_TO_FIRST_STEP: {
      if (state.steps.length === 0) return state;
      const firstStep = state.steps[0];
      return {
        ...state,
        currentStepIndex: 0,
        currentStepId: firstStep.step_id,
      };
    }

    case FLOW_ACTION.GO_TO_LAST_STEP: {
      if (state.steps.length === 0) return state;
      const lastIndex = state.steps.length - 1;
      const lastStep = state.steps[lastIndex];
      const newVisitedSteps = state.visitedSteps.includes(lastStep.step_id)
        ? state.visitedSteps
        : [...state.visitedSteps, lastStep.step_id];

      return {
        ...state,
        currentStepIndex: lastIndex,
        currentStepId: lastStep.step_id,
        visitedSteps: newVisitedSteps,
      };
    }

    case FLOW_ACTION.SET_STEP_DATA: {
      const { stepId, data } = action.payload;
      return {
        ...state,
        stepData: {
          ...state.stepData,
          [stepId]: {
            ...state.stepData[stepId],
            ...data,
          },
        },
      };
    }

    case FLOW_ACTION.RESET: {
      return initialFlowState;
    }

    default:
      return state;
  }
};

export const FlowProvider = ({ children, initialSteps }) => {
  const [state, dispatch] = useReducer(flowReducer, initialFlowState);

  const initialize = useCallback((steps) => {
    dispatch({
      type: FLOW_ACTION.INITIALIZE,
      payload: { steps },
    });
  }, []);

  const goToStep = useCallback((stepId) => {
    dispatch({
      type: FLOW_ACTION.GO_TO_STEP,
      payload: { stepId },
    });
  }, []);

  const nextStep = useCallback(() => {
    dispatch({
      type: FLOW_ACTION.NEXT_STEP,
    });
  }, []);

  const previousStep = useCallback(() => {
    dispatch({
      type: FLOW_ACTION.PREVIOUS_STEP,
    });
  }, []);

  const goToFirstStep = useCallback(() => {
    dispatch({
      type: FLOW_ACTION.GO_TO_FIRST_STEP,
    });
  }, []);

  const goToLastStep = useCallback(() => {
    dispatch({
      type: FLOW_ACTION.GO_TO_LAST_STEP,
    });
  }, []);

  const setStepData = useCallback((stepId, data) => {
    dispatch({
      type: FLOW_ACTION.SET_STEP_DATA,
      payload: { stepId, data },
    });
  }, []);

  const reset = useCallback(() => {
    dispatch({
      type: FLOW_ACTION.RESET,
    });
  }, []);

  const canGoNext = state.currentStepIndex < state.steps.length - 1;
  const canGoPrevious = state.currentStepIndex > 0;
  const isFirstStep = state.currentStepIndex === 0;
  const isLastStep = state.currentStepIndex === state.steps.length - 1;
  const currentStep = state.steps[state.currentStepIndex] || null;

  const value = {
    ...state,
    currentStep,
    canGoNext,
    canGoPrevious,
    isFirstStep,
    isLastStep,
    initialize,
    goToStep,
    nextStep,
    previousStep,
    goToFirstStep,
    goToLastStep,
    setStepData,
    reset,
  };

  return <FlowContext.Provider value={value}>{children}</FlowContext.Provider>;
};

export const useFlowController = () => {
  const context = useContext(FlowContext);
  if (!context) {
    throw new Error('useFlowController must be used within a FlowProvider');
  }
  return context;
};

class FlowController {
  constructor(steps = []) {
    this.steps = steps;
    this.currentStepIndex = 0;
    this.visitedSteps = steps[0] ? [steps[0].step_id] : [];
    this.stepData = {};
  }

  getCurrentStep() {
    return this.steps[this.currentStepIndex] || null;
  }

  getCurrentStepId() {
    return this.getCurrentStep()?.step_id || null;
  }

  getStepById(stepId) {
    return this.steps.find((s) => s.step_id === stepId);
  }

  goToStep(stepId) {
    const stepIndex = this.steps.findIndex((s) => s.step_id === stepId);
    if (stepIndex === -1) return false;

    this.currentStepIndex = stepIndex;
    if (!this.visitedSteps.includes(stepId)) {
      this.visitedSteps.push(stepId);
    }
    return true;
  }

  nextStep() {
    if (this.currentStepIndex >= this.steps.length - 1) {
      return false;
    }
    this.currentStepIndex++;
    const stepId = this.getCurrentStepId();
    if (!this.visitedSteps.includes(stepId)) {
      this.visitedSteps.push(stepId);
    }
    return true;
  }

  previousStep() {
    if (this.currentStepIndex <= 0) {
      return false;
    }
    this.currentStepIndex--;
    return true;
  }

  canGoNext() {
    return this.currentStepIndex < this.steps.length - 1;
  }

  canGoPrevious() {
    return this.currentStepIndex > 0;
  }

  isFirstStep() {
    return this.currentStepIndex === 0;
  }

  isLastStep() {
    return this.currentStepIndex === this.steps.length - 1;
  }

  setStepData(stepId, data) {
    this.stepData[stepId] = {
      ...this.stepData[stepId],
      ...data,
    };
  }

  getStepData(stepId) {
    return this.stepData[stepId] || {};
  }

  getAllData() {
    const allData = {};
    Object.values(this.stepData).forEach((stepData) => {
      Object.assign(allData, stepData);
    });
    return allData;
  }

  reset() {
    this.currentStepIndex = 0;
    this.visitedSteps = this.steps[0] ? [this.steps[0].step_id] : [];
    this.stepData = {};
  }
}

export { FlowController };
