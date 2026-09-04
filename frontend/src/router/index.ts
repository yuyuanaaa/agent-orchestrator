import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { requiresAuth: false },
    },
    {
      path: '/',
      component: () => import('@/views/HomeLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: '',
          name: 'chat',
          component: () => import('@/views/ChatView.vue'),
        },
        {
          path: 'menu',
          name: 'menu',
          component: () => import('@/views/MenuView.vue'),
        },
        {
          path: 'orders',
          name: 'orders',
          component: () => import('@/views/OrderView.vue'),
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
    {
      path: '/admin',
      component: () => import('@/views/admin/AdminLayout.vue'),
      meta: { requiresAuth: true, requiresAdmin: true },
      children: [
        {
          path: '',
          redirect: '/admin/dish',
        },
        {
          path: 'dish',
          name: 'admin-dish',
          component: () => import('@/views/admin/DishAdmin.vue'),
        },
        {
          path: 'setmeal',
          name: 'admin-setmeal',
          component: () => import('@/views/admin/SetmealAdmin.vue'),
        },
        {
          path: 'category',
          name: 'admin-category',
          component: () => import('@/views/admin/CategoryAdmin.vue'),
        },
      ],
    },
  ],
})

// 全局前置守卫：未登录跳转到登录页，非管理员访问管理端跳回主页
router.beforeEach((to) => {
  const userStore = useUserStore()
  if (to.meta.requiresAuth && !userStore.isLoggedIn) {
    return { name: 'login' }
  }
  // 管理端仅管理员可进入（后端另有 AdminInterceptor 兜底校验）
  if (to.meta.requiresAdmin && !userStore.isAdmin) {
    return { name: 'chat' }
  }
  // 已登录访问登录页则跳回主页
  if (to.name === 'login' && userStore.isLoggedIn) {
    return { name: 'chat' }
  }
  return true
})

export default router
