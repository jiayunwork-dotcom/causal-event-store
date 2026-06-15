import React, { useState, useEffect, useRef, useCallback } from 'react'
import { eventApi } from '../api.js'
import { fmtTs, prettyJson } from '../utils.jsx'
import {
  Play, Pause, SkipBack, SkipForward, RotateCcw, FastForward, Search
} from 'lucide-react'

const SPEEDS = [0.5, 1, 2, 5]

function applyMergePatch(target, patch) {
  const result = JSON.parse(JSON.stringify(target))
  if (!patch || typeof patch !== 'object' || Array.isArray(patch)) return result
  for (const [key, value] of Object.entries(patch)) {
    if (value === null) {
      delete result[key]
    } else {
      result[key] = value
    }
  }
  return result
}

export default function ReplayConsole() {
  const [aggregateId, setAggregateId] = useState('')
  const [events, setEvents] = useState([])
  const [loading, setLoading] = useState(false)
  const [currentStep, setCurrentStep] = useState(-1)
  const [states, setStates] = useState([])
  const [playing, setPlaying] = useState(false)
  const [speed, setSpeed] = useState(1)
  const [inputAggId, setInputAggId] = useState('order-1')
  const playTimerRef = useRef(null)

  const computeStates = useCallback((evts) => {
    const result = [{}]
    let state = {}
    for (let i = 0; i < evts.length; i++) {
      let payload
      try {
        payload = typeof evts[i].payload === 'string' ? JSON.parse(evts[i].payload) : evts[i].payload
      } catch {
        payload = {}
      }
      state = applyMergePatch(state, payload)
      result.push(JSON.parse(JSON.stringify(state)))
    }
    return result
  }, [])

  const loadEvents = async () => {
    if (!inputAggId) return
    setLoading(true)
    setPlaying(false)
    if (playTimerRef.current) clearInterval(playTimerRef.current)
    try {
      const data = await eventApi.readByAggregate(inputAggId)
      setEvents(data)
      setAggregateId(inputAggId)
      setCurrentStep(-1)
      setStates(computeStates(data))
    } catch (e) {
      console.error(e)
      setEvents([])
      setStates([])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    return () => {
      if (playTimerRef.current) clearInterval(playTimerRef.current)
    }
  }, [])

  useEffect(() => {
    if (playing && events.length > 0) {
      if (playTimerRef.current) clearInterval(playTimerRef.current)
      const interval = 1000 / speed
      playTimerRef.current = setInterval(() => {
        setCurrentStep(prev => {
          if (prev >= events.length - 1) {
            setPlaying(false)
            if (playTimerRef.current) clearInterval(playTimerRef.current)
            return prev
          }
          return prev + 1
        })
      }, interval)
    } else {
      if (playTimerRef.current) clearInterval(playTimerRef.current)
    }
    return () => {
      if (playTimerRef.current) clearInterval(playTimerRef.current)
    }
  }, [playing, speed, events.length])

  const handlePlay = () => {
    if (currentStep >= events.length - 1) {
      setCurrentStep(-1)
    }
    setPlaying(true)
  }

  const handlePause = () => {
    setPlaying(false)
  }

  const handleStepForward = () => {
    setPlaying(false)
    if (currentStep < events.length - 1) {
      setCurrentStep(prev => prev + 1)
    }
  }

  const handleStepBack = () => {
    setPlaying(false)
    if (currentStep > -1) {
      setCurrentStep(prev => prev - 1)
    }
  }

  const handleReset = () => {
    setPlaying(false)
    setCurrentStep(-1)
  }

  const handleProgressChange = (e) => {
    setPlaying(false)
    const val = Number(e.target.value)
    setCurrentStep(val)
  }

  const currentState = states[currentStep + 1] || {}
  const currentEvent = currentStep >= 0 ? events[currentStep] : null
  const progress = events.length > 0 ? ((currentStep + 1) / events.length) * 100 : 0

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between flex-wrap gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">回放控制台</h1>
          <p className="text-sm text-slate-500 mt-1">按时间轴逐步回放聚合根的事件序列</p>
        </div>
      </div>

      <div className="card">
        <div className="card-body">
          <div className="flex gap-4 items-end">
            <div className="flex-1">
              <label className="label">聚合根ID</label>
              <input
                className="input"
                value={inputAggId}
                onChange={e => setInputAggId(e.target.value)}
                placeholder="输入聚合根ID"
                onKeyDown={e => e.key === 'Enter' && loadEvents()}
              />
            </div>
            <button onClick={loadEvents} className="btn btn-primary" disabled={loading}>
              <Search size={14} /> {loading ? '加载中...' : '加载事件'}
            </button>
          </div>
        </div>
      </div>

      {events.length > 0 && (
        <>
          <div className="card">
            <div className="card-header">
              <h3 className="font-semibold text-slate-900">回放控制</h3>
              <div className="flex items-center gap-3">
                <span className="text-sm text-slate-500">
                  步骤 {currentStep + 1} / {events.length}
                </span>
                <div className="flex items-center gap-1 bg-slate-100 rounded-lg p-1">
                  {SPEEDS.map(s => (
                    <button
                      key={s}
                      onClick={() => setSpeed(s)}
                      className={`px-2 py-1 rounded text-xs font-medium transition-all ${
                        speed === s
                          ? 'bg-primary-600 text-white shadow-sm'
                          : 'text-slate-600 hover:bg-slate-200'
                      }`}
                    >
                      {s}x
                    </button>
                  ))}
                </div>
              </div>
            </div>
            <div className="card-body space-y-4">
              <div className="relative">
                <div className="w-full h-3 bg-slate-200 rounded-full overflow-hidden cursor-pointer">
                  <div
                    className="h-full bg-gradient-to-r from-primary-500 to-primary-600 rounded-full transition-all duration-150"
                    style={{ width: `${progress}%` }}
                  />
                </div>
                <input
                  type="range"
                  min={-1}
                  max={events.length - 1}
                  value={currentStep}
                  onChange={handleProgressChange}
                  className="absolute inset-0 w-full opacity-0 cursor-pointer"
                />
              </div>

              <div className="flex items-center justify-center gap-3">
                <button onClick={handleReset} className="btn btn-secondary p-2" title="重置">
                  <RotateCcw size={16} />
                </button>
                <button
                  onClick={handleStepBack}
                  className="btn btn-secondary p-2"
                  disabled={currentStep <= -1}
                  title="步退"
                >
                  <SkipBack size={16} />
                </button>
                {playing ? (
                  <button onClick={handlePause} className="btn btn-primary p-3 rounded-full" title="暂停">
                    <Pause size={20} />
                  </button>
                ) : (
                  <button onClick={handlePlay} className="btn btn-primary p-3 rounded-full" title="播放">
                    <Play size={20} />
                  </button>
                )}
                <button
                  onClick={handleStepForward}
                  className="btn btn-secondary p-2"
                  disabled={currentStep >= events.length - 1}
                  title="步进"
                >
                  <SkipForward size={16} />
                </button>
                <button
                  onClick={() => { setPlaying(false); setCurrentStep(events.length - 1) }}
                  className="btn btn-secondary p-2"
                  title="跳到末尾"
                >
                  <FastForward size={16} />
                </button>
              </div>
            </div>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <div className="card">
              <div className="card-header">
                <h3 className="font-semibold text-slate-900">事件时间轴</h3>
              </div>
              <div className="max-h-[500px] overflow-y-auto">
                <table className="table">
                  <thead className="sticky top-0 z-10">
                    <tr>
                      <th>#</th>
                      <th>类型</th>
                      <th>时间</th>
                      <th>标签</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {events.map((e, idx) => (
                      <tr
                        key={e.eventId}
                        className={`cursor-pointer transition-colors ${
                          idx === currentStep
                            ? 'bg-primary-50 border-l-4 border-primary-600'
                            : idx <= currentStep
                            ? 'bg-emerald-50/50 border-l-4 border-emerald-300'
                            : 'border-l-4 border-transparent'
                        }`}
                        onClick={() => { setPlaying(false); setCurrentStep(idx) }}
                      >
                        <td className="font-mono text-xs">#{e.sequenceNumber}</td>
                        <td><span className="badge badge-info">{e.eventType}</span></td>
                        <td className="text-xs">{fmtTs(e.timestamp)}</td>
                        <td>
                          <div className="flex gap-1 flex-wrap">
                            {(e.tags || []).map(t => (
                              <span key={t} className="px-1.5 py-0.5 rounded text-xs bg-amber-100 text-amber-800">
                                {t}
                              </span>
                            ))}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="space-y-4">
              <div className="card">
                <div className="card-header">
                  <h3 className="font-semibold text-slate-900">当前事件</h3>
                </div>
                <div className="card-body">
                  {currentEvent ? (
                    <div className="space-y-3">
                      <div className="flex gap-4 text-sm">
                        <span className="text-slate-500">类型:</span>
                        <span className="badge badge-info">{currentEvent.eventType}</span>
                        <span className="text-slate-500">序列:</span>
                        <span className="font-mono">#{currentEvent.sequenceNumber}</span>
                        <span className="text-slate-500">时间:</span>
                        <span>{fmtTs(currentEvent.timestamp)}</span>
                      </div>
                      <div>
                        <label className="label">事件负载</label>
                        <pre className="font-mono text-xs bg-slate-900 text-amber-400 p-3 rounded-lg max-h-40 overflow-auto">
                          {prettyJson(currentEvent.payload)}
                        </pre>
                      </div>
                    </div>
                  ) : (
                    <p className="text-sm text-slate-400">尚未开始回放，点击播放或步进开始</p>
                  )}
                </div>
              </div>

              <div className="card">
                <div className="card-header">
                  <h3 className="font-semibold text-slate-900">
                    累积状态快照
                    <span className="text-xs text-slate-400 ml-2">
                      (步骤 {currentStep + 1} 后)
                    </span>
                  </h3>
                </div>
                <div className="card-body">
                  <pre className="font-mono text-xs bg-slate-900 text-emerald-400 p-4 rounded-lg max-h-[300px] overflow-auto">
                    {prettyJson(currentState)}
                  </pre>
                </div>
              </div>
            </div>
          </div>
        </>
      )}

      {events.length === 0 && !loading && aggregateId && (
        <div className="card">
          <div className="p-10 text-center text-slate-500">
            该聚合根没有事件数据
          </div>
        </div>
      )}
    </div>
  )
}
