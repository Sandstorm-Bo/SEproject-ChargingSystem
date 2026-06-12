package com.charging.station.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;

/**
 * 系统用户（客户端注册/登录）
 */
public class User {

    private String userId;                  // 用户编号
    private String username;                // 用户名
    private String passwordHash;            // 口令摘要（SHA-256(salt + password)）
    private String salt;                    // 摘要盐值
    private String role;                    // 角色：USER 普通用户 / ADMIN 管理员
    private LocalDateTime createdAt;        // 注册时间

    public User() {}

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    @JsonIgnore
    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    @JsonIgnore
    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
