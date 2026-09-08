import router from '@/router'
import { useUserStore } from '@/stores/user'

/**
 * 登录失效统一处理：清空本地登录态并跳转登录页。
 *
 * 在 axios 拦截器（普通 JSON 接口）、SSE 解析器（流式对话，不走 axios）等
 * 非组件环境收到 401 时调用，保证「未登录 / token 过期」时所有入口行为一致。
 * 跳转用 catch 吞掉 Vue Router 的冗余导航错误（同一时刻并发多个 401 时，
 * 后续 push 到同一 login 目标会返回 "Avoided redundant navigation"，属正常预期），
 * 且不依赖模块级持久状态，用户重新登录后再次 401 仍能正常跳转。
 */
export function handleUnauthorized(): void {
  const userStore = useUserStore()
  userStore.logout()
  // 已在登录页则无需重复跳转
  if (router.currentRoute.value.name !== 'login') {
    router.push({ name: 'login' }).catch(() => {})
  }
}
