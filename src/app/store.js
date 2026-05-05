import { configureStore } from '@reduxjs/toolkit';
import formEditorReducer from '../features/formEditor/formEditorSlice';
import dataCollectorReducer from '../features/dataCollector/dataCollectorSlice';

export const store = configureStore({
  reducer: {
    formEditor: formEditorReducer,
    dataCollector: dataCollectorReducer,
  },
  middleware: (getDefaultMiddleware) =>
    getDefaultMiddleware({
      serializableCheck: false,
    }),
});

export default store;
