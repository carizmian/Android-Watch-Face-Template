package com.swarmnyc.watchfaces;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.util.TimeZone;

public class ModernWeatherWatchFaceService extends CanvasWatchFaceService {
// ------------------------------ FIELDS ------------------------------

    private static final String TAG = "ModernWatchFaceService";
    private static String PACKAGE_NAME = ModernWeatherWatchFaceService.class.getPackage().getName();

// -------------------------- OTHER METHODS --------------------------

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

// -------------------------- INNER CLASSES --------------------------

    private class Engine extends CanvasWatchFaceService.Engine
            implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            MessageApi.MessageListener,
            NodeApi.NodeListener {
// ------------------------------ FIELDS ------------------------------

        public static final String KEY_CONFIG_REQUIRE_INTERVAL = "RequireInterval";
        public static final String KEY_CONFIG_TEMPERATURE_SCALE = "TemperatureScale";
        public static final String KEY_WEATHER_CONDITION = "Condition";
        public static final String KEY_WEATHER_SUNRISE = "Sunrise";
        public static final String KEY_WEATHER_SUNSET = "Sunset";
        public static final String KEY_CONFIG_THEME = "Theme";
        public static final String KEY_CONFIG_TIME_UNIT = "TimeUnit";
        public static final String KEY_WEATHER_TEMPERATURE = "Temperature";
        public static final String PATH_CONFIG = "/ModernWeatherWatchFace/Config";
        public static final String PATH_WEATHER_INFO = "/WeatherWatchFace/WeatherInfo";
        public static final String PATH_WEATHER_REQUIRE = "/WeatherService/Require";
        public static final int TIME_DATE_DISTANCE = 5;
        private static final String COLON_STRING = ":";
        private static final int MSG_UPDATE_TIME = 0;
        /**
         * Update rate in milliseconds for normal (not ambient and not mute) mode.
         * We update twice a second to blink the colons.
         */
        private static final long UPDATE_RATE_MS = 500;
        private static final long WEATHER_INFO_TIME_OUT = DateUtils.HOUR_IN_MILLIS * 4;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        /**
         * Handler to update the time periodically in interactive mode.
         */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();

                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = UPDATE_RATE_MS - (timeMs % UPDATE_RATE_MS);

                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }

                        requireWeatherInfo();
                        break;
                }
            }
        };
        AssetManager mAsserts;
        Bitmap mWeatherConditionDrawable;
        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(ModernWeatherWatchFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();
        Paint mBackgroundPaint;
        Paint mDatePaint;
        Paint mDebugInfoPaint;
        Paint mTemperaturePaint;
        Paint mTemperatureSuffixPaint;
        Paint mTimePaint;
        Resources mResources;
        String mWeatherCondition;
        String mWeatherConditionResourceName;
        Time mSunriseTime;
        Time mSunsetTime;
        Time mTime;
        boolean isRound;
        boolean mGotConfig;
        boolean mLowBitAmbient;
        boolean mRegisteredService = false;
        float mColonXOffset;
        float mDateYOffset;
        float mDebugInfoYOffset;
        float mTemperatureSuffixYOffset;
        float mTemperatureYOffset;
        float mTimeYOffset;
        int mBackgroundColor;
        int mBackgroundDefaultColor;
        int mRequireInterval;
        int mTemperature = Integer.MAX_VALUE;
        int mTemperatureScale;
        long mWeatherInfoReceivedTime;
        long mWeatherInfoRequiredTime;
        int mTheme = 3;
        float mTemperature_picture_size;
        int mTimeUnit =  ConverterUtil.TIME_UNIT_12;
        int mDateColor;
        int mDateDefaultColor;
        int mTemperatureColor;
        int mTemperatureDefaultColor;

// ------------------------ INTERFACE METHODS ------------------------


// --------------------- Interface ConnectionCallbacks ---------------------

        @Override
        public void onConnected(Bundle bundle) {
            log("Connected: " + bundle);
            getConfig();

            Wearable.MessageApi.addListener(mGoogleApiClient, this);
            Wearable.NodeApi.addListener(mGoogleApiClient, this);
            requireWeatherInfo();
        }

        @Override
        public void onConnectionSuspended(int i) {
            log("ConnectionSuspended: " + i);
        }

// --------------------- Interface MessageListener ---------------------

        @Override
        public void onMessageReceived(MessageEvent messageEvent) {
            byte[] rawData = messageEvent.getData();
            DataMap dataMap = DataMap.fromByteArray(rawData);
            log("onMessageReceived: " + dataMap);

            fetchConfig(dataMap);

            if (messageEvent.getPath().equals(PATH_WEATHER_INFO)) {
                mWeatherInfoReceivedTime = System.currentTimeMillis();
            }

            if (messageEvent.getPath().equals(PATH_CONFIG)) {
                saveConfig();
            }
        }

// --------------------- Interface NodeListener ---------------------

        @Override
        public void onPeerConnected(Node node) {
            log("PeerConnected: " + node);
            requireWeatherInfo();
        }

        @Override
        public void onPeerDisconnected(Node node) {
            log("PeerDisconnected: " + node);
        }

// --------------------- Interface OnConnectionFailedListener ---------------------

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            log("ConnectionFailed: " + connectionResult);

        }

// -------------------------- OTHER METHODS --------------------------

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            log("onAmbientModeChanged: " + inAmbientMode);

            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mTimePaint.setAntiAlias(antiAlias);
                mDatePaint.setAntiAlias(antiAlias);
                mTemperaturePaint.setAntiAlias(antiAlias);
                mTemperatureSuffixPaint.setAntiAlias(antiAlias);
            }

            if (inAmbientMode) {
                mBackgroundPaint.setColor(mBackgroundDefaultColor);
                mDatePaint.setColor(mDateDefaultColor);
                mTemperaturePaint.setColor(mTemperatureDefaultColor);
                mTemperatureSuffixPaint.setColor(mTemperatureDefaultColor);
            } else {
                mBackgroundPaint.setColor(mBackgroundColor);
                mDatePaint.setColor(mDateColor);
                mTemperaturePaint.setColor(mTemperatureColor);
                mTemperatureSuffixPaint.setColor(mTemperatureColor);
            }

            invalidate();

            // Whether the timer should be running depends on whether we're in ambient mode (as well
            // as whether we're visible), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            isRound = insets.isRound();

            float timeTextSize = mResources.getDimension(isRound ?
                    R.dimen.modern_time_size_round : R.dimen.modern_time_size);

            float dateTextSize = mResources.getDimension(isRound ?
                    R.dimen.modern_date_size_round : R.dimen.modern_date_size);

            float tempTextSize = mResources.getDimension(isRound ?
                    R.dimen.modern_temperature_size_round : R.dimen.modern_temperature_size);

            float tempSuffixTextSize = mResources.getDimension(isRound ?
                    R.dimen.modern_temperature_suffix_size_round : R.dimen.modern_temperature_suffix_size);

            mTimePaint.setTextSize(timeTextSize);
            mDatePaint.setTextSize(dateTextSize);
            mTemperaturePaint.setTextSize(tempTextSize);
            mTemperatureSuffixPaint.setTextSize(tempSuffixTextSize);

            mTimeYOffset += (mTimePaint.descent() + mTimePaint.ascent()) / 2;
            mColonXOffset = mTimePaint.measureText(COLON_STRING) / 2;
            mDateYOffset = (mDatePaint.descent() + mDatePaint.ascent()) / 2;
            mTemperatureYOffset = (mTemperaturePaint.descent() + mTemperaturePaint.ascent()) / 2;
            mTemperatureSuffixYOffset = (mTemperatureSuffixPaint.descent() + mTemperatureSuffixPaint.ascent()) / 2;
            mDebugInfoYOffset = 5 + mDebugInfoPaint.getTextSize() + (mDebugInfoPaint.descent() + mDebugInfoPaint.ascent()) / 2;
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(ModernWeatherWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
            		.setAmbientPeekMode(WatchFaceStyle.AMBIENT_PEEK_MODE_HIDDEN)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            mResources = ModernWeatherWatchFaceService.this.getResources();
            mAsserts = ModernWeatherWatchFaceService.this.getAssets();

            mBackgroundColor = mBackgroundDefaultColor = mResources.getColor(R.color.modern_bg_color);
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(mBackgroundDefaultColor);

            Typeface timeFont = Typeface.createFromAsset(mAsserts, mResources.getString(R.string.modern_time_font));
            Typeface dateFont = Typeface.createFromAsset(mAsserts, mResources.getString(R.string.modern_date_font));
            Typeface tempFont = Typeface.createFromAsset(mAsserts, mResources.getString(R.string.modern_temperature_font));

            mDateColor = mDateDefaultColor = mResources.getColor(R.color.modern_date_color);
            mTemperatureColor = mTemperatureDefaultColor = mResources.getColor(R.color.modern_temperature_color);

            mTimePaint = createTextPaint(mResources.getColor(R.color.modern_time_color), timeFont);
            mDatePaint = createTextPaint(mDateColor, dateFont);
            mTemperaturePaint = createTextPaint(mTemperatureColor, tempFont);
            mTemperatureSuffixPaint = createTextPaint(mTemperatureColor, tempFont);

            mTemperature_picture_size = mResources.getDimension(R.dimen.modern_temperature_picture_size);

            mDebugInfoPaint = new Paint();
            mDebugInfoPaint.setColor(Color.parseColor("White"));
            mDebugInfoPaint.setTextSize(20);
            mDebugInfoPaint.setAntiAlias(true);

            mTime = new Time();
            mSunriseTime = new Time();
            mSunsetTime = new Time();

            mRequireInterval = mResources.getInteger(R.integer.WeatherDefaultRequireInterval);

            mGoogleApiClient.connect();
        }

        @Override
        public void onDestroy() {
            log("Destroy");
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mTime.setToNow();

            int width = bounds.width();
            int height = bounds.height();
            float radius = width / 2;

            canvas.drawRect(0, 0, width, height, mBackgroundPaint);

            // Time
            boolean mShouldDrawColons = (System.currentTimeMillis() % 1000) < 500;

            String hourString = String.format("%02d", ConverterUtil.convertHour(mTime.hour, mTimeUnit));
            String minString = String.format("%02d", mTime.minute);

            //For Test
//            hourString = "12";
//            minString = "30";
//            mTemperature = 50;
//            mWeatherCondition = "clear";
//            mWeatherInfoReceivedTime = System.currentTimeMillis();
//            mSunriseTime.set(mWeatherInfoReceivedTime-10000);
//            mSunsetTime.set(mWeatherInfoReceivedTime+10000);

            float hourWidth = mTimePaint.measureText(hourString);

            float x = radius - hourWidth - mColonXOffset;
            float y = radius - mTimeYOffset + TIME_DATE_DISTANCE;
            float suffixY;

            canvas.drawText(hourString, x, y, mTimePaint);

            x = radius - mColonXOffset;

            if (isInAmbientMode() || mShouldDrawColons) {
                canvas.drawText(COLON_STRING, x, y, mTimePaint);
            }

            x = radius + mColonXOffset;
            canvas.drawText(minString, x, y, mTimePaint);

            //Date
            y = radius + mTimeYOffset + mDateYOffset;

            String dateString = ConverterUtil.convertToMonth(mTime.month).toUpperCase() + " " + String.valueOf(mTime.monthDay);

            float monthWidth = mDatePaint.measureText(dateString);

            x = radius - monthWidth / 2;
            canvas.drawText(dateString, x, y, mDatePaint);

            //WeatherInfo
            long timeSpan = System.currentTimeMillis() - mWeatherInfoReceivedTime;
            if (timeSpan <= WEATHER_INFO_TIME_OUT) {
                // photo
                if (!TextUtils.isEmpty(mWeatherCondition)) {
                    StringBuilder stringBuilder = new StringBuilder();
                    stringBuilder.append("weather_");
                    stringBuilder.append(mWeatherCondition);

                    if ((mWeatherCondition.equals("cloudy") || mWeatherCondition.equals("clear")) && (Time.compare(mTime, mSunriseTime) < 0 || Time.compare(mTime, mSunsetTime) > 0)) {
                        //cloudy and clear have night picture
                        stringBuilder.append("night");
                    }

                    stringBuilder.append("_gray");

                    String name = stringBuilder.toString();
                    if (!name.equals(mWeatherConditionResourceName)) {
                        log("CreateScaledBitmap: " + name);
                        mWeatherConditionResourceName = name;
                        int id = mResources.getIdentifier(name, "drawable", PACKAGE_NAME);

                        Drawable b = mResources.getDrawable(id);
                        mWeatherConditionDrawable = ((BitmapDrawable) b).getBitmap();
                        float scaledWidth = (mTemperature_picture_size / mWeatherConditionDrawable.getHeight()) * mWeatherConditionDrawable.getWidth();
                        mWeatherConditionDrawable = Bitmap.createScaledBitmap(mWeatherConditionDrawable, (int)scaledWidth,(int)mTemperature_picture_size, true);
                    }

                    canvas.drawBitmap(mWeatherConditionDrawable, radius - mWeatherConditionDrawable.getWidth() / 2, height - mWeatherConditionDrawable.getHeight(), null);
                }

                //temperature
                if (mTemperature != Integer.MAX_VALUE && !isInAmbientMode()) {
                    String temperatureString = String.valueOf(mTemperature);
                    String temperatureScaleString = mTemperatureScale == ConverterUtil.FAHRENHEIT ? ConverterUtil.FAHRENHEIT_STRING : ConverterUtil.CELSIUS_STRING;
                    float temperatureWidth = mTemperaturePaint.measureText(temperatureString);
                    float temperatureRadius = (temperatureWidth + mTemperatureSuffixPaint.measureText(temperatureScaleString)) / 2;
                    x = radius - temperatureRadius;
                    y = bounds.height() * 0.75f;
                    suffixY = y - mTemperatureSuffixYOffset;
                    y -= mTemperatureYOffset;
                    canvas.drawText(temperatureString, x, y, mTemperaturePaint);
                    x += temperatureWidth;
                    canvas.drawText(temperatureScaleString, x, suffixY, mTemperatureSuffixPaint);
                }
            }


            if (BuildConfig.DEBUG) {
                String timeString;
                if (mWeatherInfoReceivedTime == 0) {
                    timeString = "No data received";
                } else if (timeSpan > DateUtils.HOUR_IN_MILLIS) {
                    timeString = "Get: " + String.valueOf(timeSpan / DateUtils.HOUR_IN_MILLIS) + " hours ago";
                } else if (timeSpan > DateUtils.MINUTE_IN_MILLIS) {
                    timeString = "Get: " + String.valueOf(timeSpan / DateUtils.MINUTE_IN_MILLIS) + " mins ago";
                } else {
                    timeString = "Get: " + String.valueOf(timeSpan / DateUtils.SECOND_IN_MILLIS) + " secs ago";
                }

                canvas.drawText(timeString, width - mDebugInfoPaint.measureText(timeString), mDebugInfoYOffset, mDebugInfoPaint);
            }
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            super.onInterruptionFilterChanged(interruptionFilter);

            log("onInterruptionFilterChanged: " + interruptionFilter);

            //TODO: to understand onInterruptionFilterChanged
            //boolean inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE;
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);

            log("onPropertiesChanged: LowBitAmbient=" + mLowBitAmbient);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            log("TimeTick");
            invalidate();
            requireWeatherInfo();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            log("onVisibilityChanged: " + visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerTimeZoneService();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.MessageApi.removeListener(mGoogleApiClient, this);
                    Wearable.NodeApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }

                unregisterTimeZoneService();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private Paint createTextPaint(int color, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(color);
            if (typeface != null)
                paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }


        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        private void fetchConfig(DataMap config) {
            if (config.containsKey(KEY_WEATHER_CONDITION)) {
                String cond = config.getString(KEY_WEATHER_CONDITION);
                if (TextUtils.isEmpty(cond)) {
                    mWeatherCondition = null;
                } else {
                    mWeatherCondition = cond;
                }
            }

            if (config.containsKey(KEY_WEATHER_TEMPERATURE)) {
                mTemperature = config.getInt(KEY_WEATHER_TEMPERATURE);
                if (mTemperatureScale != ConverterUtil.FAHRENHEIT) {
                    mTemperature = ConverterUtil.convertFahrenheitToCelsius(mTemperature);
                }
            }

            if (config.containsKey(KEY_WEATHER_SUNRISE)) {
                mSunriseTime.set(config.getLong(KEY_WEATHER_SUNRISE) * 1000);
                log("SunriseTime: " + mSunriseTime);
            }

            if (config.containsKey(KEY_WEATHER_SUNSET)) {
                mSunsetTime.set(config.getLong(KEY_WEATHER_SUNSET) * 1000);
                log("SunsetTime: " + mSunsetTime);
            }

            if (config.containsKey(KEY_CONFIG_TEMPERATURE_SCALE)) {
                int scale = config.getInt(KEY_CONFIG_TEMPERATURE_SCALE);

                if (scale != mTemperatureScale) {
                    if (scale == ConverterUtil.FAHRENHEIT) {
                        mTemperature = ConverterUtil.convertCelsiusToFahrenheit(mTemperature);
                    } else {
                        mTemperature = ConverterUtil.convertFahrenheitToCelsius(mTemperature);
                    }
                }

                mTemperatureScale = scale;
            }

            if (config.containsKey(KEY_CONFIG_THEME)) {
                mTheme = config.getInt(KEY_CONFIG_THEME);

                mBackgroundColor = mResources.getColor(mResources.getIdentifier("modern_theme_" + mTheme + "_bg", "color", PACKAGE_NAME));
                mDateColor = mResources.getColor(mResources.getIdentifier("modern_theme_" + mTheme + "_date", "color", PACKAGE_NAME));
                mTemperatureColor = mResources.getColor(mResources.getIdentifier("modern_theme_" + mTheme + "_temperature", "color", PACKAGE_NAME));

                if (!isInAmbientMode()) {
                    mBackgroundPaint.setColor(mBackgroundColor);
                    mDatePaint.setColor(mDateColor);
                    mTemperaturePaint.setColor(mTemperatureColor);
                    mTemperatureSuffixPaint.setColor(mTemperatureColor);
                }
            }

            if (config.containsKey(KEY_CONFIG_TIME_UNIT)) {
                mTimeUnit = config.getInt(KEY_CONFIG_TIME_UNIT);
            }

            if (config.containsKey(KEY_CONFIG_REQUIRE_INTERVAL)) {
                mRequireInterval = config.getInt(KEY_CONFIG_REQUIRE_INTERVAL);
            }

            invalidate();
        }

        private void getConfig() {
            if (mGotConfig) {
                return;
            }

            log("Start getting Config");
            Wearable.NodeApi.getLocalNode(mGoogleApiClient).setResultCallback(new ResultCallback<NodeApi.GetLocalNodeResult>() {
                @Override
                public void onResult(NodeApi.GetLocalNodeResult getLocalNodeResult) {
                    Uri uri = new Uri.Builder()
                            .scheme("wear")
                            .path(PATH_CONFIG)
                            .authority(getLocalNodeResult.getNode().getId())
                            .build();

                    Wearable.DataApi.getDataItem(mGoogleApiClient, uri)
                            .setResultCallback(
                                    new ResultCallback<DataApi.DataItemResult>() {
                                        @Override
                                        public void onResult(DataApi.DataItemResult dataItemResult) {
                                            log("Finish Config: " + dataItemResult.getStatus());
                                            if (dataItemResult.getStatus().isSuccess() && dataItemResult.getDataItem() != null) {
                                                fetchConfig(DataMapItem.fromDataItem(dataItemResult.getDataItem()).getDataMap());
                                            }
                                        }
                                    }
                            );
                }
            });
        }

        private void log(String message) {
            Log.d(TAG, message);
        }

        private void registerTimeZoneService() {
            //TimeZone and TemperatureSensor
            if (mRegisteredService) {
                return;
            }

            mRegisteredService = true;

            // TimeZone
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            ModernWeatherWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void requireWeatherInfo() {
            if (!mGoogleApiClient.isConnected())
                return;

            long timeMs = System.currentTimeMillis();

            // The weather info is still up to date.
            if ((timeMs - mWeatherInfoReceivedTime) <= mRequireInterval)
                return;

            // Try once in a min.
            if ((timeMs - mWeatherInfoRequiredTime) <= DateUtils.MINUTE_IN_MILLIS)
                return;

            mWeatherInfoRequiredTime = timeMs;
            Wearable.MessageApi.sendMessage(mGoogleApiClient, "", PATH_WEATHER_REQUIRE, null)
                    .setResultCallback(new ResultCallback<MessageApi.SendMessageResult>() {
                        @Override
                        public void onResult(MessageApi.SendMessageResult sendMessageResult) {
                            log("SendRequireMessage:" + sendMessageResult.getStatus());
                        }
                    });
        }

        private void saveConfig() {
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(PATH_CONFIG);
            DataMap config = putDataMapRequest.getDataMap();

            config.putInt(KEY_CONFIG_TEMPERATURE_SCALE, mTemperatureScale);
            config.putInt(KEY_CONFIG_THEME, mTheme);
            config.putInt(KEY_CONFIG_TIME_UNIT, mTimeUnit);
            config.putInt(KEY_CONFIG_REQUIRE_INTERVAL, mRequireInterval);

            Wearable.DataApi.putDataItem(mGoogleApiClient, putDataMapRequest.asPutDataRequest())
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            log("SaveConfig: " + dataItemResult.getStatus() + ", " + dataItemResult.getDataItem().getUri());
                        }
                    });
        }

        private void unregisterTimeZoneService() {
            if (!mRegisteredService) {
                return;
            }
            mRegisteredService = false;

            //TimeZone
            ModernWeatherWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }
    }
}
