---
name: place_order
domain: commerce
description: 帮助用户完成下单；如果用户还没决定买什么，必须先调用菜品/套餐查询工具列出选项，等用户确认具体商品后再收集配送信息并生成订单
compatibility: 需要访问菜品查询和订单生成API
metadata:
  author: dev-team
  version: 1.1
---

## Parameters
| 参数名 | 类型 | 说明 |
|--------|------|------|
| items | string[] | 用户想要购买的菜品或套餐名称列表（重要程度：中；未指定时应先查询菜单） |
| address | string | 配送地址（重要程度：高） |
| phone | string | 用户电话号码（重要程度：高） |
| note | string | 订单备注信息（重要程度：低） |

## Execution Flow
1. **确认商品**: 若用户未明确指定菜品/套餐，先使用 queryDish 或 querySetmeal 查询并展示菜单，供用户选择；禁止在未展示菜单前直接索要地址电话
2. **查询菜品或套餐详情**: 使用 queryDish 或 querySetmeal 查询用户最终确认的菜品/套餐的具体信息（名称、价格、图片等）
3. **收集配送信息**: 如果用户未提供配送地址或电话，主动询问并收集用户的地址、电话号码、备注（可选）
4. **生成订单**: 使用 makeOrder 生成订单，传入菜品/套餐信息、地址、电话、备注
5. **返回结果**: 将生成的订单信息（订单号、菜品、金额、配送地址等）展示给用户，并询问是否还有其他需要

## Related Tools
- queryDish, querySetmeal, makeOrder, assignmentFinish

## Examples
- 我要一份鱼香肉丝饭，送到科技园A座，电话13800138000
- 帮我下单：2份黄焖鸡米饭，地址是人民路15号，手机号139xxxx
- 我想点餐，有什么推荐的吗
- 有什么喝的？帮我点一份
