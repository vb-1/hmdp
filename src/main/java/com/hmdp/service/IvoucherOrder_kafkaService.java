package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;

public interface IvoucherOrder_kafkaService extends IService<VoucherOrder> {
    Result seckillvoucher(Long voucherId);
}
