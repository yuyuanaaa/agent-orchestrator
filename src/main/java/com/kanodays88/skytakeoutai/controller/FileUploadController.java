package com.kanodays88.skytakeoutai.controller;

import com.kanodays88.skytakeoutai.common.ErrorCode;
import com.kanodays88.skytakeoutai.common.Result;
import com.kanodays88.skytakeoutai.entity.vo.FileUploadVO;
import com.kanodays88.skytakeoutai.exception.BusinessException;
import com.kanodays88.skytakeoutai.service.FileUploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Objects;

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
}
