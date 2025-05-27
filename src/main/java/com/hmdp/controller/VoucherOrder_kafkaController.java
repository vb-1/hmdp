package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IvoucherOrder_kafkaService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */

@RestController
@RequestMapping("/voucher-order-kafka")
public class VoucherOrder_kafkaController {
    @Resource
    private IvoucherOrder_kafkaService voucherOrder_kafkaService;
    @PostMapping("seckill-kafka/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {

        return voucherOrder_kafkaService.seckillvoucher(voucherId);
    }
}
