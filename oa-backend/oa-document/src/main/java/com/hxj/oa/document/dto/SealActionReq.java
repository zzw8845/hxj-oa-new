package com.hxj.oa.document.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用印 / 归还动作请求。
 *
 * <p>以 {@code documentId} 为主键而非 seal_apply.id：界面上的操作人是在**单据**上点
 * 「登记用印」「归还」的，他手里只有单据；用印申请行由服务端按需派生（见
 * {@code SealService#act}）。这样前端不需要先查一次"这张单有没有用印申请行"，
 * 也就不会出现"没查到 → 报 404"这种把正常业务说成故障的体验。
 */
@Data
public class SealActionReq {

    @NotNull(message = "单据ID不能为空")
    private Long documentId;

    /** 备注（台账里留痕，例如"已当面归还"） */
    @Size(max = 500, message = "备注不能超过 500 字")
    private String remark;
}
