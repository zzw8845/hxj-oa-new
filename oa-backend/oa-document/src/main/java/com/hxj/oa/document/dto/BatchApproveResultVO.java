package com.hxj.oa.document.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量审批结果：**逐条返回**成败与原因。
 *
 * <p>为什么不搞"全成功才提交"：OA 里"3 条通过、1 条因状态已变而失败"是很有用的结果 ——
 * 全回滚会让用户白等一遍，还得自己找出是哪一条挡住了。逐条返回让用户看到
 * "哪条成功了、哪条为什么没成功"，剩下的单独处理即可。
 */
@Data
public class BatchApproveResultVO {

    private int total;
    private int succeeded;
    private int failed;
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {
        private String taskId;
        private boolean ok;
        /** 失败原因（成功时为空） */
        private String message;
        /** 成功时对应的单据 ID（便于前端定位；单据号成本更高，这里不做额外查询） */
        private Long documentId;

        public static Item ok(String taskId, Long documentId) {
            Item it = new Item();
            it.setTaskId(taskId);
            it.setOk(true);
            it.setDocumentId(documentId);
            return it;
        }

        public static Item fail(String taskId, String message) {
            Item it = new Item();
            it.setTaskId(taskId);
            it.setOk(false);
            it.setMessage(message);
            return it;
        }
    }
}
