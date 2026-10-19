package io.github.jgjoe.byh.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps application and request-parsing exceptions to the shared {@link ApiError} body
 * (RQ-N-011). Every response stays free of exception class names, stack traces and
 * database error codes; unexpected failures are logged server-side and answered with a
 * generic message.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String ORDER_CREATION_PATH = "/api/orders";

    @ExceptionHandler(InvalidParameterException.class)
    public ResponseEntity<ApiError> handleInvalidParameter(InvalidParameterException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", exception.getMessage());
    }

    @ExceptionHandler(InvalidOrderException.class)
    public ResponseEntity<ApiError> handleInvalidOrder(InvalidOrderException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_ORDER", exception.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(UnauthorizedException exception) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", exception.getMessage());
    }

    @ExceptionHandler(LoginBusyException.class)
    public ResponseEntity<ApiError> handleLoginBusy(LoginBusyException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(new ApiError("TOO_MANY_REQUESTS", exception.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
    }

    /** Unreadable JSON, a missing body or a value that cannot be deserialized. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception,
                                                         HttpServletRequest request) {
        return requestProblem(request, "요청 본문의 형식이 올바르지 않습니다.");
    }

    /** A query parameter of the wrong type, e.g. {@code page=abc}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
                                                       HttpServletRequest request) {
        return requestProblem(request, "요청 파라미터 형식이 올바르지 않습니다.");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException exception,
                                                           HttpServletRequest request) {
        return requestProblem(request, "필수 요청 파라미터가 없습니다.");
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleUnknownRoute(Exception exception) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 경로를 찾을 수 없습니다.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 콘텐츠 타입입니다.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception while processing {} {}", request.getMethod(), request.getRequestURI(), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 오류가 발생했습니다.");
    }

    private static ResponseEntity<ApiError> requestProblem(HttpServletRequest request, String message) {
        return error(HttpStatus.BAD_REQUEST, errorCode(request), message);
    }

    /** Order creation reports {@code INVALID_ORDER}; every other endpoint reports {@code INVALID_PARAMETER}. */
    private static String errorCode(HttpServletRequest request) {
        boolean orderCreation = HttpMethod.POST.matches(request.getMethod())
                && ORDER_CREATION_PATH.equals(request.getRequestURI());
        return orderCreation ? "INVALID_ORDER" : "INVALID_PARAMETER";
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }
}
