
package com.distantfuture.castvideos.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;

import com.distantfuture.castcompanionlibrary.lib.cast.VideoCastManager;
import com.distantfuture.castvideos.app.CastApplication;
import com.distantfuture.castvideos.app.R;
import com.distantfuture.castvideos.app.Utils;

public class CastPreference extends PreferenceActivity implements OnSharedPreferenceChangeListener {

  public static final String APP_DESTRUCTION_KEY = "application_destruction";
  public static final String FTU_SHOWN_KEY = "ftu_shown";
  public static final String VOLUME_SELCTION_KEY = "volume_target";
  public static final String TERMINATION_POLICY_KEY = "termination_policy";
  public static final String STOP_ON_DISCONNECT = "1";
  public static final String CONTINUE_ON_DISCONNECT = "0";
  private ListPreference mVolumeListPreference;
  private SharedPreferences mPrefs;
  private VideoCastManager mCastManager;
  boolean mStopOnExit;
  private ListPreference mTerminationListPreference;

  @SuppressWarnings("deprecation")
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    addPreferencesFromResource(R.xml.application_preference);
    getPreferenceScreen().getSharedPreferences().
        registerOnSharedPreferenceChangeListener(this);
    mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
    mCastManager = CastApplication.getCastManager(this);

    // -- Termination Policy -------------------//
    mTerminationListPreference = (ListPreference) getPreferenceScreen().findPreference(TERMINATION_POLICY_KEY);
    mTerminationListPreference.setSummary(getTerminationSummary(mPrefs));
    mCastManager.setStopOnDisconnect(mStopOnExit);

    // -- Volume settings ----------------------//
    mVolumeListPreference = (ListPreference) getPreferenceScreen().findPreference(VOLUME_SELCTION_KEY);
    String volValue = mPrefs.getString(VOLUME_SELCTION_KEY, getString(R.string.prefs_volume_default));
    String volSummary = getResources().getString(R.string.prefs_volume_title_summary, volValue);
    mVolumeListPreference.setSummary(volSummary);

    EditTextPreference versionPref = (EditTextPreference) findPreference("app_version");
    versionPref.setTitle(getString(R.string.version, Utils.getAppVersionName(this)));
  }

  public static boolean isDestroyAppOnDisconnect(Context ctx) {
    SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(ctx);
    return sharedPref.getBoolean(APP_DESTRUCTION_KEY, false);
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
    if (VOLUME_SELCTION_KEY.equals(key)) {
      String value = sharedPreferences.getString(VOLUME_SELCTION_KEY, "");
      String summary = getResources().getString(R.string.prefs_volume_title_summary, value);
      mVolumeListPreference.setSummary(summary);
    } else if (TERMINATION_POLICY_KEY.equals(key)) {
      mTerminationListPreference.setSummary(getTerminationSummary(sharedPreferences));
      mCastManager.setStopOnDisconnect(mStopOnExit);
    }
  }

  private String getTerminationSummary(SharedPreferences sharedPreferences) {
    String valueStr = sharedPreferences.getString(TERMINATION_POLICY_KEY, "0");
    String[] labels = getResources().getStringArray(R.array.prefs_termination_policy_names);
    int value = CONTINUE_ON_DISCONNECT.equals(valueStr) ? 0 : 1;
    mStopOnExit = value != 0;
    return labels[value];
  }

  public static boolean isFtuShown(Context ctx) {
    SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(ctx);
    return sharedPref.getBoolean(FTU_SHOWN_KEY, false);
  }

  public static void setFtuShown(Context ctx) {
    SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(ctx);
    sharedPref.edit().putBoolean(FTU_SHOWN_KEY, true).commit();
  }

  @Override
  protected void onResume() {
    if (null != mCastManager) {
      mCastManager.incrementUiCounter();
    }
    super.onResume();
  }

  @Override
  protected void onPause() {
    if (null != mCastManager) {
      mCastManager.decrementUiCounter();
    }
    super.onPause();
  }

}
