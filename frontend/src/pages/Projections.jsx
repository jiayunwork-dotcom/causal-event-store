import React, { useState, useEffect, useCallback } from 'react'
import { projectionApi } from '../api.js'
import { Eye, Plus, Trash2, RefreshCw, Play, Pause, CircleDot, AlertCircle,
         ChevronDown, ChevronRight, Search, ArrowUpDown, X, Check, Activity,
         GitBranch, History, Layers, Zap } from 'lucide-react'
import { fmtTs, useInterval } from '../utils.jsx'

const HealthDot = ({ status }) => {
  const color = status === 'GREEN' ? 'bg-emerald-400' : status === 'YELLOW' ? 'bg-amber-400' : 'bg-red-500'
  const label = status === 'GREEN' ? '健康' : status === 'YELLOW' ? '警告' : '异常'
  return (
    <span className="inline-flex items-center gap-1" title={label}>
      <span className={`inline-block w-2.5 h-2.5 rounded-full ${color}`}/>
    </span>
  )
}

export default function Projections() {
  const [list, setList] = useState([])
  const [loading, setLoading] = useState(true)
  const [showForm, setShowForm] = useState(false)
  const [editingId, setEditingId] = useState(null)
  const [expandedId, setExpandedId] = useState(null)
  const [expandedTab, setExpandedTab] = useState('data')
  const [viewData, setViewData] = useState({})
  const [changelogData, setChangelogData] = useState({})
  const [versionsData, setVersionsData] = useState({})
  const [metricsData, setMetricsData] = useState({})
  const [sortBy, setSortBy] = useState('updatedAt')
  const [sortOrder, setSortOrder] = useState('DESC')
  const [filters, setFilters] = useState({})
  const [page, setPage] = useState(0)
  const [pageSize] = useState(10)
  const [changelogPage, setChangelogPage] = useState(0)
  const [changelogFilters, setChangelogFilters] = useState({ aggregateId: '' })
  const [expandedChangelog, setExpandedChangelog] = useState(null)
  const [showVersionForm, setShowVersionForm] = useState(false)
  const [versionFormBase, setVersionFormBase] = useState(null)

  const defaultForm = {
    projectionId: '',
    name: '',
    description: '',
    aggregateTypePattern: '*',
    eventTypePattern: '*',
    updateStrategy: 'REALTIME',
    upstreamProjectionId: '',
    outputSchema: JSON.stringify({ fields: {} }, null, 2),
    projectionExpressions: JSON.stringify({}, null, 2),
  }
  const [form, setForm] = useState(defaultForm)

  const defaultVersionForm = {
    projectionExpressions: '',
    outputSchema: '',
  }
  const [versionForm, setVersionForm] = useState(defaultVersionForm)

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
    if (id !== expandedId || expandedTab !== 'data') return
    try {
      const params = { page, pageSize, sortBy, sortOrder, ...filters }
      const data = await projectionApi.getData(id, params)
      setViewData(prev => ({ ...prev, [id]: data }))
    } catch (err) {
      console.error('Failed to load view data:', err)
    }
  }, [expandedId, expandedTab, page, pageSize, sortBy, sortOrder, filters])

  const loadChangelogData = useCallback(async (id) => {
    if (id !== expandedId || expandedTab !== 'changelog') return
    try {
      const params = { page: changelogPage, pageSize: 20 }
      if (changelogFilters.aggregateId) params.aggregateId = changelogFilters.aggregateId
      const data = await projectionApi.getChangelog(id, params)
      setChangelogData(prev => ({ ...prev, [id]: data }))
    } catch (err) {
      console.error('Failed to load changelog:', err)
    }
  }, [expandedId, expandedTab, changelogPage, changelogFilters])

  const loadMetrics = useCallback(async (id) => {
    if (id !== expandedId || expandedTab !== 'metrics') return
    try {
      const data = await projectionApi.getMetrics(id)
      setMetricsData(prev => ({ ...prev, [id]: data }))
    } catch (err) {
      console.error('Failed to load metrics:', err)
    }
  }, [expandedId, expandedTab])

  const loadVersions = useCallback(async (id) => {
    if (id !== expandedId || expandedTab !== 'versions') return
    try {
      const data = await projectionApi.listVersions(id)
      setVersionsData(prev => ({ ...prev, [id]: data }))
    } catch (err) {
      console.error('Failed to load versions:', err)
    }
  }, [expandedId, expandedTab])

  useEffect(() => {
    if (expandedId) {
      if (expandedTab === 'data') loadViewData(expandedId)
      else if (expandedTab === 'changelog') loadChangelogData(expandedId)
      else if (expandedTab === 'metrics') loadMetrics(expandedId)
      else if (expandedTab === 'versions') loadVersions(expandedId)
    }
  }, [expandedId, expandedTab, loadViewData, loadChangelogData, loadMetrics, loadVersions])

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
      upstreamProjectionId: p.upstreamProjectionId || '',
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
      setExpandedTab('data')
    } else {
      setExpandedId(id)
      setPage(0)
      setFilters({})
      setExpandedTab('data')
      setViewData(prev => ({ ...prev, [id]: null }))
      setChangelogData(prev => ({ ...prev, [id]: null }))
      setMetricsData(prev => ({ ...prev, [id]: null }))
      setVersionsData(prev => ({ ...prev, [id]: null }))
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

  const openCreateVersion = (baseId) => {
    setVersionFormBase(baseId)
    setVersionForm({ projectionExpressions: '', outputSchema: '' })
    setShowVersionForm(true)
  }

  const submitVersionForm = async (e) => {
    e.preventDefault()
    try {
      const data = {}
      if (versionForm.projectionExpressions) {
        data.projectionExpressions = JSON.parse(versionForm.projectionExpressions)
      }
      if (versionForm.outputSchema) {
        data.outputSchema = JSON.parse(versionForm.outputSchema)
      }
      await projectionApi.createVersion(versionFormBase, data)
      setShowVersionForm(false)
      await load()
      if (expandedId) loadVersions(expandedId)
    } catch (err) {
      alert('创建版本失败: ' + (err.response?.data?.message || err.message))
    }
  }

  const activateVersion = async (baseId, version) => {
    if (!confirm(`确定激活版本 v${version}? 当前活跃版本将被归档.`)) return
    try {
      await projectionApi.activateVersion(baseId, version)
      await load()
      if (expandedId) loadVersions(expandedId)
    } catch (err) {
      alert('激活版本失败: ' + (err.response?.data?.message || err.message))
    }
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

  const getVersionStatusBadge = (vs) => {
    switch (vs) {
      case 'ACTIVE': return 'bg-blue-100 text-blue-700'
      case 'STANDBY': return 'bg-amber-100 text-amber-700'
      case 'ARCHIVED': return 'bg-slate-100 text-slate-500'
      default: return 'bg-slate-100 text-slate-700'
    }
  }

  const getVersionStatusLabel = (vs) => {
    switch (vs) {
      case 'ACTIVE': return '活跃'
      case 'STANDBY': return '预备'
      case 'ARCHIVED': return '归档'
      default: return vs
    }
  }

  const parseJsonSafe = (str) => {
    try {
      return typeof str === 'string' ? JSON.parse(str) : str
    } catch {
      return str
    }
  }

  const computeDiff = (before, after) => {
    const beforeObj = parseJsonSafe(before) || {}
    const afterObj = parseJsonSafe(after) || {}
    const allKeys = new Set([...Object.keys(beforeObj), ...Object.keys(afterObj)])
    const diffs = []
    for (const key of allKeys) {
      const bv = beforeObj[key]
      const av = afterObj[key]
      if (JSON.stringify(bv) !== JSON.stringify(av)) {
        diffs.push({ key, before: bv, after: av })
      }
    }
    return diffs
  }

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between flex-wrap gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 flex items-center gap-2">
            <Eye className="text-emerald-500"/> 投影管理
          </h1>
          <p className="text-sm text-slate-500 mt-1">
            事件流 → 物化视图, 支持链式依赖、版本灰度切换、健康监控、变更历史
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
              <div>
                <label className="label">上游投影ID (可选, 链式依赖)</label>
                <input
                  className="input font-mono text-xs"
                  value={form.upstreamProjectionId}
                  onChange={e => setForm({ ...form, upstreamProjectionId: e.target.value })}
                  placeholder="留空则监听事件流, 填写则监听上游物化视图"
                />
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

      {showVersionForm && (
        <div className="card">
          <div className="card-header flex items-center justify-between">
            <h3 className="font-semibold">创建新版本 - {versionFormBase}</h3>
            <button onClick={() => setShowVersionForm(false)} className="text-slate-400 hover:text-slate-600">
              <X size={18}/>
            </button>
          </div>
          <form onSubmit={submitVersionForm} className="card-body space-y-4">
            <p className="text-sm text-slate-500">
              新版本将自动开始重建追赶。留空则沿用当前活跃版本的配置。重建完成后可切换为活跃版本。
            </p>
            <div>
              <label className="label">投影表达式 (可选, 留空沿用当前版本)</label>
              <textarea
                className="input font-mono text-xs"
                rows={4}
                value={versionForm.projectionExpressions}
                onChange={e => setVersionForm({ ...versionForm, projectionExpressions: e.target.value })}
                placeholder='留空则沿用当前版本的表达式'
              />
            </div>
            <div>
              <label className="label">输出 Schema (可选, 留空沿用当前版本)</label>
              <textarea
                className="input font-mono text-xs"
                rows={4}
                value={versionForm.outputSchema}
                onChange={e => setVersionForm({ ...versionForm, outputSchema: e.target.value })}
                placeholder='留空则沿用当前版本的Schema'
              />
            </div>
            <div className="flex gap-2 justify-end">
              <button type="button" onClick={() => setShowVersionForm(false)} className="btn btn-secondary">取消</button>
              <button type="submit" className="btn btn-primary">创建版本</button>
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
            const changelog = changelogData[p.projectionId]
            const metrics = metricsData[p.projectionId]
            const versions = versionsData[p.projectionId]

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
                      <HealthDot status={p.healthStatus}/>
                      <span className="font-semibold truncate">{p.name}</span>
                      <span className={`px-2 py-0.5 text-xs font-medium rounded-full whitespace-nowrap ${getStatusBadgeClass(p.status)}`}>
                        {getStatusLabel(p.status)}
                      </span>
                      {p.version > 1 && (
                        <span className="px-1.5 py-0.5 text-xs font-mono bg-blue-50 text-blue-600 rounded">
                          v{p.version}
                        </span>
                      )}
                      {p.versionStatus === 'STANDBY' && (
                        <span className={`px-1.5 py-0.5 text-xs font-medium rounded-full ${getVersionStatusBadge(p.versionStatus)}`}>
                          预备
                        </span>
                      )}
                      {p.versionStatus === 'ARCHIVED' && (
                        <span className={`px-1.5 py-0.5 text-xs font-medium rounded-full ${getVersionStatusBadge(p.versionStatus)}`}>
                          归档
                        </span>
                      )}
                      {p.upstreamProjectionId && (
                        <span className="flex items-center gap-1 px-1.5 py-0.5 text-xs bg-purple-50 text-purple-600 rounded" title={`上游: ${p.upstreamProjectionId}`}>
                          <GitBranch size={10}/> 链式
                        </span>
                      )}
                      {p.pauseReason && (
                        <span className="text-xs text-amber-600" title={p.pauseReason}>
                          ({p.pauseReason})
                        </span>
                      )}
                    </div>
                    <div className="flex items-center gap-4 text-xs text-slate-500 flex-shrink-0">
                      {p.upstreamProjectionId ? (
                        <span title="上游投影">
                          <code className="bg-purple-50 px-1.5 py-0.5 rounded text-purple-600">↑ {p.upstreamProjectionId}</code>
                        </span>
                      ) : (
                        <span title="聚合根类型">
                          <code className="bg-slate-100 px-1.5 py-0.5 rounded">{p.aggregateTypePattern}</code>
                        </span>
                      )}
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
                    <span className="text-slate-400">v{p.version} · {p.versionStatus}</span>
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
                    <button onClick={() => openCreateVersion(p.baseProjectionId || p.projectionId)} className="btn btn-secondary py-1 px-2 text-xs">
                      <Layers size={12}/> 新版本
                    </button>
                    <button onClick={() => del(p.projectionId)} className="btn btn-danger py-1 px-2 text-xs">
                      <Trash2 size={12}/> 删除
                    </button>
                  </div>
                </div>

                {isExpanded && (
                  <div>
                    <div className="flex border-b border-slate-200 bg-slate-50">
                      {[
                        { key: 'data', label: '物化视图', icon: Eye },
                        { key: 'changelog', label: '变更历史', icon: History },
                        { key: 'metrics', label: '健康指标', icon: Activity },
                        { key: 'versions', label: '版本管理', icon: Layers },
                      ].map(tab => (
                        <button
                          key={tab.key}
                          onClick={() => setExpandedTab(tab.key)}
                          className={`flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
                            expandedTab === tab.key
                              ? 'border-emerald-500 text-emerald-700'
                              : 'border-transparent text-slate-500 hover:text-slate-700'
                          }`}
                        >
                          <tab.icon size={14}/> {tab.label}
                        </button>
                      ))}
                    </div>

                    <div className="p-4">
                      {expandedTab === 'data' && (
                        <>
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
                                      {schemaFields.length === 0 ? (
                                        <th className="text-left py-2 px-3 font-medium text-slate-600">(无字段定义)</th>
                                      ) : (
                                        schemaFields.map(f => (
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
                                        ))
                                      )}
                                    </tr>
                                    {schemaFields.length > 0 && (
                                      <tr className="border-b border-slate-100 bg-slate-50">
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
                                      </tr>
                                    )}
                                  </thead>
                                  <tbody>
                                    {data.rows?.length === 0 ? (
                                      <tr>
                                        <td colSpan={schemaFields.length || 1} className="text-center py-6 text-slate-400">暂无数据</td>
                                      </tr>
                                    ) : (
                                      data.rows?.map((row, idx) => (
                                        <tr key={idx} className="border-b border-slate-50 hover:bg-slate-50">
                                          {schemaFields.length === 0 ? (
                                            <td className="py-2 px-3 text-xs text-slate-400">(无字段)</td>
                                          ) : (
                                            schemaFields.map(f => (
                                              <td key={f.name} className="py-2 px-3 text-xs">
                                                {row[f.name] !== null && row[f.name] !== undefined
                                                  ? (typeof row[f.name] === 'object'
                                                      ? JSON.stringify(row[f.name])
                                                      : String(row[f.name]))
                                                  : <span className="text-slate-300">null</span>
                                                }
                                              </td>
                                            ))
                                          )}
                                        </tr>
                                      ))
                                    )}
                                  </tbody>
                                </table>
                              </div>
                              <div className="flex items-center justify-between mt-3 pt-3 border-t border-slate-100">
                                <div className="text-xs text-slate-500">共 {Number(data.total || 0).toLocaleString()} 条记录</div>
                                <div className="flex items-center gap-2">
                                  <button className="btn btn-secondary py-1 px-2 text-xs" disabled={page === 0} onClick={() => setPage(p => Math.max(0, p - 1))}>上一页</button>
                                  <span className="text-xs text-slate-600">第 {page + 1} 页</span>
                                  <button className="btn btn-secondary py-1 px-2 text-xs" disabled={!data.rows || data.rows.length < pageSize} onClick={() => setPage(p => p + 1)}>下一页</button>
                                </div>
                              </div>
                            </>
                          )}
                        </>
                      )}

                      {expandedTab === 'changelog' && (
                        <>
                          <div className="flex items-center justify-between mb-3">
                            <h4 className="font-medium text-slate-700 flex items-center gap-2">
                              <History size={16} className="text-emerald-500"/> 变更历史
                            </h4>
                            <div className="flex items-center gap-2">
                              <input
                                className="input text-xs py-1 w-48"
                                placeholder="按聚合根ID筛选..."
                                value={changelogFilters.aggregateId}
                                onChange={e => { setChangelogFilters({ aggregateId: e.target.value }); setChangelogPage(0) }}
                              />
                              <button onClick={() => loadChangelogData(p.projectionId)} className="btn btn-secondary py-1 px-2 text-xs">
                                <Search size={12}/> 查询
                              </button>
                            </div>
                          </div>
                          {!changelog ? (
                            <div className="text-center py-6 text-slate-500 text-sm">加载中...</div>
                          ) : changelog.records?.length === 0 ? (
                            <div className="text-center py-6 text-slate-400 text-sm">暂无变更记录</div>
                          ) : (
                            <>
                              <div className="space-y-2">
                                {changelog.records?.map((rec) => {
                                  const diffs = computeDiff(rec.beforeValue, rec.afterValue)
                                  const isExpandedRecord = expandedChangelog === rec.id
                                  return (
                                    <div key={rec.id} className="border border-slate-200 rounded-lg overflow-hidden">
                                      <div
                                        className="flex items-center gap-3 px-3 py-2 bg-slate-50 cursor-pointer hover:bg-slate-100 text-sm"
                                        onClick={() => setExpandedChangelog(isExpandedRecord ? null : rec.id)}
                                      >
                                        <span className={`px-1.5 py-0.5 text-xs font-medium rounded ${
                                          rec.changeType === 'INSERT' ? 'bg-green-100 text-green-700' : 'bg-blue-100 text-blue-700'
                                        }`}>
                                          {rec.changeType}
                                        </span>
                                        <span className="font-mono text-xs text-slate-600">{rec.aggregateId}</span>
                                        <span className="text-xs text-slate-400">{fmtTs(rec.createdAt)}</span>
                                        {rec.triggerEventId && (
                                          <span className="text-xs text-slate-400 font-mono" title="触发事件ID">
                                            事件: {rec.triggerEventId.substring(0, 8)}...
                                          </span>
                                        )}
                                        <span className="ml-auto text-xs text-slate-400">
                                          {diffs.length} 个字段变更
                                        </span>
                                      </div>
                                      {isExpandedRecord && (
                                        <div className="px-3 py-2 space-y-1.5">
                                          {diffs.length === 0 ? (
                                            <div className="text-xs text-slate-400">无字段级差异</div>
                                          ) : (
                                            diffs.map((d, i) => (
                                              <div key={i} className="flex items-start gap-2 text-xs">
                                                <span className="font-mono font-medium text-slate-700 min-w-[80px]">{d.key}</span>
                                                <div className="flex-1">
                                                  <div className="text-red-600 bg-red-50 px-1.5 py-0.5 rounded font-mono break-all">
                                                    - {d.before !== null && d.before !== undefined ? JSON.stringify(d.before) : 'null'}
                                                  </div>
                                                  <div className="text-green-600 bg-green-50 px-1.5 py-0.5 rounded font-mono break-all mt-0.5">
                                                    + {d.after !== null && d.after !== undefined ? JSON.stringify(d.after) : 'null'}
                                                  </div>
                                                </div>
                                              </div>
                                            ))
                                          )}
                                        </div>
                                      )}
                                    </div>
                                  )
                                })}
                              </div>
                              <div className="flex items-center justify-between mt-3 pt-3 border-t border-slate-100">
                                <div className="text-xs text-slate-500">共 {Number(changelog.total || 0).toLocaleString()} 条记录</div>
                                <div className="flex items-center gap-2">
                                  <button className="btn btn-secondary py-1 px-2 text-xs" disabled={changelogPage === 0} onClick={() => setChangelogPage(p => Math.max(0, p - 1))}>上一页</button>
                                  <span className="text-xs text-slate-600">第 {changelogPage + 1} 页</span>
                                  <button className="btn btn-secondary py-1 px-2 text-xs" disabled={!changelog.records || changelog.records.length < 20} onClick={() => setChangelogPage(p => p + 1)}>下一页</button>
                                </div>
                              </div>
                            </>
                          )}
                        </>
                      )}

                      {expandedTab === 'metrics' && (
                        <>
                          <div className="flex items-center justify-between mb-3">
                            <h4 className="font-medium text-slate-700 flex items-center gap-2">
                              <Activity size={16} className="text-emerald-500"/> 健康指标
                            </h4>
                            <button onClick={() => loadMetrics(p.projectionId)} className="btn btn-secondary py-1 px-2 text-xs">
                              <RefreshCw size={12}/> 刷新
                            </button>
                          </div>
                          {!metrics ? (
                            <div className="text-center py-6 text-slate-500 text-sm">加载中...</div>
                          ) : (
                            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                              <div className="bg-slate-50 rounded-lg p-4">
                                <div className="text-xs text-slate-500 mb-1">平均处理延迟</div>
                                <div className="text-xl font-bold text-slate-800">
                                  {metrics.avgLatencyMs != null ? `${metrics.avgLatencyMs.toFixed(1)}ms` : '-'}
                                </div>
                                <div className="text-xs text-slate-400 mt-1">
                                  {metrics.avgLatencyMs != null && metrics.avgLatencyMs < 500 ? '✓ 正常' : metrics.avgLatencyMs != null && metrics.avgLatencyMs < 2000 ? '⚠ 偏高' : metrics.avgLatencyMs != null ? '✗ 过高' : ''}
                                </div>
                              </div>
                              <div className="bg-slate-50 rounded-lg p-4">
                                <div className="text-xs text-slate-500 mb-1">处理吞吐量</div>
                                <div className="text-xl font-bold text-slate-800">
                                  {metrics.throughputPerMin != null ? `${metrics.throughputPerMin.toFixed(1)}/min` : '-'}
                                </div>
                                <div className="text-xs text-slate-400 mt-1">每分钟处理事件数</div>
                              </div>
                              <div className="bg-slate-50 rounded-lg p-4">
                                <div className="text-xs text-slate-500 mb-1">错误率</div>
                                <div className="text-xl font-bold text-slate-800">
                                  {metrics.errorRate != null ? `${(metrics.errorRate * 100).toFixed(2)}%` : '-'}
                                </div>
                                <div className="text-xs text-slate-400 mt-1">
                                  {metrics.errorRate != null && metrics.errorRate < 0.01 ? '✓ 正常' : metrics.errorRate != null && metrics.errorRate < 0.05 ? '⚠ 偏高' : metrics.errorRate != null ? '✗ 过高' : ''}
                                </div>
                              </div>
                              <div className="bg-slate-50 rounded-lg p-4">
                                <div className="text-xs text-slate-500 mb-1">物化视图行数</div>
                                <div className="text-xl font-bold text-slate-800">
                                  {metrics.mvRowCount != null ? Number(metrics.mvRowCount).toLocaleString() : '-'}
                                </div>
                                <div className="text-xs text-slate-400 mt-1">
                                  健康状态: <HealthDot status={metrics.healthStatus}/> {metrics.healthStatus === 'GREEN' ? '健康' : metrics.healthStatus === 'YELLOW' ? '警告' : metrics.healthStatus === 'RED' ? '异常' : '-'}
                                </div>
                              </div>
                            </div>
                          )}
                          {metrics && (
                            <div className="mt-3 text-xs text-slate-400">
                              指标更新时间: {fmtTs(metrics.metricsUpdatedAt)} · 统计窗口: 滑动5分钟 · 刷新间隔: 30秒
                            </div>
                          )}
                        </>
                      )}

                      {expandedTab === 'versions' && (
                        <>
                          <div className="flex items-center justify-between mb-3">
                            <h4 className="font-medium text-slate-700 flex items-center gap-2">
                              <Layers size={16} className="text-emerald-500"/> 版本管理
                            </h4>
                            <button onClick={() => loadVersions(p.projectionId)} className="btn btn-secondary py-1 px-2 text-xs">
                              <RefreshCw size={12}/> 刷新
                            </button>
                          </div>
                          {!versions ? (
                            <div className="text-center py-6 text-slate-500 text-sm">加载中...</div>
                          ) : versions.length <= 1 && p.version === 1 && p.versionStatus === 'ACTIVE' ? (
                            <div className="text-center py-6">
                              <div className="text-slate-400 text-sm mb-2">当前仅有 v1 活跃版本</div>
                              <button onClick={() => openCreateVersion(p.baseProjectionId || p.projectionId)} className="btn btn-secondary text-xs">
                                <Plus size={12}/> 创建新版本
                              </button>
                            </div>
                          ) : (
                            <div className="space-y-2">
                              {versions.map(v => (
                                <div key={v.projectionId} className={`border rounded-lg p-3 ${
                                  v.versionStatus === 'ACTIVE' ? 'border-blue-200 bg-blue-50' :
                                  v.versionStatus === 'STANDBY' ? 'border-amber-200 bg-amber-50' :
                                  'border-slate-200 bg-slate-50'
                                }`}>
                                  <div className="flex items-center justify-between">
                                    <div className="flex items-center gap-3">
                                      <span className="text-sm font-bold">v{v.version}</span>
                                      <span className={`px-2 py-0.5 text-xs font-medium rounded-full ${getVersionStatusBadge(v.versionStatus)}`}>
                                        {getVersionStatusLabel(v.versionStatus)}
                                      </span>
                                      <span className={`px-2 py-0.5 text-xs font-medium rounded-full ${getStatusBadgeClass(v.status)}`}>
                                        {getStatusLabel(v.status)}
                                      </span>
                                      <span className="text-xs text-slate-500">
                                        处理 {Number(v.processedCount || 0).toLocaleString()} 事件
                                      </span>
                                      {v.archivedAt && (
                                        <span className="text-xs text-slate-400">
                                          归档于 {fmtTs(v.archivedAt)}
                                        </span>
                                      )}
                                    </div>
                                    <div className="flex gap-2">
                                      {v.versionStatus === 'STANDBY' && v.status !== 'REBUILDING' && (
                                        <button
                                          onClick={() => activateVersion(p.baseProjectionId || p.projectionId, v.version)}
                                          className="btn btn-primary py-1 px-2 text-xs"
                                        >
                                          <Zap size={12}/> 切换为活跃
                                        </button>
                                      )}
                                      {v.versionStatus === 'STANDBY' && v.status === 'REBUILDING' && (
                                        <span className="text-xs text-amber-600 flex items-center gap-1">
                                          <RefreshCw size={12} className="animate-spin"/> 重建中...
                                        </span>
                                      )}
                                    </div>
                                  </div>
                                  <div className="mt-2 text-xs text-slate-500 font-mono">
                                    ID: {v.projectionId} · 表: {v.targetTable}
                                  </div>
                                </div>
                              ))}
                              <div className="pt-2">
                                <button onClick={() => openCreateVersion(p.baseProjectionId || p.projectionId)} className="btn btn-secondary text-xs">
                                  <Plus size={12}/> 创建新版本
                                </button>
                              </div>
                            </div>
                          )}
                        </>
                      )}
                    </div>
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
