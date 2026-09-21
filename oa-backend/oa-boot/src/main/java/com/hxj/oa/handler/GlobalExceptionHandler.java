package com.hxj.oa.handler;

import com.hxj.oa.common.api.R;
import com.hxj.oa.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/** 全局异常处理：把异常统一转成 R，避免把堆栈暴露给前端 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return R.fail(400, msg.isEmpty() ? "参数校验未通过" : msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return R.fail(400, "缺少必填参数：" + e.getParameterName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleIllegalArgument(IllegalArgumentException e) {
        return R.fail(400, e.getMessage());
    }

    /**
     * 路径不存在 → 404。
     *
     * <p>必须单独接住：Spring 6.1 对未匹配的请求抛 {@link NoResourceFoundException}，
     * 若不拦就会被下面的 {@code Exception} 兜底接走，变成「HTTP 500 + 服务异常」，
     * 还会在 error 日志里留下一条"未处理异常"堆栈。后果是：任何爬虫/扫描器扫一遍不存在的路径，
     * 就能把 error 日志刷满、把监控的 500 率顶上天，真实故障被淹没。
     *
     * <p>这里是**客户端**的错误，不是服务端故障：用 debug 记一行即可，不记 error。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleNotFound(Exception e) {
        log.debug("请求路径不存在: {}", e.getMessage());
        return R.fail(404, "请求的资源不存在");
    }

    /**
     * 上传体积超过 multipart 上限。
     *
     * <p>这个异常在进入 controller 之前就被抛出来，如果不单独接住，就会落到下面的兜底分支变成
     * 「服务异常，请联系管理员」—— 用户完全不知道是自己文件太大，只会反复重试。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public R<Void> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        long max = e.getMaxUploadSize();
        String limit = max > 0
                ? String.format(java.util.Locale.ROOT, "%.0f MB", max / 1024.0 / 1024.0)
                : null;
        log.warn("上传超出上限 max={}", max);
        return R.fail(413, limit == null
                ? "文件超过大小上限，请压缩后重试"
                : "文件超过大小上限（" + limit + "），请压缩后重试");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return R.fail(500, "服务异常，请联系管理员");
    }
}
