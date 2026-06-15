import React, { useState, useEffect, useCallback } from 'react'
import { projectionApi } from '../api.js'
import { Eye, Plus, Trash2, RefreshCw, Play, Pause, CircleDot, AlertCircle, 
         ChevronDown, ChevronRight, Search, ArrowUpDown, X, Check } from 'lucide-react'
import { fmtTs, useInterval, statusBadgeClass } from '../utils.jsx'

export default function Projections() {
  const [list, setList] = useState([])
  const [loading, setLoading] = useState(true)
  const [showForm, setShowForm] = useState(false)
  const [editingId, setEditingId] = useState(null)
  const [expandedId, setExpandedId] = useState(null)
  const [viewData, setViewData] = useState({})
  const [sortBy, setSortBy] = useState('updatedAt')
  const [sortOrder, setSortOrder] = useState('DESC')
  const [filters, setFilters] = useState({})
  const [page, setPage] = useState(0)
  const [pageSize] = useState(10)

  const defaultForm = {
    projectionId: '',
    name: '',
    description: '',
    aggregateTypePattern: '*',
    eventTypePattern: '*',
    updateStrategy: 'REALTIME',
    outputSchema: JSON.stringify({ fields: {} }, null, 2),
    projectionExpressions: JSON.stringify({}, null, 2),
  }
  const [form, setForm] = useState(defaultForm)

  const load = async () => {
    setLoading(true)
    try {
      const data = await projectionApi.list()
      setList(data)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [])
  useInterval(load, 5000)

  const loadViewData = useCallback(async (id) => {
    if (id !== expandedId) return
    try {
      const params = {
        page,
        pageSize,
        sortBy,
        sortOrder,
        ...filters
      }
      const data = await projectionApi.getData(id, params)
      setViewData(prev => ({ ...prev, [id]: data }))
    } catch (err) {
      console.error('Failed to load view data:', err)
    }
  }, [expandedId, page, pageSize, sortBy, sortOrder, filters])

  useEffect(() => {
    if (expandedId) {
      loadViewData(expandedId)
    }
  }, [expandedId, loadViewData])

  const openCreateForm = () => {
    setEditingId(null)
    setForm(defaultForm)
    setShowForm(true)
  }

  const openEditForm = (p) => {
    setEditingId(p.projectionId)
    setForm({
      projectionId: p.projectionId,
      name: p.name,
      description: p.description || '',
      aggregateTypePattern: p.aggregateTypePattern || '*',
      eventTypePattern: p.eventTypePattern || '*',
      updateStrategy: p.updateStrategy || 'REALTIME',
      outputSchema: JSON.stringify(p.outputSchema || { fields: {} }, null, 2),
      projectionExpressions: JSON.stringify(p.projectionExpressions || {}, null, 2),
    })
    setShowForm(true)
  }

  const submitForm = async (e) => {
    e.preventDefault()
    try {
      let outputSchema, projectionExpressions
      try {
        outputSchema = JSON.parse(form.outputSchema)
        projectionExpressions = JSON.parse(form.projectionExpressions)
      } catch (err) {
        alert('JSON 格式错误: ' + err.message)
        return
      }

      const data = {
        ...form,
        outputSchema,
        projectionExpressions,
      }

      if (editingId) {
        await projectionApi.update(editingId, data)
      } else {
        await projectionApi.create(data)
      }
      setShowForm(false)
      setForm(defaultForm)
      await load()
    } catch (err) {
      alert('操作失败: ' + (err.response?.data?.message || err.message))
    }
  }

  const rebuild = async (id) => {
    if (!confirm('确定重建此投影? 将清空物化视图并从第一条事件开始重放.')) return
    await projectionApi.rebuild(id)
    await load()
  }

  const pause = async (id) => {
    await projectionApi.pause(id)
    await load()
  }

  const resume = async (id) => {
    await projectionApi.resume(id)
    await load()
  }

  const del = async (id) => {
    if (!confirm('确定删除此投影及对应物化视图?')) return
    await projectionApi.delete(id)
    setExpandedId(null)
    await load()
  }

  const toggleExpand = (id) => {
    if (expandedId === id) {
      setExpandedId(null)
      setPage(0)
      setFilters({})
    } else {
      setExpandedId(id)
      setPage(0)
      setFilters({})
      setViewData(prev => ({ ...prev, [id]: null }))
    }
  }

  const handleSort = (col) => {
    if (sortBy === col) {
      setSortOrder(sortOrder === 'ASC' ? 'DESC' : 'ASC')
    } else {
      setSortBy(col)
      setSortOrder('ASC')
    }
  }

  const handleFilterChange = (col, val) => {
    setFilters(prev => ({ ...prev, [col]: val }))
    setPage(0)
  }

  const getSchemaFields = (p) => {
    const schema = p?.outputSchema
    if (!schema || !schema.fields) return []
    return Object.entries(schema.fields).map(([name, def]) => ({
      name,
      type: typeof def === 'string' ? def : def?.type || 'string'
    }))
  }

  const getRebuildProgress = (p) => {
    if (p.status !== 'REBUILDING') return null
    const total = p.rebuildTotalEvents || 0
    const processed = p.rebuildProcessedEvents || 0
    if (total === 0) return 0
    return Math.min(100, Math.round((processed / total) * 100))
  }

  const getStatusLabel = (status) => {
    switch (status) {
      case 'RUNNING': return '运行中'
      case 'STOPPED': return '已暂停'
      case 'REBUILDING': return '重建中'
      case 'ERROR': return '出错'
      default: return status
    }
  }

  const getStatusBadgeClass = (status) => {
    switch (status) {
      case 'RUNNING': return 'bg-emerald-100 text-emerald-700'
      case 'STOPPED': return 'bg-slate-100 text-slate-700'
      case 'REBUILDING': return 'bg-amber-100 text-amber-700'
      case 'ERROR': return 'bg-red-100 text-red-700'
      default: return 'bg-slate-100 text-slate-700'
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between flex-wrap gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 flex items-center gap-2">
            <Eye className="text-emerald-500"/> 投影管理
          </h1>
          <p className="text-sm text-slate-500 mt-1">
            事件流 → 物化视图 (PostgreSQL), 支持 JSON Path 投影、重建、实时查询
          </p>
        </div>
        <div className="flex gap-2">
          <button onClick={load} className="btn btn-secondary">
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''}/> 刷新
          </button>
          <button onClick={openCreateForm} className="btn btn-primary">
            <Plus size={14}/> 新建投影
          </button>
        </div>
      </div>

      {showForm && (
        <div className="card">
          <div className="card-header flex items-center justify-between">
            <h3 className="font-semibold">{editingId ? '编辑投影' : '创建投影'}</h3>
            <button onClick={() => setShowForm(false)} className="text-slate-400 hover:text-slate-600">
              <X size={18}/>
            </button>
          </div>
          <form onSubmit={submitForm} className="card-body space-y-4">
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label className="label">投影ID</label>
                <input
                  className="input font-mono text-xs"
                  value={form.projectionId}
                  onChange={e => setForm({ ...form, projectionId: e.target.value })}
                  placeholder="proj_user_summary"
                  disabled={!!editingId}
                />
              </div>
              <div>
                <label className="label">投影名称</label>
                <input
                  className="input"
                  value={form.name}
                  onChange={e => setForm({ ...form, name: e.target.value })}
                  placeholder="用户汇总视图"
                  required
                />
              </div>
              <div>
                <label className="label">聚合根类型模式 (支持通配符)</label>
                <input
                  className="input font-mono text-xs"
                  value={form.aggregateTypePattern}
                  onChange={e => setForm({ ...form, aggregateTypePattern: e.target.value })}
                  placeholder="User.* 或 Order.*"
                />
              </div>
              <div>
                <label className="label">事件类型模式 (支持通配符)</label>
                <input
                  className="input font-mono text-xs"
                  value={form.eventTypePattern}
                  onChange={e => setForm({ ...form, eventTypePattern: e.target.value })}
                  placeholder="UserCreated 或 *.Updated"
                />
              </div>
              <div>
                <label className="label">更新策略</label>
                <select
                  className="input"
                  value={form.updateStrategy}
                  onChange={e => setForm({ ...form, updateStrategy: e.target.value })}
                >
                  <option value="REALTIME">实时</option>
                  <option value="BATCH">批量</option>
                </select>
              </div>
            </div>
            <div>
              <label className="label">描述</label>
              <textarea
                className="input"
                rows={2}
                value={form.description}
                onChange={e => setForm({ ...form, description: e.target.value })}
              />
            </div>
            <div>
              <label className="label">投影表达式 (JSON Path, JSON格式: {'{'}&#34;字段名&#34;: &#34;$.path.to.field&#34;{'}'})</label>
              <textarea
                className="input font-mono text-xs"
                rows={6}
                value={form.projectionExpressions}
                onChange={e => setForm({ ...form, projectionExpressions: e.target.value })}
                placeholder='{"userName": "$.name", "email": "$.contact.email"}'
              />
              <p className="text-xs text-slate-500 mt-1">
                使用 JSON Path 语法从事件 payload 中提取字段。创建时会校验语法正确性。
              </p>
            </div>
            <div>
              <label className="label">输出 Schema (JSON格式: {'{'}&#34;fields&#34;: {'{'}&#34;字段名&#34;: {'{'}&#34;type&#34;: &#34;string&#34;{'}'}{'}'}{'}'}) </label>
              <textarea
                className="input font-mono text-xs"
                rows={6}
                value={form.outputSchema}
                onChange={e => setForm({ ...form, outputSchema: e.target.value })}
                placeholder='{"fields": {"userName": {"type": "string"}, "age": {"type": "number"}}}'
              />
              <p className="text-xs text-slate-500 mt-1">
                支持的类型: string→varchar, number→numeric, boolean→boolean, object/array→jsonb
              </p>
            </div>
            <div className="flex gap-2 justify-end">
              <button type="button" onClick={() => setShowForm(false)} className="btn btn-secondary">
                取消
              </button>
              <button type="submit" className="btn btn-primary">
                {editingId ? '保存' : '创建'}
              </button>
            </div>
          </form>
        </div>
      )}

      <div className="space-y-3">
        {loading && list.length === 0 ? (
          <div className="card text-center py-10 text-slate-500">加载中...</div>
        ) : list.length === 0 ? (
          <div className="card text-center py-10 text-slate-500">暂无投影</div>
        ) : (
          list.map(p => {
            const progress = getRebuildProgress(p)
            const schemaFields = getSchemaFields(p)
            const isExpanded = expandedId === p.projectionId
            const data = viewData[p.projectionId]

            return (
              <div key={p.projectionId} className="card overflow-hidden">
                <div
                  className="card-header cursor-pointer hover:bg-slate-50 transition-colors"
                  onClick={() => toggleExpand(p.projectionId)}
                >
                  <div className="flex items-center gap-3 flex-1">
                    <div className="text-slate-400">
                      {isExpanded ? <ChevronDown size={18}/> : <ChevronRight size={18}/>}
                    </div>
                    <div className="flex items-center gap-2 flex-1 min-w-0">
                      <CircleDot size={14} className={p.status === 'ERROR' ? 'text-red-500' : 'text-emerald-500'}/>
                      <span className="font-semibold truncate">{p.name}</span>
                      <span className={`px-2 py-0.5 text-xs font-medium rounded-full whitespace-nowrap ${getStatusBadgeClass(p.status)}`}>
                        {getStatusLabel(p.status)}
                      </span>
                    </div>
                    <div className="flex items-center gap-4 text-xs text-slate-500 flex-shrink-0">
                      <span title="聚合根类型">
                        <code className="bg-slate-100 px-1.5 py-0.5 rounded">{p.aggregateTypePattern}</code>
                      </span>
                      <span title="目标表">
                        <code className="bg-slate-100 px-1.5 py-0.5 rounded">{p.targetTable}</code>
                      </span>
                      <span>已处理 <strong className="text-slate-700">{Number(p.processedCount || 0).toLocaleString()}</strong> 事件</span>
                      <span>最后更新: {fmtTs(p.lastProcessedAt)}</span>
                    </div>
                  </div>
                </div>

                {p.status === 'REBUILDING' && progress !== null && (
                  <div className="px-4 py-2 bg-amber-50 border-b border-amber-100">
                    <div className="flex items-center gap-2 text-sm text-amber-700">
                      <RefreshCw size={14} className="animate-spin"/>
                      <span>重建进度: {progress}%</span>
                    </div>
                    <div className="mt-1 h-1.5 bg-amber-200 rounded-full overflow-hidden">
                      <div
                        className="h-full bg-amber-500 transition-all duration-300"
                        style={{ width: `${progress}%` }}
                      />
                    </div>
                    <div className="text-xs text-amber-600 mt-1">
                      已处理 {Number(p.rebuildProcessedEvents || 0).toLocaleString()} / {Number(p.rebuildTotalEvents || 0).toLocaleString()} 事件
                    </div>
                  </div>
                )}

                {p.status === 'ERROR' && p.errorMessage && (
                  <div className="px-4 py-2 bg-red-50 border-b border-red-100">
                    <div className="flex items-start gap-2 text-sm text-red-700">
                      <AlertCircle size={16} className="flex-shrink-0 mt-0.5"/>
                      <div>
                        <div className="font-medium">处理出错</div>
                        <div className="text-xs font-mono mt-1 break-all">{p.errorMessage}</div>
                        {p.errorAt && <div className="text-xs mt-1 text-red-500">出错时间: {fmtTs(p.errorAt)}</div>}
                      </div>
                    </div>
                  </div>
                )}

                <div className="px-4 py-3 bg-slate-50 border-b border-slate-100 flex items-center justify-between">
                  <div className="flex items-center gap-4 text-xs text-slate-500">
                    <span className="font-mono">{p.projectionId}</span>
                    {p.description && <span className="text-slate-600">{p.description}</span>}
                  </div>
                  <div className="flex gap-2" onClick={e => e.stopPropagation()}>
                    {p.status === 'RUNNING' && (
                      <button onClick={() => pause(p.projectionId)} className="btn btn-secondary py-1 px-2 text-xs">
                        <Pause size={12}/> 暂停
                      </button>
                    )}
                    {(p.status === 'STOPPED' || p.status === 'ERROR') && (
                      <button onClick={() => resume(p.projectionId)} className="btn btn-secondary py-1 px-2 text-xs">
                        <Play size={12}/> 恢复
                      </button>
                    )}
                    <button onClick={() => rebuild(p.projectionId)} className="btn btn-secondary py-1 px-2 text-xs">
                      <RefreshCw size={12}/> 重建
                    </button>
                    <button onClick={() => openEditForm(p)} className="btn btn-secondary py-1 px-2 text-xs">
                      编辑
                    </button>
                    <button onClick={() => del(p.projectionId)} className="btn btn-danger py-1 px-2 text-xs">
                      <Trash2 size={12}/> 删除
                    </button>
                  </div>
                </div>

                {isExpanded && (
                  <div className="p-4">
                    <div className="flex items-center justify-between mb-3">
                      <h4 className="font-medium text-slate-700 flex items-center gap-2">
                        <Eye size={16} className="text-emerald-500"/> 物化视图数据预览
                      </h4>
                      <button
                        onClick={() => loadViewData(p.projectionId)}
                        className="btn btn-secondary py-1 px-2 text-xs"
                      >
                        <RefreshCw size={12}/> 刷新
                      </button>
                    </div>

                    {!data ? (
                      <div className="text-center py-6 text-slate-500 text-sm">加载中...</div>
                    ) : (
                      <>
                        <div className="overflow-x-auto">
                          <table className="w-full text-sm">
                            <thead>
                              <tr className="border-b border-slate-200">
                                <th
                                  className="text-left py-2 px-3 font-medium text-slate-600 cursor-pointer hover:bg-slate-50"
                                  onClick={() => handleSort('aggregateId')}
                                >
                                  <div className="flex items-center gap-1">
                                    aggregateId
                                    {sortBy === 'aggregateId' && <ArrowUpDown size={12}/>}
                                  </div>
                                </th>
                                <th
                                  className="text-left py-2 px-3 font-medium text-slate-600 cursor-pointer hover:bg-slate-50"
                                  onClick={() => handleSort('aggregateType')}
                                >
                                  <div className="flex items-center gap-1">
                                    aggregateType
                                    {sortBy === 'aggregateType' && <ArrowUpDown size={12}/>}
                                  </div>
                                </th>
                                {schemaFields.map(f => (
                                  <th
                                    key={f.name}
                                    className="text-left py-2 px-3 font-medium text-slate-600 cursor-pointer hover:bg-slate-50"
                                    onClick={() => handleSort(f.name)}
                                  >
                                    <div className="flex items-center gap-1">
                                      {f.name}
                                      <span className="text-xs text-slate-400 font-normal">({f.type})</span>
                                      {sortBy === f.name && <ArrowUpDown size={12}/>}
                                    </div>
                                  </th>
                                ))}
                                <th
                                  className="text-left py-2 px-3 font-medium text-slate-600 cursor-pointer hover:bg-slate-50"
                                  onClick={() => handleSort('updatedAt')}
                                >
                                  <div className="flex items-center gap-1">
                                    updatedAt
                                    {sortBy === 'updatedAt' && <ArrowUpDown size={12}/>}
                                  </div>
                                </th>
                              </tr>
                              <tr className="border-b border-slate-100 bg-slate-50">
                                <th className="py-1.5 px-3">
                                  <input
                                    className="input text-xs py-1"
                                    placeholder="筛选..."
                                    value={filters.aggregateId || ''}
                                    onChange={e => handleFilterChange('aggregateId', e.target.value)}
                                  />
                                </th>
                                <th className="py-1.5 px-3">
                                  <input
                                    className="input text-xs py-1"
                                    placeholder="筛选..."
                                    value={filters.aggregateType || ''}
                                    onChange={e => handleFilterChange('aggregateType', e.target.value)}
                                  />
                                </th>
                                {schemaFields.map(f => (
                                  <th key={f.name} className="py-1.5 px-3">
                                    <input
                                      className="input text-xs py-1"
                                      placeholder="筛选..."
                                      value={filters[f.name] || ''}
                                      onChange={e => handleFilterChange(f.name, e.target.value)}
                                    />
                                  </th>
                                ))}
                                <th className="py-1.5 px-3"></th>
                              </tr>
                            </thead>
                            <tbody>
                              {data.rows?.length === 0 ? (
                                <tr>
                                  <td colSpan={schemaFields.length + 3} className="text-center py-6 text-slate-400">
                                    暂无数据
                                  </td>
                                </tr>
                              ) : (
                                data.rows?.map((row, idx) => (
                                  <tr key={idx} className="border-b border-slate-50 hover:bg-slate-50">
                                    <td className="py-2 px-3 font-mono text-xs">{row.aggregateId}</td>
                                    <td className="py-2 px-3 text-xs">{row.aggregateType}</td>
                                    {schemaFields.map(f => (
                                      <td key={f.name} className="py-2 px-3 text-xs">
                                        {row[f.name] !== undefined && row[f.name] !== null
                                          ? (typeof row[f.name] === 'object'
                                              ? JSON.stringify(row[f.name])
                                              : String(row[f.name]))
                                          : <span className="text-slate-300">null</span>
                                        }
                                      </td>
                                    ))}
                                    <td className="py-2 px-3 text-xs text-slate-500">
                                      {fmtTs(row.updatedAt)}
                                    </td>
                                  </tr>
                                ))
                              )}
                            </tbody>
                          </table>
                        </div>

                        <div className="flex items-center justify-between mt-3 pt-3 border-t border-slate-100">
                          <div className="text-xs text-slate-500">
                            共 {Number(data.total || 0).toLocaleString()} 条记录
                          </div>
                          <div className="flex items-center gap-2">
                            <button
                              className="btn btn-secondary py-1 px-2 text-xs"
                              disabled={page === 0}
                              onClick={() => setPage(p => Math.max(0, p - 1))}
                            >
                              上一页
                            </button>
                            <span className="text-xs text-slate-600">
                              第 {page + 1} 页
                            </span>
                            <button
                              className="btn btn-secondary py-1 px-2 text-xs"
                              disabled={!data.rows || data.rows.length < pageSize}
                              onClick={() => setPage(p => p + 1)}
                            >
                              下一页
                            </button>
                          </div>
                        </div>
                      </>
                    )}
                  </div>
                )}
              </div>
            )
          })
        )}
      </div>
    </div>
  )
}
