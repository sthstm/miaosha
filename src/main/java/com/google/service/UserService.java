package com.google.service;

import com.google.error.BusinessException;
import com.google.service.model.UserModel;

public interface UserService {
    // 通过id查询用户
    UserModel getUserById(Integer id);

    // 用户注册
    void register(UserModel userModel) throws BusinessException;

    // 用户登录
    UserModel validateLogin(String telephone, String encryptPassword) throws BusinessException;

}
