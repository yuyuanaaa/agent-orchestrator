<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const activeMenu = computed(() => route.path)

// 用户名首字母，用于头像展示
const avatarChar = computed(() => (userStore.userName || 'U').slice(0, 1).toUpperCase())

function handleMenuSelect(index: string) {
  if (index !== route.path) {
    router.push(index)
  }
}

function logout() {
  userStore.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="home-layout">
    <header class="topbar">
      <div class="brand" @click="router.push('/')">
        <div class="brand-icon">AI</div>
        <span class="brand-name">Agent-Orchestrator · 自研多智能体</span>
      </div>

      <el-menu
        mode="horizontal"
        :default-active="activeMenu"
        :ellipsis="false"
        class="nav-menu"
        @select="handleMenuSelect"
      >
        <el-menu-item index="/">
          <el-icon><ChatDotRound /></el-icon>
          <span>AI 助手</span>
        </el-menu-item>
        <el-menu-item index="/menu">
          <el-icon><Dish /></el-icon>
          <span>点餐菜单</span>
        </el-menu-item>
        <el-menu-item index="/orders">
          <el-icon><Tickets /></el-icon>
          <span>我的订单</span>
        </el-menu-item>
        <el-menu-item v-if="userStore.isAdmin" index="/admin/dish">
          <el-icon><Setting /></el-icon>
          <span>管理后台</span>
        </el-menu-item>
      </el-menu>

      <div class="user-area">
        <div class="avatar">{{ avatarChar }}</div>
        <span class="username">{{ userStore.userName }}</span>
        <el-tooltip content="退出登录" placement="bottom">
          <el-button :icon="'SwitchButton'" circle class="logout-btn" @click="logout" />
        </el-tooltip>
      </div>
    </header>

    <main class="content">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.home-layout {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #fff;
}

.topbar {
  height: 60px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  padding: 0 20px;
  background: #fff;
  border-bottom: 1px solid var(--border-1);
  box-shadow: var(--shadow-xs);
  gap: 28px;
  position: relative;
  z-index: 10;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  flex-shrink: 0;
}

.brand-icon {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: var(--brand-gradient);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--shadow-brand);
}

.brand-name {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-1);
  white-space: nowrap;
  letter-spacing: 0.3px;
}

.nav-menu {
  flex: 1;
  min-width: 0;
  border-bottom: none;
  height: 60px;
}
.nav-menu :deep(.el-menu-item) {
  height: 60px;
  line-height: 60px;
  font-weight: 500;
}
.nav-menu :deep(.el-menu-item.is-active) {
  font-weight: 600;
  color: var(--brand-500);
}
.nav-menu :deep(.el-menu-item:hover) {
  background: var(--bg-subtle);
}

.user-area {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--brand-gradient-soft);
  color: var(--brand-500);
  font-weight: 700;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border-2);
}

.username {
  color: var(--text-2);
  font-size: 14px;
  font-weight: 500;
}

.logout-btn {
  color: var(--text-3);
  border: none;
}
.logout-btn:hover {
  color: var(--danger);
  background: var(--price-soft);
}

.content {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
