package com.education.agent.ratelimit;

import com.education.agent.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
  private final DatabaseRateLimiter limiter;
  private final boolean trustForwardedFor;
  private final int authLimit, feedbackLimit, notificationLimit, reportLimit, aiLimit, codeLimit;

  public RateLimitInterceptor(DatabaseRateLimiter limiter,
      @Value("${education.security.trust-forwarded-for:false}") boolean trustForwardedFor,
      @Value("${education.rate-limit.auth-per-minute:30}") int authLimit,
      @Value("${education.rate-limit.feedback-per-minute:20}") int feedbackLimit,
      @Value("${education.rate-limit.notification-publish-per-minute:20}") int notificationLimit,
      @Value("${education.rate-limit.report-per-minute:30}") int reportLimit,
      @Value("${education.rate-limit.ai-per-minute:60}") int aiLimit,
      @Value("${education.rate-limit.code-per-minute:60}") int codeLimit) {
    this.limiter=limiter; this.trustForwardedFor=trustForwardedFor; this.authLimit=authLimit;
    this.feedbackLimit=feedbackLimit; this.notificationLimit=notificationLimit;
    this.reportLimit=reportLimit; this.aiLimit=aiLimit; this.codeLimit=codeLimit;
  }

  @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
    Rule rule = rule(request.getMethod(), path(request));
    if (rule == null) return true;
    String subject = subject(request);
    String key = sha256("v1|" + rule.scope + "|" + subject);
    DatabaseRateLimiter.Decision decision = limiter.acquire(key, rule.limit, 60);
    response.setHeader("X-RateLimit-Limit", Integer.toString(rule.limit));
    response.setHeader("X-RateLimit-Remaining", Integer.toString(Math.max(0, decision.remaining())));
    if (!decision.allowed()) throw new RateLimitException(decision.retryAfterSeconds());
    return true;
  }

  private Rule rule(String method,String path) {
    if ("POST".equals(method) && (path.equals("/api/auth/login") || path.equals("/api/auth/refresh"))) return new Rule("auth",authLimit);
    if ("POST".equals(method) && path.equals("/api/feedback")) return new Rule("feedback",feedbackLimit);
    if ("POST".equals(method) && path.equals("/api/admin/notifications")) return new Rule("notification-publish",notificationLimit);
    if ("GET".equals(method) && path.startsWith("/api/reports/")) return new Rule("report",reportLimit);
    if ("POST".equals(method) && path.matches("/api/tutor/sessions/[^/]+/messages")) return new Rule("ai-tutor",aiLimit);
    if ("POST".equals(method) && path.matches("/api/assessments/[^/]+/import-files")) return new Rule("ai-parse",aiLimit);
    if ("POST".equals(method) && path.matches("/api/submissions/[^/]+/ai-grade")) return new Rule("ai-grade",aiLimit);
    if ("POST".equals(method) && path.equals("/api/code/run")) return new Rule("code-run",codeLimit);
    return null;
  }

  private String subject(HttpServletRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AuthenticatedUser user) return "user:"+user.id();
    String ip=request.getRemoteAddr();
    if(trustForwardedFor){String forwarded=request.getHeader("X-Forwarded-For");if(forwarded!=null&&!forwarded.isBlank())ip=forwarded.split(",",2)[0].trim();}
    return "ip:"+sha256(ip == null ? "unknown" : ip);
  }
  private String path(HttpServletRequest request){String uri=request.getRequestURI();String context=request.getContextPath();return context.isEmpty()?uri:uri.substring(context.length());}
  private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
  private record Rule(String scope,int limit){}
}
