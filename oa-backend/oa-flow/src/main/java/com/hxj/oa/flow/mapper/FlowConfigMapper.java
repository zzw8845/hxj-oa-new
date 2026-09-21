package com.hxj.oa.flow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.flow.entity.FlowConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FlowConfigMapper extends BaseMapper<FlowConfig> {

    /** 取单据类型编码，用于拼装流程定义 KEY（如 DAILY_PAYMENT_V1） */
    @Select("SELECT code FROM document_type WHERE id = #{docTypeId} AND deleted = 0")
    String selectDocTypeCode(@Param("docTypeId") Long docTypeId);

    /** 单据类型是否存在 */
    @Select("SELECT COUNT(*) FROM document_type WHERE id = #{docTypeId} AND deleted = 0")
    int countDocType(@Param("docTypeId") Long docTypeId);

    /** 单据类型所属业务大类 DAILY/BIZ/REIMBURSE/SEAL */
    @Select("SELECT category FROM document_type WHERE id = #{docTypeId} AND deleted = 0")
    String selectDocTypeCategory(@Param("docTypeId") Long docTypeId);

    /** 单据类型名称 */
    @Select("SELECT name FROM document_type WHERE id = #{docTypeId} AND deleted = 0")
    String selectDocTypeName(@Param("docTypeId") Long docTypeId);

    /**
     * 把单据类型的默认流程指向新的生效版本。
     * 这是「版本化」闭环的最后一步：新版本记录 + 部署完成后，
     * 后续新提交的单据才会走到新链路上；在途单据仍然引用启动时的旧 flowConfigId。
     */
    @Update("UPDATE document_type SET flow_config_id = #{flowConfigId} WHERE id = #{docTypeId} AND deleted = 0")
    int updateDocTypeFlowConfig(@Param("docTypeId") Long docTypeId, @Param("flowConfigId") Long flowConfigId);
}
