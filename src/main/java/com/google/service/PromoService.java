package com.google.service;

import com.google.service.model.PromoModel;

public interface PromoService {

    // 根据itemId获取即将进行的或正在进行的秒杀活动
    PromoModel getPromoByItemId(Integer itemId);

    // 活动发布
    void publishPromo(Integer promoId);
}
