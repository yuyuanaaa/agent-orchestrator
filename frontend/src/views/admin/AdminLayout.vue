<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const activeMenu = computed(() => route.path)

const menus = [
  { path: '/admin/dish', icon: 'Dish', label: '菜品管理' },
  { path: '/admin/setmeal', icon: 'Food', label: '套餐管理' },
  { path: '/admin/category', icon: 'Menu', label: '分类管理' },
]

function go(path: string) {
  if (path !== route.path) router.push(path)
}
</script>

<template>
  <div class="admin-layout">
    <aside class="aside">
      <div class="aside-brand">
        <div class="brand-icon">管</div>
        <span class="brand-name">管理后台</span>
      </div>

      <nav class="nav">
        <div
          v-for="m in menus"
          :key="m.path"
          class="nav-item"
          :class="{ active: activeMenu === m.path }"
          @click="go(m.path)"
        >
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.label }}</span>
        </div>
      </nav>

      <div class="aside-foot">
        <el-button :icon="'Back'" text class="back-btn" @click="router.push('/')">
          返回前台
        </el-button>
      </div>
    </aside>

    <main class="admin-main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.admin-layout {
  height: 100%;
  display: flex;
  background: #f5f7fa;
}

.aside {
  width: 210px;
  flex-shrink: 0;
  background: #1f2733;
  display: flex;
  flex-direction: column;
}

.aside-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.brand-icon {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: var(--brand-gradient);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  box-shadow: var(--shadow-brand);
}

.brand-name {
  color: #fff;
  font-size: 15px;
  font-weight: 600;
}

.nav {
  flex: 1;
  padding: 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 9px;
  color: #c3c9d4;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
}

.nav-item.active {
  background: var(--brand-gradient);
  color: #fff;
  box-shadow: var(--shadow-brand);
}

.aside-foot {
  padding: 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.back-btn {
  width: 100%;
  color: #c3c9d4;
}

.back-btn:hover {
  color: #fff;
}

.admin-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
</style>
