
package com.distantfuture.castcompanionlibrary.lib.cast;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.media.RemoteControlClient;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.MediaRouteButton;
import android.support.v7.app.MediaRouteDialogFactory;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.support.v7.media.MediaRouter.RouteInfo;
import android.view.Menu;
import android.view.MenuItem;

import com.distantfuture.castcompanionlibrary.lib.R;
import com.distantfuture.castcompanionlibrary.lib.cast.callbacks.BaseCastConsumerImpl;
import com.distantfuture.castcompanionlibrary.lib.cast.callbacks.IBaseCastConsumer;
import com.distantfuture.castcompanionlibrary.lib.cast.exceptions.CastException;
import com.distantfuture.castcompanionlibrary.lib.cast.exceptions.NoConnectionException;
import com.distantfuture.castcompanionlibrary.lib.cast.exceptions.OnFailedListener;
import com.distantfuture.castcompanionlibrary.lib.cast.exceptions.TransientNetworkDisconnectionException;
import com.distantfuture.castcompanionlibrary.lib.utils.CastUtils;
import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.Cast.ApplicationConnectionResult;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An abstract class that manages connectivity to a cast device. Subclasses are expected to extend
 * the functionality of this class based on their purpose.
 */
public abstract class BaseCastManager implements DeviceSelectionListener, ConnectionCallbacks, OnConnectionFailedListener, OnFailedListener {
  public static enum ReconnectionStatus {
    STARTED, IN_PROGRESS, FINALIZE, INACTIVE
  }

  public static final int FEATURE_DEBUGGING = 1;
  public static final int FEATURE_NOTIFICATION = 4;
  public static final int FEATURE_LOCKSCREEN = 2;
  public static final String PREFS_KEY_SESSION_ID = "session-id";
  public static final String PREFS_KEY_APPLICATION_ID = "application-id";
  public static final String PREFS_KEY_VOLUME_INCREMENT = "volume-increment";
  public static final String PREFS_KEY_ROUTE_ID = "route-id";

  public static final int NO_STATUS_CODE = -1;

  private static final String TAG = CastUtils.makeLogTag(BaseCastManager.class);
  private static final int SESSION_RECOVERY_TIMEOUT = 5; // in seconds

  protected Context mContext;
  protected MediaRouter mMediaRouter;
  protected MediaRouteSelector mMediaRouteSelector;
  protected CastMediaRouterCallback mMediaRouterCallback;
  protected CastDevice mSelectedCastDevice;
  protected String mDeviceName;
  private final Set<IBaseCastConsumer> mBaseCastConsumers = new HashSet<IBaseCastConsumer>();
  private boolean mDestroyOnDisconnect = false;
  protected String mApplicationId;
  protected Handler mHandler;
  protected ReconnectionStatus mReconnectionStatus = ReconnectionStatus.INACTIVE;
  protected int mVisibilityCounter;
  protected boolean mUiVisible;
  protected GoogleApiClient mApiClient;
  protected AsyncTask<Void, Integer, Integer> mReconnectionTask;
  protected int mCapabilities;
  protected boolean mConnectionSuspened;
  private boolean mWifiConnectivity = true;
  protected static BaseCastManager sCastManager;

  /*************************************************************************/
  /************** Abstract Methods *****************************************/
  /*************************************************************************/

  /**
   * A chance for the subclasses to perform what needs to be done when a route is unselected. Most
   * of the logic is handled by the {@link BaseCastManager} but each subclass may have some
   * additional logic that can be done, e.g. detaching data or media channels that they may have
   * set up.
   */
  abstract void onDeviceUnselected();

  /**
   * Since application lifecycle callbacks are managed by subclasses, this abstract method needs
   * to be implemented by each subclass independently.
   */
  abstract Cast.CastOptions.Builder getCastOptionBuilder(CastDevice device);

  /**
   * Subclasses can decide how the Cast Controller Dialog should be built. If this returns
   * <code>null</code>, the default dialog will be shown.
   */
  abstract MediaRouteDialogFactory getMediaRouteDialogFactory();

  /**
   * Subclasses should implement this to react appropriately to the successful launch of their
   * application. This is called when the application is successfully launched.
   */
  abstract void onApplicationConnected(ApplicationMetadata applicationMetadata, String applicationStatus, String sessionId, boolean wasLaunched);

  /**
   * Called when the launch of application has failed. Subclasses need to handle this by doing
   * appropriate clean up.
   */
  abstract void onApplicationConnectionFailed(int statusCode);

  /**
   * Called when the attempt to stop application has failed.
   */
  abstract void onApplicationStopFailed(int statusCode);

  /**
   * ********************************************************************
   */

  protected BaseCastManager(Context context, String applicationId) {
    CastUtils.LOGD(TAG, "BaseCastManager is instantiated");
    mContext = context;
    mHandler = new Handler(Looper.getMainLooper());
    mApplicationId = applicationId;
    CastUtils.saveStringToPreference(mContext, PREFS_KEY_APPLICATION_ID, applicationId);

    CastUtils.LOGD(TAG, "Application ID is: " + mApplicationId);
    mMediaRouter = MediaRouter.getInstance(context);
    mMediaRouteSelector = new MediaRouteSelector.Builder().addControlCategory(CastMediaControlIntent
        .categoryForCast(mApplicationId)).build();

    mMediaRouterCallback = new CastMediaRouterCallback(this, context);
    mMediaRouter.addCallback(mMediaRouteSelector, mMediaRouterCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
  }

  public void onWifiConnectivityChanged(boolean connected) {
    CastUtils.LOGD(TAG, "WIFI connectivity changed to " + (connected ? "enabled" : "disabled"));
    if (connected && !mWifiConnectivity) {
      mWifiConnectivity = true;
      mHandler.postDelayed(new Runnable() {

        @Override
        public void run() {
          reconnectSessionIfPossible(mContext, false, 10);
        }
      }, 1000);

    } else {
      mWifiConnectivity = connected;
    }
  }

  public static BaseCastManager getCastManager() {
    return sCastManager;
  }

  /**
   * Sets the {@link Context} for the subsequent calls. Setting context can help the library to
   * show error messages to the user.
   */
  public void setContext(Context context) {
    mContext = context;
  }

  @Override
  public void onDeviceSelected(CastDevice device) {
    setDevice(device, mDestroyOnDisconnect);
  }

  public void setDevice(CastDevice device, boolean stopAppOnExit) {
    mSelectedCastDevice = device;
    mDeviceName = mSelectedCastDevice != null ? mSelectedCastDevice.getFriendlyName() : null;

    if (mSelectedCastDevice == null) {
      if (!mConnectionSuspened) {
        CastUtils.saveStringToPreference(mContext, PREFS_KEY_SESSION_ID, null);
        CastUtils.saveStringToPreference(mContext, PREFS_KEY_ROUTE_ID, null);
      }
      mConnectionSuspened = false;
      try {
        if (isConnected()) {
          if (stopAppOnExit) {
            CastUtils.LOGD(TAG, "Calling stopApplication");
            stopApplication();
          }
        }
      } catch (IllegalStateException e) {
        CastUtils.LOGE(TAG, "Failed to stop the application after disconecting route", e);
      } catch (IOException e) {
        CastUtils.LOGE(TAG, "Failed to stop the application after disconecting route", e);
      } catch (TransientNetworkDisconnectionException e) {
        CastUtils.LOGE(TAG, "Failed to stop the application after disconecting route", e);
      } catch (NoConnectionException e) {
        CastUtils.LOGE(TAG, "Failed to stop the application after disconecting route", e);
      }
      onDisconnected();
      onDeviceUnselected();
      if (null != mApiClient) {
        CastUtils.LOGD(TAG, "Trying to disconnect");
        mApiClient.disconnect();
        if (null != mMediaRouter) {
          mMediaRouter.selectRoute(mMediaRouter.getDefaultRoute());
        }
        mApiClient = null;
      }
    } else if (null == mApiClient) {
      CastUtils.LOGD(TAG, "acquiring a conenction to Google Play services for " + mSelectedCastDevice);
      Cast.CastOptions.Builder apiOptionsBuilder = getCastOptionBuilder(mSelectedCastDevice);
      mApiClient = new GoogleApiClient.Builder(mContext).addApi(Cast.API, apiOptionsBuilder.build())
          .addConnectionCallbacks(this)
          .addOnConnectionFailedListener(this)
          .build();
      mApiClient.connect();
    } else if (!mApiClient.isConnected()) {
      mApiClient.connect();
    }
  }

  @Override
  public void onCastDeviceDetected(RouteInfo info) {
    if (null != mBaseCastConsumers) {
      for (IBaseCastConsumer consumer : mBaseCastConsumers) {
        try {
          consumer.onCastDeviceDetected(info);
        } catch (Exception e) {
          CastUtils.LOGE(TAG, "onCastDeviceDetected(): Failed to inform " + consumer, e);
        }
      }
    }
  }

  public MenuItem addMediaRouterButton(Menu menu, int menuResourceId, Activity activity) {
    MenuItem item = menu.findItem(menuResourceId);
    if (item != null) {
      MediaRouteButton button = new MediaRouteButton(activity);  // don't pass mContext, it needs a real activity or it's fucked
      button.setRouteSelector(mMediaRouteSelector);

      if (null != getMediaRouteDialogFactory()) {
        button.setDialogFactory(getMediaRouteDialogFactory());
      }

      item.setActionView(button);
    }

    return item;
  }

  /*************************************************************************/
  /************** UI Visibility Management *********************************/
  /*************************************************************************/

  /**
   * Calling this method signals the library that an activity page is made visible. In common
   * cases, this should be called in the "onResume()" method of each activity of the application.
   * The library keeps a counter and when at least one page of the application becomes visible,
   */
  public synchronized void incrementUiCounter() {
    mVisibilityCounter++;
    if (!mUiVisible) {
      mUiVisible = true;
      onUiVisibilityChanged(true);
    }
    if (mVisibilityCounter == 0) {
      CastUtils.LOGD(TAG, "UI is no longer visible");
    } else {
      CastUtils.LOGD(TAG, "UI is visible");
    }
  }

  /**
   * Calling this method signals the library that an activity page is made invisible. In common
   * cases, this should be called in the "onPause()" method of each activity of the application.
   * The library keeps a counter and when all pages of the application become invisible, the
   */
  public synchronized void decrementUiCounter() {
    mHandler.postDelayed(new Runnable() {
      @Override
      public void run() {
        if (--mVisibilityCounter == 0) {
          CastUtils.LOGD(TAG, "UI is no longer visible");
          if (mUiVisible) {
            mUiVisible = false;
            onUiVisibilityChanged(false);
          }
        } else {
          CastUtils.LOGD(TAG, "UI is visible");
        }
      }
    }, 300);
  }

  /**
   * This is called when UI visibility of the client has changed
   */
  protected void onUiVisibilityChanged(boolean visible) {
    if (visible) {
      if (null != mMediaRouter && null != mMediaRouterCallback) {
        CastUtils.LOGD(TAG, "onUiVisibilityChanged() addCallback called");
        mMediaRouter.addCallback(mMediaRouteSelector, mMediaRouterCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
      }
    } else {
      if (null != mMediaRouter) {
        CastUtils.LOGD(TAG, "onUiVisibilityChanged() removeCallback called");
        mMediaRouter.removeCallback(mMediaRouterCallback);
      }
    }
  }

  /*************************************************************************/
  /************** Utility Methods ******************************************/
  /*************************************************************************/

  /**
   * A utility method to validate that the appropriate version of the Google Play Services is
   * available on the device. If not, it will open a dialog to address the issue. The dialog
   * displays a localized message about the error and upon user confirmation (by tapping on
   * dialog) will direct them to the Play Store if Google Play services is out of date or missing,
   * or to system settings if Google Play services is disabled on the device.
   */
  public static boolean checkGooglePlayServices(final Activity activity) {
    return CastUtils.checkGooglePlayServices(activity);
  }

  /**
   * can be used to find out if the application is connected to the service or not.
   */
  public boolean isConnected() {
    return (null != mApiClient) && mApiClient.isConnected();
  }

  /**
   * Disconnects from the cast device and stops the application on the cast device.
   */
  public void disconnect() {
    if (isConnected()) {
      setDevice(null, true);
    }
  }

  /**
   * Returns the assigned human-readable name of the device, or <code>null</code> if no device is
   * connected.
   */
  public final String getDeviceName() {
    return mDeviceName;
  }

  /**
   * Sets a flag to control whether disconnection form a cast device should result in stopping the
   * running application or not. If <code>true</code> is passed, then application will be stopped.
   * Default behavior is not to stop the app.
   *
   * @param stopOnExit
   */
  public final void setStopOnDisconnect(boolean stopOnExit) {
    mDestroyOnDisconnect = stopOnExit;
  }

  /**
   * Returns the {@link MediaRouteSelector} object.
   */
  public final MediaRouteSelector getMediaRouteSelector() {
    return mMediaRouteSelector;
  }

  /**
   * Turns on configurable features in the library. All the supported features are turned off by
   * default and clients, prior to using them, need to turn them on; it is best to do is
   * immediately after initialization of the library. Bitwise OR combination of features should be
   * passed in if multiple features are needed
   * <p/>
   * Current set of configurable features are:
   * <ul>
   * <li>FEATURE_DEBUGGING : turns on debugging in Google Play services
   * <li>FEATURE_NOTIFICATION : turns notifications on
   * <li>FEATURE_LOCKSCREEN : turns on Lock Screen using {@link RemoteControlClient} in supported
   * versions (JB+)
   * </ul>
   *
   * @param capabilities
   */
  public void enableFeatures(int capabilities) {
    mCapabilities = capabilities;
  }

  /*
   * Returns true if and only if the feature is turned on
   */
  protected boolean isFeatureEnabled(int feature) {
    return (feature & mCapabilities) > 0;
  }

  /**
   * Sets the device (system) volume.
   *
   * param volume Should be a value between 0 and 1, inclusive.
   */
  public void setDeviceVolume(double volume) throws CastException, TransientNetworkDisconnectionException, NoConnectionException {
    checkConnectivity();
    try {
      Cast.CastApi.setVolume(mApiClient, volume);
    } catch (Exception e) {
      CastUtils.LOGE(TAG, "Failed to set volume", e);
      throw new CastException("Failed to set volume");
    }
  }

  /**
   * Gets the remote's system volume, a number between 0 and 1, inclusive.
   */
  public final double getDeviceVolume() throws TransientNetworkDisconnectionException, NoConnectionException {
    checkConnectivity();
    return Cast.CastApi.getVolume(mApiClient);
  }

  /**
   * Increments (or decrements) the device volume by the given amount.
   */
  public void incrementDeviceVolume(double delta) throws CastException, TransientNetworkDisconnectionException, NoConnectionException {
    checkConnectivity();
    double vol = getDeviceVolume();
    if (vol >= 0) {
      setDeviceVolume(vol + delta);
    }
  }

  /**
   * Returns <code>true</code> if remote device is muted. It internally determines if this should
   * be done for <code>stream</code> or <code>device</code> volume.
   */
  public final boolean isDeviceMute() throws TransientNetworkDisconnectionException, NoConnectionException {
    checkConnectivity();
    return Cast.CastApi.isMute(mApiClient);
  }

  /**
   * Mutes or un-mutes the device volume.
   */
  public void setDeviceMute(boolean mute) throws CastException, TransientNetworkDisconnectionException, NoConnectionException {
    checkConnectivity();
    try {
      Cast.CastApi.setMute(mApiClient, mute);
    } catch (Exception e) {
      CastUtils.LOGE(TAG, "Failed to set mute to: " + mute, e);
      throw new CastException("Failed to mute");
    }
  }

  /*************************************************************************/
  /************** Session Recovery Methods *********************************/
  /*************************************************************************/

  /**
   * Returns the current {@link ReconnectionStatus}
   */
  public ReconnectionStatus getReconnectionStatus() {
    return mReconnectionStatus;
  }

  /**
   * Sets the {@link ReconnectionStatus}
   */
  public final void setReconnectionStatus(ReconnectionStatus status) {
    mReconnectionStatus = status;
  }

  /**
   * Returns <code>true</code> if there is enough persisted information to attempt a session
   * recovery. For this to return <code>true</code>, there needs to be persisted session ID and
   * route ID from the last successful launch.
   */
  public final boolean canConsiderSessionRecovery() {
    String sessionId = CastUtils.getStringFromPreference(mContext, PREFS_KEY_SESSION_ID);
    String routeId = CastUtils.getStringFromPreference(mContext, PREFS_KEY_ROUTE_ID);
    if (null == sessionId || null == routeId) {
      return false;
    }
    CastUtils.LOGD(TAG, "Found session info in the preferences, so proceed with an " + "attempt to reconnect if possible");
    return true;
  }

  private void reconnectSessionIfPossibleInternal(RouteInfo theRoute) {
    if (isConnected()) {
      return;
    }
    String sessionId = CastUtils.getStringFromPreference(mContext, PREFS_KEY_SESSION_ID);
    String routeId = CastUtils.getStringFromPreference(mContext, PREFS_KEY_ROUTE_ID);
    CastUtils.LOGD(TAG, "reconnectSessionIfPossible() Retrieved from preferences: " + "sessionId=" + sessionId + ", routeId=" + routeId);
    if (null == sessionId || null == routeId) {
      return;
    }
    mReconnectionStatus = ReconnectionStatus.IN_PROGRESS;
    CastDevice device = CastDevice.getFromBundle(theRoute.getExtras());

    if (null != device) {
      CastUtils.LOGD(TAG, "trying to acquire Cast Client for " + device);
      onDeviceSelected(device);
    }
  }

  /*
   * Cancels the task responsible for recovery of prior sessions, is used internally.
   */
  void cancelReconnectionTask() {
    CastUtils.LOGD(TAG, "cancelling reconnection task");
    if (null != mReconnectionTask && !mReconnectionTask.isCancelled()) {
      mReconnectionTask.cancel(true);
    }
  }

  /**
   * This method tries to automatically re-establish connection to a session if
   * <ul>
   * <li>User had not done a manual disconnect in the last session
   * <li>The Cast Device that user had connected to previously is still running the same session
   * </ul>
   * Under these conditions, a best-effort attempt will be made to continue with the same session.
   * This attempt will go on for <code>timeoutInSeconds</code> seconds. During this period, an
   * optional dialog can be shown if <code>showDialog</code> is set to <code>true</code>. The
   * message in this dialog can be changed by overriding the resource
   * <code>R.string.session_reconnection_attempt</code>
   */
  public void reconnectSessionIfPossible(final Context context, final boolean showDialog, final int timeoutInSeconds) {
    if (isConnected()) {
      return;
    }
    String routeId = CastUtils.getStringFromPreference(mContext, PREFS_KEY_ROUTE_ID);
    if (canConsiderSessionRecovery()) {
      List<RouteInfo> routes = mMediaRouter.getRoutes();
      RouteInfo theRoute = null;
      if (null != routes && !routes.isEmpty()) {
        for (RouteInfo route : routes) {
          if (route.getId().equals(routeId)) {
            theRoute = route;
            break;
          }
        }
      }
      if (null != theRoute) {
        // route has already been discovered, so lets just get the
        // device, etc
        reconnectSessionIfPossibleInternal(theRoute);
      } else {
        // we set a flag so if the route is discovered within a short
        // period, we let onRouteAdded callback of
        // CastMediaRouterCallback take
        // care of that
        mReconnectionStatus = ReconnectionStatus.STARTED;
      }

      // we may need to reconnect to an existing session
      mReconnectionTask = new AsyncTask<Void, Integer, Integer>() {
        private ProgressDialog dlg;
        private final int SUCCESS = 1;
        private final int FAILED = 2;

        @Override
        protected void onCancelled() {
          if (null != dlg) {
            dlg.dismiss();
          }
          super.onCancelled();
        }

        @Override
        protected void onPreExecute() {
          if (!showDialog) {
            return;
          }
          dlg = new ProgressDialog(context);
          dlg.setMessage(context.getString(R.string.session_reconnection_attempt));
          dlg.setIndeterminate(true);
          dlg.setCancelable(true);
          dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {

            @Override
            public void onCancel(DialogInterface dialog) {
              switch (mReconnectionStatus) {
                case STARTED:
                case IN_PROGRESS:
                case FINALIZE:
                  mReconnectionStatus = ReconnectionStatus.INACTIVE;
                  onDeviceSelected(null);
                  break;
                default:
                  break;
              }
              mReconnectionStatus = ReconnectionStatus.INACTIVE;
              if (null != dlg) {
                dlg.dismiss();
              }
              mReconnectionTask.cancel(true);
            }
          });
          dlg.setButton(ProgressDialog.BUTTON_NEGATIVE, "Cancel", new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
              switch (mReconnectionStatus) {
                case STARTED:
                case IN_PROGRESS:
                case FINALIZE:
                  mReconnectionStatus = ReconnectionStatus.INACTIVE;
                  onDeviceSelected(null);
                  break;
                default:
                  break;
              }
              mReconnectionStatus = ReconnectionStatus.INACTIVE;
              if (null != dlg) {
                dlg.cancel();
              }
              mReconnectionTask.cancel(true);
            }
          });
          dlg.show();
        }

        @Override
        protected Integer doInBackground(Void... params) {
          for (int i = 0; i < timeoutInSeconds; i++) {
            if (mReconnectionTask.isCancelled()) {
              if (null != dlg) {
                dlg.dismiss();
              }
              return SUCCESS;
            }
            try {
              if (isConnected()) {
                cancel(true);
              }
              Thread.sleep(1000);
            } catch (Exception e) {
              // ignore
            }
          }
          return FAILED;
        }

        @Override
        protected void onPostExecute(Integer result) {
          if (showDialog && null != dlg) {
            dlg.dismiss();
          }
          if (null != result) {
            if (result == FAILED) {
              mReconnectionStatus = ReconnectionStatus.INACTIVE;
              onDeviceSelected(null);
            }
          }
        }

      };
      mReconnectionTask.execute();
    }
  }

  /**
   * This method tries to automatically re-establish re-establish connection to a session if
   * <ul>
   * <li>User had not done a manual disconnect in the last session
   * <li>Device that user had connected to previously is still running the same session
   * </ul>
   * Under these conditions, a best-effort attempt will be made to continue with the same session.
   * This attempt will go on for 5 seconds. During this period, an optional dialog can be shown if
   * <code>showDialog</code> is set to <code>true
   * </code>.
   */
  public void reconnectSessionIfPossible(final Context context, final boolean showDialog) {
    reconnectSessionIfPossible(context, showDialog, SESSION_RECOVERY_TIMEOUT);
  }

  /************************************************************/
  /***** GoogleApiClient.ConnectionCallbacks ******************/
  /************************************************************/
  /**
   * This is called by the library when a connection is re-established after a transient
   * disconnect. Note: this is not called by SDK.
   */
  public void onConnectivityRecovered() {
    for (IBaseCastConsumer consumer : mBaseCastConsumers) {
      try {
        consumer.onConnectivityRecovered();
      } catch (Exception e) {
        CastUtils.LOGE(TAG, "onConnectivityRecovered: Failed to inform " + consumer, e);
      }
    }
  }

  /*
   * (non-Javadoc)
   * @see com.google.android.gms.GoogleApiClient.ConnectionCallbacks#onConnected
   * (android.os.Bundle)
   */
  @Override
  public void onConnected(Bundle arg0) {
    CastUtils.LOGD(TAG, "onConnected() reached with prior suspension: " + mConnectionSuspened);
    if (mConnectionSuspened) {
      mConnectionSuspened = false;
      onConnectivityRecovered();
      return;
    }
    if (!isConnected()) {
      if (mReconnectionStatus == ReconnectionStatus.IN_PROGRESS) {
        mReconnectionStatus = ReconnectionStatus.INACTIVE;
      }
      return;
    }
    try {
      Cast.CastApi.requestStatus(mApiClient);
      launchApp();

      if (null != mBaseCastConsumers) {
        for (IBaseCastConsumer consumer : mBaseCastConsumers) {
          try {
            consumer.onConnected();
          } catch (Exception e) {
            CastUtils.LOGE(TAG, "onConnected: Failed to inform " + consumer, e);
          }
        }
      }

    } catch (IOException e) {
      CastUtils.LOGE(TAG, "error requesting status", e);
    } catch (IllegalStateException e) {
      CastUtils.LOGE(TAG, "error requesting status", e);
    } catch (TransientNetworkDisconnectionException e) {
      CastUtils.LOGE(TAG, "error requesting status due to network issues", e);
    } catch (NoConnectionException e) {
      CastUtils.LOGE(TAG, "error requesting status due to network issues", e);
    }

  }

  /*
   * Note: this is not called by the SDK anymore but this library calls this in the appropriate
   * time.
   */
  protected void onDisconnected() {
    CastUtils.LOGD(TAG, "onDisconnected() reached");
    mDeviceName = null;
    if (null != mBaseCastConsumers) {
      for (IBaseCastConsumer consumer : mBaseCastConsumers) {
        try {
          consumer.onDisconnected();
        } catch (Exception e) {
          CastUtils.LOGE(TAG, "onDisconnected(): Failed to inform " + consumer, e);
        }
      }
    }
  }

  /*
   * (non-Javadoc)
   * @see com.google.android.gms.GoogleApiClient.OnConnectionFailedListener#
   * onConnectionFailed(com.google.android.gms.common.ConnectionResult)
   */
  @Override
  public void onConnectionFailed(ConnectionResult result) {
    CastUtils.LOGD(TAG, "onConnectionFailed() reached, error code: " + result.getErrorCode() + ", reason: " + result
        .toString());
    mSelectedCastDevice = null;
    if (null != mMediaRouter) {
      mMediaRouter.selectRoute(mMediaRouter.getDefaultRoute());
    }
    boolean showError = false;
    if (null != mBaseCastConsumers) {
      for (IBaseCastConsumer consumer : mBaseCastConsumers) {
        try {
          consumer.onConnectionFailed(result);
        } catch (Exception e) {
          CastUtils.LOGE(TAG, "onConnectionFailed(): Failed to inform " + consumer, e);
        }
      }
    }
    if (showError) {
      CastUtils.showErrorDialog(mContext, R.string.failed_to_connect);
    }
  }

  @Override
  public void onConnectionSuspended(int cause) {
    mConnectionSuspened = true;
    CastUtils.LOGD(TAG, "onConnectionSuspended() was called with cause: " + cause);
    for (IBaseCastConsumer consumer : mBaseCastConsumers) {
      try {
        consumer.onConnectionSuspended(cause);
      } catch (Exception e) {
        CastUtils.LOGE(TAG, "onConnectionSuspended(): Failed to inform " + consumer, e);
      }
    }
  }

  /*
   * Launches application. For this succeed, a connection should be already established by the
   * CastClient.
   */
  private void launchApp() throws TransientNetworkDisconnectionException, NoConnectionException {
    CastUtils.LOGD(TAG, "launchApp() is called");
    if (!isConnected()) {
      if (mReconnectionStatus == ReconnectionStatus.IN_PROGRESS) {
        mReconnectionStatus = ReconnectionStatus.INACTIVE;
        return;
      }
      checkConnectivity();
    }

    if (mReconnectionStatus == ReconnectionStatus.IN_PROGRESS) {
      CastUtils.LOGD(TAG, "Attempting to join a previously interrupted session...");
      String sessionId = CastUtils.getStringFromPreference(mContext, PREFS_KEY_SESSION_ID);
      CastUtils.LOGD(TAG, "joinApplication() -> start");
      Cast.CastApi.joinApplication(mApiClient, mApplicationId, sessionId)
          .setResultCallback(new ResultCallback<Cast.ApplicationConnectionResult>() {

            @Override
            public void onResult(ApplicationConnectionResult result) {
              if (result.getStatus().isSuccess()) {
                CastUtils.LOGD(TAG, "joinApplication() -> success");
                onApplicationConnected(result.getApplicationMetadata(), result.getApplicationStatus(), result
                    .getSessionId(), result.getWasLaunched());
              } else {
                CastUtils.LOGD(TAG, "joinApplication() -> failure");
                onApplicationConnectionFailed(result.getStatus().getStatusCode());
              }
            }
          });
    } else {
      CastUtils.LOGD(TAG, "Launching app");
      Cast.CastApi.launchApplication(mApiClient, mApplicationId)
          .setResultCallback(new ResultCallback<Cast.ApplicationConnectionResult>() {

            @Override
            public void onResult(ApplicationConnectionResult result) {
              if (result.getStatus().isSuccess()) {
                CastUtils.LOGD(TAG, "launchApplication() -> success result");
                onApplicationConnected(result.getApplicationMetadata(), result.getApplicationStatus(), result
                    .getSessionId(), result.getWasLaunched());
              } else {
                CastUtils.LOGD(TAG, "launchApplication() -> failure result");
                onApplicationConnectionFailed(result.getStatus().getStatusCode());
              }
            }
          });
    }
  }

  /**
   * Stops the application on the receiver device.
   */
  public void stopApplication() throws IllegalStateException, IOException, TransientNetworkDisconnectionException, NoConnectionException {
    checkConnectivity();
    Cast.CastApi.stopApplication(mApiClient).setResultCallback(new ResultCallback<Status>() {

      @Override
      public void onResult(Status result) {
        if (!result.isSuccess()) {
          CastUtils.LOGD(TAG, "stopApplication -> onResult: stopping " + "application failed");
          onApplicationStopFailed(result.getStatusCode());
        } else {
          CastUtils.LOGD(TAG, "stopApplication -> onResult Stopped application " + "successfully");
        }
      }
    });
  }

  /*************************************************************/
  /***** Registering IBaseCastConsumer listeners ***************/
  /*************************************************************/
  /**
   * Registers an {@link IBaseCastConsumer} interface with this class. Registered listeners will
   * be notified of changes to a variety of lifecycle callbacks that the interface provides.
   */
  public synchronized void addBaseCastConsumer(IBaseCastConsumer listener) {
    if (null != listener) {
      if (mBaseCastConsumers.add(listener)) {
        CastUtils.LOGD(TAG, "Successfully added the new BaseCastConsumer listener " + listener);
      }
    }
  }

  /**
   * Unregisters an {@link IBaseCastConsumer}.
   */
  public synchronized void removeBaseCastConsumer(IBaseCastConsumer listener) {
    if (null != listener) {
      if (mBaseCastConsumers.remove(listener)) {
        CastUtils.LOGD(TAG, "Successfully removed the existing BaseCastConsumer listener " + listener);
      }
    }
  }

  /**
   * A simple method that throws an exception of there is no connectivity to the cast device.
   */
  public void checkConnectivity() throws TransientNetworkDisconnectionException, NoConnectionException {
    if (!isConnected()) {
      if (mConnectionSuspened) {
        throw new TransientNetworkDisconnectionException();
      } else {
        throw new NoConnectionException();
      }
    }
  }

  @Override
  public void onFailed(int resourceId, int statusCode) {
    CastUtils.LOGD(TAG, "onFailed() was called with statusCode: " + statusCode);
    for (IBaseCastConsumer consumer : mBaseCastConsumers) {
      try {
        consumer.onFailed(resourceId, statusCode);
      } catch (Exception e) {
        CastUtils.LOGE(TAG, "onFailed(): Failed to inform " + consumer, e);
      }
    }

  }
}
