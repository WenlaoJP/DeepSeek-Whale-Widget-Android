package com.deepseek.whalepet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/** DeepSeek 余额接口：GET https://api.deepseek.com/user/balance 。 */
public final class BalanceClient {

    public static final String ENDPOINT = "https://api.deepseek.com/user/balance";
    private static final int TIMEOUT_MS = 12000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 一次查询的结果。 */
    public static final class Result {
        public final boolean ok;
        public final double amount;
        public final String currency;
        public final String message;

        Result(boolean ok, double amount, String currency, String message) {
            this.ok = ok;
            this.amount = amount;
            this.currency = currency;
            this.message = message;
        }

        /** 形如 ¥12.34 的展示文本。 */
        public String display() {
            if (!ok) {
                return "--";
            }
            String symbol;
            if ("CNY".equalsIgnoreCase(currency)) {
                symbol = "¥";
            } else if ("USD".equalsIgnoreCase(currency)) {
                symbol = "$";
            } else {
                return currency + " " + format(amount);
            }
            return symbol + format(amount);
        }
    }

    private BalanceClient() {
    }

    private static String format(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    /** 同步查询，调用方需放在后台线程。 */
    public static Result fetch(String apiKey) {
        if (apiKey == null || apiKey.trim().length() == 0) {
            return new Result(false, 0d, "CNY", "未配置 API Key");
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            int code = conn.getResponseCode();
            if (code == 200) {
                return parse(read(conn.getInputStream()));
            }
            String detail = read(conn.getErrorStream());
            if (code == 401) {
                return new Result(false, 0d, "CNY", "API Key 无效或已过期（401）");
            }
            if (code == 402) {
                return new Result(false, 0d, "CNY", "账户余额不足（402）");
            }
            if (code == 429) {
                return new Result(false, 0d, "CNY", "请求过于频繁（429）");
            }
            String tail = detail.length() > 60 ? detail.substring(0, 60) : detail;
            return new Result(false, 0d, "CNY", "HTTP " + code + " " + tail);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Result(false, 0d, "CNY", "网络异常：" + msg);
        } catch (Throwable t) {
            return new Result(false, 0d, "CNY", "解析异常：" + t.getClass().getSimpleName());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static Result parse(String body) {
        try {
            JSONObject json = new JSONObject(body);
            JSONArray infos = json.optJSONArray("balance_infos");
            if (infos == null || infos.length() == 0) {
                return new Result(false, 0d, "CNY", "接口未返回余额信息");
            }
            JSONObject first = infos.getJSONObject(0);
            String currency = first.optString("currency", "CNY");
            String total = first.optString("total_balance", "0");
            double amount = parseAmount(total);
            return new Result(true, amount, currency, "");
        } catch (Throwable t) {
            return new Result(false, 0d, "CNY", "返回内容无法解析");
        }
    }

    private static double parseAmount(String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (Throwable ignored) {
            return 0d;
        }
    }

    private static String read(InputStream in) {
        if (in == null) {
            return "";
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(in, UTF8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
