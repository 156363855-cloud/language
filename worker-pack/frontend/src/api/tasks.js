import { Capacitor } from '@capacitor/core'
import { Preferences } from '@capacitor/preferences'

const browserApiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api'
const browserWorkerApiBaseUrl = import.meta.env.VITE_WORKER_API_BASE_URL || '/worker-api'
const nativeApiBaseUrl =
  import.meta.env.VITE_MOBILE_API_BASE_URL || 'http://43.155.234.124:3000/api'
const nativeWorkerApiBaseUrl =
  import.meta.env.VITE_MOBILE_WORKER_API_BASE_URL || nativeApiBaseUrl
const AUTH_TOKEN_KEY = 'lingualink_auth_token'
let authTokenCache = localStorage.getItem(AUTH_TOKEN_KEY) || ''

const rawApiBaseUrl = Capacitor.isNativePlatform() ? nativeApiBaseUrl : browserApiBaseUrl
const rawWorkerApiBaseUrl = Capacitor.isNativePlatform() ? nativeWorkerApiBaseUrl : browserWorkerApiBaseUrl
const API_BASE_URL = rawApiBaseUrl.replace(/\/$/, '')
const WORKER_API_BASE_URL = rawWorkerApiBaseUrl.replace(/\/$/, '')

export function buildApiUrl(path) {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  return `${API_BASE_URL}${normalizedPath}`
}

function buildWorkerApiUrl(path) {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  return `${WORKER_API_BASE_URL}${normalizedPath}`
}

async function requestWithBase(path, baseUrl, options = {}) {
  const token = authTokenCache || localStorage.getItem(AUTH_TOKEN_KEY) || ''
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  const response = await fetch(`${baseUrl}${normalizedPath}`, {
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.headers || {})
    },
    ...options
  })

  if (!response.ok) {
    const responseText = await response.text()
    let message = ''

    if (responseText) {
      try {
        const payload = JSON.parse(responseText)
        message = payload.message || ''
      } catch {
        message = responseText.trim()
      }
    }

    throw new Error(message || `请求失败（HTTP ${response.status}）`)
  }

  if (response.status === 204) {
    return null
  }

  return response.json()
}

export async function hydrateAuthToken() {
  if (!Capacitor.isNativePlatform()) {
    authTokenCache = localStorage.getItem(AUTH_TOKEN_KEY) || ''
    return authTokenCache
  }

  try {
    const { value } = await Preferences.get({ key: AUTH_TOKEN_KEY })
    authTokenCache = value || localStorage.getItem(AUTH_TOKEN_KEY) || ''
    if (authTokenCache) {
      localStorage.setItem(AUTH_TOKEN_KEY, authTokenCache)
    } else {
      localStorage.removeItem(AUTH_TOKEN_KEY)
    }
    return authTokenCache
  } catch {
    authTokenCache = localStorage.getItem(AUTH_TOKEN_KEY) || ''
    return authTokenCache
  }
}

async function request(path, options = {}) {
  return requestWithBase(path, API_BASE_URL, options)
}

async function workerRequest(path, options = {}) {
  return requestWithBase(path, WORKER_API_BASE_URL, options)
}

export function getAuthToken() {
  return authTokenCache || localStorage.getItem(AUTH_TOKEN_KEY) || ''
}

export function setAuthToken(token) {
  authTokenCache = token || ''
  if (!token) {
    localStorage.removeItem(AUTH_TOKEN_KEY)
    if (Capacitor.isNativePlatform()) {
      void Preferences.remove({ key: AUTH_TOKEN_KEY })
    }
    return
  }
  localStorage.setItem(AUTH_TOKEN_KEY, token)
  if (Capacitor.isNativePlatform()) {
    void Preferences.set({ key: AUTH_TOKEN_KEY, value: token })
  }
}

export function clearAuthToken() {
  authTokenCache = ''
  localStorage.removeItem(AUTH_TOKEN_KEY)
  if (Capacitor.isNativePlatform()) {
    void Preferences.remove({ key: AUTH_TOKEN_KEY })
  }
}

function normalizeTargetLanguages(targetLanguages) {
  if (Array.isArray(targetLanguages)) {
    return targetLanguages.join(',')
  }
  return targetLanguages
}

export function createTask(payload) {
  return workerRequest('/tasks', {
    method: 'POST',
    body: JSON.stringify({
      ...payload,
      targetLanguages: normalizeTargetLanguages(payload?.targetLanguages)
    })
  })
}

export function importTask(payload) {
  return request('/tasks/import', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function fetchTasks() {
  return request('/tasks')
}

export function fetchTask(taskId) {
  return request(`/tasks/${taskId}`)
}

export function fetchFolders() {
  return request('/tasks/folders')
}

export function createFolder(payload) {
  return request('/tasks/folders', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function updateFolder(folderId, payload) {
  return request(`/tasks/folders/${folderId}`, {
    method: 'PATCH',
    body: JSON.stringify(payload)
  })
}

export function removeFolder(folderId) {
  return request(`/tasks/folders/${folderId}`, {
    method: 'DELETE'
  })
}

export function moveTask(taskId, payload) {
  return request(`/tasks/${taskId}/folder`, {
    method: 'PATCH',
    body: JSON.stringify(payload)
  })
}

export function deleteTask(taskId) {
  return request(`/tasks/${taskId}`, {
    method: 'DELETE'
  })
}

export function syncRuntimeToCloud() {
  return workerRequest('/tasks/sync-to-cloud', {
    method: 'POST'
  })
}

export function register(payload) {
  return request('/auth/register', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function login(payload) {
  return request('/auth/login', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function fetchCurrentUser() {
  return request('/auth/me')
}

export function updateProfile(payload) {
  return request('/auth/profile', {
    method: 'PATCH',
    body: JSON.stringify(payload)
  })
}

export async function logout(options = {}) {
  const {
    token = getAuthToken(),
    timeoutMs = 1500
  } = options

  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null
  const timer = controller
    ? window.setTimeout(() => controller.abort(), timeoutMs)
    : null

  try {
    return await requestWithBase('/auth/logout', API_BASE_URL, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      ...(controller ? { signal: controller.signal } : {})
    })
  } catch (error) {
    if (error?.name === 'AbortError') {
      return null
    }
    throw error
  } finally {
    if (timer) {
      window.clearTimeout(timer)
    }
  }
}

export function fetchVocabulary() {
  return request('/vocabulary')
}

export function addVocabulary(payload) {
  return request('/vocabulary', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function removeVocabulary(itemId) {
  return request(`/vocabulary/${itemId}`, {
    method: 'DELETE'
  })
}

export function explainWord(payload) {
  return request('/words/explain', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}
