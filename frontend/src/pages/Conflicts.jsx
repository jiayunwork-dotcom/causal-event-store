import React, { useState, useEffect } from 'react'
import { conflictApi, eventApi } from '../api.js'
import { AlertTriangle, Check, X, RefreshCw, Search, Info } from 'lucide-react'
import { fmtTs, useInterval, prettyJson, statusBadgeClass } from '../utils.jsx'

export default function Conflicts() {
  const [list, setList] = useState([])
  const [loading, setLoading] = useState(true)
  const [filter, setFilter] = useState('ALL')
  const [selected, setSelected] = useState(null)
  const [eventDetails, setEventDetails] = useState({})
  const [resolving, setResolving] = useState(false)

  const load = async () => {
    setLoading(true)
    try {
      const data = filter === 'ALL' ? await conflictApi.list() : await conflictApi.list(filter)
      setList(data)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [filter])
  useInterval(load, 10000)

  const openCount = list.filter(c => c.status === 'OPEN').length
  const resolvedCount = list.filter(c => c.status === 'RESOLVED').length

  const loadDetail = async (c) => {
    setSelected(c)
    const ids = [c.eventAId, c.eventBId]
    try {
      const g = await eventApi.getCausalGraph(ids)
      const map = {}
      for (const e of g.events || []) map[e.eventId] = e
      setEventDetails(map)
    } catch {
      setEventDetails({})
    }
  }

  const resolve = async (c, type) => {
    if (!confirm(`确定以${type === 'KEEP_A' ? '事件A' : type === 'KEEP_B' ? '事件B' : '自定义'}解决此冲突?`)) return
    setResolving(true)
    try {
      await conflictApi.resolve(c.conflictId, { resolution: type, notes: '手动解决于 ' + new Date().toISOString() })
      await load()
      setSelected(null)
    } catch (e) {
      alert('解决失败: ' + (e.response?.data?.message || e.message))
    } finally {
      setResolving(false)
    }
  }

  const generateSample = async () => {
    try {
      const aggId = 'conflict-demo-' + Date.now()
      const e1 = { aggregateId: aggId, aggregateType: 'Demo', eventId: 'c_' + Date.now() + '_1', eventType: 'Update', payload: JSON.stringify({ field: 'value_A' }), causalDependencies: [], clientVectorClock: [1, 0, 0, 0, 0, 0, 0, 0] }
      const e2 = { aggregateId: aggId, aggregateType: 'Demo', eventId: 'c_' + Date.now() + '_2', eventType: 'Update', payload: JSON.stringify({ field: 'value_B' }), causalDependencies: [], clientVectorClock: [0, 1, 0, 0, 0, 0, 0, 0] }
      await eventApi.append([e1, e2])
      alert('示例冲突已生成 (两个并发写同一聚合根)')
      await load()
    } catch (e) {
      alert('生成失败: ' + (e.response?.data?.message || e.message))
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between flex-wrap gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 flex items-center gap-2"><AlertTriangle className="text-red-500"/> 冲突处理</h1>
          <p className="text-sm text-slate-500 mt-1">同一聚合根并发事件 (向量时钟不可比) · 需人工解决</p>
        </div>
        <div className="flex gap-2">
          <button onClick={generateSample} className="btn btn-secondary">生成示例冲突</button>
          <button onClick={load} className="btn btn-secondary"><RefreshCw size={14} className={loading ? "animate-spin" : ""}/> 刷新</button>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="card bg-gradient-to-br from-red-50 to-red-100">
          <div className="card-body flex items-center justify-between">
            <div>
              <p className="text-sm text-slate-600">待解决冲突</p>
              <p className="text-3xl font-bold text-red-700 mt-2">{openCount}</p>
            </div>
            <div className="w-12 h-12 rounded-lg bg-red-500 text-white flex items-center justify-center shadow">
              <AlertTriangle size={22}/>
            </div>
          </div>
        </div>
        <div className="card bg-gradient-to-br from-emerald-50 to-emerald-100">
          <div className="card-body flex items-center justify-between">
            <div>
              <p className="text-sm text-slate-600">已解决冲突</p>
              <p className="text-3xl font-bold text-emerald-700 mt-2">{resolvedCount}</p>
            </div>
            <div className="w-12 h-12 rounded-lg bg-emerald-500 text-white flex items-center justify-center shadow">
              <Check size={22}/>
            </div>
          </div>
        </div>
        <div className="card bg-gradient-to-br from-slate-50 to-slate-100">
          <div className="card-body">
            <label className="label">状态过滤</label>
            <select className="input" value={filter} onChange={e => setFilter(e.target.value)}>
              <option value="ALL">全部 ({list.length})</option>
              <option value="OPEN">待解决 ({openCount})</option>
              <option value="RESOLVED">已解决 ({resolvedCount})</option>
            </select>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="card lg:col-span-2">
          <div className="card-header"><h3 className="font-semibold">冲突列表</h3></div>
          <div className="overflow-x-auto max-h-[600px] overflow-y-auto">
            {loading && list.length === 0 ? <div className="p-10 text-center text-slate-500">加载中...</div> :
              list.length === 0 ? <div className="p-10 text-center text-slate-500">暂无冲突</div> :
                <table className="table">
                  <thead className="sticky top-0 z-10">
                    <tr>
                      <th>操作</th>
                      <th>ID</th>
                      <th>聚合根</th>
                      <th>事件A</th>
                      <th>事件B</th>
                      <th>检测时间</th>
                      <th>状态</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {list.map(c => (
                      <tr key={c.conflictId} className={selected?.conflictId === c.conflictId ? 'bg-red-50' : ''}>
                        <td>
                          <button onClick={() => loadDetail(c)} className="btn btn-secondary py-1 px-2 text-xs">
                            <Search size={12}/> 查看
                          </button>
                        </td>
                        <td className="font-mono">#{c.conflictId}</td>
                        <td className="font-mono text-xs max-w-[140px] truncate" title={c.aggregateId}>{c.aggregateId}</td>
                        <td className="font-mono text-xs max-w-[100px] truncate" title={c.eventAId}>{c.eventAId.substring(0, 12)}...</td>
                        <td className="font-mono text-xs max-w-[100px] truncate" title={c.eventBId}>{c.eventBId.substring(0, 12)}...</td>
                        <td>{fmtTs(c.detectedAt)}</td>
                        <td><span className={statusBadgeClass(c.status)}>{c.status}</span></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
            }
          </div>
        </div>

        <div className="card">
          <div className="card-header">
            <h3 className="font-semibold flex items-center gap-2"><Info size={14}/> 冲突详情 & 解决</h3>
          </div>
          <div className="card-body">
            {!selected ? <p className="text-sm text-slate-500">点击"查看"选择冲突</p> :
              <div className="space-y-4">
                <div className="flex justify-between text-sm">
                  <span className="text-slate-500">冲突ID</span>
                  <span className="font-mono">#{selected.conflictId}</span>
                </div>
                <div className="flex justify-between text-sm">
                  <span className="text-slate-500">聚合根</span>
                  <span className="font-mono text-xs">{selected.aggregateId}</span>
                </div>
                <div className="flex justify-between text-sm">
                  <span className="text-slate-500">解决方式</span>
                  <span>{selected.resolution || <span className="text-slate-400">未解决</span>}</span>
                </div>

                <EventCard label="事件 A" event={eventDetails[selected.eventAId]} eventId={selected.eventAId} accent="primary" />
                <EventCard label="事件 B" event={eventDetails[selected.eventBId]} eventId={selected.eventBId} accent="violet" />

                {selected.status === 'OPEN' && (
                  <div className="space-y-2 pt-3 border-t border-slate-100">
                    <button disabled={resolving} onClick={() => resolve(selected, 'KEEP_A')} className="btn btn-primary w-full justify-center">
                      <Check size={14}/> 保留事件 A
                    </button>
                    <button disabled={resolving} onClick={() => resolve(selected, 'KEEP_B')} className="btn btn-secondary w-full justify-center" style={{ background: '#8b5cf6', color: 'white', borderColor: '#8b5cf6' }}>
                      <Check size={14}/> 保留事件 B
                    </button>
                    <button disabled={resolving} onClick={() => resolve(selected, 'CUSTOM')} className="btn btn-secondary w-full justify-center">
                      <Check size={14}/> 自定义合并
                    </button>
                  </div>
                )}
                {selected.status === 'RESOLVED' && (
                  <div className="p-3 bg-emerald-50 border border-emerald-200 rounded-lg">
                    <p className="text-xs text-emerald-800">
                      <Check size={12} className="inline mr-1"/>
                      已解决 · {fmtTs(selected.resolvedAt)}
                    </p>
                    {selected.resolutionNotes && <p className="text-xs text-emerald-700 mt-1">{selected.resolutionNotes}</p>}
                  </div>
                )}
              </div>
            }
          </div>
        </div>
      </div>
    </div>
  )
}

function EventCard({ label, event, eventId, accent }) {
  const bg = { primary: 'bg-primary-50 border-primary-200', violet: 'bg-violet-50 border-violet-200' }[accent]
  const head = { primary: 'bg-primary-600', violet: 'bg-violet-600' }[accent]
  return (
    <div className={`rounded-lg border overflow-hidden ${bg}`}>
      <div className={`px-3 py-1.5 text-xs font-bold text-white ${head}`}>{label}{event ? ` · ${event.eventType}` : ''}</div>
      <div className="p-3 space-y-2">
        <div className="font-mono text-xs break-all text-slate-600">{eventId}</div>
        {event ? (
          <>
            <div className="flex items-center gap-2 flex-wrap">
              <span className="badge badge-secondary">P{event.partitionId} · #{event.sequenceNumber}</span>
              <span className="text-xs text-slate-500">{fmtTs(event.timestamp)}</span>
            </div>
            <div>
              <p className="text-xs text-slate-500 mb-1">向量时钟</p>
              <div className="font-mono text-[10px] bg-white p-1.5 rounded border text-slate-600">
                [{event.vectorClock?.clocks?.join(', ') || ''}]
              </div>
            </div>
            <div>
              <p className="text-xs text-slate-500 mb-1">负载</p>
              <pre className="font-mono text-[10px] bg-white p-2 rounded border max-h-32 overflow-auto">{prettyJson(event.payload)}</pre>
            </div>
          </>
        ) : (
          <p className="text-xs text-slate-500">点击刷新以加载详情</p>
        )}
      </div>
    </div>
  )
}
