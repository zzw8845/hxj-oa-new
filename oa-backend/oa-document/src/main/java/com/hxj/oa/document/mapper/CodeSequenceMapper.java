package com.hxj.oa.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.document.entity.CodeSequence;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 单据编码序列。三步走保证不重号：
 * 1) INSERT IGNORE 确保序列行存在
 * 2) SELECT ... FOR UPDATE 持有行锁
 * 3) UPDATE 自增
 * 调用方必须包在事务里，否则行锁立即释放。
 */
@Mapper
public interface CodeSequenceMapper extends BaseMapper<CodeSequence> {

    @Insert("""
            INSERT IGNORE INTO code_sequence(company_id, biz_prefix, period, current_val)
            VALUES(#{companyId}, #{prefix}, #{period}, 0)
            """)
    int insertIfAbsent(@Param("companyId") Long companyId,
                       @Param("prefix") String prefix,
                       @Param("period") String period);

    @Select("""
            SELECT current_val FROM code_sequence
            WHERE company_id = #{companyId} AND biz_prefix = #{prefix} AND period = #{period}
            FOR UPDATE
            """)
    Long selectForUpdate(@Param("companyId") Long companyId,
                         @Param("prefix") String prefix,
                         @Param("period") String period);

    @Update("""
            UPDATE code_sequence SET current_val = current_val + 1
            WHERE company_id = #{companyId} AND biz_prefix = #{prefix} AND period = #{period}
            """)
    int increment(@Param("companyId") Long companyId,
                  @Param("prefix") String prefix,
                  @Param("period") String period);
}
