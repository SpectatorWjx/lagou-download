package com.example.lagoudownload.param;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * @author wjx
 * @description TODO
 * @date 2021/9/18
 */
@Data
public class CourseDownloadParam {
    @NotBlank(message = "课程id不能为空")
    private String courseId;

    private String cookie;
}
