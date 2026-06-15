package com.ecommercebe.config;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Slf4j
public class LoggingAspect {

    // chặn tất cả method trong các class có tên kết thúc bằng Controller thuộc package com.ecommercebe.domain và các sub-package
    @Around("execution(* com.ecommercebe.domain..*Controller.*(..))")
    public Object logApi(ProceedingJoinPoint joinPoint) throws Throwable {
        String method = joinPoint.getSignature().toShortString();
        Object[] args = joinPoint.getArgs();

        log.info("-> {} args={}", method, args);
        long start = System.currentTimeMillis();

        Object result = joinPoint.proceed();

        long elapsed = System.currentTimeMillis() - start;
        log.info("<- {} = {} ({}ms)", method, args, elapsed);

        return result;
    }
}
