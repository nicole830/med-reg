package cn.zwz.basics.exception;

import cn.zwz.basics.baseVo.Result;
import cn.zwz.basics.utils.ResultUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：保证接口出错时也返回 JSON，而不是 HTTP 200 + 空响应
 * @author 郑为中
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ZwzException.class)
    public Result<Object> handleBusinessException(ZwzException e) {
        log.warn("业务异常：{}", e.getMessage());
        return ResultUtil.error(e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Result<Object> handleAccessDenied(AccessDeniedException e) {
        log.warn("权限不足：{}", e.getMessage());
        return ResultUtil.error(403, "无权限访问");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Object> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一索引冲突：{}", e.getMessage());
        return ResultUtil.error("数据已存在，请勿重复提交");
    }

    @ExceptionHandler(BindException.class)
    public Result<Object> handleBindException(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = fieldError == null ? "参数不合法" : fieldError.getDefaultMessage();
        return ResultUtil.error(msg);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = fieldError == null ? "参数不合法" : fieldError.getDefaultMessage();
        return ResultUtil.error(msg);
    }

    @ExceptionHandler(Exception.class)
    public Result<Object> handleException(Exception e) {
        log.error("系统异常", e);
        return ResultUtil.error("系统繁忙，请稍后再试");
    }
}
