<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Camera, CameraResultType, CameraSource } from '@capacitor/camera'
import SubtitlePlayer from './components/SubtitlePlayer.vue'
import VocabularyStudyPanel from './components/VocabularyStudyPanel.vue'
import {
  addVocabulary,
  clearAuthToken,
  createFolder,
  createTask,
  deleteTask,
  fetchCurrentUser,
  fetchFolders,
  fetchTask,
  fetchTasks,
  fetchVocabulary,
  getAuthToken,
  hydrateAuthToken,
  login,
  logout,
  moveTask,
  register,
  removeFolder,
  removeVocabulary,
  setAuthToken,
  syncRuntimeToCloud,
  updateProfile,
  updateFolder
} from './api/tasks'
import { parseBulkMediaInput, readBatchFileAsText, resolveDesktopAddMediaUrls } from './utils/bulkMediaUrls.js'
import {
  cleanupExpiredAudioCache,
  clearCachedLibrarySnapshot,
  clearCachedProfile,
  clearCachedVocabulary,
  loadCachedLibrarySnapshot,
  loadCachedProfile,
  loadCachedVocabulary,
  saveCachedLibrarySnapshot,
  saveCachedProfile,
  saveCachedVocabulary
} from './services/mobileCache'
import { Capacitor } from '@capacitor/core'

const INTERFACE_LANGUAGE_KEY = 'lingualink_interface_language'
const PWA_INSTALL_DISMISSED_KEY = 'broadcast_jici_pwa_install_dismissed_v1'

const languageOptions = [
  { value: 'zh', labels: { zh: '中文', ja: '中国語', en: 'Chinese' } },
  { value: 'ja', labels: { zh: '日文', ja: '日本語', en: 'Japanese' } },
  { value: 'en', labels: { zh: '英文', ja: '英語', en: 'English' } }
]

const languageLabelMap = {
  zh: '中',
  ja: '日',
  en: '英'
}

const uiMessages = {
  zh: {
    account: '账号',
    loginTitle: '登录后使用生词本和词语解释',
    registerTitle: '先创建一个本地账号',
    loginHint: '目前先用邮箱和密码登录，不做验证码。生词本会绑定到当前账号下面。',
    interfaceLanguage: '界面语言',
    email: '邮箱',
    password: '密码',
    passwordPlaceholder: '至少 6 位',
    submitting: '提交中...',
    login: '登录',
    registerAndLogin: '注册并登录',
    goRegister: '去注册',
    goLogin: '已有账号，去登录',
    currentAccount: '当前账号',
    vocabulary: '生词本',
    vocabularySettings: '生词本设置',
    refreshContent: '刷新内容',
    refreshing: '刷新中...',
    logout: '退出登录',
    me: '我的',
    uploadAvatar: '上传头像',
    settings: '设置',
    wantToListen: '想要听广播',
    currentShowPrefix: '当前会显示',
    currentShowSuffix: '内容。',
    tools: '工具',
    feedback: '反馈',
    feedbackHint: '如果你想提建议或者反馈问题，可以直接加我微信：',
    radioManagement: '广播管理',
    createCategory: '创建大类',
    createChannel: '创建广播',
    categoryLanguage: '内容语言',
    parentCategory: '归属大类',
    categoryPanel: '广播大类',
    podcastList: '广播列表',
    audioList: '音频列表',
    enterPodcast: '进入广播',
    openAudio: '打开音频',
    back: '返回',
    noChannels: '这个大类下面还没有广播。',
    noAudios: '这个广播下面还没有音频。',
    podcastTab: 'Podcast',
    vocabularyTab: '生词本',
    myTab: '我的',
    loading: '加载中...',
    noVocabulary: '你还没有加入任何生词，长按字幕里的词就可以加入。',
    expand: '展开',
    collapse: '收起',
    viewExplain: '查看解释',
    close: '关闭',
    language: 'Language',
    closeTranslation: '关闭翻译',
    installTitle: '添加到主屏幕',
    installDescription: '装到主屏幕后，打开时就更像 App，也能启用离线缓存。',
    installButton: '立即安装',
    installLater: '稍后',
    installIosHint: '请用 Safari 打开，然后点“分享” -> “添加到主屏幕”。'
  },
  ja: {
    account: 'アカウント',
    loginTitle: 'ログインして単語帳と単語解説を使う',
    registerTitle: '先にローカルアカウントを作成する',
    loginHint: '今はメールアドレスとパスワードでログインします。認証コードはまだありません。単語帳は現在のアカウントに紐づきます。',
    interfaceLanguage: '表示言語',
    email: 'メール',
    password: 'パスワード',
    passwordPlaceholder: '6文字以上',
    submitting: '送信中...',
    login: 'ログイン',
    registerAndLogin: '登録してログイン',
    goRegister: '新規登録へ',
    goLogin: 'すでにアカウントあり、ログインへ',
    currentAccount: '現在のアカウント',
    vocabulary: '単語帳',
    vocabularySettings: '単語帳設定',
    refreshContent: '内容を更新',
    refreshing: '更新中...',
    logout: 'ログアウト',
    me: 'マイ',
    uploadAvatar: 'アイコンをアップロード',
    settings: '設定',
    wantToListen: '聞きたい放送',
    currentShowPrefix: '現在は',
    currentShowSuffix: 'の内容を表示します。',
    tools: 'ツール',
    feedback: 'フィードバック',
    feedbackHint: '提案や不具合があれば、WeChatで連絡してください：',
    radioManagement: '番組管理',
    createCategory: 'カテゴリ作成',
    createChannel: '番組作成',
    categoryLanguage: '内容言語',
    parentCategory: '親カテゴリ',
    categoryPanel: 'カテゴリ一覧',
    podcastList: '番組一覧',
    audioList: '音声一覧',
    enterPodcast: '番組へ',
    openAudio: '音声を開く',
    back: '戻る',
    noChannels: 'このカテゴリにはまだ番組がありません。',
    noAudios: 'この番組にはまだ音声がありません。',
    podcastTab: 'Podcast',
    vocabularyTab: '単語帳',
    myTab: 'マイ',
    loading: '読み込み中...',
    noVocabulary: 'まだ単語がありません。字幕の単語を長押しすると追加できます。',
    expand: '展開',
    collapse: '折りたたむ',
    viewExplain: '解説を見る',
    close: '閉じる',
    language: 'Language',
    closeTranslation: '翻訳オフ',
    installTitle: 'ホーム画面に追加',
    installDescription: 'ホーム画面に追加すると、App に近い見た目で開けて、オフラインキャッシュも使いやすくなります。',
    installButton: '今すぐ追加',
    installLater: 'あとで',
    installIosHint: 'Safari で開き、「共有」→「ホーム画面に追加」を押してください。'
  },
  en: {
    account: 'Account',
    loginTitle: 'Log in to use vocabulary and word explanations',
    registerTitle: 'Create a local account first',
    loginHint: 'For now, sign in with email and password only. Vocabulary will be saved under the current account.',
    interfaceLanguage: 'Interface language',
    email: 'Email',
    password: 'Password',
    passwordPlaceholder: 'At least 6 characters',
    submitting: 'Submitting...',
    login: 'Log in',
    registerAndLogin: 'Register and log in',
    goRegister: 'Create account',
    goLogin: 'Already have an account? Log in',
    currentAccount: 'Current account',
    vocabulary: 'Vocabulary',
    vocabularySettings: 'Vocabulary Settings',
    refreshContent: 'Refresh content',
    refreshing: 'Refreshing...',
    logout: 'Log out',
    me: 'Me',
    uploadAvatar: 'Upload avatar',
    settings: 'Settings',
    wantToListen: 'Preferred content',
    currentShowPrefix: 'Currently showing',
    currentShowSuffix: 'content.',
    tools: 'Tools',
    feedback: 'Feedback',
    feedbackHint: 'If you have suggestions or bug reports, add me on WeChat:',
    radioManagement: 'Broadcast management',
    createCategory: 'Create category',
    createChannel: 'Create channel',
    categoryLanguage: 'Content language',
    parentCategory: 'Parent category',
    categoryPanel: 'Categories',
    podcastList: 'Broadcasts',
    audioList: 'Audio list',
    enterPodcast: 'Open broadcast',
    openAudio: 'Open audio',
    back: 'Back',
    noChannels: 'There are no broadcasts in this category yet.',
    noAudios: 'There is no audio in this broadcast yet.',
    podcastTab: 'Podcast',
    vocabularyTab: 'Vocabulary',
    myTab: 'Me',
    loading: 'Loading...',
    noVocabulary: 'No vocabulary yet. Long-press a word in subtitles to save it.',
    expand: 'Expand',
    collapse: 'Collapse',
    viewExplain: 'View explanation',
    close: 'Close',
    language: 'Language',
    closeTranslation: 'Hide translation',
    installTitle: 'Add to Home Screen',
    installDescription: 'Install it on your home screen for an app-like launch experience and offline caching.',
    installButton: 'Install now',
    installLater: 'Later',
    installIosHint: 'Open this page in Safari, tap Share, then choose Add to Home Screen.'
  }
}

const sourceLanguageOptions = [
  { value: 'zh' },
  { value: 'ja' },
  { value: 'en' }
]

function normalizeLanguage(value) {
  return languageOptions.some((language) => language.value === value) ? value : 'zh'
}

function detectSystemLanguage() {
  if (typeof navigator === 'undefined') {
    return 'zh'
  }

  const candidates = [
    ...(Array.isArray(navigator.languages) ? navigator.languages : []),
    navigator.language,
    navigator.userLanguage,
    navigator.browserLanguage
  ].filter(Boolean)

  for (const candidate of candidates) {
    const normalized = String(candidate).toLowerCase()
    if (normalized.startsWith('ja')) {
      return 'ja'
    }
    if (normalized.startsWith('en')) {
      return 'en'
    }
    if (normalized.startsWith('zh')) {
      return 'zh'
    }
  }

  return 'zh'
}

function readStoredInterfaceLanguage() {
  if (typeof window === 'undefined') {
    return detectSystemLanguage()
  }
  try {
    const storedLanguage = localStorage.getItem(INTERFACE_LANGUAGE_KEY)
    return storedLanguage ? normalizeLanguage(storedLanguage) : detectSystemLanguage()
  } catch {
    return detectSystemLanguage()
  }
}

const form = ref({
  mediaUrl: '',
  sourceLanguage: 'en',
  targetLanguages: 'zh,ja',
  folderId: 'inbox'
})
const authForm = ref({
  email: '',
  password: ''
})
const interfaceLanguage = ref(readStoredInterfaceLanguage())
const authMode = ref('login')
const currentUser = ref(null)
const authReady = ref(false)
const isSubmittingAuth = ref(false)
const mobileTab = ref('podcast')
const mobilePodcastLevel = ref('categories')
const selectedMobileCategoryId = ref('')
const selectedMobileChannelId = ref('')
const selectedDesktopCategoryId = ref('')
const selectedDesktopChannelId = ref('')
const desktopLibraryLevel = ref('categories')
const hasOpenedDesktopChannel = ref(false)
const desktopTaskCategoryId = ref('')
const desktopTaskChannelId = ref('')
const selectedDesktopCategoryRadioId = ref('')
const selectedDesktopChannelRadioId = ref('')
const isUpdatingProfile = ref(false)
const isCreatingCategory = ref(false)
const isCreatingChannel = ref(false)
const newFolderName = ref('')
const renameFolderName = ref('')
const adminCategoryForm = ref({
  name: '未命名分类',
  contentLanguage: 'ja',
  coverImageDataUrl: '',
  coverOpacity: 50
})
const adminChannelForm = ref({
  name: '未命名广播',
  parentId: '',
  coverImageDataUrl: '',
  coverOpacity: 50
})
const bulkMediaInput = ref('')
const batchFileName = ref('')
const showFolderEditorModal = ref(false)
const folderEditorMode = ref('category')
const folderEditorForm = ref({
  id: '',
  name: '',
  parentId: '',
  coverImageDataUrl: '',
  coverOpacity: 50
})
const isSubmitting = ref(false)
const isCreatingFolder = ref(false)
const isLoading = ref(false)
const isMovingTask = ref(false)
const isDeletingFolder = ref(false)
const isRenamingFolder = ref(false)
const isSyncingCloud = ref(false)
const isLoggingOut = ref(false)
const isLoadingVocabulary = ref(false)
const vocabulary = ref([])
const showVocabularyModal = ref(false)
const showVocabularySettingsModal = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const authErrorMessage = ref('')
const tasks = ref([])
const folders = ref([])
const selectedTaskId = ref('')
const selectedFolderId = ref('inbox')
const taskMoveFolderId = ref('inbox')
const activeLanguage = ref('zh')
const currentView = ref('dashboard')
const viewportWidth = ref(typeof window === 'undefined' ? 1280 : window.innerWidth)
const deferredInstallPrompt = ref(null)
const dismissedInstallPrompt = ref(false)
const isStandalonePwa = ref(false)
let pollTimer = null

const selectedTask = computed(() => tasks.value.find((task) => task.id === selectedTaskId.value) || null)
const isNativeApp = computed(() => Capacitor.isNativePlatform())
const isMobileLayout = computed(() => viewportWidth.value <= 820)
const showDesktopEpisodeProgressPct = computed(() => viewportWidth.value > 1024)
const currentInterfaceLanguage = computed(() => normalizeLanguage(interfaceLanguage.value))
const isIosDevice = computed(() => {
  if (typeof navigator === 'undefined') {
    return false
  }
  const userAgent = navigator.userAgent || ''
  return /iphone|ipad|ipod/i.test(userAgent)
})
const canShowInstallPrompt = computed(() =>
  !isNativeApp.value
  && isMobileLayout.value
  && !isStandalonePwa.value
  && !dismissedInstallPrompt.value
  && (Boolean(deferredInstallPrompt.value) || isIosDevice.value)
)
const preferredContentLanguage = computed(() => currentUser.value?.preferredContentLanguage || 'en')
const visibleTasks = computed(() => {
  if (!isMobileLayout.value) {
    return tasks.value
  }
  return tasks.value.filter((task) => task.sourceLanguage === preferredContentLanguage.value)
})
const visibleFolders = computed(() => {
  if (!isMobileLayout.value) {
    return folders.value
  }
  return folders.value.filter((folder) => visibleTasks.value.some((task) => task.folderId === folder.id))
})
const filteredTasks = computed(() => visibleTasks.value.filter((task) => task.folderId === selectedFolderId.value))
const selectedFolder = computed(() => visibleFolders.value.find((folder) => folder.id === selectedFolderId.value) || null)
const selectedFolderTaskCount = computed(() => filteredTasks.value.length)
const detailPlaylist = computed(() =>
  filteredTasks.value.filter((task) => task.status === 'COMPLETED' && task.audioAvailable)
)
const detailLanguageOptions = computed(() => {
  if (!selectedTask.value) {
    return []
  }

  const sourceLanguage = selectedTask.value.sourceLanguage || ''
  const translationKeys = new Set()
  for (const segment of selectedTask.value.segments || []) {
    for (const key of Object.keys(segment.translations || {})) {
      if (key && key !== sourceLanguage) {
        translationKeys.add(key)
      }
    }
  }

  return [
    {
      value: '',
      label: t('closeTranslation')
    },
    ...[...translationKeys]
    .filter((language) => languageLabelMap[language])
    .map((language) => ({
      value: language,
      label: `${languageLabelMap[sourceLanguage] || sourceLanguage}翻${languageLabelMap[language]}`
    }))
  ]
})
const vocabularyCount = computed(() => vocabulary.value.length)
const mobilePreferredLanguageLabel = computed(() =>
  preferredContentLanguage.value === 'ja'
    ? `${getLanguageName('ja')} ${currentInterfaceLanguage.value === 'en' ? 'Audio' : currentInterfaceLanguage.value === 'ja' ? '放送' : '听力'}`
    : preferredContentLanguage.value === 'zh'
      ? `${getLanguageName('zh')} ${currentInterfaceLanguage.value === 'en' ? 'Audio' : currentInterfaceLanguage.value === 'ja' ? '放送' : '广播'}`
      : `${getLanguageName('en')} ${currentInterfaceLanguage.value === 'en' ? 'Audio' : currentInterfaceLanguage.value === 'ja' ? '放送' : '听力'}`
)
const canSyncToCloud = computed(() => currentUser.value?.email === 'leonlovepeace@outlook.com')
const isAdmin = computed(() => currentUser.value?.email === 'leonlovepeace@outlook.com')
const categoryFolders = computed(() =>
  folders.value.filter((folder) => folder.kind === 'category' && folder.contentLanguage === preferredContentLanguage.value)
)
const selectedMobileCategory = computed(() =>
  categoryFolders.value.find((folder) => folder.id === selectedMobileCategoryId.value) || null
)
const selectedMobileChannel = computed(() =>
  folders.value.find((folder) => folder.id === selectedMobileChannelId.value) || null
)
const categoryDirectTasks = computed(() =>
  visibleTasks.value.filter((task) => task.folderId === selectedMobileCategoryId.value)
)
const desktopCategoryDirectTasks = computed(() =>
  visibleTasks.value.filter((task) => task.folderId === selectedDesktopCategoryId.value)
)
const categoryChannels = computed(() =>
  folders.value
    .filter((folder) => folder.kind === 'channel' && folder.parentId === selectedMobileCategoryId.value)
    .map((channel) => {
      const channelTasks = visibleTasks.value.filter((task) => task.folderId === channel.id)
      const latestTask = [...channelTasks].sort((left, right) => new Date(right.updatedAt) - new Date(left.updatedAt))[0] || null
      return {
        ...channel,
        taskCount: channelTasks.length,
        latestTaskTitle: latestTask?.mediaTitle || latestTask?.mediaUrl || '',
        latestUpdatedAt: latestTask?.updatedAt || latestTask?.createdAt || null
      }
    })
    .filter((channel) => channel.taskCount > 0)
)
const mobileChannelCards = computed(() => {
  const cards = [...categoryChannels.value]
  if (categoryDirectTasks.value.length > 0 && selectedMobileCategory.value) {
    const latestTask = [...categoryDirectTasks.value].sort((left, right) => new Date(right.updatedAt) - new Date(left.updatedAt))[0] || null
    cards.unshift({
      id: `direct-${selectedMobileCategory.value.id}`,
      name: selectedMobileCategory.value.name,
      taskCount: categoryDirectTasks.value.length,
      latestTaskTitle: latestTask?.mediaTitle || latestTask?.mediaUrl || '',
      latestUpdatedAt: latestTask?.updatedAt || latestTask?.createdAt || null,
      isVirtual: true
    })
  }
  return cards
})
const mobileEpisodeTasks = computed(() => {
  if (!selectedMobileChannelId.value) {
    return []
  }
  if (selectedMobileChannelId.value.startsWith('direct-')) {
    return [...categoryDirectTasks.value].sort((left, right) => new Date(right.createdAt) - new Date(left.createdAt))
  }
  return visibleTasks.value
    .filter((task) => task.folderId === selectedMobileChannelId.value)
    .sort((left, right) => new Date(right.createdAt) - new Date(left.createdAt))
})
const selectedDesktopCategory = computed(() =>
  categoryFolders.value.find((folder) => folder.id === selectedDesktopCategoryId.value) || null
)
const selectedDesktopChannel = computed(() =>
  folders.value.find((folder) => folder.id === selectedDesktopChannelId.value) || null
)
const selectedDesktopChannelCard = computed(() =>
  desktopChannelCards.value.find((card) => card.id === selectedDesktopChannelId.value) || null
)
const showDesktopChannelPanel = computed(() =>
  desktopLibraryLevel.value === 'channels' && hasOpenedDesktopChannel.value && Boolean(selectedDesktopChannelId.value)
)
const desktopVisibleCategories = computed(() => {
  if (desktopLibraryLevel.value === 'channels' && selectedDesktopCategory.value) {
    return [selectedDesktopCategory.value]
  }
  return categoryFolders.value
})
const desktopCategoryTaskCount = (categoryId) =>
  visibleTasks.value.filter((task) =>
    task.folderId === categoryId || folders.value.some((folder) => folder.id === task.folderId && folder.parentId === categoryId)
  ).length
const desktopCategoryChannels = computed(() =>
  folders.value
    .filter((folder) => folder.kind === 'channel' && folder.parentId === selectedDesktopCategoryId.value)
    .map((channel) => {
      const channelTasks = visibleTasks.value.filter((task) => task.folderId === channel.id)
      const latestTask = [...channelTasks].sort((left, right) => new Date(right.updatedAt) - new Date(left.updatedAt))[0] || null
      return {
        ...channel,
        taskCount: channelTasks.length,
        latestTaskTitle: latestTask?.mediaTitle || latestTask?.mediaUrl || '',
        latestUpdatedAt: latestTask?.updatedAt || latestTask?.createdAt || null
      }
    })
)
const desktopChannelCards = computed(() => {
  const cards = [...desktopCategoryChannels.value]
  if (desktopCategoryDirectTasks.value.length > 0 && selectedDesktopCategory.value) {
    const latestTask = [...desktopCategoryDirectTasks.value].sort((left, right) => new Date(right.updatedAt) - new Date(left.updatedAt))[0] || null
    cards.unshift({
      id: `direct-${selectedDesktopCategory.value.id}`,
      name: '直属内容',
      taskCount: desktopCategoryDirectTasks.value.length,
      latestTaskTitle: latestTask?.mediaTitle || latestTask?.mediaUrl || '',
      latestUpdatedAt: latestTask?.updatedAt || latestTask?.createdAt || null,
      isVirtual: true,
      contentLanguage: selectedDesktopCategory.value.contentLanguage
    })
  }
  return cards
})
const desktopChannelOptions = computed(() =>
  folders.value.filter((folder) => folder.kind === 'channel' && folder.parentId === desktopTaskCategoryId.value)
)
const desktopRealChannelCount = computed(() => desktopCategoryChannels.value.length)
const desktopSelectedCategoryForEdit = computed(() =>
  categoryFolders.value.find((folder) => folder.id === selectedDesktopCategoryRadioId.value)
  || categoryFolders.value.find((folder) => folder.id === selectedDesktopCategoryId.value)
  || null
)
const desktopSelectedChannelForEdit = computed(() =>
  folders.value.find((folder) => folder.id === selectedDesktopChannelRadioId.value)
  || folders.value.find((folder) => folder.id === selectedDesktopChannelId.value)
  || null
)
const desktopEpisodeTasks = computed(() => {
  if (!selectedDesktopChannelId.value) {
    return []
  }
  if (selectedDesktopChannelId.value.startsWith('direct-')) {
    return [...desktopCategoryDirectTasks.value].sort((left, right) => new Date(right.createdAt) - new Date(left.createdAt))
  }
  return visibleTasks.value
    .filter((task) => task.folderId === selectedDesktopChannelId.value)
    .sort((left, right) => new Date(right.createdAt) - new Date(left.createdAt))
})
const parsedBulkLinks = computed(() => parseBulkMediaInput(bulkMediaInput.value))
const autoTargetLanguageOptions = computed(() =>
  sourceLanguageOptions.filter((language) => language.value !== form.value.sourceLanguage)
)
const autoTargetLanguageLabel = computed(() =>
  autoTargetLanguageOptions.value.map((language) => getLanguageName(language.value)).join(' / ')
)

function getLanguageName(languageValue, uiLanguage = currentInterfaceLanguage.value) {
  const matchedLanguage = languageOptions.find((language) => language.value === languageValue)
  return matchedLanguage?.labels?.[uiLanguage] || matchedLanguage?.labels?.zh || languageValue
}

function t(key) {
  return uiMessages[currentInterfaceLanguage.value]?.[key] || uiMessages.zh[key] || key
}

function formatMobileTaskListStatus(task) {
  if (!task) {
    return ''
  }
  const s = String(task.status ?? '').trim()
  if (!s) {
    return ''
  }
  const std = s.match(/^(QUEUED|PROCESSING|COMPLETED|FAILED)\b/i)
  if (std) {
    return std[1].toUpperCase()
  }
  let t = s.split(/[·•∙⋅・]/u)[0].trim()
  t = t.replace(/\s+\d{1,3}\s*[%％]\s*$/u, '').replace(/\d{1,3}\s*[%％]$/u, '').trim()
  if (/%/.test(t)) {
    t = t.replace(/\s*[·•]?\s*\d{1,3}\s*[%％]\s*$/u, '').trim()
  }
  return t
}

function setInterfaceLanguage(nextLanguage) {
  const normalized = normalizeLanguage(nextLanguage)
  interfaceLanguage.value = normalized
  try {
    localStorage.setItem(INTERFACE_LANGUAGE_KEY, normalized)
  } catch {
    // Ignore local storage failures.
  }
}

function detectStandaloneMode() {
  if (typeof window === 'undefined') {
    return false
  }
  return window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true
}

function refreshInstallPromptState() {
  isStandalonePwa.value = detectStandaloneMode()
  try {
    dismissedInstallPrompt.value = localStorage.getItem(PWA_INSTALL_DISMISSED_KEY) === '1'
  } catch {
    dismissedInstallPrompt.value = false
  }
}

function dismissInstallPrompt() {
  dismissedInstallPrompt.value = true
  try {
    localStorage.setItem(PWA_INSTALL_DISMISSED_KEY, '1')
  } catch {
    // Ignore local storage failures.
  }
}

async function promptInstall() {
  if (!deferredInstallPrompt.value) {
    return
  }

  const installPrompt = deferredInstallPrompt.value
  deferredInstallPrompt.value = null

  try {
    await installPrompt.prompt()
    await installPrompt.userChoice
  } catch {
    // Ignore cancelled prompt.
  }
}

function handleBeforeInstallPrompt(event) {
  event.preventDefault()
  deferredInstallPrompt.value = event
  refreshInstallPromptState()
}

function handleAppInstalled() {
  deferredInstallPrompt.value = null
  isStandalonePwa.value = true
  try {
    localStorage.removeItem(PWA_INSTALL_DISMISSED_KEY)
  } catch {
    // Ignore local storage failures.
  }
}

function updateViewportWidth() {
  viewportWidth.value = window.innerWidth
}

function buildAutoTargetLanguages(sourceLanguage) {
  return sourceLanguageOptions
    .filter((language) => language.value !== sourceLanguage)
    .map((language) => language.value)
    .join(',')
}

function readFileAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(new Error('读取图片失败'))
    reader.readAsDataURL(file)
  })
}

function loadImageFromDataUrl(dataUrl) {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error('读取图片失败'))
    image.src = dataUrl
  })
}

async function normalizeAvatarDataUrl(dataUrl) {
  return normalizeImageDataUrl(dataUrl, { maxSide: 512, quality: 0.82 })
}

async function normalizeCoverDataUrl(dataUrl) {
  return normalizeImageDataUrl(dataUrl, { maxSide: 1280, quality: 0.78 })
}

async function normalizeImageDataUrl(dataUrl, { maxSide = 1024, quality = 0.8 } = {}) {
  if (!dataUrl) {
    return ''
  }

  try {
    const image = await loadImageFromDataUrl(dataUrl)
    const scale = Math.min(1, maxSide / Math.max(image.width || 1, image.height || 1))
    const width = Math.max(1, Math.round((image.width || 1) * scale))
    const height = Math.max(1, Math.round((image.height || 1) * scale))
    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const context = canvas.getContext('2d')
    if (!context) {
      return dataUrl
    }
    context.drawImage(image, 0, 0, width, height)
    return canvas.toDataURL('image/jpeg', quality)
  } catch {
    return dataUrl
  }
}

async function persistAvatarDataUrl(dataUrl) {
  if (!currentUser.value) {
    return
  }

  isUpdatingProfile.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const normalizedAvatar = await normalizeAvatarDataUrl(String(dataUrl || ''))
    currentUser.value = await updateProfile({ avatarDataUrl: normalizedAvatar })
    await saveCachedProfile(currentUser.value)
    successMessage.value = '头像已更新'
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isUpdatingProfile.value = false
  }
}

function buildFolderCardStyle(folder) {
  if (!folder?.coverImageDataUrl) {
    return {}
  }
  const opacity = Math.max(0, Math.min(100, Number(folder.coverOpacity ?? 50))) / 100
  return {
    backgroundImage: `linear-gradient(rgba(20, 50, 61, ${opacity}), rgba(20, 50, 61, ${opacity})), url(${folder.coverImageDataUrl})`,
    backgroundSize: 'cover',
    backgroundPosition: 'center'
  }
}

function buildNextDefaultFolderName(baseName, parentId = '') {
  const siblingNames = folders.value
    .filter((folder) => (folder.parentId || '') === (parentId || ''))
    .map((folder) => folder.name.toLowerCase())

  if (!siblingNames.includes(baseName.toLowerCase())) {
    return baseName
  }

  let index = 2
  while (siblingNames.includes(`${baseName} ${index}`.toLowerCase())) {
    index += 1
  }
  return `${baseName} ${index}`
}

function parseHashRoute() {
  const hash = window.location.hash.replace(/^#/, '')
  const match = hash.match(/^\/task\/([^/]+)$/)
  if (match) {
    return {
      view: 'detail',
      taskId: decodeURIComponent(match[1])
    }
  }
  return {
    view: 'dashboard',
    taskId: ''
  }
}

function syncViewFromHash() {
  const route = parseHashRoute()
  currentView.value = route.view
  if (route.taskId) {
    selectedTaskId.value = route.taskId
    const task = tasks.value.find((item) => item.id === route.taskId)
    if (task) {
      selectedFolderId.value = task.folderId
    }
  }
}

function openTaskDetail(taskId) {
  selectedTaskId.value = taskId
  currentView.value = 'detail'
  mobileTab.value = 'podcast'
  window.location.hash = `/task/${encodeURIComponent(taskId)}`
}

function openMobileCategory(categoryId) {
  selectedMobileCategoryId.value = categoryId
  selectedMobileChannelId.value = ''
  mobilePodcastLevel.value = 'channels'
}

function openMobileChannel(channelId) {
  selectedMobileChannelId.value = channelId
  mobilePodcastLevel.value = 'episodes'
}

function backMobilePodcastLevel() {
  if (mobilePodcastLevel.value === 'episodes') {
    selectedMobileChannelId.value = ''
    mobilePodcastLevel.value = 'channels'
    return
  }
  selectedMobileCategoryId.value = ''
  mobilePodcastLevel.value = 'categories'
}

function openDesktopCategory(categoryId) {
  selectedDesktopCategoryId.value = categoryId
  selectedDesktopCategoryRadioId.value = categoryId
  selectedDesktopChannelRadioId.value = ''
  hasOpenedDesktopChannel.value = false
  desktopTaskCategoryId.value = categoryId
  const directTaskCount = visibleTasks.value.filter((task) => task.folderId === categoryId).length
  const cards = [
    ...(directTaskCount > 0 ? [`direct-${categoryId}`] : []),
    ...folders.value
      .filter((folder) => folder.kind === 'channel' && folder.parentId === categoryId)
      .map((channel) => channel.id)
  ]
  selectedDesktopChannelId.value = cards[0] || ''
  desktopTaskChannelId.value = cards[0]?.startsWith('direct-') ? '' : cards[0] || ''
}

function openDesktopChannel(channelId) {
  if (channelId.startsWith('direct-')) {
    const categoryId = channelId.replace(/^direct-/, '')
    selectedDesktopCategoryId.value = categoryId
    selectedDesktopCategoryRadioId.value = categoryId
    hasOpenedDesktopChannel.value = true
    selectedDesktopChannelId.value = channelId
    selectedDesktopChannelRadioId.value = channelId
    desktopTaskChannelId.value = ''
    selectedFolderId.value = categoryId
    return
  }
  const targetChannel = folders.value.find((folder) => folder.id === channelId)
  if (targetChannel?.parentId) {
    selectedDesktopCategoryId.value = targetChannel.parentId
    selectedDesktopCategoryRadioId.value = targetChannel.parentId
  }
  hasOpenedDesktopChannel.value = true
  selectedDesktopChannelId.value = channelId
  selectedDesktopChannelRadioId.value = channelId
  desktopTaskChannelId.value = channelId
  selectedFolderId.value = channelId
}

function openSelectedDesktopFolder() {
  if (selectedDesktopChannelRadioId.value) {
    desktopLibraryLevel.value = 'channels'
    openDesktopChannel(selectedDesktopChannelRadioId.value)
    return
  }
  if (selectedDesktopCategoryRadioId.value) {
    desktopLibraryLevel.value = 'channels'
    openDesktopCategory(selectedDesktopCategoryRadioId.value)
  }
}

function backDesktopFolderLevel() {
  desktopLibraryLevel.value = 'categories'
  hasOpenedDesktopChannel.value = false
  selectedDesktopChannelRadioId.value = ''
}

function backToDashboard() {
  currentView.value = 'dashboard'
  window.location.hash = '/'
}

async function loadLibrary(options = {}) {
  if (!currentUser.value) {
    return
  }

  const { preferredFolderId = '', preferredTaskId = '' } = options
  isLoading.value = true
  errorMessage.value = ''
  try {
    const [folderData, taskData] = await Promise.all([fetchFolders(), fetchTasks()])
    folders.value = folderData
    tasks.value = taskData
    await saveCachedLibrarySnapshot({
      folders: folderData,
      tasks: taskData,
      cachedAt: Date.now()
    })

    const availableFolders = isMobileLayout.value ? visibleFolders.value : folders.value
    const resolvedFolderId = availableFolders.some((folder) => folder.id === preferredFolderId)
      ? preferredFolderId
      : selectedFolderId.value

    if (availableFolders.some((folder) => folder.id === resolvedFolderId)) {
      selectedFolderId.value = resolvedFolderId
    } else if (availableFolders.length > 0) {
      selectedFolderId.value = availableFolders[0].id
    }

    const currentVisibleTasks = visibleTasks.value.filter((task) => task.folderId === selectedFolderId.value)
    if (preferredTaskId && currentVisibleTasks.some((task) => task.id === preferredTaskId)) {
      selectedTaskId.value = preferredTaskId
    } else if (
      selectedTaskId.value &&
      visibleTasks.value.some((task) => task.id === selectedTaskId.value)
    ) {
      const selectedTaskEntry = visibleTasks.value.find((task) => task.id === selectedTaskId.value)
      selectedFolderId.value = selectedTaskEntry?.folderId || selectedFolderId.value
    } else if (!currentVisibleTasks.some((task) => task.id === selectedTaskId.value)) {
      selectedTaskId.value = currentVisibleTasks[0]?.id || ''
    }

    syncViewFromHash()

    const preferredCategory = categoryFolders.value.find((folder) => folder.id === selectedDesktopCategoryId.value)
      || categoryFolders.value[0]
      || null
    selectedDesktopCategoryId.value = preferredCategory?.id || ''

    const preferredChannels = folders.value.filter(
      (folder) => folder.kind === 'channel' && folder.parentId === selectedDesktopCategoryId.value
    )
    if (!preferredChannels.some((folder) => folder.id === selectedDesktopChannelId.value)) {
      selectedDesktopChannelId.value = preferredChannels[0]?.id || ''
    }

    desktopTaskCategoryId.value = selectedDesktopCategoryId.value
    if (!preferredChannels.some((folder) => folder.id === desktopTaskChannelId.value)) {
      desktopTaskChannelId.value = preferredChannels[0]?.id || ''
    }
  } catch (error) {
    const cachedSnapshot = await loadCachedLibrarySnapshot()
    if (cachedSnapshot?.folders?.length || cachedSnapshot?.tasks?.length) {
      folders.value = cachedSnapshot.folders || []
      tasks.value = cachedSnapshot.tasks || []
      errorMessage.value = '当前使用本地缓存内容，网络恢复后会自动刷新'
      syncViewFromHash()
    } else {
      errorMessage.value = error.message
    }
  } finally {
    isLoading.value = false
  }
}

async function loadVocabularyList() {
  if (!currentUser.value) {
    vocabulary.value = []
    return
  }

  isLoadingVocabulary.value = true
  try {
    vocabulary.value = await fetchVocabulary()
    await saveCachedVocabulary(currentUser.value.id, vocabulary.value)
  } catch (error) {
    const cachedVocabulary = await loadCachedVocabulary(currentUser.value.id)
    if (cachedVocabulary.length > 0) {
      vocabulary.value = cachedVocabulary
      errorMessage.value = '当前使用本地缓存生词本，网络恢复后会自动刷新'
    } else {
      errorMessage.value = error.message
    }
  } finally {
    isLoadingVocabulary.value = false
  }
}

async function bootstrapAuthenticatedUser() {
  await hydrateAuthToken()
  if (!getAuthToken()) {
    authReady.value = true
    return
  }

  try {
    await cleanupExpiredAudioCache()
    const cachedProfile = await loadCachedProfile()
    if (cachedProfile) {
      currentUser.value = cachedProfile
    }

    const cachedSnapshot = await loadCachedLibrarySnapshot()
    if (cachedSnapshot?.folders?.length || cachedSnapshot?.tasks?.length) {
      folders.value = cachedSnapshot.folders || []
      tasks.value = cachedSnapshot.tasks || []
      syncViewFromHash()
    }

    if (cachedProfile?.id) {
      const cachedVocabulary = await loadCachedVocabulary(cachedProfile.id)
      if (cachedVocabulary.length > 0) {
        vocabulary.value = cachedVocabulary
      }
    }

    if (currentUser.value) {
      authReady.value = true
    }

    const me = await fetchCurrentUser()
    currentUser.value = me
    await saveCachedProfile(me)
    authReady.value = true
    await Promise.all([
      loadLibrary({ preferredTaskId: parseHashRoute().taskId }),
      loadVocabularyList()
    ])
  } catch {
    clearAuthToken()
    currentUser.value = null
  } finally {
    authReady.value = true
  }
}

async function submitAuth() {
  isSubmittingAuth.value = true
  authErrorMessage.value = ''

  try {
    const request = authMode.value === 'login' ? login : register
    const result = await request(authForm.value)
    setAuthToken(result.token)
    currentUser.value = result.user
    await saveCachedProfile(result.user)
    authForm.value.password = ''
    authReady.value = true
    Promise.allSettled([
      loadLibrary({ preferredTaskId: parseHashRoute().taskId }),
      loadVocabularyList()
    ])
  } catch (error) {
    authErrorMessage.value = error.message
  } finally {
    isSubmittingAuth.value = false
    authReady.value = true
  }
}

async function handleLogout() {
  if (isLoggingOut.value) {
    return
  }

  isLoggingOut.value = true
  const cachedUserId = currentUser.value?.id || ''
  const logoutToken = getAuthToken()

  clearAuthToken()
  await clearCachedProfile()
  await clearCachedLibrarySnapshot()
  await clearCachedVocabulary(cachedUserId)
  currentUser.value = null
  vocabulary.value = []
  tasks.value = []
  folders.value = []
  selectedTaskId.value = ''
  selectedFolderId.value = 'inbox'
  currentView.value = 'dashboard'
  mobileTab.value = 'podcast'
  authMode.value = 'login'
  window.location.hash = '/'

  try {
    await logout({ token: logoutToken })
  } catch {
    // Ignore logout API failures and keep the local logout immediate.
  } finally {
    isLoggingOut.value = false
  }
}

async function handleProfileLanguageChange(event) {
  const nextLanguage = event.target.value
  if (!currentUser.value || nextLanguage === preferredContentLanguage.value) {
    return
  }

  isUpdatingProfile.value = true
  errorMessage.value = ''
  try {
    currentUser.value = await updateProfile({ preferredContentLanguage: nextLanguage })
    await saveCachedProfile(currentUser.value)
    await loadLibrary({ preferredFolderId: selectedFolderId.value, preferredTaskId: selectedTaskId.value })
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isUpdatingProfile.value = false
  }
}

function handleInterfaceLanguageChange(eventOrLanguage) {
  const nextLanguage = typeof eventOrLanguage === 'string'
    ? eventOrLanguage
    : eventOrLanguage?.target?.value
  setInterfaceLanguage(nextLanguage)
}

async function handleAvatarSelected(event) {
  const [file] = event.target.files || []
  if (!file || !currentUser.value) {
    return
  }

  try {
    const dataUrl = await readFileAsDataUrl(file)
    await persistAvatarDataUrl(dataUrl)
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    event.target.value = ''
  }
}

async function pickAvatarFromDevice() {
  if (!currentUser.value || !Capacitor.isNativePlatform()) {
    return
  }

  try {
    const photo = await Camera.getPhoto({
      source: CameraSource.Photos,
      resultType: CameraResultType.DataUrl,
      quality: 80,
      width: 512,
      height: 512,
      promptLabelHeader: '选择头像',
      promptLabelPhoto: '从相册选择',
      promptLabelPicture: '拍照',
      promptLabelCancel: '取消'
    })

    if (!photo?.dataUrl) {
      return
    }

    await persistAvatarDataUrl(photo.dataUrl)
  } catch (error) {
    if (String(error?.message || '').toLowerCase().includes('cancel')) {
      return
    }
    errorMessage.value = '选择头像失败'
  }
}

async function handleAdminChannelCoverSelected(event) {
  const [file] = event.target.files || []
  if (!file) {
    return
  }
  try {
    const dataUrl = await readFileAsDataUrl(file)
    adminChannelForm.value.coverImageDataUrl = await normalizeCoverDataUrl(dataUrl)
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function handleAdminCategoryCoverSelected(event) {
  const [file] = event.target.files || []
  if (!file) {
    return
  }
  try {
    const dataUrl = await readFileAsDataUrl(file)
    adminCategoryForm.value.coverImageDataUrl = await normalizeCoverDataUrl(dataUrl)
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function handleFolderEditorCoverSelected(event) {
  const [file] = event.target.files || []
  if (!file) {
    return
  }
  try {
    const dataUrl = await readFileAsDataUrl(file)
    folderEditorForm.value.coverImageDataUrl = await normalizeCoverDataUrl(dataUrl)
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function handleBatchFileSelected(event) {
  const [file] = event.target.files || []
  if (!file) {
    return
  }
  try {
    batchFileName.value = file.name
    bulkMediaInput.value = await readBatchFileAsText(file)
  } catch (error) {
    errorMessage.value = '读取批量链接文件失败'
  }
}

async function submitAdminCategory() {
  isCreatingCategory.value = true
  errorMessage.value = ''
  try {
    const nextName = buildNextDefaultFolderName(adminCategoryForm.value.name.trim() || '未命名分类')
    const createdCategory = await createFolder({
      name: nextName,
      kind: 'category',
      contentLanguage: adminCategoryForm.value.contentLanguage,
      coverImageDataUrl: adminCategoryForm.value.coverImageDataUrl,
      coverOpacity: adminCategoryForm.value.coverOpacity
    })
    adminCategoryForm.value.name = '未命名分类'
    adminCategoryForm.value.coverImageDataUrl = ''
    adminCategoryForm.value.coverOpacity = 50
    await loadLibrary({ preferredFolderId: selectedFolderId.value, preferredTaskId: selectedTaskId.value })
    selectedDesktopCategoryRadioId.value = createdCategory.id
    openDesktopCategory(createdCategory.id)
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isCreatingCategory.value = false
  }
}

async function submitAdminChannel() {
  if (!adminChannelForm.value.parentId) {
    return
  }
  isCreatingChannel.value = true
  errorMessage.value = ''
  try {
    const nextName = buildNextDefaultFolderName(
      adminChannelForm.value.name.trim() || '未命名广播',
      adminChannelForm.value.parentId
    )
    const createdChannel = await createFolder({
      name: nextName,
      kind: 'channel',
      parentId: adminChannelForm.value.parentId,
      coverImageDataUrl: adminChannelForm.value.coverImageDataUrl,
      coverOpacity: adminChannelForm.value.coverOpacity
    })
    adminChannelForm.value.name = '未命名广播'
    adminChannelForm.value.coverImageDataUrl = ''
    adminChannelForm.value.coverOpacity = 50
    await loadLibrary({ preferredFolderId: selectedFolderId.value, preferredTaskId: selectedTaskId.value })
    selectedDesktopChannelRadioId.value = createdChannel.id
    openDesktopChannel(createdChannel.id)
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isCreatingChannel.value = false
  }
}

async function runCreateDesktopTasks(urls, { fromBulk = false } = {}) {
  if (urls.length === 0) {
    return
  }
  isSubmitting.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const folderId = desktopTaskChannelId.value
    const created = []
    for (const mediaUrl of urls) {
      created.push(
        await createTask({
          mediaUrl,
          sourceLanguage: form.value.sourceLanguage,
          targetLanguages: form.value.targetLanguages,
          folderId
        })
      )
    }
    tasks.value = [...created, ...tasks.value]
    selectedDesktopChannelId.value = folderId
    if (created.length === 1) {
      const task = created[0]
      selectedFolderId.value = task.folderId
      selectedTaskId.value = task.id
      currentView.value = 'dashboard'
    }
    successMessage.value = created.length > 1 ? `已批量添加 ${created.length} 条链接` : '已添加任务'
    if (fromBulk) {
      bulkMediaInput.value = ''
      batchFileName.value = ''
    }
    startPolling()
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isSubmitting.value = false
  }
}

/**
 * 主按钮/表单提交：优先使用批量中解析出的链接，否则用单条。
 */
async function submitDesktopTask() {
  if (!desktopTaskChannelId.value) {
    errorMessage.value = '先选择一个二类广播，再添加内容'
    return
  }
  form.value.folderId = desktopTaskChannelId.value
  const resolved = resolveDesktopAddMediaUrls({
    singleLine: form.value.mediaUrl,
    bulkText: bulkMediaInput.value,
    strategy: 'smart'
  })
  if (!resolved.ok) {
    errorMessage.value = resolved.error
    return
  }
  await runCreateDesktopTasks(resolved.urls, { fromBulk: resolved.fromBulk })
}

/**
 * 显式只走批量区（yml/多行）。
 */
async function submitDesktopBatchTasks() {
  if (!desktopTaskChannelId.value) {
    errorMessage.value = '先选择一个二类广播，再批量添加链接'
    return
  }
  form.value.folderId = desktopTaskChannelId.value
  const resolved = resolveDesktopAddMediaUrls({
    singleLine: '',
    bulkText: bulkMediaInput.value,
    strategy: 'batch-only'
  })
  if (!resolved.ok) {
    errorMessage.value = resolved.error
    return
  }
  await runCreateDesktopTasks(resolved.urls, { fromBulk: true })
}

function openFolderEditor(mode) {
  const target = mode === 'category' ? desktopSelectedCategoryForEdit.value : desktopSelectedChannelForEdit.value
  if (!target) {
    errorMessage.value = mode === 'category' ? '先选中一个一类大类' : '先选中一个二类广播'
    return
  }
  folderEditorMode.value = mode
  folderEditorForm.value = {
    id: target.id,
    name: target.name,
    parentId: target.parentId || selectedDesktopCategoryId.value || '',
    coverImageDataUrl: target.coverImageDataUrl || '',
    coverOpacity: Number(target.coverOpacity ?? 50)
  }
  showFolderEditorModal.value = true
}

async function saveFolderEditor() {
  if (!folderEditorForm.value.id || !folderEditorForm.value.name.trim()) {
    return
  }
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const payload = {
      name: folderEditorForm.value.name.trim(),
      coverImageDataUrl: folderEditorForm.value.coverImageDataUrl,
      coverOpacity: Number(folderEditorForm.value.coverOpacity ?? 50)
    }
    if (folderEditorMode.value === 'category') {
      payload.contentLanguage = selectedDesktopCategory.value?.contentLanguage || preferredContentLanguage.value
    } else {
      payload.parentId = folderEditorForm.value.parentId || selectedDesktopCategoryId.value
      payload.contentLanguage = selectedDesktopChannel.value?.contentLanguage || preferredContentLanguage.value
    }
    await updateFolder(folderEditorForm.value.id, payload)
    await loadLibrary({ preferredFolderId: selectedFolderId.value, preferredTaskId: selectedTaskId.value })
    successMessage.value = folderEditorMode.value === 'category' ? '已更新大类' : '已更新广播'
    showFolderEditorModal.value = false
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function removeFolderFromEditor() {
  if (!folderEditorForm.value.id) {
    return
  }
  const confirmed = window.confirm(folderEditorMode.value === 'category' ? '确定删除这个大类吗？下面的广播会一起删除。' : '确定删除这个二类广播吗？')
  if (!confirmed) {
    return
  }
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await removeFolder(folderEditorForm.value.id)
    await loadLibrary({ preferredFolderId: 'inbox' })
    if (folderEditorMode.value === 'category') {
      selectedDesktopCategoryRadioId.value = ''
      selectedDesktopCategoryId.value = ''
    } else {
      selectedDesktopChannelRadioId.value = ''
      selectedDesktopChannelId.value = ''
    }
    showFolderEditorModal.value = false
    successMessage.value = folderEditorMode.value === 'category' ? '已删除大类' : '已删除广播'
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function submitFolder() {
  if (!newFolderName.value.trim()) {
    return
  }
  isCreatingFolder.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const folder = await createFolder({ name: newFolderName.value.trim() })
    folders.value = [...folders.value, folder]
    form.value.folderId = folder.id
    selectedFolderId.value = folder.id
    newFolderName.value = ''
    successMessage.value = `已创建文件夹：${folder.name}`
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isCreatingFolder.value = false
  }
}

async function updateTaskFolder(folderId) {
  if (!selectedTask.value || selectedTask.value.folderId === folderId) {
    return
  }
  isMovingTask.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const updatedTask = await moveTask(selectedTask.value.id, { folderId })
    await loadLibrary({
      preferredFolderId: updatedTask.folderId,
      preferredTaskId: updatedTask.id
    })
    const destinationFolder = folders.value.find((folder) => folder.id === updatedTask.folderId)
    successMessage.value = `已移动到：${destinationFolder?.name || '目标文件夹'}`
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isMovingTask.value = false
  }
}

async function removeSelectedTask() {
  if (!selectedTask.value) {
    return
  }
  const confirmed = window.confirm(`确定删除《${selectedTask.value.mediaTitle || selectedTask.value.mediaUrl}》吗？`)
  if (!confirmed) {
    return
  }

  try {
    const taskId = selectedTask.value.id
    await deleteTask(taskId)
    tasks.value = tasks.value.filter((task) => task.id !== taskId)
    selectedTaskId.value = filteredTasks.value[0]?.id || ''
    successMessage.value = '已删除这条内容'
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function removeSelectedFolder() {
  if (!selectedFolderId.value || selectedFolderId.value === 'inbox') {
    return
  }

  const folder = folders.value.find((item) => item.id === selectedFolderId.value)
  if (!folder) {
    return
  }

  const taskCount = tasks.value.filter((task) => task.folderId === folder.id).length
  const confirmed = window.confirm(
    `确定删除文件夹《${folder.name}》吗？其中 ${taskCount} 条内容会自动移到“未分类”。`
  )
  if (!confirmed) {
    return
  }

  isDeletingFolder.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await removeFolder(folder.id)
    await loadLibrary({ preferredFolderId: 'inbox' })
    successMessage.value = `已删除文件夹：${folder.name}，原内容已移到未分类`
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isDeletingFolder.value = false
  }
}

async function renameSelectedFolder() {
  const folder = folders.value.find((item) => item.id === selectedFolderId.value)
  const nextName = renameFolderName.value.trim()
  if (!folder || !nextName || nextName === folder.name) {
    return
  }

  isRenamingFolder.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const updatedFolder = await updateFolder(folder.id, { name: nextName })
    await loadLibrary({ preferredFolderId: updatedFolder.id, preferredTaskId: selectedTaskId.value })
    renameFolderName.value = updatedFolder.name
    successMessage.value = `已重命名文件夹：${updatedFolder.name}`
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isRenamingFolder.value = false
  }
}

async function pushRuntimeToCloud() {
  isSyncingCloud.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const result = await syncRuntimeToCloud()
    successMessage.value = result.message || '已同步到云端'
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isSyncingCloud.value = false
  }
}

async function refreshSelectedTask() {
  if (!selectedTaskId.value) {
    return
  }

  try {
    const latestTask = await fetchTask(selectedTaskId.value)
    tasks.value = tasks.value.map((task) => (task.id === latestTask.id ? latestTask : task))
    if (latestTask.status === 'COMPLETED' || latestTask.status === 'FAILED') {
      stopPolling()
    }
  } catch (error) {
    errorMessage.value = error.message
    stopPolling()
  }
}

function startPolling() {
  stopPolling()
  pollTimer = window.setInterval(refreshSelectedTask, 3000)
}

function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

function openVocabularyModal() {
  if (isMobileLayout.value) {
    mobileTab.value = 'vocabulary'
    loadVocabularyList()
    return
  }
  showVocabularyModal.value = true
  loadVocabularyList()
}

function openVocabularySettings() {
  showVocabularySettingsModal.value = true
  loadVocabularyList()
}

async function handleRemoveVocabulary(itemId) {
  try {
    await removeVocabulary(itemId)
    vocabulary.value = vocabulary.value.filter((item) => item.id !== itemId)
    if (currentUser.value?.id) {
      await saveCachedVocabulary(currentUser.value.id, vocabulary.value)
    }
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function handleWordSaved(item) {
  if (!item) {
    return
  }
  vocabulary.value = [item, ...vocabulary.value.filter((existing) => existing.id !== item.id)]
  if (currentUser.value?.id) {
    await saveCachedVocabulary(currentUser.value.id, vocabulary.value)
  }
}

watch(filteredTasks, (currentTasks) => {
  if (currentView.value === 'detail') {
    return
  }
  if (!currentTasks.some((task) => task.id === selectedTaskId.value)) {
    selectedTaskId.value = currentTasks[0]?.id || ''
  }
})

watch(visibleFolders, (currentFolders) => {
  if (!currentFolders.some((folder) => folder.id === selectedFolderId.value)) {
    selectedFolderId.value = currentFolders[0]?.id || 'inbox'
  }
})

watch(categoryFolders, (currentCategories) => {
  if (!currentCategories.some((folder) => folder.id === selectedMobileCategoryId.value)) {
    selectedMobileCategoryId.value = ''
    selectedMobileChannelId.value = ''
    mobilePodcastLevel.value = 'categories'
  }
  if (!currentCategories.some((folder) => folder.id === selectedDesktopCategoryId.value)) {
    selectedDesktopCategoryId.value = currentCategories[0]?.id || ''
  }
  if (!currentCategories.some((folder) => folder.id === selectedDesktopCategoryRadioId.value)) {
    selectedDesktopCategoryRadioId.value = selectedDesktopCategoryId.value || currentCategories[0]?.id || ''
  }
  if (!currentCategories.some((folder) => folder.id === desktopTaskCategoryId.value)) {
    desktopTaskCategoryId.value = currentCategories[0]?.id || ''
  }
  if (!adminChannelForm.value.parentId && currentCategories.length > 0) {
    adminChannelForm.value.parentId = currentCategories[0].id
  }
})

watch(preferredContentLanguage, () => {
  selectedMobileCategoryId.value = ''
  selectedMobileChannelId.value = ''
  mobilePodcastLevel.value = 'categories'
  desktopLibraryLevel.value = 'categories'
  hasOpenedDesktopChannel.value = false
  selectedDesktopCategoryId.value = ''
  selectedDesktopChannelId.value = ''
  desktopTaskCategoryId.value = ''
  desktopTaskChannelId.value = ''
  adminCategoryForm.value.contentLanguage = preferredContentLanguage.value
})

watch(
  () => form.value.sourceLanguage,
  (sourceLanguage) => {
    form.value.targetLanguages = buildAutoTargetLanguages(sourceLanguage)
  },
  { immediate: true }
)

watch(
  detailLanguageOptions,
  (options) => {
    if (!options.length) {
      activeLanguage.value = ''
      return
    }
    if (!options.some((option) => option.value === activeLanguage.value)) {
      activeLanguage.value = options[0].value
    }
  },
  { immediate: true }
)

watch(desktopChannelCards, (cards) => {
  if (!cards.some((card) => card.id === selectedDesktopChannelId.value)) {
    selectedDesktopChannelId.value = cards[0]?.id || ''
  }
  if (!cards.some((card) => card.id === selectedDesktopChannelRadioId.value)) {
    selectedDesktopChannelRadioId.value = selectedDesktopChannelId.value || cards[0]?.id || ''
  }
})

watch(selectedDesktopCategory, (category) => {
  adminChannelForm.value.parentId = category?.id || ''
  if (category) {
    desktopTaskCategoryId.value = category.id
  }
})

watch(selectedDesktopChannel, (channel) => {
  if (channel) {
    desktopTaskChannelId.value = channel.id
    selectedFolderId.value = channel.id
  }
})

watch(desktopTaskCategoryId, (categoryId) => {
  const options = folders.value.filter((folder) => folder.kind === 'channel' && folder.parentId === categoryId)
  if (!options.some((folder) => folder.id === desktopTaskChannelId.value)) {
    desktopTaskChannelId.value = options[0]?.id || ''
  }
})

watch(selectedTask, (task) => {
  if (task && (task.status === 'QUEUED' || task.status === 'PROCESSING')) {
    startPolling()
  } else {
    stopPolling()
  }

  taskMoveFolderId.value = task?.folderId || selectedFolderId.value
})

watch(selectedFolderId, (folderId) => {
  form.value.folderId = folderId
  renameFolderName.value = folders.value.find((folder) => folder.id === folderId)?.name || ''
})

onMounted(() => {
  updateViewportWidth()
  refreshInstallPromptState()
  syncViewFromHash()
  window.addEventListener('hashchange', syncViewFromHash)
  window.addEventListener('resize', updateViewportWidth)
  window.addEventListener('beforeinstallprompt', handleBeforeInstallPrompt)
  window.addEventListener('appinstalled', handleAppInstalled)
  bootstrapAuthenticatedUser()
})

onBeforeUnmount(() => {
  stopPolling()
  window.removeEventListener('hashchange', syncViewFromHash)
  window.removeEventListener('resize', updateViewportWidth)
  window.removeEventListener('beforeinstallprompt', handleBeforeInstallPrompt)
  window.removeEventListener('appinstalled', handleAppInstalled)
})
</script>

<template>
  <main
    class="page-shell"
    :class="{
      'detail-shell': currentView === 'detail',
      'mobile-shell': isMobileLayout
    }"
  >
    <section v-if="!authReady || !currentUser" class="hero-card auth-shell">
      <div class="auth-card">
        <p class="eyebrow">{{ t('account') }}</p>
        <h1>{{ authMode === 'login' ? t('loginTitle') : t('registerTitle') }}</h1>
        <p class="hero-text">
          {{ t('loginHint') }}
        </p>

        <form class="task-form auth-form" @submit.prevent="submitAuth">
          <label>
            <span>{{ t('email') }}</span>
            <input v-model.trim="authForm.email" type="email" placeholder="you@example.com" required />
          </label>
          <label>
            <span>{{ t('password') }}</span>
            <input v-model="authForm.password" type="password" :placeholder="t('passwordPlaceholder')" required />
          </label>
          <p v-if="authErrorMessage" class="error-banner">{{ authErrorMessage }}</p>
          <div class="auth-actions">
            <button type="submit" class="primary-button" :disabled="isSubmittingAuth">
              {{ isSubmittingAuth ? t('submitting') : authMode === 'login' ? t('login') : t('registerAndLogin') }}
            </button>
            <button
              type="button"
              class="ghost-button"
              @click="authMode = authMode === 'login' ? 'register' : 'login'"
            >
              {{ authMode === 'login' ? t('goRegister') : t('goLogin') }}
            </button>
          </div>
        </form>
      </div>
    </section>

    <template v-else>
      <section v-if="!isMobileLayout" class="hero-card session-toolbar">
        <div>
          <p class="eyebrow">{{ t('currentAccount') }}</p>
          <strong>{{ currentUser.email }}</strong>
        </div>
        <div v-if="!isMobileLayout" class="session-actions">
          <div class="language-switcher interface-language-switcher">
            <button
              v-for="language in languageOptions"
              :key="language.value"
              type="button"
              class="switch-button"
              :class="{ active: currentInterfaceLanguage === language.value }"
              @click="handleInterfaceLanguageChange(language.value)"
            >
              {{ getLanguageName(language.value) }}
            </button>
          </div>
          <button type="button" class="ghost-button" @click="openVocabularyModal">
            {{ t('vocabulary') }} {{ vocabularyCount ? `(${vocabularyCount})` : '' }}
          </button>
          <button type="button" class="ghost-button" @click="loadLibrary" :disabled="isLoading">
            {{ isLoading ? t('refreshing') : t('refreshContent') }}
          </button>
          <button type="button" class="danger-button session-logout-button" @click="handleLogout">
            {{ t('logout') }}
          </button>
        </div>
      </section>

      <template v-if="currentView === 'dashboard'">
        <section v-if="isMobileLayout && mobileTab === 'me'" class="dashboard-grid mobile-profile-grid">
          <section class="panel-card mobile-profile-card">
            <p class="eyebrow">{{ t('me') }}</p>
            <div class="profile-avatar-row">
              <div class="profile-avatar">
                <img v-if="currentUser.avatarDataUrl" :src="currentUser.avatarDataUrl" alt="avatar" />
                <span v-else>{{ currentUser.email.slice(0, 1).toUpperCase() }}</span>
              </div>
              <div class="profile-avatar-copy">
                <strong>{{ currentUser.email }}</strong>
                <button
                  v-if="isNativeApp"
                  type="button"
                  class="ghost-button avatar-upload-button"
                  :disabled="isUpdatingProfile"
                  @click="pickAvatarFromDevice"
                >
                  {{ isUpdatingProfile ? t('submitting') : t('uploadAvatar') }}
                </button>
                <label v-else class="ghost-button avatar-upload-button">
                  {{ t('uploadAvatar') }}
                  <input type="file" accept="image/*" class="hidden-file-input" @change="handleAvatarSelected" />
                </label>
              </div>
            </div>

            <div class="mobile-setting-card">
              <p class="eyebrow">{{ t('settings') }}</p>
              <label>
                <span>{{ t('interfaceLanguage') }}</span>
                <select :value="currentInterfaceLanguage" @change="handleInterfaceLanguageChange">
                  <option v-for="language in languageOptions" :key="language.value" :value="language.value">
                    {{ getLanguageName(language.value) }}
                  </option>
                </select>
              </label>
              <label>
                <span>{{ t('wantToListen') }}</span>
                <select :value="preferredContentLanguage" @change="handleProfileLanguageChange" :disabled="isUpdatingProfile">
                  <option v-for="language in languageOptions" :key="language.value" :value="language.value">
                    {{ getLanguageName(language.value) }}
                  </option>
                </select>
              </label>
              <p class="hero-text">
                {{ t('currentShowPrefix') }}{{ mobilePreferredLanguageLabel }}{{ t('currentShowSuffix') }}
              </p>
            </div>

            <div class="mobile-setting-card">
              <p class="eyebrow">{{ t('tools') }}</p>
              <div class="auth-actions">
                <button type="button" class="ghost-button" @click="openVocabularySettings">
                  {{ t('vocabularySettings') }}
                </button>
                <button type="button" class="ghost-button" @click="loadLibrary" :disabled="isLoading">
                  {{ isLoading ? t('refreshing') : t('refreshContent') }}
                </button>
              </div>
            </div>

            <div class="mobile-setting-card">
              <p class="eyebrow">{{ t('feedback') }}</p>
              <p class="hero-text">{{ t('feedbackHint') }}</p>
              <strong class="feedback-wechat">-Leonfc-</strong>
            </div>

            <div v-if="isAdmin" class="mobile-setting-card">
              <p class="eyebrow">{{ t('radioManagement') }}</p>
              <form class="task-form" @submit.prevent="submitAdminCategory">
                <label>
                  <span>{{ t('createCategory') }}</span>
                  <input v-model.trim="adminCategoryForm.name" type="text" placeholder="比如：日语听力 / 英文新闻" />
                </label>
                <label>
                  <span>{{ t('categoryLanguage') }}</span>
                  <select v-model="adminCategoryForm.contentLanguage">
                    <option v-for="language in languageOptions" :key="language.value" :value="language.value">
                      {{ getLanguageName(language.value) }}
                    </option>
                  </select>
                </label>
                <button type="submit" class="ghost-button" :disabled="isCreatingCategory">
                  {{ isCreatingCategory ? t('submitting') : t('createCategory') }}
                </button>
              </form>

              <form class="task-form" @submit.prevent="submitAdminChannel">
                <label>
                  <span>{{ t('createChannel') }}</span>
                  <input v-model.trim="adminChannelForm.name" type="text" placeholder="比如：NHK World / BBC Learning English" />
                </label>
                <label>
                  <span>{{ t('parentCategory') }}</span>
                  <select v-model="adminChannelForm.parentId">
                    <option v-for="folder in categoryFolders" :key="folder.id" :value="folder.id">{{ folder.name }}</option>
                  </select>
                </label>
                <button type="submit" class="ghost-button" :disabled="isCreatingChannel || !categoryFolders.length">
                  {{ isCreatingChannel ? t('submitting') : t('createChannel') }}
                </button>
              </form>
            </div>

            <button type="button" class="danger-button session-logout-button" @click="handleLogout">
              {{ t('logout') }}
            </button>
          </section>
        </section>

        <section v-if="isMobileLayout && mobileTab === 'vocabulary'" class="dashboard-grid mobile-profile-grid">
          <section class="panel-card mobile-profile-card">
            <div class="panel-header">
              <div>
                <p class="eyebrow">生词本</p>
                <h2>{{ vocabularyCount ? `${vocabularyCount} 个生词` : '还没有生词' }}</h2>
              </div>
            </div>
            <VocabularyStudyPanel
              :vocabulary="vocabulary"
              :is-loading="isLoadingVocabulary"
              :interface-language="currentInterfaceLanguage"
              :user-id="currentUser?.id || ''"
              mode="study"
              @remove-item="handleRemoveVocabulary"
            />
          </section>
        </section>

        <template v-if="!isMobileLayout || mobileTab === 'podcast'">
        <section v-if="isMobileLayout" class="panel-card mobile-podcast-shell">
          <template v-if="mobilePodcastLevel === 'categories'">
            <div class="panel-header">
              <div>
                <p class="eyebrow">{{ t('categoryPanel') }}</p>
                <h2>{{ mobilePreferredLanguageLabel }}</h2>
              </div>
            </div>

            <div class="mobile-category-grid">
              <button
                v-for="category in categoryFolders"
                :key="category.id"
                type="button"
                class="folder-item mobile-category-card"
                :style="buildFolderCardStyle(category)"
                @click="openMobileCategory(category.id)"
              >
                <span class="folder-item-copy">
                  <strong>{{ category.name }}</strong>
                  <small>{{ visibleTasks.filter((task) => task.folderId === category.id || folders.some((folder) => folder.id === task.folderId && folder.parentId === category.id)).length }} 条音频</small>
                </span>
              </button>
            </div>
          </template>

          <template v-else-if="mobilePodcastLevel === 'channels'">
            <div class="panel-header">
              <div>
                <p class="eyebrow">{{ t('podcastList') }}</p>
                <h2>{{ selectedMobileCategory?.name || mobilePreferredLanguageLabel }}</h2>
              </div>
              <button type="button" class="ghost-button" @click="backMobilePodcastLevel">{{ t('back') }}</button>
            </div>

            <div v-if="mobileChannelCards.length === 0" class="empty-state">{{ t('noChannels') }}</div>
            <div v-else class="task-list">
              <button
                v-for="channel in mobileChannelCards"
                :key="channel.id"
                type="button"
                class="task-item mobile-task-item"
                :style="buildFolderCardStyle(channel)"
                @click="openMobileChannel(channel.id)"
              >
                <div class="task-item-topline">
                  <span class="task-status">{{ channel.taskCount }} 条更新</span>
                  <span class="task-open-hint">{{ t('enterPodcast') }}</span>
                </div>
                <strong>{{ channel.name }}</strong>
                <small>{{ channel.latestTaskTitle || '还没有最近更新' }}</small>
              </button>
            </div>
          </template>

          <template v-else>
            <div class="panel-header">
              <div>
                <p class="eyebrow">{{ t('audioList') }}</p>
                <h2>{{ selectedMobileChannel?.name || selectedMobileCategory?.name || mobilePreferredLanguageLabel }}</h2>
              </div>
              <button type="button" class="ghost-button" @click="backMobilePodcastLevel">{{ t('back') }}</button>
            </div>

            <p v-if="errorMessage" class="error-banner">{{ errorMessage }}</p>
            <p v-if="successMessage" class="success-banner">{{ successMessage }}</p>
            <p v-else-if="mobileEpisodeTasks.length === 0" class="empty-state">{{ t('noAudios') }}</p>

            <div v-else class="task-list">
              <button
                v-for="task in mobileEpisodeTasks"
                :key="task.id"
                type="button"
                class="task-item mobile-task-item"
                @click="openTaskDetail(task.id)"
              >
                <div class="task-item-topline">
                  <span v-if="!isMobileLayout" class="task-status">{{ formatMobileTaskListStatus(task) }}</span>
                  <span class="task-open-hint">{{ t('openAudio') }}</span>
                </div>
                <strong class="task-title-clip">{{ task.mediaTitle || task.mediaUrl }}</strong>
                <small>{{ new Date(task.createdAt).toLocaleString() }}</small>
              </button>
            </div>
          </template>
        </section>

        <template v-if="!isMobileLayout">
        <section v-if="!isMobileLayout" class="hero-card dashboard-hero desktop-admin-hero">
          <div>
            <p class="eyebrow">桌面配置台</p>
            <h1>电脑端只做分类和广播配置</h1>
            <p class="hero-text">
              左边直接用和客户端接近的结构来管理大类和二类，右边只负责批量导入广播内容。
            </p>
          </div>
          <div class="session-actions">
            <div class="language-switcher desktop-language-switcher">
              <button
                type="button"
                class="switch-button"
                :class="{ active: preferredContentLanguage === 'zh' }"
                :disabled="isUpdatingProfile"
                @click="handleProfileLanguageChange({ target: { value: 'zh' } })"
              >
                编辑中文
              </button>
              <button
                type="button"
                class="switch-button"
                :class="{ active: preferredContentLanguage === 'ja' }"
                :disabled="isUpdatingProfile"
                @click="handleProfileLanguageChange({ target: { value: 'ja' } })"
              >
                编辑日文
              </button>
              <button
                type="button"
                class="switch-button"
                :class="{ active: preferredContentLanguage === 'en' }"
                :disabled="isUpdatingProfile"
                @click="handleProfileLanguageChange({ target: { value: 'en' } })"
              >
                编辑英文
              </button>
            </div>
            <button type="button" class="ghost-button" @click="submitAdminCategory" :disabled="isCreatingCategory">
              {{ isCreatingCategory ? '添加中...' : '添加默认分类' }}
            </button>
            <button type="button" class="ghost-button" @click="submitAdminChannel" :disabled="isCreatingChannel || !selectedDesktopCategoryId">
              {{ isCreatingChannel ? '添加中...' : '添加默认二类' }}
            </button>
            <button
              type="button"
              class="ghost-button"
              @click="openSelectedDesktopFolder"
              :disabled="!selectedDesktopCategoryRadioId && !selectedDesktopChannelRadioId"
            >
              打开文件夹
            </button>
            <button type="button" class="ghost-button" @click="openFolderEditor('category')" :disabled="!desktopSelectedCategoryForEdit">
              编辑一类
            </button>
            <button type="button" class="ghost-button" @click="openFolderEditor('channel')" :disabled="!desktopSelectedChannelForEdit">
              编辑二类
            </button>
            <button type="button" class="ghost-button" @click="openVocabularyModal">
              生词本 {{ vocabularyCount ? `(${vocabularyCount})` : '' }}
            </button>
            <button type="button" class="ghost-button" @click="openVocabularySettings">
              {{ t('vocabularySettings') }}
            </button>
            <button v-if="canSyncToCloud" type="button" class="ghost-button" @click="pushRuntimeToCloud" :disabled="isSyncingCloud">
              {{ isSyncingCloud ? '同步中...' : '同步到云端' }}
            </button>
            <button type="button" class="ghost-button" @click="loadLibrary" :disabled="isLoading">
              {{ isLoading ? '刷新中...' : '刷新内容' }}
            </button>
          </div>
        </section>

        <section class="workspace-grid desktop-config-grid">
          <section class="panel-card scroll-panel desktop-library-panel">
            <div class="panel-header">
              <div>
                <p class="eyebrow">广播目录</p>
                <h2>{{ desktopLibraryLevel === 'channels' ? (selectedDesktopCategory?.name || mobilePreferredLanguageLabel) : mobilePreferredLanguageLabel }}</h2>
              </div>
              <div class="panel-header-actions">
                <div class="summary-pill">
                  <strong>{{ desktopLibraryLevel === 'channels' ? desktopRealChannelCount : categoryFolders.length }}</strong>
                  <span>{{ desktopLibraryLevel === 'channels' ? '个二类' : '个目录' }}</span>
                </div>
                <button v-if="desktopLibraryLevel === 'channels'" type="button" class="ghost-button" @click="backDesktopFolderLevel">
                  返回上一级
                </button>
              </div>
            </div>

            <p v-if="errorMessage" class="error-banner">{{ errorMessage }}</p>
            <p v-if="successMessage" class="success-banner">{{ successMessage }}</p>

            <div class="desktop-folder-stack">
              <section class="desktop-folder-group">
                <div class="desktop-group-head">
                  <span>{{ desktopLibraryLevel === 'channels' ? '当前一类里的二类广播' : '一类大类' }}</span>
                  <strong>{{ desktopLibraryLevel === 'channels' ? desktopRealChannelCount : categoryFolders.length }}</strong>
                </div>
                <div class="folder-list desktop-compact-list">
                  <template v-if="desktopLibraryLevel === 'categories'">
                    <button
                      v-for="category in desktopVisibleCategories"
                      :key="category.id"
                      type="button"
                      class="folder-item desktop-folder-item"
                      :class="{ active: category.id === selectedDesktopCategoryId }"
                      :style="buildFolderCardStyle(category)"
                      @click="openDesktopCategory(category.id)"
                    >
                      <span class="folder-item-select" @click.stop="openDesktopCategory(category.id)">
                        <span class="folder-item-radio" :class="{ active: selectedDesktopCategoryRadioId === category.id }"></span>
                      </span>
                      <span class="folder-item-copy">
                        <strong>{{ category.name }}</strong>
                        <small>{{ desktopCategoryTaskCount(category.id) }} 条广播内容</small>
                      </span>
                    </button>
                  </template>

                  <template v-else>
                    <button
                      v-if="selectedDesktopCategory"
                      type="button"
                      class="folder-item desktop-folder-item current-folder-card"
                      :class="{ active: true }"
                      :style="buildFolderCardStyle(selectedDesktopCategory)"
                    >
                      <span class="folder-item-select">
                        <span class="folder-item-radio active"></span>
                      </span>
                      <span class="folder-item-copy">
                        <strong>{{ selectedDesktopCategory.name }}</strong>
                        <small>{{ desktopCategoryTaskCount(selectedDesktopCategory.id) }} 条广播内容</small>
                      </span>
                    </button>

                    <p v-if="desktopChannelCards.length === 0" class="empty-state compact-empty-state">这个一类下面还没有二类广播。</p>
                    <div v-else class="task-list desktop-compact-list nested-channel-list">
                      <button
                        v-for="channel in desktopChannelCards"
                        :key="channel.id"
                        type="button"
                        class="task-item desktop-channel-card"
                        :class="{ active: channel.id === selectedDesktopChannelId }"
                        :style="buildFolderCardStyle(channel)"
                        @click="openDesktopChannel(channel.id)"
                      >
                        <span class="folder-item-select" @click.stop="openDesktopChannel(channel.id)">
                          <span class="folder-item-radio" :class="{ active: selectedDesktopChannelRadioId === channel.id }"></span>
                        </span>
                        <div class="desktop-channel-copy">
                          <div class="task-item-topline">
                            <span class="task-status">
                              {{ channel.isVirtual ? '未归类内容' : `${channel.taskCount} 条内容` }}
                            </span>
                          </div>
                          <strong>{{ channel.name }}</strong>
                          <small>{{ channel.latestTaskTitle || '还没有广播内容' }}</small>
                        </div>
                      </button>
                    </div>
                  </template>
                </div>
              </section>
            </div>
          </section>

          <section class="panel-card scroll-panel">
            <div class="panel-header">
              <div>
                <p class="eyebrow">{{ showDesktopChannelPanel ? '广播内容' : '添加广播内容' }}</p>
                <h2>{{ selectedDesktopChannelCard?.name || '先选二类，再在这里添加录音' }}</h2>
              </div>
              <div class="summary-pill">
                <strong>{{ showDesktopChannelPanel ? desktopEpisodeTasks.length : desktopChannelOptions.length }}</strong>
                <span>{{ showDesktopChannelPanel ? '条内容' : '个可选二类' }}</span>
              </div>
            </div>

            <form class="task-form desktop-task-form" @submit.prevent="submitDesktopTask">
              <label class="form-span-2">
                <span>媒体链接</span>
                <textarea
                  v-model.trim="form.mediaUrl"
                  rows="3"
                  placeholder="只添加一条时填这里。若下面「批量」里已有内容，会优先用批量，不必填这格。"
                ></textarea>
              </label>
              <label class="form-span-2">
                <span>批量链接</span>
                <textarea
                  v-model="bulkMediaInput"
                  rows="7"
                  placeholder="多行、每行一个 https:// 地址；# 可注释。支持 yml。主按钮会优先根据这里解析结果批量添加。"
                ></textarea>
              </label>
              <label class="cover-upload-field form-span-2">
                <span>批量文件导入</span>
                <input type="file" accept=".yml,.yaml,.txt" @change="handleBatchFileSelected" />
                <small v-if="batchFileName">{{ batchFileName }}</small>
              </label>
              <label>
                <span>源语言</span>
                <select v-model="form.sourceLanguage" required>
                  <option v-for="language in sourceLanguageOptions" :key="language.value" :value="language.value">
                    {{ getLanguageName(language.value) }}
                  </option>
                </select>
              </label>
              <label class="auto-translation-field">
                <span>自动翻译</span>
                <div class="auto-translation-value">{{ autoTargetLanguageLabel || '处理中会自动翻译成另外两种语言' }}</div>
                <small>处理时会自动生成另外两种语言，播放器里可以直接切换。</small>
              </label>
              <label>
                <span>放到哪个大类</span>
                <select v-model="desktopTaskCategoryId">
                  <option v-for="folder in categoryFolders" :key="folder.id" :value="folder.id">{{ folder.name }}</option>
                </select>
              </label>
              <label>
                <span>放到哪个二类下面</span>
                <select v-model="desktopTaskChannelId" required>
                  <option v-for="folder in desktopChannelOptions" :key="folder.id" :value="folder.id">{{ folder.name }}</option>
                </select>
              </label>
              <div class="hero-actions form-span-2">
                <button type="submit" class="primary-button" :disabled="isSubmitting || !desktopTaskChannelId">
                  {{ isSubmitting ? '处理中...' : '添加（优先批量，否则单条）' }}
                </button>
                <button
                  type="button"
                  class="ghost-button"
                  :disabled="isSubmitting || !desktopTaskChannelId || parsedBulkLinks.length === 0"
                  @click="submitDesktopBatchTasks"
                >
                  {{ isSubmitting ? '处理中...' : `只批量：${parsedBulkLinks.length} 条` }}
                </button>
              </div>
            </form>

            <p v-if="!showDesktopChannelPanel" class="empty-state">右边保留添加入口。只有打开某个广播后，下面才会显示这个广播里的音频内容。</p>
            <p v-else-if="desktopEpisodeTasks.length === 0" class="empty-state">这个广播下面还没有内容，直接在上面添加就行。</p>

            <div v-else class="task-list">
              <button
                v-for="task in desktopEpisodeTasks"
                :key="task.id"
                type="button"
                class="task-item"
                @click="openTaskDetail(task.id)"
              >
                <div class="task-item-topline">
                  <span class="task-status">
                    <template v-if="showDesktopEpisodeProgressPct">{{ task.status }} · {{ task.progress }}%</template>
                    <template v-else>{{ task.status }}</template>
                  </span>
                  <span class="task-open-hint">打开音频</span>
                </div>
                <strong>{{ task.mediaTitle || task.mediaUrl }}</strong>
                <small>{{ new Date(task.createdAt).toLocaleString() }}</small>
              </button>
            </div>
          </section>
        </section>
        </template>
        </template>
      </template>

      <template v-else>
        <section class="detail-page">
          <section class="hero-card detail-header-card">
            <div v-if="!isMobileLayout" class="detail-topbar">
              <button type="button" class="ghost-button" @click="backToDashboard">{{ t('back') }}</button>
              <div class="language-switcher">
                <button
                  v-for="language in detailLanguageOptions"
                  :key="language.value"
                  type="button"
                  class="switch-button"
                  :class="{ active: language.value === activeLanguage }"
                  @click="activeLanguage = language.value"
                >
                  {{ language.label }}
                </button>
              </div>
            </div>

            <div v-else class="mobile-detail-topbar">
              <button type="button" class="mobile-back-button" @click="backToDashboard">{{ t('back') }}</button>
              <div v-if="detailLanguageOptions.length" class="language-switcher mobile-detail-language-switcher">
                <button
                  v-for="language in detailLanguageOptions"
                  :key="language.value"
                  type="button"
                  class="switch-button"
                  :class="{ active: language.value === activeLanguage }"
                  @click="activeLanguage = language.value"
                >
                  {{ language.label }}
                </button>
              </div>
            </div>

            <div class="detail-header-content">
              <div class="detail-heading-block">
                <h1>{{ selectedTask?.mediaTitle || '加载中...' }}</h1>
              </div>
              <button
                v-if="!isMobileLayout && selectedTask"
                type="button"
                class="danger-button detail-delete-button"
                @click="removeSelectedTask"
              >
                删除这条音频
              </button>
            </div>
          </section>

          <p
            v-if="!isMobileLayout && (selectedTask?.status === 'QUEUED' || selectedTask?.status === 'PROCESSING')"
            class="empty-state detail-note"
          >
            任务正在后台处理真实链接。本地 Whisper 转写长视频会比较慢，页面会自动轮询刷新结果。
          </p>
          <p v-else-if="!selectedTask" class="empty-state detail-note">没有找到这条音频，可能已经被删除。</p>

          <SubtitlePlayer
            v-if="selectedTask && selectedTask.status === 'COMPLETED' && selectedTask.segments.length > 0"
            :task="selectedTask"
            :active-language="activeLanguage"
            :is-mobile="isMobileLayout"
            :playlist="detailPlaylist"
            :on-select-task="openTaskDetail"
            :user="currentUser"
            :interface-language="currentInterfaceLanguage"
            @word-saved="handleWordSaved"
          />
        </section>
      </template>
    </template>

    <div v-if="showFolderEditorModal" class="overlay-shell" @click.self="showFolderEditorModal = false">
      <section class="overlay-card word-dialog folder-editor-modal">
        <div class="panel-header">
          <div>
            <p class="eyebrow">{{ folderEditorMode === 'category' ? '编辑一类大类' : '编辑二类广播' }}</p>
            <h2>{{ folderEditorForm.name || '未命名' }}</h2>
          </div>
          <button type="button" class="ghost-button" @click="showFolderEditorModal = false">关闭</button>
        </div>

        <form class="task-form" @submit.prevent="saveFolderEditor">
          <label>
            <span>名称</span>
            <input v-model.trim="folderEditorForm.name" type="text" placeholder="修改名称" />
          </label>
          <label v-if="folderEditorMode === 'channel'">
            <span>归属一类大类</span>
            <select v-model="folderEditorForm.parentId">
              <option v-for="folder in categoryFolders" :key="folder.id" :value="folder.id">{{ folder.name }}</option>
            </select>
          </label>
          <label class="cover-upload-field">
            <span>封面图片</span>
            <input type="file" accept="image/*" @change="handleFolderEditorCoverSelected" />
          </label>
          <label>
            <span>封面透明度 {{ folderEditorForm.coverOpacity }}%</span>
            <input v-model="folderEditorForm.coverOpacity" type="range" min="0" max="100" step="5" />
          </label>
          <div v-if="folderEditorForm.coverImageDataUrl" class="desktop-cover-preview">
            <img :src="folderEditorForm.coverImageDataUrl" :style="{ opacity: 1 - Number(folderEditorForm.coverOpacity || 50) / 100 }" alt="folder cover preview" />
          </div>
          <div class="inline-button-row">
            <button type="submit" class="ghost-button">保存</button>
            <button type="button" class="danger-button inline-danger-button" @click="removeFolderFromEditor">删除</button>
          </div>
        </form>
      </section>
    </div>

    <div v-if="showVocabularyModal" class="overlay-shell" @click.self="showVocabularyModal = false">
      <section class="overlay-card vocabulary-modal">
        <div class="panel-header">
          <div>
            <p class="eyebrow">{{ t('vocabulary') }}</p>
            <h2>{{ currentUser?.email }}</h2>
          </div>
          <button type="button" class="ghost-button" @click="showVocabularyModal = false">{{ t('close') }}</button>
        </div>
        <VocabularyStudyPanel
          :vocabulary="vocabulary"
          :is-loading="isLoadingVocabulary"
          :interface-language="currentInterfaceLanguage"
          :user-id="currentUser?.id || ''"
          mode="study"
          @remove-item="handleRemoveVocabulary"
        />
      </section>
    </div>

    <div v-if="showVocabularySettingsModal" class="overlay-shell" @click.self="showVocabularySettingsModal = false">
      <section class="overlay-card vocabulary-modal">
        <div class="panel-header">
          <div>
            <p class="eyebrow">{{ t('settings') }}</p>
            <h2>{{ t('vocabularySettings') }}</h2>
          </div>
          <button type="button" class="ghost-button" @click="showVocabularySettingsModal = false">{{ t('close') }}</button>
        </div>
        <VocabularyStudyPanel
          :vocabulary="vocabulary"
          :is-loading="isLoadingVocabulary"
          :interface-language="currentInterfaceLanguage"
          :user-id="currentUser?.id || ''"
          mode="settings"
          @remove-item="handleRemoveVocabulary"
        />
      </section>
    </div>

    <section v-if="canShowInstallPrompt" class="pwa-install-banner">
      <div class="pwa-install-copy">
        <strong>{{ t('installTitle') }}</strong>
        <p>
          {{ deferredInstallPrompt ? t('installDescription') : t('installIosHint') }}
        </p>
      </div>
      <div class="pwa-install-actions">
        <button
          v-if="deferredInstallPrompt"
          type="button"
          class="primary-button pwa-install-button"
          @click="promptInstall"
        >
          {{ t('installButton') }}
        </button>
        <button type="button" class="ghost-button pwa-install-dismiss" @click="dismissInstallPrompt">
          {{ t('installLater') }}
        </button>
      </div>
    </section>

    <nav v-if="isMobileLayout && authReady && currentUser && currentView === 'dashboard'" class="mobile-bottom-tabbar">
      <button
        type="button"
        class="mobile-bottom-tab"
        :class="{ active: mobileTab === 'podcast' }"
        @click="mobileTab = 'podcast'"
      >
        {{ t('podcastTab') }}
      </button>
      <button
        type="button"
        class="mobile-bottom-tab"
        :class="{ active: mobileTab === 'vocabulary' }"
        @click="mobileTab = 'vocabulary'"
      >
        {{ t('vocabularyTab') }}
      </button>
      <button
        type="button"
        class="mobile-bottom-tab"
        :class="{ active: mobileTab === 'me' }"
        @click="mobileTab = 'me'"
      >
        {{ t('myTab') }}
      </button>
    </nav>
  </main>
</template>
