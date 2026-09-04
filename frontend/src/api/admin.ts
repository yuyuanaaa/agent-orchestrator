import request from './request'
import type { Category, AdminDish, AdminSetmeal } from '@/types'

// ===== 图片上传 =====

/** 上传菜品/套餐图片，返回可访问的完整 URL */
export function uploadImage(file: File): Promise<string> {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/ai/admin/image/upload', formData)
}

// ===== 分类 =====

export function getCategoryList(type?: number): Promise<Category[]> {
  return request.get('/ai/admin/category/list', { params: type != null ? { type } : {} })
}

export function addCategory(data: Category): Promise<void> {
  return request.post('/ai/admin/category', data)
}

export function updateCategory(data: Category): Promise<void> {
  return request.put('/ai/admin/category', data)
}

export function deleteCategory(id: number): Promise<void> {
  return request.delete(`/ai/admin/category/${id}`)
}

// ===== 菜品 =====

export function getAdminDishList(): Promise<AdminDish[]> {
  return request.get('/ai/admin/dish/list')
}

export function addDish(data: AdminDish): Promise<void> {
  return request.post('/ai/admin/dish', data)
}

export function updateDish(data: AdminDish): Promise<void> {
  return request.put('/ai/admin/dish', data)
}

export function deleteDish(id: number): Promise<void> {
  return request.delete(`/ai/admin/dish/${id}`)
}

// ===== 套餐 =====

export function getAdminSetmealList(): Promise<AdminSetmeal[]> {
  return request.get('/ai/admin/setmeal/list')
}

export function addSetmeal(data: AdminSetmeal): Promise<void> {
  return request.post('/ai/admin/setmeal', data)
}

export function updateSetmeal(data: AdminSetmeal): Promise<void> {
  return request.put('/ai/admin/setmeal', data)
}

export function deleteSetmeal(id: number): Promise<void> {
  return request.delete(`/ai/admin/setmeal/${id}`)
}
