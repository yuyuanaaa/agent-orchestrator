# AI 智能体对话前端

基于后端 [agent-orchestrator](../) 的接口契约，用 Vue 3 + Vite + TypeScript + Element Plus + Pinia 构建。

## 技术栈

| 依赖 | 用途 |
| --- | --- |
| Vue 3 | 组合式 API，`<script setup>` |
| Vite | 开发服务器 + 构建，代理转发 |
| TypeScript | 类型安全，类型定义对齐后端 DTO/VO |
| Element Plus | UI 组件库 |
| Pinia | 登录态 / 会话状态管理 |
| Vue Router | 路由 + 登录守卫 |
| axios | 普通 JSON 接口请求 |
| 原生 fetch + ReadableStream | SSE 流式对话（EventSource 传不了自定义请求头） |

## 快速开始

```bash
cd frontend
npm install
npm run dev
```

访问 http://localhost:5173，登录后即可对话。

> 前置条件：后端已启动在 `http://localhost:8080`（context-path 为 `/api`）。

## 目录结构

```
src/
├── api/            # axios 实例 + 各模块接口
│   ├── request.ts  #   baseURL=/api、token 拦截器、Result<T> 解包
│   ├── user.ts     #   登录
│   ├── chat.ts     #   会话历史查询/删除
│   └── upload.ts   #   PDF 上传
├── stores/         # Pinia
│   ├── user.ts     #   登录态（token 持久化 localStorage）
│   └── chat.ts     #   当前会话 id、RAG 模式
├── router/         # 路由 + 登录守卫
├── views/
│   ├── LoginView.vue
│   └── ChatView.vue    # 主界面：会话列表 + 流式对话 + 上传
├── components/
│   ├── ChatSidebar.vue
│   └── MessageBubble.vue
├── utils/
│   └── sse.ts      # SSE 流式解析器（fetch + ReadableStream）
└── types/          # TypeScript 类型，对齐后端 DTO/VO
```

## 前后端联调要点

### 1. 代理转发解决跨域 + context-path

后端 `application.yaml` 配置了 `server.servlet.context-path: /api`，且 Controller 都加了 `@CrossOrigin`。
前端开发环境用 Vite 代理，把 `/api` 转发到 `http://localhost:8080`，**不重写路径前缀**（因为后端 context-path 就是 `/api`）。

```ts
// vite.config.ts
proxy: {
  '/api': { target: 'http://localhost:8080', changeOrigin: true }
}
```

### 2. 统一响应结构

后端 `Result<T>` 是 record：`(int code, String message, T data)`，**成功码是 200**（`ErrorCode.SUCCESS`），不是 1。
axios 响应拦截器里判断 `code !== 200` 即失败。

### 3. 登录态

- 登录接口 `POST /ai/user/login`，返回 `UserLoginVO`（含 token）。
- token 存 localStorage，axios 请求拦截器注入 `Authorization` 头。
- 路由守卫：未登录跳 `/login`。

### 4. SSE 流式对话（本项目的技术亮点）

后端 `ChatController` 的对话接口是 `text/event-stream`，且要求 `@RequestHeader("chatId")`。
`EventSource` 不支持自定义请求头，所以用 `fetch + ReadableStream` 手动解析：

- 事件无 `event:`/`id:` 字段，只有 `data:` 行；
- 类型靠前缀区分：`Agent思考:` = 思考过程，`Agent结果:` = 最终结果；
- 无结束标记，靠后端 `emitter.complete()` 关闭连接判定结束；
- `TextDecoder({ stream: true })` 处理 UTF-8 字符被 chunk 截断的边界。

## 面试可展开的三个底层点

1. **HTTP 分块传输**：`resp.body.getReader()` 逐 chunk 读，响应不是一次性到达。
2. **SSE 消息格式**：`data:` 行 + 空行 `\n\n` 分隔事件，`event:`/`id:` 是可选字段。
3. **UTF-8 边界**：一个中文字符占 3 字节，可能被切成两半，`TextDecoder` 流式解码跨 chunk 拼接。
