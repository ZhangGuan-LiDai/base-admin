package cn.huanzi.qch.baseadmin.util;

import cn.huanzi.qch.baseadmin.annotation.Between;
import cn.huanzi.qch.baseadmin.annotation.In;
import cn.huanzi.qch.baseadmin.annotation.Like;
import cn.huanzi.qch.baseadmin.common.pojo.PageCondition;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.annotation.Transient;
import org.springframework.util.StringUtils;

import javax.persistence.Table;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 拼接SQL工具类
 */
@Slf4j
public class SqlUtil {

    /**
     * 日期转换格式
     */
    private static SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * 数据库驱动类，用于判断数据库类型
     * MySQL：com.mysql.cj.jdbc.Driver
     * postgresql：org.postgresql.Driver
     * Oracle：oracle.jdbc.OracleDriver
     */
    @Value("${string.datasource.driver-class-name}")
    private static String sqlType;

    /**
     * 自动拼接原生SQL的“and”查询条件,支持自定义注解：@Like @Between @In
     *
     * @param entity           实体对象
     * @param sql              待拼接SQL
     * @param ignoreProperties 忽略属性
     */
    public static void appendQueryColumns(Object entity, StringBuilder sql, String... ignoreProperties) {

        try {
            //忽略属性
            List<String> ignoreList1 = Arrays.asList(ignoreProperties);
            //默认忽略分页参数
            List<String> ignoreList2 = Arrays.asList("class", "pageable", "page", "rows", "sidx", "sord");

            //反射获取Class的属性（Field表示类中的成员变量）
            for (Field field : entity.getClass().getDeclaredFields()) {
                //获取授权
                field.setAccessible(true);
                //属性名称
                String fieldName = field.getName();
                //属性的值
                Object fieldValue = field.get(entity);
                //检查Transient注解，是否忽略拼接
                if (!field.isAnnotationPresent(Transient.class)) {
                    String column = SqlUtil.getUnderScoreColumn(fieldName);
                    //值是否为空
                    if (!StringUtils.isEmpty(fieldValue)) {
                        //映射关系：对象属性(驼峰)->数据库字段(下划线)
                        if (!ignoreList1.contains(fieldName) && !ignoreList2.contains(fieldName)) {
                            //开启模糊查询
                            if (field.isAnnotationPresent(Like.class)) {
                                sql.append(" and " + column + " like '%" + SqlUtil.escapeSql((String) fieldValue) + "%'");
                            }
                            //开启等值查询
                            else {
                                sql.append(" and " + column + " = '" + SqlUtil.escapeSql((String) fieldValue) + "'");
                            }
                        }
                    } else {
                        //开启区间查询
                        if (field.isAnnotationPresent(Between.class)) {
                            //获取最小值
                            Field minField = entity.getClass().getDeclaredField(field.getAnnotation(Between.class).min());
                            minField.setAccessible(true);
                            Object minVal = minField.get(entity);
                            //获取最大值
                            Field maxField = entity.getClass().getDeclaredField(field.getAnnotation(Between.class).max());
                            maxField.setAccessible(true);
                            Object maxVal = maxField.get(entity);
                            //开启区间查询，需要使用对应的函数
                            if (field.getType().getName().equals("java.util.Date")) {
                                //MySQL
                                if(sqlType.toLowerCase().contains("com.mysql.cj.jdbc.Driver")){
                                    if (!StringUtils.isEmpty(minVal)) {
                                        sql.append(" and " + column + " > str_to_date( '" + simpleDateFormat.format((Date) minVal) + "','%Y-%m-%d %H:%i;%s')");
                                    }
                                    if (!StringUtils.isEmpty(maxVal)) {
                                        sql.append(" and " + column + " < str_to_date( '" + simpleDateFormat.format((Date) maxVal) + "','%Y-%m-%d %H:%i;%s')");
                                    }
                                }
                                //postgresql
                                if(sqlType.toLowerCase().contains("org.postgresql.Driver")){
                                    if (!StringUtils.isEmpty(minVal)) {
                                        sql.append(" and " + column + " > cast('" + simpleDateFormat.format((Date) minVal) + "' as timestamp)");
                                    }
                                    if (!StringUtils.isEmpty(maxVal)) {
                                        sql.append(" and " + column + " < cast('" + simpleDateFormat.format((Date) maxVal) + "' as timestamp)");
                                    }
                                }
                                //Oracle
                                if(sqlType.toLowerCase().contains("oracle.jdbc.OracleDriver")){
                                    if (!StringUtils.isEmpty(minVal)) {
                                        sql.append(" and " + column + " > to_date( '" + simpleDateFormat.format((Date) minVal) + "','yyyy-mm-dd hh24:mi:ss')");
                                    }
                                    if (!StringUtils.isEmpty(maxVal)) {
                                        sql.append(" and " + column + " < to_date( '" + simpleDateFormat.format((Date) maxVal) + "','yyyy-mm-dd hh24:mi:ss')");
                                    }
                                }
                            }
                        }

                        //开启in查询
                        if (field.isAnnotationPresent(In.class)) {
                            //获取要in的值
                            Field values = entity.getClass().getDeclaredField(field.getAnnotation(In.class).values());
                            values.setAccessible(true);
                            List<String> valuesList = (List<String>) values.get(entity);
                            if (valuesList != null && valuesList.size() > 0) {
                                StringBuilder inValues = new StringBuilder();
                                for (String value : valuesList) {
                                    inValues.append("'").append(SqlUtil.escapeSql(value)).append("'");
                                }
                                sql.append(" and " + column + " in (" + inValues + ")");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            //输出到日志文件中
            log.error(ErrorUtil.errorInfoToString(e));
        }
    }

    /**
     *
     * @param entity 自动拼接实体对象字段
     * @param ignoreProperties 动态参数  忽略拼接的字段
     * @return sql
     */
    public static StringBuilder appendFields(Object entity, String... ignoreProperties) {
        StringBuilder sql = new StringBuilder();
        List<String> ignoreList = Arrays.asList(ignoreProperties);
        try {
            sql.append("select ");

            for (Field field : entity.getClass().getDeclaredFields()) {
                //获取授权
                field.setAccessible(true);
                String fieldName = field.getName();//属性名称
                Object fieldValue = field.get(entity);//属性的值
                //非临时字段、非忽略字段
                if (!field.isAnnotationPresent(Transient.class) && !ignoreList.contains(fieldName)) {
                    //拼接查询字段  驼峰属性转下划线
                    sql.append(SqlUtil.getUnderScoreColumn(fieldName)).append(" ").append(",");
                }
            }
            //处理逗号（删除最后一个字符）
            sql.deleteCharAt(sql.length() - 1);

            String tableName = entity.getClass().getAnnotation(Table.class).name();
            sql.append("from ").append(tableName).append(" where '1' = '1' ");
        } catch (IllegalAccessException e) {
            //输出到日志文件中
            log.error(ErrorUtil.errorInfoToString(e));
        }
        return sql;
    }

    /**
     * 拼接排序SQL
     *
     * @param entityVo Vo类
     * @param sql    待拼接的SQL
     */
    public static void orderByColumn(PageCondition entityVo, StringBuilder sql) {
        String sidx = entityVo.getSidx();
        String sord = entityVo.getSord();

        if (!StringUtils.isEmpty(sidx)) {
            //1.获取Bean
            BeanWrapper srcBean = new BeanWrapperImpl(entityVo);
            //2.获取Bean的属性描述
            PropertyDescriptor[] pds = srcBean.getPropertyDescriptors();
            //3.获取符合的排序字段名
            for (PropertyDescriptor p : pds) {
                String propertyName = p.getName();
                if (sidx.equals(propertyName)) {
                    sql.append(" order by ").append(getUnderScoreColumn(sidx)).append("desc".equalsIgnoreCase(sord) ? " desc" : " asc");
                }
            }
        }
    }

    /**
     * 实体属性转表字段，驼峰属性转下划线，并全部转小写
     */
    private static String getUnderScoreColumn(String fieldName){
        return new PropertyNamingStrategy.SnakeCaseStrategy().translate(fieldName).toLowerCase();
    }

    /**
     * sql转义
     * 动态拼写SQL，需要进行转义防范SQL注入！
     */
    private static String escapeSql(String str) {
        if (str == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char src = str.charAt(i);
            switch (src) {
                case '\'':
                    sb.append("''");// hibernate转义多个单引号必须用两个单引号
                    break;
                case '\"':
                case '\\':
                    sb.append('\\');
                default:
                    sb.append(src);
                    break;
            }
        }
        return sb.toString();
    }
}
