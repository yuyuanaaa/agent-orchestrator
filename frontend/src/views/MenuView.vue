<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getDishList, getSetmealList } from '@/api/shop'
import { useChatStore } from '@/stores/chat'
import type { DishVO, SetmealVO } from '@/types'

const router = useRouter()
const chatStore = useChatStore()

const dishes = ref<DishVO[]>([])
const setmeals = ref<SetmealVO[]>([])
const loading = ref(false)

const tab = ref<'dish' | 'setmeal'>('dish')
const activeCategory = ref('全部')

// 分类从当前 tab 的数据里聚合（categoryName 去重）
const categories = computed(() => {
  const list = tab.value === 'dish' ? dishes.value : setmeals.value
  const names = [...new Set(list.map((i) => i.categoryName).filter(Boolean))]
  return ['全部', ...names]
})

// 统一的卡片展示结构，避免 template 里对 DishVO/SetmealVO 联合类型做类型窄化
interface CardItem {
  name: string
  price: number
  categoryName: string
  image: string
  description?: string
  status?: number
  subText?: string
}

const filtered = computed<CardItem[]>(() => {
  const list = tab.value === 'dish' ? dishes.value : setmeals.value
  const raw = activeCategory.value === '全部' ? list : list.filter((i) => i.categoryName === activeCategory.value)
  const isSetmeal = tab.value === 'setmeal'
  return raw.map((i) => ({
    name: i.name,
    price: Number(i.price),
    categoryName: i.categoryName,
    image: i.image,
    description: i.description,
    status: i.status,
    subText: isSetmeal ? (i as SetmealVO).dishesName?.join('、') : undefined,
  }))
})

function switchTab(t: 'dish' | 'setmeal') {
  tab.value = t
  activeCategory.value = '全部'
}

function isSoldOut(item: CardItem): boolean {
  return item.status === 0
}

/** 点餐联动：跳转到 AI 助手并预填点餐指令 */
function orderWithAI(name: string) {
  chatStore.setPendingMessage(`帮我点一份「${name}」`)
  router.push({ name: 'chat' })
}

async function load() {
  loading.value = true
  try {
    const [d, s] = await Promise.all([getDishList(), getSetmealList()])
    dishes.value = d
    setmeals.value = s
  } catch (e) {
    // 错误已统一处理
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="menu-page">
    <!-- 顶部工具条 -->
    <div class="toolbar">
      <div class="tabs">
        <button class="tab" :class="{ active: tab === 'dish' }" @click="switchTab('dish')">
          菜品
        </button>
        <button class="tab" :class="{ active: tab === 'setmeal' }" @click="switchTab('setmeal')">
          套餐
        </button>
      </div>
      <div class="cats">
        <button
          v-for="c in categories"
          :key="c"
          class="cat"
          :class="{ active: activeCategory === c }"
          @click="activeCategory = c"
        >
          {{ c }}
        </button>
      </div>
    </div>

    <!-- 卡片网格 -->
    <div class="grid" v-loading="loading">
      <div v-for="item in filtered" :key="item.name" class="card">
        <div class="cover" :class="{ soldout: isSoldOut(item) }">
          <img v-if="item.image" :src="item.image" :alt="item.name" loading="lazy" />
          <div v-else class="cover-placeholder">{{ tab === 'dish' ? '🍜' : '🍱' }}</div>
          <span v-if="isSoldOut(item)" class="soldout-badge">已停售</span>
        </div>

        <div class="body">
          <div class="title-row">
            <span class="name">{{ item.name }}</span>
            <span class="price">¥{{ Number(item.price).toFixed(2) }}</span>
          </div>
          <div v-if="item.categoryName" class="cat-tag">{{ item.categoryName }}</div>
          <p v-if="item.description" class="desc">{{ item.description }}</p>
          <p v-else-if="item.subText" class="desc">含：{{ item.subText }}</p>

          <el-button
            type="primary"
            size="small"
            class="order-btn"
            :disabled="isSoldOut(item)"
            @click="orderWithAI(item.name)"
          >
            {{ isSoldOut(item) ? '已停售' : '让 AI 帮我点' }}
          </el-button>
        </div>
      </div>

      <div v-if="!loading && filtered.length === 0" class="empty">
        <div class="empty-icon">🍽️</div>
        <div>暂无{{ tab === 'dish' ? '菜品' : '套餐' }}数据</div>
        <div class="empty-sub">可在后台管理端添加菜品后刷新</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.menu-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
}

.toolbar {
  padding: 16px 24px 14px;
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
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
}

.tab {
  padding: 7px 20px;
  border-radius: 9px;
  border: 1px solid var(--border-1);
  background: #fff;
  color: var(--text-2);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.tab.active {
  background: var(--brand-500);
  border-color: var(--brand-500);
  color: #fff;
  box-shadow: var(--shadow-brand);
}

.cats {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.cat {
  padding: 5px 15px;
  border-radius: 16px;
  border: none;
  background: var(--bg-subtle);
  color: var(--text-2);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.cat:hover {
  background: var(--bg-hover);
}

.cat.active {
  background: var(--brand-gradient-soft);
  color: var(--brand-500);
  font-weight: 600;
}

.grid {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
  align-content: start;
}

.card {
  background: #fff;
  border-radius: 14px;
  overflow: hidden;
  border: 1px solid var(--border-2);
  display: flex;
  flex-direction: column;
  transition: box-shadow 0.25s, transform 0.25s;
}

.card:hover {
  box-shadow: var(--shadow-lg);
  transform: translateY(-3px);
  border-color: transparent;
}

.cover {
  position: relative;
  height: 140px;
  background: var(--bg-subtle);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.4s ease;
}

.card:hover .cover img {
  transform: scale(1.06);
}

.cover-placeholder {
  font-size: 42px;
}

.cover.soldout img {
  filter: grayscale(1);
  opacity: 0.5;
}

.soldout-badge {
  position: absolute;
  top: 10px;
  right: 10px;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 12px;
  padding: 3px 8px;
  border-radius: 6px;
}

.body {
  padding: 12px 14px 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
}

.title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.price {
  color: var(--price);
  font-weight: 700;
  font-size: 16px;
  flex-shrink: 0;
}

.cat-tag {
  align-self: flex-start;
  font-size: 12px;
  color: var(--brand-500);
  background: var(--brand-gradient-soft);
  padding: 2px 8px;
  border-radius: 5px;
}

.desc {
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.5;
  margin: 0;
  flex: 1;
}

.order-btn {
  width: 100%;
  margin-top: 4px;
}

.empty {
  grid-column: 1 / -1;
  text-align: center;
  color: #8a919f;
  padding: 60px 0;
}

.empty-icon {
  font-size: 44px;
  margin-bottom: 12px;
}

.empty-sub {
  font-size: 12px;
  margin-top: 6px;
}
</style>
