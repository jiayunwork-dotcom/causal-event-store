import React, { useState, useEffect } from 'react'
import { subscriptionApi } from '../api.js'
import { Bell, Plus, Trash2, RefreshCw } from 'lucide-react'
import { fmtTs, useInterval } from '../utils.jsx'

export default function Subscriptions() {
  const [subs, setSubs] = useState([])
  const [loading, setLoading] = useState(true)
  const [showForm, setShowForm] = useState(false)
  const [form, setForm] = useState({ consumerId: '', eventPattern: '*' })

  const load = async () => {
    setLoading(true)
    try {
      setSubs(await subscriptionApi.list())
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [])
  useInterval(load, 10000)

  const create = async (e) => {
    e.preventDefault()
    try {
      await subscriptionApi.create({
        consumerId: form.consumerId || 'consumer_' + Date.now(),
        eventPattern: form.eventPattern || '*',
        startVector: Array(8).fill(0),
      })
      setForm({ consumerId: '', eventPattern: '*' })
      setShowForm(false)
      await load()
    } catch (err) {
      alert('创建失败: ' + (err.response?.data?.message || err.message))
    }
  }

  const del = async (id) => {
    if (!confirm('确定删除此订阅?')) return
    await subscriptionApi.delete(id)
    await load()
  }

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between flex-wrap gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 flex items-center gap-2"><Bell className="text-amber-500"/> 订阅管理</h1>
          <p className="text-sm text-slate-500 mt-1">实时事件推送 (Push模式, WebSocket)</p>
        </div>
        <div className="flex gap-2">
          <button onClick={load} className="btn btn-secondary"><RefreshCw size={14} className={loading ? 'animate-spin' : ''}/> 刷新</button>
          <button onClick={() => setShowForm(!showForm)} className="btn btn-primary"><Plus size={14}/> 新建订阅</button>
        </div>
      </div>

      {showForm && (
        <div className="card">
          <div className="card-header"><h3 className="font-semibold">创建订阅</h3></div>
          <form onSubmit={create} className="card-body space-y-4">
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label className="label">消费者ID</label>
                <input className="input" value={form.consumerId} onChange={e => setForm({ ...form, consumerId: e.target.value })} placeholder="consumer_xxx" />
              </div>
              <div>
                <label className="label">事件模式 (通配符支持 * ?)</label>
                <input className="input font-mono text-xs" value={form.eventPattern} onChange={e => setForm({ ...form, eventPattern: e.target.value })} placeholder="Order.* 或 *" />
              </div>
            </div>
            <div className="flex gap-2 justify-end">
              <button type="button" onClick={() => setShowForm(false)} className="btn btn-secondary">取消</button>
              <button type="submit" className="btn btn-primary">创建</button>
            </div>
          </form>
        </div>
      )}

      <div className="card">
        <div className="overflow-x-auto">
          {loading && subs.length === 0 ? <div className="p-10 text-center text-slate-500">加载中...</div> :
            subs.length === 0 ? <div className="p-10 text-center text-slate-500">暂无订阅</div> :
              <table className="table">
                <thead>
                  <tr>
                    <th>订阅ID</th>
                    <th>消费者</th>
                    <th>事件模式</th>
                    <th>读取游标 (VC)</th>
                    <th>创建时间</th>
                    <th>最后推送</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {subs.map(s => (
                    <tr key={s.subscriptionId}>
                      <td className="font-mono text-xs">{s.subscriptionId}</td>
                      <td><span className="badge badge-info">{s.consumerId}</span></td>
                      <td className="font-mono text-xs">{s.eventPattern}</td>
                      <td className="font-mono text-xs text-slate-600">[{s.cursorVector?.clocks?.join(',') || '0,0,0,0,0,0,0,0'}]</td>
                      <td>{fmtTs(s.createdAt)}</td>
                      <td>{fmtTs(s.lastPushAt)}</td>
                      <td>
                        <button onClick={() => del(s.subscriptionId)} className="btn btn-danger py-1 px-2 text-xs">
                          <Trash2 size={12}/> 删除
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
          }
        </div>
      </div>

      <div className="card">
        <div className="card-header"><h3 className="font-semibold">连接说明</h3></div>
        <div className="card-body space-y-2 text-sm text-slate-600">
          <p>WebSocket 端点: <code className="bg-slate-100 px-2 py-0.5 rounded font-mono text-xs">ws://{window.location.host}/ws/events?consumerId=YOUR_ID</code></p>
          <p>推送消息格式: JSON对象, 包含 <code className="font-mono bg-slate-100 px-1 rounded">type</code>, <code className="font-mono bg-slate-100 px-1 rounded">subscriptionId</code>, <code className="font-mono bg-slate-100 px-1 rounded">event</code> 字段</p>
          <p className="text-xs text-slate-500">保证因果序: 同一消费者收到的事件中, 若事件B依赖A, 则A必先于B推送</p>
        </div>
      </div>
    </div>
  )
}
