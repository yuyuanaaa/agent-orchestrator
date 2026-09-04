package com.agentorchestrator.platform.entity.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import com.agentorchestrator.platform.entity.SetmealDish;

/**
 * 管理端新增/编辑套餐的入参。
 * 套餐基本信息 + 套餐内菜品列表（一次提交，后端负责重建 setmeal_dish 关联）。
 */
@Data
public class SetmealAdminDTO {
    /** 编辑时必填，新增时为空 */
    private Long id;
    /** 所属套餐分类 id */
    private Long categoryId;
    private String name;
    private BigDecimal price;
    /** 0 停售 1 起售 */
    private Integer status;
    private String description;
    /** 图片：可为完整 URL 或相对路径，后端统一规范化为相对路径 */
    private String image;
    /** 套餐内菜品（含份数） */
    private List<SetmealDish> dishes;
}
