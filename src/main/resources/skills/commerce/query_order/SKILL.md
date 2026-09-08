---
name: query_order
domain: commerce
description: 帮助用户查询当前登录账号下的已有订单，支持按订单号或手机号过滤
compatibility: 需要访问订单查询API
metadata:
  author: dev-team
  version: 1.0
---

## Parameters
| 参数名 | 类型 | 说明 |
|--------|------|------|
| phone | string | 用户下单时使用的电话号码，可选的附加过滤条件（重要程度：低） |
| orderNumber | string | 具体要查询的订单号，可选；不提供则返回该账号全部订单（重要程度：低） |

## Execution Flow
1. **明确查询范围**: 订单归属当前登录用户，无需向用户索要手机号；仅当用户主动给出订单号或手机号时才作为过滤条件
2. **查询订单**: 使用 queryOrder 查询订单信息，传入用户给出的订单号和手机号（均可选）
3. **展示结果**: 将订单信息以表格形式展示给用户，包含订单号、菜品、金额、状态、下单时间等
4. **后续处理**: 询问用户是否需要取消订单或有其他需求

## Related Tools
- queryOrder, assignmentFinish

## Examples
- 查一下我的订单
- 我想查看订单状态，手机号是13800138000
- 帮我查一下订单号 20240501001 的进度
