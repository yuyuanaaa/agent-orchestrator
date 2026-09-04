<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getCategoryList, addCategory, updateCategory, deleteCategory } from '@/api/admin'
import type { Category } from '@/types'

const tab = ref<1 | 2>(1)
const list = ref<Category[]>([])
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | undefined>()
const form = ref<Category>({ type: 1, name: '', sort: 0, status: 1 })

const title = computed(() => (editingId.value ? '编辑分类' : '新增分类'))

async function load() {
  loading.value = true
  try {
    list.value = await getCategoryList(tab.value)
  } catch (e) {
    // 错误已统一处理
  } finally {
    loading.value = false
  }
}

function switchTab(t: 1 | 2) {
  tab.value = t
  load()
}

function openAdd() {
  editingId.value = undefined
  form.value = { type: tab.value, name: '', sort: 0, status: 1 }
  dialogVisible.value = true
}

function openEdit(row: Category) {
  editingId.value = row.id
  form.value = { ...row }
  dialogVisible.value = true
}

async function save() {
  if (!form.value.name.trim()) {
    ElMessage.warning('请输入分类名称')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateCategory({ ...form.value, id: editingId.value })
    } else {
      await addCategory(form.value)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } catch (e) {
    // 错误已统一处理
  } finally {
    saving.value = false
  }
}

async function remove(row: Category) {
  try {
    await ElMessageBox.confirm(`确认删除分类「${row.name}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteCategory(row.id!)
    ElMessage.success('删除成功')
    load()
  } catch (e) {
    // 错误已统一处理
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div class="tabs">
        <button class="tab" :class="{ active: tab === 1 }" @click="switchTab(1)">菜品分类</button>
        <button class="tab" :class="{ active: tab === 2 }" @click="switchTab(2)">套餐分类</button>
      </div>
      <el-button type="primary" :icon="'Plus'" @click="openAdd">新增分类</el-button>
    </div>

    <div class="page-body">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="name" label="分类名称" min-width="160" />
        <el-table-column prop="sort" label="排序" width="100" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>暂无分类</template>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="title" width="420px">
      <el-form label-width="80px">
        <el-form-item label="分类名称">
          <el-input v-model="form.name" placeholder="如：主食 / 饮品" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  background: #fff;
  border-bottom: 1px solid var(--border-1);
  box-shadow: var(--shadow-xs);
  flex-shrink: 0;
  position: relative;
  z-index: 5;
}

.tabs {
  display: flex;
  gap: 6px;
  background: var(--bg-subtle);
  padding: 4px;
  border-radius: 10px;
}

.tab {
  padding: 6px 18px;
  border-radius: 8px;
  border: none;
  background: transparent;
  color: var(--text-2);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.tab.active {
  background: #fff;
  color: var(--brand-500);
  box-shadow: var(--shadow-sm);
}

.page-body {
  flex: 1;
  overflow: auto;
  padding: 20px 24px;
  background: var(--bg-page);
}

.page-body :deep(.el-table) {
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: var(--shadow-xs);
}
</style>
