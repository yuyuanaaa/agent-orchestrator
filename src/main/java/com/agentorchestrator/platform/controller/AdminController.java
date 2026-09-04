package com.agentorchestrator.platform.controller;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.agentorchestrator.platform.cache.MenuCacheService;
import com.agentorchestrator.platform.common.ErrorCode;
import com.agentorchestrator.platform.common.Result;
import com.agentorchestrator.platform.constant.FileConstant;
import com.agentorchestrator.platform.entity.Category;
import com.agentorchestrator.platform.entity.Dish;
import com.agentorchestrator.platform.entity.Setmeal;
import com.agentorchestrator.platform.entity.SetmealDish;
import com.agentorchestrator.platform.entity.dto.SetmealAdminDTO;
import com.agentorchestrator.platform.entity.vo.SetmealAdminVO;
import com.agentorchestrator.platform.exception.BusinessException;
import com.agentorchestrator.platform.service.CategoryService;
import com.agentorchestrator.platform.service.DishService;
import com.agentorchestrator.platform.service.SetmealDishService;
import com.agentorchestrator.platform.service.SetmealService;
import com.agentorchestrator.platform.utils.HttpPathUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 管理端后台：分类 / 菜品 / 套餐的增删改查，以及图片上传。
 * <p>
 * 位于 /ai/** 之下，被 LoginInterceptor 拦截，仅登录用户可访问。
 * 图片落盘到 {@code tmp/upload/}（与 /upload/** 静态映射对应），image 字段统一存相对路径，
 * 查询返回时再拼成完整 URL，与对话链路（DishTool/SetmealTool）保持一致。
 */
@RestController
@RequestMapping("/ai/admin")
@Slf4j
public class AdminController {

    private static final Set<String> ALLOWED_IMAGE_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp");

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private DishService dishService;

    @Autowired
    private SetmealService setmealService;

    @Autowired
    private SetmealDishService setmealDishService;

    /**
     * 菜单查询缓存的失效入口。
     * <p>
     * 本类是菜单数据的<b>唯一写路径</b>，读路径（DishTool / SetmealTool）走 Cache-Aside。
     * 采用「先更新数据库、再删除缓存」的顺序：若反过来先删缓存，
     * 并发的读请求可能在事务提交前回源，把旧值重新写入缓存，形成长期脏数据。
     * 删除而非更新缓存，则保证操作幂等，且天然规避并发写导致的覆盖问题。
     */
    @Autowired
    private MenuCacheService menuCacheService;

    // ============================ 图片上传 ============================

    /** 上传菜品/套餐图片，落盘 tmp/upload/，返回可访问的完整 URL */
    @PostMapping("/image/upload")
    public Result<String> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "图片不能为空");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "只能上传图片文件");
        }
        String ext = "png";
        String original = file.getOriginalFilename();
        if (original != null && original.contains(".")) {
            ext = original.substring(original.lastIndexOf(".") + 1).toLowerCase();
        }
        if (!ALLOWED_IMAGE_EXT.contains(ext)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的图片格式，仅支持 jpg/jpeg/png/gif/webp");
        }

        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path dir = Paths.get(FileConstant.FILE_SAVE_DIR, "upload");
        try {
            Files.createDirectories(dir);
            Files.copy(file.getInputStream(), dir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("图片保存失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片保存失败");
        }
        return Result.success(HttpPathUtil.writeHttpUrl("/upload/" + fileName));
    }

    // ============================ 分类 ============================

    /** 分类列表，type 可选：1 菜品分类，2 套餐分类；不传则返回全部 */
    @GetMapping("/category/list")
    public Result<List<Category>> categoryList(@RequestParam(required = false) Integer type) {
        if (type == null) {
            return Result.success(categoryService.query().orderByAsc("sort", "id").list());
        }
        return Result.success(categoryService.query().eq("type", type).orderByAsc("sort", "id").list());
    }

    @PostMapping("/category")
    public Result<Void> addCategory(@RequestBody Category category) {
        if (category.getName() == null || category.getName().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类名称不能为空");
        }
        if (category.getType() == null || (category.getType() != 1 && category.getType() != 2)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类类型必须为 1（菜品）或 2（套餐）");
        }
        if (category.getStatus() == null) {
            category.setStatus(1);
        }
        category.setId(null);
        categoryService.save(category);
        // 分类名直接参与缓存 key 的拼接，新增分类后整体失效
        menuCacheService.evictAllMenuCache();
        return Result.success();
    }

    @PutMapping("/category")
    public Result<Void> updateCategory(@RequestBody Category category) {
        if (category.getId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "缺少分类 id");
        }
        if (category.getName() == null || category.getName().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类名称不能为空");
        }
        categoryService.updateById(category);
        // 分类改名会让既有缓存 key 的语义失效（key 中拼接的是分类名），整体清理
        menuCacheService.evictAllMenuCache();
        return Result.success();
    }

    @DeleteMapping("/category/{id}")
    public Result<Void> deleteCategory(@PathVariable Long id) {
        long dishCount = dishService.query().eq("category_id", id).count();
        long setmealCount = setmealService.query().eq("category_id", id).count();
        if (dishCount > 0 || setmealCount > 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "该分类下仍有菜品或套餐，请先清理后再删除");
        }
        categoryService.removeById(id);
        menuCacheService.evictAllMenuCache();
        return Result.success();
    }

    // ============================ 菜品 ============================

    @GetMapping("/dish/list")
    public Result<List<Dish>> dishList() {
        List<Dish> list = dishService.query().orderByAsc("id").list();
        list.forEach(d -> d.setImage(toFullUrl(d.getImage())));
        return Result.success(list);
    }

    @PostMapping("/dish")
    public Result<Void> addDish(@RequestBody Dish dish) {
        validateDish(dish);
        dish.setId(null);
        dish.setImage(normalizeImage(dish.getImage()));
        if (dish.getStatus() == null) {
            dish.setStatus(1);
        }
        dishService.save(dish);
        // 新增菜品会影响「查全部菜品」以及按菜品名过滤的套餐查询，两类缓存一并失效
        menuCacheService.evictDishCache();
        return Result.success();
    }

    @PutMapping("/dish")
    public Result<Void> updateDish(@RequestBody Dish dish) {
        if (dish.getId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "缺少菜品 id");
        }
        validateDish(dish);
        dish.setImage(normalizeImage(dish.getImage()));
        dishService.updateById(dish);
        // 改价 / 改名 / 停售都必须在用户侧立即生效，不能等 30 分钟 TTL 自然过期
        menuCacheService.evictDishCache();
        return Result.success();
    }

    @DeleteMapping("/dish/{id}")
    public Result<Void> deleteDish(@PathVariable Long id) {
        dishService.removeById(id);
        // 级联删除套餐中对该菜品的引用（setmeal_dish 有 name/price 冗余，删除菜品不影响历史订单展示）
        setmealDishService.remove(new LambdaQueryWrapper<SetmealDish>().eq(SetmealDish::getDishId, id));
        // 已下架菜品必须从查询结果中消失，否则用户仍可看到甚至下单
        menuCacheService.evictDishCache();
        return Result.success();
    }

    private void validateDish(Dish dish) {
        if (dish.getName() == null || dish.getName().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "菜品名称不能为空");
        }
        if (dish.getPrice() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "菜品价格不能为空");
        }
        if (dish.getCategoryId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择菜品分类");
        }
    }

    // ============================ 套餐 ============================

    @GetMapping("/setmeal/list")
    public Result<List<SetmealAdminVO>> setmealList() {
        List<Setmeal> list = setmealService.query().orderByAsc("id").list();
        List<SetmealAdminVO> vos = new ArrayList<>();
        for (Setmeal s : list) {
            SetmealAdminVO vo = new SetmealAdminVO();
            BeanUtil.copyProperties(s, vo);
            vo.setImage(toFullUrl(s.getImage()));
            vo.setDishes(setmealDishService.query().eq("setmeal_id", s.getId()).list());
            vos.add(vo);
        }
        return Result.success(vos);
    }

    @PostMapping("/setmeal")
    @Transactional
    public Result<Void> addSetmeal(@RequestBody SetmealAdminDTO dto) {
        validateSetmeal(dto);
        dto.setId(null);
        saveSetmeal(dto);
        menuCacheService.evictSetmealCache();
        return Result.success();
    }

    @PutMapping("/setmeal")
    @Transactional
    public Result<Void> updateSetmeal(@RequestBody SetmealAdminDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "缺少套餐 id");
        }
        validateSetmeal(dto);
        saveSetmeal(dto);
        // 套餐改价 / 调整所含菜品后，用户侧必须立即看到最新结果
        menuCacheService.evictSetmealCache();
        return Result.success();
    }

    @DeleteMapping("/setmeal/{id}")
    public Result<Void> deleteSetmeal(@PathVariable Long id) {
        setmealService.removeById(id);
        setmealDishService.remove(new LambdaQueryWrapper<SetmealDish>().eq(SetmealDish::getSetmealId, id));
        menuCacheService.evictSetmealCache();
        return Result.success();
    }

    private void validateSetmeal(SetmealAdminDTO dto) {
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "套餐名称不能为空");
        }
        if (dto.getPrice() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "套餐价格不能为空");
        }
        if (dto.getCategoryId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择套餐分类");
        }
    }

    /**
     * 保存套餐并重建套餐-菜品关联（先删旧关联，再插入新关联）。
     * <p>
     * 事务由两个 public 入口 {@link #addSetmeal} / {@link #updateSetmeal} 上的
     * {@code @Transactional} 保证——本方法是 private，Spring AOP 代理无法拦截
     * 类内部自调用，直接在这里加 {@code @Transactional} 不会生效。主表保存 +
     * 关联重建两步在同一事务内，任一步失败整体回滚，不留下脏数据。
     */
    private void saveSetmeal(SetmealAdminDTO dto) {
        Setmeal setmeal = new Setmeal();
        setmeal.setId(dto.getId());
        setmeal.setCategoryId(dto.getCategoryId());
        setmeal.setName(dto.getName());
        setmeal.setPrice(dto.getPrice());
        setmeal.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        setmeal.setDescription(dto.getDescription());
        setmeal.setImage(normalizeImage(dto.getImage()));

        if (dto.getId() == null) {
            setmealService.save(setmeal); // 主键自动回填
        } else {
            setmealService.updateById(setmeal);
        }

        Long setmealId = setmeal.getId();
        setmealDishService.remove(new LambdaQueryWrapper<SetmealDish>().eq(SetmealDish::getSetmealId, setmealId));
        if (dto.getDishes() != null) {
            for (SetmealDish sd : dto.getDishes()) {
                sd.setId(null);
                sd.setSetmealId(setmealId);
                setmealDishService.save(sd);
            }
        }
    }

    // ============================ 工具方法 ============================

    /** 相对路径拼完整 URL（已含 http 则原样返回） */
    private String toFullUrl(String image) {
        if (image == null || image.isBlank() || image.startsWith("http")) {
            return image;
        }
        return HttpPathUtil.writeHttpUrl("/upload/" + image);
    }

    /** 把完整 URL 或相对路径统一规范化为相对路径（去掉 /upload/ 前缀） */
    private String normalizeImage(String image) {
        if (image == null || image.isBlank()) {
            return null;
        }
        int idx = image.indexOf("/upload/");
        if (idx >= 0) {
            return image.substring(idx + "/upload/".length());
        }
        return image;
    }
}
