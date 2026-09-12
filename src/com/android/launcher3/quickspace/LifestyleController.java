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

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.launcher3.LauncherPrefs;
import com.bestrom.agent.brain.BestromBrainClient;
import com.bestrom.agent.brain.BrainProxyContract;
import com.bestrom.agent.health.BestromHealthClient;
import com.bestrom.agent.health.HealthProxyContract;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Focus + water/health strip for Quickspace. Numbers local; optional brain tip.
 */
public class LifestyleController {

  private static final String TAG = "LifestyleController";
  private static final long REFRESH_MS = 5 * 60 * 1000L;

  private final Context mContext;
  private final Handler mMain = new Handler(Looper.getMainLooper());
  private final ExecutorService mIo = Executors.newSingleThreadExecutor();
  private final Runnable mNotify;

  private volatile String mLine = "";
  private volatile String mBrainTip = "";
  private long mLastBrainMs = 0;

  public LifestyleController(Context context, Runnable notify) {
    mContext = context.getApplicationContext();
    mNotify = notify;
    refresh();
  }

  public String getLine() {
    if (!LauncherPrefs.get(mContext).get(LauncherPrefs.SHOW_QUICKSPACE_LIFESTYLE)) {
      return "";
    }
    if (mBrainTip.isEmpty()) return mLine;
    return mLine + " · " + mBrainTip;
  }

  public void refresh() {
    mIo.execute(
        () -> {
          FocusStatsEngine.Snapshot focus = FocusStatsEngine.today(mContext);
          String waterBit = "";
          String healthBit = "";
          BestromHealthClient health = new BestromHealthClient(mContext);
          try {
            if (health.bind(2000)) {
              Bundle st = health.status();
              if (st != null && st.getBoolean(HealthProxyContract.KEY_OK, false)) {
                int cups = st.getInt(HealthProxyContract.KEY_WATER_CUPS, 0);
                int goal = st.getInt(HealthProxyContract.KEY_WATER_GOAL, 8);
                int streak = st.getInt(HealthProxyContract.KEY_WATER_STREAK, 0);
                waterBit = "Water " + cups + "/" + goal;
                if (streak > 0) waterBit += " · streak " + streak + "d";
                String trend = st.getString(HealthProxyContract.KEY_WEIGHT_TREND, "flat");
                if (st.containsKey(HealthProxyContract.KEY_WEIGHT_KG)) {
                  healthBit = "Wt " + trend;
                }
              }
            }
          } catch (Exception e) {
            Log.d(TAG, "health unavailable", e);
          } finally {
            health.unbind();
          }
          StringBuilder sb = new StringBuilder(focus.line());
          if (!waterBit.isEmpty()) sb.append(" · ").append(waterBit);
          if (!healthBit.isEmpty()) sb.append(" · ").append(healthBit);
          mLine = sb.toString();
          maybeBrain(focus);
          mMain.post(mNotify);
          mMain.removeCallbacks(mRefreshRunnable);
          mMain.postDelayed(mRefreshRunnable, REFRESH_MS);
        });
  }

  private final Runnable mRefreshRunnable = this::refresh;

  private void maybeBrain(FocusStatsEngine.Snapshot focus) {
    long now = System.currentTimeMillis();
    if (now - mLastBrainMs < 6 * 60 * 60 * 1000L) return;
    BestromBrainClient brain = new BestromBrainClient(mContext);
    try {
      if (!brain.bind(2000) || !brain.isReady()) return;
      Bundle r =
          brain.complete(
              "One short coaching line for phone focus. Not medical advice."
                  + " Data is today's aggregates only.",
              focus.aggregateJson());
      if (r != null && r.getBoolean(BrainProxyContract.KEY_OK, false)) {
        String t = r.getString(BrainProxyContract.KEY_TEXT, "");
        if (t != null && !t.isEmpty()) {
          mBrainTip = t.length() > 80 ? t.substring(0, 80) + "…" : t;
          mLastBrainMs = now;
        }
      }
    } catch (Exception e) {
      Log.d(TAG, "brain tip skipped", e);
    } finally {
      brain.unbind();
    }
  }

  /** +1 cup of water via Agent; returns updated line asynchronously. */
  public void addWaterCup() {
    mIo.execute(
        () -> {
          BestromHealthClient health = new BestromHealthClient(mContext);
          try {
            if (health.bind(2000)) {
              health.addWaterCup(1);
            }
          } catch (Exception ignored) {
          } finally {
            health.unbind();
          }
          refresh();
        });
  }

  public void destroy() {
    mMain.removeCallbacks(mRefreshRunnable);
    mIo.shutdownNow();
  }
}
