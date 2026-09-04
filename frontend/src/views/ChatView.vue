<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ChatSidebar from '@/components/ChatSidebar.vue'
import MessageBubble from '@/components/MessageBubble.vue'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import { streamSSE } from '@/utils/sse'
import { getHistoryByChatId } from '@/api/chat'
import { uploadPdf, getFileList, downloadPdf, deleteFile } from '@/api/upload'
import { getDishList } from '@/api/shop'
import type { DishVO } from '@/types'

const router = useRouter()
const userStore = useUserStore()
const chatStore = useChatStore()

// 消息列表，每条消息包含角色、类型（think/result）、文本
interface Msg {
  role: 'user' | 'assistant'
  type?: 'think' | 'result'
  text: string
}

const messages = ref<Msg[]>([])
const inputText = ref('')
const sending = ref(false)
const chatBoxRef = ref<HTMLElement>()
const sidebarRef = ref()
// 当前会话已上传的 PDF 文件名列表
const files = ref<string[]>([])
// 右侧菜单推荐面板
const menuOpen = ref(true)
const menuDishes = ref<DishVO[]>([])

// 打字机效果：把最终结果逐字渲染
let typingTimer: ReturnType<typeof setInterval> | null = null

function scrollBottom() {
  nextTick(() => {
    chatBoxRef.value?.scrollTo({ top: chatBoxRef.value.scrollHeight })
  })
}

async function newChat() {
  chatStore.newChat()
  messages.value = []
  files.value = []
  sidebarRef.value?.loadList?.()
}

async function loadFiles() {
  if (!chatStore.currentChatId) {
    files.value = []
    return
  }
  try {
    files.value = await getFileList(chatStore.currentChatId)
  } catch (e) {
    // 错误已统一处理
  }
}

async function loadMenu() {
  try {
    menuDishes.value = await getDishList()
  } catch (e) {
    // 错误已统一处理
  }
}

async function selectChat(id: string) {
  chatStore.setChatId(id)
  messages.value = []
  loadFiles()
  // 拉取该会话的历史消息
  try {
    const history = await getHistoryByChatId(id)
    messages.value = history.map((raw) => {
      const idx = raw.indexOf(':')
      const type = raw.slice(0, idx)
      const text = raw.slice(idx + 1)
      return { role: type === 'USER' ? 'user' : 'assistant', text }
    })
    scrollBottom()
  } catch (e) {
    // 错误已统一处理
  }
}

async function handleUpload(file: File) {
  if (!chatStore.currentChatId) {
    chatStore.newChat()
  }
  try {
    const vo = await uploadPdf(chatStore.currentChatId, file)
    ElMessage.success(`已上传：${vo.fileName}`)
    loadFiles()
    sidebarRef.value?.loadList?.()
  } catch (e) {
    // 错误已统一处理
  }
}

async function downloadFile(fileName: string) {
  if (!chatStore.currentChatId) return
  try {
    await downloadPdf(chatStore.currentChatId, fileName)
  } catch (e) {
    ElMessage.error((e as Error).message || '下载失败')
  }
}

async function removeFile(fileName: string) {
  if (!chatStore.currentChatId) return
  try {
    await ElMessageBox.confirm(`确定删除「${fileName}」吗？删除后该文档的向量也将被移除。`, '删除文档', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    // 用户取消
    return
  }
  try {
    await deleteFile(chatStore.currentChatId, fileName)
    ElMessage.success(`已删除：${fileName}`)
    loadFiles()
  } catch (e) {
    ElMessage.error((e as Error).message || '删除失败')
  }
}

async function send() {
  const text = inputText.value.trim()
  if (!text || sending.value) return

  // 首次对话自动生成会话 id
  if (!chatStore.currentChatId) {
    chatStore.newChat()
  }

  messages.value.push({ role: 'user', text })
  inputText.value = ''
  sending.value = true
  scrollBottom()

  // 构造请求地址：普通对话 / RAG 问答
  const base = chatStore.ragMode ? '/api/ai/chat/rag/' : '/api/ai/chat/'
  const url = base + encodeURIComponent(text)

  // 当前正在流式追加的消息引用
  let thinkMsg: Msg | null = null
  let resultMsg: Msg | null = null
  let finalResult = ''

  try {
    await streamSSE({
      baseUrl: url,
      headers: {
        chatId: chatStore.currentChatId,
        // 关键：SSE 走的是原生 fetch，不会经过 axios 拦截器，必须手动带上 token，
        // 否则后端 RefreshTokenInterceptor/LoginInterceptor 会判定未登录返回 401
        Authorization: userStore.token,
      },
      onThink: (chunk) => {
        if (!thinkMsg) {
          messages.value.push({ role: 'assistant', type: 'think', text: '' })
          // 关键：从响应式数组取回代理对象。直接改 push 之前的普通对象引用不会触发视图更新
          thinkMsg = messages.value[messages.value.length - 1]
        }
        thinkMsg.text += chunk
        scrollBottom()
      },
      onResult: (result) => {
        finalResult = result
      },
      onDone: () => {
        // 最终结果做打字机渲染
        if (finalResult) {
          messages.value.push({ role: 'assistant', type: 'result', text: '' })
          // 取回响应式代理，否则打字机逐字写入不会反映到视图
          resultMsg = messages.value[messages.value.length - 1]
          typewrite(resultMsg, finalResult)
        }
        sending.value = false
        sidebarRef.value?.loadList?.()
      },
      onError: (err) => {
        ElMessage.error('对话失败：' + err.message)
        sending.value = false
      },
    })
  } catch (e) {
    sending.value = false
  }
}

function typewrite(target: Msg, text: string) {
  let i = 0
  target.text = ''
  typingTimer = setInterval(() => {
    if (i < text.length) {
      target.text += text[i]
      i++
      scrollBottom()
    } else {
      if (typingTimer) clearInterval(typingTimer)
      typingTimer = null
    }
  }, 25)
}

/** 右侧菜单卡片点餐联动：填入点餐指令并发送 */
function orderFromCard(item: DishVO) {
  if (item.status === 0) return
  const text = `帮我点一份「${item.name}」`
  inputText.value = text
  // 正在回复中则不打断，仅填入输入框，让用户稍后手动发送
  if (!sending.value) {
    send()
  }
}

// 欢迎页快捷指令
const suggestions = [
  { icon: 'Dish', label: '推荐几道招牌菜', text: '帮我推荐几道招牌菜' },
  { icon: 'Coffee', label: '来杯喝的', text: '有什么喝的？帮我点一份' },
  { icon: 'Document', label: '上传 PDF 问答', text: '我已上传 PDF，请基于文档回答问题' },
  { icon: 'Tickets', label: '查我的订单', text: '帮我查一下我的订单' },
]

function pickSuggestion(text: string) {
  inputText.value = text
  if (!sending.value) {
    send()
  }
}

onMounted(() => {
  if (!chatStore.currentChatId) {
    chatStore.newChat()
  }
  loadMenu()
  // 消费来自菜单页的点餐指令，自动发起点餐
  const pending = chatStore.consumePendingMessage()
  if (pending) {
    inputText.value = pending
    nextTick(() => send())
  }
})
</script>

<template>
  <div class="chat-layout">
    <ChatSidebar
      ref="sidebarRef"
      :active-id="chatStore.currentChatId"
      @select="selectChat"
      @new-chat="newChat"
    />

    <div class="chat-main">
      <!-- 顶栏 -->
      <div class="header">
        <div class="header-left">
          <span class="header-title">AI 对话</span>
          <el-switch
            v-model="chatStore.ragMode"
            active-text="RAG 知识库"
            inline-prompt
          />
        </div>
        <div class="header-right">
          <el-upload
            :show-file-list="false"
            :before-upload="(file: File) => { handleUpload(file); return false }"
            accept="application/pdf"
          >
            <el-button :icon="'Upload'" plain>上传 PDF</el-button>
          </el-upload>
          <el-button :icon="menuOpen ? 'ArrowRight' : 'Dish'" plain @click="menuOpen = !menuOpen">
            {{ menuOpen ? '收起菜单' : '菜单' }}
          </el-button>
        </div>
      </div>

      <!-- 已上传的知识库文档（可下载/删除） -->
      <div v-if="files.length" class="files-bar">
        <el-icon class="files-icon"><Document /></el-icon>
        <span class="files-label">已上传文档</span>
        <div v-for="(f, i) in files" :key="i" class="file-item">
          <span class="file-name" :title="f">{{ f }}</span>
          <el-icon class="file-download" title="下载" @click="downloadFile(f)">
            <Download />
          </el-icon>
          <el-icon class="file-delete" title="删除" @click="removeFile(f)">
            <Delete />
          </el-icon>
        </div>
      </div>

      <!-- 消息区 -->
      <div class="chat-box" ref="chatBoxRef">
        <MessageBubble
          v-for="(m, i) in messages"
          :key="i"
          :role="m.role"
          :type="m.type"
          :text="m.text"
          :streaming="sending && i === messages.length - 1 && m.type === 'result'"
        />
        <div v-if="messages.length === 0" class="welcome">
          <div class="welcome-icon">
            <el-icon><Cpu /></el-icon>
          </div>
          <div class="welcome-text">你好，我是你的 AI 点餐助手</div>
          <div class="welcome-sub">支持普通问答、基于 PDF 的 RAG 问答，以及自然语言点餐</div>
          <div class="suggestion-grid">
            <button
              v-for="s in suggestions"
              :key="s.label"
              class="suggestion-chip"
              @click="pickSuggestion(s.text)"
            >
              <el-icon><component :is="s.icon" /></el-icon>
              <span>{{ s.label }}</span>
            </button>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="input-area">
        <div class="input-box">
          <el-input
            v-model="inputText"
            type="textarea"
            :rows="3"
            resize="none"
            placeholder="输入问题，Enter 发送，Shift+Enter 换行；也可说“帮我点一份宫保鸡丁”"
            @keydown.enter.exact.prevent="send"
          />
          <div class="input-foot">
            <span class="input-hint">Enter 发送 · Shift+Enter 换行</span>
            <el-button
              type="primary"
              :icon="'Promotion'"
              :loading="sending"
              @click="send"
            >
              {{ sending ? '思考中' : '发送' }}
            </el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 右侧菜单推荐面板 -->
    <div v-if="menuOpen" class="menu-panel">
      <div class="menu-head">
        <span class="menu-title">推荐菜品</span>
        <span class="menu-more" @click="router.push({ name: 'menu' })">查看全部</span>
      </div>
      <div class="menu-list">
        <div
          v-for="d in menuDishes"
          :key="d.name"
          class="menu-card"
          :class="{ soldout: d.status === 0 }"
        >
          <div class="menu-thumb">
            <img v-if="d.image" :src="d.image" :alt="d.name" loading="lazy" />
            <span v-else class="menu-ph">🍜</span>
          </div>
          <div class="menu-info">
            <div class="menu-name" :title="d.name">{{ d.name }}</div>
            <div class="menu-price">¥{{ Number(d.price).toFixed(2) }}</div>
          </div>
          <el-button
            size="small"
            type="primary"
            circle
            class="menu-order"
            :disabled="d.status === 0"
            @click="orderFromCard(d)"
          >
            <el-icon><Plus /></el-icon>
          </el-button>
        </div>
        <div v-if="menuDishes.length === 0" class="menu-empty">暂无菜品</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-layout {
  height: 100%;
  display: flex;
  overflow: hidden;
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: #fff;
}

/* 顶栏 */
.header {
  height: 56px;
  border-bottom: 1px solid var(--border-1);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  flex-shrink: 0;
  background: #fff;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.header-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 已上传文档条 */
.files-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 9px 20px;
  border-bottom: 1px solid var(--border-1);
  background: var(--bg-subtle);
  flex-shrink: 0;
}

.files-icon {
  color: var(--brand-500);
  font-size: 15px;
}

.files-label {
  font-size: 12px;
  color: var(--text-3);
  font-weight: 600;
  margin-right: 4px;
}

.file-item {
  display: flex;
  align-items: center;
  gap: 6px;
  background: #fff;
  border: 1px solid var(--border-1);
  border-radius: 8px;
  padding: 3px 9px;
  max-width: 260px;
  box-shadow: var(--shadow-xs);
}

.file-name {
  font-size: 12px;
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-download {
  cursor: pointer;
  color: var(--brand-500);
  flex-shrink: 0;
  font-size: 14px;
}

.file-delete {
  cursor: pointer;
  color: var(--text-4);
  flex-shrink: 0;
  font-size: 14px;
}

.file-delete:hover {
  color: var(--danger);
}

/* 消息区 */
.chat-box {
  flex: 1;
  overflow-y: auto;
  padding: 28px 24px;
  background: linear-gradient(180deg, #fbfcff 0%, #fff 100%);
}

/* 欢迎屏 */
.welcome {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 20px;
}

.welcome-icon {
  width: 64px;
  height: 64px;
  border-radius: 20px;
  background: var(--brand-gradient);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 30px;
  margin-bottom: 20px;
  box-shadow: var(--shadow-brand);
}

.welcome-text {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-1);
  margin-bottom: 10px;
}

.welcome-sub {
  font-size: 13px;
  color: var(--text-3);
  margin-bottom: 30px;
}

.suggestion-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  width: 100%;
  max-width: 460px;
}

.suggestion-chip {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid var(--border-2);
  border-radius: 12px;
  cursor: pointer;
  font-size: 13px;
  color: var(--text-2);
  transition: all 0.2s ease;
  text-align: left;
}
.suggestion-chip:hover {
  border-color: var(--brand-500);
  color: var(--brand-500);
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}
.suggestion-chip .el-icon {
  font-size: 18px;
  color: var(--brand-500);
  flex-shrink: 0;
}

/* 输入区 */
.input-area {
  padding: 14px 20px 18px;
  flex-shrink: 0;
  background: #fff;
  border-top: 1px solid var(--border-1);
}

.input-box {
  max-width: 860px;
  margin: 0 auto;
  border: 1px solid var(--border-1);
  border-radius: 16px;
  padding: 12px 14px 10px;
  background: #fff;
  box-shadow: var(--shadow-sm);
  transition: border-color 0.2s, box-shadow 0.2s;
}
.input-box:focus-within {
  border-color: var(--brand-500);
  box-shadow: 0 0 0 3px rgba(51, 112, 255, 0.12);
}
.input-box :deep(.el-textarea__inner) {
  box-shadow: none;
  border: none;
  padding: 0;
  background: transparent;
  font-size: 14px;
  line-height: 1.6;
}

.input-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.input-hint {
  font-size: 12px;
  color: var(--text-4);
}

/* 右侧菜单面板 */
.menu-panel {
  width: 264px;
  flex-shrink: 0;
  border-left: 1px solid var(--border-1);
  background: var(--bg-subtle);
  display: flex;
  flex-direction: column;
}

.menu-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  border-bottom: 1px solid var(--border-2);
}

.menu-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}

.menu-more {
  font-size: 12px;
  color: var(--brand-500);
  cursor: pointer;
}
.menu-more:hover {
  color: var(--brand-600);
}

.menu-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.menu-card {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #fff;
  border: 1px solid var(--border-2);
  border-radius: 12px;
  padding: 9px;
  transition: box-shadow 0.2s, transform 0.2s;
}
.menu-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.menu-card.soldout {
  opacity: 0.55;
}

.menu-thumb {
  width: 48px;
  height: 48px;
  border-radius: 9px;
  overflow: hidden;
  background: var(--bg-subtle);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.menu-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.menu-ph {
  font-size: 22px;
}

.menu-info {
  flex: 1;
  min-width: 0;
}

.menu-name {
  font-size: 13px;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}

.menu-price {
  font-size: 13px;
  color: var(--price);
  font-weight: 700;
  margin-top: 3px;
}

.menu-order {
  flex-shrink: 0;
}

.menu-empty {
  text-align: center;
  color: var(--text-4);
  font-size: 13px;
  padding: 30px 0;
}
</style>
