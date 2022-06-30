package com.google.controller;


import com.google.common.util.concurrent.RateLimiter;
import com.google.error.BusinessException;
import com.google.error.EmBusinessError;
import com.google.mq.MqProducer;
import com.google.response.CommonReturnType;
import com.google.service.ItemService;
import com.google.service.OrderService;
import com.google.service.PromoService;
import com.google.service.model.UserModel;
import com.google.util.CodeUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.RenderedImage;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
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

    private RateLimiter orderCreateRateLimiter;

    @PostConstruct
    public void init() {
        executorService = Executors.newFixedThreadPool(20);
        orderCreateRateLimiter = RateLimiter.create(300);
    }

    // 生成验证码
    @RequestMapping(value = "/generateverifycode", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public void generateverifycode(HttpServletResponse response) throws BusinessException, IOException {
        // 根据token获取用户登录信息
        String token = httpServletRequest.getParameterMap().get("token")[0];

        if (StringUtils.isEmpty(token)) {
            throw new BusinessException(EmBusinessError.PARAMETER__VALIDATION_ERROR, "用户还未登录，不能生成验证码");

        }

        // 获取用户登录信息
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);

        if (userModel == null) {
            throw new BusinessException(EmBusinessError.PARAMETER__VALIDATION_ERROR, "用户还未登录，不能生成验证码");
        }

        //创建文件输出流对象
        Map<String,Object> map = CodeUtil.generateCodeAndPic();
        redisTemplate.opsForValue().set("verify_code_" + userModel.getId(), map.get("code"));
        redisTemplate.expire("verify_code_" + userModel.getId(), 10, TimeUnit.MINUTES);
        ImageIO.write((RenderedImage) map.get("codePic"), "jpeg", response.getOutputStream());

    }

    // 生成秒杀令牌
    @RequestMapping(value = "/generatetoken", method = {RequestMethod.POST}, consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType generatetoken(@RequestParam(name = "itemId") Integer itemId,
                                          @RequestParam(name = "promoId") Integer promoId,
                                          @RequestParam(name = "verifyCode") String verifyCode) throws BusinessException {

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

        // 通过verifycode验证验证码的有效性
        String redisVerifyCode = (String) redisTemplate.opsForValue().get("verify_code_" + userModel.getId());
        if (StringUtils.isEmpty(redisVerifyCode)) {
            throw new BusinessException(EmBusinessError.PARAMETER__VALIDATION_ERROR, "请求非法");
        }

        if (!redisVerifyCode.equalsIgnoreCase(verifyCode)) {
            throw new BusinessException(EmBusinessError.PARAMETER__VALIDATION_ERROR, "请求非法，验证码错误");
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

        if (!orderCreateRateLimiter.tryAcquire()) {
            throw new BusinessException(EmBusinessError.RATELIMIT);
        }

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
