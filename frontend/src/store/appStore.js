import { create } from 'zustand';

const useAppStore = create((set, get) => ({
  currentCommit: null,
  commits: [],
  selectedFile: null,
  files: [],
  
  analysisResults: {
    complexity: null,
    lint: null,
    duplicate: null
  },
  
  comments: [],
  tasks: [],
  
  reports: [],
  trendData: [],
  
  loading: false,
  error: null,
  
  setCurrentCommit: (commit) => set({ currentCommit: commit }),
  
  setCommits: (commits) => set({ commits }),
  
  setSelectedFile: (file) => set({ selectedFile: file }),
  
  setFiles: (files) => set({ files }),
  
  setAnalysisResults: (results) => set((state) => ({
    analysisResults: { ...state.analysisResults, ...results }
  })),
  
  setComments: (comments) => set({ comments }),
  
  addComment: (comment) => set((state) => ({
    comments: [...state.comments, comment]
  })),
  
  updateComment: (comment_id, updates) => set((state) => ({
    comments: state.comments.map(c => 
      c.comment_id === comment_id ? { ...c, ...updates } : c
    )
  })),
  
  setTasks: (tasks) => set({ tasks }),
  
  setReports: (reports) => set({ reports }),
  
  setTrendData: (trendData) => set({ trendData }),
  
  setLoading: (loading) => set({ loading }),
  
  setError: (error) => set({ error }),
  
  clearError: () => set({ error: null }),
  
  resetState: () => set({
    currentCommit: null,
    selectedFile: null,
    analysisResults: {
      complexity: null,
      lint: null,
      duplicate: null
    },
    comments: [],
    error: null
  }),
  
  getOverallScore: () => {
    const { analysisResults } = get();
    const { complexity, lint, duplicate } = analysisResults;
    
    if (!complexity?.overall_score && !lint?.score && !duplicate?.score) {
      return null;
    }
    
    let total = 0;
    let count = 0;
    
    if (complexity?.overall_score !== undefined) {
      total += complexity.overall_score * 0.4;
      count += 0.4;
    }
    if (lint?.score !== undefined) {
      total += lint.score * 0.4;
      count += 0.4;
    }
    if (duplicate?.score !== undefined) {
      total += duplicate.score * 0.2;
      count += 0.2;
    }
    
    return count > 0 ? Math.round(total / count) : null;
  }
}));

export default useAppStore;
