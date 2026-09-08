package com.agentorchestrator.platform.controller;

import com.agentorchestrator.platform.common.Result;
import com.agentorchestrator.platform.entity.querys.DishQuery;
import com.agentorchestrator.platform.entity.querys.SetmealQuery;
import com.agentorchestrator.platform.entity.vo.DishVO;
import com.agentorchestrator.platform.entity.vo.OrderVO;
import com.agentorchestrator.platform.entity.vo.SetmealVO;
import com.agentorchestrator.platform.tools.DishTool;
import com.agentorchestrator.platform.tools.OrderTool;
import com.agentorchestrator.platform.tools.SetmealTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 前端商品浏览所需的只读查询接口。
 * <p>
 * 与对话链路共用同一套工具类（{@link DishTool} / {@link SetmealTool} / {@link OrderTool}），
 * 因此天然复用其中的缓存策略（Cache-Aside + 分布式锁）与图片 URL 拼接逻辑，
 * 不会出现「对话里查到的数据」和「页面上看到的数据」不一致的问题。
 * <p>
 * 这些接口位于 /ai/** 之下，会被 {@code LoginInterceptor} 拦截，仅登录用户可访问；
 * 订单查询由 {@link OrderTool} 内部按当前登录用户做归属过滤，天然防横向越权。
 */
@RestController
@RequestMapping("/ai/shop")
@Slf4j
public class ShopController {

    @Autowired
    private DishTool dishTool;

    @Autowired
    private SetmealTool setmealTool;

    @Autowired
    private OrderTool orderTool;

    /** 查询全部菜品（含停售标记，前端负责呈现），空条件命中「查全量」的缓存 key */
    @GetMapping("/dish/list")
    public Result<List<DishVO>> dishList() {
        return Result.success(dishTool.queryDish(new DishQuery()));
    }

    /** 查询全部套餐 */
    @GetMapping("/setmeal/list")
    public Result<List<SetmealVO>> setmealList() {
        return Result.success(setmealTool.querySetmeal(new SetmealQuery()));
    }

    /** 查询当前登录用户的全部订单（按时间倒序由前端排序） */
    @GetMapping("/order/list")
    public Result<List<OrderVO>> orderList() {
        return Result.success(orderTool.queryOrder(null, null));
    }
}
