import React from 'react'
import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom'
import {
  LayoutDashboard, ListTree, GitBranch, Bell, Eye, Camera, AlertTriangle, Server
} from 'lucide-react'
import ClusterOverview from './pages/ClusterOverview.jsx'
import EventBrowser from './pages/EventBrowser.jsx'
import CausalGraph from './pages/CausalGraph.jsx'
import Subscriptions from './pages/Subscriptions.jsx'
import Projections from './pages/Projections.jsx'
import Snapshots from './pages/Snapshots.jsx'
import Conflicts from './pages/Conflicts.jsx'

const NavItem = ({ to, label, icon: Icon }) => (
  <NavLink to={to}
    className={({ isActive }) =>
      `flex items-center gap-3 px-4 py-3 rounded-lg transition-all ${
        isActive
          ? 'bg-primary-50 text-primary-700 font-semibold border-l-4 border-primary-600'
          : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
      }`
    }>
    <Icon size={18} />
    <span className="text-sm">{label}</span>
  </NavLink>
)

export default function App() {
  return (
    <BrowserRouter>
      <div className="min-h-screen bg-slate-50 flex">
        <aside className="w-64 bg-white border-r border-slate-200 min-h-screen sticky top-0">
          <div className="px-6 py-5 border-b border-slate-100">
            <div className="flex items-center gap-2">
              <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-primary-500 to-primary-700 flex items-center justify-center text-white">
                <Server size={18} />
              </div>
              <div>
                <h1 className="font-bold text-slate-900 text-sm leading-tight">Causal Event Store</h1>
                <p className="text-xs text-slate-500">Admin Console</p>
              </div>
            </div>
          </div>
          <nav className="p-3 space-y-1">
            <NavItem to="/" label="集群概览" icon={LayoutDashboard} />
            <NavItem to="/events" label="事件浏览器" icon={ListTree} />
            <NavItem to="/causal" label="因果图" icon={GitBranch} />
            <NavItem to="/subscriptions" label="订阅管理" icon={Bell} />
            <NavItem to="/projections" label="投影管理" icon={Eye} />
            <NavItem to="/snapshots" label="快照管理" icon={Camera} />
            <NavItem to="/conflicts" label="冲突处理" icon={AlertTriangle} />
          </nav>
        </aside>
        <main className="flex-1 min-w-0">
          <div className="p-8 max-w-7xl mx-auto">
            <Routes>
              <Route path="/" element={<ClusterOverview />} />
              <Route path="/events" element={<EventBrowser />} />
              <Route path="/causal" element={<CausalGraph />} />
              <Route path="/subscriptions" element={<Subscriptions />} />
              <Route path="/projections" element={<Projections />} />
              <Route path="/snapshots" element={<Snapshots />} />
              <Route path="/conflicts" element={<Conflicts />} />
            </Routes>
          </div>
        </main>
      </div>
    </BrowserRouter>
  )
}
