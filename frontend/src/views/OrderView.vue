<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getOrderList } from '@/api/shop'
import type { OrderVO } from '@/types'

const router = useRouter()
const orders = ref<OrderVO[]>([])
const loading = ref(false)

interface OrderItem {
  name: string
  count: number
  image?: string
}

/** 把订单里的菜品与套餐合并成统一的明细列表 */
function itemsOf(o: OrderVO): OrderItem[] {
  const arr: OrderItem[] = []
  Object.entries(o.dishes || {}).forEach(([name, count]) => {
    arr.push({ name, count, image: o.dishesImage?.[name] })
  })
  Object.entries(o.setmeals || {}).forEach(([name, count]) => {
    arr.push({ name, count, image: o.setmealsImage?.[name] })
  })
  return arr
}

async function load() {
  loading.value = true
  try {
    const list = await getOrderList()
    orders.value = [...list].sort((a, b) => (a.orderTime < b.orderTime ? 1 : -1))
  } catch (e) {
    // 错误已统一处理
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="order-page">
    <div class="page-head">
      <h2>我的订单</h2>
      <el-button :icon="'Refresh'" @click="load">刷新</el-button>
    </div>

    <div class="order-list" v-loading="loading">
      <div v-for="o in orders" :key="o.number" class="order-card">
        <div class="order-head">
          <div class="order-no">
            <span class="no-label">订单号</span>
            <span class="no-value">{{ o.number }}</span>
          </div>
          <span class="order-time">{{ o.orderTime }}</span>
        </div>

        <div class="order-items">
          <div v-for="(it, i) in itemsOf(o)" :key="i" class="item">
            <div class="item-thumb">
              <img v-if="it.image" :src="it.image" :alt="it.name" loading="lazy" />
              <span v-else class="thumb-placeholder">🍜</span>
            </div>
            <span class="item-name">{{ it.name }}</span>
            <span class="item-count">× {{ it.count }}</span>
          </div>
        </div>

        <div class="order-foot">
          <div class="addr">
            <el-icon><Location /></el-icon>
            <span>{{ o.address || '暂无地址' }}</span>
            <span v-if="o.remark" class="remark">备注：{{ o.remark }}</span>
          </div>
          <div class="amount">
            实付 <span class="amount-value">¥{{ Number(o.amount).toFixed(2) }}</span>
          </div>
        </div>
      </div>

      <div v-if="!loading && orders.length === 0" class="empty">
        <div class="empty-icon">📋</div>
        <div>还没有订单</div>
        <div class="empty-sub">去「AI 助手」里说一句“帮我点一份宫保鸡丁”试试</div>
        <el-button type="primary" class="empty-btn" @click="router.push({ name: 'chat' })">
          去找 AI 点餐
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.order-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
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

.order-list {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 880px;
  width: 100%;
  margin: 0 auto;
}

.order-card {
  background: #fff;
  border-radius: 14px;
  border: 1px solid var(--border-2);
  overflow: hidden;
  box-shadow: var(--shadow-xs);
  transition: box-shadow 0.25s, transform 0.25s;
}

.order-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}

.order-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 18px;
  background: var(--bg-subtle);
  border-bottom: 1px solid var(--border-2);
}

.order-no {
  display: flex;
  align-items: center;
  gap: 8px;
}

.no-label {
  font-size: 12px;
  color: var(--text-3);
}

.no-value {
  font-size: 13px;
  color: var(--text-1);
  font-family: 'SF Mono', Consolas, monospace;
}

.order-time {
  font-size: 12px;
  color: var(--text-3);
}

.order-items {
  padding: 10px 18px;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: var(--bg-subtle);
  border-radius: 9px;
}

.item-thumb {
  width: 34px;
  height: 34px;
  border-radius: 7px;
  overflow: hidden;
  background: var(--bg-hover);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.item-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-placeholder {
  font-size: 16px;
}

.item-name {
  font-size: 13px;
  color: var(--text-1);
}

.item-count {
  font-size: 12px;
  color: var(--text-3);
}

.order-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-top: 1px solid var(--border-2);
}

.addr {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text-2);
}

.remark {
  color: var(--text-3);
  font-size: 12px;
}

.amount {
  font-size: 13px;
  color: var(--text-2);
  flex-shrink: 0;
}

.amount-value {
  color: var(--price);
  font-weight: 700;
  font-size: 18px;
  margin-left: 4px;
}

.empty {
  text-align: center;
  color: var(--text-3);
  padding: 80px 0;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 16px;
}

.empty-sub {
  font-size: 12px;
  color: var(--text-4);
  margin-top: 6px;
}

.empty-btn {
  margin-top: 24px;
}
</style>
