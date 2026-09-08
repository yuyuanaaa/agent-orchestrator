---
name: dish_and_setmeal_query
domain: commerce
description: 帮助用户查询菜品或套餐信息，支持按口味、价格区间、分类筛选；当用户问“有什么吃的/喝的”时，必须先列出当前在售的菜品/套餐，再询问是否需要下单
compatibility: 需要访问菜品查询和套餐查询API
metadata:
author: dev-team
version: 1.1
---

## Additional Reference
- 各菜品口味参考：references/dish-flavor.md

## Parameters
| 参数名 | 类型 | 说明 |
|--------|------|------|
| taste | string | 口味偏好（麻辣、酸甜、清淡等，重要程度：低） |
| min_price | number | 最低价格（元，重要程度：低） |
| max_price | number | 最高价格（元，重要程度：低） |
| category | string | 菜品/套餐分类（重要程度：低） |

## Execution Flow
1. **解析意图**: 识别用户是查询特定菜品/套餐还是浏览某类菜品；若用户问“有什么吃的/喝的/推荐的”，属于浏览类查询
2. **提取筛选条件**: 仅提取用户明确提到的口味、价格、分类信息；对“喝的”应映射到分类“饮品”
3. **按口味筛选**: 用户提到麻辣、清淡、酸甜等口味时，先调用 loadReference(skillName="dish_and_setmeal_query", referenceFile="references/dish-flavor.md") 读取口味对照，再按匹配出的菜名调用 queryDish
4. **调用工具查询**: 根据查询类型调用 queryDish 或 querySetmeal，无法区分则同时调用；浏览类查询不要向用户索要地址电话
5. **展示结果**: 整理并返回菜品/套餐的名称、价格、分类、图片（前四个必须要有）等信息，用表格形式返回
6. **后续交互**: 仅当用户明确要下单时，再进入下单流程收集地址、电话等信息

## Related Tools
- queryDish, querySetmeal, loadReference, assignmentFinish

## Examples
- 这里有什么吃的
- 有什么喝的
- 帮我查一下麻辣口味的菜品
- 看看30元以下的单人套餐
- 有什么清淡的汤品推荐
- 查一下宫保鸡丁的价格
