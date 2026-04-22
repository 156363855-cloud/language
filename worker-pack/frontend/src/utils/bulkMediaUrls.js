/**
 * 批量/单条 任务添加：从文本、yml、文本框中解析 http(s) 地址。
 */

const URL_IN_TEXT = /https?:\/\/[^\s<>"'，。]+/g
const BULK_TRAILING_JUNK = /[),，。．;；」)]+$/g

/**
 * 从 yml/纯文本中提取所有不重复的 http(s) 链接（保序）。
 * @param {string} [raw]
 * @returns {string[]}
 */
export function parseBulkMediaInput(raw) {
  if (!raw || !String(raw).trim()) {
    return []
  }
  const text = String(raw)
    .replace(/^\uFEFF/g, '')
    .replace(/[\u200B-\u200D\ufeff]/g, '')
    .split(/\r?\n/)
    .map((line) => line.replace(/\u00a0/g, ' ').trim())
    .filter((line) => line.length > 0 && !/^\s*#/.test(line))
    .join('\n')
    .replace(/\r?\n&/g, '&')

  const seen = new Set()
  const out = []
  for (const match of text.matchAll(URL_IN_TEXT)) {
    const rawUrl = match[0].replace(BULK_TRAILING_JUNK, '')
    if (rawUrl && /^https?:\/\//.test(rawUrl) && !seen.has(rawUrl)) {
      seen.add(rawUrl)
      out.push(rawUrl)
    }
  }
  return out
}

/**
 * 决定使用哪些媒体 URL 及来源（用于成功后清空哪一块输入框）。
 * @param {object} p
 * @param {string} [p.singleLine] form.mediaUrl
 * @param {string} [p.bulkText] bulk 文本
 * @param {'smart' | 'batch-only' | 'single-only'} p.strategy
 * @returns {{ ok: true, urls: string[], fromBulk: boolean } | { ok: false, urls: [], error: string } }
 */
export function resolveDesktopAddMediaUrls(p) {
  const single = (p.singleLine || '').trim()
  const bulk = parseBulkMediaInput(p.bulkText)
  const strategy = p.strategy || 'smart'

  if (strategy === 'batch-only') {
    if (bulk.length === 0) {
      return { ok: false, urls: [], error: '批量区没有解析到有效链接。请每行以 https:// 开头，或上传 yml / txt 后再点本按钮。' }
    }
    return { ok: true, urls: bulk, fromBulk: true }
  }

  if (strategy === 'single-only') {
    if (!single) {
      return { ok: false, urls: [], error: '请在上方的「单条媒体链接」中粘贴一个地址。' }
    }
    return { ok: true, urls: [single], fromBulk: false }
  }

  if (bulk.length > 0) {
    return { ok: true, urls: bulk, fromBulk: true }
  }
  if (single) {
    return { ok: true, urls: [single], fromBulk: false }
  }
  return {
    ok: false,
    urls: [],
    error: '没有可添加的链接。单条用上方「媒体链接」；多条用「批量」或 yml 文件。'
  }
}

/**
 * 打开本地批量文件为文本（可配合 {@link parseBulkMediaInput} 使用）
 * @param {File} file
 * @returns {Promise<string>}
 */
export function readBatchFileAsText(file) {
  return file.text().then((t) => t.replace(/^\uFEFF/, ''))
}
