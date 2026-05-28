package com.example.customeragent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class CustomerTools {

    private static final Logger log = LoggerFactory.getLogger(CustomerTools.class);

    @Tool(description = "根据订单号查询订单当前状态，如待支付、已发货、已完成等")
    public String getOrderStatus(@ToolParam(description = "订单号，格式如 ORD20240501001") String orderId) {
        log.info("调用工具 getOrderStatus: orderId={}", orderId);
        if (orderId == null || orderId.isBlank()) {
            return "请提供有效的订单号";
        }
        return "订单 " + orderId + " 当前状态：已发货，预计 2026-06-01 前送达。" +
                "如需详细物流信息，请使用物流查询功能。";
    }

    @Tool(description = "查询订单的物流跟踪信息，包括流转节点和预计送达时间")
    public String trackLogistics(@ToolParam(description = "订单号，格式如 ORD20240501001") String orderId) {
        log.info("调用工具 trackLogistics: orderId={}", orderId);
        if (orderId == null || orderId.isBlank()) {
            return "请提供有效的订单号";
        }
        return "订单 " + orderId + " 物流信息：\n"
                + "- 2026-05-25 20:00 已揽收（上海分拣中心）\n"
                + "- 2026-05-26 10:00 运输中（上海→北京）\n"
                + "- 2026-05-27 08:00 到达北京分拣中心\n"
                + "- 2026-05-28 09:00 派送中（预计今日送达）\n"
                + "承运商：圆通速递，单号：YT1234567890";
    }

    @Tool(description = "提交退货退款申请，生成退货运单号，返回退货地址和注意事项")
    public String submitRefundRequest(
            @ToolParam(description = "需要退货的订单号") String orderId,
            @ToolParam(description = "退货原因，如质量问题、尺寸不合适等") String reason) {
        log.info("调用工具 submitRefundRequest: orderId={}, reason={}", orderId, reason);
        if (orderId == null || orderId.isBlank()) {
            return "请提供有效的订单号";
        }
        String refundId = "REF" + Math.abs(orderId.hashCode() % 1000000);
        return "退货申请已提交！\n"
                + "- 退货运单号：" + refundId + "\n"
                + "- 请在 7 天内将商品寄回：上海市浦东新区xx路100号 售后部\n"
                + "- 退货运费由我方承担（需先垫付，收到退货后返还）\n"
                + "- 退款将在收到退货后 3-5 个工作日内原路返回";
    }
}
