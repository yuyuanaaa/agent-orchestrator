<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getHistoryList, removeHistory } from '@/api/chat'

const props = defineProps<{
  activeId: string
}>()

const emit = defineEmits<{
  (e: 'select', id: string): void
  (e: 'new-chat'): void
}>()

const list = ref<string[]>([])
const loading = ref(false)

async function loadList() {
  loading.value = true
  try {
    list.value = await getHistoryList()
  } catch (e) {
    // 错误已统一处理
  } finally {
    loading.value = false
  }
}

async function handleRemove(id: string) {
  try {
    await ElMessageBox.confirm('删除后该会话的对话记录与上传文件都会被清除，确认删除？', '提示', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await removeHistory(id)
    ElMessage.success('删除成功')
    await loadList()
    // 如果删的是当前会话，通知父组件新建
    if (props.activeId === id) {
      emit('new-chat')
    }
  } catch (e) {
    // 错误已统一处理
  }
}

onMounted(loadList)

// 暴露给父组件，让父组件在发送消息后能刷新列表
defineExpose({ loadList })
</script>

<template>
  <div class="sidebar">
    <el-button type="primary" class="new-btn" @click="emit('new-chat')">
      <el-icon><Plus /></el-icon>
      <span>新建对话</span>
    </el-button>

    <div class="list" v-loading="loading">
      <div
        v-for="id in list"
        :key="id"
        class="chat-item"
        :class="{ active: id === activeId }"
        @click="emit('select', id)"
      >
        <el-icon class="chat-icon"><ChatDotRound /></el-icon>
        <span class="chat-name" :title="id">{{ id }}</span>
        <el-icon class="del-icon" @click.stop="handleRemove(id)"><Delete /></el-icon>
      </div>

      <div v-if="!loading && list.length === 0" class="empty">
        <div class="empty-icon"><el-icon><ChatLineRound /></el-icon></div>
        <div class="empty-text">暂无历史会话</div>
        <div class="empty-sub">点击上方按钮开始对话</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sidebar {
  width: 264px;
  height: 100%;
  background: var(--bg-subtle);
  border-right: 1px solid var(--border-1);
  display: flex;
  flex-direction: column;
  padding: 16px 12px;
}

.new-btn {
  width: 100%;
  margin-bottom: 14px;
  font-weight: 600;
  height: 40px;
  border-radius: 10px;
}

.list {
  flex: 1;
  overflow-y: auto;
}

.chat-item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  color: var(--text-2);
  font-size: 13px;
  margin-bottom: 3px;
  transition: background 0.18s, color 0.18s;
  position: relative;
}

.chat-item:hover {
  background: var(--bg-hover);
}

.chat-item.active {
  background: #fff;
  color: var(--brand-500);
  box-shadow: var(--shadow-sm);
  font-weight: 500;
}

/* 活跃态左侧高亮条 */
.chat-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 2px;
  background: var(--brand-500);
}

.chat-icon {
  flex-shrink: 0;
  font-size: 15px;
}

.chat-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.del-icon {
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.2s, color 0.2s;
}

.chat-item:hover .del-icon {
  opacity: 1;
}

.del-icon:hover {
  color: var(--danger);
}

.empty {
  text-align: center;
  color: var(--text-4);
  padding: 60px 0;
}

.empty-icon {
  font-size: 40px;
  color: var(--border-1);
  margin-bottom: 12px;
}

.empty-text {
  font-size: 14px;
  color: var(--text-3);
  margin-bottom: 6px;
}

.empty-sub {
  font-size: 12px;
  color: var(--text-4);
}
</style>
