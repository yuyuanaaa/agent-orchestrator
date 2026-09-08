<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '@/api/user'
import { useUserStore } from '@/stores/user'
import type { UserLoginDTO } from '@/types'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const form = reactive<UserLoginDTO>({
  userName: '',
  password: '',
})

async function handleLogin() {
  if (!form.userName.trim() || !form.password.trim()) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  // 与后端 UserFilePath 白名单保持一致：用户名仅支持字母/数字/下划线/短横线（1-64 位）
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(form.userName.trim())) {
    ElMessage.warning('用户名仅支持字母、数字、下划线、短横线（1-64 位）')
    return
  }
  loading.value = true
  try {
    const vo = await login({ userName: form.userName.trim(), password: form.password })
    userStore.setLogin(vo)
    ElMessage.success('登录成功')
    router.push({ name: 'chat' })
  } catch (e) {
    // 错误提示已在 axios 拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <!-- 左侧品牌区 -->
    <aside class="brand-panel">
      <div class="brand-inner">
        <div class="brand-logo">
          <el-icon :size="34"><Cpu /></el-icon>
        </div>
        <h1 class="brand-title">Agent-Orchestrator · 自研多智能体</h1>
        <p class="brand-desc">多智能体驱动的商家服务平台</p>

        <ul class="feature-list">
          <li>
            <el-icon><ChatDotRound /></el-icon>
            <span>自然语言对话点餐，自动识别菜品与套餐</span>
          </li>
          <li>
            <el-icon><Document /></el-icon>
            <span>上传 PDF 建立私有知识库，RAG 精准问答</span>
          </li>
          <li>
            <el-icon><DataAnalysis /></el-icon>
            <span>规划 / 执行 / 反思多智能体协作，可解释推理</span>
          </li>
        </ul>
      </div>
    </aside>

    <!-- 右侧登录表单 -->
    <main class="form-panel">
      <div class="form-card">
        <div class="form-head">
          <h2>欢迎回来</h2>
          <p>用户名不存在时将自动注册</p>
        </div>

        <el-form @submit.prevent="handleLogin">
          <el-form-item>
            <el-input
              v-model="form.userName"
              placeholder="用户名"
              size="large"
              :prefix-icon="'User'"
              clearable
            />
          </el-form-item>
          <el-form-item>
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码（至少 6 位）"
              size="large"
              :prefix-icon="'Lock'"
              show-password
              @keyup.enter="handleLogin"
            />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              size="large"
              class="login-btn"
              :loading="loading"
              @click="handleLogin"
            >
              登录 / 注册
            </el-button>
          </el-form-item>
        </el-form>
      </div>
    </main>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  background: #fff;
}

/* 左侧品牌区 */
.brand-panel {
  width: 46%;
  min-width: 360px;
  background: var(--brand-gradient);
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}

/* 装饰性光斑 */
.brand-panel::before,
.brand-panel::after {
  content: '';
  position: absolute;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.12);
}
.brand-panel::before {
  width: 380px;
  height: 380px;
  top: -120px;
  right: -80px;
}
.brand-panel::after {
  width: 260px;
  height: 260px;
  bottom: -90px;
  left: -60px;
  background: rgba(255, 255, 255, 0.08);
}

.brand-inner {
  position: relative;
  z-index: 1;
  padding: 48px;
  max-width: 420px;
}

.brand-logo {
  width: 64px;
  height: 64px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.2);
  backdrop-filter: blur(8px);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 28px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
}

.brand-title {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 0.5px;
  margin-bottom: 12px;
}

.brand-desc {
  font-size: 15px;
  opacity: 0.9;
  margin-bottom: 40px;
}

.feature-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.feature-list li {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14px;
  opacity: 0.95;
}
.feature-list .el-icon {
  font-size: 20px;
  flex-shrink: 0;
}

/* 右侧表单区 */
.form-panel {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-page);
  padding: 40px;
}

.form-card {
  width: 100%;
  max-width: 380px;
  background: #fff;
  border-radius: var(--radius-xl);
  padding: 44px 40px;
  box-shadow: var(--shadow-lg);
}

.form-head {
  margin-bottom: 30px;
}

.form-head h2 {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-1);
  margin-bottom: 8px;
}

.form-head p {
  font-size: 13px;
  color: var(--text-3);
}

.login-btn {
  width: 100%;
  font-weight: 600;
  letter-spacing: 1px;
}

/* 窄屏：隐藏品牌区 */
@media (max-width: 900px) {
  .brand-panel {
    display: none;
  }
}
</style>
