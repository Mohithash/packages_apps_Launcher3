/*
 * Copyright (C) 2026 BestROM
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.quickspace;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Productive vs wasted minutes from UsageStats (local only). */
public final class FocusStatsEngine {

  public static final class Snapshot {
    public final long productiveMs;
    public final long wastedMs;
    public final int focusScore;
    public final String topWaste;
    public final String topProductive;

    Snapshot(long productiveMs, long wastedMs, int focusScore, String topWaste, String topProductive) {
      this.productiveMs = productiveMs;
      this.wastedMs = wastedMs;
      this.focusScore = focusScore;
      this.topWaste = topWaste;
      this.topProductive = topProductive;
    }

    public String line() {
      long p = TimeUnit.MILLISECONDS.toMinutes(productiveMs);
      long w = TimeUnit.MILLISECONDS.toMinutes(wastedMs);
      return "Focus " + focusScore + " · " + p + "m useful / " + w + "m leisure";
    }

    public String aggregateJson() {
      return "{\"productive_min\":"
          + TimeUnit.MILLISECONDS.toMinutes(productiveMs)
          + ",\"waste_min\":"
          + TimeUnit.MILLISECONDS.toMinutes(wastedMs)
          + ",\"focus\":"
          + focusScore
          + ",\"top_waste\":\""
          + (topWaste == null ? "" : topWaste.replace("\"", ""))
          + "\",\"top_productive\":\""
          + (topProductive == null ? "" : topProductive.replace("\"", ""))
          + "\"}";
    }
  }

  private static final Set<String> WASTE =
      new HashSet<>(
          List.of(
              "com.instagram.android",
              "com.zhiliaoapp.musically",
              "com.ss.android.ugc.aweme",
              "com.google.android.youtube",
              "com.facebook.katana",
              "com.facebook.orca",
              "com.snapchat.android",
              "com.twitter.android",
              "com.reddit.frontpage",
              "tv.twitch.android.app",
              "com.netflix.mediaclient",
              "com.disney.disneyplus",
              "com.amazon.avod.thirdpartyclient"));

  private static final Set<String> PRODUCTIVE =
      new HashSet<>(
          List.of(
              "com.google.android.apps.docs",
              "com.google.android.gm",
              "com.microsoft.office",
              "com.slack",
              "com.android.settings",
              "com.google.android.calendar",
              "com.google.android.apps.maps",
              "org.chromium.chrome",
              "com.android.chrome",
              "com.termux",
              "com.bestrom.agent"));

  private FocusStatsEngine() {}

  public static Snapshot today(Context context) {
    UsageStatsManager usm = context.getSystemService(UsageStatsManager.class);
    if (usm == null) {
      return new Snapshot(0, 0, 50, null, null);
    }
    Calendar cal = Calendar.getInstance();
    long end = cal.getTimeInMillis();
    cal.set(Calendar.HOUR_OF_DAY, 0);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    long start = cal.getTimeInMillis();
    List<UsageStats> stats;
    try {
      stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end);
    } catch (SecurityException e) {
      return new Snapshot(0, 0, 50, null, null);
    }
    if (stats == null) stats = new ArrayList<>();
    long prod = 0;
    long waste = 0;
    String topW = null;
    String topP = null;
    long topWms = 0;
    long topPms = 0;
    PackageManager pm = context.getPackageManager();
    for (UsageStats s : stats) {
      long t = s.getTotalTimeInForeground();
      if (t < 60_000) continue;
      String pkg = s.getPackageName();
      if (WASTE.contains(pkg) || looksSocial(pkg)) {
        waste += t;
        if (t > topWms) {
          topWms = t;
          topW = label(pm, pkg);
        }
      } else if (PRODUCTIVE.contains(pkg) || looksWork(pkg)) {
        prod += t;
        if (t > topPms) {
          topPms = t;
          topP = label(pm, pkg);
        }
      }
    }
    long total = prod + waste;
    int score =
        total <= 0 ? 50 : (int) Math.max(0, Math.min(100, (prod * 100) / Math.max(1, total)));
    return new Snapshot(prod, waste, score, topW, topP);
  }

  private static boolean looksSocial(String pkg) {
    String p = pkg.toLowerCase();
    return p.contains("tiktok") || p.contains("instagram") || p.contains("facebook");
  }

  private static boolean looksWork(String pkg) {
    String p = pkg.toLowerCase();
    return p.contains("office") || p.contains("docs") || p.contains("mail") || p.contains("slack");
  }

  private static String label(PackageManager pm, String pkg) {
    try {
      ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
      return pm.getApplicationLabel(ai).toString();
    } catch (Exception e) {
      return pkg;
    }
  }
}
