import axios, { type AxiosInstance } from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import type { ApiResult } from '@/types'

/**
 * axios 实例封装。
 *
 * 两个关键点：
 * 1. baseURL 用 '/api'，配合 vite.config.ts 的 proxy 转发到后端 8080（同时覆盖 context-path）
 * 2. 请求拦截器自动带上 token（后端通过 authorization 请求头识别登录态）
 * 3. 响应拦截器统一处理 Result<T>：code !== 200 视为业务失败，弹错误提示
 */
const instance: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

// 请求拦截器：注入 token
instance.interceptors.request.use((config) => {
  const userStore = useUserStore()
  if (userStore.token) {
    config.headers.Authorization = userStore.token
  }
  return config
})

// 响应拦截器：统一解包 Result<T> 并处理错误
instance.interceptors.response.use(
  (response) => {
    // SSE 流式接口不走这里，普通 JSON 接口走这里
    const body = response.data as ApiResult
    // 后端 ErrorCode.SUCCESS = 200，其他 code 均为失败
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code !== 200) {
        ElMessage.error(body.message || '请求失败')
        return Promise.reject(new Error(body.message))
      }
      // 直接返回 data，调用方拿到的就是业务数据
      return body.data as never
    }
    return body as never
  },
  (error) => {
    const status = error.response?.status
    const message =
      error.response?.data?.message ||
      (status === 401
        ? '未登录或登录已过期，请重新登录'
        : status === 404
          ? '接口不存在'
          : '网络请求失败')
    ElMessage.error(message)
    return Promise.reject(error)
  },
)

export default instance
