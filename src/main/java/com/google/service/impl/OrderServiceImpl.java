package com.google.service.impl;

import com.google.dao.OrderDOMapper;
import com.google.dao.SequenceDOMapper;
import com.google.dataobject.OrderDO;
import com.google.dataobject.SequenceDO;
import com.google.error.BusinessException;
import com.google.error.EmBusinessError;
import com.google.service.ItemService;
import com.google.service.OrderService;
import com.google.service.UserService;
import com.google.service.model.ItemModel;
import com.google.service.model.OrderModel;
import com.google.service.model.UserModel;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private ItemService itemService;

    @Autowired
    private UserService userService;

    @Autowired
    private OrderDOMapper orderDOMapper;


    @Autowired
    private SequenceDOMapper sequenceDOMapper;

    @Override
    @Transactional
    public OrderModel createOrder(Integer userId, Integer itemId, Integer amount) throws BusinessException {
        // 校验下单状态，下单的商品是否存在，用户是否合法，购买数量是否正确
        ItemModel itemModel = itemService.getItemById(itemId);
        if (itemModel == null) {
            throw new BusinessException(EmBusinessError.PARAMETER__VALIDATION_ERROR, "商品信息不存在");
        }

        UserModel userModel = userService.getUserById(userId);
        if (userModel == null) {
            throw new BusinessException(EmBusinessError.PARAMETER__VALIDATION_ERROR, "用户信息不存在");
        }

        if (amount <= 0 || amount > 99) {
            throw new BusinessException(EmBusinessError.PARAMETER__VALIDATION_ERROR, "数量信息不正确");
        }

        // 落单减库存
        boolean result = itemService.decreaseStock(itemId, amount);

        if (!result) {
            throw new BusinessException(EmBusinessError.STOCK_NOT_ENOUGH);
        }
        // 订单入库
        OrderModel orderModel = new OrderModel();
        orderModel.setUserId(userId);
        orderModel.setItemId(itemId);
        orderModel.setAmount(amount);
        orderModel.setItemPrice(itemModel.getPrice());
        orderModel.setOrderPrice(itemModel.getPrice().multiply(BigDecimal.valueOf(amount)));

        // 生成交易流水号，订单号
        orderModel.setId(generateOrderNO());


        OrderDO orderDO = convertFromOrderModel(orderModel);

        orderDOMapper.insertSelective(orderDO);

        // 返回前端
        return orderModel;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    String generateOrderNO() {
        StringBuilder stringBuilder = new StringBuilder();
        // 订单号16位
        // 前8位为时间信息，年月日
        LocalDateTime now = LocalDateTime.now();
        String nowDate = now.format(DateTimeFormatter.ISO_DATE).replace("-", "");
        stringBuilder.append(nowDate);


        // 中间六位为自增序列
        // 获取当前sequence
        int sequence = 0;

        SequenceDO sequenceDO = sequenceDOMapper.getSequenceByName("order_info");

        sequence = sequenceDO.getCurrentValue();
        sequenceDO.setCurrentValue(sequence + sequenceDO.getStep());
        sequenceDOMapper.updateByPrimaryKeySelective(sequenceDO);
        // 凑足6位
        String sequenceStr = String.valueOf(sequence);
        for (int i = 0; i < 6 - sequenceStr.length(); i++) {
            stringBuilder.append(0);
        }

        stringBuilder.append(sequenceStr);

        // 最后为分库分表位，暂时写死
        stringBuilder.append("00");

        return stringBuilder.toString();
    }


    private OrderDO convertFromOrderModel(OrderModel orderModel) {
        if (orderModel == null) {
            return null;
        }
        OrderDO orderDO = new OrderDO();
        BeanUtils.copyProperties(orderModel, orderDO);

        return orderDO;
    }
}
