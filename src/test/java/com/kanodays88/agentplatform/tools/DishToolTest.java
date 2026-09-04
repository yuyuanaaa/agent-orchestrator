package com.kanodays88.agentplatform.tools;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.kanodays88.agentplatform.entity.Category;
import com.kanodays88.agentplatform.entity.Dish;
import com.kanodays88.agentplatform.service.CategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 菜品分类名批量装载的单元测试（不依赖 Spring 容器与数据库）。
 * <p>
 * <b>回归背景</b>：原实现在装配 VO 的循环里逐条 {@code eq("id", categoryId)} 查询分类，
 * 查 N 道菜就是 1 次菜品查询 + N 次分类查询（教科书级 N+1）。
 * 改为先对 categoryId 去重、再单条 IN 查询后，DB 往返固定为 2 次，与结果集大小无关。
 * <p>
 * 本测试锁定这条契约：<b>无论有多少道菜、多少个分类，分类查询只能发生一次</b>。
 */
class DishToolTest {

    private CategoryService categoryService;
    private QueryChainWrapper<Category> categoryWrapper;
    private DishTool dishTool;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        categoryService = mock(CategoryService.class);
        categoryWrapper = mock(QueryChainWrapper.class);
        dishTool = new DishTool();
        ReflectionTestUtils.setField(dishTool, "categoryServiceImpl", categoryService);
    }

    /** 桩掉 categoryService 的链式调用：query() -> select() -> in() -> list() */
    private void stubCategoryQuery(List<Category> categories) {
        when(categoryService.query()).thenReturn(categoryWrapper);
        when(categoryWrapper.select(anyString(), anyString())).thenReturn(categoryWrapper);
        when(categoryWrapper.in(eq("id"), anyCollection())).thenReturn(categoryWrapper);
        when(categoryWrapper.list()).thenReturn(categories);
    }

    // ==================== 测试数据构造 ====================

    private Dish dish(Long id, String name, Long categoryId) {
        Dish d = new Dish();
        d.setId(id);
        d.setName(name);
        d.setCategoryId(categoryId);
        return d;
    }

    private Category category(Long id, String name) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        return c;
    }

    // ==================== 测试用例 ====================

    @Test
    @DisplayName("多道菜跨多个分类时，分类查询只发生一次（回归 N+1）")
    void shouldLoadCategoryNamesInSingleQuery() {
        stubCategoryQuery(List.of(category(1L, "主食"), category(2L, "饮料")));

        List<Dish> dishes = List.of(
                dish(1L, "米饭", 1L),
                dish(2L, "面条", 1L),
                dish(3L, "饺子", 1L),
                dish(4L, "可乐", 2L),
                dish(5L, "雪碧", 2L)
        );

        Map<Long, String> map = dishTool.loadCategoryNameMap(dishes);

        assertEquals("主食", map.get(1L));
        assertEquals("饮料", map.get(2L));
        // 关键断言：5 道菜、2 个分类，分类查询固定 1 次，与结果集大小无关
        verify(categoryService, times(1)).query();
    }

    @Test
    @DisplayName("同一分类的多道菜应去重，IN 查询只带唯一的分类 id")
    void shouldDeduplicateCategoryIds() {
        stubCategoryQuery(List.of(category(1L, "主食")));

        dishTool.loadCategoryNameMap(List.of(
                dish(1L, "米饭", 1L),
                dish(2L, "面条", 1L),
                dish(3L, "饺子", 1L)
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<?>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(categoryWrapper, times(1)).in(eq("id"), captor.capture());
        assertEquals(1, captor.getValue().size(), "3 道菜同属一个分类，去重后应只剩 1 个 id");
        assertTrue(captor.getValue().contains(1L));
    }

    @Test
    @DisplayName("结果集为空时不发起任何分类查询，避免无意义的 DB 往返")
    void shouldSkipQueryWhenResultEmpty() {
        Map<Long, String> map = dishTool.loadCategoryNameMap(List.of());

        assertTrue(map.isEmpty());
        verify(categoryService, never()).query();
    }

    @Test
    @DisplayName("categoryId 为 null 的菜品应被过滤，不进入 IN 条件")
    void shouldFilterNullCategoryId() {
        stubCategoryQuery(List.of(category(2L, "饮料")));

        Map<Long, String> map = dishTool.loadCategoryNameMap(List.of(
                dish(1L, "孤儿菜品", null),
                dish(2L, "可乐", 2L)
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<?>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(categoryWrapper, times(1)).in(eq("id"), captor.capture());
        assertEquals(1, captor.getValue().size(), "null 分类不应进入查询条件");
        assertEquals("饮料", map.get(2L));
    }
}
