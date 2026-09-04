package com.agentorchestrator.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.agentorchestrator.platform.entity.Dish;
import com.agentorchestrator.platform.service.DishService;
import com.agentorchestrator.platform.mapper.DishMapper;
import org.springframework.stereotype.Service;

/**
* @author Administrator
* @description 针对表【dish(菜品)】的数据库操作Service实现
* @createDate 2026-04-03 21:32:10
*/
@Service
public class DishServiceImpl extends ServiceImpl<DishMapper, Dish>
    implements DishService{

}




