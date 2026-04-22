<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { addVocabulary, buildApiUrl, explainWord, getAuthToken } from '../api/tasks'
import {
  cacheRecentlyPlayedAudio,
  cleanupExpiredAudioCache,
  clearPlaybackProgress,
  getCachedAudioUrl,
  loadPlaybackProgress,
  savePlaybackProgress
} from '../services/mobileCache'

const props = defineProps({
  task: {
    type: Object,
    required: true
  },
  activeLanguage: {
    type: String,
    required: true
  },
  playlist: {
    type: Array,
    default: () => []
  },
  onSelectTask: {
    type: Function,
    default: null
  },
  isMobile: {
    type: Boolean,
    default: false
  },
  user: {
    type: Object,
    default: null
  },
  interfaceLanguage: {
    type: String,
    default: 'zh'
  }
})

const emit = defineEmits(['word-saved'])

const currentTime = ref(0)
const duration = ref(0)
const isPlaying = ref(false)
const playbackRate = ref(1)
const playbackMode = ref('loop')
const loopSegmentIndex = ref(null)
const segmentRefs = ref([])
const audioRef = ref(null)
const audioLoadError = ref('')
const wordDialog = ref(null)
const isExplainingWord = ref(false)
const isSavingWord = ref(false)
const wordDialogError = ref('')
const longPressTimer = ref(null)
const suppressNextTokenClick = ref(false)
const pendingResumeSeconds = ref(null)
const pendingResumeDuration = ref(null)
const appliedResumeTaskId = ref('')
const lastPersistedTaskId = ref('')
const lastPersistedSeconds = ref(-1)
const lastPersistedAt = ref(0)

const tokenPalette = ['token-amber', 'token-teal', 'token-blue', 'token-sand', 'token-ink']

const remoteAudioUrl = computed(() => buildApiUrl(`/tasks/${props.task.id}/audio`))
const resolvedAudioUrl = ref('')
const canPlayAudio = computed(() => Boolean(props.task.audioAvailable))

const activeSegmentIndex = computed(() =>
  props.task.segments.findIndex(
    (segment) => currentTime.value >= segment.startSeconds && currentTime.value < segment.endSeconds
  )
)

const activeSegment = computed(() =>
  activeSegmentIndex.value >= 0 ? props.task.segments[activeSegmentIndex.value] : null
)
const showTranslation = computed(() => Boolean(props.activeLanguage))

const playbackModeLabel = computed(() => {
  if (playbackMode.value === 'segment') {
    return '单句'
  }
  if (playbackMode.value === 'folder') {
    return '顺播'
  }
  return '循环'
})

const currentPlaylistIndex = computed(() =>
  props.playlist.findIndex((item) => item.id === props.task.id)
)

function looksLikeTranslationError(text) {
  if (!text) {
    return true
  }
  const normalized = text.trim().toLowerCase()
  return (
    normalized.startsWith('error 500') ||
    normalized.includes('server error') ||
    normalized.includes('please try again later') ||
    normalized.startsWith('<!doctype html') ||
    normalized.startsWith('<html')
  )
}

function displayTranslation(segment) {
  if (!props.activeLanguage) {
    return ''
  }
  const translated = segment?.translations?.[props.activeLanguage] || ''
  if (looksLikeTranslationError(translated)) {
    return props.activeLanguage === 'en' ? segment?.originalText || '' : '当前语言暂无可用翻译'
  }
  return translated
}

function formatTime(totalSeconds) {
  if (!Number.isFinite(totalSeconds) || totalSeconds < 0) {
    return '0:00'
  }
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = Math.floor(totalSeconds % 60)
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

function syncAudioState() {
  currentTime.value = audioRef.value?.currentTime || 0
  duration.value = audioRef.value?.duration || 0
  isPlaying.value = Boolean(audioRef.value && !audioRef.value.paused)

  if (
    playbackMode.value === 'segment'
    && audioRef.value
    && loopSegmentIndex.value !== null
    && props.task.segments[loopSegmentIndex.value]
  ) {
    const targetSegment = props.task.segments[loopSegmentIndex.value]
    if (currentTime.value >= targetSegment.endSeconds) {
      audioRef.value.currentTime = targetSegment.startSeconds
      currentTime.value = targetSegment.startSeconds
      if (audioRef.value.paused) {
        audioRef.value.play().catch(() => {})
      }
    }
  }

  void persistPlaybackProgress()
}

async function hydrateAudioSource() {
  if (!canPlayAudio.value || !props.task?.id) {
    resolvedAudioUrl.value = ''
    return
  }

  resolvedAudioUrl.value = remoteAudioUrl.value

  try {
    await cleanupExpiredAudioCache()
    const cachedUrl = await getCachedAudioUrl(props.task.id)
    if (cachedUrl) {
      resolvedAudioUrl.value = cachedUrl
    }
  } catch {
    resolvedAudioUrl.value = remoteAudioUrl.value
  }
}

async function warmAudioCache() {
  if (!canPlayAudio.value || !props.task?.id) {
    return
  }

  try {
    const cachedUrl = await cacheRecentlyPlayedAudio({
      taskId: props.task.id,
      remoteUrl: remoteAudioUrl.value,
      token: getAuthToken()
    })

    const isActivelyPlaying = Boolean(audioRef.value && !audioRef.value.paused)
    const hasMeaningfulProgress = (audioRef.value?.currentTime ?? currentTime.value) > 1

    if (cachedUrl && resolvedAudioUrl.value !== cachedUrl && !isActivelyPlaying && !hasMeaningfulProgress) {
      resolvedAudioUrl.value = cachedUrl
    }
  } catch {
    // Keep remote playback when cache warmup fails.
  }
}

function shouldRestorePlaybackPosition(seconds, totalDuration) {
  if (!Number.isFinite(seconds) || seconds < 3) {
    return false
  }
  if (Number.isFinite(totalDuration) && totalDuration > 0 && totalDuration - seconds <= 5) {
    return false
  }
  return true
}

async function persistPlaybackProgress(options = {}) {
  const {
    force = false,
    taskId = props.task?.id,
    seconds = audioRef.value?.currentTime ?? currentTime.value,
    totalDuration = audioRef.value?.duration ?? duration.value
  } = options

  if (!taskId || !Number.isFinite(seconds) || seconds < 0) {
    return
  }

  if (Number.isFinite(totalDuration) && totalDuration > 0 && totalDuration - seconds <= 3) {
    await clearPlaybackProgress(taskId)
    return
  }

  const now = Date.now()
  if (
    !force
    && lastPersistedTaskId.value === taskId
    && Math.abs(lastPersistedSeconds.value - seconds) < 3
    && now - lastPersistedAt.value < 5000
  ) {
    return
  }

  await savePlaybackProgress({
    taskId,
    currentSeconds: seconds,
    duration: totalDuration
  })
  lastPersistedTaskId.value = taskId
  lastPersistedSeconds.value = seconds
  lastPersistedAt.value = now
}

function maybeRestorePlaybackProgress() {
  if (!audioRef.value || appliedResumeTaskId.value === props.task.id || pendingResumeSeconds.value === null) {
    return
  }

  const totalDuration = audioRef.value.duration || duration.value || pendingResumeDuration.value
  if (!shouldRestorePlaybackPosition(pendingResumeSeconds.value, totalDuration)) {
    pendingResumeSeconds.value = null
    pendingResumeDuration.value = null
    return
  }

  const maxResumeTime = Number.isFinite(totalDuration) && totalDuration > 1
    ? Math.max(0, totalDuration - 1)
    : pendingResumeSeconds.value
  const nextTime = Math.min(pendingResumeSeconds.value, maxResumeTime)
  audioRef.value.currentTime = nextTime
  currentTime.value = nextTime
  appliedResumeTaskId.value = props.task.id
  pendingResumeSeconds.value = null
  pendingResumeDuration.value = null
}

function togglePlayback() {
  if (!canPlayAudio.value || !audioRef.value) {
    return
  }

  if (audioRef.value.paused) {
    audioRef.value.play().catch(() => {})
    void warmAudioCache()
    return
  }

  audioRef.value.pause()
}

function stopPlayback() {
  if (audioRef.value) {
    audioRef.value.pause()
  }
  isPlaying.value = false
}

function togglePlaybackRate() {
  const rates = [1, 1.25, 1.5, 2]
  const currentIndex = rates.findIndex((rate) => rate === playbackRate.value)
  const nextRate = rates[(currentIndex + 1) % rates.length]
  playbackRate.value = nextRate

  if (audioRef.value) {
    audioRef.value.playbackRate = nextRate
  }
}

function togglePlaybackMode() {
  if (playbackMode.value === 'loop') {
    playbackMode.value = 'segment'
    loopSegmentIndex.value = activeSegmentIndex.value >= 0 ? activeSegmentIndex.value : 0
    return
  }
  if (playbackMode.value === 'segment') {
    playbackMode.value = 'folder'
    loopSegmentIndex.value = null
    return
  }
  playbackMode.value = 'loop'
  loopSegmentIndex.value = null
}

function handleEnded() {
  currentTime.value = 0
  isPlaying.value = false
  void clearPlaybackProgress(props.task.id)
  pendingResumeSeconds.value = null
  pendingResumeDuration.value = null
  appliedResumeTaskId.value = ''

  if (!audioRef.value) {
    return
  }

  if (playbackMode.value === 'segment' && loopSegmentIndex.value !== null && props.task.segments[loopSegmentIndex.value]) {
    const targetSegment = props.task.segments[loopSegmentIndex.value]
    audioRef.value.currentTime = targetSegment.startSeconds
    currentTime.value = targetSegment.startSeconds
    audioRef.value.play().catch(() => {})
    return
  }

  if (playbackMode.value === 'loop') {
    audioRef.value.currentTime = 0
    audioRef.value.play().catch(() => {})
    return
  }

  if (!props.playlist.length || !props.onSelectTask) {
    return
  }

  const nextIndex = currentPlaylistIndex.value + 1
  const nextTask = props.playlist[nextIndex]

  if (!nextTask) {
    audioRef.value.currentTime = 0
    return
  }

  props.onSelectTask(nextTask.id)
}

function jumpTo(seconds) {
  currentTime.value = seconds
  if (playbackMode.value === 'segment') {
    const targetIndex = props.task.segments.findIndex(
      (segment) => seconds >= segment.startSeconds && seconds < segment.endSeconds
    )
    loopSegmentIndex.value = targetIndex >= 0 ? targetIndex : loopSegmentIndex.value
  }
  if (audioRef.value) {
    audioRef.value.currentTime = seconds
    audioRef.value.play().catch(() => {})
  }
  void persistPlaybackProgress({
    force: true,
    seconds
  })
}

function seekAudio(event) {
  const nextTime = Number(event.target.value)
  currentTime.value = nextTime
  if (playbackMode.value === 'segment') {
    const targetIndex = props.task.segments.findIndex(
      (segment) => nextTime >= segment.startSeconds && nextTime < segment.endSeconds
    )
    loopSegmentIndex.value = targetIndex >= 0 ? targetIndex : loopSegmentIndex.value
  }
  if (audioRef.value) {
    audioRef.value.currentTime = nextTime
  }
  void persistPlaybackProgress({
    force: true,
    seconds: nextTime
  })
}

function setSegmentRef(element, index) {
  if (element) {
    segmentRefs.value[index] = element
  }
}

function scrollActiveSegmentIntoView(index) {
  if (index < 0) {
    return
  }
  const element = segmentRefs.value[index]
  if (!element) {
    return
  }
  element.scrollIntoView({
    behavior: 'smooth',
    block: 'center'
  })
}

function isJapaneseText(text) {
  return /[\u3040-\u30ff\u3400-\u9fff々]/.test(text || '')
}

function tokenizeJapanese(text) {
  const matches = text.match(/([一-龯々]+[ぁ-ゖー]*|[ァ-ヺー]+|[ぁ-ゖー]+|[A-Za-z0-9]+|[。、！？「」（）・…,.!?]+|\s+|.)/g)
  return (matches || []).map((value, index) => ({
    id: `${index}-${value}`,
    value,
    interactive: !/^\s+$/.test(value) && !/^[。、！？「」（）・…,.!?]+$/.test(value),
    colorClass: tokenPalette[index % tokenPalette.length]
  }))
}

function tokenizeEnglish(text) {
  const matches = text.match(/([A-Za-z]+(?:['’-][A-Za-z]+)*|[0-9]+(?:[.,][0-9]+)*|[.,!?;:()[\]"“”‘’/\\-]+|\s+|.)/g)
  return (matches || []).map((value, index) => ({
    id: `${index}-${value}`,
    value,
    interactive: /^[A-Za-z]+(?:['’-][A-Za-z]+)*$/.test(value) || /^[0-9]+(?:[.,][0-9]+)*$/.test(value),
    colorClass: tokenPalette[index % tokenPalette.length]
  }))
}

function buildTokens(text) {
  if (!text) {
    return []
  }
  if (isJapaneseText(text)) {
    return tokenizeJapanese(text)
  }
  return tokenizeEnglish(text)
}

function clearLongPress() {
  if (longPressTimer.value) {
    window.clearTimeout(longPressTimer.value)
    longPressTimer.value = null
  }
}

function beginExplainToken(token, segment) {
  if (!token?.interactive || !props.user) {
    return
  }

  clearLongPress()
  longPressTimer.value = window.setTimeout(async () => {
    suppressNextTokenClick.value = true
    wordDialogError.value = ''
    isExplainingWord.value = true
    wordDialog.value = {
      word: token.value,
      sentence: segment.originalText,
      reading: '',
      meaning: '',
      usage: '',
      example: '',
      source: '',
      saved: false
    }

    try {
      const explanation = await explainWord({
        word: token.value,
        sentence: segment.originalText,
        language: props.task.sourceLanguage || 'ja',
        interfaceLanguage: props.interfaceLanguage || 'zh'
      })
      wordDialog.value = {
        ...wordDialog.value,
        ...explanation,
        sentence: segment.originalText
      }
    } catch (error) {
      wordDialogError.value = error.message
    } finally {
      isExplainingWord.value = false
    }
  }, 420)
}

function endExplainToken() {
  clearLongPress()
  window.setTimeout(() => {
    suppressNextTokenClick.value = false
  }, 120)
}

function handleTokenClick(event) {
  event.stopPropagation()
  if (suppressNextTokenClick.value) {
    suppressNextTokenClick.value = false
  }
}

async function saveWordToVocabulary() {
  if (!wordDialog.value || !props.user) {
    return
  }

  isSavingWord.value = true
  wordDialogError.value = ''
  try {
    const item = await addVocabulary({
      word: wordDialog.value.word,
      reading: wordDialog.value.reading,
      meaning: wordDialog.value.meaning || '暂无释义',
      usage: wordDialog.value.usage,
      example: wordDialog.value.example,
      sentence: wordDialog.value.sentence,
      language: props.task.sourceLanguage || 'ja'
    })
    wordDialog.value.saved = true
    emit('word-saved', item)
  } catch (error) {
    wordDialogError.value = error.message
  } finally {
    isSavingWord.value = false
  }
}

watch(
  () => props.task.id,
  async (taskId, previousTaskId) => {
    if (previousTaskId) {
      await persistPlaybackProgress({
        force: true,
        taskId: previousTaskId
      })
    }
    currentTime.value = 0
    duration.value = 0
    isPlaying.value = false
    playbackRate.value = 1
    playbackMode.value = 'loop'
    loopSegmentIndex.value = null
    segmentRefs.value = []
    audioLoadError.value = ''
    wordDialog.value = null
    appliedResumeTaskId.value = ''
    pendingResumeSeconds.value = null
    pendingResumeDuration.value = null
    const savedProgress = await loadPlaybackProgress(taskId)
    if (savedProgress) {
      pendingResumeSeconds.value = savedProgress.currentSeconds
      pendingResumeDuration.value = savedProgress.duration
    }
    await hydrateAudioSource()
    if (audioRef.value) {
      audioRef.value.pause()
      audioRef.value.playbackRate = 1
      audioRef.value.load()
    }
  },
  { immediate: true }
)

watch(activeSegmentIndex, async (index, previousIndex) => {
  if (index === previousIndex) {
    return
  }
  await nextTick()
  scrollActiveSegmentIntoView(index)
})

onBeforeUnmount(() => {
  void persistPlaybackProgress({ force: true })
  stopPlayback()
  clearLongPress()
})
</script>

<template>
  <section class="player-card" :class="{ 'mobile-player-card': isMobile }">
    <audio
      v-if="canPlayAudio"
      ref="audioRef"
      class="audio-player"
      :class="{ 'audio-player-hidden': isMobile }"
      :src="resolvedAudioUrl"
      :controls="!isMobile"
      preload="metadata"
      @timeupdate="syncAudioState"
      @loadedmetadata="syncAudioState(); maybeRestorePlaybackProgress()"
      @play="isPlaying = true; void warmAudioCache()"
      @pause="isPlaying = false; void persistPlaybackProgress({ force: true })"
      @ended="handleEnded"
      @error="audioLoadError = '这条历史内容没有可播放的本地音频文件，只能查看字幕。'"
    ></audio>

    <p v-if="!canPlayAudio || audioLoadError" class="empty-state">
      {{ audioLoadError || '这条内容目前没有可播放音频，可以先看字幕内容。' }}
    </p>

    <div v-if="activeSegment" class="current-caption-card">
      <p class="current-caption-label">当前字幕</p>
      <p class="current-caption-original token-line">
        <template v-for="token in buildTokens(activeSegment.originalText)" :key="token.id">
          <span
            class="word-token"
            :class="token.colorClass"
            @mousedown.stop="beginExplainToken(token, activeSegment)"
            @mouseup.stop="endExplainToken"
            @mouseleave.stop="endExplainToken"
            @touchstart.stop.prevent="beginExplainToken(token, activeSegment)"
            @touchend.stop="endExplainToken"
            @touchcancel.stop="endExplainToken"
            @click.stop="handleTokenClick"
          >
            {{ token.value }}
          </span>
        </template>
      </p>
      <p v-if="showTranslation" class="current-caption-translation">{{ displayTranslation(activeSegment) || '当前语言暂无翻译' }}</p>
    </div>

    <div class="segment-list" :class="{ 'mobile-segment-list': isMobile }">
      <article
        v-for="(segment, index) in task.segments"
        :key="segment.id"
        class="segment-card"
        :class="{ active: index === activeSegmentIndex }"
        @click="jumpTo(segment.startSeconds)"
        :ref="(element) => setSegmentRef(element, index)"
      >
        <p class="segment-original">{{ segment.originalText }}</p>
        <p v-if="showTranslation" class="segment-translation">{{ displayTranslation(segment) || '当前语言暂无翻译' }}</p>
      </article>
    </div>

    <div v-if="isMobile && canPlayAudio" class="mobile-player-dock">
      <div class="mobile-progress-row">
        <span>{{ formatTime(currentTime) }}</span>
        <input
          class="mobile-progress-slider"
          type="range"
          min="0"
          :max="duration || 0"
          step="0.1"
          :value="currentTime"
          @input="seekAudio"
        />
        <span>{{ formatTime(duration) }}</span>
      </div>

      <div class="mobile-control-row">
        <button type="button" class="mobile-mode-button" @click="togglePlaybackMode">
          {{ playbackModeLabel }}
        </button>
        <button type="button" class="mobile-rate-button" @click="togglePlaybackRate">
          {{ playbackRate }}x
        </button>
        <button type="button" class="mobile-play-button" @click="togglePlayback">
          {{ isPlaying ? '暂停' : '播放' }}
        </button>
      </div>
    </div>

    <div v-if="wordDialog" class="overlay-shell" @click.self="wordDialog = null">
      <section class="overlay-card word-dialog">
        <div class="panel-header">
          <div>
            <p class="eyebrow">词语解释</p>
            <h2>{{ wordDialog.word }}</h2>
          </div>
          <button type="button" class="ghost-button" @click="wordDialog = null">关闭</button>
        </div>

        <p v-if="isExplainingWord" class="empty-state">正在生成解释...</p>
        <p v-if="wordDialogError" class="error-banner">{{ wordDialogError }}</p>

        <div v-if="!isExplainingWord" class="word-dialog-body">
          <p v-if="wordDialog.reading" class="word-dialog-reading">{{ wordDialog.reading }}</p>
          <div class="word-dialog-block">
            <span>意思</span>
            <p>{{ wordDialog.meaning || '暂无释义' }}</p>
          </div>
          <div class="word-dialog-block">
            <span>用法</span>
            <p>{{ wordDialog.usage || '建议结合上下文一起记忆。' }}</p>
          </div>
          <div class="word-dialog-block">
            <span>例句</span>
            <p>{{ wordDialog.example || wordDialog.sentence }}</p>
          </div>
        </div>

        <div class="word-dialog-actions">
          <button
            type="button"
            class="primary-button"
            :disabled="isSavingWord || wordDialog.saved"
            @click="saveWordToVocabulary"
          >
            {{
              wordDialog.saved
                ? '已加入生词本'
                : isSavingWord
                  ? '加入中...'
                  : '加入生词本'
            }}
          </button>
        </div>
      </section>
    </div>
  </section>
</template>
