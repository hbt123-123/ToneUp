/**
 * 图表数据契约（EC-03）：仅描述形状，不含取数逻辑；
 * 数据由 store/VM 注入，图表组件零 store 依赖、零网络请求。
 * 字段与后端 GET /api/stats/daily-trend（§6.11）及 Android TrendCharts.kt 对齐。
 */

/** 每日趋势点（daily-trend 响应 points 项；UTC 日历日分桶） */
export interface DailyTrendPoint {
  /** 日期，格式 YYYY-MM-DD */
  date: string
  /** 当日去重作答题数 */
  attempts: number
  /** 当日正确率 0~1（4 位小数；空天为 0） */
  correct_rate: number
}

/** 专题进度项（分节已做/总数） */
export interface TopicProgressItem {
  title: string
  done: number
  total: number
}

/** 成绩历史点（会话序号 → 得分率） */
export interface ScoreHistoryPoint {
  /** 会话序号，从 1 开始 */
  index: number
  /** 得分率 0~1 */
  score_rate: number
}
