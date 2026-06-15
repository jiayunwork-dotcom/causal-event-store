import React, { useState, useEffect } from 'react'
import ReactFlow, { Background, Controls, MiniMap, Handle, Position, MarkerType } from 'reactflow'
import 'reactflow/dist/style.css'
import { eventApi } from '../api.js'
import { GitBranch, Plus, Trash2, RefreshCw, Info } from 'lucide-react'
import { prettyJson, shortId, fmtTs } from '../utils.jsx'

const Node = ({ data, selected }) => {
  const vc = data.clocks?.join(',') || ''
  return (
    <div className={`bg-white rounded-lg shadow-lg border-2 ${selected ? 'border-primary-500' : 'border-slate-200'} min-w-[200px] overflow-hidden`}>
      <Handle type="target" position={Position.Top} />
      <div className={`px-3 py-2 text-xs font-bold text-white ${data.typeClass}`}>
        {data.type}
      </div>
      <div className="px-3 py-2 space-y-1">
        <div className="font-mono text-[10px] text-slate-500">#{data.seq} · P{data.partition}</div>
        <div className="font-mono text-[10px] text-slate-600 truncate" title={data.id}>{shortId(data.id, 12)}</div>
        <div className="text-[10px] bg-slate-50 px-1.5 py-1 rounded font-mono text-slate-500 break-all">
          VC: [{vc}]
        </div>
      </div>
      <Handle type="source" position={Position.Bottom} />
    </div>
  )
}

const typeClass = (t) => {
  if (t.includes('Created')) return 'bg-emerald-500'
  if (t.includes('Shipped') || t.includes('Reserved')) return 'bg-primary-500'
  if (t.includes('Payment')) return 'bg-violet-500'
  if (t.includes('Conflict')) return 'bg-red-500'
  return 'bg-slate-500'
}

export default function CausalGraph() {
  const [eventIds, setEventIds] = useState('')
  const [idsList, setIdsList] = useState([])
  const [graph, setGraph] = useState(null)
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState(null)
  const [rfInstance, setRfInstance] = useState(null)

  const addSample = async () => {
    const aggId = 'graph-demo-' + Date.now()
    try {
      const base = { aggregateId: aggId, aggregateType: 'Demo' }
      const e1 = { ...base, eventId: 'd_' + Date.now() + '_1', eventType: 'Created', payload: '{}', causalDependencies: [] }
      const e2 = { ...base, eventId: 'd_' + Date.now() + '_2', eventType: 'Updated', payload: '{}', causalDependencies: [e1.eventId] }
      const e3 = { ...base, eventId: 'd_' + Date.now() + '_3', eventType: 'Payment', payload: '{}', causalDependencies: [e1.eventId] }
      const e4 = { ...base, eventId: 'd_' + Date.now() + '_4', eventType: 'Shipped', payload: '{}', causalDependencies: [e2.eventId, e3.eventId] }
      await eventApi.append([e1, e2, e3, e4])
      const ids = [e1, e2, e3, e4].map(e => e.eventId)
      setIdsList(ids)
      setEventIds(ids.join('\n'))
      await buildGraph(ids)
    } catch (e) {
      alert('生成示例失败: ' + (e.response?.data?.message || e.message))
    }
  }

  const buildGraph = async (ids) => {
    if (!ids || ids.length === 0) return
    setLoading(true)
    try {
      const data = await eventApi.getCausalGraph(ids)
      setGraph(data)
    } catch (e) {
      console.error(e)
    } finally {
      setLoading(false)
    }
  }

  const handleBuild = () => {
    const ids = eventIds.split(/[\n,; ]+/).map(s => s.trim()).filter(Boolean)
    setIdsList(ids)
    buildGraph(ids)
  }

  const { nodes, edges } = React.useMemo(() => {
    if (!graph?.events?.length) return { nodes: [], edges: [] }
    const cols = Math.min(graph.events.length, 4)
    const ns = graph.events.map((e, i) => {
      const col = i % cols
      const row = Math.floor(i / cols)
      return {
        id: e.eventId,
        type: 'custom',
        position: { x: col * 260, y: row * 180 },
        data: {
          id: e.eventId,
          type: e.eventType,
          seq: e.sequenceNumber,
          partition: e.partitionId,
          clocks: e.vectorClock?.clocks || [],
          typeClass: typeClass(e.eventType),
          event: e
        }
      }
    })
    const es = (graph.edges || []).map((ed, i) => ({
      id: 'e_' + i + '_' + ed.from + '_' + ed.to,
      source: ed.from,
      target: ed.to,
      animated: ed.type === 'vector',
      label: ed.type === 'vector' ? 'VC' : 'dep',
      labelStyle: { fontSize: 10, fill: ed.type === 'vector' ? '#8b5cf6' : '#0ea5e9' },
      style: { stroke: ed.type === 'vector' ? '#8b5cf6' : '#0ea5e9', strokeWidth: 2 },
      markerEnd: { type: MarkerType.ArrowClosed },
    }))
    return { nodes: ns, edges: es }
  }, [graph])

  const nodeTypes = { custom: Node }

  useEffect(() => {
    if (rfInstance) setTimeout(() => rfInstance.fitView(), 100)
  }, [nodes.length, rfInstance])

  const onSelectionChange = (s) => {
    if (s?.nodes?.[0]?.data?.event) {
      setSelected(s.nodes[0].data.event)
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between flex-wrap gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 flex items-center gap-2"><GitBranch className="text-primary-600"/> 因果图</h1>
          <p className="text-sm text-slate-500 mt-1">可视化展示事件间的因果偏序关系 (DAG)</p>
        </div>
        <button onClick={addSample} className="btn btn-secondary"><Plus size={14}/> 加载示例</button>
      </div>

      <div className="card">
        <div className="card-body">
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
            <div className="lg:col-span-2">
              <label className="label">事件ID列表 (换行/逗号/分号分隔)</label>
              <textarea className="input min-h-[100px] font-mono text-xs"
                value={eventIds} onChange={e => setEventIds(e.target.value)}
                placeholder="evt_xxx\nevt_yyy\nevt_zzz" />
            </div>
            <div className="flex flex-col justify-end gap-2">
              <button onClick={handleBuild} disabled={loading} className="btn btn-primary">
                {loading ? <RefreshCw size={14} className="animate-spin"/> : <GitBranch size={14}/>}
                {loading ? ' 构建中...' : ' 构建因果图'}
              </button>
              <button onClick={() => { setEventIds(''); setIdsList([]); setGraph(null); setSelected(null) }} className="btn btn-secondary">
                <Trash2 size={14}/> 清空
              </button>
              <div className="mt-2 p-3 bg-slate-50 rounded-lg border border-slate-200 text-xs text-slate-600 space-y-1">
                <div className="flex items-center gap-2"><div className="w-3 h-3 rounded-full bg-primary-500"></div> dep: 显式声明的依赖</div>
                <div className="flex items-center gap-2"><div className="w-3 h-3 rounded-full bg-violet-500"></div> VC: 向量时钟推断的因果</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="card lg:col-span-2" style={{ minHeight: 500 }}>
          <div className="card-header">
            <h3 className="font-semibold text-slate-900">DAG 可视化</h3>
            <span className="text-xs text-slate-500">{nodes.length} 节点 · {edges.length} 边</span>
          </div>
          <div className="h-[600px]">
            {nodes.length === 0 ?
              <div className="h-full flex items-center justify-center text-slate-400">
                <div className="text-center">
                  <GitBranch size={48} className="mx-auto mb-3 opacity-30"/>
                  <p>输入事件ID后点击"构建因果图"</p>
                </div>
              </div> :
              <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes}
                onInit={setRfInstance} onSelectionChange={onSelectionChange}
                fitView nodesDraggable>
                <Background color="#e2e8f0" gap={16} />
                <Controls />
                <MiniMap nodeStrokeWidth={3} />
              </ReactFlow>
            }
          </div>
        </div>

        <div className="card">
          <div className="card-header">
            <h3 className="font-semibold text-slate-900 flex items-center gap-2"><Info size={14}/> 节点详情</h3>
          </div>
          <div className="card-body">
            {!selected ? <p className="text-sm text-slate-500">点击图中节点查看详情</p> :
              <div className="space-y-3">
                <div>
                  <label className="label">事件ID</label>
                  <div className="font-mono text-xs break-all">{selected.eventId}</div>
                </div>
                <div className="flex gap-2 flex-wrap">
                  <span className="badge badge-info">{selected.eventType}</span>
                  <span className="badge badge-secondary">P{selected.partitionId} · #{selected.sequenceNumber}</span>
                </div>
                <div>
                  <label className="label">时间</label>
                  <div className="text-sm">{fmtTs(selected.timestamp)}</div>
                </div>
                <div>
                  <label className="label">向量时钟</label>
                  <div className="font-mono text-xs bg-slate-50 p-2 rounded border break-all">
                    [{selected.vectorClock?.clocks?.join(', ') || ''}]
                  </div>
                </div>
                <div>
                  <label className="label">负载</label>
                  <pre className="font-mono text-[11px] bg-slate-900 text-emerald-400 p-2 rounded max-h-60 overflow-auto">{prettyJson(selected.payload)}</pre>
                </div>
              </div>
            }
          </div>
        </div>
      </div>
    </div>
  )
}
