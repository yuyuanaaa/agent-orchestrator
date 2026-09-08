package com.agentorchestrator.platform.controller;

import com.agentorchestrator.platform.common.ErrorCode;
import com.agentorchestrator.platform.common.Result;
import com.agentorchestrator.platform.content.BaseContent;
import com.agentorchestrator.platform.entity.dto.UserLoginDTO;
import com.agentorchestrator.platform.entity.vo.FileUploadVO;
import com.agentorchestrator.platform.exception.BusinessException;
import com.agentorchestrator.platform.service.FileUploadService;
import com.agentorchestrator.platform.utils.UserFilePath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 知识库文档上传：PDF 落盘并写入向量库，供 RAG 问答按会话维度检索。
 */
@RestController
@RequestMapping("/ai/upload")
@Slf4j
public class FileUploadController {

    @Autowired
    private FileUploadService fileUploadService;

    /**
     * 上传 PDF 到指定会话的知识库
     */
    @PostMapping("/pdf/{chatId}")
    public Result<FileUploadVO> uploadPdf(@PathVariable String chatId, @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "上传文件不能为空");
        }
        if (!Objects.equals(file.getContentType(), "application/pdf")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "上传失败，文件格式必须是 pdf");
        }

        boolean success = fileUploadService.save(chatId, file.getResource());
        if (!success) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "后台保存文件失败，请重新上传");
        }

        FileUploadVO fileUploadVO = new FileUploadVO();
        fileUploadVO.setFileName(file.getOriginalFilename());
        fileUploadVO.setChatId(chatId);
        return Result.success(fileUploadVO);
    }

    /**
     * 查询指定会话已上传的文件名列表（仅返回文件名，含扩展名）
     */
    @GetMapping("/list/{chatId}")
    public Result<List<String>> listFiles(@PathVariable String chatId) {
        UserLoginDTO user = BaseContent.getUser();
        if (user == null || user.getUserName() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        // 统一走 UserFilePath 白名单校验（userName + chatId），
        // 避免 chatId 传 ../ 等片段越界枚举其他目录文件名
        File dir = UserFilePath.resolveSessionDir(user.getUserName(), chatId).toFile();
        if (!dir.exists() || !dir.isDirectory()) {
            return Result.success(List.of());
        }
        File[] files = dir.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            return Result.success(List.of());
        }
        return Result.success(Arrays.stream(files)
                .map(File::getName)
                .sorted()
                .collect(Collectors.toList()));
    }

    /**
     * 删除指定会话已上传的 PDF（同时清理磁盘文件与向量库中该文件的向量）。
     * <p>
     * 安全：路径按当前登录用户 userName 拼接隔离 + 文件名路径穿越校验，与下载接口保持一致。
     */
    @DeleteMapping("/{chatId}/{fileName}")
    public Result<Void> delete(@PathVariable String chatId, @PathVariable String fileName) {
        UserLoginDTO user = BaseContent.getUser();
        if (user == null || user.getUserName() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        // 防路径穿越：文件名不允许携带任何路径信息
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "非法文件名");
        }

        boolean success = fileUploadService.delete(chatId, fileName);
        if (!success) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "删除失败，请重试");
        }
        return Result.success();
    }

    /**
     * 下载指定会话已上传的 PDF。
     * <p>
     * 安全：路径按当前登录用户的 userName 拼接，天然隔离到「自己」的目录；
     * 同时对文件名做路径穿越校验（拒绝 ../、/、\），防止越权读取他人文件。
     */
    @GetMapping("/download/{chatId}/{fileName}")
    public ResponseEntity<Resource> download(@PathVariable String chatId, @PathVariable String fileName) {
        UserLoginDTO user = BaseContent.getUser();
        if (user == null || user.getUserName() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        // 防路径穿越：文件名不允许携带任何路径信息
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "非法文件名");
        }

        // 统一走 UserFilePath 白名单校验（userName + chatId），
        // 再对文件名做穿越校验，杜绝 chatId=../.. + fileName 越权读取他人/服务器文件
        Path baseDir = UserFilePath.resolveSessionDir(user.getUserName(), chatId);
        Path filePath = baseDir.resolve(fileName).normalize();
        if (!filePath.startsWith(baseDir)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "非法文件路径");
        }

        File file = filePath.toFile();
        if (!file.exists() || !file.isFile()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在或已被清理");
        }

        // RFC 5987 编码，兼容中文文件名
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(new FileSystemResource(file));
    }
}
