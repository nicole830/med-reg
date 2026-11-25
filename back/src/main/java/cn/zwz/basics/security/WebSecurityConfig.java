package cn.zwz.basics.security;

import cn.zwz.basics.security.jwt.JwtRoleFilter;
import cn.zwz.basics.utils.SecurityUtil;
import cn.zwz.basics.parameter.NoAuthenticationProperties;
import cn.zwz.basics.parameter.ZwzLoginProperties;
import cn.zwz.basics.security.jwt.AuthenticationFailHandler;
import cn.zwz.basics.security.jwt.AuthenticationSuccessHandler;
import cn.zwz.basics.security.jwt.ZwzAccessDeniedHandler;
import cn.zwz.basics.security.validate.ImageValidateFilter;import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.annotation.web.configurers.ExpressionUrlAuthorizationConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * SpringSecurity 配置
 * @author 郑为中
 */
@Configuration
@EnableGlobalMethodSecurity(prePostEnabled=true)
public class WebSecurityConfig extends WebSecurityConfigurerAdapter {

    /**
     * 管理端写接口白名单式保护：只允许 ROLE_ADMIN
     * 说明：原来依赖 a_permission 的 URL 权限表，但表里没有 URL 规则，等于全部放行
     */
    private static final String[] ADMIN_ONLY_URLS = {
            "/zwz/role/**",
            "/zwz/myUser/**",
            "/zwz/log/**",
            "/zwz/redis/**",
            "/zwz/code/**",
            "/zwz/dict/**",
            "/zwz/setting/**",
            "/zwz/upload/**",
            "/zwz/file/getByCondition",
            "/zwz/file/copy",
            "/zwz/file/rename",
            "/zwz/file/delete",
            "/zwz/department/add",
            "/zwz/department/edit",
            "/zwz/department/delByIds",
            "/zwz/user/getUserList",
            "/zwz/user/getByCondition",
            "/zwz/user/getAll",
            "/zwz/user/getByDepartmentId/**",
            "/zwz/user/delByIds",
            "/zwz/permission/getAllList",
            "/zwz/permission/add",
            "/zwz/permission/edit",
            "/zwz/permission/delByIds",
            "/zwz/permission/search",
            "/zwz/permission/getUserByPermission",
            "/zwz/hospitalOrder/getAll",
            "/zwz/hospitalOrder/getByPage",
            "/zwz/hospitalOrder/insertOrUpdate",
            "/zwz/hospitalOrder/delByIds",
            "/zwz/paymentRecord/getByPage",
            "/zwz/doctor/insertOrUpdate",
            "/zwz/doctor/delByIds",
            "/zwz/doctorScheduling/addNumber",
            "/zwz/doctorScheduling/insertOrUpdate",
            "/zwz/doctorScheduling/delByIds",
            "/zwz/hospitalNews/insertOrUpdate",
            "/zwz/hospitalNews/delByIds",
            "/zwz/hospitalNews/set_top_by_id",
            "/zwz/hospitalNews/set_public_by_id",
            "/zwz/hospitalSubject/insertOrUpdate",
            "/zwz/hospitalSubject/delByIds",
            "/zwz/messageBoard/delByIds"
    };

    @Autowired
    private ZwzLoginProperties zwzLoginProperties;

    @Autowired
    private NoAuthenticationProperties noAuthenticationProperties;

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Autowired
    private AuthenticationSuccessHandler authenticationSuccessHandler;

    @Autowired
    private AuthenticationFailHandler authenticationFailHandler;

    @Autowired
    private ZwzAccessDeniedHandler zwzAccessDeniedHandler;

    @Autowired
    private ImageValidateFilter imageValidateFilter;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SecurityUtil securityUtil;

    @Override
    protected void configure(AuthenticationManagerBuilder authenticationManagerBuilder) throws Exception {
        authenticationManagerBuilder.userDetailsService(userDetailsService).passwordEncoder(new BCryptPasswordEncoder());
    }

    @Override
    @ApiOperation(value = "SpringSecurity配置")
    protected void configure(HttpSecurity httpSecurity) throws Exception {
        ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry expressionInterceptUrlRegistry = httpSecurity.authorizeRequests();
        for(String authenticationUrl : noAuthenticationProperties.getAuthentication()){
            expressionInterceptUrlRegistry.antMatchers(authenticationUrl).permitAll();
        }
        // 管理端写接口统一要求管理员角色
        expressionInterceptUrlRegistry.antMatchers(ADMIN_ONLY_URLS).hasAuthority("ROLE_ADMIN");
        expressionInterceptUrlRegistry.and().formLogin()
                // 默认提示登陆的接口
                .loginPage("/zwz/common/needLogin")
                // 默认登陆的接口
                .loginProcessingUrl("/zwz/login")
                .permitAll()
                // 登陆成功
                .successHandler(authenticationSuccessHandler)
                // 登陆失败
                .failureHandler(authenticationFailHandler)
                .and()
                // IFrame跨域
                .headers().frameOptions().disable()
                .and()
                .logout()
                .permitAll()
                .and()
                .authorizeRequests()
                .anyRequest()
                // 请求的身份认证
                .authenticated()
                .and()
                // 跨域配置
                .cors().and()
                // 关闭跨域拦截
                .csrf().disable()
                // 配置JWT
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                // 系统菜单权限拦截：未认证时直接返回 JSON 401，避免 302 跳转登录页
                .exceptionHandling()
                .authenticationEntryPoint((request, response, exception) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.setStatus(401);
                    response.getWriter().write("{\"success\":false,\"code\":401,\"message\":\"登陆失效\",\"result\":null}");
                })
                .accessDeniedHandler(zwzAccessDeniedHandler)
                .and()
                // 验证码过滤
                //请把我写的 imageValidateFilter 放在 UsernamePasswordAuthenticationFilter 的前面执行。
                //UsernamePasswordAuthenticationFilter 是 Spring Security 内置的一个非常核心的过滤器。它的主要职责是处理表单登录（即拦截默认的 /login 请求，提取用户输入的用户名和密码，并交给系统进行认证）。
                .addFilterBefore(imageValidateFilter, UsernamePasswordAuthenticationFilter.class)
                // JWT认证过滤
                .addFilter(new JwtRoleFilter(authenticationManager(), zwzLoginProperties, stringRedisTemplate, securityUtil));
    }
}
