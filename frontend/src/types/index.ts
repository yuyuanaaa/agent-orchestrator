// 统一响应结构：对应后端 common/Result.java
// public record Result<T>(int code, String message, T data)
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
}

// 登录请求：对应 entity/dto/UserLoginDTO.java
export interface UserLoginDTO {
  id?: number
  userName: string
  phone?: string
  password: string
}

// 登录结果：对应 entity/vo/UserLoginVO.java
export interface UserLoginVO {
  token: string
  userId: number
  userName: string
  role?: string // admin / user
}

// 文件上传结果：对应 entity/vo/FileUploadVO.java
export interface FileUploadVO {
  fileName: string
  chatId: string
}

// 会话历史消息：后端 historyQueryByChatId 返回 List<String>，
// 每条格式为 "MessageType:文本"，如 "USER:你好" / "ASSISTANT:你好呀"
export interface ChatHistoryItem {
  type: 'USER' | 'ASSISTANT' | string
  text: string
}

// 菜品：对应 entity/vo/DishVO.java
export interface DishVO {
  name: string
  price: number
  categoryName: string
  image: string
  description?: string
  status?: number
}

// 套餐：对应 entity/vo/SetmealVO.java
export interface SetmealVO {
  name: string
  price: number
  categoryName: string
  image: string
  dishesName: string[]
  description?: string
  status?: number
}

// 订单：对应 entity/vo/OrderVO.java
export interface OrderVO {
  number: string
  phone: string
  orderTime: string
  address: string
  amount: number
  remark?: string
  dishes: Record<string, number>
  dishesImage: Record<string, string>
  setmeals: Record<string, number>
  setmealsImage: Record<string, string>
}

// ===== 管理端类型 =====

// 分类：对应 entity/Category.java
export interface Category {
  id?: number
  type: number // 1 菜品分类 2 套餐分类
  name: string
  sort?: number
  status?: number
}

// 管理端菜品：对应 entity/Dish.java（提交/列表通用）
export interface AdminDish {
  id?: number
  name: string
  categoryId: number
  price: number
  image?: string
  description?: string
  status?: number
}

// 套餐内菜品：对应 entity/SetmealDish.java
export interface SetmealDish {
  id?: number
  setmealId?: number
  dishId: number
  name: string
  price: number
  copies: number
}

// 管理端套餐：对应 entity/dto/SetmealAdminDTO.java / vo/SetmealAdminVO.java
export interface AdminSetmeal {
  id?: number
  categoryId: number
  name: string
  price: number
  status?: number
  description?: string
  image?: string
  dishes?: SetmealDish[]
}
