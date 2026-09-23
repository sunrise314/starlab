<script setup>
import { ref, reactive, onMounted, onUnmounted, nextTick } from 'vue'
import * as Cesium from 'cesium'
import * as echarts from 'echarts'
import {
  getConstellation,
  getSatellites,
  getGroundStations,
  getVisibleLinks,
  simulateHandover,
  compareHandover,
  fetchTimeline,
  configureConstellation,
  resetConstellation,
  getHandoverEvents,
  clearHandoverEvents,
  analyzeInterference,
  getIslTopology,
  computeIslRoute
} from '@/services/api'

/**
 * starlab 三维可视化主场景
 *
 * ── 数据流 ──
 *   onMounted
 *     ① 初始化 Cesium Viewer（地球 + 中国版图视角）
 *     ② 一次性加载地面站（Marker + Label）
 *     ③ 启动 1Hz 轮询循环：
 *        - GET /api/satellites → 更新 12 颗卫星 Entity 位置
 *        - GET /api/links      → 渲染可见链路 Polyline（绿色）
 *        - GET /api/constellation → 概览面板（首帧一次即可）
 *
 * ── 关键设计 ──
 * 1. 卫星 Entity 用 SampledPositionProperty 不合算（这里只显示实时点），
 *    直接每秒 entity.position 赋值 Cartesian3.fromDegrees(lon, lat, alt_m)
 * 2. 卫星轨迹：保留过去 90s 的位置点画 Polyline（按卫星 id 维护）
 * 3. 链路 Polyline 每帧重建——因为卫星在动，不能复用旧 entity
 * 4. viewer.destroy() 必须在 onUnmounted 调——避免反复进出页面黑屏
 */

const viewerContainer = ref(null)
let viewer = null

// ── UI 响应式状态 ──
const constellationInfo = reactive({
  name: '',
  totalSatellites: 0,
  totalStations: 0,
  planes: 0,
  satsPerPlane: 0,
  inclination: 0,
  altitude: 0,
  epoch: ''
})
const visibleLinkCount = ref(0)
const selectedSat = ref(null)        // 选中卫星详情
const wsTick = ref(0)                 // 刷新计数（仅用于显示活跃指示）
let lastTickTime = 0
const tickLatency = ref(0)            // 上一帧 RTT (ms)

// ── Cesium Entity 管理 ──
const satEntities = new Map()         // satelliteId → Cesium.Entity
const stationEntities = new Map()      // stationId → Cesium.Entity
const linkEntities = new Map()         // `${satId}#${stationId}` → Cesium.Entity
const orbitTrailEntities = new Map()  // satelliteId → Cesium.Entity (Polyline)
const orbitTrailPoints = new Map()    // satelliteId → Cartesian3[] (最近 N 个点)

// ── 轮询 ──
let pollTimer = null
const POLL_INTERVAL = 1000  // 1 秒刷新（轨道传播足够流畅）
const TRAIL_MAX_POINTS = 90 // 90 个点 = 90s 轨迹（一圈 ~95min，能看出方向）

// ── STCN 切换仿真 ──
const simParams = reactive({
  durationSec: 3600,   // 默认 1 小时
  tickSec: 10,         // 默认 10s tick
  algorithm: 'greedy'  // greedy / predictive / interference-aware
})
const simLoading = ref(false)
const simResult = ref(null)       // 最近一次 simulate 结果
const compareLoading = ref(false)
const compareResult = ref(null)   // compare 结果（双跑）
const handoverEvents = ref([])    // 环形缓冲事件列表
const handoverPanelOpen = ref(true)

// ── 时间轴回放 ──
const timelineData = ref([])         // TickSnapshot[]
const timelineEvents = ref([])       // HandoverEvent[]
const timelineMeta = reactive({
  simStart: '', simEnd: '', durationSec: 0, tickSec: 0,
  totalTicks: 0, handoverCount: 0, handoverRatePerMin: 0, algorithmId: ''
})
const timelineIndex = ref(0)
const timelinePlaying = ref(false)
const timelineSpeed = ref(1)
const timelineLoading = ref(false)
const isReplayMode = ref(false)
let replayTimer = null
const SPEED_OPTIONS = [1, 2, 5, 10]
const replayLinkEntities = new Map()       // 回放专用链路 Entity
const replayHighlightEntities = new Map()   // 事件高亮 Entity

// ── 链路质量曲线（ECharts） ──
const qualityChartContainer = ref(null)
let qualityChart = null
const qualityLoading = ref(false)
const showQualityPanel = ref(false)
const qualityTimelineGreedy = ref([])
const qualityTimelinePredictive = ref([])

// ── 星座动态配置 ──
const constellationConfigOpen = ref(false)
const constellationConfig = reactive({
  total: 12,
  planes: 3,
  inclination: 53.0,
  altitudeKm: 550.0,
  phasingF: 1
})
const constellationConfigLoading = ref(false)

// ── 干扰分析（方向5） ──
const interferencePanelOpen = ref(false)
const interferenceLoading = ref(false)
const interferenceResult = ref(null)   // { analyzedAt, statistics, results }
const interferenceParams = reactive({
  targetSatelliteId: '',   // 空 = 分析所有卫星
  stationId: ''            // 空 = 分析所有地面站
})
const groundStationsList = ref([])  // 地面站下拉数据
const satelliteList = ref([])       // 卫星下拉数据（来自 constellationInfo.satellites）

// ── 星间链路 ISL（方向6） ──
const islPanelOpen = ref(false)
const islLoading = ref(false)
const islTopology = ref(null)          // { analyzedAt, statistics, adjacency, degrees, links }
const islAutoRefresh = ref(false)      // 定时刷新开关
let islRefreshTimer = null
const islRouteLoading = ref(false)
const islRouteResult = ref(null)       // { analyzedAt, path, hopCount, totalLatencyMs, reachable, segments }
const islRouteParams = reactive({
  sourceSatelliteId: '',
  sourceStationId: 'BJO',
  destinationSatelliteId: 'STARLAB-08',
  maxDistanceKm: 5000
})
const islLinkEntities = new Map()          // ISL Polyline Entity（虚线）
const islRouteEntities = new Map()         // 路径高亮 Entity（实线 + 加粗）

async function runHandoverSim() {
  simLoading.value = true
  compareResult.value = null  // 清空对比结果
  try {
    await clearHandoverEvents().catch(() => {})
    const result = await simulateHandover({
      durationSec: simParams.durationSec,
      tickSec: simParams.tickSec,
      algorithm: simParams.algorithm
    })
    simResult.value = result
    const buf = await getHandoverEvents(50)
    handoverEvents.value = buf.events || []
    handoverPanelOpen.value = true
  } catch (e) {
    console.error('[starlab] 切换仿真失败:', e.message)
  } finally {
    simLoading.value = false
  }
}

async function runHandoverCompare() {
  compareLoading.value = true
  simResult.value = null  // 清空单次结果
  try {
    await clearHandoverEvents().catch(() => {})
    const result = await compareHandover({
      durationSec: simParams.durationSec,
      tickSec: simParams.tickSec
    })
    compareResult.value = result
    // 把贪心的事件拉进环形缓冲，方便用户看
    if (result?.greedy?.events) {
      handoverEvents.value = result.greedy.events
    }
    handoverPanelOpen.value = true
  } catch (e) {
    console.error('[starlab] 对比仿真失败:', e.message)
  } finally {
    compareLoading.value = false
  }
}

async function refreshHandoverEvents() {
  try {
    const buf = await getHandoverEvents(50)
    handoverEvents.value = buf.events || []
  } catch (e) {
    console.warn('[starlab] 查询切换事件失败:', e.message)
  }
}

async function clearAllHandoverEvents() {
  try {
    await clearHandoverEvents()
    handoverEvents.value = []
    simResult.value = null
  } catch (e) {
    console.warn('[starlab] 清空切换事件失败:', e.message)
  }
}

// ── 链路质量曲线函数 ──

/** 并行加载贪心 + 预测式两个算法的 timeline，画 3 子图叠加曲线 */
async function loadQualityChart() {
  qualityLoading.value = true
  showQualityPanel.value = true
  try {
    const [g, p] = await Promise.all([
      fetchTimeline({ durationSec: simParams.durationSec, tickSec: simParams.tickSec, algorithm: 'greedy' }),
      fetchTimeline({ durationSec: simParams.durationSec, tickSec: simParams.tickSec, algorithm: 'predictive' })
    ])
    qualityTimelineGreedy.value = g.timeline || []
    qualityTimelinePredictive.value = p.timeline || []
    await nextTick()
    renderQualityChart()
  } catch (e) {
    console.error('[starlab] 加载链路质量曲线失败:', e.message)
  } finally {
    qualityLoading.value = false
  }
}

/** 计算每个 tick 的「平均仰角 / 平均时延 / 链路可用率」 */
function computeTimelineStats(timeline) {
  const elev = [], lat = [], avail = []
  for (const tick of timeline) {
    const connected = tick.stations.filter(s => s.linkStatus === 'CONNECTED')
    const avgElev = connected.length > 0
      ? connected.reduce((a, s) => a + s.elevationDeg, 0) / connected.length
      : 0
    const avgLat = connected.length > 0
      ? connected.reduce((a, s) => a + s.latencyMs, 0) / connected.length
      : 0
    elev.push(Math.round(avgElev * 10) / 10)
    lat.push(Math.round(avgLat * 10) / 10)
    avail.push(Math.round(connected.length / tick.stations.length * 1000) / 10)
  }
  return { elev, lat, avail }
}

function renderQualityChart() {
  if (!qualityChartContainer.value) return
  if (!qualityChart) {
    qualityChart = echarts.init(qualityChartContainer.value, 'dark')
  }
  const xLabels = qualityTimelineGreedy.value.map(t => fmtSimSec(t.simSec))
  const g = computeTimelineStats(qualityTimelineGreedy.value)
  const p = computeTimelineStats(qualityTimelinePredictive.value)
  qualityChart.setOption({
    backgroundColor: 'transparent',
    color: ['#67c23a', '#00d9ff'],
    tooltip: { trigger: 'axis' },
    legend: { data: ['贪心 v1', '预测 v2'], textStyle: { color: '#ccc' }, top: 0 },
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    grid: [
      { left: 50, right: 20, top: 32, height: '20%' },
      { left: 50, right: 20, top: '40%', height: '20%' },
      { left: 50, right: 20, top: '68%', height: '22%' }
    ],
    xAxis: [
      { type: 'category', data: xLabels, gridIndex: 0, show: false },
      { type: 'category', data: xLabels, gridIndex: 1, show: false },
      { type: 'category', data: xLabels, gridIndex: 2,
        axisLabel: { color: '#888', fontSize: 10, interval: Math.floor(xLabels.length / 12) } }
    ],
    yAxis: [
      { type: 'value', name: '仰角°', gridIndex: 0, nameTextStyle: { color: '#aaa' },
        axisLabel: { color: '#888' }, splitLine: { lineStyle: { color: '#222' } } },
      { type: 'value', name: '时延ms', gridIndex: 1, nameTextStyle: { color: '#aaa' },
        axisLabel: { color: '#888' }, splitLine: { lineStyle: { color: '#222' } } },
      { type: 'value', name: '可用率%', gridIndex: 2, nameTextStyle: { color: '#aaa' },
        axisLabel: { color: '#888' }, splitLine: { lineStyle: { color: '#222' } },
        min: 0, max: 100 }
    ],
    series: [
      { name: '贪心 v1', type: 'line', data: g.elev, xAxisIndex: 0, yAxisIndex: 0, symbol: 'none', lineStyle: { width: 1.5 } },
      { name: '预测 v2', type: 'line', data: p.elev, xAxisIndex: 0, yAxisIndex: 0, symbol: 'none', lineStyle: { width: 1.5 } },
      { name: '贪心 v1', type: 'line', data: g.lat, xAxisIndex: 1, yAxisIndex: 1, symbol: 'none', lineStyle: { width: 1.5 } },
      { name: '预测 v2', type: 'line', data: p.lat, xAxisIndex: 1, yAxisIndex: 1, symbol: 'none', lineStyle: { width: 1.5 } },
      { name: '贪心 v1', type: 'line', data: g.avail, xAxisIndex: 2, yAxisIndex: 2, symbol: 'none', lineStyle: { width: 1.5 } },
      { name: '预测 v2', type: 'line', data: p.avail, xAxisIndex: 2, yAxisIndex: 2, symbol: 'none', lineStyle: { width: 1.5 } }
    ]
  })
}

function closeQualityPanel() {
  showQualityPanel.value = false
  if (qualityChart) {
    qualityChart.dispose()
    qualityChart = null
  }
}

// ── 干扰分析函数 ──

/**
 * 调用后端 /api/interference/analyze：
 *   - 不传 targetSatelliteId / stationId → 分析所有 (卫星 × 地面站) 组合
 *   - 只传 targetSatelliteId → 该卫星对所有地面站
 *   - 只传 stationId → 所有卫星对该地面站
 *   - 都传 → 单条精确分析
 */
async function runInterferenceAnalysis() {
  interferenceLoading.value = true
  try {
    const params = {}
    if (interferenceParams.targetSatelliteId) {
      params.targetSatelliteId = interferenceParams.targetSatelliteId
    }
    if (interferenceParams.stationId) {
      params.stationId = interferenceParams.stationId
    }
    const result = await analyzeInterference(params)
    interferenceResult.value = result
    interferencePanelOpen.value = true
  } catch (e) {
    console.error('[starlab] 干扰分析失败:', e.message)
  } finally {
    interferenceLoading.value = false
  }
}

/** 重置干扰分析参数与结果 */
function resetInterference() {
  interferenceParams.targetSatelliteId = ''
  interferenceParams.stationId = ''
  interferenceResult.value = null
}

/** 干扰结果中"可用"标签着色 */
function availabilityClass(link) {
  if (!link.visible) return 'tag-gray'
  return link.linkAvailable ? 'tag-green' : 'tag-red'
}
function availabilityLabel(link) {
  if (!link.visible) return '不可见'
  return link.linkAvailable ? '可用' : '干扰'
}

/** C/I 数值着色：>15 dB 绿、9~15 dB 黄、<9 dB 红 */
function cirClass(cirDb) {
  if (cirDb == null) return ''
  if (cirDb >= 15) return 'value-good'
  if (cirDb >= 9) return 'value-warn'
  return 'value-bad'
}

// ── C/I 时序曲线（复用 ECharts 模式） ──

const interferenceChartContainer = ref(null)
let interferenceChart = null
const showInterferenceChart = ref(false)

/**
 * 拉取 timeline + 画 C/I 时序曲线
 * 复用 fetchTimeline，只跑一种算法（干扰不依赖切换策略）
 */
async function loadInterferenceChart() {
  interferenceLoading.value = true
  showInterferenceChart.value = true
  try {
    const result = await fetchTimeline({
      durationSec: simParams.durationSec,
      tickSec: simParams.tickSec,
      algorithm: 'greedy'
    })
    const timeline = result.timeline || []
    await nextTick()
    renderInterferenceChart(timeline)
  } catch (e) {
    console.error('[starlab] 加载 C/I 时序曲线失败:', e.message)
  } finally {
    interferenceLoading.value = false
  }
}

/**
 * 画 C/I 时序曲线 —— 3 子图：
 *   ① 平均 C/I + 9dB 阈值线
 *   ② 同频干扰卫星数
 *   ③ 可用链路占比（干扰判定）
 */
function renderInterferenceChart(timeline) {
  if (!interferenceChartContainer.value) return
  if (!interferenceChart) {
    interferenceChart = echarts.init(interferenceChartContainer.value, 'dark')
  }

  const xLabels = timeline.map(t => fmtSimSec(t.simSec))
  const cirs = [], cochs = [], avails = []

  for (const tick of timeline) {
    const connected = tick.stations.filter(s => s.linkStatus === 'CONNECTED' && s.cirDb != null)
    // ① 平均 C/I
    const avgCir = connected.length > 0
      ? connected.reduce((a, s) => a + s.cirDb, 0) / connected.length
      : null
    cirs.push(avgCir != null ? Math.round(avgCir * 10) / 10 : null)

    // ② 平均同频干扰源数
    const avgCoch = connected.length > 0
      ? connected.reduce((a, s) => a + (s.coChannelCount || 0), 0) / connected.length
      : 0
    cochs.push(Math.round(avgCoch * 10) / 10)

    // ③ 干扰下可用链路占比
    const availStations = tick.stations.filter(s => s.linkAvailable === true).length
    avails.push(Math.round(availStations / tick.stations.length * 1000) / 10)
  }

  interferenceChart.setOption({
    backgroundColor: 'transparent',
    color: ['#9b59b6'],
    tooltip: { trigger: 'axis' },
    legend: { data: ['平均 C/I', '同频干扰数', '干扰可用率'], textStyle: { color: '#ccc' }, top: 0 },
    grid: [
      { left: 50, right: 20, top: 32, height: '22%' },
      { left: 50, right: 20, top: '48%', height: '18%' },
      { left: 50, right: 20, top: '72%', height: '22%' }
    ],
    xAxis: [
      { type: 'category', data: xLabels, gridIndex: 0, show: false },
      { type: 'category', data: xLabels, gridIndex: 1, show: false },
      { type: 'category', data: xLabels, gridIndex: 2,
        axisLabel: { color: '#888', fontSize: 10, interval: Math.floor(xLabels.length / 12) } }
    ],
    yAxis: [
      { type: 'value', name: 'C/I dB', gridIndex: 0, nameTextStyle: { color: '#aaa' },
        axisLabel: { color: '#888' }, splitLine: { lineStyle: { color: '#222' } } },
      { type: 'value', name: '同频数', gridIndex: 1, nameTextStyle: { color: '#aaa' },
        axisLabel: { color: '#888' }, splitLine: { lineStyle: { color: '#222' } },
        min: 0 },
      { type: 'value', name: '可用率%', gridIndex: 2, nameTextStyle: { color: '#aaa' },
        axisLabel: { color: '#888' }, splitLine: { lineStyle: { color: '#222' } },
        min: 0, max: 100 }
    ],
    series: [
      {
        name: '平均 C/I', type: 'line', data: cirs,
        xAxisIndex: 0, yAxisIndex: 0, symbol: 'none',
        lineStyle: { width: 1.5, color: '#9b59b6' },
        areaStyle: { color: 'rgba(155,89,182,0.15)' },
        markLine: {
          symbol: 'none',
          data: [{ yAxis: 9, name: 'C/I 阈值 9dB', lineStyle: { color: '#e74c3c', type: 'dashed' } }]
        }
      },
      { name: '同频干扰数', type: 'line', data: cochs,
        xAxisIndex: 1, yAxisIndex: 1, symbol: 'none',
        lineStyle: { width: 1.5, color: '#f39c12' } },
      { name: '干扰可用率', type: 'line', data: avails,
        xAxisIndex: 2, yAxisIndex: 2, symbol: 'none',
        lineStyle: { width: 1.5, color: '#67c23a' },
        areaStyle: { color: 'rgba(103,194,58,0.15)' } }
    ]
  })
}

function closeInterferenceChart() {
  showInterferenceChart.value = false
  if (interferenceChart) { interferenceChart.dispose(); interferenceChart = null }
}

// ── 星间链路 ISL 函数 ──

/** 拉取当前 ISL 拓扑 + 渲染卫星间虚线 */
async function loadIslTopology() {
  islLoading.value = true
  try {
    const result = await getIslTopology({ maxDistanceKm: islRouteParams.maxDistanceKm })
    islTopology.value = result
    islPanelOpen.value = true
    drawIslLinks(result.links || [])
  } catch (e) {
    console.error('[starlab] ISL 拓扑加载失败:', e.message)
  } finally {
    islLoading.value = false
  }
}

/** 切换自动刷新（每 10s 拉一次拓扑 + 重绘） */
function toggleIslAutoRefresh() {
  islAutoRefresh.value = !islAutoRefresh.value
  if (islAutoRefresh.value) {
    // 立即先拉一次
    loadIslTopology()
    islRefreshTimer = setInterval(() => {
      if (islAutoRefresh.value) loadIslTopology()
    }, 10000)
  } else {
    if (islRefreshTimer) { clearInterval(islRefreshTimer); islRefreshTimer = null }
  }
}

/** 渲染卫星间 ISL 虚线（同面=青色，跨面=紫色） */
function drawIslLinks(links) {
  // 先清掉旧 ISL Entity
  for (const entity of islLinkEntities.values()) {
    viewer.entities.remove(entity)
  }
  islLinkEntities.clear()

  links.forEach(link => {
    const sat1 = satEntities.get(link.sat1Id)
    const sat2 = satEntities.get(link.sat2Id)
    if (!sat1 || !sat2) return
    const isCrossPlane = link.linkType === 'cross-plane'
    const color = isCrossPlane
      ? Cesium.Color.fromCssColorString('#9b59b6').withAlpha(0.7)
      : Cesium.Color.fromCssColorString('#00d9ff').withAlpha(0.8)
    const entity = viewer.entities.add({
      id: `isl-${link.sat1Id}-${link.sat2Id}`,
      name: `ISL ${link.sat1Id} ↔ ${link.sat2Id}`,
      polyline: {
        positions: new Cesium.CallbackProperty(() => {
          if (!sat1?.position || !sat2?.position) return []
          return [
            sat1.position.getValue(Cesium.JulianDate.now()),
            sat2.position.getValue(Cesium.JulianDate.now())
          ]
        }, false),
        width: 1.2,
        material: new Cesium.PolylineDashMaterialProperty({
          color,
          dashLength: 8
        })
      },
      description: `
        <div style="padding:10px;font-size:13px;min-width:240px">
          <h4 style="margin:0 0 6px;color:#9b59b6">🔗 ISL ${link.sat1Id} ↔ ${link.sat2Id}</h4>
          <p>类型: <strong>${link.linkType}</strong></p>
          <p>距离: <strong>${link.distanceKm?.toFixed(1)} km</strong></p>
          <p>时延: <strong>${link.latencyMs?.toFixed(3)} ms</strong></p>
        </div>
      `
    })
    islLinkEntities.set(`${link.sat1Id}-${link.sat2Id}`, entity)
  })
}

/** 计算多跳路由 + 高亮路径 */
async function computeRoute() {
  islRouteLoading.value = true
  // 清掉旧路径高亮
  for (const entity of islRouteEntities.values()) {
    viewer.entities.remove(entity)
  }
  islRouteEntities.clear()

  try {
    const params = {
      destinationSatelliteId: islRouteParams.destinationSatelliteId,
      maxDistanceKm: islRouteParams.maxDistanceKm
    }
    // 二选一：优先 sourceSatelliteId
    if (islRouteParams.sourceSatelliteId) {
      params.sourceSatelliteId = islRouteParams.sourceSatelliteId
    } else if (islRouteParams.sourceStationId) {
      params.sourceStationId = islRouteParams.sourceStationId
    } else {
      throw new Error('需要指定源卫星或源地面站')
    }
    const result = await computeIslRoute(params)
    islRouteResult.value = result
    drawRouteHighlight(result.path || [], result.segments || [])
  } catch (e) {
    console.error('[starlab] ISL 路由计算失败:', e.message)
  } finally {
    islRouteLoading.value = false
  }
}

/** 高亮路径段：实线 + 加粗，按段类型着色 */
function drawRouteHighlight(path, segments) {
  if (!path || path.length < 2) return
  segments.forEach((seg, idx) => {
    const fromEntity = seg.from.startsWith('STATION:')
      ? stationEntities.get(seg.from.substring('STATION:'.length))
      : satEntities.get(seg.from)
    const toEntity = seg.to.startsWith('STATION:')
      ? stationEntities.get(seg.to.substring('STATION:'.length))
      : satEntities.get(seg.to)
    if (!fromEntity || !toEntity) return

    // 接入段=绿色实线，intra-plane=青色实线，cross-plane=紫色实线
    let color
    if (seg.type === 'ground-to-sat' || seg.type === 'sat-to-ground') {
      color = Cesium.Color.fromCssColorString('#67c23a').withAlpha(0.95)
    } else if (seg.type === 'intra-plane') {
      color = Cesium.Color.fromCssColorString('#00d9ff').withAlpha(0.95)
    } else {
      color = Cesium.Color.fromCssColorString('#9b59b6').withAlpha(0.95)
    }

    const entity = viewer.entities.add({
      id: `route-${idx}-${seg.from}-${seg.to}`,
      name: `Route segment ${seg.from} → ${seg.to}`,
      polyline: {
        positions: new Cesium.CallbackProperty(() => {
          if (!fromEntity?.position || !toEntity?.position) return []
          return [
            fromEntity.position.getValue(Cesium.JulianDate.now()),
            toEntity.position.getValue(Cesium.JulianDate.now())
          ]
        }, false),
        width: 4,
        material: color
      },
      description: `
        <div style="padding:10px;font-size:13px;min-width:240px">
          <h4 style="margin:0 0 6px;color:#f39c12">🛰️ 多跳路径段 ${idx + 1}</h4>
          <p>${seg.from} → ${seg.to}</p>
          <p>类型: <strong>${seg.type}</strong></p>
          <p>时延: <strong>${seg.latencyMs?.toFixed(3)} ms</strong></p>
        </div>
      `
    })
    islRouteEntities.set(`route-${idx}`, entity)
  })
}

/** 清除 ISL 拓扑和路径高亮 */
function clearIslOverlays() {
  for (const entity of islLinkEntities.values()) viewer.entities.remove(entity)
  for (const entity of islRouteEntities.values()) viewer.entities.remove(entity)
  islLinkEntities.clear()
  islRouteEntities.clear()
  islTopology.value = null
  islRouteResult.value = null
  if (islRefreshTimer) { clearInterval(islRefreshTimer); islRefreshTimer = null }
  islAutoRefresh.value = false
}

/** 路由段类型着色 */
function segTypeClass(type) {
  if (type === 'ground-to-sat' || type === 'sat-to-ground') return 'seg-ground'
  if (type === 'intra-plane') return 'seg-intra'
  return 'seg-cross'
}
function segLabel(type) {
  if (type === 'ground-to-sat') return '星地'
  if (type === 'sat-to-ground') return '地星'
  if (type === 'intra-plane') return '同面'
  if (type === 'cross-plane') return '跨面'
  return type
}

// ── 星座动态配置函数 ──

/** 应用新 Walker 参数：调后端 configure → 清空场景 → 重新加载 */
async function applyConstellationConfig() {
  // 客户端校验
  const T = constellationConfig.total
  const P = constellationConfig.planes
  if (T <= 0 || P <= 0 || T % P !== 0) {
    alert(`Walker 参数非法：T=${T} P=${P}（需 T%P=0）`)
    return
  }
  constellationConfigLoading.value = true
  try {
    // 退出回放/质量面板避免干扰
    if (isReplayMode.value) exitReplayMode()
    if (showQualityPanel.value) closeQualityPanel()
    const overview = await configureConstellation({
      total: T,
      planes: P,
      inclination: constellationConfig.inclination,
      altitudeKm: constellationConfig.altitudeKm,
      phasingF: constellationConfig.phasingF
    })
    Object.assign(constellationInfo, overview)
    // 清空所有卫星 Entity（重新 pollOnce 会创建新卫星）
    for (const entity of satEntities.values()) viewer.entities.remove(entity)
    satEntities.clear()
    for (const entity of orbitTrailEntities.values()) viewer.entities.remove(entity)
    orbitTrailEntities.clear()
    orbitTrailPoints.clear()
    console.log('[starlab] 星座已重配:', overview.name)
    // 立即拉一次新数据
    await pollOnce()
    constellationConfigOpen.value = false
  } catch (e) {
    console.error('[starlab] 星座配置失败:', e.message)
    alert('星座配置失败: ' + e.message)
  } finally {
    constellationConfigLoading.value = false
  }
}

/** 恢复默认 Walker 12/3/1，53°/550km */
async function resetConstellationConfig() {
  constellationConfigLoading.value = true
  try {
    if (isReplayMode.value) exitReplayMode()
    if (showQualityPanel.value) closeQualityPanel()
    const overview = await resetConstellation()
    Object.assign(constellationInfo, overview)
    // 同步表单
    Object.assign(constellationConfig, {
      total: 12, planes: 3, inclination: 53.0, altitudeKm: 550.0, phasingF: 1
    })
    for (const entity of satEntities.values()) viewer.entities.remove(entity)
    satEntities.clear()
    for (const entity of orbitTrailEntities.values()) viewer.entities.remove(entity)
    orbitTrailEntities.clear()
    orbitTrailPoints.clear()
    console.log('[starlab] 星座已重置为默认:', overview.name)
    await pollOnce()
    constellationConfigOpen.value = false
  } catch (e) {
    console.error('[starlab] 星座重置失败:', e.message)
  } finally {
    constellationConfigLoading.value = false
  }
}

// ── 时间轴回放函数 ──

/** 加载时间轴：调后端 /timeline → 存 timeline + events → 自动进入回放模式并播放 */
async function loadTimeline() {
  timelineLoading.value = true
  pausePlayback()
  try {
    await clearHandoverEvents().catch(() => {})
    const result = await fetchTimeline({
      durationSec: simParams.durationSec,
      tickSec: simParams.tickSec,
      algorithm: simParams.algorithm
    })
    timelineData.value = result.timeline || []
    timelineEvents.value = result.events || []
    Object.assign(timelineMeta, {
      simStart: result.simStart,
      simEnd: result.simEnd,
      durationSec: result.durationSec,
      tickSec: result.tickSec,
      totalTicks: result.totalTicks,
      handoverCount: result.handoverCount,
      handoverRatePerMin: result.handoverRatePerMin,
      algorithmId: result.algorithmId
    })
    timelineIndex.value = 0
    enterReplayMode()
    renderTick(0)
    startPlayback()
  } catch (e) {
    console.error('[starlab] 加载时间轴失败:', e.message)
  } finally {
    timelineLoading.value = false
  }
}

/** 进入回放模式：暂停实时轮询，清掉实时链路 */
function enterReplayMode() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
  isReplayMode.value = true
  for (const entity of linkEntities.values()) viewer.entities.remove(entity)
  linkEntities.clear()
}

/** 退出回放模式：清掉回放链路 + 高亮，恢复实时轮询 */
function exitReplayMode() {
  pausePlayback()
  isReplayMode.value = false
  for (const entity of replayLinkEntities.values()) viewer.entities.remove(entity)
  replayLinkEntities.clear()
  for (const entity of replayHighlightEntities.values()) viewer.entities.remove(entity)
  replayHighlightEntities.clear()
  if (!pollTimer) {
    pollOnce().then(() => {
      pollTimer = setInterval(pollOnce, POLL_INTERVAL)
    })
  }
}

function startPlayback() {
  if (timelineData.value.length === 0) return
  if (timelineIndex.value >= timelineData.value.length - 1) {
    timelineIndex.value = 0
    renderTick(0)
  }
  timelinePlaying.value = true
  // tickMs: 1x=1000ms/tick, 2x=500ms, 5x=200ms, 10x=100ms
  const tickMs = Math.max(50, Math.round(1000 / timelineSpeed.value))
  if (replayTimer) clearInterval(replayTimer)
  replayTimer = setInterval(() => {
    if (timelineIndex.value >= timelineData.value.length - 1) {
      pausePlayback()
      return
    }
    timelineIndex.value++
    renderTick(timelineIndex.value)
  }, tickMs)
}

function pausePlayback() {
  if (replayTimer) { clearInterval(replayTimer); replayTimer = null }
  timelinePlaying.value = false
}

function togglePlayback() {
  if (timelinePlaying.value) pausePlayback()
  else startPlayback()
}

function seekTimeline(idx) {
  timelineIndex.value = idx
  renderTick(idx)
}

function setSpeed(speed) {
  timelineSpeed.value = speed
  if (timelinePlaying.value) startPlayback()  // 重启 interval 用新速率
}

/** 格式化 simSec → mm:ss */
function fmtSimSec(sec) {
  if (sec == null) return '00:00'
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/** 渲染当前 tick 的所有卫星位置 + 链路 + 事件高亮 */
function renderTick(idx) {
  if (!timelineData.value[idx]) return
  const tick = timelineData.value[idx]
  // 1. 更新卫星位置 + 点颜色随链路状态变化
  for (const state of tick.stations) {
    const entity = satEntities.get(state.satId)
    if (!entity) continue
    const altM = (state.altitude || 0) * 1000
    entity.position = Cesium.Cartesian3.fromDegrees(state.longitude, state.latitude, altM)
    if (entity.point) {
      if (state.linkStatus === 'CONNECTED') {
        entity.point.color = Cesium.Color.LIME
      } else if (state.linkStatus === 'BROKEN') {
        entity.point.color = Cesium.Color.RED
      } else if (state.linkStatus === 'COOLDOWN') {
        entity.point.color = Cesium.Color.GRAY
      }
    }
  }
  // 2. 重建回放链路：CONNECTED 的画线，其他状态删除
  const currentKeys = new Set()
  for (const state of tick.stations) {
    if (state.linkStatus !== 'CONNECTED' || !state.stationId) continue
    const key = `${state.satId}#${state.stationId}`
    currentKeys.add(key)
    if (replayLinkEntities.has(key)) continue
    const satEntity = satEntities.get(state.satId)
    const stationEntity = stationEntities.get(state.stationId)
    if (!satEntity || !stationEntity) continue
    const entity = viewer.entities.add({
      id: `replay-link-${key}`,
      name: `回放链路 ${state.satId} ↔ ${state.stationId}`,
      polyline: {
        positions: new Cesium.CallbackProperty(() => {
          const sat = satEntities.get(state.satId)
          const st = stationEntities.get(state.stationId)
          if (!sat?.position || !st?.position) return []
          return [sat.position.getValue(Cesium.JulianDate.now()),
                  st.position.getValue(Cesium.JulianDate.now())]
        }, false),
        width: 3,
        material: Cesium.Color.LIME.withAlpha(0.9)
      }
    })
    replayLinkEntities.set(key, entity)
  }
  for (const [key, entity] of replayLinkEntities.entries()) {
    if (!currentKeys.has(key)) {
      viewer.entities.remove(entity)
      replayLinkEntities.delete(key)
    }
  }
  // 3. 事件高亮：在当前 tick 触发事件的卫星位置画大圆点
  for (const entity of replayHighlightEntities.values()) viewer.entities.remove(entity)
  replayHighlightEntities.clear()
  const eventsAtTick = tick.time
    ? timelineEvents.value.filter(e => e.triggerTime === tick.time)
    : []
  for (const evt of eventsAtTick) {
    const satState = tick.stations.find(s => s.satId === evt.satelliteId)
    if (!satState) continue
    const altM = (satState.altitude || 0) * 1000
    const position = Cesium.Cartesian3.fromDegrees(satState.longitude, satState.latitude, altM)
    const color = evt.triggerType === 'LINK_BREAK' ? Cesium.Color.RED
                : evt.triggerType === 'PREDICTIVE' ? Cesium.Color.CYAN
                : Cesium.Color.ORANGE
    const entity = viewer.entities.add({
      id: `replay-hl-${evt.satelliteId}-${tick.simSec}`,
      position,
      point: {
        pixelSize: 22,
        color: color.withAlpha(0.5),
        outlineColor: color,
        outlineWidth: 3,
        heightReference: Cesium.HeightReference.NONE
      }
    })
    replayHighlightEntities.set(evt.satelliteId, entity)
  }
}

/** 格式化切换触发时间 (ISO → HH:mm:ss) */
function fmtTrigger(iso) {
  if (!iso) return '-'
  return iso.slice(11, 19)
}

/** 触发类型中文名 */
function triggerLabel(type) {
  switch (type) {
    case 'ELEVATION_FALL': return '仰角切换'
    case 'LINK_BREAK':     return '链路断裂'
    case 'PREDICTIVE':     return '预测切换'
    default: return type || '-'
  }
}

/** 触发类型样式 class（用于事件列表行颜色标记） */
function triggerClass(type) {
  switch (type) {
    case 'LINK_BREAK':     return 'event-break'
    case 'PREDICTIVE':     return 'event-predictive'
    case 'ELEVATION_FALL': return 'event-fall'
    default: return ''
  }
}

// ── 生命周期 ──

onMounted(async () => {
  // ① 初始化 Cesium Viewer
  // Cesium 1.145 起 UrlTemplateImageryProvider.fromUrl 静态方法已移除，
  // 直接 new 构造 provider 再包进 ImageryLayer
  try {
    viewer = new Cesium.Viewer(viewerContainer.value, {
      baseLayer: new Cesium.ImageryLayer(
        new Cesium.UrlTemplateImageryProvider({
          url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
          maximumLevel: 19
        })
      ),
      geocoder: false,
    homeButton: true,
    sceneModePicker: true,
    baseLayerPicker: false,
    navigationHelpButton: false,
    animation: false,
    timeline: false,
    fullscreenButton: true,
    infoBox: true,
    selectionIndicator: true
    })
    // 调试钩子：把 viewer 挂到 canvas 元素，便于 devtools 检查
    const canvas = viewer.canvas
    if (canvas) canvas.__starlabViewer = viewer
    console.log('[starlab] Cesium Viewer 初始化成功，entities=', viewer.entities.values.length)
  } catch (e) {
    console.error('[starlab] Cesium Viewer 初始化失败:', e.message, e.stack)
    return
  }

  viewer.cesiumWidget.creditContainer.style.display = 'none'
  // 视角：中国上空，能看到北纬 0~60°、东经 70~140°
  viewer.camera.flyTo({
    destination: Cesium.Cartesian3.fromDegrees(105.0, 35.0, 8_000_000),
    orientation: { heading: 0, pitch: -Cesium.Math.PI_OVER_TWO, roll: 0 },
    duration: 2
  })

  // ② 加载星座概览 + 地面站
  try {
    const [overview, stations] = await Promise.all([
      getConstellation(),
      getGroundStations()
    ])
    Object.assign(constellationInfo, overview)
    groundStationsList.value = stations || []
    satelliteList.value = overview?.satellites || []
    console.log('[starlab] 加载地面站', stations.length, '个')
    stations.forEach(s => addStationMarker(s))
    console.log('[starlab] 地面站添加完成，entities=', viewer.entities.values.length)
  } catch (e) {
    console.error('[starlab] 初始化数据加载失败:', e.message, e.stack)
  }

  // ③ 启动 1Hz 轮询
  await pollOnce()
  console.log('[starlab] 首次轮询完成，satEntities=', satEntities.size, 'linkEntities=', linkEntities.size, 'viewer entities=', viewer.entities.values.length)
  pollTimer = setInterval(pollOnce, POLL_INTERVAL)
})

onUnmounted(() => {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
  if (replayTimer) { clearInterval(replayTimer); replayTimer = null }
  if (qualityChart) { qualityChart.dispose(); qualityChart = null }
  if (interferenceChart) { interferenceChart.dispose(); interferenceChart = null }
  if (islRefreshTimer) { clearInterval(islRefreshTimer); islRefreshTimer = null }
  satEntities.clear()
  stationEntities.clear()
  linkEntities.clear()
  replayLinkEntities.clear()
  replayHighlightEntities.clear()
  orbitTrailEntities.clear()
  orbitTrailPoints.clear()
  islLinkEntities.clear()
  islRouteEntities.clear()
  if (viewer) { viewer.destroy(); viewer = null }
})

// ── 主轮询：拉新数据 + 更新 Entity ──

async function pollOnce() {
  const t0 = performance.now()
  try {
    const [sats, links] = await Promise.all([getSatellites(), getVisibleLinks()])
    // 1. 更新卫星位置 + 轨迹
    sats.forEach(s => updateSatellite(s))
    // 2. 更新可见链路（先清旧的，再画新的——因为卫星在动，链路集合每帧不同）
    rebuildLinks(links)
    visibleLinkCount.value = links.length
  } catch (e) {
    // 后端未启动时静默
  }
  wsTick.value++
  lastTickTime = performance.now()
  tickLatency.value = Math.round(lastTickTime - t0)
}

// ── 卫星 Entity ──

function updateSatellite(sat) {
  const id = sat.satelliteId
  // altitude 单位 km → m
  const altM = (sat.altitude || 0) * 1000
  const position = Cesium.Cartesian3.fromDegrees(sat.longitude, sat.latitude, altM)

  // 维护轨迹点
  if (!orbitTrailPoints.has(id)) orbitTrailPoints.set(id, [])
  const trail = orbitTrailPoints.get(id)
  trail.push(position)
  if (trail.length > TRAIL_MAX_POINTS) trail.shift()

  // Entity 不存在 → 创建
  if (!satEntities.has(id)) {
    const entity = viewer.entities.add({
      id: `sat-${id}`,
      name: sat.name,
      position,
      point: {
        pixelSize: 10,
        color: Cesium.Color.LIME,
        outlineColor: Cesium.Color.WHITE,
        outlineWidth: 2,
        heightReference: Cesium.HeightReference.NONE
      },
      label: {
        text: id,
        font: '12px sans-serif',
        fillColor: Cesium.Color.WHITE,
        outlineColor: Cesium.Color.BLACK,
        outlineWidth: 2,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        pixelOffset: new Cesium.Cartesian2(0, -18),
        heightReference: Cesium.HeightReference.NONE
      },
      description: buildSatDescription(sat)
    })
    satEntities.set(id, entity)

    // 同时建轨迹 Polyline
    const trailEntity = viewer.entities.add({
      id: `trail-${id}`,
      name: `轨迹 - ${id}`,
      polyline: {
        positions: new Cesium.CallbackProperty(() => orbitTrailPoints.get(id) || [], false),
        width: 1.5,
        material: Cesium.Color.LIME.withAlpha(0.4)
      }
    })
    orbitTrailEntities.set(id, trailEntity)
  } else {
    // Entity 已存在 → 更新位置 + description
    const entity = satEntities.get(id)
    entity.position = position
    entity.description = buildSatDescription(sat)
  }
}

function buildSatDescription(sat) {
  return `
    <div style="padding:12px;font-size:13px;min-width:240px">
      <h4 style="margin:0 0 8px;color:#67c23a">${sat.name}</h4>
      <p>卫星 ID: <code>${sat.satelliteId}</code></p>
      <p>经度: ${sat.longitude?.toFixed(4)}°</p>
      <p>纬度: ${sat.latitude?.toFixed(4)}°</p>
      <p>高度: ${sat.altitude?.toFixed(2)} km</p>
      <p>地面速度: ${sat.groundSpeed?.toFixed(3)} km/s</p>
      <p>历元: ${sat.time}</p>
    </div>
  `
}

// ── 地面站 Entity ──

function addStationMarker(station) {
  const altM = (station.altitude || 0) * 1000
  const entity = viewer.entities.add({
    id: `station-${station.id}`,
    name: station.name,
    position: Cesium.Cartesian3.fromDegrees(station.longitude, station.latitude, altM),
    point: {
      pixelSize: 14,
      color: Cesium.Color.CYAN,
      outlineColor: Cesium.Color.WHITE,
      outlineWidth: 2,
      heightReference: Cesium.HeightReference.CLAMP_TO_GROUND
    },
    label: {
      text: station.id,
      font: '12px sans-serif',
      fillColor: Cesium.Color.CYAN,
      outlineColor: Cesium.Color.BLACK,
      outlineWidth: 2,
      style: Cesium.LabelStyle.FILL_AND_OUTLINE,
      pixelOffset: new Cesium.Cartesian2(0, -22),
      heightReference: Cesium.HeightReference.CLAMP_TO_GROUND
    },
    description: `
      <div style="padding:12px;font-size:13px">
        <h4 style="margin:0 0 8px;color:#00d9ff">${station.name}</h4>
        <p>站码: <code>${station.id}</code></p>
        <p>经度: ${station.longitude}°</p>
        <p>纬度: ${station.latitude}°</p>
        <p>海拔: ${station.altitude} km</p>
      </div>
    `
  })
  stationEntities.set(station.id, entity)
}

// ── 链路 Polyline（绿色 = 可见） ──

function rebuildLinks(links) {
  // 先清掉不再可见的链路 Entity
  const currentKeys = new Set(links.map(l => `${l.satelliteId}#${l.stationId}`))
  for (const [key, entity] of linkEntities.entries()) {
    if (!currentKeys.has(key)) {
      viewer.entities.remove(entity)
      linkEntities.delete(key)
    }
  }
  // 新增可见链路
  links.forEach(link => {
    const key = `${link.satelliteId}#${link.stationId}`
    if (linkEntities.has(key)) {
      // 已存在 → 更新 description（位置 Polyline 端点几乎不变，地面站固定，卫星动）
      const entity = linkEntities.get(key)
      entity.description = buildLinkDescription(link)
      // 更新 Polyline 端点（卫星在动）
      const satEntity = satEntities.get(link.satelliteId)
      if (satEntity && entity.polyline) {
        entity.polyline.positions = new Cesium.CallbackProperty(() => {
          const sat = satEntities.get(link.satelliteId)
          const st = stationEntities.get(link.stationId)
          if (!sat?.position || !st?.position) return []
          return [sat.position.getValue(Cesium.JulianDate.now()), st.position.getValue(Cesium.JulianDate.now())]
        }, false)
      }
      return
    }
    // 新建链路 Polyline
    const satEntity = satEntities.get(link.satelliteId)
    const stationEntity = stationEntities.get(link.stationId)
    if (!satEntity || !stationEntity) return
    const entity = viewer.entities.add({
      id: `link-${key}`,
      name: `链路 ${link.satelliteId} ↔ ${link.stationId}`,
      polyline: {
        positions: new Cesium.CallbackProperty(() => {
          const sat = satEntities.get(link.satelliteId)
          const st = stationEntities.get(link.stationId)
          if (!sat?.position || !st?.position) return []
          return [sat.position.getValue(Cesium.JulianDate.now()), st.position.getValue(Cesium.JulianDate.now())]
        }, false),
        width: 2.5,
        material: Cesium.Color.LIME.withAlpha(0.9)
      },
      description: buildLinkDescription(link)
    })
    linkEntities.set(key, entity)
  })
}

function buildLinkDescription(link) {
  return `
    <div style="padding:12px;font-size:13px;min-width:260px">
      <h4 style="margin:0 0 8px;color:#67c23a">🛰️ ${link.satelliteName} ↔ ${link.stationName}</h4>
      <p>卫星 ID: <code>${link.satelliteId}</code></p>
      <p>地面站 ID: <code>${link.stationId}</code></p>
      <hr style="border:0;border-top:1px solid #333;margin:8px 0">
      <p>距离: <strong>${link.range?.toFixed(2)} km</strong></p>
      <p>仰角: <strong style="color:#67c23a">${link.elevation?.toFixed(2)}°</strong></p>
      <p>方位角: ${link.azimuth?.toFixed(2)}°</p>
      <p>多普勒频移: <strong style="color:${link.doppler >= 0 ? '#ff9f43' : '#00d9ff'}">${link.doppler?.toFixed(0)} Hz</strong></p>
      <p>路径损耗: ${link.pathLoss?.toFixed(2)} dB</p>
      <p>雨衰 + 大气: ${(link.rainAttenuationDb ?? 0).toFixed(3)} + ${(link.atmosphericLossDb ?? 0).toFixed(3)} dB</p>
      <p>SNR: <strong style="color:#67c23a">${link.snrDb?.toFixed(1)} dB</strong></p>
      <p>Shannon 吞吐量: <strong style="color:#00d9ff">${link.throughputMbps?.toFixed(1)} Mbps</strong></p>
    </div>
  `
}
</script>

<template>
  <div class="cesium-container" ref="viewerContainer"></div>

  <!-- 左上角：星座概览 -->
  <div class="panel constellation-panel">
    <h3>🛰️ 星座概览</h3>
    <div class="status-row">
      <span class="label">星座构型</span>
      <span class="value">{{ constellationInfo.name || '-' }}</span>
    </div>
    <div class="status-row">
      <span class="label">卫星总数</span>
      <span class="value">{{ constellationInfo.totalSatellites ?? '-' }}</span>
    </div>
    <div class="status-row">
      <span class="label">轨道面 / 面内星</span>
      <span class="value">{{ constellationInfo.planes }} / {{ constellationInfo.satsPerPlane }}</span>
    </div>
    <div class="status-row">
      <span class="label">倾角</span>
      <span class="value">{{ constellationInfo.inclination }}°</span>
    </div>
    <div class="status-row">
      <span class="label">轨道高度</span>
      <span class="value">{{ constellationInfo.altitude?.toFixed(1) }} km</span>
    </div>
    <div class="status-row">
      <span class="label">地面站</span>
      <span class="value">{{ constellationInfo.totalStations }}</span>
    </div>
    <div class="status-row">
      <span class="label">平均运动</span>
      <span class="value">{{ constellationInfo.meanMotion }} 圈/天</span>
    </div>
    <div class="status-row">
      <span class="label">Walker F</span>
      <span class="value">{{ constellationInfo.phasingF }}</span>
    </div>

    <!-- ⚙ 星座动态配置 -->
    <div class="panel-header config-toggle" @click="constellationConfigOpen = !constellationConfigOpen">
      <span class="config-title">⚙ 星座参数配置</span>
      <span class="toggle-arrow" :class="{ open: constellationConfigOpen }">▼</span>
    </div>
    <div v-show="constellationConfigOpen" class="config-form">
      <div class="config-row">
        <label>卫星总数 T</label>
        <input type="number" v-model.number="constellationConfig.total" min="1" max="200" />
      </div>
      <div class="config-row">
        <label>轨道面 P</label>
        <input type="number" v-model.number="constellationConfig.planes" min="1" max="20" />
      </div>
      <div class="config-row">
        <label>倾角 (°)</label>
        <input type="number" v-model.number="constellationConfig.inclination" min="0" max="180" step="0.1" />
      </div>
      <div class="config-row">
        <label>高度 (km)</label>
        <input type="number" v-model.number="constellationConfig.altitudeKm" min="200" max="2000" step="10" />
      </div>
      <div class="config-row">
        <label>Walker F</label>
        <input type="number" v-model.number="constellationConfig.phasingF" min="0" max="199" />
      </div>
      <div class="btn-row">
        <button class="btn-apply" @click="applyConstellationConfig"
          :disabled="constellationConfigLoading">
          {{ constellationConfigLoading ? '⏳ 应用中...' : '✓ 应用配置' }}
        </button>
        <button class="btn-reset" @click="resetConstellationConfig"
          :disabled="constellationConfigLoading">↺ 默认</button>
      </div>
      <p class="config-hint">T 必须能被 P 整除，高度 200~2000 km (LEO)</p>
    </div>
  </div>

  <!-- 左下方：STCN 切换仿真 -->
  <div class="panel handover-panel">
    <div class="panel-header" @click="handoverPanelOpen = !handoverPanelOpen">
      <h3>🔀 STCN 切换仿真</h3>
      <span class="toggle-arrow" :class="{ open: handoverPanelOpen }">▼</span>
    </div>

    <div v-if="handoverPanelOpen" class="panel-body">
      <!-- 仿真参数 -->
      <div class="form-group">
        <label>仿真时长</label>
        <select v-model.number="simParams.durationSec">
          <option :value="600">10 分钟</option>
          <option :value="1800">30 分钟</option>
          <option :value="3600">1 小时</option>
          <option :value="7200">2 小时</option>
        </select>
      </div>
      <div class="form-group">
        <label>Tick 步长</label>
        <select v-model.number="simParams.tickSec">
          <option :value="1">1 秒（精细）</option>
          <option :value="5">5 秒</option>
          <option :value="10">10 秒（推荐）</option>
          <option :value="30">30 秒（快速）</option>
        </select>
      </div>
      <div class="form-group">
        <label>选站算法</label>
        <select v-model="simParams.algorithm">
          <option value="greedy">贪心最高仰角 (v1)</option>
          <option value="predictive">预测式窗口优先 (v2)</option>
          <option value="interference-aware">干扰感知 C/I 优先 (v3)</option>
        </select>
      </div>

      <div class="btn-row">
        <button class="btn-primary" @click="runHandoverSim" :disabled="simLoading || compareLoading || timelineLoading">
          {{ simLoading ? '⏳ 仿真中...' : '▶ 运行仿真' }}
        </button>
        <button class="btn-compare" @click="runHandoverCompare" :disabled="simLoading || compareLoading || timelineLoading">
          {{ compareLoading ? '⏳ 对比中...' : '≡ 对比运行' }}
        </button>
      </div>
      <div class="btn-row">
        <button class="btn-replay" @click="loadTimeline" :disabled="simLoading || compareLoading || timelineLoading || qualityLoading">
          {{ timelineLoading ? '⏳ 加载时间轴...' : '🎬 时间轴回放' }}
        </button>
        <button v-if="isReplayMode" class="btn-exit-replay" @click="exitReplayMode">
          ⏹ 退出回放
        </button>
      </div>
      <div class="btn-row">
        <button class="btn-quality" @click="loadQualityChart" :disabled="simLoading || compareLoading || timelineLoading || qualityLoading">
          {{ qualityLoading ? '⏳ 加载曲线...' : '📊 链路质量' }}
        </button>
      </div>
      <div class="btn-row">
        <button class="btn-interference" @click="runInterferenceAnalysis" :disabled="interferenceLoading">
          {{ interferenceLoading ? '⏳ 干扰分析中...' : '📡 干扰分析' }}
        </button>
        <button v-if="interferenceResult" class="btn-mini" @click="interferencePanelOpen = !interferencePanelOpen">
          {{ interferencePanelOpen ? '▾' : '▸' }}
        </button>
      </div>
      <div class="btn-row">
        <button class="btn-isl" @click="loadIslTopology" :disabled="islLoading">
          {{ islLoading ? '⏳ 加载拓扑...' : '🔗 ISL 拓扑' }}
        </button>
      </div>

      <!-- 单次仿真结果统计 -->
      <div v-if="simResult" class="sim-stats">
        <div class="status-row">
          <span class="label">算法</span>
          <span class="value">{{ simResult.algorithmId }}</span>
        </div>
        <div class="status-row">
          <span class="label">仿真区间</span>
          <span class="value">{{ simResult.durationSec / 60 }}min</span>
        </div>
        <div class="status-row">
          <span class="label">总 tick 数</span>
          <span class="value">{{ simResult.totalTicks }}</span>
        </div>
        <div class="status-row">
          <span class="label">切换次数</span>
          <span class="value stat-highlight">{{ simResult.handoverCount }}</span>
        </div>
        <div class="status-row">
          <span class="label">切换率</span>
          <span class="value">{{ simResult.handoverRatePerMin }} 次/分</span>
        </div>
      </div>

      <!-- 对比结果（双跑） -->
      <div v-if="compareResult" class="compare-stats">
        <div class="compare-title">算法对比</div>
        <table class="compare-table">
          <thead>
            <tr>
              <th>指标</th>
              <th>贪心 v1</th>
              <th>预测 v2</th>
              <th>差 Δ</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>切换次数</td>
              <td>{{ compareResult.greedy.handoverCount }}</td>
              <td>{{ compareResult.predictive.handoverCount }}</td>
              <td :class="compareResult.improvement.reducedSwitches >= 0 ? 'delta-pos' : 'delta-neg'">
                {{ compareResult.improvement.reducedSwitches }}
              </td>
            </tr>
            <tr>
              <td>链路断裂</td>
              <td>{{ compareResult.greedy.linkBreakCount }}</td>
              <td>{{ compareResult.predictive.linkBreakCount }}</td>
              <td :class="compareResult.improvement.linkBreakDelta <= 0 ? 'delta-pos' : 'delta-neg'">
                {{ compareResult.improvement.linkBreakDelta }}
              </td>
            </tr>
            <tr>
              <td>平均时延</td>
              <td>{{ compareResult.greedy.avgLatencyMs }}ms</td>
              <td>{{ compareResult.predictive.avgLatencyMs }}ms</td>
              <td :class="compareResult.improvement.latencyDeltaMs <= 0 ? 'delta-pos' : 'delta-neg'">
                {{ compareResult.improvement.latencyDeltaMs }}ms
              </td>
            </tr>
          </tbody>
        </table>
        <div class="compare-summary">
          改进：<strong>{{ compareResult.improvement.reducedSwitchesPct?.toFixed(1) ?? '—' }}%</strong>
          · 少断链 <strong>{{ Math.abs(compareResult.improvement.linkBreakDelta) }}</strong> 次
          · 时延 <strong>{{ compareResult.improvement.latencyDeltaMs > 0 ? '+' : '' }}{{ compareResult.improvement.latencyDeltaMs }}ms</strong>
        </div>
      </div>

      <!-- 事件时间轴 -->
      <div v-if="handoverEvents.length > 0" class="events-section">
        <div class="events-header">
          <span>最近 {{ handoverEvents.length }} 次切换</span>
          <button class="btn-mini" @click="refreshHandoverEvents">↻</button>
          <button class="btn-mini btn-danger" @click="clearAllHandoverEvents">🗑</button>
        </div>
        <div class="events-list">
          <div
            v-for="(e, idx) in handoverEvents"
            :key="idx"
            class="event-item"
            :class="triggerClass(e.triggerType)"
          >
            <span class="event-time">{{ fmtTrigger(e.triggerTime) }}</span>
            <span class="event-sat">{{ e.satelliteId }}</span>
            <span class="event-link">
              <span class="station">{{ e.fromStationId }}</span>
              <span class="arrow">→</span>
              <span class="station" v-if="e.toStationId">{{ e.toStationId }}</span>
              <span class="station broken" v-else>断开</span>
            </span>
            <span class="event-tag">{{ triggerLabel(e.triggerType) }}</span>
            <span class="event-latency" v-if="e.totalLatencyMs > 0">
              {{ e.totalLatencyMs.toFixed(1) }}ms
            </span>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- 右上角：实时链路状态 -->
  <div class="panel links-panel">
    <h3>📡 实时链路</h3>
    <div class="status-row">
      <span class="label"><span class="status-dot green"></span>可见链路数</span>
      <span class="value">{{ visibleLinkCount }}</span>
    </div>
    <div class="status-row">
      <span class="label">刷新频率</span>
      <span class="value">1 Hz</span>
    </div>
    <div class="status-row">
      <span class="label">上一帧耗时</span>
      <span class="value">{{ tickLatency }} ms</span>
    </div>
    <div class="status-row">
      <span class="label">刷新计数</span>
      <span class="value">{{ wsTick }}</span>
    </div>
    <div class="legend">
      <div class="legend-item"><span class="status-dot green"></span>可见链路 Polyline</div>
      <div class="legend-item"><span class="status-dot cyan"></span>地面站 Marker</div>
      <div class="legend-item"><span class="status-dot orange"></span>卫星实体点</div>
    </div>
  </div>

  <!-- 底部：操作提示 -->
  <div v-if="!isReplayMode && !showQualityPanel && !interferencePanelOpen" class="panel hint-panel">
    <span class="hint-text">
      💡 点击卫星 / 地面站 / 链路查看详情 · 鼠标拖拽旋转 · 滚轮缩放
    </span>
  </div>

  <!-- 右下角：干扰分析面板（方向5） -->
  <div v-if="interferencePanelOpen && interferenceResult" class="panel interference-panel">
    <div class="panel-header interference-header">
      <h3>📡 干扰分析</h3>
      <button class="btn-mini btn-danger" @click="interferencePanelOpen = false">✕</button>
    </div>

    <!-- 分析参数筛选 -->
    <div class="interference-filters">
      <div class="form-group">
        <label>目标卫星</label>
        <select v-model="interferenceParams.targetSatelliteId">
          <option value="">全部卫星</option>
          <option v-for="s in satelliteList" :key="s.id" :value="s.id">
            {{ s.name }} ({{ s.id }})
          </option>
        </select>
      </div>
      <div class="form-group">
        <label>地面站</label>
        <select v-model="interferenceParams.stationId">
          <option value="">全部地面站</option>
          <option v-for="st in groundStationsList" :key="st.id" :value="st.id">
            {{ st.name }} ({{ st.id }})
          </option>
        </select>
      </div>
      <div class="btn-row">
        <button class="btn-primary" @click="runInterferenceAnalysis" :disabled="interferenceLoading">
          {{ interferenceLoading ? '⏳ 分析中...' : '▶ 重新分析' }}
        </button>
        <button class="btn-mini" @click="resetInterference">↻</button>
      </div>
    </div>

    <!-- 统计摘要 -->
    <div v-if="interferenceResult.statistics" class="interference-stats">
      <div class="status-row">
        <span class="label">分析时刻</span>
        <span class="value">{{ interferenceResult.analyzedAt?.substring(11, 19) }}</span>
      </div>
      <div class="status-row">
        <span class="label">组合数</span>
        <span class="value">{{ interferenceResult.statistics.totalCombos }}</span>
      </div>
      <div class="status-row">
        <span class="label">可用链路</span>
        <span class="value stat-highlight">
          {{ interferenceResult.statistics.availableCount }}
          ({{ interferenceResult.statistics.availabilityPct }}%)
        </span>
      </div>
      <div class="status-row">
        <span class="label">平均 C/I</span>
        <span class="value" :class="cirClass(interferenceResult.statistics.avgCirDb)">
          {{ interferenceResult.statistics.avgCirDb }} dB
        </span>
      </div>
      <div class="status-row">
        <span class="label">最差 C/I</span>
        <span class="value" :class="cirClass(interferenceResult.statistics.worstCirDb)">
          {{ interferenceResult.statistics.worstCirDb }} dB
        </span>
      </div>
      <div class="status-row">
        <span class="label">平均干扰功率</span>
        <span class="value">{{ interferenceResult.statistics.avgInterferenceDbm }} dBm</span>
      </div>
      <div class="status-row">
        <span class="label">平均同频干扰源</span>
        <span class="value">{{ interferenceResult.statistics.coChannelAvg }} 颗</span>
      </div>
      <div class="btn-row">
        <button class="btn-mini" @click="loadInterferenceChart" :disabled="interferenceLoading">
          {{ interferenceLoading ? '⏳ 加载中...' : '📈 C/I 时序曲线' }}
        </button>
      </div>
    </div>

    <!-- 详细链路结果列表 -->
    <div class="interference-results">
      <div class="results-title">链路明细 ({{ interferenceResult.results?.length || 0 }})</div>
      <div class="results-list">
        <div
          v-for="r in (interferenceResult.results || []).filter(r => r.visible)"
          :key="`${r.satelliteId}#${r.stationId}`"
          class="result-row"
        >
          <div class="result-head">
            <span class="result-sat">{{ r.satelliteName }}</span>
            <span class="result-arrow">↔</span>
            <span class="result-station">{{ r.stationId }}</span>
            <span class="result-tag" :class="availabilityClass(r)">{{ availabilityLabel(r) }}</span>
          </div>
          <div class="result-meta">
            <span>C/I <strong :class="cirClass(r.cirDb)">{{ r.cirDb?.toFixed(1) }} dB</strong></span>
            <span>SNR {{ r.snrDb?.toFixed(1) }} dB</span>
            <span>干扰 {{ r.interferencePowerDbm?.toFixed(1) }} dBm</span>
            <span>同频 {{ r.coChannelCount }} 颗</span>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- 右下角：星间链路 ISL 面板（方向6） -->
  <div v-if="islPanelOpen && islTopology" class="panel isl-panel">
    <div class="panel-header isl-header">
      <h3>🔗 星间链路 ISL</h3>
      <button class="btn-mini btn-danger" @click="islPanelOpen = false">✕</button>
    </div>

    <!-- 拓扑统计 -->
    <div v-if="islTopology.statistics" class="isl-stats">
      <div class="status-row">
        <span class="label">分析时刻</span>
        <span class="value">{{ islTopology.analyzedAt?.substring(11, 19) }}</span>
      </div>
      <div class="status-row">
        <span class="label">ISL 总数</span>
        <span class="value stat-highlight">{{ islTopology.statistics.totalLinks }}</span>
      </div>
      <div class="status-row">
        <span class="label">同面 / 跨面</span>
        <span class="value">
          {{ islTopology.statistics.intraPlaneLinks }} / {{ islTopology.statistics.crossPlaneLinks }}
        </span>
      </div>
      <div class="status-row">
        <span class="label">平均度数</span>
        <span class="value">{{ islTopology.statistics.avgDegree }}</span>
      </div>
      <div class="status-row">
        <span class="label">平均距离</span>
        <span class="value">{{ islTopology.statistics.avgDistanceKm }} km</span>
      </div>
      <div class="status-row">
        <span class="label">最远距离</span>
        <span class="value">{{ islTopology.statistics.maxDistanceKm }} km</span>
      </div>
      <div class="status-row">
        <span class="label">平均时延</span>
        <span class="value">{{ islTopology.statistics.avgLatencyMs }} ms</span>
      </div>
    </div>

    <!-- 多跳路由查询 -->
    <div class="isl-route-form">
      <div class="form-group">
        <label>源地面站（与源卫星二选一）</label>
        <select v-model="islRouteParams.sourceStationId" :disabled="!!islRouteParams.sourceSatelliteId">
          <option value="">不指定</option>
          <option v-for="st in groundStationsList" :key="st.id" :value="st.id">
            {{ st.name }} ({{ st.id }})
          </option>
        </select>
      </div>
      <div class="form-group">
        <label>源卫星（与源地面站二选一）</label>
        <select v-model="islRouteParams.sourceSatelliteId" :disabled="!!islRouteParams.sourceStationId">
          <option value="">不指定</option>
          <option v-for="s in satelliteList" :key="s.id" :value="s.id">
            {{ s.name }} ({{ s.id }})
          </option>
        </select>
      </div>
      <div class="form-group">
        <label>目的卫星</label>
        <select v-model="islRouteParams.destinationSatelliteId">
          <option v-for="s in satelliteList" :key="s.id" :value="s.id">
            {{ s.name }} ({{ s.id }})
          </option>
        </select>
      </div>
      <div class="form-group">
        <label>ISL 最大距离 (km)</label>
        <select v-model.number="islRouteParams.maxDistanceKm">
          <option :value="3000">3000 km（紧凑）</option>
          <option :value="5000">5000 km（默认）</option>
          <option :value="8000">8000 km（宽松）</option>
          <option :value="15000">15000 km（全连通）</option>
        </select>
      </div>
      <div class="btn-row">
        <button class="btn-primary" @click="computeRoute" :disabled="islRouteLoading">
          {{ islRouteLoading ? '⏳ 路由中...' : '🛰️ 计算多跳路由' }}
        </button>
        <button class="btn-mini" @click="clearIslOverlays">🗑</button>
      </div>
      <div class="btn-row">
        <label class="isl-autorefresh">
          <input type="checkbox" v-model="islAutoRefresh" @change="toggleIslAutoRefresh" />
          <span>🔄 每 10s 自动刷新拓扑</span>
        </label>
      </div>
    </div>

    <!-- 路由结果 -->
    <div v-if="islRouteResult" class="isl-route-result">
      <div class="status-row" v-if="islRouteResult.reachable">
        <span class="label">总时延</span>
        <span class="value stat-highlight">{{ islRouteResult.totalLatencyMs }} ms</span>
      </div>
      <div class="status-row" v-if="islRouteResult.reachable">
        <span class="label">跳数</span>
        <span class="value">{{ islRouteResult.hopCount }}</span>
      </div>
      <div v-if="!islRouteResult.reachable" class="route-unreachable">
        ❌ {{ islRouteResult.reason || '不可达' }}
      </div>
      <div v-if="islRouteResult.segments?.length" class="route-segments">
        <div class="segments-title">分段明细</div>
        <div class="segment-row" v-for="(seg, i) in islRouteResult.segments" :key="i">
          <span class="seg-index">{{ i + 1 }}</span>
          <span class="seg-node">{{ seg.from }}</span>
          <span class="seg-arrow">→</span>
          <span class="seg-node">{{ seg.to }}</span>
          <span class="seg-type" :class="segTypeClass(seg.type)">{{ segLabel(seg.type) }}</span>
          <span class="seg-latency">{{ seg.latencyMs?.toFixed(2) }} ms</span>
        </div>
      </div>
    </div>
  </div>

  <!-- 顶部居中：链路质量曲线面板（ECharts） -->
  <div v-if="showQualityPanel" class="panel quality-chart-panel">
    <div class="quality-header">
      <span class="quality-title">📊 链路质量分析 · 贪心 vs 预测式 · {{ simParams.durationSec / 60 }}min / tick={{ simParams.tickSec }}s</span>
      <button class="btn-mini btn-danger" @click="closeQualityPanel">✕</button>
    </div>
    <div ref="qualityChartContainer" class="quality-chart-canvas"></div>
  </div>

  <!-- 顶部居中：C/I 干扰时序曲线面板 -->
  <div v-if="showInterferenceChart" class="panel quality-chart-panel">
    <div class="quality-header">
      <span class="quality-title">📡 C/I 干扰时序曲线 · {{ simParams.durationSec / 60 }}min / tick={{ simParams.tickSec }}s</span>
      <button class="btn-mini btn-danger" @click="closeInterferenceChart">✕</button>
    </div>
    <div ref="interferenceChartContainer" class="quality-chart-canvas"></div>
  </div>

  <!-- 底部：时间轴回放控件条 -->
  <div v-if="isReplayMode" class="panel replay-bar">
    <div class="replay-info">
      <span class="replay-algo">算法: {{ timelineMeta.algorithmId }}</span>
      <span class="replay-stat">切换 <strong>{{ timelineMeta.handoverCount }}</strong></span>
      <span class="replay-stat">{{ timelineMeta.handoverRatePerMin }} 次/分</span>
      <span class="replay-time">{{ fmtSimSec(timelineData[timelineIndex]?.simSec ?? 0) }} / {{ fmtSimSec(timelineMeta.durationSec) }}</span>
      <span class="replay-tick">tick {{ timelineIndex + 1 }} / {{ timelineMeta.totalTicks }}</span>
    </div>
    <div class="replay-controls">
      <button class="btn-play" @click="togglePlayback">
        {{ timelinePlaying ? '⏸ 暂停' : '▶ 播放' }}
      </button>
      <button class="btn-step" @click="seekTimeline(Math.max(0, timelineIndex - 1))" :disabled="timelineIndex === 0">⏮</button>
      <button class="btn-step" @click="seekTimeline(Math.min(timelineData.length - 1, timelineIndex + 1))" :disabled="timelineIndex >= timelineData.length - 1">⏭</button>
      <div class="speed-group">
        <button v-for="s in SPEED_OPTIONS" :key="s"
          class="btn-speed" :class="{ active: timelineSpeed === s }"
          @click="setSpeed(s)">{{ s }}x</button>
      </div>
      <input type="range" class="timeline-slider"
        min="0" :max="Math.max(0, timelineData.length - 1)"
        :value="timelineIndex"
        @input="seekTimeline(parseInt($event.target.value))" />
    </div>
  </div>
</template>

<style>
.cesium-container {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  background: var(--bg-deep);
}

/* ── 浮层面板 ── */
.constellation-panel {
  position: absolute;
  top: 16px;
  left: 16px;
  width: 240px;
  z-index: 10;
}

.handover-panel {
  position: absolute;
  top: 290px;
  left: 16px;
  width: 240px;
  max-height: calc(100vh - 320px);
  overflow-y: auto;
  z-index: 10;
}

.handover-panel .panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  user-select: none;
}
.handover-panel .panel-header h3 { margin-bottom: 0; }

.toggle-arrow {
  font-size: 10px;
  color: var(--text-secondary);
  transition: transform 0.2s;
}
.toggle-arrow.open { transform: rotate(0deg); }
.toggle-arrow:not(.open) { transform: rotate(-90deg); }

.panel-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 10px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.form-group label {
  font-size: 11px;
  color: var(--text-secondary);
}
.form-group select {
  background: var(--bg-panel-solid);
  color: var(--text-primary);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 4px 8px;
  font-size: 12px;
}

.btn-primary {
  background: var(--accent-green);
  color: #0a0e1a;
  border: none;
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.15s;
}
.btn-primary:hover:not(:disabled) { opacity: 0.85; }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }

.btn-row {
  display: flex;
  gap: 6px;
}
.btn-row .btn-primary,
.btn-row .btn-compare {
  flex: 1;
  padding: 7px 8px;
  font-size: 11px;
}
.btn-compare {
  background: var(--accent-cyan, #00d9ff);
  color: #0a0e1a;
  border: none;
  border-radius: 6px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.15s;
}
.btn-compare:hover:not(:disabled) { opacity: 0.85; }
.btn-compare:disabled { opacity: 0.5; cursor: not-allowed; }

.btn-replay {
  background: linear-gradient(135deg, #9b59b6, #6c5ce7);
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 7px 8px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.15s;
}
.btn-replay:hover:not(:disabled) { opacity: 0.85; }
.btn-replay:disabled { opacity: 0.5; cursor: not-allowed; }

.btn-exit-replay {
  background: var(--accent-red);
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 7px 8px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
}
.btn-exit-replay:hover { opacity: 0.85; }

.btn-quality {
  background: linear-gradient(135deg, #e67e22, #d35400);
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 7px 8px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.15s;
}
.btn-quality:hover:not(:disabled) { opacity: 0.85; }
.btn-quality:disabled { opacity: 0.5; cursor: not-allowed; }

.btn-interference {
  background: linear-gradient(135deg, #9b59b6, #8e44ad);
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 7px 8px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.15s;
  flex: 1;
}
.btn-interference:hover:not(:disabled) { opacity: 0.85; }
.btn-interference:disabled { opacity: 0.5; cursor: not-allowed; }

.btn-isl {
  background: linear-gradient(135deg, #16a085, #1abc9c);
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 7px 8px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.15s;
  flex: 1;
}
.btn-isl:hover:not(:disabled) { opacity: 0.85; }
.btn-isl:disabled { opacity: 0.5; cursor: not-allowed; }

/* ── 链路质量曲线面板 ── */
.quality-chart-panel {
  position: absolute;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  width: min(900px, calc(100vw - 32px));
  height: 360px;
  z-index: 15;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
}
.quality-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.quality-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-primary);
}
.quality-chart-canvas {
  width: 100%;
  height: 300px;
}

/* ── 星座动态配置 ── */
.config-toggle {
  margin-top: 10px;
  padding: 6px 0;
  border-top: 1px solid var(--border);
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.config-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--accent-cyan, #00d9ff);
}
.config-form {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.config-row {
  display: grid;
  grid-template-columns: 80px 1fr;
  align-items: center;
  gap: 8px;
}
.config-row label {
  font-size: 11px;
  color: var(--text-secondary);
}
.config-row input {
  background: rgba(0, 0, 0, 0.35);
  border: 1px solid var(--border);
  color: var(--text-primary);
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  width: 100%;
  box-sizing: border-box;
}
.config-row input:focus {
  outline: none;
  border-color: var(--accent-cyan, #00d9ff);
}
.btn-apply {
  background: linear-gradient(135deg, #00d9ff, #0099cc);
  color: #0a0e1a;
  border: none;
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  flex: 1;
}
.btn-apply:hover:not(:disabled) { opacity: 0.85; }
.btn-apply:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-reset {
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 11px;
  cursor: pointer;
}
.btn-reset:hover:not(:disabled) {
  color: var(--text-primary);
  border-color: var(--accent-cyan, #00d9ff);
}
.btn-reset:disabled { opacity: 0.5; cursor: not-allowed; }
.config-hint {
  font-size: 10px;
  color: var(--text-secondary);
  margin: 4px 0 0 0;
  opacity: 0.7;
}

/* ── 底部时间轴回放控件条 ── */
.replay-bar {
  position: absolute;
  bottom: 16px;
  left: 50%;
  transform: translateX(-50%);
  width: min(900px, calc(100vw - 32px));
  z-index: 10;
  padding: 12px 16px;
}
.replay-info {
  display: flex;
  gap: 16px;
  align-items: center;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 8px;
}
.replay-info strong { color: var(--accent-orange); font-size: 14px; }
.replay-algo { color: #9b59b6; font-weight: 600; }
.replay-time {
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  color: var(--text-primary);
  background: rgba(0, 0, 0, 0.35);
  padding: 2px 8px;
  border-radius: 4px;
}
.replay-tick {
  font-size: 11px;
  color: var(--text-secondary);
}
.replay-controls {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.btn-play {
  background: var(--accent-green);
  color: #0a0e1a;
  border: none;
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  min-width: 70px;
}
.btn-play:hover { opacity: 0.85; }
.btn-step {
  background: transparent;
  color: var(--text-primary);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 4px 10px;
  font-size: 14px;
  cursor: pointer;
}
.btn-step:hover:not(:disabled) { border-color: var(--accent-green); color: var(--accent-green); }
.btn-step:disabled { opacity: 0.4; cursor: not-allowed; }
.speed-group {
  display: inline-flex;
  border: 1px solid var(--border);
  border-radius: 4px;
  overflow: hidden;
}
.btn-speed {
  background: transparent;
  color: var(--text-secondary);
  border: none;
  padding: 4px 8px;
  font-size: 11px;
  cursor: pointer;
  transition: all 0.15s;
}
.btn-speed:hover { color: var(--text-primary); }
.btn-speed.active {
  background: var(--accent-cyan, #00d9ff);
  color: #0a0e1a;
  font-weight: 600;
}
.timeline-slider {
  flex: 1;
  min-width: 200px;
  height: 6px;
  accent-color: var(--accent-green);
  cursor: pointer;
}

.btn-mini {
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 11px;
  cursor: pointer;
}
.btn-mini:hover { color: var(--text-primary); }
.btn-mini.btn-danger:hover { color: var(--accent-red); border-color: var(--accent-red); }

.sim-stats {
  border-top: 1px dashed var(--border);
  padding-top: 8px;
}
.stat-highlight {
  color: var(--accent-orange);
  font-size: 18px;
}

.events-section {
  border-top: 1px dashed var(--border);
  padding-top: 8px;
}
.events-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11px;
  color: var(--text-secondary);
  margin-bottom: 6px;
}
.events-list {
  display: flex;
  flex-direction: column;
  gap: 3px;
  max-height: 280px;
  overflow-y: auto;
}
.event-item {
  display: grid;
  grid-template-columns: 46px 68px 1fr 62px 42px;
  gap: 3px;
  align-items: center;
  padding: 3px 6px;
  background: rgba(0, 0, 0, 0.25);
  border-radius: 4px;
  font-size: 11px;
}
.event-break {
  border-left: 2px solid var(--accent-red);
}
.event-predictive {
  border-left: 2px solid #00d9ff;
}
.event-fall {
  border-left: 2px solid var(--accent-orange);
}
.event-tag {
  font-size: 10px;
  padding: 1px 4px;
  border-radius: 3px;
  background: rgba(0, 0, 0, 0.35);
  color: var(--text-secondary);
  white-space: nowrap;
}
.event-predictive .event-tag { background: rgba(0, 217, 255, 0.25); color: #00d9ff; }
.event-break .event-tag { background: rgba(255, 82, 82, 0.25); color: var(--accent-red); }
.event-fall .event-tag { background: rgba(255, 159, 67, 0.25); color: var(--accent-orange); }

/* ── 对比结果表 ── */
.compare-stats {
  border-top: 1px dashed var(--border);
  padding-top: 8px;
}
.compare-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 6px;
}
.compare-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 11px;
}
.compare-table th,
.compare-table td {
  padding: 4px 3px;
  text-align: right;
  border-bottom: 1px solid rgba(255,255,255,0.05);
}
.compare-table th:first-child,
.compare-table td:first-child { text-align: left; color: var(--text-secondary); }
.compare-table th { font-size: 10px; color: var(--text-secondary); font-weight: 500; }
.delta-pos { color: #67c23a; }  /* 改进 = 更少断链/更低时延/更少切换 */
.delta-neg { color: var(--accent-red); }  /* 变差 */
.compare-summary {
  margin-top: 6px;
  font-size: 11px;
  color: var(--text-secondary);
  padding: 6px 8px;
  background: rgba(0, 217, 255, 0.08);
  border-radius: 4px;
}
.compare-summary strong { color: #00d9ff; }
.event-time {
  color: var(--text-secondary);
  font-family: 'JetBrains Mono', 'Consolas', monospace;
}
.event-sat {
  color: var(--accent-cyan);
  font-weight: 600;
}
.event-link {
  display: flex;
  align-items: center;
  gap: 2px;
}
.station {
  color: var(--accent-green);
  font-weight: 600;
}
.station.broken {
  color: var(--accent-red);
}
.arrow {
  color: var(--text-secondary);
}
.event-latency {
  color: var(--accent-orange);
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  text-align: right;
}

.links-panel {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 240px;
  z-index: 10;
}

.links-panel .legend {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--border);
}
.legend-item {
  font-size: 11px;
  color: var(--text-secondary);
  padding: 3px 0;
  display: flex;
  align-items: center;
}

.hint-panel {
  position: absolute;
  bottom: 16px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 10;
  padding: 8px 16px;
}
.hint-text {
  font-size: 12px;
  color: var(--text-secondary);
  white-space: nowrap;
}

/* ── 干扰分析面板（方向5） ── */
.interference-panel {
  position: absolute;
  right: 16px;
  bottom: 16px;
  width: 320px;
  max-height: calc(100vh - 32px);
  overflow-y: auto;
  z-index: 12;
}
.interference-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.interference-header h3 { margin: 0; }

.interference-filters {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 0;
  border-bottom: 1px solid var(--border);
  margin-bottom: 8px;
}
.interference-filters .btn-row { gap: 6px; }

.interference-stats {
  padding: 6px 0 10px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 8px;
}
.interference-stats .stat-highlight { color: #67c23a; }

.interference-results { padding-top: 4px; }
.results-title {
  font-size: 11px;
  color: var(--text-secondary);
  margin-bottom: 6px;
}
.results-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.result-row {
  background: rgba(255,255,255,0.03);
  border-left: 2px solid var(--border);
  border-radius: 4px;
  padding: 6px 8px;
  font-size: 11px;
}
.result-head {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 4px;
}
.result-sat { color: #ff9f43; font-weight: 600; }
.result-arrow { color: var(--text-secondary); }
.result-station { color: #00d9ff; }
.result-tag {
  margin-left: auto;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 10px;
  font-weight: 600;
}
.tag-green { background: #67c23a; color: #0a0e1a; }
.tag-red { background: #e74c3c; color: #fff; }
.tag-gray { background: #555; color: #ccc; }

.result-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 2px 8px;
  color: var(--text-secondary);
  font-size: 10px;
}
.result-meta strong { color: var(--text-primary); font-weight: 600; }
.value-good { color: #67c23a; }
.value-warn { color: #f39c12; }
.value-bad { color: #e74c3c; }

/* ── 星间链路 ISL 面板（方向6） ── */
.isl-panel {
  position: absolute;
  right: 16px;
  bottom: 16px;
  width: 320px;
  max-height: calc(100vh - 32px);
  overflow-y: auto;
  z-index: 12;
}
.isl-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.isl-header h3 { margin: 0; }
.isl-stats {
  padding: 6px 0 10px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 8px;
}
.isl-stats .stat-highlight { color: #1abc9c; }
.isl-route-form {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 0;
  border-bottom: 1px solid var(--border);
  margin-bottom: 8px;
}
.isl-route-form .btn-row { gap: 6px; }

.isl-route-result { padding-top: 4px; }
.isl-route-result .stat-highlight { color: #1abc9c; }
.route-unreachable {
  padding: 8px 10px;
  background: rgba(231, 76, 60, 0.12);
  border-left: 3px solid #e74c3c;
  border-radius: 4px;
  color: #e74c3c;
  font-size: 12px;
  margin: 6px 0;
}
.route-segments { margin-top: 8px; }
.segments-title {
  font-size: 11px;
  color: var(--text-secondary);
  margin-bottom: 6px;
}
.segment-row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 6px;
  background: rgba(255,255,255,0.03);
  border-radius: 4px;
  margin-bottom: 3px;
  font-size: 10px;
}
.seg-index {
  background: var(--bg-panel-solid);
  border-radius: 3px;
  padding: 0 4px;
  font-weight: 600;
  color: var(--text-secondary);
}
.seg-node { color: var(--text-primary); font-weight: 500; }
.seg-arrow { color: var(--text-secondary); }
.seg-type {
  margin-left: auto;
  padding: 1px 5px;
  border-radius: 3px;
  font-weight: 600;
}
.seg-ground { background: #67c23a; color: #0a0e1a; }
.seg-intra { background: #00d9ff; color: #0a0e1a; }
.seg-cross { background: #9b59b6; color: #fff; }
.seg-latency { color: var(--text-secondary); font-size: 10px; }

.isl-autorefresh {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--text-secondary);
  cursor: pointer;
}

/* ── Cesium 工具栏样式覆盖（深色主题） ── */
.cesium-viewer-toolbar {
  filter: invert(0.9) hue-rotate(180deg);
}
.cesium-viewer-bottom {
  display: none;
}
</style>
