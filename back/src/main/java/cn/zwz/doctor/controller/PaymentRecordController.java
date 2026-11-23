package cn.zwz.doctor.controller;

import cn.zwz.basics.baseVo.PageVo;
import cn.zwz.basics.baseVo.Result;
import cn.zwz.basics.utils.PageUtil;
import cn.zwz.basics.utils.ResultUtil;
import cn.zwz.basics.utils.SecurityUtil;
import cn.zwz.data.entity.User;
import cn.zwz.data.utils.ZwzNullUtils;
import cn.zwz.doctor.entity.PaymentRecord;
import cn.zwz.doctor.service.IPaymentRecordService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 支付流水查询接口
 * @author 郑为中
 */
@RestController
@Api(tags = "支付流水接口")
@RequestMapping("/zwz/paymentRecord")
@Transactional
public class PaymentRecordController {

    @Autowired
    private IPaymentRecordService iPaymentRecordService;

    @Autowired
    private SecurityUtil securityUtil;

    @RequestMapping(value = "/getMyList", method = RequestMethod.POST)
    @ApiOperation(value = "查询我的支付流水")
    public Result<IPage<PaymentRecord>> getMyList(@ModelAttribute PaymentRecord record, @ModelAttribute PageVo page) {
        User currUser = securityUtil.getCurrUser();
        QueryWrapper<PaymentRecord> qw = new QueryWrapper<>();
        qw.eq("user_id", currUser.getId());
        qw.orderByDesc("create_time");
        if(!ZwzNullUtils.isNull(record.getOrderId())) {
            qw.eq("order_id", record.getOrderId());
        }
        if(record.getStatus() != null) {
            qw.eq("status", record.getStatus());
        }
        return new ResultUtil<IPage<PaymentRecord>>().setData(iPaymentRecordService.page(PageUtil.initMpPage(page), qw));
    }

    @RequestMapping(value = "/getByPage", method = RequestMethod.GET)
    @ApiOperation(value = "分页查询支付流水")
    public Result<IPage<PaymentRecord>> getByPage(@ModelAttribute PaymentRecord record, @ModelAttribute PageVo page) {
        QueryWrapper<PaymentRecord> qw = new QueryWrapper<>();
        if(!ZwzNullUtils.isNull(record.getOrderId())) {
            qw.eq("order_id", record.getOrderId());
        }
        if(!ZwzNullUtils.isNull(record.getUserName())) {
            qw.like("user_name", record.getUserName());
        }
        if(record.getStatus() != null) {
            qw.eq("status", record.getStatus());
        }
        qw.orderByDesc("create_time");
        return new ResultUtil<IPage<PaymentRecord>>().setData(iPaymentRecordService.page(PageUtil.initMpPage(page), qw));
    }
}
