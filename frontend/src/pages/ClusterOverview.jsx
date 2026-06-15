import React, { useState, useEffect } from 'react'
import { clusterApi } from '../api.js'
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
  PieChart, Pie, Cell
} from 'recharts'
import { Server, Database, Activity, HardDrive, Cpu } from 'lucide-react'
import { fmtTs, statusBadgeClass, useInterval, fmtBytes } from '../utils.jsx'

const COLORS = ['#0ea5e9', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899', '#14b8a6', '#f97316']

export default function ClusterOverview() {
  const [status, setStatus] = useState(null)
  const [loading, setLoading] = useState(true)

  const load = async () => {
    try {
      const data = await clusterApi.getStatus()
      setStatus(data)
    } catch (e) {
      console.error(e)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [])
  useInterval(load, 5000)

  if (loading || !status) return <div className="text-center py-20 text-slate-500">加载中...</div>

  const partitionData = status.partitions || []
  const barData = Object.entries(status.partitionEventCounts || {}).map(([k, v]) => ({
    name: `P${k}`, count: v,
    leader: status.partitionLeaderSequences?.[k] || 0
  }))

  const pieData = Object.entries(status.partitionEventCounts || {}).map(([k, v]) => ({
    name: `分区${k}`, value: v || 0
  }))

  const nodeStatus = status.nodes?.reduce((acc, n) => {
    acc[n.status] = (acc[n.status] || 0) + 1
    return acc
  }, {}) || {}

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-slate-900">集群概览</h1>
        <p className="text-sm text-slate-500 mt-1">节点ID: <span className="font-mono">{status.nodeId}</span> | 角色: <span className={statusBadgeClass(status.nodeRole)}>{status.nodeRole}</span></p>
      </div>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard label="事件总数" value={Number(status.totalEvents || 0).toLocaleString()} icon={<Database size={20} />} color="primary" />
        <StatCard label="聚合根数量" value={Number(status.totalAggregates || 0).toLocaleString()} icon={<HardDrive size={20} />} color="emerald" />
        <StatCard label="节点数" value={(status.nodes?.length || 0)} sub={
          <span className="flex gap-1">
            {Object.entries(nodeStatus).map(([k, v]) => (
              <span key={k} className={statusBadgeClass(k)}>{k}: {v}</span>
            ))}
          </span>
        } icon={<Server size={20} />} color="amber" />
        <StatCard label="分区数" value={partitionData.length || 8} sub={<span className="text-xs text-slate-500">不可动态调整</span>} icon={<Cpu size={20} />} color="violet" />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="card">
          <div className="card-header">
            <h3 className="font-semibold text-slate-900 flex items-center gap-2">
              <Activity size={16} className="text-primary-600" /> 各分区事件分布
            </h3>
          </div>
          <div className="card-body">
            <div style={{ height: 280 }}>
              <ResponsiveContainer>
                <BarChart data={barData}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                  <XAxis dataKey="name" tick={{ fontSize: 12 }} />
                  <YAxis tick={{ fontSize: 12 }} />
                  <Tooltip />
                  <Legend />
                  <Bar dataKey="count" name="事件数" fill="#0ea5e9" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </div>
        </div>

        <div className="card">
          <div className="card-header">
            <h3 className="font-semibold text-slate-900 flex items-center gap-2">
              <Activity size={16} className="text-emerald-600" /> 分区事件占比
            </h3>
          </div>
          <div className="card-body">
            <div style={{ height: 280 }}>
              <ResponsiveContainer>
                <PieChart>
                  <Pie data={pieData} dataKey="value" nameKey="name" outerRadius={100} label>
                    {pieData.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
                  </Pie>
                  <Tooltip />
                  <Legend />
                </PieChart>
              </ResponsiveContainer>
            </div>
          </div>
        </div>
      </div>

      <div className="card">
        <div className="card-header">
          <h3 className="font-semibold text-slate-900 flex items-center gap-2">
            <Server size={16} className="text-primary-600" /> 集群节点
          </h3>
        </div>
        <div className="overflow-x-auto">
          <table className="table">
            <thead>
              <tr>
                <th>节点ID</th>
                <th>角色</th>
                <th>主机</th>
                <th>gRPC端口</th>
                <th>HTTP端口</th>
                <th>状态</th>
                <th>加入时间</th>
                <th>最后心跳</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {status.nodes?.map((n) => (
                <tr key={n.nodeId}>
                  <td className="font-mono text-xs">{n.nodeId}</td>
                  <td><span className={statusBadgeClass(n.nodeRole)}>{n.nodeRole}</span></td>
                  <td className="font-mono text-xs">{n.host}</td>
                  <td>{n.grpcPort}</td>
                  <td>{n.httpPort}</td>
                  <td><span className={statusBadgeClass(n.status)}>{n.status}</span></td>
                  <td>{fmtTs(n.joinedAt)}</td>
                  <td>{fmtTs(n.lastHeartbeat)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <div className="card-header">
          <h3 className="font-semibold text-slate-900 flex items-center gap-2">
            <Activity size={16} className="text-violet-600" /> 分区复制状态
          </h3>
        </div>
        <div className="overflow-x-auto">
          <table className="table">
            <thead>
              <tr>
                <th>分区</th>
                <th>事件总数</th>
                <th>Leader序列号</th>
                <th>副本状态</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {partitionData.map((p) => (
                <tr key={p.partitionId}>
                  <td><span className="badge badge-info">P{p.partitionId}</span></td>
                  <td>{Number(p.eventCount || 0).toLocaleString()}</td>
                  <td className="font-mono">{Number(p.leaderSequence || 0).toLocaleString()}</td>
                  <td>
                    <div className="flex flex-wrap gap-2">
                      {Object.entries(p.followerSequences || {}).map(([node, seq]) => (
                        <div key={node} className="text-xs bg-slate-50 rounded px-2 py-1 border border-slate-200">
                          <div className="font-mono text-slate-700">{node}</div>
                          <div className="flex gap-2 text-xs mt-0.5">
                            <span className="text-slate-500">Seq: <span className="text-slate-900">{seq}</span></span>
                            <span className={p.followerLagSeconds?.[node] > 10 ? 'text-red-600' : 'text-emerald-600'}>
                              Lag: {p.followerLagSeconds?.[node] || 0}s
                            </span>
                          </div>
                        </div>
                      ))}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

function StatCard({ label, value, icon, sub, color = 'primary' }) {
  const colors = {
    primary: 'from-primary-50 to-primary-100 text-primary-700',
    emerald: 'from-emerald-50 to-emerald-100 text-emerald-700',
    amber: 'from-amber-50 to-amber-100 text-amber-700',
    violet: 'from-violet-50 to-violet-100 text-violet-700',
  }
  const iconColors = {
    primary: 'bg-primary-500',
    emerald: 'bg-emerald-500',
    amber: 'bg-amber-500',
    violet: 'bg-violet-500',
  }
  return (
    <div className={`card p-5 bg-gradient-to-br ${colors[color]}`}>
      <div className="flex items-start justify-between">
        <div>
          <p className="text-sm font-medium text-slate-600">{label}</p>
          <p className="text-3xl font-bold mt-2">{value}</p>
          {sub && <div className="mt-2">{sub}</div>}
        </div>
        <div className={`w-10 h-10 rounded-lg ${iconColors[color]} text-white flex items-center justify-center shadow`}>
          {icon}
        </div>
      </div>
    </div>
  )
}
