package com.charging.station.mapper;

import com.charging.station.domain.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 Mapper（注册/登录）
 */
@Mapper
public interface UserMapper {

    @Select("SELECT user_id AS userId, username, password_hash AS passwordHash, salt, role, created_at AS createdAt "
            + "FROM sys_user WHERE username = #{username}")
    User getByUsername(@Param("username") String username);

    @Select("SELECT user_id AS userId, username, password_hash AS passwordHash, salt, role, created_at AS createdAt "
            + "FROM sys_user WHERE user_id = #{userId}")
    User getByUserId(@Param("userId") String userId);

    @Insert("INSERT INTO sys_user (user_id, username, password_hash, salt, role) "
            + "VALUES (#{userId}, #{username}, #{passwordHash}, #{salt}, #{role})")
    void insertUser(User user);
}
