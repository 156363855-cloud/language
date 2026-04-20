<script setup>
import { computed, ref, watch } from 'vue'
import {
  loadVocabularyReviewSession,
  loadVocabularyReviewSettings,
  loadVocabularyReviewState,
  saveVocabularyReviewSession,
  saveVocabularyReviewSettings,
  saveVocabularyReviewState
} from '../services/mobileCache'
import {
  applyReviewAction,
  buildReviewedVocabulary,
  clampDailyLimit,
  getDateKey,
  getHistoryDateKey,
  normalizeReviewSession,
  normalizeReviewSettings
} from '../services/vocabularyReview'

const props = defineProps({
  vocabulary: {
    type: Array,
    default: () => []
  },
  isLoading: {
    type: Boolean,
    default: false
  },
  interfaceLanguage: {
    type: String,
    default: 'zh'
  },
  userId: {
    type: String,
    default: ''
  },
  mode: {
    type: String,
    default: 'study'
  }
})

const emit = defineEmits(['remove-item'])

const messages = {
  zh: {
    loading: '加载中...',
    noWords: '你还没有加入任何生词，长按字幕里的词就可以加入。',
    completedTitle: '今天的单词都记完了',
    completedHint: '明天会按规则重新安排需要复习的词。',
    tapToReveal: '点一下屏幕查看解释',
    remaining: '剩余',
    reading: '读法',
    meaning: '解释',
    usage: '用法',
    example: '例句',
    know: '记住',
    fuzzy: '模糊',
    forgot: '忘记',
    dailyLimit: '每日学习量',
    dateMode: '日期视图',
    createdMode: '按加入日期',
    reviewMode: '按复习日期',
    datePicker: '选择日期',
    emptyDate: '这一天没有对应的生词。',
    noReviewDate: '未复习',
    deleteWord: '删除',
    scoreLabel: '记忆值',
    historyTitle: '生词本设置',
    limitHint: '每天只抽取记忆值最高的一批词做复习。',
    settingsHint: '这里放学习量、日期查看和删除词。',
    totalWords: '生词总数'
  },
  ja: {
    loading: '読み込み中...',
    noWords: 'まだ単語がありません。字幕の単語を長押しすると追加できます。',
    completedTitle: '今日の単語は全部終わりました',
    completedHint: '明日またルールに沿って復習単語を作ります。',
    tapToReveal: '画面をタップして解説を見る',
    remaining: '残り',
    reading: '読み方',
    meaning: '意味',
    usage: '使い方',
    example: '例文',
    know: '記住',
    fuzzy: '模糊',
    forgot: '忘记',
    dailyLimit: '1日の学習数',
    dateMode: '日付表示',
    createdMode: '追加日で表示',
    reviewMode: '復習日で表示',
    datePicker: '日付を選ぶ',
    emptyDate: 'この日付の単語はありません。',
    noReviewDate: '未復習',
    deleteWord: '削除',
    scoreLabel: '記憶値',
    historyTitle: '単語帳設定',
    limitHint: '記憶値が高い単語から今日の復習に入ります。',
    settingsHint: 'ここで学習量、日付確認、削除を管理します。',
    totalWords: '単語総数'
  },
  en: {
    loading: 'Loading...',
    noWords: 'No vocabulary yet. Long-press a word in subtitles to save it.',
    completedTitle: 'Today’s words are done',
    completedHint: 'Tomorrow a new review list will be prepared by the rule.',
    tapToReveal: 'Tap the screen to reveal the explanation',
    remaining: 'Remaining',
    reading: 'Reading',
    meaning: 'Meaning',
    usage: 'Usage',
    example: 'Example',
    know: 'Know',
    fuzzy: 'Fuzzy',
    forgot: 'Forgot',
    dailyLimit: 'Daily limit',
    dateMode: 'Date view',
    createdMode: 'By saved date',
    reviewMode: 'By review date',
    datePicker: 'Select date',
    emptyDate: 'No vocabulary for this date.',
    noReviewDate: 'Not reviewed',
    deleteWord: 'Delete',
    scoreLabel: 'Score',
    historyTitle: 'Vocabulary Settings',
    limitHint: 'Only the highest-score words enter today’s review list.',
    settingsHint: 'Manage the daily limit, date view, and deletion here.',
    totalWords: 'Total words'
  }
}

const reviewState = ref({})
const reviewSettings = ref(normalizeReviewSettings())
const reviewSession = ref({
  dateKey: getDateKey(Date.now()),
  itemIds: []
})
const selectedHistoryDate = ref(getDateKey(Date.now()))
const activeItemId = ref('')
const isRevealed = ref(false)
const localReady = ref(false)

const uiLanguage = computed(() => (['zh', 'ja', 'en'].includes(props.interfaceLanguage) ? props.interfaceLanguage : 'zh'))
const text = computed(() => messages[uiLanguage.value] || messages.zh)
const isStudyMode = computed(() => props.mode !== 'settings')

const reviewedVocabulary = computed(() =>
  buildReviewedVocabulary(props.vocabulary || [], reviewState.value || {})
)

const todayReviewItems = computed(() => {
  const itemMap = new Map(reviewedVocabulary.value.map((item) => [item.id, item]))
  return (reviewSession.value?.itemIds || [])
    .map((itemId) => itemMap.get(itemId))
    .filter(Boolean)
})

const pendingTodayItems = computed(() =>
  todayReviewItems.value.filter((item) => item.reviewScore > 0)
)

const currentReviewItem = computed(() =>
  pendingTodayItems.value.find((item) => item.id === activeItemId.value)
  || pendingTodayItems.value[0]
  || null
)

const availableHistoryDates = computed(() => {
  const values = new Set(
    reviewedVocabulary.value.map((item) => getHistoryDateKey(item, reviewSettings.value.dateMode))
  )
  return [...values].sort((left, right) => {
    if (left === '未记录日期') {
      return 1
    }
    if (right === '未记录日期') {
      return -1
    }
    return right.localeCompare(left)
  })
})

const historyItems = computed(() =>
  reviewedVocabulary.value
    .filter((item) => getHistoryDateKey(item, reviewSettings.value.dateMode) === selectedHistoryDate.value)
    .sort((left, right) => {
      const rightTime = new Date(right.lastReviewedAt || right.createdAt || 0).getTime()
      const leftTime = new Date(left.lastReviewedAt || left.createdAt || 0).getTime()
      return rightTime - leftTime
    })
)

function t(key) {
  return text.value[key] || messages.zh[key] || key
}

function formatDateLabel(dateKey) {
  if (!dateKey || dateKey === '未记录日期') {
    return t('noReviewDate')
  }
  const date = new Date(`${dateKey}T00:00:00`)
  if (Number.isNaN(date.getTime())) {
    return dateKey
  }
  if (uiLanguage.value === 'ja') {
    return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`
  }
  if (uiLanguage.value === 'en') {
    return date.toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric'
    })
  }
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`
}

function pruneReviewState(nextState = {}) {
  const itemIds = new Set((props.vocabulary || []).map((item) => item.id))
  return Object.fromEntries(
    Object.entries(nextState).filter(([itemId]) => itemIds.has(itemId))
  )
}

function syncSessionAndActiveItem() {
  if (!localReady.value) {
    return
  }

  const normalizedSession = normalizeReviewSession(
    reviewedVocabulary.value,
    reviewSession.value,
    reviewSettings.value
  )

  if (JSON.stringify(normalizedSession) !== JSON.stringify(reviewSession.value)) {
    reviewSession.value = normalizedSession
  }

  const pendingIds = new Set(pendingTodayItems.value.map((item) => item.id))
  if (pendingIds.has(activeItemId.value)) {
    return
  }

  activeItemId.value = pendingTodayItems.value[0]?.id || ''
}

async function hydrateLocalReviewData() {
  activeItemId.value = ''
  isRevealed.value = false

  if (!props.userId) {
    reviewState.value = {}
    reviewSettings.value = normalizeReviewSettings()
    reviewSession.value = {
      dateKey: getDateKey(Date.now()),
      itemIds: []
    }
    selectedHistoryDate.value = getDateKey(Date.now())
    localReady.value = true
    return
  }

  const [storedState, storedSettings, storedSession] = await Promise.all([
    loadVocabularyReviewState(props.userId),
    loadVocabularyReviewSettings(props.userId),
    loadVocabularyReviewSession(props.userId)
  ])

  reviewState.value = pruneReviewState(storedState || {})
  reviewSettings.value = normalizeReviewSettings(storedSettings || {})
  reviewSession.value = storedSession || {
    dateKey: getDateKey(Date.now()),
    itemIds: []
  }
  selectedHistoryDate.value = getDateKey(Date.now())
  localReady.value = true
  syncSessionAndActiveItem()
}

function revealCurrentCard() {
  if (!currentReviewItem.value || isRevealed.value) {
    return
  }
  isRevealed.value = true
}

function updateDailyLimit(event) {
  reviewSettings.value = {
    ...reviewSettings.value,
    dailyLimit: clampDailyLimit(event?.target?.value ?? reviewSettings.value.dailyLimit)
  }
}

function updateDateMode(event) {
  reviewSettings.value = {
    ...reviewSettings.value,
    dateMode: event?.target?.value === 'created' ? 'created' : 'review'
  }
}

function updateSelectedHistoryDate(event) {
  selectedHistoryDate.value = event?.target?.value || availableHistoryDates.value[0] || getDateKey(Date.now())
}

function removeWord(itemId) {
  emit('remove-item', itemId)
}

function answerCurrentCard(action) {
  if (!currentReviewItem.value) {
    return
  }

  reviewState.value = applyReviewAction(currentReviewItem.value, reviewState.value, action)
  isRevealed.value = false
  syncSessionAndActiveItem()
}

watch(
  () => props.userId,
  () => {
    localReady.value = false
    void hydrateLocalReviewData()
  },
  { immediate: true }
)

watch(
  () => props.vocabulary,
  async () => {
    if (!props.userId) {
      return
    }
    if (!localReady.value) {
      await hydrateLocalReviewData()
      return
    }
    reviewState.value = pruneReviewState(reviewState.value)
    syncSessionAndActiveItem()
  },
  { deep: true }
)

watch(
  reviewedVocabulary,
  () => {
    syncSessionAndActiveItem()
  },
  { deep: true }
)

watch(
  () => reviewSettings.value.dailyLimit,
  () => {
    syncSessionAndActiveItem()
  }
)

watch(
  currentReviewItem,
  () => {
    isRevealed.value = false
  }
)

watch(
  availableHistoryDates,
  (dates) => {
    if (!dates.length) {
      selectedHistoryDate.value = getDateKey(Date.now())
      return
    }
    if (!dates.includes(selectedHistoryDate.value)) {
      selectedHistoryDate.value = dates[0]
    }
  },
  { immediate: true }
)

watch(
  reviewState,
  async (value) => {
    if (!props.userId || !localReady.value) {
      return
    }
    await saveVocabularyReviewState(props.userId, value)
  },
  { deep: true }
)

watch(
  reviewSettings,
  async (value) => {
    if (!props.userId || !localReady.value) {
      return
    }
    await saveVocabularyReviewSettings(props.userId, normalizeReviewSettings(value))
  },
  { deep: true }
)

watch(
  reviewSession,
  async (value) => {
    if (!props.userId || !localReady.value) {
      return
    }
    await saveVocabularyReviewSession(props.userId, value)
  },
  { deep: true }
)
</script>

<template>
  <section
    class="vocabulary-study-shell"
    :class="{ 'study-shell-clickable': isStudyMode && currentReviewItem && !isRevealed }"
    @click="isStudyMode && !isRevealed ? revealCurrentCard() : null"
  >
    <p v-if="isLoading" class="empty-state">{{ t('loading') }}</p>
    <p v-else-if="!reviewedVocabulary.length" class="empty-state">{{ t('noWords') }}</p>

    <template v-else-if="isStudyMode">
      <section
        v-if="currentReviewItem"
        class="vocabulary-simple-review"
        :class="{ 'can-reveal': !isRevealed }"
      >
        <div class="vocabulary-simple-head">
          <span>{{ t('remaining') }} {{ pendingTodayItems.length }}</span>
        </div>

        <button
          type="button"
          class="vocabulary-simple-card"
          :class="{ revealed: isRevealed }"
          @click.stop="revealCurrentCard"
        >
          <strong>{{ currentReviewItem.word }}</strong>
          <p v-if="!isRevealed" class="vocabulary-front-hint">{{ t('tapToReveal') }}</p>
        </button>

        <div v-if="isRevealed" class="vocabulary-answer-body">
          <div class="word-dialog-block">
            <span>{{ t('reading') }}</span>
            <p>{{ currentReviewItem.reading || '-' }}</p>
          </div>
          <div class="word-dialog-block">
            <span>{{ t('meaning') }}</span>
            <p>{{ currentReviewItem.meaning || '-' }}</p>
          </div>
          <div class="word-dialog-block">
            <span>{{ t('usage') }}</span>
            <p>{{ currentReviewItem.usage || '-' }}</p>
          </div>
          <div class="word-dialog-block">
            <span>{{ t('example') }}</span>
            <p>{{ currentReviewItem.example || currentReviewItem.sentence || '-' }}</p>
          </div>

          <div class="vocabulary-review-actions" @click.stop>
            <button type="button" class="danger-button" @click.stop="answerCurrentCard('forgot')">
              {{ t('forgot') }}
            </button>
            <button type="button" class="ghost-button" @click.stop="answerCurrentCard('fuzzy')">
              {{ t('fuzzy') }}
            </button>
            <button type="button" class="primary-button" @click.stop="answerCurrentCard('know')">
              {{ t('know') }}
            </button>
          </div>
        </div>
      </section>

      <section v-else class="vocabulary-review-card vocabulary-review-complete">
        <h3>{{ t('completedTitle') }}</h3>
        <p class="hero-text">{{ t('completedHint') }}</p>
      </section>
    </template>

    <section v-else class="vocabulary-history-card">
      <div>
        <p class="eyebrow">{{ t('historyTitle') }}</p>
        <h3>{{ t('settingsHint') }}</h3>
      </div>

      <label>
        <span>{{ t('dailyLimit') }}</span>
        <input
          type="number"
          min="1"
          max="200"
          :value="reviewSettings.dailyLimit"
          @change="updateDailyLimit"
        />
      </label>
      <p class="vocabulary-setting-hint">{{ t('limitHint') }}</p>

      <div class="vocabulary-history-controls">
        <label>
          <span>{{ t('dateMode') }}</span>
          <select :value="reviewSettings.dateMode" @change="updateDateMode">
            <option value="review">{{ t('reviewMode') }}</option>
            <option value="created">{{ t('createdMode') }}</option>
          </select>
        </label>
        <label>
          <span>{{ t('datePicker') }}</span>
          <select :value="selectedHistoryDate" @change="updateSelectedHistoryDate">
            <option v-for="dateKey in availableHistoryDates" :key="dateKey" :value="dateKey">
              {{ formatDateLabel(dateKey) }}
            </option>
          </select>
        </label>
      </div>

      <div class="vocabulary-study-summary single-summary">
        <article class="vocabulary-summary-pill">
          <span>{{ t('totalWords') }}</span>
          <strong>{{ reviewedVocabulary.length }}</strong>
        </article>
      </div>

      <p v-if="historyItems.length === 0" class="empty-state">{{ t('emptyDate') }}</p>

      <div v-else class="vocabulary-list">
        <article v-for="item in historyItems" :key="item.id" class="vocabulary-item vocabulary-history-item">
          <div class="vocabulary-item-top">
            <strong>{{ item.word }}</strong>
            <span class="vocabulary-score-chip">{{ t('scoreLabel') }} {{ item.reviewScore }}</span>
          </div>
          <p v-if="item.reading" class="vocabulary-reading">{{ item.reading }}</p>
          <p class="vocabulary-meta">{{ item.meaning || item.sentence || '-' }}</p>
          <div class="vocabulary-history-actions">
            <small>{{ formatDateLabel(getHistoryDateKey(item, reviewSettings.dateMode)) }}</small>
            <button type="button" class="ghost-button" @click="removeWord(item.id)">
              {{ t('deleteWord') }}
            </button>
          </div>
        </article>
      </div>
    </section>
  </section>
</template>
