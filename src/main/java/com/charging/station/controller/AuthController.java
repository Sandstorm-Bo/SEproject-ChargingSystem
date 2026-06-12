package com.charging.station.controller;

import com.charging.station.domain.User;
import com.charging.station.dto.Result;
import com.charging.station.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 用户注册 / 登录控制器
 * 客户端需求 b)：注册、登录；登录后才能查看本人详单等信息。
 */
@RestController
@RequestMapping("/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private UserMapper userMapper;

    /**
     * 注册（role=ADMIN 注册管理员账号，缺省为普通用户）
     * POST /api/auth/register  {username, password[, role]}
     */
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        try {
            String username = trimmed(body.get("username"));
            String password = body.get("password") == null ? "" : body.get("password");
            String role = "ADMIN".equalsIgnoreCase(trimmed(body.get("role"))) ? "ADMIN" : "USER";
            if (username.isEmpty() || username.length() > 30) {
                return Result.error("用户名需为 1~30 个字符");
            }
            if (password.length() < 4) {
                return Result.error("密码至少 4 位");
            }
            if (userMapper.getByUsername(username) != null) {
                return Result.error("用户名已存在");
            }
            User user = new User();
            user.setUserId(("ADMIN".equals(role) ? "A-" : "U-")
                    + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            user.setUsername(username);
            user.setRole(role);
            String salt = randomSalt();
            user.setSalt(salt);
            user.setPasswordHash(sha256(salt + password));
            userMapper.insertUser(user);
            return Result.success("注册成功", publicView(user));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 登录
     * POST /api/auth/login  {username, password}
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        try {
            String username = trimmed(body.get("username"));
            String password = body.get("password") == null ? "" : body.get("password");
            User user = userMapper.getByUsername(username);
            if (user == null || !sha256(user.getSalt() + password).equals(user.getPasswordHash())) {
                return Result.error("用户名或密码错误");
            }
            return Result.success("登录成功", publicView(user));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    private static Map<String, Object> publicView(User user) {
        Map<String, Object> view = new HashMap<>();
        view.put("userId", user.getUserId());
        view.put("username", user.getUsername());
        view.put("role", user.getRole() == null ? "USER" : user.getRole());
        return view;
    }

    private static String trimmed(String s) {
        return s == null ? "" : s.trim();
    }

    private static String randomSalt() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return hex(bytes);
    }

    private static String sha256(String text) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
