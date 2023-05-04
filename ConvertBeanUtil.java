import com.alibaba.fastjson.JSONObject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.*;
import java.util.*;

/**
 * @author hzq
 * @description bean转换工具类
 */
public class ConvertBeanUtil {
    private ConvertBeanUtil(){}
    
    /**
     * 标记在需要填充的属性上 指名要填充的属性名
     */
    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface CloneableField {

        //对应的源端属性名称
        String fieldName();

        //集合或数组形式时从源端的第ordered个索引处开始同步
        int start() default 0;
    }
    
    /**
     * 将多个源对象转换为一个对象(属性填充)
     * 注意：如果属性重复会被覆盖
     */
    public static <R> void fill(R target,Object... sources){
        if(sources == null){
            return ;
        }
        for (Object source : sources) {
            fill(source.getClass(),target,source);
        }
    }
    
    /**
     * 转换源bean获取目标bean
     */
    public static <T,R> R convert(String json,Class<T> source,Class<R> target){
        return convert(source,target,convert(json,source));
    }

    public static <R> R convert(Class<R> target,Object... sources){
        if(sources == null || target == null){
            return null;
        }
        R result = null;
        try {
            result = target.newInstance();
            for (Object source : sources) {
                fill(source.getClass(),result,source);
            }
        } catch (InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return result;
    }
    
    public static <K,T> K convert(T source,Class<K> target){
        if(source == null || target == null){
            return null;
        }
        return convert(source.getClass(), target,source);
    }
    
    public static <T> T convert(String json,Class<T> target){
        return JSONObject.parseObject(json,target);
    }

    public static <T,R> R convert(Class<?> sourceCls, Class<R> targetCls, T sourceObj){
        R result = null;
        try {
            result = targetCls.newInstance();
            fill(sourceCls,result,sourceObj);
        } catch (InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return result;
    }
    
    /**
     * 数据填充：将源bean内容填充到目标bean中
     * 目标端子属性不能为数组类型
     */
    public static <T,R> void fill(Class<?> sourceCls,R target,T sourceObj){
        try {
            Class<?> targetCls = target.getClass();
            Field[] sourceFields = sourceCls.getDeclaredFields();
            Field[] targetFields = targetCls.getDeclaredFields();
            //获取targetFiled的所有需要填充的属性
            List<Field> cloneFieldList = getCloneField(targetFields);
            for (Field sourceField : sourceFields) {
                sourceField.setAccessible(true);
                //获取到变量的类型
                Class<?> sourceFieldType = sourceField.getType();

                if (isBaseType(sourceFieldType)) {
                    doSetValue(targetCls,sourceField,target,sourceObj);
                }else if(cloneFieldList.size() > 0){ //目标属性不是基本类型且需要填充的属性列表不为空 深度搜索填充
                    //获取匹配的目标属性对象
                    Field cloneField = matchTargetField(sourceField,cloneFieldList);
                    //匹配到进行填充
                    doFill(cloneField,sourceField,target,sourceObj,sourceFieldType);
                }
            }
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 进行数据填充
     */
    private static <R, T> void doFill(Field cloneField, Field sourceField, R target, T sourceObj, Class<?> sourceFieldType) throws IllegalAccessException {
        if(cloneField != null){
            //目标子属性对象
            Object targetFieldObj = cloneField.get(target);
            //源子属性
            Object sourceFieldObj = sourceField.get(sourceObj);
            //源子属性不为空时才进行填充
            if(sourceFieldObj != null){
                if(targetFieldObj == null)//初始化子属性对象
                    targetFieldObj = initSubField(cloneField,sourceField,sourceObj,target);
                    if(targetFieldObj != null){
                        Class<?> sourceType = getCollectionType(sourceField.getType());
                        Class<?> targetType = getCollectionType(cloneField.getType());
                        //集合或数组类型数据填充
                        multipleTypeFill(sourceType,targetType,cloneField,sourceField,sourceObj,targetFieldObj,target);
                        //当前属性填充
                        fill(sourceFieldType,targetFieldObj,sourceFieldObj);
                    }
                }
            }
        }
    }
    
    /**
     * 初始化子属性对象
     */
    private static <T,R> Object initSubField(Field cloneField, Field sourceField, T sourceObj ,R target) {
        Object result = null;
        try {
            //初始化目标子属性对象
            //目标子属性类型为数组
            if(cloneField.getType().isArray()){
                //获取数组元素类型
                Class<?> elementType = cloneField.getType().getComponentType();
                //获取源属性类型
                Class<?> sourceType = getCollectionType(sourceField.getType()); 
                int size = 0;
                //获取源集合的大小 初始化目标集合
                if(sourceType == collectionType[0] || sourceType == collectionType[1]){ //collection map类型
                    size = (int) sourceField.get(sourceObj).getClass().getDeclaredMethod("size").invoke(sourceField.get(sourceObj));
                }else if(sourceType == Array.class){ //array类型
                    size = Array.getLength(sourceObj);
                }
                result = Array.newInstance(elementType,size);
            }else {
                result = cloneField.getType().newInstance();
            }
            //设置目标子属性对象到目标对象中
            cloneField.set(target,result);
        } catch (InstantiationException | InvocationTargetException | NoSuchMethodException | IllegalAccessException e) {
            //目标子属性为空 且生成时失败
            e.printStackTrace();
        }
        return result;
    }
    
     /**
     * 集合或数组类型数据填充
     */
    private static <R> void multipleTypeFill(
            Class<?> sourceType,
            Class<?> targetType,
            Field cloneField,
            Field sourceField,
            Object sourceObj,
            Object targetFieldObj,
            R target) throws IllegalAccessException {
            
        //不为集合或数组类型 不进行数据填充
        if(sourceType == null && targetType == null){
            return;
        }

        //当前源属性或目标属性是集合、数组类型
        //获取目标属性上的注解的索引信息
        int start = getCloneableFieldAnnotationStart(cloneField);
        if(sourceType != null){
            if(sourceType == collectionType[0]){ //源端是Map类型
                //map类型数据填充
                mapTypeFill(targetType,cloneField,sourceField,sourceObj,targetFieldObj,target,start);
            } else if(sourceType == collectionType[1]){ //源端是Collection类型
                //集合类型数据填充
                collectionTypeFill(targetType,cloneField,sourceField,sourceObj,targetFieldObj,target,start);
            } else if(sourceType == Array.class){ //源端是数组类型
                //array类型数据填充 不支持源端是数组类型的数据填充
                //arrayTypeFill(targetType,cloneField,sourceField,sourceObj,targetFieldObj,target,start);
            }
        }
    }
    
    /**
     * Map类型数据填充 源端是Map类型
     * map 到集合和数组类型的转换只同步value
     */
    private static <R> void mapTypeFill(Class<?> targetType,Field cloneField,Field sourceField,Object sourceObj,Object targetFieldObj,R targetObj,int start)
            throws IllegalAccessException {
        AbstractTypeFill.fill(targetType,cloneField,sourceField,sourceObj,targetFieldObj,targetObj,start,collectionType[0]);
    }
    
    /**
     * 抽象类型填充
     */
    private static abstract class AbstractTypeFill{

        private static <T,R> void fill(
                Class<?> targetType,
                Field cloneField,
                Field sourceField,
                Object sourceObj,
                Object targetFieldObj,
                R targetObj,
                int start,
                Class<T> targetFieldType)
                throws IllegalAccessException {
            //获取源属性对象并且转换为对应类型
            Object o = getSourceFieldObject(sourceField,sourceObj,targetFieldType);
            //目标属性为集合或数组
            if(targetType != null){ //多对多数据填充
                //获取目标端类型
                if(targetType == collectionType[0]){ //是Map类型 map to map
                    //获取目标端map对象
                    Map<Object,Object> target = (Map<Object,Object>) cloneField.get(targetObj);
                    toMap(o,target,start,targetFieldType);
                } else if(targetType == collectionType[1]){ //是Collection类型 map to Collection
                    //获取目标端collection对象
                    Collection<Object> target = (Collection<Object>) cloneField.get(targetObj);
                    toCollectionType(o,target,start,targetFieldType);
                } else if(targetType == Array.class){ //是数组类型 map to array
                    //获取目标端array对象
                    List<Object> arrayList = new ArrayList<>();
                    //将数组转换为List
                    Collections.addAll(arrayList,cloneField.get(targetObj));
                    //填充数据
                    toArrayType(o,arrayList,start,targetFieldType);
                    //将arrList中的数据填充到目标数组中
                    Class<?> elementType = targetFieldObj.getClass().getComponentType();
                    for (int i = 1; i < arrayList.size(); i++) {
                        Array.set(targetFieldObj,i - 1,convert(arrayList.get(i),elementType));
                    }
                }
             }else{ //多对一数据填充
                //获取到泛型类型
                Class<?> genericType = getGenericType(sourceField);
                if(genericType != null){ //有泛型设置
                    //仅设置索引位置的数据到目标属性中
                    separate(o,sourceField,targetFieldObj,targetFieldType,start);
                }
            }
        }
        
        /**
         * 获取源属性对象并且转换为对应类型
         */
        private static <T> Object getSourceFieldObject(Field sourceField, Object sourceObj, Class<T> targetFieldType) throws IllegalAccessException {
            Object result = null;
            if(targetFieldType == collectionType[0]){
                result = (Map<Object, Object>) sourceField.get(sourceObj);
            }else if(targetFieldType == collectionType[1]){
                result = (Collection<?>) sourceField.get(sourceObj);
            }else if(targetFieldType == Array.class){
                //不支持源端是数组类型的数据填充
            }
            return result;
        }
        
        /**
         * 向array类型填充数据
         */
        private static <T> void toArrayType(Object source, List<Object> arrayList, int start,Class<T> targetFieldType){
            if(targetFieldType == collectionType[0]){
                ConvertBeanUtil.mapToArrayType((Map<Object, Object>) source,arrayList,start);
            }else if(targetFieldType == collectionType[1]){
                ConvertBeanUtil.collectionToArrayType((Collection<?>) source,arrayList,start);
            }else if(targetFieldType == Array.class){
                //array to array 不同步
            }
        }
        
        /**
         * 向collection类型填充数据
         */
        private static <T> void toCollectionType(Object source, Collection<Object> target, int start,Class<T> targetFieldType) {
            if(targetFieldType == collectionType[0]){
                ConvertBeanUtil.mapToCollectionType((Map<Object, Object>) source,target,start);
            }else if(targetFieldType == collectionType[1]){
                ConvertBeanUtil.collectionToCollectionType((Collection<?>) source,target,start);
            }else if(targetFieldType == Array.class){
                //array to collection不同步
            }
        }
        
        /**
         * 向map类型填充数据
         */
        private static <T> void toMap(Object source, Map<Object, Object> target, int start,Class<T> targetFieldType) {
            if(targetFieldType == collectionType[0]){
                ConvertBeanUtil.mapToMapType((Map<Object, Object>) source,target,start);
            }else if(targetFieldType == collectionType[1]){
                //collection to map不同步
            }else if(targetFieldType == Array.class){
                //array to map 不同步
            }
        }
  
        /**
         * 多对一设置目标属性值 仅设置索引位置的数据到目标属性中
         */
        private static <T> void separate(Object o, Field sourceField, Object targetFieldObj, Class<T> targetFieldType,int start) {
            //获取到泛型类型
            Class<?> genericType = getGenericType(sourceField);
            if(genericType != null){ //有泛型设置
                //仅设置索引位置的数据到目标属性中
                if(targetFieldType == collectionType[0]){
                    for (Map.Entry<Object, Object> entry : ((Map<Object,Object>)o).entrySet()) {
                        if(start <= 0){
                            ConvertBeanUtil.fill(genericType,targetFieldObj,entry.getValue());
                            break;
                        }
                    }
                }else if(targetFieldType == collectionType[1] || targetFieldType == Array.class){
                    //仅设置索引位置的数据到目标属性中
                    for (Object data : ((Collection)o)) {
                        if(start <= 0){
                            ConvertBeanUtil.fill(genericType,targetFieldObj,data);
                            break;
                        }
                    }
                }
            }
        }
    }
    
    /**
     * map to array 数据填充
     */
    private static void mapToArrayType(Map<Object, Object> map, List<Object> arrayList, int start) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if(start <= 0){
                arrayList.add(entry.getValue());
            }
            start--;
        }
    }
    
    /**
     * map to collection 数据填充
     */
    private static void mapToCollectionType(Map<Object, Object> map, Collection<Object> target, int start) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if(start <= 0){
                target.add(entry.getValue());
            }
            start--;
        }
    }
    
    /**
     * map to map 数据填充
     */
    private static void mapToMapType(Map<Object,Object> map, Map<Object,Object> target, int start) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if(start <= 0){
                target.put(entry.getKey(),entry.getValue());
            }
            start--;
        }
    }
    
    /**
     * 集合类型数据填充 源端是Collection类型
     */
    private static <R> void collectionTypeFill(Class<?> targetType,Field cloneField,Field sourceField,Object sourceObj,Object targetFieldObj,R targetObj,int start)
            throws IllegalAccessException {
        AbstractTypeFill.fill(targetType,cloneField,sourceField,sourceObj,targetFieldObj,targetObj,start,collectionType[1]);
    }
    
    /**
     * 源端collection to 目标端 array
     */
    private static void collectionToArrayType(Collection<?> collection,List<Object> target,int start) {
        final int[] idx = {start};
        collection.forEach(data -> {
            if(idx[0] <= 0){
                target.add(data);
            }
            idx[0]--;
        });
    }
    
    /**
     * 源端collection to 目标端 collection
     */
    private static void collectionToCollectionType(Collection<?> collection,Collection<Object> target,int start) {
        //根据开始索引将源数据设置到目标集合或数组中
        for (Object o : collection) {
            //索引倒减 到达0时开始填充数据至填充完毕
            if (start <= 0) {
                //开始填充数据到目标源属性集合中
                target.add(o);
            }
            start--;
        }
    }
    
    /**
     * 获取此属性的泛型类型
     */
    private static Class<?> getGenericType(Field sourceField) {
        Type genericType = sourceField.getGenericType();
        if(genericType instanceof ParameterizedType){
            return (Class<?>) ((ParameterizedType) genericType).getActualTypeArguments()[0];
        }
        return null;
    }
    
    /**
     * 判断此类型是否为集合或数组类型并获取其类型
     */
    private static Class<?> getCollectionType(Class<?> type) {
        for (Class<?> targetType : collectionType) {
            if (isFrom(type,targetType)) {
                //集合类型
                return targetType;
            }
        }
        //数组类型
        return type.isArray() ? Array.class : null;
    }
    
    /**
     * 深度搜索当前类型是否为目标类型的子类
     */
    private static boolean isFrom(Class<?> type, Class<?> targetType) {
        if(type == null){
            return false;
        }
        //获取父类 递归搜索
        return targetType.isAssignableFrom(type) || isFrom(type.getSuperclass(), targetType);
    }

    /**
     * 匹配目标field并设置值
     */
    private static <T,R> void doSetValue(Class<?> targetCls, Field sourceField,R target,T sourceObj) throws IllegalAccessException {
        Field[] targetFields = targetCls.getDeclaredFields();
        for (Field targetField : targetFields) {
            targetField.setAccessible(true);
            String targetFieldName = getCloneableFieldAnnotationName(targetField);
            if(targetFieldName != null){
                if (targetFieldName.equals(sourceField.getName())) {
                    targetField.set(target, sourceField.get(sourceObj));
                    break;
                }
            }else{
                if(targetField.getName().equals(sourceField.getName())){
                    targetField.set(target, sourceField.get(sourceObj));
                    break;
                }
            }
        }
    }
    
    /**
     * 根据fieldName匹配到目标属性
     */
    private static Field matchTargetField(Field sourceField, List<Field> cloneFieldList) {
        for (Field cloneField : cloneFieldList) {
            if(sourceField.getName().equals(getCloneableFieldAnnotationName(cloneField))){
                return cloneField;
            }
        }
        return null;
    }
    
    /**
     * 获取所有需要填充的属性
     */
    private static List<Field> getCloneField(Field[] targetFields) {
        List<Field> cloneFieldList = new ArrayList<>();
        for (Field targetField : targetFields) {
            if(getCloneableFieldAnnotationName(targetField) != null){
                targetField.setAccessible(true);
                cloneFieldList.add(targetField);
            }
        }
        return cloneFieldList;
    }
    
    /**
     * 判断当前属性是否进行填充 进行填充则返回要填充的属性对应的名称
     */
    private static String getCloneableFieldAnnotationName(Field sourceField) {
        CloneableField cloneField = sourceField.getDeclaredAnnotation(CloneableField.class);
        return cloneField == null ? null : cloneField.fieldName();
    }
     
     /**
     * 判断当前属性是否进行填充 进行填充则返回要填充的属性对应的同步索引开始位置
     */
    private static int getCloneableFieldAnnotationStart(Field sourceField){
        CloneableField cloneField = sourceField.getDeclaredAnnotation(CloneableField.class);
        return cloneField == null ? -1 : cloneField.start();
    }
    
    /**
     * 判断是否为基础数据类型
     * @param sourceFieldType
     * @return
     */
    private static boolean isBaseType(Class<?> sourceFieldType) {
        for (Class<?> cls : baseType) {
            if(cls.equals(sourceFieldType)){
                return true;
            }
        }
        return false;
    }
    
    /**
     * 基础类型
     */
    private static final Class<?>[] baseType = {
            int.class,Integer.class,
            short.class,Short.class,
            long.class,Long.class,
            boolean.class,Boolean.class,
            float.class,Float.class,
            double.class,Double.class,
            byte.class,Byte.class,
            char.class,Character.class,
            String.class
    };
    
    /**
     * 集合类型
     */
    private static final Class<?>[] collectionType = {
            Map.class,
            Collection.class
    };

}
