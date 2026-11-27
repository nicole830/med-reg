package cn.zwz.basics.security;

import cn.zwz.basics.exception.ZwzException;
import cn.zwz.data.entity.User;
import cn.zwz.data.service.IUserService;
import cn.zwz.data.utils.ZwzNullUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 根据账号/手机号查询用户所有信息
 * @author 郑为中
 */
@Component
public class UserDetailsServiceImpl implements UserDetailsService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private IUserService iUserService;

    private static final String LOGIN_FAIL_DISABLED_PRE = "userLoginDisableFlag:";

    @Override
    @ApiOperation(value = "根据账号/手机号查询用户所有信息")
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String loginFailFlag = LOGIN_FAIL_DISABLED_PRE + username;
        String value = redisTemplate.opsForValue().get(loginFailFlag);
        if (!ZwzNullUtils.isNull(value)) {
            Long timeRest = redisTemplate.getExpire(loginFailFlag, TimeUnit.MINUTES);
            String tipMsg;
            if (timeRest > 0) {
                tipMsg = "试错超限，请您在" + timeRest + "分钟后再登录";
            } else if (timeRest == -1) {
                tipMsg = "账号已被永久锁定，请联系管理员";
            } else {
                // timeRest == -2 理论上走不到这里，因为value不为null
                tipMsg = "账号锁定异常";
            }
            throw new ZwzException(tipMsg);
        }

        // account：支持用户名 / 手机号登录
        String account = username;
        QueryWrapper<User> userQw = new QueryWrapper<>();
        userQw.and(w -> w.eq("username", account).or().eq("mobile", account));
        userQw.orderByDesc("create_time");
        // getOne第二个参数false：多条时不抛异常，返回第一条
        User user = iUserService.getOne(userQw, false);
        if (user == null) {
            // 这里可以抛出账号不存在异常，和你上面登录失败锁定逻辑配合
            throw new ZwzException("账号不存在");
        }
        return new SecurityUserDetails(user);

    }
}
