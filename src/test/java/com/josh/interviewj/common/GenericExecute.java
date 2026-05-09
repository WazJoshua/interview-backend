package com.josh.interviewj.common;

import com.alipay.v3.ApiClient;
import com.alipay.v3.ApiException;
import com.alipay.v3.ApiResponse;
import com.alipay.v3.Configuration;
import com.alipay.v3.util.GenericExecuteApi;
import com.alipay.v3.util.model.AlipayConfig;
import com.alipay.v3.util.model.CustomizedParams;
import com.alipay.v3.util.model.OpenApiGenericRequest;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GenericExecute {

    /**
     * Minimal sandbox demo for alipay.trade.page.pay.
     *
     * This method intentionally does NOT replace the original main method.
     * Call it manually when you want to verify the smallest valid page-pay flow.
     *
     * Fixes compared with the large sample in main:
     * 1. puts return_url and notify_url in top-level public params
     * 2. removes unrelated optional fields
     * 3. generates a unique out_trade_no for every run
     * 4. only keeps the required business fields plus timeout_express
     */
    public static void runMinimalPagePayDemo() throws ApiException {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setAlipayConfig(getAlipayConfig());

        GenericExecuteApi api = new GenericExecuteApi();
        Map<String, Object> requestParams = buildMinimalPagePayParams();

        try {
            String pageRedirectionData = api.pageExecute("alipay.trade.page.pay", "GET", requestParams);
            System.out.println("minimal page pay request succeeded");
            System.out.println(pageRedirectionData);
        } catch (ApiException e) {
            System.out.println("minimal page pay request failed");
            System.out.println("errorCode=" + e.getCode());
            System.out.println("message=" + e.getMessage());
            throw e;
        }
    }

    public static void main(String[] args) throws ApiException {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        // 初始化alipay参数（全局设置一次）
        defaultClient.setAlipayConfig(getAlipayConfig());

        GenericExecuteApi api = new GenericExecuteApi();

        // 构造请求参数以调用接口
        Map<String, Object> bizParams = new HashMap<>();
        Map<String, Object> bizContent = new HashMap<>();

        // 设置商户订单号
        bizContent.put("out_trade_no", "20150320010101001");

        // 设置订单总金额
        bizContent.put("total_amount", "88.88");

        // 设置订单标题
        bizContent.put("subject", "Iphone6 16G");

        // 设置产品码
        bizContent.put("product_code", "FAST_INSTANT_TRADE_PAY");

        // 设置订单附加信息
        bizContent.put("body", "Iphone6 16G");

        // 设置PC扫码支付的方式
        bizContent.put("qr_pay_mode", "2");

        // 设置商户自定义二维码宽度
        bizContent.put("qrcode_width", 100);

        // 设置订单包含的商品列表信息
        List<Map<String, Object>> goodsDetail = new ArrayList<>();
        Map<String, Object> goodsDetail0 = new HashMap<>();
        goodsDetail0.put("out_sku_id", "outSku_01");
        goodsDetail0.put("goods_name", "ipad");
        goodsDetail0.put("alipay_goods_id", "20010001");
        goodsDetail0.put("quantity", 1);
        goodsDetail0.put("price", "2000");
        goodsDetail0.put("out_item_id", "outItem_01");
        goodsDetail0.put("goods_id", "apple-01");
        goodsDetail0.put("goods_category", "34543238");
        goodsDetail0.put("categories_tree", "124868003|126232002|126252004");
        goodsDetail0.put("show_url", "http://www.alipay.com/xxx.jpg");
        goodsDetail.add(goodsDetail0);
        bizContent.put("goods_detail", goodsDetail);

        // 设置订单绝对超时时间
        bizContent.put("time_expire", "2016-12-31 10:05:01");

        // 设置建议使用time_expire字段
        bizContent.put("timeout_express", "90m");

        // 设置描述分账信息
        Map<String, Object> royaltyInfo = new HashMap<>();
        royaltyInfo.put("royalty_type", "ROYALTY");
        List<Map<String, Object>> royaltyDetailInfos = new ArrayList<>();
        Map<String, Object> royaltyDetailInfos0 = new HashMap<>();
        royaltyDetailInfos0.put("out_relation_id", "20131124001");
        royaltyDetailInfos0.put("amount_percentage", "100");
        royaltyDetailInfos0.put("amount", "0.1");
        royaltyDetailInfos0.put("batch_no", "123");
        royaltyDetailInfos0.put("trans_in", "2088101126708402");
        royaltyDetailInfos0.put("trans_out_type", "userId");
        royaltyDetailInfos0.put("trans_out", "2088101126765726");
        royaltyDetailInfos0.put("serial_no", 1);
        royaltyDetailInfos0.put("trans_in_type", "userId");
        royaltyDetailInfos0.put("desc", "分账测试1");
        royaltyDetailInfos.add(royaltyDetailInfos0);
        royaltyInfo.put("royalty_detail_infos", royaltyDetailInfos);
        bizContent.put("royalty_info", royaltyInfo);

      /*  // 设置二级商户信息
        Map<String, Object> subMerchantDwKJH = new HashMap<>();
        subMerchantDwKJH.put("merchant_id", "2088000603999128");
        subMerchantDwKJH.put("merchant_type", "alipay");
        bizContent.put("sub_merchant", subMerchantDwKJH);
*/
        // 设置描述结算信息
        Map<String, Object> settleInfo = new HashMap<>();
        settleInfo.put("settle_period_time", "7d");
        List<Map<String, Object>> settleDetailInfos = new ArrayList<>();
        Map<String, Object> settleDetailInfos0 = new HashMap<>();
        settleDetailInfos0.put("amount", "0.1");
        settleDetailInfos0.put("trans_in", "A0001");
        settleDetailInfos0.put("settle_entity_type", "SecondMerchant");
        settleDetailInfos0.put("summary_dimension", "A0001");
        settleDetailInfos0.put("actual_amount", "0.1");
        settleDetailInfos0.put("settle_entity_id", "2088xxxxx;ST_0001");
        settleDetailInfos0.put("trans_in_type", "cardAliasNo");
        settleDetailInfos.add(settleDetailInfos0);
        settleInfo.put("settle_detail_infos", settleDetailInfos);
        bizContent.put("settle_info", settleInfo);

        // 设置业务扩展参数
        Map<String, Object> extendParams = new HashMap<>();
        extendParams.put("sys_service_provider_id", "2088511833207846");
        extendParams.put("hb_fq_seller_percent", "100");
        extendParams.put("hb_fq_num", "3");
        extendParams.put("tc_installment_order_id", "2015042321001004720200028594");
        extendParams.put("industry_reflux_info", "{\"scene_code\":\"metro_tradeorder\",\"channel\":\"xxxx\",\"scene_data\":{\"asset_name\":\"ALIPAY\"}}");
        extendParams.put("specified_seller_name", "XXX的跨境小铺");
        extendParams.put("royalty_freeze", "true");
        extendParams.put("card_type", "S0JP0000");
        extendParams.put("credit_ext_info", "{\"category\":\"CHARGE_PILE_CAR\",\"serviceId\":\"2020042800000000000001450466\"}");
        extendParams.put("trade_component_order_id", "2023060801502300000008810000005657");
        bizContent.put("extend_params", extendParams);

        // 设置商户传入业务信息
        bizContent.put("business_params", "{\"mc_create_trade_ip\":\"127.0.0.1\"}");

        // 设置优惠参数
//        bizContent.put("promo_params", "{\"storeIdType\":\"1\"}");

        // 设置请求后页面的集成方式
        bizContent.put("integration_type", "PCWEB");

        // 设置请求来源地址
        bizContent.put("request_from_url", "https://");

        // 设置签约参数
        Map<String, Object> agreementSignParams = new HashMap<>();
        Map<String, Object> subMerchantAJkjd = new HashMap<>();
        subMerchantAJkjd.put("sub_merchant_name", "滴滴出行");
        subMerchantAJkjd.put("sub_merchant_service_name", "滴滴出行免密支付");
        subMerchantAJkjd.put("sub_merchant_service_description", "免密付车费，单次最高500");
        subMerchantAJkjd.put("sub_merchant_id", "2088123412341234");
        agreementSignParams.put("sub_merchant", subMerchantAJkjd);
        agreementSignParams.put("buckle_app_id", "1001164");
        agreementSignParams.put("sign_validity_period", "2m");
        agreementSignParams.put("buckle_merchant_id", "268820000000414397785");
        agreementSignParams.put("external_logon_id", "138****8888");
        agreementSignParams.put("third_party_type", "PARTNER");
        agreementSignParams.put("personal_product_code", "GENERAL_WITHHOLDING_P");
        agreementSignParams.put("external_agreement_no", "test");
//        agreementSignParams.put("promo_params", "{\"key\",\"value\"}");
        agreementSignParams.put("sign_scene", "INDUSTRY|CARRENTAL");
        bizContent.put("agreement_sign_params", agreementSignParams);

        // 设置商户门店编号
        bizContent.put("store_id", "NJ_001");

        // 设置指定支付渠道
        bizContent.put("enable_pay_channels", "pcredit,moneyFund,debitCardExpress");

        // 设置禁用渠道
        bizContent.put("disable_pay_channels", "pcredit,moneyFund,debitCardExpress");

        // 设置商户的原始订单号
        bizContent.put("merchant_order_no", "20161008001");

        // 设置外部指定买家
        Map<String, Object> extUserInfo = new HashMap<>();
        extUserInfo.put("cert_type", "IDENTITY_CARD");
        extUserInfo.put("cert_no", "362334768769238881");
        extUserInfo.put("mobile", "16587658765");
        extUserInfo.put("name", "李明");
        extUserInfo.put("min_age", "18");
        extUserInfo.put("need_check_info", "F");
        extUserInfo.put("identity_hash", "27bfcd1dee4f22c8fe8a2374af9b660419d1361b1c207e9b41a754a113f38fcc");
        bizContent.put("ext_user_info", extUserInfo);

        // 设置开票信息
       /* Map<String, Object> invoiceInfo = new HashMap<>();
        Map<String, Object> keyInfo = new HashMap<>();
        keyInfo.put("tax_num", "1464888883494");
        keyInfo.put("is_support_invoice", true);
        keyInfo.put("invoice_merchant_name", "ABC|003");
        invoiceInfo.put("key_info", keyInfo);
        invoiceInfo.put("details", "[{\"code\":\"100294400\",\"name\":\"服饰\",\"num\":\"2\",\"sumPrice\":\"200.00\",\"taxRate\":\"6%\"}]");
        bizContent.put("invoice_info", invoiceInfo);*/

        // 设置返回参数选项
        List<String> queryOptions = new ArrayList<>();
        queryOptions.add("hyb_amount");
        queryOptions.add("enterprise_pay_info");
        bizContent.put("query_options", queryOptions);

        bizParams.put("biz_content", bizContent);

        runMinimalPagePayDemo();

        /*try {
            // 如果是第三方代调用模式，请设置app_auth_token（应用授权令牌）
            //String pageRedirectionData = api.pageExecute("alipay.trade.page.pay", "POST", bizParams, null, "<-- 请填写应用授权令牌 -->", null);
            // 如果需要返回GET请求，请使用
            String pageRedirectionData = api.pageExecute("alipay.trade.page.pay", "GET", bizParams);
            System.out.println(pageRedirectionData);
        } catch (ApiException e) {
            System.out.println("调用失败");
        }*/
    }

    private static Map<String, Object> buildMinimalPagePayParams() {
        Map<String, Object> requestParams = new HashMap<>();
        requestParams.put("return_url", "<-- 请填写同步跳转地址 -->");
        requestParams.put("notify_url", "<-- 请填写异步通知地址 -->");

        Map<String, Object> bizContent = new HashMap<>();
        bizContent.put("out_trade_no", generateOutTradeNo());
        bizContent.put("total_amount", "0.01");
        bizContent.put("subject", "sandbox-page-pay-demo");
        bizContent.put("product_code", "FAST_INSTANT_TRADE_PAY");
        bizContent.put("timeout_express", "30m");

        requestParams.put("biz_content", bizContent);
        return requestParams;
    }

    private static String generateOutTradeNo() {
        return "sandbox_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static AlipayConfig getAlipayConfig() {
        AlipayConfig alipayConfig = new AlipayConfig();
        alipayConfig.setServerUrl("https://openapi-sandbox.dl.alipaydev.com");
        alipayConfig.setAppId("9021000133686939");
        alipayConfig.setPrivateKey("MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCUW3HxDOWnUYJcZ5oYxhDXKwtblp22V7PisXu/UcUXgUhsAyMuwbbjiSs36agUyRFGjAzF8X8/BgKv5ZhZAI26Yk/x9z2cML6E0h7kasF+CDle5EUuh1dfrju5YEdaVAuBx+qQ4ZtgGGP4G1Gz2sjxWTGiZEmxJXUAjxqIk0lZHjkUMZezXaSNAd6cEVHXb9vLglptkUPVOvrW5pzH2wkC4ww8/Hl82XTXi/yMnFWqBJ6xBY5tVMlDF/B+kSGAOU6Vk0OUXyKx6C7LigLbnXvAOXMegp3jsemxS6kmiiH75XW4UASMqMKVfDVkwFfoIMlwGotY2Y75aIZPp9UhQUnzAgMBAAECggEAHxCi5EG6GPYVYPoEp2lZ/2QxBaWdMqBct3xImEAsEAbvPkbN6C2B+VUwqo2Pt4J+zpHm3s/5wB2nFGP0KbbW9o89hEIsMXd3fWJuO5ArIhPgx4DvB5YpcoyOTvvdFQoCOtO8x0emfFG/JSW7TvjiGL9/iCyNzb01Kb/NY4nL0xASMZG/uLT/n+ApjGFTUPHLnUF7nAZSFGHW/Ps1P6Prw3qZnxoUpqeqEnzYwQV91bI15eaEdj5Ffoy2PTvlHmA5F5i8ImrV4TxrlThwbgFZfycAv0CRSWlOprEMvbV69RkqxdKTofzRR59IexD+OCg7lnl8gTaq4Krxx3/9+/KB0QKBgQDSjOVrxhxsTHghJui2Klz+w2xlOV8o8y19SdHJZ1pKuRTd70xLyjk/7knduL/XbATi6GiNKgkZFAHnkY0lVRVY/ixJrkrNYO3Pu0l1rfpD4cf7OhsejIXQvTZbBsHTYPIdNEpEz6Qh36E7ijqxfbGryz2pBa/y5dMtyLIlBOSaWQKBgQC0YbpSpUoq+OOOGgN4163f3bzPGe5ZSfYld2aapoeoHnHf/wH8BmFKAcWwNP4Jg/jp1x52a2iSHZ8O882sno0p6+lqZzCgh/xHCvQqoqBC6/73itYvXiNY4i4gbKLaurxtq6OF3E/8/M7K38+qwnBjmuGRG35NAtp0G/9OBmClKwKBgCQrI/YBkDxiXniDcJJqcJMrCuHQmzIzo+rT1X/dizsu1BDT3K1gTblXu8Skyf+9hJFeDyfAwtNWBoW7ER4KoaIp932ovRzOu8tlhtb6TfV/QZU8hBAZX5teBYpZfM5M5wpxQxjS+oT/H3lU44Q97VlvjmYMng8Bv+crhL6rOoHxAoGBAID2Sebig6c7cD1dPdQ8pvQhrOaITI8LwO7Ccdnpa513vsR9peaQjSgDIlo7WNBmySQuB3p0ijvE774Z8pcSeCxbnPLUucBb2LLWEwEUae3OmehTG5ScAjt+4amO4dPto9/o0a1XeNyPxiznXKph/0ZHKjO7a04V1yciCSK4joonAoGBALKSMJz6LVQ1MRe6em3qtvjZMsEHoA2FjEYvPclWoSi99hahZDK2A/psHY1Mn5ScYOUww7TU0C6aiJI60i2wOmbilZdJvcIG4gnTK6JnSuno6GmRxZ4yijlCJ1VhhQW3d5VhqD6nyhRs7vRaHMFb71D1qvKOPCww65uep+PzY2si");
        alipayConfig.setAlipayPublicKey("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArF0aKskORn9rkDcCj7tvNg9irpJO+K6FmU9xuEeBt+fVOOhdpsIn8XSYRx5TEXdlhQlek1YuMn0FAI2KMl9vLF7atGZcQp8uHYTaLWeIFKD/Nx3GA5Jx0I4fI2XPrZq2s5Kfk/xkG2w5rlwFPUJCGAgfQj0x6dmZDJYN5+8s37GIBgHmKoN2yYYGsxV2noA6s9AjCHQClZAyZ4Ue/b2yZnEHEmhvwTJieoJSwoAfU60vvq2LuMpAoh1ad5IOtr+OQ02iObFTi3uxF77s2dn3MHUssHkmksxy9DeYDfKWQJHBZs/3bpij1RtLg8hTX7lkJL+xotzyYjUpPjUuC2ZSbwIDAQAB");
        return alipayConfig;
    }
}
