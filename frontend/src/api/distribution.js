import request from './index'

export function createChannel(data) {
  return request({
    url: '/v1/distribution/channels',
    method: 'post',
    data
  })
}

export function getChannel(configId) {
  return request({
    url: `/v1/distribution/channels/${configId}`,
    method: 'get'
  })
}

export function listChannels(params) {
  return request({
    url: '/v1/distribution/channels',
    method: 'get',
    params
  })
}

export function updateChannel(configId, data) {
  return request({
    url: `/v1/distribution/channels/${configId}`,
    method: 'put',
    data
  })
}

export function deleteChannel(configId) {
  return request({
    url: `/v1/distribution/channels/${configId}`,
    method: 'delete'
  })
}

export function createDistributionTask(data) {
  return request({
    url: '/v1/distribution/tasks',
    method: 'post',
    data
  })
}

export function getDistributionTask(taskId) {
  return request({
    url: `/v1/distribution/tasks/${taskId}`,
    method: 'get'
  })
}

export function listDistributionTasks(params) {
  return request({
    url: '/v1/distribution/tasks',
    method: 'get',
    params
  })
}

export function executeDistribution(taskId) {
  return request({
    url: `/v1/distribution/tasks/${taskId}/execute`,
    method: 'post'
  })
}

export function batchDistribute(data) {
  return request({
    url: '/v1/distribution/batch-distribute',
    method: 'post',
    data
  })
}

export function getDistributionStats() {
  return request({
    url: '/v1/distribution/channels/stats',
    method: 'get'
  })
}
