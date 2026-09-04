import request from './request'
import type { FileUploadVO } from '@/types'

/** 上传 PDF 到指定会话的知识库（用于 RAG 问答） */
export function uploadPdf(chatId: string, file: File): Promise<FileUploadVO> {
  const formData = new FormData()
  formData.append('file', file)
  // 不要手动指定 Content-Type：交给浏览器/axios 自动生成带 boundary 的 multipart 头，
  // 手动写死 'multipart/form-data' 会丢失 boundary，部分环境会导致后端解析文件失败
  return request.post(`/ai/upload/pdf/${chatId}`, formData)
}

/** 查询指定会话已上传的文件名列表 */
export function getFileList(chatId: string): Promise<string[]> {
  return request.get(`/ai/upload/list/${chatId}`)
}

/** 删除指定会话已上传的 PDF（同时清理磁盘文件与向量库向量） */
export function deleteFile(chatId: string, fileName: string): Promise<void> {
  return request.delete(`/ai/upload/${chatId}/${encodeURIComponent(fileName)}`)
}

/**
 * 下载指定会话的 PDF（返回 blob 后由前端触发浏览器保存）。
 * 注意：后端下载接口走登录校验，必须带 token，所以不能用 window.open（不会带 Authorization），
 * 这里用 axios 带 token 请求 blob 再手动触发下载。
 */
export async function downloadPdf(chatId: string, fileName: string): Promise<void> {
  const blob = await request.get(`/ai/upload/download/${chatId}/${encodeURIComponent(fileName)}`, {
    responseType: 'blob',
  })

  // 后端出错时（如文件不存在）会返回 Result JSON，此时 response.data 是被当成 blob 的 JSON 文本
  const data = blob as unknown as Blob
  if (data.type && data.type.includes('application/json')) {
    const text = await data.text()
    let message = '下载失败'
    try {
      message = JSON.parse(text)?.message || '下载失败'
    } catch {
      message = text || '下载失败'
    }
    throw new Error(message)
  }

  const url = URL.createObjectURL(data)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
