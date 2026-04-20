import { Capacitor } from '@capacitor/core'
import { Directory, Filesystem } from '@capacitor/filesystem'
import { Preferences } from '@capacitor/preferences'

const LIBRARY_CACHE_KEY = 'lingualink_library_cache_v1'
const PROFILE_CACHE_KEY = 'lingualink_profile_cache_v1'
const AUDIO_CACHE_META_KEY = 'lingualink_audio_cache_meta_v1'
const AUDIO_CACHE_DIR = 'lingualink/audio'
const AUDIO_CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000

function isNativeApp() {
  return Capacitor.isNativePlatform()
}

async function getJson(key, fallbackValue) {
  try {
    const { value } = await Preferences.get({ key })
    return value ? JSON.parse(value) : fallbackValue
  } catch {
    return fallbackValue
  }
}

async function setJson(key, value) {
  await Preferences.set({
    key,
    value: JSON.stringify(value)
  })
}

async function removeKey(key) {
  await Preferences.remove({ key })
}

function arrayBufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer)
  const chunkSize = 0x8000
  let binary = ''
  for (let index = 0; index < bytes.length; index += chunkSize) {
    const chunk = bytes.subarray(index, index + chunkSize)
    binary += String.fromCharCode(...chunk)
  }
  return btoa(binary)
}

function sanitizeAudioExtension(contentType) {
  if (!contentType) {
    return 'mp3'
  }
  const normalized = contentType.toLowerCase()
  if (normalized.includes('mpeg') || normalized.includes('mp3')) {
    return 'mp3'
  }
  if (normalized.includes('mp4') || normalized.includes('m4a')) {
    return 'm4a'
  }
  if (normalized.includes('wav')) {
    return 'wav'
  }
  if (normalized.includes('ogg')) {
    return 'ogg'
  }
  return 'bin'
}

async function ensureAudioDirectory() {
  if (!isNativeApp()) {
    return
  }
  try {
    await Filesystem.mkdir({
      path: AUDIO_CACHE_DIR,
      directory: Directory.Data,
      recursive: true
    })
  } catch {
    // Ignore existing directory errors.
  }
}

export async function loadCachedLibrarySnapshot() {
  return getJson(LIBRARY_CACHE_KEY, null)
}

export async function saveCachedLibrarySnapshot(snapshot) {
  await setJson(LIBRARY_CACHE_KEY, snapshot)
}

export async function clearCachedLibrarySnapshot() {
  await removeKey(LIBRARY_CACHE_KEY)
}

export async function loadCachedProfile() {
  return getJson(PROFILE_CACHE_KEY, null)
}

export async function saveCachedProfile(profile) {
  await setJson(PROFILE_CACHE_KEY, profile)
}

export async function clearCachedProfile() {
  await removeKey(PROFILE_CACHE_KEY)
}

export async function loadCachedVocabulary(userId) {
  if (!userId) {
    return []
  }
  return getJson(`lingualink_vocabulary_${userId}`, [])
}

export async function saveCachedVocabulary(userId, items) {
  if (!userId) {
    return
  }
  await setJson(`lingualink_vocabulary_${userId}`, items)
}

export async function clearCachedVocabulary(userId) {
  if (!userId) {
    return
  }
  await removeKey(`lingualink_vocabulary_${userId}`)
}

async function loadAudioMeta() {
  return getJson(AUDIO_CACHE_META_KEY, {})
}

async function saveAudioMeta(meta) {
  await setJson(AUDIO_CACHE_META_KEY, meta)
}

async function deleteCachedAudioFile(relativePath) {
  if (!isNativeApp() || !relativePath) {
    return
  }
  try {
    await Filesystem.deleteFile({
      path: relativePath,
      directory: Directory.Data
    })
  } catch {
    // Ignore missing file errors.
  }
}

export async function cleanupExpiredAudioCache() {
  if (!isNativeApp()) {
    return
  }

  const now = Date.now()
  const meta = await loadAudioMeta()
  let changed = false

  for (const [taskId, entry] of Object.entries(meta)) {
    const lastPlayedAt = Number(entry?.lastPlayedAt || 0)
    if (!lastPlayedAt || now - lastPlayedAt <= AUDIO_CACHE_TTL_MS) {
      continue
    }
    await deleteCachedAudioFile(entry.path)
    delete meta[taskId]
    changed = true
  }

  if (changed) {
    await saveAudioMeta(meta)
  }
}

export async function getCachedAudioUrl(taskId) {
  if (!isNativeApp() || !taskId) {
    return ''
  }

  await cleanupExpiredAudioCache()
  const meta = await loadAudioMeta()
  const entry = meta[taskId]
  if (!entry?.path) {
    return ''
  }

  try {
    meta[taskId] = {
      ...entry,
      lastPlayedAt: Date.now()
    }
    await saveAudioMeta(meta)
    const uri = await Filesystem.getUri({
      path: entry.path,
      directory: Directory.Data
    })
    return Capacitor.convertFileSrc(uri.uri)
  } catch {
    delete meta[taskId]
    await saveAudioMeta(meta)
    return ''
  }
}

export async function cacheRecentlyPlayedAudio({ taskId, remoteUrl, token }) {
  if (!isNativeApp() || !taskId || !remoteUrl) {
    return ''
  }

  await cleanupExpiredAudioCache()
  await ensureAudioDirectory()

  const now = Date.now()
  const meta = await loadAudioMeta()
  const existing = meta[taskId]

  if (existing?.path && existing.sourceUrl === remoteUrl) {
    meta[taskId] = {
      ...existing,
      lastPlayedAt: now
    }
    await saveAudioMeta(meta)
    return getCachedAudioUrl(taskId)
  }

  const response = await fetch(remoteUrl, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined
  })
  if (!response.ok) {
    throw new Error(`音频缓存失败（HTTP ${response.status}）`)
  }

  const extension = sanitizeAudioExtension(response.headers.get('content-type'))
  const relativePath = `${AUDIO_CACHE_DIR}/${taskId}.${extension}`
  const base64 = arrayBufferToBase64(await response.arrayBuffer())

  await Filesystem.writeFile({
    path: relativePath,
    directory: Directory.Data,
    data: base64,
    recursive: true
  })

  meta[taskId] = {
    path: relativePath,
    sourceUrl: remoteUrl,
    cachedAt: now,
    lastPlayedAt: now
  }
  await saveAudioMeta(meta)
  return getCachedAudioUrl(taskId)
}
