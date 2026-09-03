package com.kanodays88.skytakeoutai.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 目录递归删除工具。
 * <p>
 * {@link File#delete()} 只能删除空目录，会话目录下会包含子目录与上传文件，因此这里统一用
 * 深度优先逆序删除（先删文件再删目录）的方式清理。
 */
@Slf4j
public final class DirectoryCleaner {

    private DirectoryCleaner() {
    }

    /**
     * 递归删除目录或文件，路径不存在时直接返回。
     *
     * @return 是否全部删除成功
     */
    public static boolean deleteRecursively(Path target) {
        if (target == null || !Files.exists(target)) {
            return false;
        }
        boolean allDeleted = true;
        try (Stream<Path> walk = Files.walk(target)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    allDeleted = false;
                    log.warn("删除失败: {}", path, e);
                }
            }
        } catch (IOException e) {
            allDeleted = false;
            log.warn("遍历目录失败: {}", target, e);
        }
        return allDeleted;
    }
}
