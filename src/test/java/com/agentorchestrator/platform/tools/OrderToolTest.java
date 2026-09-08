package com.agentorchestrator.platform.tools;

import com.agentorchestrator.platform.entity.OrderDetail;
import com.agentorchestrator.platform.entity.Orders;
import com.agentorchestrator.platform.entity.vo.OrderVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单详情装配的纯逻辑单元测试（不依赖 Spring 容器与数据库）。
 * <p>
 * <b>回归背景</b>：过去写入侧把「非本类型」的哨兵值记为 {@code -1L}，
 * 而读取侧的判断条件写的是 {@code dishId == 0L} / {@code setmealId == 0L}。
 * 两端约定不一致导致两个分支<b>恒不命中</b>，查询订单时
 * {@code dishes / setmeals} 永远是空 Map——用户下单成功后看不到自己买了什么。
 * <p>
 * 本测试锁定修复后的契约：判定「本项是不是菜品」统一用 {@code dishId > 0}，
 * 不再与任何具体哨兵值做相等比较，因此对 -1 / 0 / NULL 三种历史数据都能正确归类。
 */
class OrderToolTest {

    private final OrderTool orderTool = new OrderTool();

    // ==================== 测试数据构造 ====================

    private Orders sampleOrder() {
        Orders o = new Orders();
        o.setId(100L);
        o.setNumber("1756000000000-1");
        o.setUserId(1L);
        o.setPhone("13800138000");
        o.setAddress("测试配送地址");
        o.setAmount(new BigDecimal("58.00"));
        o.setOrderTime(LocalDateTime.of(2026, 9, 4, 11, 0));
        return o;
    }

    /** 菜品项明细：dish_id 为真实主键，setmeal_id 记为哨兵值 */
    private OrderDetail dishDetail(String name, Long dishId, Long setmealSentinel, int number, String image) {
        OrderDetail od = new OrderDetail();
        od.setName(name);
        od.setDishId(dishId);
        od.setSetmealId(setmealSentinel);
        od.setNumber(number);
        od.setImage(image);
        od.setAmount(new BigDecimal("38.00"));
        return od;
    }

    /** 套餐项明细：setmeal_id 为真实主键，dish_id 记为哨兵值 */
    private OrderDetail setmealDetail(String name, Long dishSentinel, Long setmealId, int number, String image) {
        OrderDetail od = new OrderDetail();
        od.setName(name);
        od.setDishId(dishSentinel);
        od.setSetmealId(setmealId);
        od.setNumber(number);
        od.setImage(image);
        od.setAmount(new BigDecimal("20.00"));
        return od;
    }

    // ==================== 测试用例 ====================

    @Test
    @DisplayName("订单详情必须回填菜品与套餐（回归：哨兵值约定不一致曾导致结果永远为空）")
    void shouldFillDishesAndSetmeals() {
        List<OrderDetail> details = List.of(
                dishDetail("宫保鸡丁", 12L, -1L, 2, "gongbao.png"),
                setmealDetail("双人套餐", -1L, 7L, 1, "combo.png")
        );

        OrderVO vo = orderTool.toOrderVO(sampleOrder(), details);

        assertEquals(Map.of("宫保鸡丁", 2), vo.getDishes(), "菜品数量未回填");
        assertEquals(Map.of("双人套餐", 1), vo.getSetmeals(), "套餐数量未回填");
        assertEquals(Map.of("宫保鸡丁", "gongbao.png"), vo.getDishesImage(), "菜品图片未回填");
        assertEquals(Map.of("双人套餐", "combo.png"), vo.getSetmealsImage(), "套餐图片未回填");
        // 订单主信息不应被装配逻辑破坏
        assertEquals("1756000000000-1", vo.getNumber());
        assertEquals(new BigDecimal("58.00"), vo.getAmount());
    }

    @Test
    @DisplayName("兼容哨兵值为 0 的历史数据（旧注释约定的是 0，修复后仍应正确归类）")
    void shouldTolerateLegacyZeroSentinel() {
        List<OrderDetail> details = List.of(
                dishDetail("鱼香肉丝", 3L, 0L, 1, "yuxiang.png"),
                setmealDetail("单人套餐", 0L, 9L, 2, "solo.png")
        );

        OrderVO vo = orderTool.toOrderVO(sampleOrder(), details);

        assertEquals(Map.of("鱼香肉丝", 1), vo.getDishes());
        assertEquals(Map.of("单人套餐", 2), vo.getSetmeals());
        // 反向校验：菜品不能被误归类进套餐，反之亦然
        assertTrue(vo.getDishes().getOrDefault("单人套餐", 0) == 0, "套餐被误判为菜品");
        assertTrue(vo.getSetmeals().getOrDefault("鱼香肉丝", 0) == 0, "菜品被误判为套餐");
    }

    @Test
    @DisplayName("dish_id / setmeal_id 为空时不抛异常，且不被误归类")
    void shouldHandleNullIdsGracefully() {
        OrderDetail od = new OrderDetail();
        od.setName("未知条目");
        od.setNumber(1);
        od.setImage("unknown.png");

        OrderVO vo = orderTool.toOrderVO(sampleOrder(), List.of(od));

        assertNotNull(vo.getDishes());
        assertNotNull(vo.getSetmeals());
        assertTrue(vo.getDishes().isEmpty(), "id 为空不应被归类为菜品");
        assertTrue(vo.getSetmeals().isEmpty(), "id 为空不应被归类为套餐");
    }

    @Test
    @DisplayName("明细列表为空时返回空集合而非 null")
    void shouldReturnEmptyMapsWhenNoDetails() {
        OrderVO vo = orderTool.toOrderVO(sampleOrder(), List.of());

        assertNotNull(vo.getDishes());
        assertNotNull(vo.getSetmeals());
        assertTrue(vo.getDishes().isEmpty());
        assertTrue(vo.getSetmeals().isEmpty());
    }

    @Test
    @DisplayName("取消订单的状态与取消时间应随订单主信息一起返回")
    void shouldCarryCancelStateIntoOrderVO() {
        Orders canceled = sampleOrder();
        canceled.setStatus(6);
        canceled.setCancelReason("用户取消");
        canceled.setCancelTime(LocalDateTime.of(2026, 9, 5, 12, 0));

        OrderVO vo = orderTool.toOrderVO(canceled, List.of());

        assertEquals(6, vo.getStatus());
        assertEquals("用户取消", vo.getCancelReason());
        assertNotNull(vo.getCancelTime());
    }

    @Test
    @DisplayName("多个菜品与多个套餐混合时应全部回填，不互相覆盖")
    void shouldFillMultipleItemsWithoutOverwriting() {
        List<OrderDetail> details = List.of(
                dishDetail("宫保鸡丁", 12L, -1L, 2, "gongbao.png"),
                dishDetail("鱼香肉丝", 3L, -1L, 1, "yuxiang.png"),
                setmealDetail("双人套餐", -1L, 7L, 1, "combo.png"),
                setmealDetail("单人套餐", -1L, 9L, 3, "solo.png")
        );

        OrderVO vo = orderTool.toOrderVO(sampleOrder(), details);

        assertEquals(2, vo.getDishes().size(), "两种菜品应分别保留");
        assertEquals(2, vo.getSetmeals().size(), "两种套餐应分别保留");
        assertEquals(2, vo.getDishes().get("宫保鸡丁"));
        assertEquals(1, vo.getDishes().get("鱼香肉丝"));
        assertEquals(1, vo.getSetmeals().get("双人套餐"));
        assertEquals(3, vo.getSetmeals().get("单人套餐"));
    }
}
