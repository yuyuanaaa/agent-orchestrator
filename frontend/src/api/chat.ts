import request from './request'

/** 查询当前用户的全部会话 id，返回 string[] */
export function getHistoryList(): Promise<string[]> {
  return request.get('/ai/chat/history')
}

/** 查询指定会话的历史消息，返回 List<String>，每条 "MessageType:文本" */
export function getHistoryByChatId(chatId: string): Promise<string[]> {
  return request.get(`/ai/chat/history/${chatId}`)
}

/** 删除指定会话的对话记忆与本地文件 */
export function removeHistory(chatId: string): Promise<string> {
  return request.delete(`/ai/chat/history/${chatId}`)
}
