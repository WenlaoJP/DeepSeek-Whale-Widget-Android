package com.deepseek.whalepet;

import android.content.Context;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 「小鲸鱼记账」：用余额差值累计当天用量，跨天自动归零归档，保留 30 天。
 * 逻辑照搬原版 recordLedgerUsage（含币种切换只重置基准的保护）。
 */
public final class UsageLedger {

    private static final int KEEP_DAYS = 30;

    public static final class State {
        public String date = todayKey();
        public double lastBalance = Double.NaN;
        public String lastCurrency = "";
        public double todayUsage = 0d;
        public final JSONObject history = new JSONObject();
    }

    private UsageLedger() {
    }

    public static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static State parse(String raw) {
        State s = new State();
        if (raw == null || raw.length() == 0) {
            return s;
        }
        try {
            JSONObject o = new JSONObject(raw);
            String d = o.optString("date", "");
            if (d.length() > 0) {
                s.date = d;
            }
            if (o.has("lastBalance")) {
                s.lastBalance = o.optDouble("lastBalance", Double.NaN);
            }
            s.lastCurrency = o.optString("lastCurrency", "");
            s.todayUsage = o.optDouble("todayUsage", 0d);
            JSONObject h = o.optJSONObject("history");
            if (h != null) {
                Iterator<String> it = h.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    try {
                        s.history.put(k, h.optDouble(k, 0d));
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    private static String serialize(State s) {
        JSONObject o = new JSONObject();
        try {
            o.put("date", s.date);
            if (!Double.isNaN(s.lastBalance)) {
                o.put("lastBalance", s.lastBalance);
            }
            o.put("lastCurrency", s.lastCurrency);
            o.put("todayUsage", s.todayUsage);
            o.put("history", s.history);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    /** 记录一次余额观测，返回最新账本。 */
    public static State record(Context context, double balance, String currency) {
        String today = todayKey();
        State s = parse(Prefs.ledger(context));
        String cur = currency == null ? "" : currency;
        boolean currencyChanged = s.lastCurrency.length() > 0 && cur.length() > 0
                && !s.lastCurrency.equals(cur);

        if (!today.equals(s.date)) {
            // 跨天：归档旧账，重置基准
            if (s.date.length() > 0) {
                try {
                    s.history.put(s.date, s.todayUsage);
                } catch (Throwable ignored) {
                }
            }
            s.date = today;
            s.lastBalance = balance;
            s.lastCurrency = cur;
            s.todayUsage = 0d;
        } else if (currencyChanged) {
            // 币种切换：只换基准，不把差值记成消费
            s.lastBalance = balance;
            s.lastCurrency = cur;
        } else {
            double prev = Double.isNaN(s.lastBalance) ? balance : s.lastBalance;
            if (balance < prev) {
                s.todayUsage += (prev - balance);
            }
            s.lastBalance = balance;
            s.lastCurrency = cur;
        }

        // 只保留最近 30 天
        List<String> keys = new ArrayList<>();
        Iterator<String> it = s.history.keys();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        Collections.sort(keys);
        while (keys.size() > KEEP_DAYS) {
            s.history.remove(keys.remove(0));
        }

        Prefs.setLedger(context, serialize(s));
        return s;
    }

    public static State read(Context context) {
        return parse(Prefs.ledger(context));
    }

    /** 今日已用；没记过账时返回 NaN。 */
    public static double todayUsage(Context context) {
        State s = parse(Prefs.ledger(context));
        if (!todayKey().equals(s.date)) {
            return 0d;
        }
        return s.todayUsage;
    }
}
