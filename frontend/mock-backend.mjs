// 完整 mock 后端：模拟 agent-orchestrator 的全部前端所需接口
// 用于在没有真实后端时，验证前端登录 → 对话 → 会话列表全链路。
//
// 模拟的接口（对应后端 Controller）：
//   POST /api/ai/user/login            -> Result<UserLoginVO>
//   GET  /api/ai/chat/history          -> Result<string[]>
//   GET  /api/ai/chat/history/:chatId  -> Result<List<String>>
//   DELETE /api/ai/chat/history/:chatId -> Result<string>
//   GET  /api/ai/chat/:msg             -> SSE (data:Agent思考:... / data:Agent结果:...)
//   GET  /api/ai/chat/rag/:msg         -> SSE
//
// 统一响应 Result<T>：{ code: 200, message: "成功", data: T }
// 注意 context-path 是 /api

import { createServer } from 'node:http'

const PORT = 8080
const result = (data, code = 200, message = '成功') => ({ code, message, data })

// 内存会话存储：chatId -> 消息列表 ["USER:xxx", "ASSISTANT:xxx"]
const sessions = new Map()

function ok(res, data) {
  res.writeHead(200, { 'Content-Type': 'application/json;charset=utf-8' })
  res.end(JSON.stringify(result(data)))
}

function readBody(req) {
  return new Promise((resolve) => {
    let body = ''
    req.on('data', (c) => (body += c))
    req.on('end', () => {
      try {
        resolve(body ? JSON.parse(body) : {})
      } catch {
        resolve({})
      }
    })
  })
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`)
  const path = url.pathname
  const method = req.method

  // 登录
  if (method === 'POST' && path === '/api/ai/user/login') {
    const body = await readBody(req)
    const token = 'mock-token-' + Date.now()
    ok(res, { token, userId: 1, userName: body.userName || 'demo' })
    return
  }

  // 会话历史列表
  if (method === 'GET' && path === '/api/ai/chat/history') {
    ok(res, Array.from(sessions.keys()))
    return
  }

  // 单个会话历史
  const historyMatch = path.match(/^\/api\/ai\/chat\/history\/(.+)$/)
  if (historyMatch && method === 'GET') {
    const chatId = decodeURIComponent(historyMatch[1])
    ok(res, sessions.get(chatId) || [])
    return
  }
  if (historyMatch && method === 'DELETE') {
    const chatId = decodeURIComponent(historyMatch[1])
    sessions.delete(chatId)
    ok(res, '删除成功')
    return
  }

  // 普通对话 SSE
  const chatMatch = path.match(/^\/api\/ai\/chat\/(.+)$/)
  const ragMatch = path.match(/^\/api\/ai\/chat\/rag\/(.+)$/)
  if (method === 'GET' && (chatMatch || ragMatch)) {
    const isRag = !!ragMatch
    const msg = decodeURIComponent((ragMatch || chatMatch)[1])
    const chatId = req.headers['chatid'] || 'demo-chat'

    if (!sessions.has(chatId)) sessions.set(chatId, [])
    sessions.get(chatId).push(`USER:${msg}`)

    res.writeHead(200, {
      'Content-Type': 'text/event-stream;charset=utf-8',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })

    const thinkSteps = isRag
      ? ['开始检索知识库关联内容', '检索完成']
      : ['正在分析问题', '进行意图路由', '组织回答']

    let step = 0
    const timer = setInterval(() => {
      if (step < thinkSteps.length) {
        res.write(`data:Agent思考:${thinkSteps[step]}\n\n`)
        step++
      } else {
        clearInterval(timer)
        const answer = isRag
          ? `这是基于知识库的 RAG 回答：${msg}`
          : `这是针对「${msg}」的回答。\n\n演示了完整的 SSE 流式链路：后端 SseEmitter 推送带前缀的 data 事件，前端 fetch + ReadableStream 逐块读取、按空行分隔、按前缀区分思考与结果。`
        sessions.get(chatId).push(`ASSISTANT:${answer}`)
        res.write(`data:Agent结果:${answer}\n\n`)
        res.end()
      }
    }, 400)

    req.on('close', () => clearInterval(timer))
    return
  }

  // 404
  res.writeHead(404, { 'Content-Type': 'application/json;charset=utf-8' })
  res.end(JSON.stringify(result(null, 404, '接口不存在')))
})

server.listen(PORT, () => {
  console.log(`mock 后端已启动：http://localhost:${PORT}`)
  console.log('接口路径均带 context-path /api')
})
