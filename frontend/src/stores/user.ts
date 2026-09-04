import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { UserLoginVO } from '@/types'

/**
 * 用户登录态管理。
 * token 持久化到 localStorage，刷新页面后仍保持登录。
 */
export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem('token') || '')
  const userId = ref<number>(Number(localStorage.getItem('userId')) || 0)
  const userName = ref<string>(localStorage.getItem('userName') || '')
  const role = ref<string>(localStorage.getItem('role') || 'user')

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => role.value === 'admin')

  function setLogin(vo: UserLoginVO) {
    token.value = vo.token
    userId.value = vo.userId
    userName.value = vo.userName
    role.value = vo.role || 'user'
    localStorage.setItem('token', vo.token)
    localStorage.setItem('userId', String(vo.userId))
    localStorage.setItem('userName', vo.userName)
    localStorage.setItem('role', role.value)
  }

  function logout() {
    token.value = ''
    userId.value = 0
    userName.value = ''
    role.value = 'user'
    localStorage.removeItem('token')
    localStorage.removeItem('userId')
    localStorage.removeItem('userName')
    localStorage.removeItem('role')
  }

  return { token, userId, userName, role, isLoggedIn, isAdmin, setLogin, logout }
})
