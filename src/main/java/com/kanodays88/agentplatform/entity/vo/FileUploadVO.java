package com.kanodays88.agentplatform.entity.vo;

import lombok.Data;

/**
 * 文件上传结果。
 * <p>
 * 失败信息统一由 {@link com.kanodays88.agentplatform.common.Result} 承载，这里只返回成功后的数据。
 */
@Data
public class FileUploadVO {

    /** 文件名 */
    private String fileName;

    /** 归属会话 id */
    private String chatId;
}
