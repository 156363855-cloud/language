<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import SubtitlePlayer from './components/SubtitlePlayer.vue'
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

const languageOptions = [
  { label: '中文', value: 'zh' },
  { label: '日文', value: 'ja' },
  { label: '英文', value: 'en' }
]

const sourceLanguageOptions = [
  { label: '中文', value: 'zh' },
  { label: '日文', value: 'ja' },
  { label: '英文', value: 'en' }
]

const targetLanguageOptions = [
  { label: '中文', value: 'zh' },
  { label: '日文', value: 'ja' },
  { label: '英文', value: 'en' }
]

const form = ref({
  mediaUrl: '',
  sourceLanguage: 'en',
  targetLanguages: 'ja',
  folderId: 'inbox'
})
const authForm = ref({
  email: '',
  password: ''
})
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
const desktopTaskCategoryId = ref('')
const desktopTaskChannelId = ref('')
const isUpdatingProfile = ref(false)
const isCreatingCategory = ref(false)
const isCreatingChannel = ref(false)
const newFolderName = ref('')
const renameFolderName = ref('')
const adminCategoryForm = ref({
  name: '',
  contentLanguage: 'ja'
})
const adminChannelForm = ref({
  name: '',
  parentId: '',
  coverImageDataUrl: ''
})
const categoryEditorName = ref('')
const channelEditorName = ref('')
const channelEditorCoverImageDataUrl = ref('')
const isSubmitting = ref(false)
const isCreatingFolder = ref(false)
const isLoading = ref(false)
const isMovingTask = ref(false)
const isDeletingFolder = ref(false)
const isRenamingFolder = ref(false)
const isSyncingCloud = ref(false)
const isLoadingVocabulary = ref(false)
const vocabulary = ref([])
const showVocabularyModal = ref(false)
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
let pollTimer = null

const selectedTask = computed(() => tasks.value.find((task) => task.id === selectedTaskId.value) || null)
const isMobileLayout = computed(() => viewportWidth.value <= 820)
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
const vocabularyCount = computed(() => vocabulary.value.length)
const mobilePreferredLanguageLabel = computed(() =>
  preferredContentLanguage.value === 'ja' ? '日文听力' : '英文听力'
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
    .filter((channel) => channel.taskCount > 0)
)
const desktopChannelCards = computed(() => desktopCategoryChannels.value)
const desktopChannelOptions = computed(() =>
  folders.value.filter((folder) => folder.kind === 'channel' && folder.parentId === desktopTaskCategoryId.value)
)
const desktopEpisodeTasks = computed(() => {
  if (!selectedDesktopChannelId.value) {
    return []
  }
  return visibleTasks.value
    .filter((task) => task.folderId === selectedDesktopChannelId.value)
    .sort((left, right) => new Date(right.createdAt) - new Date(left.createdAt))
})

function updateViewportWidth() {
  viewportWidth.value = window.innerWidth
}

function readFileAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(new Error('读取图片失败'))
    reader.readAsDataURL(file)
  })
}

function buildCoverCardStyle(coverImageDataUrl) {
  if (!coverImageDataUrl) {
    return {}
  }
  return {
    backgroundImage: `linear-gradient(rgba(20, 50, 61, 0.5), rgba(20, 50, 61, 0.5)), url(${coverImageDataUrl})`,
    backgroundSize: 'cover',
    backgroundPosition: 'center'
  }
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
  desktopTaskCategoryId.value = categoryId
  const cards = folders.value
    .filter((folder) => folder.kind === 'channel' && folder.parentId === categoryId)
    .map((channel) => channel.id)
  selectedDesktopChannelId.value = cards[0] || ''
  desktopTaskChannelId.value = cards[0] || ''
}

function openDesktopChannel(channelId) {
  selectedDesktopChannelId.value = channelId
  desktopTaskChannelId.value = channelId
  selectedFolderId.value = channelId
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
    errorMessage.value = error.message
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
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isLoadingVocabulary.value = false
  }
}

async function bootstrapAuthenticatedUser() {
  if (!getAuthToken()) {
    authReady.value = true
    return
  }

  try {
    currentUser.value = await fetchCurrentUser()
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
    authForm.value.password = ''
    await Promise.all([
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
  try {
    await logout()
  } catch {
    // Ignore logout API failures and clear local state anyway.
  }
  clearAuthToken()
  currentUser.value = null
  vocabulary.value = []
  tasks.value = []
  folders.value = []
  selectedTaskId.value = ''
  selectedFolderId.value = 'inbox'
  currentView.value = 'dashboard'
  mobileTab.value = 'podcast'
  authMode.value = 'login'
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
    await loadLibrary({ preferredFolderId: selectedFolderId.value, preferredTaskId: selectedTaskId.value })
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isUpdatingProfile.value = false
  }
}

async function handleAvatarSelected(event) {
  const [file] = event.target.files || []
  if (!file || !currentUser.value) {
    return
  }

  const reader = new FileReader()
  reader.onload = async () => {
    isUpdatingProfile.value = true
    errorMessage.value = ''
    try {
      currentUser.value = await updateProfile({ avatarDataUrl: String(reader.result || '') })
      successMessage.value = '头像已更新'
    } catch (error) {
      errorMessage.value = error.message
    } finally {
      isUpdatingProfile.value = false
    }
  }
  reader.readAsDataURL(file)
}

async function handleAdminChannelCoverSelected(event) {
  const [file] = event.target.files || []
  if (!file) {
    return
  }
  try {
    adminChannelForm.value.coverImageDataUrl = await readFileAsDataUrl(file)
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function handleChannelEditorCoverSelected(event) {
  const [file] = event.target.files || []
  if (!file) {
    return
  }
  try {
    channelEditorCoverImageDataUrl.value = await readFileAsDataUrl(file)
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function submitAdminCategory() {
  if (!adminCategoryForm.value.name.trim()) {
    return
  }
  isCreatingCategory.value = true
  errorMessage.value = ''
  try {
    await createFolder({
      name: adminCategoryForm.value.name.trim(),
      kind: 'category',
      contentLanguage: adminCategoryForm.value.contentLanguage
    })
    adminCategoryForm.value.name = ''
    await loadLibrary({ preferredFolderId: selectedFolderId.value, preferredTaskId: selectedTaskId.value })
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isCreatingCategory.value = false
  }
}

async function submitAdminChannel() {
  if (!adminChannelForm.value.name.trim() || !adminChannelForm.value.parentId) {
    return
  }
  isCreatingChannel.value = true
  errorMessage.value = ''
  try {
    await createFolder({
      name: adminChannelForm.value.name.trim(),
      kind: 'channel',
      parentId: adminChannelForm.value.parentId,
      coverImageDataUrl: adminChannelForm.value.coverImageDataUrl
    })
    adminChannelForm.value.name = ''
    adminChannelForm.value.coverImageDataUrl = ''
    await loadLibrary({ preferredFolderId: selectedFolderId.value, preferredTaskId: selectedTaskId.value })
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isCreatingChannel.value = false
  }
}

async function updateSelectedCategory() {
  if (!selectedDesktopCategory.value || !categoryEditorName.value.trim()) {
    return
  }
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const updatedFolder = await updateFolder(selectedDesktopCategory.value.id, {
      name: categoryEditorName.value.trim(),
      contentLanguage: selectedDesktopCategory.value.contentLanguage
    })
    await loadLibrary({ preferredFolderId: updatedFolder.id, preferredTaskId: selectedTaskId.value })
    successMessage.value = `已更新大类：${updatedFolder.name}`
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function updateSelectedChannel() {
  if (!selectedDesktopChannel.value || !channelEditorName.value.trim()) {
    return
  }
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const updatedFolder = await updateFolder(selectedDesktopChannel.value.id, {
      name: channelEditorName.value.trim(),
      parentId: selectedDesktopCategoryId.value,
      contentLanguage: selectedDesktopChannel.value.contentLanguage,
      coverImageDataUrl: channelEditorCoverImageDataUrl.value
    })
    await loadLibrary({ preferredFolderId: updatedFolder.id, preferredTaskId: selectedTaskId.value })
    successMessage.value = `已更新广播：${updatedFolder.name}`
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function removeDesktopCategory() {
  if (!selectedDesktopCategory.value) {
    return
  }
  const confirmed = window.confirm(`确定删除大类《${selectedDesktopCategory.value.name}》吗？它下面的广播也会一起删除。`)
  if (!confirmed) {
    return
  }
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await removeFolder(selectedDesktopCategory.value.id)
    selectedDesktopCategoryId.value = ''
    selectedDesktopChannelId.value = ''
    await loadLibrary({ preferredFolderId: 'inbox' })
    successMessage.value = '已删除大类'
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function removeDesktopChannel() {
  if (!selectedDesktopChannel.value) {
    return
  }
  const confirmed = window.confirm(`确定删除广播《${selectedDesktopChannel.value.name}》吗？`)
  if (!confirmed) {
    return
  }
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await removeFolder(selectedDesktopChannel.value.id)
    selectedDesktopChannelId.value = ''
    await loadLibrary({ preferredFolderId: 'inbox' })
    successMessage.value = '已删除广播'
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function submitDesktopTask() {
  if (!desktopTaskChannelId.value) {
    errorMessage.value = '先选择一个二类广播，再添加内容'
    return
  }
  form.value.folderId = desktopTaskChannelId.value
  await submitTask()
  selectedDesktopChannelId.value = desktopTaskChannelId.value
}

async function submitTask() {
  isSubmitting.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const task = await createTask(form.value)
    tasks.value = [task, ...tasks.value]
    selectedFolderId.value = task.folderId
    selectedTaskId.value = task.id
    currentView.value = 'dashboard'
    startPolling()
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isSubmitting.value = false
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
  showVocabularyModal.value = true
  loadVocabularyList()
}

async function handleRemoveVocabulary(itemId) {
  try {
    await removeVocabulary(itemId)
    vocabulary.value = vocabulary.value.filter((item) => item.id !== itemId)
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function handleWordSaved(item) {
  if (!item) {
    return
  }
  vocabulary.value = [item, ...vocabulary.value.filter((existing) => existing.id !== item.id)]
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
  selectedDesktopCategoryId.value = ''
  selectedDesktopChannelId.value = ''
  desktopTaskCategoryId.value = ''
  desktopTaskChannelId.value = ''
})

watch(desktopChannelCards, (cards) => {
  if (!cards.some((card) => card.id === selectedDesktopChannelId.value)) {
    selectedDesktopChannelId.value = cards[0]?.id || ''
  }
})

watch(selectedDesktopCategory, (category) => {
  categoryEditorName.value = category?.name || ''
  adminChannelForm.value.parentId = category?.id || ''
  if (category) {
    desktopTaskCategoryId.value = category.id
  }
})

watch(selectedDesktopChannel, (channel) => {
  channelEditorName.value = channel?.name || ''
  channelEditorCoverImageDataUrl.value = channel?.coverImageDataUrl || ''
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
  syncViewFromHash()
  window.addEventListener('hashchange', syncViewFromHash)
  window.addEventListener('resize', updateViewportWidth)
  bootstrapAuthenticatedUser()
})

onBeforeUnmount(() => {
  stopPolling()
  window.removeEventListener('hashchange', syncViewFromHash)
  window.removeEventListener('resize', updateViewportWidth)
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
        <p class="eyebrow">账号</p>
        <h1>{{ authMode === 'login' ? '登录后使用生词本和词语解释' : '先创建一个本地账号' }}</h1>
        <p class="hero-text">
          目前先用邮箱和密码登录，不做验证码。生词本会绑定到当前账号下面。
        </p>

        <form class="task-form auth-form" @submit.prevent="submitAuth">
          <label>
            <span>邮箱</span>
            <input v-model.trim="authForm.email" type="email" placeholder="you@example.com" required />
          </label>
          <label>
            <span>密码</span>
            <input v-model="authForm.password" type="password" placeholder="至少 6 位" required />
          </label>
          <p v-if="authErrorMessage" class="error-banner">{{ authErrorMessage }}</p>
          <div class="auth-actions">
            <button type="submit" class="primary-button" :disabled="isSubmittingAuth">
              {{ isSubmittingAuth ? '提交中...' : authMode === 'login' ? '登录' : '注册并登录' }}
            </button>
            <button
              type="button"
              class="ghost-button"
              @click="authMode = authMode === 'login' ? 'register' : 'login'"
            >
              {{ authMode === 'login' ? '去注册' : '已有账号，去登录' }}
            </button>
          </div>
        </form>
      </div>
    </section>

    <template v-else>
      <section v-if="!isMobileLayout" class="hero-card session-toolbar">
        <div>
          <p class="eyebrow">当前账号</p>
          <strong>{{ currentUser.email }}</strong>
        </div>
        <div v-if="!isMobileLayout" class="session-actions">
          <button type="button" class="ghost-button" @click="openVocabularyModal">
            生词本 {{ vocabularyCount ? `(${vocabularyCount})` : '' }}
          </button>
          <button type="button" class="ghost-button" @click="loadLibrary" :disabled="isLoading">
            {{ isLoading ? '刷新中...' : '刷新内容' }}
          </button>
          <button type="button" class="danger-button session-logout-button" @click="handleLogout">
            退出登录
          </button>
        </div>
      </section>

      <template v-if="currentView === 'dashboard'">
        <section v-if="isMobileLayout && mobileTab === 'me'" class="dashboard-grid mobile-profile-grid">
          <section class="panel-card mobile-profile-card">
            <p class="eyebrow">我的</p>
            <div class="profile-avatar-row">
              <div class="profile-avatar">
                <img v-if="currentUser.avatarDataUrl" :src="currentUser.avatarDataUrl" alt="avatar" />
                <span v-else>{{ currentUser.email.slice(0, 1).toUpperCase() }}</span>
              </div>
              <div class="profile-avatar-copy">
                <strong>{{ currentUser.email }}</strong>
                <label class="ghost-button avatar-upload-button">
                  上传头像
                  <input type="file" accept="image/*" class="hidden-file-input" @change="handleAvatarSelected" />
                </label>
              </div>
            </div>

            <div class="mobile-setting-card">
              <p class="eyebrow">设置</p>
              <label>
                <span>想要听广播</span>
                <select :value="preferredContentLanguage" @change="handleProfileLanguageChange" :disabled="isUpdatingProfile">
                  <option value="en">英文</option>
                  <option value="ja">日文</option>
                </select>
              </label>
              <p class="hero-text">
                当前会显示{{ mobilePreferredLanguageLabel }}内容。
              </p>
            </div>

            <div class="mobile-setting-card">
              <p class="eyebrow">工具</p>
              <div class="auth-actions">
                <button type="button" class="ghost-button" @click="openVocabularyModal">
                  生词本 {{ vocabularyCount ? `(${vocabularyCount})` : '' }}
                </button>
                <button type="button" class="ghost-button" @click="loadLibrary" :disabled="isLoading">
                  {{ isLoading ? '刷新中...' : '刷新内容' }}
                </button>
              </div>
            </div>

            <div class="mobile-setting-card">
              <p class="eyebrow">反馈</p>
              <p class="hero-text">如果你想提建议或者反馈问题，可以直接加我微信：</p>
              <strong class="feedback-wechat">-Leonfc-</strong>
            </div>

            <div v-if="isAdmin" class="mobile-setting-card">
              <p class="eyebrow">广播管理</p>
              <form class="task-form" @submit.prevent="submitAdminCategory">
                <label>
                  <span>新建大类</span>
                  <input v-model.trim="adminCategoryForm.name" type="text" placeholder="比如：日语听力 / 英文新闻" />
                </label>
                <label>
                  <span>内容语言</span>
                  <select v-model="adminCategoryForm.contentLanguage">
                    <option value="ja">日文</option>
                    <option value="en">英文</option>
                  </select>
                </label>
                <button type="submit" class="ghost-button" :disabled="isCreatingCategory">
                  {{ isCreatingCategory ? '创建中...' : '创建大类' }}
                </button>
              </form>

              <form class="task-form" @submit.prevent="submitAdminChannel">
                <label>
                  <span>新建广播</span>
                  <input v-model.trim="adminChannelForm.name" type="text" placeholder="比如：NHK World / BBC Learning English" />
                </label>
                <label>
                  <span>归属大类</span>
                  <select v-model="adminChannelForm.parentId">
                    <option v-for="folder in categoryFolders" :key="folder.id" :value="folder.id">{{ folder.name }}</option>
                  </select>
                </label>
                <button type="submit" class="ghost-button" :disabled="isCreatingChannel || !categoryFolders.length">
                  {{ isCreatingChannel ? '创建中...' : '创建广播' }}
                </button>
              </form>
            </div>

            <button type="button" class="danger-button session-logout-button" @click="handleLogout">
              退出登录
            </button>
          </section>
        </section>

        <template v-if="!isMobileLayout || mobileTab === 'podcast'">
        <section v-if="isMobileLayout" class="panel-card mobile-podcast-shell">
          <template v-if="mobilePodcastLevel === 'categories'">
            <div class="panel-header">
              <div>
                <p class="eyebrow">广播大类</p>
                <h2>{{ mobilePreferredLanguageLabel }}</h2>
              </div>
            </div>

            <div class="mobile-category-grid">
              <button
                v-for="category in categoryFolders"
                :key="category.id"
                type="button"
                class="folder-item mobile-category-card"
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
                <p class="eyebrow">广播列表</p>
                <h2>{{ selectedMobileCategory?.name || mobilePreferredLanguageLabel }}</h2>
              </div>
              <button type="button" class="ghost-button" @click="backMobilePodcastLevel">返回</button>
            </div>

            <div v-if="mobileChannelCards.length === 0" class="empty-state">这个大类下面还没有广播。</div>
            <div v-else class="task-list">
              <button
                v-for="channel in mobileChannelCards"
                :key="channel.id"
                type="button"
                class="task-item mobile-task-item"
                @click="openMobileChannel(channel.id)"
              >
                <div class="task-item-topline">
                  <span class="task-status">{{ channel.taskCount }} 条更新</span>
                  <span class="task-open-hint">进入广播</span>
                </div>
                <strong>{{ channel.name }}</strong>
                <small>{{ channel.latestTaskTitle || '还没有最近更新' }}</small>
              </button>
            </div>
          </template>

          <template v-else>
            <div class="panel-header">
              <div>
                <p class="eyebrow">音频列表</p>
                <h2>{{ selectedMobileChannel?.name || selectedMobileCategory?.name || mobilePreferredLanguageLabel }}</h2>
              </div>
              <button type="button" class="ghost-button" @click="backMobilePodcastLevel">返回</button>
            </div>

            <p v-if="errorMessage" class="error-banner">{{ errorMessage }}</p>
            <p v-if="successMessage" class="success-banner">{{ successMessage }}</p>
            <p v-else-if="mobileEpisodeTasks.length === 0" class="empty-state">这个广播下面还没有音频。</p>

            <div v-else class="task-list">
              <button
                v-for="task in mobileEpisodeTasks"
                :key="task.id"
                type="button"
                class="task-item mobile-task-item"
                @click="openTaskDetail(task.id)"
              >
                <div class="task-item-topline">
                  <span class="task-status">{{ task.status }} · {{ task.progress }}%</span>
                  <span class="task-open-hint">打开音频</span>
                </div>
                <strong>{{ task.mediaTitle || task.mediaUrl }}</strong>
                <small>{{ new Date(task.createdAt).toLocaleString() }}</small>
              </button>
            </div>
          </template>
        </section>

        <template v-if="!isMobileLayout">
        <section v-if="!isMobileLayout" class="hero-card dashboard-hero desktop-admin-hero">
          <div>
            <p class="eyebrow">桌面配置台</p>
            <h1>电脑端只做广播配置</h1>
            <p class="hero-text">
              这里专门配置大类名、二类广播名、广播封面，以及把新的链接放进指定二类下面。
            </p>
          </div>
          <div class="session-actions">
            <button type="button" class="ghost-button" @click="openVocabularyModal">
              生词本 {{ vocabularyCount ? `(${vocabularyCount})` : '' }}
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
          <section class="panel-card scroll-panel">
            <div class="panel-header">
              <div>
                <p class="eyebrow">一类大类</p>
                <h2>{{ mobilePreferredLanguageLabel }}</h2>
              </div>
              <div class="summary-pill">
                <strong>{{ categoryFolders.length }}</strong>
                <span>个大类</span>
              </div>
            </div>

            <p v-if="errorMessage" class="error-banner">{{ errorMessage }}</p>
            <p v-if="successMessage" class="success-banner">{{ successMessage }}</p>

            <div class="folder-list">
              <button
                v-for="category in categoryFolders"
                :key="category.id"
                type="button"
                class="folder-item"
                :class="{ active: category.id === selectedDesktopCategoryId }"
                @click="openDesktopCategory(category.id)"
              >
                <span class="folder-item-copy">
                  <strong>{{ category.name }}</strong>
                  <small>{{ desktopCategoryTaskCount(category.id) }} 条广播内容</small>
                </span>
              </button>
            </div>

            <div v-if="isAdmin" class="desktop-admin-stack">
              <form class="task-form" @submit.prevent="submitAdminCategory">
                <label>
                  <span>新增大类名</span>
                  <input v-model.trim="adminCategoryForm.name" type="text" placeholder="比如：日文听力 / 英文播客" />
                </label>
                <label>
                  <span>内容语言</span>
                  <select v-model="adminCategoryForm.contentLanguage">
                    <option value="ja">日文</option>
                    <option value="en">英文</option>
                  </select>
                </label>
                <button type="submit" class="ghost-button" :disabled="isCreatingCategory">
                  {{ isCreatingCategory ? '创建中...' : '创建大类' }}
                </button>
              </form>

              <form v-if="selectedDesktopCategory" class="task-form" @submit.prevent="updateSelectedCategory">
                <label>
                  <span>修改当前大类名</span>
                  <input v-model.trim="categoryEditorName" type="text" placeholder="修改当前大类名称" />
                </label>
                <div class="inline-button-row">
                  <button type="submit" class="ghost-button">保存大类</button>
                  <button type="button" class="danger-button inline-danger-button" @click="removeDesktopCategory">
                    删除大类
                  </button>
                </div>
              </form>
            </div>
          </section>

          <section class="panel-card scroll-panel">
            <div class="panel-header">
              <div>
                <p class="eyebrow">二类广播</p>
                <h2>{{ selectedDesktopCategory?.name || '先选左侧大类' }}</h2>
              </div>
              <div class="summary-pill">
                <strong>{{ desktopChannelCards.length }}</strong>
                <span>个广播</span>
              </div>
            </div>

            <div v-if="!selectedDesktopCategory" class="empty-state">先在左边选一个大类，再配置二类广播。</div>
            <div v-else class="task-list desktop-channel-list">
              <button
                v-for="channel in desktopChannelCards"
                :key="channel.id"
                type="button"
                class="task-item desktop-channel-card"
                :class="{ active: channel.id === selectedDesktopChannelId }"
                :style="buildCoverCardStyle(channel.coverImageDataUrl)"
                @click="openDesktopChannel(channel.id)"
              >
                <div class="task-item-topline">
                  <span class="task-status">{{ channel.taskCount }} 条内容</span>
                  <span class="task-open-hint">当前二类</span>
                </div>
                <strong>{{ channel.name }}</strong>
                <small>{{ channel.latestTaskTitle || '还没有广播内容' }}</small>
              </button>
            </div>

            <div v-if="isAdmin && selectedDesktopCategory" class="desktop-admin-stack">
              <form class="task-form" @submit.prevent="submitAdminChannel">
                <label>
                  <span>新增二类广播名</span>
                  <input v-model.trim="adminChannelForm.name" type="text" placeholder="比如：NHK / BBC / 某个主播" />
                </label>
                <label>
                  <span>放到哪个大类下面</span>
                  <select v-model="adminChannelForm.parentId">
                    <option v-for="folder in categoryFolders" :key="folder.id" :value="folder.id">{{ folder.name }}</option>
                  </select>
                </label>
                <label class="cover-upload-field">
                  <span>广播封面</span>
                  <input type="file" accept="image/*" @change="handleAdminChannelCoverSelected" />
                </label>
                <div v-if="adminChannelForm.coverImageDataUrl" class="desktop-cover-preview">
                  <img :src="adminChannelForm.coverImageDataUrl" alt="channel preview" />
                </div>
                <button type="submit" class="ghost-button" :disabled="isCreatingChannel || !categoryFolders.length">
                  {{ isCreatingChannel ? '创建中...' : '创建二类广播' }}
                </button>
              </form>

              <form v-if="selectedDesktopChannel" class="task-form" @submit.prevent="updateSelectedChannel">
                <label>
                  <span>修改当前广播名</span>
                  <input v-model.trim="channelEditorName" type="text" placeholder="修改当前广播名称" />
                </label>
                <label class="cover-upload-field">
                  <span>更新广播封面</span>
                  <input type="file" accept="image/*" @change="handleChannelEditorCoverSelected" />
                </label>
                <div v-if="channelEditorCoverImageDataUrl" class="desktop-cover-preview">
                  <img :src="channelEditorCoverImageDataUrl" alt="selected channel cover" />
                </div>
                <div class="inline-button-row">
                  <button type="submit" class="ghost-button">保存广播</button>
                  <button type="button" class="danger-button inline-danger-button" @click="removeDesktopChannel">
                    删除广播
                  </button>
                </div>
              </form>
            </div>
          </section>

          <section class="panel-card scroll-panel">
            <div class="panel-header">
              <div>
                <p class="eyebrow">广播内容</p>
                <h2>{{ selectedDesktopChannel?.name || '先选中间二类广播' }}</h2>
              </div>
              <div class="summary-pill">
                <strong>{{ desktopEpisodeTasks.length }}</strong>
                <span>条内容</span>
              </div>
            </div>

            <form class="task-form desktop-task-form" @submit.prevent="submitDesktopTask">
              <label class="form-span-2">
                <span>媒体链接</span>
                <input v-model.trim="form.mediaUrl" type="url" placeholder="https://example.com/video" required />
              </label>
              <label>
                <span>源语言</span>
                <select v-model="form.sourceLanguage" required>
                  <option v-for="language in sourceLanguageOptions" :key="language.value" :value="language.value">
                    {{ language.label }}
                  </option>
                </select>
              </label>
              <label>
                <span>翻译语言</span>
                <select v-model="form.targetLanguages" required>
                  <option v-for="language in targetLanguageOptions" :key="language.value" :value="language.value">
                    {{ language.label }}
                  </option>
                </select>
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
                  {{ isSubmitting ? '处理中...' : '添加到这个广播' }}
                </button>
              </div>
            </form>

            <p v-if="!selectedDesktopChannelId" class="empty-state">先在中间选一个二类广播。</p>
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
                  <span class="task-status">{{ task.status }} · {{ task.progress }}%</span>
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
              <button type="button" class="ghost-button" @click="backToDashboard">返回目录</button>
              <div class="language-switcher">
                <button
                  v-for="language in languageOptions"
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
              <button type="button" class="mobile-back-button" @click="backToDashboard">返回列表</button>
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
            @word-saved="handleWordSaved"
          />
        </section>
      </template>
    </template>

    <div v-if="showVocabularyModal" class="overlay-shell" @click.self="showVocabularyModal = false">
      <section class="overlay-card vocabulary-modal">
        <div class="panel-header">
          <div>
            <p class="eyebrow">生词本</p>
            <h2>{{ currentUser?.email }}</h2>
          </div>
          <button type="button" class="ghost-button" @click="showVocabularyModal = false">关闭</button>
        </div>

        <p v-if="isLoadingVocabulary" class="empty-state">加载中...</p>
        <p v-else-if="vocabulary.length === 0" class="empty-state">你还没有加入任何生词，长按字幕里的词就可以加入。</p>

        <div v-else class="vocabulary-list">
          <article v-for="item in vocabulary" :key="item.id" class="vocabulary-item">
            <div class="vocabulary-item-top">
              <strong>{{ item.word }}</strong>
              <button type="button" class="danger-button inline-danger-button" @click="handleRemoveVocabulary(item.id)">
                删除
              </button>
            </div>
            <p v-if="item.reading" class="vocabulary-reading">{{ item.reading }}</p>
            <p class="vocabulary-meaning">{{ item.meaning }}</p>
            <p v-if="item.usage" class="vocabulary-meta">{{ item.usage }}</p>
            <p v-if="item.example" class="vocabulary-meta">{{ item.example }}</p>
          </article>
        </div>
      </section>
    </div>

    <nav v-if="isMobileLayout && authReady && currentUser && currentView === 'dashboard'" class="mobile-bottom-tabbar">
      <button
        type="button"
        class="mobile-bottom-tab"
        :class="{ active: mobileTab === 'podcast' }"
        @click="mobileTab = 'podcast'"
      >
        Podcast
      </button>
      <button
        type="button"
        class="mobile-bottom-tab"
        :class="{ active: mobileTab === 'me' }"
        @click="mobileTab = 'me'"
      >
        我的
      </button>
    </nav>
  </main>
</template>
