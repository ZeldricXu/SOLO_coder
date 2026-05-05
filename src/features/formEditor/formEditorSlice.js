import { createSlice, createAction } from '@reduxjs/toolkit';
import { COMPONENT_CONFIGS } from '../componentLibrary';

const generateId = () => `comp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;

const generateStepId = () => `step_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;

const initialFormConfig = {
  form_id: '',
  form_name: '未命名表单',
  form_type: 'single_step',
  description: '',
  components: [],
  steps: [
    {
      step_id: generateStepId(),
      step_title: '第一步',
      step_description: '',
      components: [],
    },
  ],
  submit_config: {
    submit_button_text: '提交',
    success_message: '感谢您的参与',
  },
  publish_config: {
    is_published: false,
    publish_url: '',
  },
};

const initialState = {
  formConfig: initialFormConfig,
  selectedComponentId: null,
  currentStepId: null,
  isPreviewMode: false,
  isDirty: false,
  saving: false,
  saved: false,
  error: null,
};

const formEditorSlice = createSlice({
  name: 'formEditor',
  initialState,
  reducers: {
    resetForm: (state) => {
      state.formConfig = {
        ...initialFormConfig,
        steps: [
          {
            step_id: generateStepId(),
            step_title: '第一步',
            step_description: '',
            components: [],
          },
        ],
      };
      state.selectedComponentId = null;
      state.currentStepId = state.formConfig.steps[0].step_id;
      state.isDirty = false;
      state.saved = false;
      state.error = null;
    },

    loadForm: (state, action) => {
      state.formConfig = action.payload;
      state.selectedComponentId = null;
      if (action.payload.form_type === 'multi_step' && action.payload.steps?.length > 0) {
        state.currentStepId = action.payload.steps[0].step_id;
      }
      state.isDirty = false;
      state.saved = false;
    },

    updateFormConfig: (state, action) => {
      state.formConfig = { ...state.formConfig, ...action.payload };
      state.isDirty = true;
    },

    setFormType: (state, action) => {
      const formType = action.payload;
      state.formConfig.form_type = formType;

      if (formType === 'multi_step' && (!state.formConfig.steps || state.formConfig.steps.length === 0)) {
        const components = [...(state.formConfig.components || [])];
        state.formConfig.steps = [
          {
            step_id: generateStepId(),
            step_title: '第一步',
            step_description: '',
            components,
          },
        ];
        state.formConfig.components = [];
        state.currentStepId = state.formConfig.steps[0].step_id;
      } else if (formType === 'single_step') {
        const allComponents = [];
        (state.formConfig.steps || []).forEach(step => {
          allComponents.push(...(step.components || []));
        });
        state.formConfig.components = allComponents;
        state.formConfig.steps = [
          {
            step_id: generateStepId(),
            step_title: '第一步',
            step_description: '',
            components: [],
          },
        ];
        state.currentStepId = null;
      }

      state.isDirty = true;
    },

    setCurrentStep: (state, action) => {
      state.currentStepId = action.payload;
      state.selectedComponentId = null;
    },

    addStep: (state, action) => {
      const newStep = {
        step_id: generateStepId(),
        step_title: action.payload?.title || `第${(state.formConfig.steps?.length || 0) + 1}步`,
        step_description: action.payload?.description || '',
        components: [],
      };

      if (!state.formConfig.steps) {
        state.formConfig.steps = [];
      }

      state.formConfig.steps.push(newStep);
      state.currentStepId = newStep.step_id;
      state.isDirty = true;
    },

    updateStep: (state, action) => {
      const { stepId, updates } = action.payload;
      const steps = state.formConfig.steps || [];
      const stepIndex = steps.findIndex(s => s.step_id === stepId);
      if (stepIndex !== -1) {
        steps[stepIndex] = { ...steps[stepIndex], ...updates };
        state.isDirty = true;
      }
    },

    deleteStep: (state, action) => {
      const stepId = action.payload;
      const steps = state.formConfig.steps || [];
      if (steps.length <= 1) {
        return;
      }

      const stepIndex = steps.findIndex(s => s.step_id === stepId);
      if (stepIndex !== -1) {
        steps.splice(stepIndex, 1);
        if (state.currentStepId === stepId) {
          state.currentStepId = steps[Math.max(0, stepIndex - 1)]?.step_id || null;
        }
        state.selectedComponentId = null;
        state.isDirty = true;
      }
    },

    moveStep: (state, action) => {
      const { fromIndex, toIndex } = action.payload;
      const steps = state.formConfig.steps || [];
      if (fromIndex === toIndex || fromIndex < 0 || toIndex >= steps.length) {
        return;
      }

      const [removed] = steps.splice(fromIndex, 1);
      steps.splice(toIndex, 0, removed);
      state.isDirty = true;
    },

    addComponent: (state, action) => {
      const { componentType, stepId } = action.payload;
      const componentConfig = COMPONENT_CONFIGS[componentType];

      if (!componentConfig) {
        return;
      }

      const newComponent = {
        ...componentConfig.defaultConfig,
        component_id: generateId(),
        label: componentConfig.defaultConfig.label,
      };

      if (state.formConfig.form_type === 'multi_step' && stepId) {
        const step = (state.formConfig.steps || []).find(s => s.step_id === stepId);
        if (step) {
          if (!step.components) {
            step.components = [];
          }
          step.components.push(newComponent);
        }
      } else {
        if (!state.formConfig.components) {
          state.formConfig.components = [];
        }
        state.formConfig.components.push(newComponent);
      }

      state.selectedComponentId = newComponent.component_id;
      state.isDirty = true;
    },

    updateComponent: (state, action) => {
      const { componentId, updates } = action.payload;

      const updateInList = (components) => {
        const index = components.findIndex(c => c.component_id === componentId);
        if (index !== -1) {
          components[index] = { ...components[index], ...updates };
          return true;
        }
        return false;
      };

      let found = false;

      if (state.formConfig.form_type === 'multi_step') {
        (state.formConfig.steps || []).forEach(step => {
          if (step.components && updateInList(step.components)) {
            found = true;
          }
        });
      } else {
        if (state.formConfig.components) {
          found = updateInList(state.formConfig.components);
        }
      }

      if (found) {
        state.isDirty = true;
      }
    },

    deleteComponent: (state, action) => {
      const componentId = action.payload;

      const deleteFromList = (components) => {
        const index = components.findIndex(c => c.component_id === componentId);
        if (index !== -1) {
          components.splice(index, 1);
          return true;
        }
        return false;
      };

      let found = false;

      if (state.formConfig.form_type === 'multi_step') {
        (state.formConfig.steps || []).forEach(step => {
          if (step.components && deleteFromList(step.components)) {
            found = true;
          }
        });
      } else {
        if (state.formConfig.components) {
          found = deleteFromList(state.formConfig.components);
        }
      }

      if (found) {
        if (state.selectedComponentId === componentId) {
          state.selectedComponentId = null;
        }
        state.isDirty = true;
      }
    },

    moveComponent: (state, action) => {
      const { fromIndex, toIndex, stepId } = action.payload;

      const moveInList = (components) => {
        if (fromIndex === toIndex || fromIndex < 0 || toIndex >= components.length) {
          return false;
        }
        const [removed] = components.splice(fromIndex, 1);
        components.splice(toIndex, 0, removed);
        return true;
      };

      let moved = false;

      if (state.formConfig.form_type === 'multi_step' && stepId) {
        const step = (state.formConfig.steps || []).find(s => s.step_id === stepId);
        if (step?.components) {
          moved = moveInList(step.components);
        }
      } else {
        if (state.formConfig.components) {
          moved = moveInList(state.formConfig.components);
        }
      }

      if (moved) {
        state.isDirty = true;
      }
    },

    selectComponent: (state, action) => {
      state.selectedComponentId = action.payload;
    },

    clearSelection: (state) => {
      state.selectedComponentId = null;
    },

    setPreviewMode: (state, action) => {
      state.isPreviewMode = action.payload;
      if (action.payload) {
        state.selectedComponentId = null;
      }
    },

    saveStart: (state) => {
      state.saving = true;
      state.error = null;
    },

    saveSuccess: (state, action) => {
      state.saving = false;
      state.saved = true;
      state.isDirty = false;
      if (action.payload?.form_id) {
        state.formConfig.form_id = action.payload.form_id;
      }
      if (action.payload?.publish_url) {
        state.formConfig.publish_config = {
          ...state.formConfig.publish_config,
          publish_url: action.payload.publish_url,
        };
      }
    },

    saveFailure: (state, action) => {
      state.saving = false;
      state.error = action.payload;
    },

    publishForm: (state, action) => {
      state.formConfig.publish_config = {
        is_published: true,
        publish_url: action.payload?.publish_url || '',
      };
      state.isDirty = true;
    },

    unpublishForm: (state) => {
      state.formConfig.publish_config = {
        ...state.formConfig.publish_config,
        is_published: false,
      };
      state.isDirty = true;
    },
  },
});

export const {
  resetForm,
  loadForm,
  updateFormConfig,
  setFormType,
  setCurrentStep,
  addStep,
  updateStep,
  deleteStep,
  moveStep,
  addComponent,
  updateComponent,
  deleteComponent,
  moveComponent,
  selectComponent,
  clearSelection,
  setPreviewMode,
  saveStart,
  saveSuccess,
  saveFailure,
  publishForm,
  unpublishForm,
} = formEditorSlice.actions;

export const selectFormConfig = (state) => state.formEditor.formConfig;
export const selectSelectedComponentId = (state) => state.formEditor.selectedComponentId;
export const selectCurrentStepId = (state) => state.formEditor.currentStepId;
export const selectIsPreviewMode = (state) => state.formEditor.isPreviewMode;
export const selectIsDirty = (state) => state.formEditor.isDirty;
export const selectSaving = (state) => state.formEditor.saving;
export const selectSaved = (state) => state.formEditor.saved;
export const selectError = (state) => state.formEditor.error;

export const selectSelectedComponent = (state) => {
  const { formConfig, selectedComponentId } = state.formEditor;
  if (!selectedComponentId) return null;

  const findInList = (components) => {
    return components?.find(c => c.component_id === selectedComponentId) || null;
  };

  if (formConfig.form_type === 'multi_step') {
    for (const step of formConfig.steps || []) {
      const found = findInList(step.components);
      if (found) return found;
    }
  } else {
    return findInList(formConfig.components);
  }

  return null;
};

export const selectCurrentStepComponents = (state) => {
  const { formConfig, currentStepId } = state.formEditor;

  if (formConfig.form_type === 'multi_step') {
    const step = (formConfig.steps || []).find(s => s.step_id === currentStepId);
    return step?.components || [];
  }

  return formConfig.components || [];
};

export default formEditorSlice.reducer;
