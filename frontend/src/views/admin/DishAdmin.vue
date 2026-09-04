<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getAdminDishList,
  addDish,
  updateDish,
  deleteDish,
  uploadImage,
  getCategoryList,
} from '@/api/admin'
import type { AdminDish, Category } from '@/types'

const list = ref<AdminDish[]>([])
const categories = ref<Category[]>([])
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const uploading = ref(false)
const editingId = ref<number | undefined>()
const form = ref<AdminDish>({ name: '', categoryId: 0, price: 0, status: 1 })

const title = computed(() => (editingId.value ? '编辑菜品' : '新增菜品'))

function categoryName(id: number): string {
  return categories.value.find((c) => c.id === id)?.name || '—'
}

async function load() {
  loading.value = true
  try {
    list.value = await getAdminDishList()
  } catch (e) {
    // 错误已统一处理
  } finally {
    loading.value = false
  }
}

async function loadCategories() {
  try {
    categories.value = await getCategoryList(1)
  } catch (e) {
    // 错误已统一处理
  }
}

function openAdd() {
  editingId.value = undefined
  form.value = { name: '', categoryId: categories.value[0]?.id ?? 0, price: 0, status: 1 }
  dialogVisible.value = true
}

function openEdit(row: AdminDish) {
  editingId.value = row.id
  form.value = {
    name: row.name,
    categoryId: row.categoryId,
    price: row.price,
    image: row.image,
    description: row.description,
    status: row.status,
  }
  dialogVisible.value = true
}

/** 图片选择后手动上传（before-upload 返回 false 阻止默认行为） */
async function handleImage(file: File) {
  uploading.value = true
  try {
    const url = await uploadImage(file)
    form.value.image = url
    ElMessage.success('图片上传成功')
  } catch (e) {
    // 错误已统一处理
  } finally {
    uploading.value = false
  }
  return false
}

async function save() {
  if (!form.value.name.trim()) {
    ElMessage.warning('请输入菜品名称')
    return
  }
  if (!form.value.categoryId) {
    ElMessage.warning('请选择菜品分类')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateDish({ ...form.value, id: editingId.value })
    } else {
      await addDish(form.value)
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

async function remove(row: AdminDish) {
  try {
    await ElMessageBox.confirm(`确认删除菜品「${row.name}」？套餐中对该菜品的引用会一并移除`, '提示', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await deleteDish(row.id!)
    ElMessage.success('删除成功')
    load()
  } catch (e) {
    // 错误已统一处理
  }
}

onMounted(() => {
  load()
  loadCategories()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>菜品管理</h2>
      <el-button type="primary" :icon="'Plus'" @click="openAdd">新增菜品</el-button>
    </div>

    <div class="page-body">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column label="图片" width="80">
          <template #default="{ row }">
            <img v-if="row.image" :src="row.image" class="thumb" :alt="row.name" />
            <span v-else class="no-img">无</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="分类" width="120">
          <template #default="{ row }">{{ categoryName(row.categoryId) }}</template>
        </el-table-column>
        <el-table-column label="价格" width="110">
          <template #default="{ row }">
            <span class="price">¥{{ Number(row.price).toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '起售' : '停售' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>暂无菜品</template>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="title" width="520px">
      <el-form label-width="80px">
        <el-form-item label="图片">
          <el-upload
            :show-file-list="false"
            :before-upload="handleImage"
            accept="image/*"
            class="image-uploader"
          >
            <div class="uploader-inner" v-loading="uploading">
              <img v-if="form.image" :src="form.image" class="cover" />
              <div v-else class="upload-trigger">
                <el-icon><Plus /></el-icon>
                <span>上传图片</span>
              </div>
            </div>
          </el-upload>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="菜品名称" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.categoryId" placeholder="选择分类" style="width: 100%">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="价格">
          <el-input-number v-model="form.price" :min="0" :precision="2" :step="1" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="菜品描述" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="起售" inactive-text="停售" />
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

.page-head h2 {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-1);
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

.thumb {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  object-fit: cover;
}

.no-img {
  color: var(--text-4);
  font-size: 12px;
}

.price {
  color: var(--price);
  font-weight: 600;
}

.image-uploader :deep(.el-upload) {
  border: 1px dashed #d9d9d9;
  border-radius: 8px;
  cursor: pointer;
  overflow: hidden;
}

.uploader-inner {
  width: 120px;
  height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.cover {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.upload-trigger {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  color: #8a919f;
  font-size: 12px;
}
</style>
