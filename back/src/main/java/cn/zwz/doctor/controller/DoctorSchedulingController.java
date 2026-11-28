package cn.zwz.doctor.controller;

import cn.hutool.core.date.DateUtil;
import cn.zwz.basics.baseVo.PageVo;
import cn.zwz.basics.baseVo.Result;
import cn.zwz.basics.exception.ZwzException;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * @author 郑为中
 */
@Slf4j
@RestController
@Api(tags = "医生排班管理")
@RequestMapping("/zwz/doctorScheduling")
@Transactional
public class DoctorSchedulingController {

    /** 放号防连点标记前缀：同一医生 + 日期 + 时段在窗口期内只接受一次放号 */
    private static final String ADD_NUMBER_DEDUPE_PRE = "ZWZ_ADD_NUMBER_DEDUPE:";

    /** 防连点窗口，单位秒 */
    private static final long ADD_NUMBER_DEDUPE_SECONDS = 5L;

    @Autowired
    private IDoctorSchedulingService iDoctorSchedulingService;

    @Autowired
    private IDoctorService iDoctorService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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
        // 防连点：同一医生/日期/时段在窗口期内只接受一次请求，重复点击的第二次直接拒绝
        // Redis 不可用时降级放行，交给数据库唯一索引 uk_doc_date_step_num 兜底
        String dedupeKey = ADD_NUMBER_DEDUPE_PRE + doctorId + ":" + date + ":" + step;
        boolean dedupeMarked = false;
        try {
            Boolean first = stringRedisTemplate.opsForValue()
                    .setIfAbsent(dedupeKey, "1", ADD_NUMBER_DEDUPE_SECONDS, TimeUnit.SECONDS);
            if (Boolean.FALSE.equals(first)) {
                return ResultUtil.error("该医生该时段刚刚放号，请 " + ADD_NUMBER_DEDUPE_SECONDS + " 秒后再试");
            }
            dedupeMarked = Boolean.TRUE.equals(first);
        } catch (Exception e) {
            log.warn("放号防连点标记写入失败，本次降级为仅依赖唯一索引：{}", e.getMessage());
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
                clearDedupeMark(dedupeKey, dedupeMarked);
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
        try {
            iDoctorSchedulingService.saveBatch(batch);
        } catch (DataIntegrityViolationException e) {
            // 并发下两个请求算出了同一段号：唯一索引拦下后给出放号场景专属提示（DuplicateKeyException 是它的子类）
            clearDedupeMark(dedupeKey, dedupeMarked);
            throw new ZwzException("该医生该时段号源正在被放号，请稍后重试");
        } catch (RuntimeException e) {
            clearDedupeMark(dedupeKey, dedupeMarked);
            throw e;
        }
        return ResultUtil.success("放号成功，本次号源 " + start + " - " + (start + number - 1));
    }

    /**
     * 放号失败时释放防连点标记，方便管理员修正参数后立即重试
     */
    private void clearDedupeMark(String dedupeKey, boolean dedupeMarked) {
        if(!dedupeMarked) {
            return;
        }
        try {
            stringRedisTemplate.delete(dedupeKey);
        } catch (Exception e) {
            log.warn("放号防连点标记清理失败：{}", e.getMessage());
        }
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