import axios from 'axios'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 60000,
  headers: {
    'Content-Type': 'application/json',
  },
})

api.interceptors.response.use(
  (res) => res,
  (err) => {
    console.error('API Error:', err?.response?.data || err.message)
    return Promise.reject(err)
  }
)

export const eventApi = {
  append: (events) => api.post('/events', events).then(r => r.data),
  readByAggregate: (aggregateId, fromSeq, tags, tagMode) =>
    api.get(`/events/aggregate/${aggregateId}`, {
      params: {
        fromSequence: fromSeq || undefined,
        tags: tags && tags.length > 0 ? tags.join(',') : undefined,
        tagMode: tagMode || undefined,
      }
    }).then(r => r.data),
  readCausal: (vectorClock) => api.post('/events/causal', { vectorClock }).then(r => r.data),
  getCausalGraph: (eventIds) => api.post('/events/causal-graph', eventIds).then(r => r.data),
  getStateAtTimestamp: (aggregateId, timestamp) =>
    api.get(`/events/aggregate/${aggregateId}/at`, { params: { timestamp } }).then(r => r.data),
  getReplayData: (aggregateId) =>
    api.get(`/events/aggregate/${aggregateId}/replay`).then(r => r.data),
}

export const clusterApi = {
  getStatus: () => api.get('/cluster/status').then(r => r.data),
  getNodes: () => api.get('/cluster/nodes').then(r => r.data),
  getPartitionCounts: () => api.get('/cluster/partition-counts').then(r => r.data),
}

export const snapshotApi = {
  create: (aggregateId) => api.post(`/snapshots/${aggregateId}`).then(r => r.data),
  list: (aggregateId) => api.get(`/snapshots/${aggregateId}`).then(r => r.data),
  delete: (snapshotId) => api.delete(`/snapshots/${snapshotId}`).then(r => r.data),
}

export const subscriptionApi = {
  list: () => api.get('/subscriptions').then(r => r.data),
  create: (data) => api.post('/subscriptions', data).then(r => r.data),
  delete: (id) => api.delete(`/subscriptions/${id}`).then(r => r.data),
}

export const projectionApi = {
  list: () => api.get('/projections').then(r => r.data),
  get: (id) => api.get(`/projections/${id}`).then(r => r.data),
  create: (data) => api.post('/projections', data).then(r => r.data),
  update: (id, data) => api.put(`/projections/${id}`, data).then(r => r.data),
  rebuild: (id) => api.post(`/projections/${id}/rebuild`).then(r => r.data),
  pause: (id) => api.post(`/projections/${id}/pause`).then(r => r.data),
  resume: (id) => api.post(`/projections/${id}/resume`).then(r => r.data),
  delete: (id) => api.delete(`/projections/${id}`).then(r => r.data),
  getData: (id, params) => api.get(`/projections/${id}/data`, { params }).then(r => r.data),
  getPendingCount: (id) => api.get(`/projections/${id}/pending-count`).then(r => r.data),
  getMetrics: (id) => api.get(`/projections/${id}/metrics`).then(r => r.data),
  getChangelog: (id, params) => api.get(`/projections/${id}/changelog`, { params }).then(r => r.data),
  listVersions: (id) => api.get(`/projections/${id}/versions`).then(r => r.data),
  createVersion: (id, data) => api.post(`/projections/${id}/versions`, data).then(r => r.data),
  activateVersion: (id, version) => api.post(`/projections/${id}/versions/${version}/activate`).then(r => r.data),
  getVersionData: (id, version, params) => api.get(`/projections/${id}/versions/${version}/data`, { params }).then(r => r.data),
}

export const conflictApi = {
  list: (status) => api.get('/conflicts', { params: { status } }).then(r => r.data),
  listByAggregate: (aggregateId) => api.get(`/conflicts/aggregate/${aggregateId}`).then(r => r.data),
  resolve: (id, data) => api.post(`/conflicts/${id}/resolve`, data).then(r => r.data),
}

export default api
