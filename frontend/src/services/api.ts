import axios from 'axios';
import type {
  ApiResponse,
  ImportResult,
  SurveyData,
  SurveyPreview,
  StatisticsData,
  FrequencyResult,
  DescriptiveStats,
  CrossAnalysisResult,
  Report,
  ReportPreview
} from '../types';

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 60000,
  headers: {
    'Content-Type': 'application/json'
  }
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    console.error('API Error:', error);
    return Promise.reject(error);
  }
);

export const surveyApi = {
  import: async (file: File, surveyName?: string): Promise<ImportResult> => {
    const formData = new FormData();
    formData.append('file', file);
    if (surveyName) {
      formData.append('survey_name', surveyName);
    }
    
    const response = await api.post<ApiResponse<ImportResult>>('/survey/import', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    });
    
    if (response.data.code !== 200) {
      throw new Error(response.data.message || 'Import failed');
    }
    
    return response.data.data!;
  },

  get: async (surveyId: string): Promise<SurveyData> => {
    const response = await api.get<ApiResponse<SurveyData>>(`/survey/${surveyId}`);
    
    if (response.data.code !== 200) {
      throw new Error(response.data.message || 'Failed to get survey');
    }
    
    return response.data.data!;
  },

  getPreview: async (surveyId: string, rows?: number): Promise<SurveyPreview> => {
    const params = rows ? { rows } : {};
    const response = await api.get<ApiResponse<SurveyPreview>>(`/survey/${surveyId}/preview`, { params });
    
    if (response.data.code !== 200) {
      throw new Error(response.data.message || 'Failed to get preview');
    }
    
    return response.data.data!;
  },

  updateFieldMapping: async (surveyId: string, fieldMappings: {
    field_id: string;
    field_name: string;
    field_type: string;
    source_column: string;
    options?: string[];
    range?: number[];
  }[]): Promise<ImportResult> => {
    const response = await api.put<ApiResponse<ImportResult>>(`/survey/${surveyId}/fields`, {
      field_mappings: fieldMappings
    });
    
    if (response.data.code !== 200) {
      throw new Error(response.data.message || 'Failed to update field mapping');
    }
    
    return response.data.data!;
  }
};

export const analysisApi = {
  getStatistics: async (surveyId: string): Promise<StatisticsData> => {
    const response = await api.get<ApiResponse<StatisticsData>>(`/analysis/statistics/${surveyId}`);
    
    if (response.data.code !== 200) {
      throw new Error(response.data.message || 'Failed to get statistics');
    }
    
    return response.data.data!;
  },

  getFrequency: async (surveyId: string, fieldId: string): Promise<FrequencyResult> => {
    const response = await api.get<ApiResponse<FrequencyResult>>(`/analysis/frequency/${surveyId}/${fieldId}`);
    
    if (response.data.code !== 200) {
      throw new Error(response.data.message || 'Failed to get frequency');
    }
    
    return response.data.data!;
  },

  getDescriptive: async (surveyId: string, fieldId: string): Promise<DescriptiveStats> => {
    const response = await api.get<ApiResponse<DescriptiveStats>>(`/analysis/descriptive/${surveyId}/${fieldId}`);
    
    if (response.data.code !== 200) {
      throw new Error(response.data.message || 'Failed to get descriptive stats');
    }
    
    return response.data.data!;
  },

  performCrossAnalysis: async (params: {
    survey_id: string;
    variables: string[];
    analysis_type?: string;
  }): Promise<CrossAnalysisResult> => {
    const response = await api.post<ApiResponse<CrossAnalysisResult>>('/analysis/cross', params);
    
    if (response.data.code !== 200) {
      throw new Error(response.data.message || 'Failed to perform cross analysis');
    }
    
    return response.data.data!;
  },

  getCrossAnalysis: async (analysisId: string): Promise<CrossAnalysisResult> => {
    const response = await api.get<ApiResponse<CrossAnalysisResult>>(`/analysis/cross/${analysisId}`);
    
    if (response.data.code !== 200) {
      throw new Error(response.data.message || 'Failed to get cross analysis');
    }
    
    return response.data.data!;
  },

  getSurveyCrossAnalyses: async (surveyId: string): Promise<CrossAnalysisResult[]> => {
    const response = await api.get<ApiResponse<CrossAnalysisResult[]>>(`/analysis/cross/survey/${surveyId}`);
    
    if (response.data.code !== 200) {
      throw new Error(response.data.message || 'Failed to get cross analyses');
    }
    
    return response.data.data!;
  }
};

export const reportApi = {
  generate: async (surveyId: string, title?: string): Promise<Report> => {
    const response = await api.post<ApiResponse<Report>>('/report/generate', {
      survey_id: surveyId,
      title
    });
    
    if (response.data.code !== 200) {
      throw new Error(response.data.message || 'Failed to generate report');
    }
    
    return response.data.data!;
  },

  get: async (reportId: string): Promise<Report> => {
    const response = await api.get<ApiResponse<Report>>(`/report/${reportId}`);
    
    if (response.data.code !== 200) {
      throw new Error(response.data.message || 'Failed to get report');
    }
    
    return response.data.data!;
  },

  getPreview: async (reportId: string): Promise<ReportPreview> => {
    const response = await api.get<ApiResponse<ReportPreview>>(`/report/${reportId}/preview`);
    
    if (response.data.code !== 200) {
      throw new Error(response.data.message || 'Failed to get report preview');
    }
    
    return response.data.data!;
  },

  export: async (reportId: string, format: 'word' | 'pdf'): Promise<Blob> => {
    const response = await api.get(`/report/${reportId}/export/${format}`, {
      responseType: 'blob'
    });
    
    return response.data;
  }
};

export { api };
