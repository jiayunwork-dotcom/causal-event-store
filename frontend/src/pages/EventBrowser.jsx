import React, { useState, useEffect, useMemo } from 'react'
import { eventApi } from '../api.js'
import { fmtTs, prettyJson, shortId } from '../utils.jsx'
import { Search, Eye, FileJson, RefreshCw, GitCompare } from 'lucide-react'

function applyMergePatch(target, patch) {
  const result = JSON.parse(JSON.stringify(target))
  if (!patch || typeof patch !== 'object' || Array.isArray(patch)) return result
  for (const [key, value] of Object.entries(patch)) {
    if (value === null) {
      delete result[key]
    } else if (typeof value === 'object' && !Array.isArray(value)
               && typeof result[key] === 'object' && !Array.isArray(result[key])
               && result[key] !== null) {
      result[key] = applyMergePatch(result[key], value)
    } else {
      result[key] = value
    }
  }
  return result
}

function computeDiff(before, after) {
  const diffs = []
  const allKeys = new Set([...Object.keys(before), ...Object.keys(after)])
  for (const key of allKeys) {
    const inBefore = key in before
    const inAfter = key in after
    if (!inBefore && inAfter) {
      diffs.push({ key, type: 'added', value: after[key] })
    } else if (inBefore && !inAfter) {
      diffs.push({ key, type: 'removed', value: before[key] })
    } else if (JSON.stringify(before[key]) !== JSON.stringify(after[key])) {
      diffs.push({ key, type: 'modified', before: before[key], after: after[key] })
    }
  }
  return diffs
}

export default function EventBrowser() {
  const [aggregateId, setAggregateId] = useState('order-1')
  const [fromSeq, setFromSeq] = useState('')
  const [filterType, setFilterType] = useState('')
  const [events, setEvents] = useState([])
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState(null)
  const [tagInput, setTagInput] = useState('')
  const [selectedTags, setSelectedTags] = useState([])
  const [tagMode, setTagMode] = useState('OR')
  const [compareIdx, setCompareIdx] = useState(null)

  const load = async () => {
    if (!aggregateId) return
    setLoading(true)
    try {
      const tags = selectedTags.length > 0 ? selectedTags : null
      const data = await eventApi.readByAggregate(aggregateId, fromSeq ? Number(fromSeq) : null, tags, tagMode)
      setEvents(data)
    } catch (e) {
      console.error(e)
      setEvents([])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    const id = setTimeout(load, 300)
    return () => clearTimeout(id)
  }, [aggregateId, fromSeq])

  const allTags = useMemo(() => {
    const tagSet = new Set()
    events.forEach(e => (e.tags || []).forEach(t => tagSet.add(t)))
    return Array.from(tagSet).sort()
  }, [events])

  const filtered = events.filter(e => {
    if (filterType && !e.eventType.includes(filterType)) return false
    if (selectedTags.length > 0) {
      const eventTags = e.tags || []
      if (tagMode === 'AND') {
        return selectedTags.every(t => eventTags.includes(t))
      } else {
        return selectedTags.some(t => eventTags.includes(t))
      }
    }
    return true
  })

  const accumulatedStates = useMemo(() => {
    const states = [{}]
    let state = {}
    for (const e of events) {
      let payload
      try {
        payload = typeof e.payload === 'string' ? JSON.parse(e.payload) : e.payload
      } catch {
        payload = {}
      }
      state = applyMergePatch(state, payload)
      states.push(JSON.parse(JSON.stringify(state)))
    }
    return states
  }, [events])

  const handleCompare = (idx) => {
    setCompareIdx(idx)
  }

  const loadSample = async () => {
    try {
      const samples = [
        { aggregateId, aggregateType: 'Order', eventType: 'OrderCreated', payload: JSON.stringify({ orderId: aggregateId, amount: 100, status: 'PENDING' }), causalDependencies: [], tags: ['order', 'created'] },
        { aggregateId, aggregateType: 'Order', eventType: 'PaymentReceived', payload: JSON.stringify({ method: 'ALIPAY', amount: 100 }), causalDependencies: [], tags: ['payment'] },
        { aggregateId, aggregateType: 'Order', eventType: 'InventoryReserved', payload: JSON.stringify({ items: [{ sku: 'SKU001', qty: 2 }] }), causalDependencies: [], tags: ['inventory'] },
        { aggregateId, aggregateType: 'Order', eventType: 'OrderShipped', payload: JSON.stringify({ carrier: 'SF', trackingNo: 'SF' + Date.now(), status: 'SHIPPED' }), causalDependencies: [], tags: ['shipping', 'order'] },
      ]
      const deps1 = []
      for (let i = 0; i < samples.length; i++) {
        samples[i].eventId = 'evt_' + aggregateId + '_' + i + '_' + Date.now()
        samples[i].causalDependencies = i === 0 ? [] : deps1.slice()
        deps1.push(samples[i].eventId)
      }
      await eventApi.append(samples)
      await load()
    } catch (e) {
      alert('加载示例失败: ' + (e.response?.data?.message || e.message))
    }
  }

  const toggleTag = (tag) => {
    setSelectedTags(prev =>
      prev.includes(tag) ? prev.filter(t => t !== tag) : [...prev, tag]
    )
  }

  const addCustomTag = () => {
    const trimmed = tagInput.trim()
    if (trimmed && !selectedTags.includes(trimmed)) {
      setSelectedTags(prev => [...prev, trimmed])
    }
    setTagInput('')
  }

  const selectedEventIndex = events.findIndex(e => e.eventId === selected?.eventId)

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between flex-wrap gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">事件浏览器</h1>
          <p className="text-sm text-slate-500 mt-1">按聚合根检索事件列表</p>
        </div>
        <div className="flex gap-2">
          <button onClick={loadSample} className="btn btn-secondary"><FileJson size={14}/> 生成示例数据</button>
          <button onClick={load} className="btn btn-primary"><RefreshCw size={14} className={loading ? 'animate-spin' : ''}/> 刷新</button>
        </div>
      </div>

      <div className="card">
        <div className="card-body">
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            <div>
              <label className="label">聚合根ID</label>
              <input className="input" value={aggregateId} onChange={e => setAggregateId(e.target.value)} placeholder="order-1" />
            </div>
            <div>
              <label className="label">起始序列号</label>
              <input className="input" type="number" value={fromSeq} onChange={e => setFromSeq(e.target.value)} placeholder="0" />
            </div>
            <div>
              <label className="label">事件类型过滤</label>
              <input className="input" value={filterType} onChange={e => setFilterType(e.target.value)} placeholder="OrderCreated" />
            </div>
            <div className="flex items-end gap-2">
              <button onClick={load} className="btn btn-primary w-full"><Search size={14}/> 查询</button>
            </div>
          </div>

          {allTags.length > 0 && (
            <div className="mt-4 pt-4 border-t border-slate-100">
              <div className="flex items-center gap-3 mb-2">
                <label className="label mb-0">标签过滤</label>
                <div className="flex items-center gap-1 bg-slate-100 rounded-lg p-1">
                  <button
                    onClick={() => setTagMode('OR')}
                    className={`px-3 py-1 rounded text-xs font-medium transition-all ${
                      tagMode === 'OR' ? 'bg-primary-600 text-white' : 'text-slate-600 hover:bg-slate-200'
                    }`}
                  >
                    OR
                  </button>
                  <button
                    onClick={() => setTagMode('AND')}
                    className={`px-3 py-1 rounded text-xs font-medium transition-all ${
                      tagMode === 'AND' ? 'bg-primary-600 text-white' : 'text-slate-600 hover:bg-slate-200'
                    }`}
                  >
                    AND
                  </button>
                </div>
                <div className="flex items-center gap-2 ml-auto">
                  <input
                    className="input py-1 text-xs"
                    style={{ width: 120 }}
                    value={tagInput}
                    onChange={e => setTagInput(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && addCustomTag()}
                    placeholder="添加标签..."
                  />
                  <button onClick={addCustomTag} className="btn btn-secondary py-1 px-2 text-xs">添加</button>
                  {selectedTags.length > 0 && (
                    <button onClick={() => setSelectedTags([])} className="text-xs text-slate-400 hover:text-red-500">清除</button>
                  )}
                </div>
              </div>
              <div className="flex gap-1.5 flex-wrap">
                {allTags.map(tag => (
                  <button
                    key={tag}
                    onClick={() => toggleTag(tag)}
                    className={`px-2.5 py-1 rounded-full text-xs font-medium transition-all ${
                      selectedTags.includes(tag)
                        ? 'bg-primary-600 text-white shadow-sm'
                        : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                    }`}
                  >
                    {tag}
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="card lg:col-span-2">
          <div className="card-header">
            <h3 className="font-semibold text-slate-900">事件列表 ({filtered.length})</h3>
          </div>
          <div className="overflow-x-auto max-h-[600px] overflow-y-auto">
            {loading ? <div className="p-10 text-center text-slate-500">加载中...</div> :
              filtered.length === 0 ? <div className="p-10 text-center text-slate-500">暂无事件</div> :
                <table className="table">
                  <thead className="sticky top-0 z-10">
                    <tr>
                      <th>操作</th>
                      <th>序列号</th>
                      <th>类型</th>
                      <th>分区</th>
                      <th>标签</th>
                      <th>时间</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {filtered.map((e) => {
                      const eIdx = events.findIndex(ev => ev.eventId === e.eventId)
                      return (
                        <tr key={e.eventId} className={selected?.eventId === e.eventId ? 'bg-primary-50' : ''}>
                          <td>
                            <div className="flex gap-1">
                              <button onClick={() => setSelected(e)} className="btn btn-secondary py-1 px-2 text-xs">
                                <Eye size={12}/> 查看
                              </button>
                              <button onClick={() => handleCompare(eIdx)} className="btn btn-secondary py-1 px-2 text-xs" title="与前一条对比">
                                <GitCompare size={12}/> 对比
                              </button>
                            </div>
                          </td>
                          <td className="font-mono">#{e.sequenceNumber}</td>
                          <td><span className="badge badge-info">{e.eventType}</span></td>
                          <td>P{e.partitionId}</td>
                          <td>
                            <div className="flex gap-1 flex-wrap">
                              {(e.tags || []).map(t => (
                                <span key={t} className="px-1.5 py-0.5 rounded text-xs bg-amber-100 text-amber-800">{t}</span>
                              ))}
                            </div>
                          </td>
                          <td>{fmtTs(e.timestamp)}</td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
            }
          </div>
        </div>

        <div className="card">
          <div className="card-header">
            <h3 className="font-semibold text-slate-900">事件详情</h3>
          </div>
          <div className="card-body">
            {!selected ? <p className="text-sm text-slate-500">点击"查看"查看详情</p> :
              <div className="space-y-4">
                <Detail label="事件ID" value={selected.eventId} mono />
                <Detail label="聚合根" value={selected.aggregateId} />
                <Detail label="类型" value={<span className="badge badge-info">{selected.eventType}</span>} />
                <Detail label="聚合根内序列号" value={`#${selected.sequenceNumber}`} mono />
                <Detail label="分区内序列号" value={`P${selected.partitionId} / #${selected.partitionSequenceNumber}`} mono />
                <Detail label="全局序列号" value={selected.globalSequence != null ? `#${selected.globalSequence}` : '未分配'} mono />
                <Detail label="时间" value={fmtTs(selected.timestamp)} />
                {(selected.tags || []).length > 0 && (
                  <div>
                    <label className="label">标签</label>
                    <div className="flex gap-1.5 flex-wrap">
                      {selected.tags.map(t => (
                        <span key={t} className="px-2 py-0.5 rounded-full text-xs font-medium bg-amber-100 text-amber-800">{t}</span>
                      ))}
                    </div>
                  </div>
                )}
                <div>
                  <label className="label">向量时钟</label>
                  <div className="font-mono text-xs bg-slate-50 p-3 rounded-lg border border-slate-200 break-all">
                    [{selected.vectorClock?.clocks?.join(', ') || '[]'}]
                  </div>
                </div>
                <div>
                  <label className="label">因果依赖 ({selected.causalDependencies?.length || 0})</label>
                  <div className="space-y-1">
                    {selected.causalDependencies?.length > 0 ? selected.causalDependencies.map(d => (
                      <div key={d} className="font-mono text-xs bg-amber-50 p-2 rounded border border-amber-200 text-amber-800">
                        {shortId(d, 16)}
                      </div>
                    )) : <p className="text-xs text-slate-400">无依赖</p>}
                  </div>
                </div>
                <div>
                  <label className="label">负载 (JSON)</label>
                  <pre className="font-mono text-xs bg-slate-900 text-emerald-400 p-3 rounded-lg max-h-60 overflow-auto">{prettyJson(selected.payload)}</pre>
                </div>
              </div>
            }
          </div>
        </div>
      </div>

      {compareIdx !== null && (
        <DiffPanel
          beforeState={accumulatedStates[compareIdx] || {}}
          afterState={accumulatedStates[compareIdx + 1] || {}}
          event={events[compareIdx]}
          isFirst={compareIdx === 0}
          onClose={() => setCompareIdx(null)}
        />
      )}
    </div>
  )
}

function DiffPanel({ beforeState, afterState, event, isFirst, onClose }) {
  const diffs = computeDiff(beforeState, afterState)

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-2xl max-w-5xl w-full mx-4 max-h-[80vh] flex flex-col" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between px-6 py-4 border-b border-slate-200">
          <div>
            <h3 className="font-semibold text-slate-900 text-lg">事件对比视图</h3>
            <p className="text-sm text-slate-500 mt-0.5">
              {isFirst ? '初始事件' : `事件 #${event?.sequenceNumber} - ${event?.eventType}`} 的状态变化
            </p>
          </div>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600 text-2xl leading-none">&times;</button>
        </div>

        <div className="flex-1 overflow-auto p-6">
          <div className="grid grid-cols-2 gap-6 mb-6">
            <div>
              <h4 className="font-medium text-slate-700 mb-2">
                {isFirst ? '应用前 (空对象)' : '应用前的状态'}
              </h4>
              <pre className="font-mono text-xs bg-slate-900 text-slate-300 p-4 rounded-lg max-h-60 overflow-auto">
                {prettyJson(beforeState)}
              </pre>
            </div>
            <div>
              <h4 className="font-medium text-slate-700 mb-2">应用后的状态</h4>
              <pre className="font-mono text-xs bg-slate-900 text-emerald-400 p-4 rounded-lg max-h-60 overflow-auto">
                {prettyJson(afterState)}
              </pre>
            </div>
          </div>

          {diffs.length > 0 && (
            <div>
              <h4 className="font-medium text-slate-700 mb-3">差异字段</h4>
              <div className="space-y-2">
                {diffs.map(d => (
                  <div key={d.key} className={`rounded-lg p-3 border ${
                    d.type === 'added' ? 'bg-emerald-50 border-emerald-200' :
                    d.type === 'removed' ? 'bg-red-50 border-red-200' :
                    'bg-amber-50 border-amber-200'
                  }`}>
                    <div className="flex items-center gap-2 mb-1">
                      <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                        d.type === 'added' ? 'bg-emerald-200 text-emerald-800' :
                        d.type === 'removed' ? 'bg-red-200 text-red-800' :
                        'bg-amber-200 text-amber-800'
                      }`}>
                        {d.type === 'added' ? '新增' : d.type === 'removed' ? '删除' : '修改'}
                      </span>
                      <span className="font-mono text-sm font-semibold text-slate-900">{d.key}</span>
                    </div>
                    {d.type === 'modified' && (
                      <div className="flex gap-4 text-xs font-mono">
                        <div className="flex-1">
                          <span className="text-red-500 line-through">{JSON.stringify(d.before)}</span>
                        </div>
                        <div className="flex-1">
                          <span className="text-emerald-600">{JSON.stringify(d.after)}</span>
                        </div>
                      </div>
                    )}
                    {d.type === 'added' && (
                      <div className="text-xs font-mono text-emerald-600">{JSON.stringify(d.value)}</div>
                    )}
                    {d.type === 'removed' && (
                      <div className="text-xs font-mono text-red-500 line-through">{JSON.stringify(d.value)}</div>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}

          {diffs.length === 0 && (
            <div className="text-center text-slate-400 py-6">无差异</div>
          )}
        </div>
      </div>
    </div>
  )
}

function Detail({ label, value, mono }) {
  return (
    <div>
      <label className="label">{label}</label>
      <div className={`text-sm ${mono ? 'font-mono text-xs' : ''} break-all`}>{value || '-'}</div>
    </div>
  )
}
