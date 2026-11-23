package cn.zwz.basics.utils;

import cn.zwz.basics.exception.ZwzException;
import cn.zwz.basics.baseVo.TokenUser;
import cn.zwz.basics.parameter.ZwzLoginProperties;
import cn.zwz.data.entity.*;
import cn.zwz.data.service.IPermissionService;
import cn.zwz.data.service.IRoleService;
import cn.zwz.data.service.IUserService;
import cn.zwz.data.utils.ZwzNullUtils;
import cn.zwz.data.vo.PermissionDTO;
import cn.zwz.data.vo.RoleDTO;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.Gson;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 鉴权工具类
 * @author 郑为中
 */
@Slf4j
@Component
public class SecurityUtil {

    /**
     * 用户（含角色、菜单权限）缓存：key = user::detail:{username}
     * 前缀落在 user: 下，角色/菜单变更时现有清缓存逻辑可以一并清掉
     */
    private static final String USER_CACHE_PRE = "user::detail:";

    /**
     * 权限缓存 30 分钟；角色/用户变更会主动失效，正常请求直接命中 Redis
     */
    private static final long USER_CACHE_MINUTES = 30L;

    @Autowired
    private ZwzLoginProperties tokenProperties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private IUserService iUserService;

    @Autowired
    private IRoleService iRoleService;

    @Autowired
    private IPermissionService iPermissionService;

    private static final String TOKEN_REPLACE_FRONT_STR = "-";

    private static final String TOKEN_REPLACE_BACK_STR = "";

    private User selectByUserName(String title) {
        String cacheKey = USER_CACHE_PRE + title;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if(!ZwzNullUtils.isNull(cached)) {
                User cachedUser = new Gson().fromJson(cached, User.class);
                if(cachedUser != null) {
                    return cachedUser;
                }
            }
        } catch (Exception e) {
            // Redis 异常时回退数据库，保证鉴权仍然可用
            log.warn("读取用户权限缓存失败，回退数据库：{}", e.getMessage());
        }
        User user = loadUserFromDb(title);
        if(user == null) {
            return null;
        }
        // 密码不进缓存，重置/解锁密码走数据库实时查询
        user.setPassword(null);
        try {
            redisTemplate.opsForValue().set(cacheKey, new Gson().toJson(user), USER_CACHE_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("写入用户权限缓存失败：{}", e.getMessage());
        }
        return user;
    }

    private User loadUserFromDb(String title) {
        QueryWrapper<User> userQw = new QueryWrapper<>();
        userQw.eq("username",title);
        User user = iUserService.getOne(userQw);
        if(user == null) {
            return null;
        }
        /**
         * 填充角色
         */
        QueryWrapper<Role> roleQw = new QueryWrapper<>();
        roleQw.inSql("id","SELECT role_id FROM a_user_role WHERE del_flag = 0 AND user_id = '" + user.getId() + "'");
        List<Role> roleList = iRoleService.list(roleQw);
        List<RoleDTO> roles = new ArrayList<>();
        for (Role role : roleList) {
            roles.add(new RoleDTO(role.getName(),role.getId(),role.getDescription()));
        }
        user.setRoles(roles);
        /**
         * 填充菜单/按钮权限：按 用户 -> 角色 -> 角色权限 -> 菜单 三表关联
         */
        QueryWrapper<Permission> permissionQw = new QueryWrapper<>();
        permissionQw.inSql("id","SELECT rp.permission_id FROM a_role_permission rp JOIN a_user_role ur ON ur.role_id = rp.role_id WHERE rp.del_flag = 0 AND ur.del_flag = 0 AND ur.user_id = '" + user.getId() + "'");
        List<Permission> permissionList = iPermissionService.list(permissionQw);
        List<PermissionDTO> permissions = new ArrayList<>();
        for (Permission permission : permissionList) {
            if(!ZwzNullUtils.isNull(permission.getTitle())) {
                permissions.add(new PermissionDTO(permission.getPath(),permission.getTitle()));
            }
        }
        user.setPermissions(permissions);
        return user;
    }

    /**
     * 清单个用户的权限缓存：登录、改资料、重置密码时调用
     */
    public void evictUserCache(String username) {
        if(ZwzNullUtils.isNull(username)) {
            return;
        }
        try {
            redisTemplate.delete(USER_CACHE_PRE + username);
        } catch (Exception e) {
            log.warn("清理用户权限缓存失败：{}", e.getMessage());
        }
    }

    /**
     * 清全部用户权限缓存：角色授权、菜单变更时调用
     */
    public void evictAllUserCache() {
        try {
            Set<String> keys = redisTemplate.keys(USER_CACHE_PRE + "*");
            if(keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("批量清理用户权限缓存失败：{}", e.getMessage());
        }
    }

    @ApiOperation(value = "获取新的用户Token")
    public String getToken(String username, Boolean saveLogin){
        if(ZwzNullUtils.isNull(username)){
            throw new ZwzException("username不能为空");
        }
        // 登录时强制读最新权限，避免旧缓存把刚调整过的角色带进新 token
        evictUserCache(username);
        boolean saved = false;
        if(saveLogin == null || saveLogin){
            saved = true;
        }
        User selectUser = selectByUserName(username);
        // 菜单和角色的数组
        List<String> permissionTitleList = new ArrayList<>();
        if(tokenProperties.getSaveRoleFlag()){
            for(PermissionDTO p : selectUser.getPermissions()){
                if(!ZwzNullUtils.isNull(p.getTitle()) && !ZwzNullUtils.isNull(p.getPath())) {
                    permissionTitleList.add(p.getTitle());
                }
            }
            for(RoleDTO r : selectUser.getRoles()){
                permissionTitleList.add(r.getName());
            }
        }
        String ansUserToken = UUID.randomUUID().toString().replace(TOKEN_REPLACE_FRONT_STR, TOKEN_REPLACE_BACK_STR);
        TokenUser tokenUser = new TokenUser(selectUser.getUsername(), permissionTitleList, saved);
        // 单点登录删除旧Token
        if(tokenProperties.getSsoFlag()) {
            String oldToken = redisTemplate.opsForValue().get(ZwzLoginProperties.USER_TOKEN_PRE + selectUser.getUsername());
            if (StrUtil.isNotBlank(oldToken)) {
                redisTemplate.delete(ZwzLoginProperties.HTTP_TOKEN_PRE + oldToken);
            }
        }
        // 保存至Redis备查
        if(saved){
            redisTemplate.opsForValue().set(ZwzLoginProperties.USER_TOKEN_PRE + selectUser.getUsername(), ansUserToken, tokenProperties.getUserSaveLoginTokenDays(), TimeUnit.DAYS);
            redisTemplate.opsForValue().set(ZwzLoginProperties.HTTP_TOKEN_PRE + ansUserToken, new Gson().toJson(tokenUser), tokenProperties.getUserSaveLoginTokenDays(), TimeUnit.DAYS);
        }else{
            redisTemplate.opsForValue().set(ZwzLoginProperties.USER_TOKEN_PRE + selectUser.getUsername(), ansUserToken, tokenProperties.getUserTokenInvalidDays(), TimeUnit.MINUTES);
            redisTemplate.opsForValue().set(ZwzLoginProperties.HTTP_TOKEN_PRE + ansUserToken, new Gson().toJson(tokenUser), tokenProperties.getUserTokenInvalidDays(), TimeUnit.MINUTES);
        }
        return ansUserToken;
    }

    @ApiOperation(value = "查询指定用户的权限列表")
    public List<GrantedAuthority> getCurrUserPerms(String userName){
        List<GrantedAuthority> ans = new ArrayList<>();
        User selectUser = selectByUserName(userName);
        if(selectUser == null){
            return ans;
        }
        // 菜单/按钮权限
        if(selectUser.getPermissions() != null) {
            for(PermissionDTO vo : selectUser.getPermissions()){
                if(!ZwzNullUtils.isNull(vo.getTitle())) {
                    ans.add(new SimpleGrantedAuthority(vo.getTitle()));
                }
            }
        }
        // 角色也必须放进 authorities，否则 hasRole 判断永远不成立
        if(selectUser.getRoles() != null) {
            for(RoleDTO role : selectUser.getRoles()){
                if(!ZwzNullUtils.isNull(role.getName())) {
                    ans.add(new SimpleGrantedAuthority(role.getName()));
                }
            }
        }
        return ans;
    }

    @ApiOperation(value = "查询当前Token的用户对象")
    public User getCurrUser(){
        Object selectUser = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if(Objects.equals("anonymousUser",selectUser.toString())){
            throw new ZwzException("登陆失效");
        }
        UserDetails user = (UserDetails) selectUser;
        return selectByUserName(user.getUsername());
    }
}
