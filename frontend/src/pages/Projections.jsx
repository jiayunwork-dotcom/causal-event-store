import React, { useState, useEffect } from 'react'
import { projectionApi } from '../api.js'
import { Eye, Plus, Trash2, RefreshCw, Play, CircleDot, AlertCircle } from 'lucide-react'
import { fmtTs, useInterval, statusBadgeClass } from '../utils.jsx'

export default function Projections() {
  const [list, setList] = useState([])
  const [loading, setLoading] = useState(true)
  const [showForm, setShowForm] = useState(false)
  const [form, setForm] = useState({
    projectionId: '', name: '', description: '',
    eventTypePattern: '*', handlerLogic: '// 处理逻辑说明\nevent => { ... }',
    targetTable: '',
  })

  const load = async () => {
    setLoading(true)
    try {
      setList(await projectionApi.list())
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [])
  useInterval(load, 5000)

  const create = async (e) => {
    e.preventDefault()
    try {
      await projectionApi.create({
        ...form,
        projectionId: form.projectionId || 'proj_' + Date.now(),
        targetTable: form.targetTable || 'proj_' + (form.projectionId || Date.now()).toLowerCase().replace(/[^a-z0-9]/g, '_'),
      })
      setShowForm(false)
      setForm({ projectionId: '', name: '', description: '', eventTypePattern: '*', handlerLogic: '', targetTable: '' })
      await load()
    } catch (err) {
      alert('创建失败: ' + (err.response?.data?.message || err.message))
    }
  }

  const replay = async (id) => {
    if (!confirm('确定重放此投影? 将清空物化视图并重新计算.')) return
    await projectionApi.replay(id)
    await load()
  }

  const del = async (id) => {
    if (!confirm('确定删除此投影及对应物化视图?')) return
    await projectionApi.delete(id)
    await load()
  }

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between flex-wrap gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 flex items-center gap-2"><Eye className="text-emerald-500"/> 投影管理</h1>
          <p className="text-sm text-slate-500 mt-1">事件流 → 物化视图 (PostgreSQL), 支持重放</p>
        </div>
        <div className="flex gap-2">
          <button onClick={load} className="btn btn-secondary"><RefreshCw size={14} className={loading ? 'animate-spin' : ''}/> 刷新</button>
          <button onClick={() => setShowForm(!showForm)} className="btn btn-primary"><Plus size={14}/> 新建投影</button>
        </div>
      </div>

      {showForm && (
        <div className="card">
          <div className="card-header"><h3 className="font-semibold">创建投影</h3></div>
          <form onSubmit={create} className="card-body space-y-4">
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label className="label">投影ID</label>
                <input className="input font-mono text-xs" value={form.projectionId} onChange={e => setForm({ ...form, projectionId: e.target.value })} placeholder="proj_user_summary" />
              </div>
              <div>
                <label className="label">投影名称</label>
                <input className="input" value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} placeholder="用户汇总视图" required />
              </div>
              <div>
                <label className="label">事件类型模式</label>
                <input className="input font-mono text-xs" value={form.eventTypePattern} onChange={e => setForm({ ...form, eventTypePattern: e.target.value })} placeholder="User.* 或 Order.*" />
              </div>
              <div>
                <label className="label">目标表名 (PostgreSQL)</label>
                <input className="input font-mono text-xs" value={form.targetTable} onChange={e => setForm({ ...form, targetTable: e.target.value })} placeholder="proj_user_summary" />
              </div>
            </div>
            <div>
              <label className="label">描述</label>
              <textarea className="input" rows={2} value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} />
            </div>
            <div>
              <label className="label">处理逻辑描述</label>
              <textarea className="input font-mono text-xs" rows={4} value={form.handlerLogic} onChange={e => setForm({ ...form, handlerLogic: e.target.value })} />
            </div>
            <div className="flex gap-2 justify-end">
              <button type="button" onClick={() => setShowForm(false)} className="btn btn-secondary">取消</button>
              <button type="submit" className="btn btn-primary">创建</button>
            </div>
          </form>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {loading && list.length === 0 ? <div className="col-span-full text-center py-10 text-slate-500">加载中...</div> :
          list.length === 0 ? <div className="col-span-full text-center py-10 text-slate-500">暂无投影</div> :
            list.map(p => (
              <div key={p.projectionId} className="card">
                <div className="card-header">
                  <div className="flex items-center gap-2">
                    <CircleDot size={14} className="text-emerald-500"/>
                    <span className="font-semibold">{p.name}</span>
                  </div>
                  <span className={statusBadgeClass(p.status)}>{p.status}</span>
                </div>
                <div className="card-body space-y-3 text-sm">
                  <div className="flex items-center gap-2 text-xs">
                    <AlertCircle size={12} className="text-slate-400"/>
                    <span className="font-mono text-slate-500">{p.projectionId}</span>
                  </div>
                  {p.description && <p className="text-xs text-slate-600">{p.description}</p>}
                  <div className="space-y-2 pt-2 border-t border-slate-100">
                    <Row label="事件模式" value={<code className="text-xs bg-slate-100 px-1.5 py-0.5 rounded">{p.eventTypePattern}</code>} />
                    <Row label="目标表" value={<code className="text-xs bg-slate-100 px-1.5 py-0.5 rounded">{p.targetTable}</code>} />
                    <Row label="已处理" value={<span className="font-semibold text-primary-700">{Number(p.processedCount || 0).toLocaleString()} 事件</span>} />
                    <Row label="最后事件" value={p.lastProcessedEventId ? <span className="font-mono text-xs">{p.lastProcessedEventId.substring(0, 16)}...</span> : <span className="text-slate-400">无</span>} />
                    <Row label="VC位置" value={<span className="font-mono text-xs">[{p.processedVector?.clocks?.join(',') || '0,0,0,0,0,0,0,0'}]</span>} />
                    <Row label="更新时间" value={fmtTs(p.lastProcessedAt)} />
                  </div>
                  <div className="flex gap-2 pt-3 border-t border-slate-100">
                    <button onClick={() => replay(p.projectionId)} className="btn btn-secondary py-1 px-2 text-xs flex-1">
                      <Play size={12}/> 重放
                    </button>
                    <button onClick={() => del(p.projectionId)} className="btn btn-danger py-1 px-2 text-xs flex-1">
                      <Trash2 size={12}/> 删除
                    </button>
                  </div>
                </div>
              </div>
            ))
        }
      </div>
    </div>
  )
}

function Row({ label, value }) {
  return (
    <div className="flex justify-between text-xs">
      <span className="text-slate-500">{label}</span>
      <span className="text-slate-800">{value}</span>
    </div>
  )
}
