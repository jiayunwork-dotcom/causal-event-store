import React, { useState, useEffect } from 'react'
import { snapshotApi } from '../api.js'
import { Camera, Plus, Trash2, RefreshCw, Search, Clock, HardDrive } from 'lucide-react'
import { fmtTs, fmtBytes, useInterval } from '../utils.jsx'

export default function Snapshots() {
  const [aggregateId, setAggregateId] = useState('order-1')
  const [list, setList] = useState([])
  const [loading, setLoading] = useState(false)
  const [creating, setCreating] = useState(false)

  const load = async () => {
    if (!aggregateId) return
    setLoading(true)
    try {
      setList(await snapshotApi.list(aggregateId))
    } catch (e) {
      setList([])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [aggregateId])
  useInterval(load, 10000)

  const create = async () => {
    if (!aggregateId) return
    setCreating(true)
    try {
      await snapshotApi.create(aggregateId)
      await load()
    } catch (e) {
      alert('创建失败: ' + (e.response?.data?.message || e.message))
    } finally {
      setCreating(false)
    }
  }

  const del = async (id) => {
    if (!confirm('确定删除此快照?')) return
    await snapshotApi.delete(id)
    await load()
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-slate-900 flex items-center gap-2"><Camera className="text-violet-500"/> 快照管理</h1>
        <p className="text-sm text-slate-500 mt-1">聚合根状态快照 · 每100条事件自动触发 · 保留最近3个</p>
      </div>

      <div className="card">
        <div className="card-body">
          <div className="grid grid-cols-1 md:grid-cols-12 gap-4 items-end">
            <div className="md:col-span-6">
              <label className="label">聚合根ID</label>
              <div className="flex gap-2">
                <input className="input flex-1" value={aggregateId} onChange={e => setAggregateId(e.target.value)} placeholder="order-1" />
                <button onClick={load} className="btn btn-secondary"><Search size={14}/> 查询</button>
              </div>
            </div>
            <div className="md:col-span-6 flex gap-2">
              <button onClick={create} disabled={creating} className="btn btn-success flex-1">
                {creating ? <RefreshCw size={14} className="animate-spin"/> : <Plus size={14}/>}
                {creating ? ' 创建中...' : ' 手动创建快照'}
              </button>
              <button onClick={load} className="btn btn-secondary">
                <RefreshCw size={14} className={loading ? 'animate-spin' : ''}/>
              </button>
            </div>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="card bg-gradient-to-br from-violet-50 to-violet-100">
          <div className="card-body flex items-center justify-between">
            <div>
              <p className="text-sm text-slate-600">快照总数</p>
              <p className="text-3xl font-bold text-violet-700 mt-2">{list.length}</p>
            </div>
            <div className="w-12 h-12 rounded-lg bg-violet-500 text-white flex items-center justify-center">
              <Camera size={22}/>
            </div>
          </div>
        </div>
        <div className="card bg-gradient-to-br from-sky-50 to-sky-100">
          <div className="card-body flex items-center justify-between">
            <div>
              <p className="text-sm text-slate-600">总大小</p>
              <p className="text-3xl font-bold text-sky-700 mt-2">{fmtBytes(list.reduce((s, x) => s + (x.sizeBytes || 0), 0))}</p>
            </div>
            <div className="w-12 h-12 rounded-lg bg-sky-500 text-white flex items-center justify-center">
              <HardDrive size={22}/>
            </div>
          </div>
        </div>
        <div className="card bg-gradient-to-br from-amber-50 to-amber-100">
          <div className="card-body flex items-center justify-between">
            <div>
              <p className="text-sm text-slate-600">最新事件位置</p>
              <p className="text-3xl font-bold text-amber-700 mt-2">#{list[0]?.lastSequence || 0}</p>
            </div>
            <div className="w-12 h-12 rounded-lg bg-amber-500 text-white flex items-center justify-center">
              <Clock size={22}/>
            </div>
          </div>
        </div>
      </div>

      <div className="card">
        <div className="card-header">
          <h3 className="font-semibold text-slate-900">快照列表 (按事件位置降序)</h3>
        </div>
        <div className="overflow-x-auto">
          {loading && list.length === 0 ? <div className="p-10 text-center text-slate-500">加载中...</div> :
            list.length === 0 ? <div className="p-10 text-center text-slate-500">该聚合根暂无快照, 点击"手动创建快照"</div> :
              <table className="table">
                <thead>
                  <tr>
                    <th>快照ID</th>
                    <th>最后事件序列号</th>
                    <th>大小</th>
                    <th>创建时间</th>
                    <th>新旧</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {list.map((s, i) => (
                    <tr key={s.snapshotId}>
                      <td className="font-mono">#{s.snapshotId}</td>
                      <td className="font-mono">#{s.lastSequence}</td>
                      <td>{fmtBytes(s.sizeBytes)}</td>
                      <td>{fmtTs(s.createdAt)}</td>
                      <td>
                        {i === 0 ? <span className="badge badge-success">最新</span> :
                          i < 3 ? <span className="badge badge-info">保留</span> :
                            <span className="badge badge-warning">待清理</span>}
                      </td>
                      <td>
                        <button onClick={() => del(s.snapshotId)} className="btn btn-danger py-1 px-2 text-xs">
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

      {list[0] && (
        <div className="card">
          <div className="card-header">
            <h3 className="font-semibold text-slate-900">最新快照内容预览</h3>
          </div>
          <div className="card-body">
            <pre className="font-mono text-xs bg-slate-900 text-emerald-400 p-4 rounded-lg max-h-80 overflow-auto">
              {(() => {
                try { return JSON.stringify(JSON.parse(list[0].snapshotState), null, 2) }
                catch { return list[0].snapshotState }
              })()}
            </pre>
          </div>
        </div>
      )}
    </div>
  )
}
