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
  readByAggregate: (aggregateId, fromSeq) =>
    api.get(`/events/aggregate/${aggregateId}`, { params: { fromSequence: fromSeq } }).then(r => r.data),
  readCausal: (vectorClock) => api.post('/events/causal', { vectorClock }).then(r => r.data),
  getCausalGraph: (eventIds) => api.post('/events/causal-graph', eventIds).then(r => r.data),
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
  create: (data) => api.post('/projections', data).then(r => r.data),
  replay: (id) => api.post(`/projections/${id}/replay`).then(r => r.data),
  delete: (id) => api.delete(`/projections/${id}`).then(r => r.data),
}

export const conflictApi = {
  list: (status) => api.get('/conflicts', { params: { status } }).then(r => r.data),
  listByAggregate: (aggregateId) => api.get(`/conflicts/aggregate/${aggregateId}`).then(r => r.data),
  resolve: (id, data) => api.post(`/conflicts/${id}/resolve`, data).then(r => r.data),
}

export default api
