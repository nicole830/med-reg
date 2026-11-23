package cn.zwz.doctor.controller;

import cn.zwz.basics.baseVo.PageVo;
import cn.zwz.basics.baseVo.Result;
import cn.zwz.basics.exception.ZwzException;
import cn.zwz.basics.utils.PageUtil;
import cn.zwz.basics.utils.ResultUtil;
import cn.zwz.basics.utils.SecurityUtil;
import cn.zwz.data.entity.User;
import cn.zwz.data.utils.ZwzNullUtils;
import cn.zwz.doctor.entity.Doctor;
import cn.zwz.doctor.entity.DoctorScheduling;
import cn.zwz.doctor.entity.HospitalOrder;
import cn.zwz.doctor.entity.PaymentRecord;
import cn.zwz.doctor.service.IDoctorSchedulingService;
import cn.zwz.doctor.service.IDoctorService;
import cn.zwz.doctor.service.IHospitalOrderService;
import cn.zwz.doctor.service.IPaymentRecordService;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.math.BigDecimal;
import java.util.Date;

/**
 * @author 郑为中
 */
@RestController
@Api(tags = "挂号管理接口")
@RequestMapping("/zwz/hospitalOrder")
@Transactional
public class HospitalOrderController {

    @Autowired
    private IHospitalOrderService iHospitalOrderService;

    @Autowired
    private SecurityUtil securityUtil;

    @Autowired
    private IDoctorService iDoctorService;

    @Autowired
    private IDoctorSchedulingService iDoctorSchedulingService;

    @Autowired
    private IPaymentRecordService iPaymentRecordService;

    @RequestMapping(value = "/getMyOrderList", method = RequestMethod.POST)
    @ApiOperation(value = "查询我的挂号")
    public Result<IPage<HospitalOrder>> getMyOrderList(@ModelAttribute HospitalOrder order,@ModelAttribute PageVo page){
        User currUser = securityUtil.getCurrUser();
        QueryWrapper<HospitalOrder> qw = new QueryWrapper<>();
        qw.eq("user_id",currUser.getId());
        qw.orderByDesc("create_time");
        if(!ZwzNullUtils.isNull(order.getDateTime())) {
            qw.eq("date_time",order.getDateTime());
        }
        if(!ZwzNullUtils.isNull(order.getDoctorName())) {
            qw.like("doctor_name",order.getDoctorName());
        }
        return new ResultUtil<IPage<HospitalOrder>>().setData(iHospitalOrderService.page(PageUtil.initMpPage(page),qw));
    }
    @RequestMapping(value = "/payMoney", method = RequestMethod.POST)
    @ApiOperation(value = "挂号付款")
    public Result<Object> payMoney(@RequestParam String orderId){
        User currUser = securityUtil.getCurrUser();
        HospitalOrder ho = iHospitalOrderService.getById(orderId);
        if(ho == null) {
            return ResultUtil.error("挂号单不存在");
        }
        // 只能给自己付款，防止 IDOR
        if(!Objects.equals(ho.getUserId(), currUser.getId())) {
            return ResultUtil.error("无权操作该挂号单");
        }
        if(ho.getStatus() != 0) {
            return ResultUtil.error("该挂号单已取消，不能付款");
        }
        // 条件更新：并发的重复付款只有一次能成功
        boolean paid = iHospitalOrderService.update(new UpdateWrapper<HospitalOrder>()
                .eq("id", orderId)
                .eq("status", 0)
                .eq("money_flag", 0)
                .set("money_flag", 1));
        if(!paid) {
            return ResultUtil.error("该挂号单已支付或已取消，请勿重复付款");
        }
        // 付款成功写支付流水；流水写失败会抛异常，事务把 money_flag 一并回滚
        createPaymentRecord(ho);
        return ResultUtil.success("付款成功");
    }

    @RequestMapping(value = "/noneOrder", method = RequestMethod.POST)
    @ApiOperation(value = "取消挂号")
    public Result<Object> noneOrder(@RequestParam String orderId){
        User currUser = securityUtil.getCurrUser();
        // 锁住订单行，避免“付款”和“取消”并发时漏写退款流水
        HospitalOrder ho = iHospitalOrderService.getOne(new QueryWrapper<HospitalOrder>()
                .eq("id", orderId).last("FOR UPDATE"));
        if(ho == null) {
            return ResultUtil.error("挂号单不存在");
        }
        if(!Objects.equals(ho.getUserId(), currUser.getId())) {
            return ResultUtil.error("无权操作该挂号单");
        }
        if(ho.getStatus() != 0) {
            return ResultUtil.error("该挂号单已取消，请勿重复操作");
        }
        // 条件更新：同一张单并发取消只有一次生效
        boolean cancelled = iHospitalOrderService.update(new UpdateWrapper<HospitalOrder>()
                .eq("id", orderId)
                .eq("status", 0)
                .set("status", 1)
                .set("money_flag", 0));
        if(!cancelled) {
            return ResultUtil.error("该挂号单已取消，请勿重复操作");
        }
        // 已付款订单取消时补退款流水（行锁保证这里读到的是取消前的最新付款状态）
        if(ho.getMoneyFlag() == 1) {
            refundPayment(ho);
        }
        // 取消后必须把号源放回可预约状态，否则号源被永久占用
        releaseSlot(ho.getOrderId());
        return ResultUtil.success("取消成功");
    }

    /**
     * 释放号源：只有当前是“已预约”才回退，避免把别人刚抢到的号放掉
     */
    private void releaseSlot(String slotId) {
        if(ZwzNullUtils.isNull(slotId)) {
            return;
        }
        DoctorScheduling ds = iDoctorSchedulingService.getById(slotId);
        if(ds == null) {
            return;
        }
        iDoctorSchedulingService.update(new UpdateWrapper<DoctorScheduling>()
                .eq("id", slotId)
                .eq("order_flag", 1)
                .set("order_flag", 0));
    }

    /**
     * 写支付流水：一张挂号单只允许一条流水，order_id 上有唯一索引
     */
    private void createPaymentRecord(HospitalOrder ho) {
        PaymentRecord record = new PaymentRecord();
        record.setOrderId(ho.getId());
        record.setUserId(ho.getUserId());
        record.setUserName(ho.getUserName());
        record.setAmount(ho.getMoneyData() == null ? BigDecimal.ZERO : ho.getMoneyData());
        record.setStatus(0);
        record.setPayNo("PAY" + IdUtil.getSnowflake(1, 1).nextId());
        record.setRemark("挂号支付");
        if(!iPaymentRecordService.save(record)) {
            throw new ZwzException("支付流水写入失败");
        }
    }

    /**
     * 写退款流水：已有支付流水就把原流水置为已退款；历史订单没有流水则补一条退款记录
     */
    private void refundPayment(HospitalOrder ho) {
        PaymentRecord record = iPaymentRecordService.getOne(new QueryWrapper<PaymentRecord>()
                .eq("order_id", ho.getId()));
        String refundNo = "REF" + IdUtil.getSnowflake(1, 1).nextId();
        if(record == null) {
            PaymentRecord refund = new PaymentRecord();
            refund.setOrderId(ho.getId());
            refund.setUserId(ho.getUserId());
            refund.setUserName(ho.getUserName());
            refund.setAmount(ho.getMoneyData() == null ? BigDecimal.ZERO : ho.getMoneyData());
            refund.setStatus(1);
            refund.setRefundNo(refundNo);
            refund.setRemark("历史订单退款（无支付流水）");
            if(!iPaymentRecordService.save(refund)) {
                throw new ZwzException("退款流水写入失败");
            }
            return;
        }
        if(record.getStatus() != null && record.getStatus() == 1) {
            return;
        }
        boolean refunded = iPaymentRecordService.update(new UpdateWrapper<PaymentRecord>()
                .eq("id", record.getId())
                .eq("status", 0)
                .set("status", 1)
                .set("refund_no", refundNo)
                .set("update_time", new Date())
                .set("remark", "取消挂号退款"));
        if(!refunded) {
            throw new ZwzException("退款流水更新失败");
        }
    }

    @RequestMapping(value = "/addOrder", method = RequestMethod.POST)
    @ApiOperation(value = "新增挂号")
    public Result<Object> addOrder(@RequestParam String orderId){
        User currUser = securityUtil.getCurrUser();
        DoctorScheduling ds = iDoctorSchedulingService.getById(orderId);
        if(ds == null) {
            return ResultUtil.error("号源不存在");
        }
        if(ds.getOrderFlag() > 0) {
            return ResultUtil.error("您手慢拉,该号已被别人预约!");
        }
        // 过期号源不能挂
        if(ZwzNullUtils.isNull(ds.getDate()) || ds.getDate().compareTo(cn.hutool.core.date.DateUtil.today()) < 0) {
            return ResultUtil.error("该号源已过期，请选择今日或之后的号源");
        }
        Doctor doctor = iDoctorService.getById(ds.getDoctorId());
        if(doctor == null) {
            return ResultUtil.error("医生不存在");
        }
        // 原子占号：数据库行锁保证并发下只有一个请求能把 0 改成 1
        boolean occupied = iDoctorSchedulingService.update(new UpdateWrapper<DoctorScheduling>()
                .eq("id", ds.getId())
                .eq("order_flag", 0)
                .set("order_flag", 1));
        if(!occupied) {
            return ResultUtil.error("您手慢拉,该号已被别人预约!");
        }
        HospitalOrder ho = new HospitalOrder();
        ho.setUserId(currUser.getId());
        ho.setUserName(currUser.getNickname());
        ho.setOrderId(ds.getId());
        ho.setNumber(ds.getNumber());
        ho.setStep(ds.getStep());
        ho.setDateTime(ds.getDate());
        ho.setDoctorId(ds.getDoctorId());
        ho.setDoctorName(ds.getDoctorName());
        ho.setMoneyData(doctor.getOrderMoney());
        if(!iHospitalOrderService.saveOrUpdate(ho)) {
            // 抛异常触发事务回滚，把刚占的号源一起回退
            throw new ZwzException("挂号单创建失败");
        }
        return ResultUtil.success("预约成功!");
    }

    @RequestMapping(value = "/getAll", method = RequestMethod.GET)
    @ApiOperation(value = "查询所有挂号信息")
    public Result<List<HospitalOrder>> getAll(){
        return new ResultUtil<List<HospitalOrder>>().setData(iHospitalOrderService.list());
    }

    @RequestMapping(value = "/getByPage", method = RequestMethod.GET)
    @ApiOperation(value = "查询挂号信息")
    public Result<IPage<HospitalOrder>> getByPage(PageVo page){
        return new ResultUtil<IPage<HospitalOrder>>().setData(iHospitalOrderService.page(PageUtil.initMpPage(page)));
    }

    @RequestMapping(value = "/insertOrUpdate", method = RequestMethod.POST)
    @ApiOperation(value = "增改挂号信息")
    public Result<HospitalOrder> saveOrUpdate(HospitalOrder hospitalOrder){
        if(iHospitalOrderService.saveOrUpdate(hospitalOrder)){
            return new ResultUtil<HospitalOrder>().setData(hospitalOrder);
        }
        return ResultUtil.error();
    }

    @RequestMapping(value = "/delByIds", method = RequestMethod.POST)
    @ApiOperation(value = "删除挂号信息")
    public Result<Object> delAllByIds(@RequestParam String[] ids){
        for(String id : ids){
            HospitalOrder ho = iHospitalOrderService.getOne(new QueryWrapper<HospitalOrder>()
                    .eq("id", id).last("FOR UPDATE"));
            if(ho == null) {
                continue;
            }
            // 删除已付款订单前补退款流水，避免“删单不退钱”
            if(ho.getMoneyFlag() == 1) {
                refundPayment(ho);
            }
            if(!iHospitalOrderService.removeById(id)) {
                throw new ZwzException("挂号单删除失败");
            }
            // 删除挂号单同样要释放号源
            releaseSlot(ho.getOrderId());
        }
        return ResultUtil.success();
    }
}
