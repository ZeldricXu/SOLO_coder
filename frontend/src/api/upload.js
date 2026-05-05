import request from './index'
import axios from 'axios'
import SparkMD5 from 'spark-md5'

export function getConfig() {
  return request({
    url: '/v1/config',
    method: 'get'
  })
}

export function createSession(data) {
  return request({
    url: '/v1/media/upload/session',
    method: 'post',
    data
  })
}

export function getSessionStatus(fileId) {
  return request({
    url: `/v1/media/upload/session/${fileId}`,
    method: 'get'
  })
}

export function uploadChunk(formData) {
  return request({
    url: '/v1/media/upload/chunk',
    method: 'post',
    data: formData,
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  })
}

export function completeUpload(data) {
  return request({
    url: '/v1/media/upload/complete',
    method: 'post',
    data
  })
}

export function cancelUpload(fileId) {
  return request({
    url: `/v1/media/upload/${fileId}`,
    method: 'delete'
  })
}

export function getChunkStatus(fileId) {
  return request({
    url: `/v1/media/upload/status/${fileId}`,
    method: 'get'
  })
}

class ChunkUploader {
  constructor(options = {}) {
    this.chunkSize = options.chunkSize || 5 * 1024 * 1024
    this.concurrentUploads = options.concurrentUploads || 3
    this.retryCount = options.retryCount || 3
    this.retryDelay = options.retryDelay || 1000
    
    this.onProgress = options.onProgress || (() => {})
    this.onComplete = options.onComplete || (() => {})
    this.onError = options.onError || (() => {})
    
    this.file = null
    this.fileId = null
    this.totalChunks = 0
    this.uploadedChunks = 0
    this.uploadProgress = 0
    this.isPaused = false
    this.isCancelled = false
    this.chunkStatuses = []
  }

  async calculateMD5(file, onProgress) {
    return new Promise((resolve, reject) => {
      const fileReader = new FileReader()
      const spark = new SparkMD5.ArrayBuffer()
      const chunkSize = 2 * 1024 * 1024
      const chunks = Math.ceil(file.size / chunkSize)
      let currentChunk = 0

      fileReader.onload = (e) => {
        spark.append(e.target.result)
        currentChunk++
        
        if (onProgress) {
          onProgress(Math.round((currentChunk / chunks) * 100))
        }

        if (currentChunk < chunks) {
          loadNext()
        } else {
          resolve(spark.end())
        }
      }

      fileReader.onerror = () => {
        reject(new Error('MD5 calculation failed'))
      }

      const loadNext = () => {
        const start = currentChunk * chunkSize
        const end = Math.min(start + chunkSize, file.size)
        fileReader.readAsArrayBuffer(file.slice(start, end))
      }

      loadNext()
    })
  }

  async prepareUpload(file) {
    this.file = file
    this.totalChunks = Math.ceil(file.size / this.chunkSize)
    this.chunkStatuses = new Array(this.totalChunks).fill('pending')
    
    try {
      const sessionRes = await createSession({
        filename: file.name,
        file_size: file.size,
        mime_type: file.type
      })

      if (sessionRes.code === 200) {
        this.fileId = sessionRes.data.file_id
        this.totalChunks = sessionRes.data.total_chunks
        this.chunkSize = sessionRes.data.chunk_size
        
        return {
          success: true,
          fileId: this.fileId,
          totalChunks: this.totalChunks,
          chunkSize: this.chunkSize
        }
      }
    } catch (error) {
      console.error('Failed to create upload session:', error)
      throw error
    }
  }

  async checkResumable(fileId) {
    try {
      const statusRes = await getChunkStatus(fileId)
      
      if (statusRes.code === 200) {
        const data = statusRes.data
        const uploadedIndices = data.uploaded_chunks || []
        
        return {
          success: true,
          totalChunks: data.total_chunks,
          uploadedChunks: data.uploaded_chunks,
          missingChunks: data.missing_chunks,
          progress: data.progress
        }
      }
    } catch (error) {
      console.error('Failed to check upload status:', error)
      return {
        success: false,
        error: error.message
      }
    }
  }

  async uploadChunkWithRetry(chunkIndex, chunk) {
    for (let attempt = 0; attempt < this.retryCount; attempt++) {
      if (this.isCancelled || this.isPaused) {
        return { success: false, cancelled: true }
      }

      try {
        const formData = new FormData()
        formData.append('file_id', this.fileId)
        formData.append('chunk_index', chunkIndex)
        formData.append('total_chunks', this.totalChunks)
        formData.append('chunk_data', chunk)

        const res = await uploadChunk(formData)

        if (res.code === 200) {
          return { success: true, data: res.data }
        }
      } catch (error) {
        console.error(`Chunk ${chunkIndex} upload failed (attempt ${attempt + 1}):`, error)
        
        if (attempt < this.retryCount - 1) {
          await this.delay(this.retryDelay * (attempt + 1))
        }
      }
    }

    return { success: false, error: 'Max retries exceeded' }
  }

  delay(ms) {
    return new Promise(resolve => setTimeout(resolve, ms))
  }

  async startUpload() {
    if (!this.fileId) {
      throw new Error('Upload not prepared')
    }

    this.isPaused = false
    this.isCancelled = false

    let currentIndex = 0
    const uploadQueue = []

    for (let i = 0; i < this.totalChunks; i++) {
      if (this.chunkStatuses[i] !== 'completed') {
        uploadQueue.push(i)
      }
    }

    const uploadNext = async () => {
      while (currentIndex < uploadQueue.length && !this.isCancelled && !this.isPaused) {
        const chunkIndex = uploadQueue[currentIndex]
        currentIndex++

        const start = chunkIndex * this.chunkSize
        const end = Math.min(start + this.chunkSize, this.file.size)
        const chunk = this.file.slice(start, end)

        this.chunkStatuses[chunkIndex] = 'uploading'

        const result = await this.uploadChunkWithRetry(chunkIndex, chunk)

        if (result.success) {
          this.chunkStatuses[chunkIndex] = 'completed'
          this.uploadedChunks++
          this.uploadProgress = Math.round((this.uploadedChunks / this.totalChunks) * 100)
          
          this.onProgress({
            chunkIndex,
            uploadedChunks: this.uploadedChunks,
            totalChunks: this.totalChunks,
            progress: this.uploadProgress
          })
        } else if (result.cancelled) {
          this.chunkStatuses[chunkIndex] = 'pending'
        } else {
          this.chunkStatuses[chunkIndex] = 'failed'
          this.onError({
            chunkIndex,
            error: result.error
          })
        }
      }
    }

    const workers = []
    for (let i = 0; i < this.concurrentUploads; i++) {
      workers.push(uploadNext())
    }

    await Promise.all(workers)

    if (this.isCancelled) {
      return { success: false, cancelled: true }
    }

    const failedChunks = this.chunkStatuses
      .map((status, index) => status === 'failed' ? index : -1)
      .filter(index => index !== -1)

    if (failedChunks.length > 0) {
      return {
        success: false,
        failedChunks
      }
    }

    return {
      success: true,
      uploadedChunks: this.uploadedChunks,
      totalChunks: this.totalChunks
    }
  }

  async complete(expectedMd5 = null) {
    try {
      const completeRes = await completeUpload({
        file_id: this.fileId,
        filename: this.file.name,
        total_size: this.file.size,
        expected_md5: expectedMd5
      })

      if (completeRes.code === 200) {
        this.onComplete(completeRes.data)
        return {
          success: true,
          data: completeRes.data
        }
      }
    } catch (error) {
      console.error('Failed to complete upload:', error)
      this.onError(error)
      return {
        success: false,
        error: error.message
      }
    }
  }

  pause() {
    this.isPaused = true
  }

  resume() {
    this.isPaused = false
  }

  async cancel() {
    this.isCancelled = true
    
    try {
      await cancelUpload(this.fileId)
    } catch (error) {
      console.error('Failed to cancel upload:', error)
    }
  }
}

export { ChunkUploader }
