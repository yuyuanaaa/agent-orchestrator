package com.kanodays88.skytakeoutai.tools;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kanodays88.skytakeoutai.common.ErrorCode;
import com.kanodays88.skytakeoutai.content.BaseContent;
import com.kanodays88.skytakeoutai.entity.OrderDetail;
import com.kanodays88.skytakeoutai.entity.Orders;
import com.kanodays88.skytakeoutai.entity.dto.UserLoginDTO;
import com.kanodays88.skytakeoutai.entity.querys.OrderQuery;
import com.kanodays88.skytakeoutai.entity.vo.OrderVO;
import com.kanodays88.skytakeoutai.exception.BusinessException;
import com.kanodays88.skytakeoutai.mapper.OrderDetailMapper;
import com.kanodays88.skytakeoutai.mapper.OrdersMapper;
import com.kanodays88.skytakeoutai.service.OrderDetailService;
import com.kanodays88.skytakeoutai.service.OrdersService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单工具：下单、查询、删除。
 * <p>
 * 所有操作都绑定当前登录用户（从 BaseContent 获取），
 * 查询与删除均校验订单归属，防止横向越权。
 */
@Component
public class OrderTool {

    @Autowired
    private OrdersService ordersServiceImpl;

    @Autowired
    private OrdersMapper ordersMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private OrderDetailService orderDetailServiceImpl;

    /** 获取当前登录用户，工具在异步线程中执行时由 PlanExecute / ChatController 负责传递上下文 */
    private UserLoginDTO currentUser() {
        UserLoginDTO user = BaseContent.getUser();
        if (user == null || user.getId() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录后再操作订单");
        }
        return user;
    }

    @Tool(description = "外卖平台生成订单工具，订单归属当前登录用户")
    @Transactional
    public OrderVO makeOrder(@ToolParam(description = "生成订单所需信息") OrderQuery orderQuery) {
        UserLoginDTO user = currentUser();

        boolean hasDish = orderQuery.getDishesName() != null && !orderQuery.getDishesName().isEmpty();
        boolean hasSetmeal = orderQuery.getSetmealsName() != null && !orderQuery.getSetmealsName().isEmpty();
        if (!hasDish && !hasSetmeal) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "订单至少需要包含一个菜品或套餐");
        }

        Orders orders = new Orders();
        // 订单号：时间戳 + 用户 id，避免同一毫秒内不同用户订单号冲突
        orders.setNumber(System.currentTimeMillis() + "-" + user.getId());
        orders.setAddress(orderQuery.getAddress());
        orders.setPhone(orderQuery.getPhone());
        orders.setRemark(orderQuery.getRemark());
        orders.setOrderTime(LocalDateTime.now());
        orders.setUserId(user.getId());
        orders.setAddressBookId(-1L);

        // 遍历菜品和套餐，计算总额（价格或数量缺失视为参数不完整，直接拒绝）
        BigDecimal amount = BigDecimal.ZERO;
        if (hasDish) {
            for (String d : orderQuery.getDishesName()) {
                amount = amount.add(multiply(orderQuery.getDishesPrice().get(d), orderQuery.getDishesNumber().get(d), d));
            }
        }
        if (hasSetmeal) {
            for (String s : orderQuery.getSetmealsName()) {
                amount = amount.add(multiply(orderQuery.getSetmealsPrice().get(s), orderQuery.getSetmealsNumber().get(s), s));
            }
        }
        orders.setAmount(amount);

        int rows = ordersMapper.insert(orders); // 自带主键回显
        if (rows <= 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "订单生成失败");
        }

        // 生成订单详细项
        if (hasDish) {
            for (String d : orderQuery.getDishesName()) {
                OrderDetail orderDetail = new OrderDetail();
                orderDetail.setOrderId(orders.getId());
                orderDetail.setName(d);
                orderDetail.setDishId(0L);
                orderDetail.setSetmealId(-1L);
                orderDetail.setNumber(orderQuery.getDishesNumber().get(d));
                orderDetail.setImage(orderQuery.getDishesImage().get(d));
                orderDetail.setAmount(multiply(orderQuery.getDishesPrice().get(d), orderQuery.getDishesNumber().get(d), d));
                insertOrderDetail(orderDetail);
            }
        }
        if (hasSetmeal) {
            for (String s : orderQuery.getSetmealsName()) {
                OrderDetail orderDetail = new OrderDetail();
                orderDetail.setOrderId(orders.getId());
                orderDetail.setName(s);
                orderDetail.setDishId(-1L);
                orderDetail.setSetmealId(0L);
                orderDetail.setNumber(orderQuery.getSetmealsNumber().get(s));
                orderDetail.setImage(orderQuery.getSetmealsImage().get(s));
                orderDetail.setAmount(multiply(orderQuery.getSetmealsPrice().get(s), orderQuery.getSetmealsNumber().get(s), s));
                insertOrderDetail(orderDetail);
            }
        }

        // 包装订单信息返回
        OrderVO orderVO = new OrderVO();
        BeanUtil.copyProperties(orders, orderVO);
        orderVO.setDishes(orderQuery.getDishesNumber());
        orderVO.setSetmeals(orderQuery.getSetmealsNumber());
        orderVO.setDishesImage(orderQuery.getDishesImage());
        orderVO.setSetmealsImage(orderQuery.getSetmealsImage());

        return orderVO;
    }

    /** 单价 × 数量，缺失任一项视为参数不完整 */
    private BigDecimal multiply(BigDecimal price, Integer number, String name) {
        if (price == null || number == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "订单项缺少价格或数量: " + name);
        }
        return price.multiply(BigDecimal.valueOf(number));
    }

    private void insertOrderDetail(OrderDetail orderDetail) {
        int rows = orderDetailMapper.insert(orderDetail);
        if (rows <= 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "订单详细项生成失败");
        }
    }

    @Tool(description = "查询订单工具，只返回当前登录用户自己的订单，可按电话号码过滤")
    public List<OrderVO> queryOrder(@ToolParam(description = "用于查询订单的电话号码，可选") String phone) {
        UserLoginDTO user = currentUser();

        // 归属校验：只查当前用户自己的订单，phone 仅作为附加过滤条件
        var query = ordersServiceImpl.query().eq("user_id", user.getId());
        if (phone != null && !phone.isBlank()) {
            query.eq("phone", phone);
        }
        List<Orders> orders = query.list();

        List<OrderVO> orderVOS = new ArrayList<>();
        for (Orders o : orders) {
            orderVOS.add(toOrderVO(o));
        }
        return orderVOS;
    }

    @Tool(description = "删除订单工具，会返回删除的订单的信息，只能删除当前登录用户自己的订单")
    @Transactional
    public OrderVO removeOrder(@ToolParam(description = "删除订单对应的订单号") String orderNumber) {
        UserLoginDTO user = currentUser();

        List<Orders> orders = ordersServiceImpl.query().eq("number", orderNumber).list();
        if (orders == null || orders.isEmpty()) return null;

        // 越权校验：订单必须归属当前登录用户
        Orders target = orders.get(0);
        if (!user.getId().equals(target.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作他人的订单");
        }

        List<OrderDetail> orderDetails = orderDetailServiceImpl.query().eq("order_id", target.getId()).list();

        LambdaUpdateWrapper<OrderDetail> wrapper = new LambdaUpdateWrapper<>();
        int rows = orderDetailMapper.delete(wrapper.eq(OrderDetail::getOrderId, target.getId()));
        if (rows <= 0) throw new BusinessException(ErrorCode.SYSTEM_ERROR, "删除订单详细项失败");

        LambdaUpdateWrapper<Orders> wrapper1 = new LambdaUpdateWrapper<>();
        rows = ordersMapper.delete(wrapper1.eq(Orders::getNumber, orderNumber));
        if (rows <= 0) throw new BusinessException(ErrorCode.SYSTEM_ERROR, "删除订单失败");

        return toOrderVO(target, orderDetails);
    }

    private OrderVO toOrderVO(Orders o) {
        List<OrderDetail> orderDetails = orderDetailServiceImpl.query().eq("order_id", o.getId()).list();
        return toOrderVO(o, orderDetails);
    }

    private OrderVO toOrderVO(Orders o, List<OrderDetail> orderDetails) {
        OrderVO orderVO = new OrderVO();
        BeanUtil.copyProperties(o, orderVO);

        Map<String, Integer> dishesNumber = new HashMap<>();
        Map<String, Integer> setmealsNumber = new HashMap<>();
        Map<String, String> dishesImage = new HashMap<>();
        Map<String, String> setmealsImage = new HashMap<>();

        for (OrderDetail od : orderDetails) {
            if (od.getDishId() != null && od.getDishId() == 0L) {
                dishesNumber.put(od.getName(), od.getNumber());
                dishesImage.put(od.getName(), od.getImage());
            } else if (od.getSetmealId() != null && od.getSetmealId() == 0L) {
                setmealsNumber.put(od.getName(), od.getNumber());
                setmealsImage.put(od.getName(), od.getImage());
            }
        }
        orderVO.setDishes(dishesNumber);
        orderVO.setSetmeals(setmealsNumber);
        orderVO.setDishesImage(dishesImage);
        orderVO.setSetmealsImage(setmealsImage);
        return orderVO;
    }

}
