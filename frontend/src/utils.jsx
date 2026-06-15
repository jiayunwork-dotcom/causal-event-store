import React from 'react'
import { format } from 'date-fns'

export const fmtTs = (ts) => {
  if (!ts) return '-'
  try {
    const d = typeof ts === 'string' || ts instanceof Date ? new Date(ts) : new Date(Number(ts))
    if (isNaN(d.getTime())) return String(ts)
    return format(d, 'yyyy-MM-dd HH:mm:ss')
  } catch {
    return String(ts)
  }
}

export const fmtBytes = (b) => {
  if (!b || b === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let v = b
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(2)} ${units[i]}`
}

export const shortId = (id, n = 8) => {
  if (!id) return ''
  return id.length > n ? id.substring(0, n) + '...' : id
}

export const statusBadgeClass = (status) => {
  const s = String(status || '').toUpperCase()
  if (['UP', 'RUNNING', 'RESOLVED', 'SUCCESS'].includes(s)) return 'badge badge-success'
  if (['DOWN', 'ERROR', 'OPEN', 'FAILED'].includes(s)) return 'badge badge-danger'
  if (['DEGRADED', 'REPLAYING', 'STOPPED'].includes(s)) return 'badge badge-warning'
  if (['LEADER', 'ACTIVE'].includes(s)) return 'badge badge-info'
  return 'badge badge-secondary'
}

export const useInterval = (fn, delay) => {
  const saved = React.useRef(fn)
  React.useEffect(() => { saved.current = fn }, [fn])
  React.useEffect(() => {
    if (delay == null) return
    const id = setInterval(() => saved.current(), delay)
    return () => clearInterval(id)
  }, [delay])
}

export const prettyJson = (obj) => {
  try {
    if (typeof obj === 'string') obj = JSON.parse(obj)
    return JSON.stringify(obj, null, 2)
  } catch {
    return String(obj)
  }
}
