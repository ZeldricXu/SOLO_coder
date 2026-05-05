import Vue from 'vue'
import Vuex from 'vuex'
import reviewApi from '@/api/review'

Vue.use(Vuex)

export default new Vuex.Store({
  state: {
    uploadSessions: [],
    pendingReviewCount: 0
  },
  getters: {
    uploadSessions: state => state.uploadSessions,
    pendingReviewCount: state => state.pendingReviewCount,
    activeUploads: state => {
      return state.uploadSessions.filter(session => 
        ['uploading', 'pending'].includes(session.status)
      )
    }
  },
  mutations: {
    ADD_UPLOAD_SESSION(state, session) {
      const existingIndex = state.uploadSessions.findIndex(
        s => s.fileId === session.fileId
      )
      if (existingIndex === -1) {
        state.uploadSessions.push(session)
      } else {
        state.uploadSessions[existingIndex] = {
          ...state.uploadSessions[existingIndex],
          ...session
        }
      }
    },
    UPDATE_UPLOAD_SESSION(state, { fileId, updates }) {
      const index = state.uploadSessions.findIndex(s => s.fileId === fileId)
      if (index !== -1) {
        state.uploadSessions[index] = {
          ...state.uploadSessions[index],
          ...updates
        }
      }
    },
    REMOVE_UPLOAD_SESSION(state, fileId) {
      const index = state.uploadSessions.findIndex(s => s.fileId === fileId)
      if (index !== -1) {
        state.uploadSessions.splice(index, 1)
      }
    },
    CLEAR_COMPLETED_UPLOADS(state) {
      state.uploadSessions = state.uploadSessions.filter(session =>
        !['completed', 'failed', 'cancelled'].includes(session.status)
      )
    },
    SET_PENDING_REVIEW_COUNT(state, count) {
      state.pendingReviewCount = count
    }
  },
  actions: {
    addUploadSession({ commit }, session) {
      commit('ADD_UPLOAD_SESSION', session)
    },
    updateUploadSession({ commit }, payload) {
      commit('UPDATE_UPLOAD_SESSION', payload)
    },
    removeUploadSession({ commit }, fileId) {
      commit('REMOVE_UPLOAD_SESSION', fileId)
    },
    clearCompletedUploads({ commit }) {
      commit('CLEAR_COMPLETED_UPLOADS')
    },
    async fetchReviewStats({ commit }) {
      try {
        const res = await reviewApi.getStats()
        if (res.code === 200) {
          commit('SET_PENDING_REVIEW_COUNT', res.data.pending || 0)
        }
      } catch (error) {
        console.error('Failed to fetch review stats:', error)
      }
    }
  },
  modules: {}
})
