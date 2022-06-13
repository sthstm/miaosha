package com.google.service;

import com.google.error.BusinessException;
import com.google.service.model.OrderModel;

public interface OrderService {

    OrderModel createOrder(Integer userId, Integer itemId, Integer amount) throws BusinessException;


}
