const DEFAULT_DAILY_LIMIT = 30
const MAX_DAILY_LIMIT = 200

function toDate(value) {
  if (!value) {
    return null
  }
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

function startOfDay(date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate())
}

export function getDateKey(value) {
  const date = toDate(value)
  if (!date) {
    return '未记录日期'
  }
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function clampDailyLimit(value) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) {
    return DEFAULT_DAILY_LIMIT
  }
  return Math.max(1, Math.min(MAX_DAILY_LIMIT, Math.round(numeric)))
}

export function normalizeReviewSettings(settings = {}) {
  return {
    dailyLimit: clampDailyLimit(settings.dailyLimit),
    dateMode: settings.dateMode === 'created' ? 'created' : 'review'
  }
}

export function getRecoveryIncrement(anchorValue, nowValue = Date.now()) {
  const anchor = toDate(anchorValue)
  const now = toDate(nowValue)
  if (!anchor || !now) {
    return 0
  }

  const anchorDay = startOfDay(anchor)
  const nowDay = startOfDay(now)
  const elapsedMs = nowDay.getTime() - anchorDay.getTime()
  const elapsedDays = Math.floor(elapsedMs / (24 * 60 * 60 * 1000))
  if (elapsedDays <= 0) {
    return 0
  }
  if (elapsedDays <= 7) {
    return elapsedDays
  }
  return 7 + Math.floor((elapsedDays - 7) / 7)
}

export function buildReviewedVocabulary(items = [], reviewState = {}, nowValue = Date.now()) {
  return items.map((item) => {
    const entry = reviewState[item.id] || {}
    const baseScore = Number.isFinite(entry.baseScore)
      ? Math.max(0, Math.round(entry.baseScore))
      : 3
    const anchor = entry.lastReviewedAt || item.createdAt || nowValue
    const reviewScore = Math.max(0, baseScore + getRecoveryIncrement(anchor, nowValue))
    return {
      ...item,
      reviewBaseScore: baseScore,
      reviewScore,
      lastReviewedAt: entry.lastReviewedAt || '',
      createdDateKey: getDateKey(item.createdAt),
      reviewDateKey: getDateKey(entry.lastReviewedAt)
    }
  })
}

function compareReviewPriority(left, right) {
  if (right.reviewScore !== left.reviewScore) {
    return right.reviewScore - left.reviewScore
  }

  const leftAnchor = toDate(left.lastReviewedAt || left.createdAt)?.getTime() || 0
  const rightAnchor = toDate(right.lastReviewedAt || right.createdAt)?.getTime() || 0
  if (leftAnchor !== rightAnchor) {
    return leftAnchor - rightAnchor
  }

  return String(left.word || '').localeCompare(String(right.word || ''))
}

export function selectDailyReviewIds(reviewedItems = [], dailyLimit = DEFAULT_DAILY_LIMIT) {
  return [...reviewedItems]
    .sort(compareReviewPriority)
    .slice(0, clampDailyLimit(dailyLimit))
    .map((item) => item.id)
}

export function normalizeReviewSession(reviewedItems = [], session = {}, settings = {}, nowValue = Date.now()) {
  const todayKey = getDateKey(nowValue)
  const normalizedSettings = normalizeReviewSettings(settings)
  const currentIds = new Set(reviewedItems.map((item) => item.id))

  if (session?.dateKey === todayKey && Array.isArray(session.itemIds)) {
    const existingIds = session.itemIds.filter((itemId) => currentIds.has(itemId))
    const missingCandidates = selectDailyReviewIds(
      reviewedItems.filter((item) => !existingIds.includes(item.id)),
      normalizedSettings.dailyLimit
    )
    return {
      dateKey: todayKey,
      itemIds: [...existingIds, ...missingCandidates].slice(0, normalizedSettings.dailyLimit)
    }
  }

  return {
    dateKey: todayKey,
    itemIds: selectDailyReviewIds(reviewedItems, normalizedSettings.dailyLimit)
  }
}

export function applyReviewAction(item, reviewState = {}, action, nowValue = Date.now()) {
  const reviewedItem = buildReviewedVocabulary([item], reviewState, nowValue)[0]
  if (!reviewedItem) {
    return reviewState
  }

  let nextBaseScore = reviewedItem.reviewScore
  if (action === 'know') {
    nextBaseScore = Math.max(0, reviewedItem.reviewScore - 3)
  } else if (action === 'fuzzy') {
    nextBaseScore = Math.max(0, reviewedItem.reviewScore - 1)
  } else if (action === 'forgot') {
    nextBaseScore = 3
  }

  return {
    ...reviewState,
    [item.id]: {
      baseScore: nextBaseScore,
      lastReviewedAt: new Date(nowValue).toISOString()
    }
  }
}

export function getHistoryDateKey(item, mode = 'review') {
  return mode === 'created' ? item.createdDateKey : item.reviewDateKey
}

export {
  DEFAULT_DAILY_LIMIT,
  MAX_DAILY_LIMIT
}
