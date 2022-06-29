package com.google.controller;


import com.google.error.BusinessException;
import com.google.error.EmBusinessError;
import com.google.mq.MqProducer;
import com.google.response.CommonReturnType;
import com.google.service.ItemService;
import com.google.service.OrderService;
import com.google.service.PromoService;
import com.google.service.model.UserModel;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.util.concurrent.*;

@Controller("order")
@RequestMapping("/order")
@CrossOrigin(allowCredentials = "true", origins = {"*"})
public class OrderController extends BaseController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private HttpServletRequest httpServletRequest;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private MqProducer mqProducer;

    @Autowired
    private ItemService itemService;

    @Autowired
    private PromoService promoService;

    private ExecutorService executorService;

    @PostConstruct
    public void init() {
        executorService = Executors.newFixedThreadPool(20);

    }


    // 生成秒杀令牌
    @RequestMapping(value = "/generatetoken", method = {RequestMethod.POST}, consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType generatetoken(@RequestParam(name = "itemId") Integer itemId,
                                          @RequestParam(name = "promoId") Integer promoId) throws BusinessException {

        // 根据token获取用户登录信息
        String token = httpServletRequest.getParameterMap().get("token")[0];

        if (StringUtils.isEmpty(token)) {
            throw new BusinessException(EmBusinessError.PARAMETER__VALIDATION_ERROR, "用户还未登录，不能下单");
        }

        // 获取用户登录信息
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);

        if (userModel == null) {
            throw new BusinessException(EmBusinessError.PARAMETER__VALIDATION_ERROR, "用户还未登录，不能下单");
        }

        // 获取秒杀令牌
        String promoToken = promoService.generateSecondKillToken(promoId, itemId, userModel.getId());

        if (promoToken == null) {
            throw new BusinessException(EmBusinessError.PARAMETER__VALIDATION_ERROR, "生成令牌失败");
        }

        return CommonReturnType.create(promoToken);
    }


    // 封装下单请求
    @RequestMapping(value = "/createorder", method = {RequestMethod.POST}, consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType createOrder(@RequestParam(name = "itemId") Integer itemId,
                                        @RequestParam(name = "amount") Integer amount,
                                        @RequestParam(name = "promoId", required = false) Integer promoId,
                                        @RequestParam(name = "promoToken", required = false) String promoToken) throws BusinessException {


        String token = httpServletRequest.getParameterMap().get("token")[0];

        if (StringUtils.isEmpty(token)) {
            throw new BusinessException(EmBusinessError.PARAMETER__VALIDATION_ERROR, "用户还未登录，不能下单");
        }

        // 获取用户登录信息
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);

        if (userModel == null) {
            throw new BusinessException(EmBusinessError.PARAMETER__VALIDATION_ERROR, "用户还未登录，不能下单");
        }

        // 校验秒杀令牌是否正确
        if (promoId != null) {
            String inRedisPromoToken = (String) redisTemplate.opsForValue().get("promo_token_" + promoId + "_userid_" + userModel.getId() + "_itemid_" + itemId);
            if (inRedisPromoToken == null) {
                throw new BusinessException(EmBusinessError.PARAMETER__VALIDATION_ERROR, "秒杀令牌校验失败");
            }

            if (!StringUtils.equals(promoToken, inRedisPromoToken)) {
                throw new BusinessException(EmBusinessError.PARAMETER__VALIDATION_ERROR, "秒杀令牌校验失败");
            }
        }

        // 同步调用线程池的submit方法
        Future<Object> future = executorService.submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                // 加入库存流水init状态
                String stockLogId = itemService.initStockLog(itemId, amount);

                // 再去完成对应的下单事务型消息
                if (!mqProducer.transactionAsyncReduceStock(userModel.getId(), itemId, promoId, amount, stockLogId)) {
                    throw new BusinessException(EmBusinessError.UNKNOWN_ERROR, "下单失败");
                }
                return null;
            }
        });

        try {
            future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new BusinessException(EmBusinessError.UNKNOWN_ERROR);
        }

        return CommonReturnType.create(null);
    }
}
