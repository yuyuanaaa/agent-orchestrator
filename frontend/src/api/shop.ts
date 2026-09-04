import request from './request'
import type { DishVO, SetmealVO, OrderVO } from '@/types'

/** 查询全部菜品（含停售标记） */
export function getDishList(): Promise<DishVO[]> {
  return request.get('/ai/shop/dish/list')
}

/** 查询全部套餐 */
export function getSetmealList(): Promise<SetmealVO[]> {
  return request.get('/ai/shop/setmeal/list')
}

/** 查询当前登录用户的全部订单 */
export function getOrderList(): Promise<OrderVO[]> {
  return request.get('/ai/shop/order/list')
}
