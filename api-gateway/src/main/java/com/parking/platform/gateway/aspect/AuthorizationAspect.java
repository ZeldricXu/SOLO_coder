package com.parking.platform.gateway.aspect;

import com.parking.platform.gateway.annotation.RequiresRole;
import com.parking.platform.gateway.service.AuthenticationService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

@Aspect
@Component
public class AuthorizationAspect {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationAspect.class);

    private final AuthenticationService authenticationService;

    public AuthorizationAspect(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Around("@within(com.parking.platform.gateway.annotation.RequiresRole) || @annotation(com.parking.platform.gateway.annotation.RequiresRole)")
    public Object checkAuthorization(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        RequiresRole annotation = method.getAnnotation(RequiresRole.class);
        if (annotation == null) {
            annotation = joinPoint.getTarget().getClass().getAnnotation(RequiresRole.class);
        }

        if (annotation != null) {
            List<String> requiredRoles = Arrays.asList(annotation.value());
            log.debug("Checking authorization for roles: {}", requiredRoles);

            if (annotation.logical() == RequiresRole.Logical.ALL) {
                authenticationService.authorizeAll(requiredRoles);
            } else {
                authenticationService.authorizeAny(requiredRoles);
            }
        }

        return joinPoint.proceed();
    }
}
