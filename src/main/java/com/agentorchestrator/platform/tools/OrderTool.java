package com.agentorchestrator.platform.tools;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.agentorchestrator.platform.common.ErrorCode;
import com.agentorchestrator.platform.content.BaseContent;
import com.agentorchestrator.platform.entity.Dish;
import com.agentorchestrator.platform.entity.OrderDetail;
import com.agentorchestrator.platform.entity.Orders;
import com.agentorchestrator.platform.entity.Setmeal;
import com.agentorchestrator.platform.entity.dto.UserLoginDTO;
import com.agentorchestrator.platform.entity.querys.OrderQuery;
import com.agentorchestrator.platform.entity.vo.OrderVO;
import com.agentorchestrator.platform.exception.BusinessException;
import com.agentorchestrator.platform.mapper.OrderDetailMapper;
import com.agentorchestrator.platform.mapper.OrdersMapper;
import com.agentorchestrator.platform.service.DishService;
import com.agentorchestrator.platform.service.OrderDetailService;
import com.agentorchestrator.platform.service.OrdersService;
import com.agentorchestrator.platform.service.SetmealService;
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
import java.util.UUID;

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

    @Autowired
    private DishService dishService;

    @Autowired
    private SetmealService setmealService;

    /**
     * 订单明细中「该项不是菜品」时 dish_id 记的哨兵值。
     * <p>
     * 一条订单明细要么是菜品项、要么是套餐项：属于套餐项时 dish_id 记为本哨兵值，
     * 属于菜品项时 setmeal_id 记为 {@link #NOT_A_SETMEAL}。
     * <p>
     * 关键约定：判定某项是否为菜品一律用 {@code dishId > 0}（真实自增主键从 1 开始），
     * 而不要与某个具体哨兵值做相等比较。此前读取侧写的是 {@code == 0L}、
     * 写入侧写的却是 {@code -1L}，两端约定不一致导致两个分支恒为 false，
     * 订单详情的菜品/套餐信息永远回填为空。改用 {@code > 0} 后可同时兼容
     * 哨兵值为 -1 或 0 的历史数据，也让「什么算菜品」的语义只有一处定义。
     */
    private static final long NOT_A_DISH = -1L;

    /** 订单明细中「该项不是套餐」时 setmeal_id 记的哨兵值，语义见 {@link #NOT_A_DISH} */
    private static final long NOT_A_SETMEAL = -1L;

    /** 获取当前登录用户，工具在异步线程中执行时由 PlanExecute / ChatController 负责传递上下文 */
    private UserLoginDTO currentUser() {
        UserLoginDTO user = BaseContent.getUser();
        if (user == null || user.getId() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录后再操作订单");
        }
        return user;
    }

    @Tool(description = "商家服务平台生成订单工具，订单归属当前登录用户")
    @Transactional
    public OrderVO makeOrder(@ToolParam(description = "生成订单所需信息") OrderQuery orderQuery) {
        UserLoginDTO user = currentUser();

        boolean hasDish = orderQuery.getDishesName() != null && !orderQuery.getDishesName().isEmpty();
        boolean hasSetmeal = orderQuery.getSetmealsName() != null && !orderQuery.getSetmealsName().isEmpty();
        if (!hasDish && !hasSetmeal) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "订单至少需要包含一个菜品或套餐");
        }
        if (orderQuery.getAddress() == null || orderQuery.getAddress().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "订单缺少配送地址");
        }
        if (orderQuery.getPhone() == null || orderQuery.getPhone().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "订单缺少联系电话");
        }

        Orders orders = new Orders();
        // 订单号：UUID（去连字符），并发下唯一，不再用「时间戳+userId」这种伪唯一组合
        // （同一用户同一毫秒并发下单会撞号）
        orders.setNumber(UUID.randomUUID().toString().replace("-", ""));
        orders.setAddress(orderQuery.getAddress());
        orders.setPhone(orderQuery.getPhone());
        orders.setRemark(orderQuery.getRemark());
        orders.setOrderTime(LocalDateTime.now());
        orders.setUserId(user.getId());
        orders.setAddressBookId(-1L);
        orders.setStatus(1); // 新订单：待付款
        orders.setPayStatus(0); // 未支付
        orders.setUserName(user.getUserName());

        // 服务端按名称回查数据库，拿到真实单价/图片/主键，不信任模型传入的价格（防止模型算错价或虚构菜品）
        Map<String, Dish> dishMap = loadDishes(orderQuery.getDishesName());
        Map<String, Setmeal> setmealMap = loadSetmeals(orderQuery.getSetmealsName());

        // 遍历订单项，用数据库单价重算总额
        BigDecimal amount = BigDecimal.ZERO;
        if (hasDish) {
            for (String name : orderQuery.getDishesName()) {
                amount = amount.add(calcItemAmount(dishMap.get(name).getPrice(), orderQuery.getDishesNumber().get(name), name));
            }
        }
        if (hasSetmeal) {
            for (String name : orderQuery.getSetmealsName()) {
                amount = amount.add(calcItemAmount(setmealMap.get(name).getPrice(), orderQuery.getSetmealsNumber().get(name), name));
            }
        }
        orders.setAmount(amount);

        int rows = ordersMapper.insert(orders); // 自带主键回显
        if (rows <= 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "订单生成失败");
        }

        // 生成订单详细项：金额、图片、主键均以数据库为准，菜品/套餐通过主键真实关联
        if (hasDish) {
            for (String name : orderQuery.getDishesName()) {
                Dish dish = dishMap.get(name);
                OrderDetail orderDetail = new OrderDetail();
                orderDetail.setOrderId(orders.getId());
                orderDetail.setName(name);
                orderDetail.setDishId(dish.getId());
                orderDetail.setSetmealId(NOT_A_SETMEAL);
                orderDetail.setNumber(orderQuery.getDishesNumber().get(name));
                orderDetail.setImage(dish.getImage());
                orderDetail.setAmount(calcItemAmount(dish.getPrice(), orderQuery.getDishesNumber().get(name), name));
                insertOrderDetail(orderDetail);
            }
        }
        if (hasSetmeal) {
            for (String name : orderQuery.getSetmealsName()) {
                Setmeal setmeal = setmealMap.get(name);
                OrderDetail orderDetail = new OrderDetail();
                orderDetail.setOrderId(orders.getId());
                orderDetail.setName(name);
                orderDetail.setDishId(NOT_A_DISH);
                orderDetail.setSetmealId(setmeal.getId());
                orderDetail.setNumber(orderQuery.getSetmealsNumber().get(name));
                orderDetail.setImage(setmeal.getImage());
                orderDetail.setAmount(calcItemAmount(setmeal.getPrice(), orderQuery.getSetmealsNumber().get(name), name));
                insertOrderDetail(orderDetail);
            }
        }

        // 包装订单信息返回（图片同样以数据库为准，而非模型传入值）
        OrderVO orderVO = new OrderVO();
        BeanUtil.copyProperties(orders, orderVO);
        orderVO.setDishes(orderQuery.getDishesNumber());
        orderVO.setSetmeals(orderQuery.getSetmealsNumber());
        Map<String, String> dishImages = new HashMap<>();
        if (hasDish) {
            for (String name : orderQuery.getDishesName()) {
                dishImages.put(name, dishMap.get(name).getImage());
            }
        }
        Map<String, String> setmealImages = new HashMap<>();
        if (hasSetmeal) {
            for (String name : orderQuery.getSetmealsName()) {
                setmealImages.put(name, setmealMap.get(name).getImage());
            }
        }
        orderVO.setDishesImage(dishImages);
        orderVO.setSetmealsImage(setmealImages);

        return orderVO;
    }

    /**
     * 按名称批量回查菜品，构建 name -> Dish 映射。
     * 不信任模型传入的价格与图片，一切以数据库为准；查不到或已停售直接拒绝下单，
     * 防止模型算错价、虚构不存在的菜品或对停售商品下单。
     */
    private Map<String, Dish> loadDishes(List<String> names) {
        Map<String, Dish> map = new HashMap<>();
        if (names == null || names.isEmpty()) {
            return map;
        }
        for (Dish dish : dishService.query().in("name", names).list()) {
            map.put(dish.getName(), dish);
        }
        for (String name : names) {
            Dish dish = map.get(name);
            if (dish == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "菜品不存在或已下架: " + name);
            }
            if (dish.getStatus() != null && dish.getStatus() == 0) {
                throw new BusinessException(ErrorCode.BUSINESS_ERROR, "菜品已停售，无法下单: " + name);
            }
        }
        return map;
    }

    /** 同 {@link #loadDishes}，针对套餐 */
    private Map<String, Setmeal> loadSetmeals(List<String> names) {
        Map<String, Setmeal> map = new HashMap<>();
        if (names == null || names.isEmpty()) {
            return map;
        }
        for (Setmeal setmeal : setmealService.query().in("name", names).list()) {
            map.put(setmeal.getName(), setmeal);
        }
        for (String name : names) {
            Setmeal setmeal = map.get(name);
            if (setmeal == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "套餐不存在或已下架: " + name);
            }
            if (setmeal.getStatus() != null && setmeal.getStatus() == 0) {
                throw new BusinessException(ErrorCode.BUSINESS_ERROR, "套餐已停售，无法下单: " + name);
            }
        }
        return map;
    }

    /** 数据库单价 × 数量（数量缺失或非法视为参数不完整） */
    private BigDecimal calcItemAmount(BigDecimal dbPrice, Integer number, String name) {
        if (number == null || number <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "订单项缺少数量或数量非法: " + name);
        }
        return dbPrice.multiply(BigDecimal.valueOf(number));
    }

    private void insertOrderDetail(OrderDetail orderDetail) {
        int rows = orderDetailMapper.insert(orderDetail);
        if (rows <= 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "订单详细项生成失败");
        }
    }

    @Tool(description = "查询订单工具，只返回当前登录用户自己的订单，可按订单号或电话号码过滤")
    public List<OrderVO> queryOrder(
            @ToolParam(description = "要查询的订单号，可选；不传则返回该用户全部订单") String orderNumber,
            @ToolParam(description = "用于辅助过滤的电话号码，可选") String phone) {
        UserLoginDTO user = currentUser();

        // 归属校验：只查当前用户自己的订单，phone 仅作为附加过滤条件
        var query = ordersServiceImpl.query().eq("user_id", user.getId());
        if (orderNumber != null && !orderNumber.isBlank()) {
            query.eq("number", orderNumber);
        }
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

    @Tool(description = "取消订单工具，将订单标记为已取消并保留明细，只能取消当前登录用户自己的订单")
    @Transactional
    public OrderVO cancelOrder(@ToolParam(description = "要取消的订单号") String orderNumber) {
        UserLoginDTO user = currentUser();

        List<Orders> orders = ordersServiceImpl.query().eq("number", orderNumber).list();
        if (orders == null || orders.isEmpty()) return null;

        // 越权校验：订单必须归属当前登录用户
        Orders target = orders.get(0);
        if (!user.getId().equals(target.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作他人的订单");
        }

        if (target.getStatus() != null && target.getStatus() == 6) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "订单已取消，无需重复操作");
        }
        if (target.getStatus() != null && target.getStatus() == 7) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "订单已退款，无法取消");
        }
        if (target.getStatus() != null && target.getStatus() >= 4) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "订单已开始配送或已完成，无法取消");
        }

        List<OrderDetail> orderDetails = orderDetailServiceImpl.query().eq("order_id", target.getId()).list();

        LocalDateTime cancelTime = LocalDateTime.now();
        // 乐观更新：带上原 status 作为条件，避免并发请求把同一订单重复取消
        int rows = ordersMapper.update(null, new LambdaUpdateWrapper<Orders>()
                .eq(Orders::getId, target.getId())
                .eq(Orders::getStatus, target.getStatus() == null ? 1 : target.getStatus())
                .set(Orders::getStatus, 6)
                .set(Orders::getCancelTime, cancelTime)
                .set(Orders::getCancelReason, "用户取消"));
        if (rows <= 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "取消订单失败，请刷新后重试");
        }

        target.setStatus(6);
        target.setCancelTime(cancelTime);
        target.setCancelReason("用户取消");
        return toOrderVO(target, orderDetails);
    }

    private OrderVO toOrderVO(Orders o) {
        List<OrderDetail> orderDetails = orderDetailServiceImpl.query().eq("order_id", o.getId()).list();
        return toOrderVO(o, orderDetails);
    }

    /**
     * 包可见（而非 private）是为了让单元测试 {@code OrderToolTest} 能直接覆盖这段装配逻辑。
     * 它不依赖 Spring 容器，只做纯数据转换，历史上正是这里出过
     * 「写入侧哨兵值与读取侧判断条件不一致、导致详情永远回填为空」的缺陷。
     */
    OrderVO toOrderVO(Orders o, List<OrderDetail> orderDetails) {
        OrderVO orderVO = new OrderVO();
        BeanUtil.copyProperties(o, orderVO);

        Map<String, Integer> dishesNumber = new HashMap<>();
        Map<String, Integer> setmealsNumber = new HashMap<>();
        Map<String, String> dishesImage = new HashMap<>();
        Map<String, String> setmealsImage = new HashMap<>();

        for (OrderDetail od : orderDetails) {
            // 真实自增主键从 1 开始，故用 > 0 判定本项类型。
            // 哨兵值（-1 / 0）与 NULL 一律视为「非该类型」，不再与具体哨兵值做相等比较，
            // 避免再次出现写入侧与读取侧约定不一致、导致分支恒不命中、详情回填为空的问题。
            if (od.getDishId() != null && od.getDishId() > 0) {
                dishesNumber.put(od.getName(), od.getNumber());
                dishesImage.put(od.getName(), od.getImage());
            } else if (od.getSetmealId() != null && od.getSetmealId() > 0) {
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
