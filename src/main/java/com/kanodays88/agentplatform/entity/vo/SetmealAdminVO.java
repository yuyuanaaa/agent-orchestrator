package com.kanodays88.agentplatform.entity.vo;

import com.kanodays88.agentplatform.entity.SetmealDish;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 管理端套餐列表返回结构：套餐基本信息 + 套餐内菜品（image 为完整 URL）。
 */
@Data
public class SetmealAdminVO {
    private Long id;
    private Long categoryId;
    private String name;
    private BigDecimal price;
    /** 0 停售 1 起售 */
    private Integer status;
    private String description;
    /** 完整可访问的图片 URL */
    private String image;
    /** 套餐内菜品（含份数） */
    private List<SetmealDish> dishes;
}
