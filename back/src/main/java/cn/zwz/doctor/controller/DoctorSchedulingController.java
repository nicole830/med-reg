package cn.zwz.doctor.controller;

import cn.hutool.core.date.DateUtil;
import cn.zwz.basics.baseVo.PageVo;
import cn.zwz.basics.baseVo.Result;
import cn.zwz.basics.utils.PageUtil;
import cn.zwz.basics.utils.ResultUtil;
import cn.zwz.data.utils.ZwzNullUtils;
import cn.zwz.doctor.entity.Doctor;
import cn.zwz.doctor.entity.DoctorScheduling;
import cn.zwz.doctor.service.IDoctorSchedulingService;
import cn.zwz.doctor.service.IDoctorService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author 郑为中
 */
@RestController
@Api(tags = "医生排班管理")
@RequestMapping("/zwz/doctorScheduling")
@Transactional
public class DoctorSchedulingController {

    @Autowired
    private IDoctorSchedulingService iDoctorSchedulingService;

    @Autowired
    private IDoctorService iDoctorService;

    @RequestMapping(value = "/addNumber", method = RequestMethod.POST)
    @ApiOperation(value = "医生放号")
    public Result<Object> addNumber(@RequestParam String doctorId,@RequestParam String date,@RequestParam int step,@RequestParam int number){
        Doctor doctor = iDoctorService.getById(doctorId);
        if(doctor == null) {
            return ResultUtil.error("医生不存在");
        }
        if(number < 1 || number > 200) {
            return ResultUtil.error("单次放号数必须在 1-200 之间");
        }
        if(ZwzNullUtils.isNull(date) || !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return ResultUtil.error("日期格式应为 yyyy-MM-dd");
        }
        if(date.compareTo(DateUtil.today()) < 0) {
            return ResultUtil.error("不能给历史日期放号");
        }
        if(step < 0 || step > 1) {
            return ResultUtil.error("时段只能是 0（上午）或 1（下午）");
        }
        // 幂等：接着该医生/日期/时段已有最大号往后放，重复点击不会生成重复号
        QueryWrapper<DoctorScheduling> maxQw = new QueryWrapper<>();
        maxQw.select("COALESCE(MAX(CAST(number AS UNSIGNED)), 0) AS number");
        maxQw.eq("doctor_id", doctorId);
        maxQw.eq("date", date);
        maxQw.eq("step", String.valueOf(step));
        DoctorScheduling maxRow = iDoctorSchedulingService.getOne(maxQw);
        int start = 1;
        if(maxRow != null && !ZwzNullUtils.isNull(maxRow.getNumber())) {
            try {
                start = Integer.parseInt(maxRow.getNumber()) + 1;
            } catch (NumberFormatException e) {
                return ResultUtil.error("已有号源编号异常，无法继续放号");
            }
        }
        List<DoctorScheduling> batch = new ArrayList<>();
        for(int i = 0; i < number; i ++) {
            DoctorScheduling ds = new DoctorScheduling();
            ds.setDoctorId(doctor.getId());
            ds.setDoctorName(doctor.getDoctorName());
            ds.setNumber((start + i) + "");
            ds.setStep(step + "");
            ds.setDate(date);
            ds.setOrderFlag(0);
            batch.add(ds);
        }
        // 批量插入，避免 for 循环一条一条写库
        iDoctorSchedulingService.saveBatch(batch);
        return ResultUtil.success("放号成功，本次号源 " + start + " - " + (start + number - 1));
    }

    @RequestMapping(value = "/getAll", method = RequestMethod.GET)
    @ApiOperation(value = "查询所有医生排班")
    public Result<List<DoctorScheduling>> getAll(){
        return new ResultUtil<List<DoctorScheduling>>().setData(iDoctorSchedulingService.list());
    }

    @RequestMapping(value = "/getByPage", method = RequestMethod.GET)
    @ApiOperation(value = "查询医生排班")
    public Result<IPage<DoctorScheduling>> getByPage(@ModelAttribute DoctorScheduling scheduling,@ModelAttribute PageVo page){
        QueryWrapper<DoctorScheduling> qw = new QueryWrapper<>();
        if(!ZwzNullUtils.isNull(scheduling.getDoctorId())) {
            qw.eq("doctor_id",scheduling.getDoctorId());
        }
        if(!ZwzNullUtils.isNull(scheduling.getDate())) {
            qw.eq("date",scheduling.getDate());
        }
        if(Objects.equals("0", scheduling.getStep())) {
            qw.eq("step","0");
        } else if(Objects.equals("1", scheduling.getStep())) {
            qw.eq("step","1");
        }
        return new ResultUtil<IPage<DoctorScheduling>>().setData(iDoctorSchedulingService.page(PageUtil.initMpPage(page),qw));
    }

    @RequestMapping(value = "/insertOrUpdate", method = RequestMethod.POST)
    @ApiOperation(value = "增改医生排班")
    public Result<DoctorScheduling> saveOrUpdate(DoctorScheduling doctorScheduling){
        if(iDoctorSchedulingService.saveOrUpdate(doctorScheduling)){
            return new ResultUtil<DoctorScheduling>().setData(doctorScheduling);
        }
        return ResultUtil.error();
    }

    @RequestMapping(value = "/delByIds", method = RequestMethod.POST)
    @ApiOperation(value = "删除医生排班")
    public Result<Object> delAllByIds(@RequestParam String[] ids){
        for(String id : ids){
            iDoctorSchedulingService.removeById(id);
        }
        return ResultUtil.success();
    }
}