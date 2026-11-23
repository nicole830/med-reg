package cn.zwz.basics.utils;

import cn.zwz.basics.exception.ZwzException;
import cn.zwz.basics.baseVo.PageVo;
import cn.zwz.data.utils.ZwzNullUtils;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.ApiModelProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 封装的分页插件
 * @author 郑为中
 */
public class PageUtil {

    /**
     * 排序字段白名单：只允许字母开头、字母/数字/下划线组成，最长 64 位
     */
    private static final Pattern SAFE_SORT_FIELD = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    /**
     * 不允许作为排序字段的 SQL 关键字（整词匹配，避免 updateTime 这类字段被误杀）
     */
    private static final Set<String> NO_CAN_USE_WORDS = new HashSet<>(Arrays.asList(
            "drop", "select", "master", "insert", "truncate", "declare", "delete", "sleep", "update", "alter",
            "union", "where", "from", "order", "group", "having", "into", "table", "database", "and", "or", "like"));

    private static final String SORT_BY_ASC = "asc";

    private static final String SORT_BY_DESC = "desc";

    private static final String CAMEL_STEP_STR = "_";

    private static final String NULL_STR = "";

    @ApiModelProperty(value = "JPA分页方法")
    public static Pageable initPage(PageVo page){
        Pageable able = null;
        int pageNumber = page.getPageNumber();
        int pageSize = page.getPageSize();
        String sort = page.getSort();
        String order = page.getOrder();
        SQLInject(sort);
        pageNumber = pageNumber < 1 ? 1 : pageNumber;
        pageSize = pageSize < 1 ? 1 : pageSize;
        pageSize = pageSize > 100 ? 100 : pageSize;
        if(!ZwzNullUtils.isNull(sort)) {
            Sort.Direction direction = ZwzNullUtils.isNull(order) ? Sort.Direction.DESC : Sort.Direction.valueOf(order.toUpperCase());
            Sort sortBy = Sort.by(direction, sort);
            able = PageRequest.of(pageNumber - 1, pageSize, sortBy);
        } else {
            able = PageRequest.of(pageNumber - 1, pageSize);
        }
        return able;
    }

    @ApiModelProperty(value = "MybatisPlus分页方法")
    public static Page initMpPage(PageVo page){
        Page newPage = null;
        int pageNumber = page.getPageNumber();
        int pageSize = page.getPageSize();
        String sort = page.getSort();
        String order = page.getOrder();
        SQLInject(sort);
        pageNumber = pageNumber < 1 ? 1 : pageNumber;
        pageSize = pageSize < 1 ? 1 : pageSize;
        pageSize = pageSize > 100 ? 100 : pageSize;
        if(!ZwzNullUtils.isNull(sort)) {
            Boolean isAsc = false;
            if(ZwzNullUtils.isNull(order)) {
                isAsc = false;
            } else {
                if(Objects.equals(order.toLowerCase(),SORT_BY_DESC)){
                    isAsc = false;
                } else if(Objects.equals(order.toLowerCase(),SORT_BY_ASC)){
                    isAsc = true;
                }
            }
            newPage = new Page(pageNumber, pageSize);
            newPage.addOrder(isAsc ? OrderItem.asc(camel2Underline(sort)) : OrderItem.desc(camel2Underline(sort)));
        } else {
            newPage = new Page(pageNumber, pageSize);
        }
        return newPage;
    }

    @ApiModelProperty(value = "自定义分页方法")
    public static List listToPage(PageVo page, List list) {
        int pageNumber = page.getPageNumber() - 1;
        int pageSize = page.getPageSize();
        pageNumber = pageNumber < 1 ? 1 : pageNumber;
        pageSize = pageSize < 1 ? 1 : pageSize;
        pageSize = pageSize > 100 ? 100 : pageSize;
        int startIndex = pageNumber * pageSize;
        int endIndex = pageNumber * pageSize + pageSize;
        if(startIndex > list.size()){
            return new ArrayList();
        } else if(endIndex > list.size() - 1) {
            return list.subList(startIndex, list.size());
        } else {
            return list.subList(startIndex, endIndex);
        }
    }

    @ApiModelProperty(value = "防 Mybatis Plus 的 SQL 注入攻击")
    public static void SQLInject(String sqlStr){
        if (ZwzNullUtils.isNull(sqlStr)) {
            return;
        }
        String field = sqlStr.trim();
        // 白名单：只允许普通字段名，拦截分号、空格、括号、逗号等注入载荷
        if (!SAFE_SORT_FIELD.matcher(field).matches()) {
            throw new ZwzException(sqlStr + " 单词不合法");
        }
        // 关键字整词匹配，不再用 contains，避免 updateTime / createTime 被误伤
        if (NO_CAN_USE_WORDS.contains(field.toLowerCase())) {
            throw new ZwzException(sqlStr + " 单词不合法");
        }
    }

    @ApiModelProperty(value = "驼峰转下划线")
    public static String camel2Underline(String underlineContent) {
        if (ZwzNullUtils.isNull(underlineContent)) {
            return NULL_STR;
        }
        if(underlineContent.length() < 2){
            return underlineContent.toLowerCase();
        }
        StringBuffer camelUnderlineTempBuf = new StringBuffer();
        for(int i = 1; i < underlineContent.length(); i ++){
            camelUnderlineTempBuf.append(Character.isUpperCase(underlineContent.charAt(i)) ? CAMEL_STEP_STR + Character.toLowerCase(underlineContent.charAt(i)) : underlineContent.charAt(i));
        }
        String ans = underlineContent.charAt(0) + camelUnderlineTempBuf.toString();
        return ans.toLowerCase();
    }
}
