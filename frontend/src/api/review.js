import request from './index'

export function createReview(data) {
  return request({
    url: '/v1/reviews',
    method: 'post',
    data
  })
}

export function getReview(reviewId) {
  return request({
    url: `/v1/reviews/${reviewId}`,
    method: 'get'
  })
}

export function getReviewsByMedia(mediaId) {
  return request({
    url: `/v1/reviews/media/${mediaId}`,
    method: 'get'
  })
}

export function listPendingReviews(params) {
  return request({
    url: '/v1/reviews/pending',
    method: 'get',
    params
  })
}

export function listMyReviews(params) {
  return request({
    url: '/v1/reviews/my',
    method: 'get',
    params
  })
}

export function startReview(reviewId) {
  return request({
    url: `/v1/reviews/${reviewId}/start`,
    method: 'post'
  })
}

export function approveReview(reviewId, data) {
  return request({
    url: `/v1/reviews/${reviewId}/approve`,
    method: 'post',
    data
  })
}

export function rejectReview(reviewId, data) {
  return request({
    url: `/v1/reviews/${reviewId}/reject`,
    method: 'post',
    data
  })
}

export function addComment(reviewId, data) {
  return request({
    url: `/v1/reviews/${reviewId}/comment`,
    method: 'post',
    data
  })
}

export function updatePriority(reviewId, data) {
  return request({
    url: `/v1/reviews/${reviewId}/priority`,
    method: 'put',
    data
  })
}

export function reassignReview(reviewId, data) {
  return request({
    url: `/v1/reviews/${reviewId}/reassign`,
    method: 'put',
    data
  })
}

export function getStats() {
  return request({
    url: '/v1/reviews/stats',
    method: 'get'
  })
}
