<script setup lang="ts">
// 单条消息气泡，支持用户消息 / 思考过程 / AI 结果三种形态
defineProps<{
  role: 'user' | 'assistant'
  type?: 'think' | 'result'
  text: string
  streaming?: boolean
}>()
</script>

<template>
  <div class="msg" :class="role">
    <!-- AI 头像 -->
    <div v-if="role === 'assistant'" class="avatar ai-avatar">
      <el-icon><Cpu /></el-icon>
    </div>

    <div class="content">
      <div v-if="role === 'user'" class="bubble user-bubble">{{ text }}</div>

      <div v-else-if="type === 'think'" class="think-block">
        <div class="think-head">
          <el-icon><Lightning /></el-icon>
          <span>思考过程</span>
        </div>
        <div class="think-text">{{ text }}</div>
      </div>

      <div v-else class="bubble ai-bubble">
        {{ text }}
        <span v-if="streaming" class="cursor"></span>
      </div>
    </div>

    <!-- 用户头像 -->
    <div v-if="role === 'user'" class="avatar user-avatar">
      <el-icon><UserFilled /></el-icon>
    </div>
  </div>
</template>

<style scoped>
.msg {
  margin-bottom: 20px;
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.msg.user {
  flex-direction: row-reverse;
}

.content {
  max-width: 75%;
  display: flex;
  flex-direction: column;
}

.msg.user .content {
  align-items: flex-end;
}

/* 头像 */
.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  margin-top: 2px;
}
.ai-avatar {
  background: var(--brand-gradient);
  color: #fff;
  box-shadow: var(--shadow-brand);
}
.user-avatar {
  background: var(--brand-gradient-soft);
  color: var(--brand-500);
  border: 1px solid var(--border-2);
}

/* 气泡 */
.bubble {
  padding: 11px 15px;
  border-radius: 14px;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.user-bubble {
  background: var(--brand-gradient);
  color: #fff;
  border-bottom-right-radius: 6px;
  box-shadow: 0 3px 10px rgba(51, 112, 255, 0.22);
}

.ai-bubble {
  background: #fff;
  color: var(--text-1);
  border: 1px solid var(--border-2);
  border-bottom-left-radius: 6px;
  box-shadow: var(--shadow-xs);
}

/* 思考过程 */
.think-block {
  background: var(--bg-subtle);
  border: 1px dashed var(--border-1);
  border-radius: 12px;
  padding: 10px 14px;
  font-size: 13px;
  color: var(--text-3);
}

.think-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-3);
  margin-bottom: 6px;
}
.think-head .el-icon {
  color: var(--warning);
}

.think-text {
  color: var(--text-3);
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
}

/* 打字机光标 */
.cursor {
  display: inline-block;
  width: 2px;
  height: 15px;
  background: var(--brand-500);
  vertical-align: -2px;
  margin-left: 2px;
  animation: blink 1s steps(1) infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}
</style>
