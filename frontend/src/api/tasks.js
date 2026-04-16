import { Capacitor } from '@capacitor/core'

const browserApiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api'
const nativeApiBaseUrl =
  import.meta.env.VITE_MOBILE_API_BASE_URL || 'http://150.230.214.236:8080/api'
const AUTH_TOKEN_KEY = 'lingualink_auth_token'

const rawApiBaseUrl = Capacitor.isNativePlatform() ? nativeApiBaseUrl : browserApiBaseUrl
const API_BASE_URL = rawApiBaseUrl.replace(/\/$/, '')

export function buildApiUrl(path) {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  return `${API_BASE_URL}${normalizedPath}`
}

async function request(path, options = {}) {
  const token = localStorage.getItem(AUTH_TOKEN_KEY)
  const response = await fetch(buildApiUrl(path), {
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

export function getAuthToken() {
  return localStorage.getItem(AUTH_TOKEN_KEY) || ''
}

export function setAuthToken(token) {
  if (!token) {
    localStorage.removeItem(AUTH_TOKEN_KEY)
    return
  }
  localStorage.setItem(AUTH_TOKEN_KEY, token)
}

export function clearAuthToken() {
  localStorage.removeItem(AUTH_TOKEN_KEY)
}

export function createTask(payload) {
  return request('/tasks', {
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
  return request('/tasks/sync-to-cloud', {
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

export function logout() {
  return request('/auth/logout', {
    method: 'POST'
  })
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
