import request from './request'
import type { UserLoginDTO, UserLoginVO } from '@/types'

/** 登录（用户名不存在时后端自动注册） */
export function login(data: UserLoginDTO): Promise<UserLoginVO> {
  return request.post('/ai/user/login', data)
}
