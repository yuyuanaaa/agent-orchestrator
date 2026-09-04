import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 聊天会话状态：当前选中的会话 id，以及 RAG 模式开关。
 */
export const useChatStore = defineStore('chat', () => {
  // 当前会话 id。后端 login 时不会自动生成 chatId，前端在用户首次对话时生成。
  const currentChatId = ref<string>('')
  // 是否走 RAG 问答（基于上传 PDF 的知识库）
  const ragMode = ref<boolean>(false)
  // 待发送消息：由菜单页等入口写入，聊天页挂载时消费（实现「点菜卡片 → 自动发起点餐」联动）
  const pendingMessage = ref<string>('')

  function newChat() {
    // 用时间戳生成一个简单的会话 id，也可用 crypto.randomUUID()
    currentChatId.value =
      typeof crypto !== 'undefined' && crypto.randomUUID
        ? crypto.randomUUID()
        : 'chat-' + Date.now()
    return currentChatId.value
  }

  function setChatId(id: string) {
    currentChatId.value = id
  }

  function toggleRag() {
    ragMode.value = !ragMode.value
  }

  function setPendingMessage(text: string) {
    pendingMessage.value = text
  }

  function consumePendingMessage(): string {
    const text = pendingMessage.value
    pendingMessage.value = ''
    return text
  }

  return { currentChatId, ragMode, pendingMessage, newChat, setChatId, toggleRag, setPendingMessage, consumePendingMessage }
})
