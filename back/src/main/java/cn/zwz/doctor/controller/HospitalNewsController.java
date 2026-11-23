package cn.zwz.doctor.controller;

import cn.zwz.basics.baseVo.PageVo;
import cn.zwz.basics.baseVo.Result;
import cn.zwz.basics.utils.PageUtil;
import cn.zwz.basics.utils.ResultUtil;
import cn.zwz.data.utils.ZwzNullUtils;
import cn.zwz.doctor.entity.HospitalNews;
import cn.zwz.doctor.service.IHospitalNewsService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * @author 郑为中
 */
@RestController
@Api(tags = "医院新闻管理")
@RequestMapping("/zwz/hospitalNews")
@Transactional
public class HospitalNewsController {

    @Autowired
    private IHospitalNewsService iHospitalNewsService;

    @RequestMapping(value = "/getAll", method = RequestMethod.GET)
    @ApiOperation(value = "查询所有医院新闻")
    public Result<List<HospitalNews>> getAll(){
        return new ResultUtil<List<HospitalNews>>().setData(iHospitalNewsService.list());
    }

    @RequestMapping(value = "/getByPage", method = RequestMethod.GET)
    @ApiOperation(value = "查询医院新闻")
    public Result<IPage<HospitalNews>> getByPage(PageVo page, @RequestParam(required = false) String publicOnly){
        QueryWrapper<HospitalNews> qw = new QueryWrapper<>();
        // 患者端可传 publicOnly=yes，只拿已公开新闻；后台不传则查全部
        if("yes".equalsIgnoreCase(publicOnly)) {
            qw.eq("is_public", "yes");
        }
        return new ResultUtil<IPage<HospitalNews>>().setData(iHospitalNewsService.page(PageUtil.initMpPage(page), qw));
    }

    @RequestMapping(value = "/insertOrUpdate", method = RequestMethod.POST)
    @ApiOperation(value = "增改医院新闻")
    public Result<HospitalNews> saveOrUpdate(HospitalNews hospitalNews){
        // 新增时补齐默认值，避免 is_top / is_public 为 null 导致开关接口 NPE
        if(ZwzNullUtils.isNull(hospitalNews.getIsTop())) {
            hospitalNews.setIsTop("no");
        }
        if(ZwzNullUtils.isNull(hospitalNews.getIsPublic())) {
            hospitalNews.setIsPublic("no");
        }
        if(iHospitalNewsService.saveOrUpdate(hospitalNews)){
            return new ResultUtil<HospitalNews>().setData(hospitalNews);
        }
        return ResultUtil.error();
    }

    @RequestMapping(value = "/delByIds", method = RequestMethod.POST)
    @ApiOperation(value = "删除医院新闻")
    public Result<Object> delAllByIds(@RequestParam String[] ids){
        for(String id : ids){
            iHospitalNewsService.removeById(id);
        }
        return ResultUtil.success();
    }

    @RequestMapping(value = "/set_top_by_id", method = RequestMethod.POST)
    @ApiOperation(value = "置顶医院新闻")
    public Result<Boolean> setTopById(@RequestParam String id){
        HospitalNews appDynamicNew=iHospitalNewsService.getById(id);
        if(appDynamicNew==null) {
            return ResultUtil.error("该动态信息不存在");
        }
        if(appDynamicNew.getIsTop() != null && appDynamicNew.getIsTop().equals("yes")) {
            appDynamicNew.setIsTop("no");
        }
        else {
            appDynamicNew.setIsTop("yes");
        }
        if(!iHospitalNewsService.saveOrUpdate(appDynamicNew)) {
            return ResultUtil.error("设置失败");
        }
        return ResultUtil.success("设置成功");
    }

    @RequestMapping(value = "/set_public_by_id", method = RequestMethod.POST)
    @ApiOperation(value = "公开医院新闻")
    public Result<Boolean> setPublicById(@RequestParam String id){
        HospitalNews appDynamicNew=iHospitalNewsService.getById(id);
        if(appDynamicNew==null) {
            return ResultUtil.error("该动态信息不存在");
        }
        if(appDynamicNew.getIsPublic() != null && appDynamicNew.getIsPublic().equals("yes")) {
            appDynamicNew.setIsPublic("no");
        }
        else {
            appDynamicNew.setIsPublic("yes");
        }
        if(!iHospitalNewsService.saveOrUpdate(appDynamicNew)) {
            return ResultUtil.error("设置失败");
        }
        return ResultUtil.success("设置成功");
    }
}
