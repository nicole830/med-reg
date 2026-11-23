package cn.zwz.doctor.controller;

import cn.zwz.basics.baseVo.Result;
import cn.zwz.basics.utils.ResultUtil;
import cn.zwz.doctor.entity.HospitalSubject;
import cn.zwz.doctor.service.IHospitalSubjectService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 兼容历史前端接口：/zwz/dynamic_new/get_service_by_ids
 * 新闻编辑页用它回填“关联服务”名称，后端原先没有这个控制器，请求 404
 * @author 郑为中
 */
@RestController
@Api(tags = "新闻关联科室兼容接口")
@RequestMapping("/zwz/dynamic_new")
public class DynamicNewCompatController {

    @Autowired
    private IHospitalSubjectService iHospitalSubjectService;

    @RequestMapping(value = "/get_service_by_ids", method = RequestMethod.GET)
    @ApiOperation(value = "按科室ID查询关联服务")
    public Result<List<Map<String, Object>>> getServiceByIds(@RequestParam(required = false) String ids) {
        QueryWrapper<HospitalSubject> qw = new QueryWrapper<>();
        if (ids != null && !ids.trim().isEmpty()) {
            List<String> idList = new ArrayList<>();
            for (String id : Arrays.asList(ids.split(","))) {
                if (!id.trim().isEmpty()) {
                    idList.add(id.trim());
                }
            }
            if (!idList.isEmpty()) {
                qw.in("id", idList);
            }
        }
        List<HospitalSubject> subjects = iHospitalSubjectService.list(qw);
        List<Map<String, Object>> result = new ArrayList<>();
        for (HospitalSubject subject : subjects) {
            Map<String, Object> item = new HashMap<>(4);
            item.put("id", subject.getId());
            item.put("serviceName", subject.getSubName());
            result.add(item);
        }
        return new ResultUtil<List<Map<String, Object>>>().setData(result);
    }
}
