import React, { useState, useEffect } from 'react'
import { eventApi } from '../api.js'
import { fmtTs, prettyJson, shortId } from '../utils.jsx'
import { Search, Eye, FileJson, RefreshCw } from 'lucide-react'

export default function EventBrowser() {
  const [aggregateId, setAggregateId] = useState('order-1')
  const [fromSeq, setFromSeq] = useState('')
  const [filterType, setFilterType] = useState('')
  const [events, setEvents] = useState([])
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState(null)
  const [timeRange, setTimeRange] = useState({ start: '', end: '' })

  const load = async () => {
    if (!aggregateId) return
    setLoading(true)
    try {
      const data = await eventApi.readByAggregate(aggregateId, fromSeq ? Number(fromSeq) : null)
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

  const filtered = events.filter(e => !filterType || e.eventType.includes(filterType))

  const loadSample = async () => {
    try {
      const samples = [
        { aggregateId, aggregateType: 'Order', eventType: 'OrderCreated', payload: JSON.stringify({ orderId: aggregateId, amount: 100, status: 'PENDING' }), causalDependencies: [] },
        { aggregateId, aggregateType: 'Order', eventType: 'PaymentReceived', payload: JSON.stringify({ method: 'ALIPAY', amount: 100 }), causalDependencies: [] },
        { aggregateId, aggregateType: 'Order', eventType: 'InventoryReserved', payload: JSON.stringify({ items: [{ sku: 'SKU001', qty: 2 }] }), causalDependencies: [] },
        { aggregateId, aggregateType: 'Order', eventType: 'OrderShipped', payload: JSON.stringify({ carrier: 'SF', trackingNo: 'SF' + Date.now() }), causalDependencies: [] },
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
                      <th>时间</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {filtered.map((e) => (
                      <tr key={e.eventId} className={selected?.eventId === e.eventId ? 'bg-primary-50' : ''}>
                        <td>
                          <button onClick={() => setSelected(e)} className="btn btn-secondary py-1 px-2 text-xs">
                            <Eye size={12}/> 查看
                          </button>
                        </td>
                        <td className="font-mono">#{e.sequenceNumber}</td>
                        <td><span className="badge badge-info">{e.eventType}</span></td>
                        <td>P{e.partitionId}</td>
                        <td>{fmtTs(e.timestamp)}</td>
                      </tr>
                    ))}
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
