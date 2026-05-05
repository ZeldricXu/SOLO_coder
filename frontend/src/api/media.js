import request from './index'

export function getMediaList(params) {
  return request({
    url: '/v1/media',
    method: 'get',
    params
  })
}

export function getMediaById(mediaId) {
  return request({
    url: `/v1/media/${mediaId}`,
    method: 'get'
  })
}

export function updateMedia(mediaId, data) {
  return request({
    url: `/v1/media/${mediaId}`,
    method: 'put',
    data
  })
}

export function deleteMedia(mediaId) {
  return request({
    url: `/v1/media/${mediaId}`,
    method: 'delete'
  })
}

export function getMediaStats() {
  return request({
    url: '/v1/media/stats',
    method: 'get'
  })
}

export function getPresignedUrl(mediaId, params) {
  return request({
    url: `/v1/media/${mediaId}/presigned-url`,
    method: 'get',
    params
  })
}

export function batchDelete(data) {
  return request({
    url: '/v1/media/batch-delete',
    method: 'post',
    data
  })
}
