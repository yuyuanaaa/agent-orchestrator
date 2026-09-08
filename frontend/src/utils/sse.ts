/**
 * SSE 流式解析器（fetch + ReadableStream）
 *
 * 后端 ChatController 的对话接口是 text/event-stream，且要求 @RequestHeader("chatId")，
 * EventSource 传不了自定义请求头，所以用 fetch 手动读流。
 *
 * 后端协议事实（读 ChatController.java / SSESend.java 确认）：
 *   1. 事件无 event:/id: 字段，只有 data: 行
 *   2. 类型靠前缀区分：'Agent思考:'=思考过程，'Agent结果:'=最终结果
 *   3. 无显式结束标记，靠后端 emitter.complete() 关闭连接判定结束
 */

import { handleUnauthorized } from '@/utils/auth'

export interface SSEMessage {
  type: 'think' | 'result' | 'raw'
  data: string
}

export interface SSEStreamOptions {
  baseUrl: string
  headers?: Record<string, string>
  onThink?: (chunk: string) => void
  onResult?: (result: string) => void
  onRaw?: (raw: string) => void
  onDone?: () => void
  onError?: (err: Error) => void
  signal?: AbortSignal
}

const THINK_PREFIX = 'Agent思考:'
const RESULT_PREFIX = 'Agent结果:'

export async function streamSSE(opts: SSEStreamOptions): Promise<() => void> {
  const controller = new AbortController()
  const onAbort = () => controller.abort()
  opts.signal?.addEventListener('abort', onAbort)

  ;(async () => {
    try {
      const resp = await fetch(opts.baseUrl, {
        method: 'GET',
        headers: {
          Accept: 'text/event-stream',
          ...(opts.headers ?? {}),
        },
        signal: controller.signal,
      })

      if (!resp.ok) {
        // 后端非 2xx（如 401）会返回 Result<T> JSON，尽量把 message 提出来给用户看
        let message = `HTTP ${resp.status}`
        try {
          const text = await resp.text()
          const json = JSON.parse(text)
          if (json && typeof json.message === 'string' && json.message) {
            message = json.message
          }
        } catch {
          // 非 JSON 响应，保留默认状态码文案
        }
        // 未登录 / token 过期：统一清空登录态并跳转登录页
        if (resp.status === 401) {
          handleUnauthorized()
        }
        // 错误对象带上 status，让调用方（如 ChatView）可区分 401 与普通错误，避免重复提示
        const err = new Error(message) as Error & { status?: number }
        err.status = resp.status
        throw err
      }
      if (!resp.body) {
        throw new Error('当前环境不支持 ReadableStream')
      }

      const reader = resp.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      const flushLines = (text: string) => {
        buffer += text
        let sep: number
        while ((sep = buffer.indexOf('\n\n')) !== -1) {
          const rawEvent = buffer.slice(0, sep)
          buffer = buffer.slice(sep + 2)
          parseEvent(rawEvent, opts)
        }
      }

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        flushLines(decoder.decode(value, { stream: true }))
      }
      flushLines(decoder.decode())

      if (buffer.trim()) {
        parseEvent(buffer, opts)
        buffer = ''
      }

      opts.onDone?.()
    } catch (err) {
      if ((err as Error).name === 'AbortError') return
      opts.onError?.(err as Error)
    } finally {
      opts.signal?.removeEventListener('abort', onAbort)
    }
  })()

  return () => controller.abort()
}

function parseEvent(raw: string, opts: SSEStreamOptions): void {
  const dataLines: string[] = []
  for (const line of raw.split(/\r?\n/)) {
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }
  if (dataLines.length === 0) return
  const data = dataLines.join('\n')

  if (data.startsWith(THINK_PREFIX)) {
    opts.onThink?.(data.slice(THINK_PREFIX.length))
  } else if (data.startsWith(RESULT_PREFIX)) {
    opts.onResult?.(data.slice(RESULT_PREFIX.length))
  } else {
    opts.onRaw?.(data)
  }
}
