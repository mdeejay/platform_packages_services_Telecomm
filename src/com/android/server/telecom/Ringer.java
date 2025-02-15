/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Person;
import android.content.ContentResolver;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.VibrationEffect;
import android.telecom.Log;
import android.telecom.TelecomManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.VolumeShaper;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.LogUtils.EventTimer;
import com.android.internal.util.syberia.SyberiaUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Controls the ringtone player.
 */
@VisibleForTesting
public class Ringer {
    public static class VibrationEffectProxy {
        public VibrationEffect createWaveform(long[] timings, int[] amplitudes, int repeat) {
            return VibrationEffect.createWaveform(timings, amplitudes, repeat);
        }

        public VibrationEffect get(Uri ringtoneUri, Context context) {
            return VibrationEffect.get(ringtoneUri, context);
        }
    }
    @VisibleForTesting
    public VibrationEffect mDefaultVibrationEffect;

    private static final long[] PULSE_PRIMING_PATTERN = {0,12,250,12,500}; // priming  + interval

    private static final int[] PULSE_PRIMING_AMPLITUDE = {0,255,0,255,0};  // priming  + interval

    // ease-in + peak + pause
    private static final long[] PULSE_RAMPING_PATTERN = {
        50,50,50,50,50,50,50,50,50,50,50,50,50,50,300,1000};

    // ease-in (min amplitude = 30%) + peak + pause
    private static final int[] PULSE_RAMPING_AMPLITUDE = {
        77,77,78,79,81,84,87,93,101,114,133,162,205,255,255,0};

    private static final long[] PULSE_PATTERN;

    private static final int[] PULSE_AMPLITUDE;

    private static final int RAMPING_RINGER_VIBRATION_DURATION = 5000;
    private static final int RAMPING_RINGER_DURATION = 10000;

    private static final int OUTGOING_CALL_VIBRATING_DURATION = 100;

    static {
        // construct complete pulse pattern
        PULSE_PATTERN = new long[PULSE_PRIMING_PATTERN.length + PULSE_RAMPING_PATTERN.length];
        System.arraycopy(
            PULSE_PRIMING_PATTERN, 0, PULSE_PATTERN, 0, PULSE_PRIMING_PATTERN.length);
        System.arraycopy(PULSE_RAMPING_PATTERN, 0, PULSE_PATTERN,
            PULSE_PRIMING_PATTERN.length, PULSE_RAMPING_PATTERN.length);

        // construct complete pulse amplitude
        PULSE_AMPLITUDE = new int[PULSE_PRIMING_AMPLITUDE.length + PULSE_RAMPING_AMPLITUDE.length];
        System.arraycopy(
            PULSE_PRIMING_AMPLITUDE, 0, PULSE_AMPLITUDE, 0, PULSE_PRIMING_AMPLITUDE.length);
        System.arraycopy(PULSE_RAMPING_AMPLITUDE, 0, PULSE_AMPLITUDE,
            PULSE_PRIMING_AMPLITUDE.length, PULSE_RAMPING_AMPLITUDE.length);
    }

    private static final long[] SIMPLE_VIBRATION_PATTERN = {
        0, // No delay before starting
        800, // How long to vibrate
        800, // How long to wait before vibrating again
    };

    private static final long[] DZZZ_DA_VIBRATION_PATTERN = {
        0, // No delay before starting
        500, // How long to vibrate
        200, // Delay
        70, // How long to vibrate
        720, // How long to wait before vibrating again
    };

    private static final long[] MM_MM_MM_VIBRATION_PATTERN = {
        0, // No delay before starting
        300, // How long to vibrate
        400, // Delay
        300, // How long to vibrate
        400, // Delay
        300, // How long to vibrate
        1400, // How long to wait before vibrating again
    };

    private static final long[] DA_DA_DZZZ_VIBRATION_PATTERN = {
        0, // No delay before starting
        70, // How long to vibrate
        80, // Delay
        70, // How long to vibrate
        180, // Delay
        600,  // How long to vibrate
        1050, // How long to wait before vibrating again
    };

    private static final long[] DA_DZZZ_DA_VIBRATION_PATTERN = {
        0, // No delay before starting
        80, // How long to vibrate
        200, // Delay
        600, // How long to vibrate
        150, // Delay
        60,  // How long to vibrate
        1050, // How long to wait before vibrating again
    };

    private static final int[] SEVEN_ELEMENTS_VIBRATION_AMPLITUDE = {
        0, // No delay before starting
        255, // Vibrate full amplitude
        0, // No amplitude while waiting
        255,
        0,
        255,
        0,
    };

    private static final int[] FIVE_ELEMENTS_VIBRATION_AMPLITUDE = {
        0, // No delay before starting
        255, // Vibrate full amplitude
        0, // No amplitude while waiting
        255,
        0,
    };

    private static final int[] SIMPLE_VIBRATION_AMPLITUDE = {
        0, // No delay before starting
        255, // Vibrate full amplitude
        0, // No amplitude while waiting
    };

    private boolean mUseSimplePattern;
    private int mVibrationPattern;
    private SettingsObserver mSettingObserver;
    private final Handler mH = new Handler();

    /**
     * Indicates that vibration should be repeated at element 5 in the {@link #PULSE_AMPLITUDE} and
     * {@link #PULSE_PATTERN} arrays.  This means repetition will happen for the main ease-in/peak
     * pattern, but the priming + interval part will not be repeated.
     */
    private static final int REPEAT_VIBRATION_AT = 5;

    private static final int REPEAT_SIMPLE_VIBRATION_AT = 1;

    private int mRampingRingerDuration = -1;  // ramping ringer duration in millisecond
    private float mRampingRingerStartVolume = 0f;

    private static final float EPSILON = 1e-6f;

    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build();

    private static VibrationEffect mRampingRingerVibrationEffect;
    private static VolumeShaper.Configuration mVolumeShaperConfig;

    /**
     * Used to keep ordering of unanswered incoming calls. There can easily exist multiple incoming
     * calls and explicit ordering is useful for maintaining the proper state of the ringer.
     */

    private final SystemSettingsUtil mSystemSettingsUtil;
    private final InCallTonePlayer.Factory mPlayerFactory;
    private final AsyncRingtonePlayer mRingtonePlayer;
    private final Context mContext;
    private final Vibrator mVibrator;
    private final InCallController mInCallController;
    private final VibrationEffectProxy mVibrationEffectProxy;
    private final boolean mIsHapticPlaybackSupportedByDevice;
    /**
     * For unit testing purposes only; when set, {@link #startRinging(Call, boolean)} will complete
     * the future provided by the test using {@link #setBlockOnRingingFuture(CompletableFuture)}.
     */
    private CompletableFuture<Void> mBlockOnRingingFuture = null;

    private CompletableFuture<Void> mVibrateFuture = CompletableFuture.completedFuture(null);

    private InCallTonePlayer mCallWaitingPlayer;
    private RingtoneFactory mRingtoneFactory;

    /**
     * Call objects that are ringing, vibrating or call-waiting. These are used only for logging
     * purposes.
     */
    private Call mRingingCall;
    private Call mVibratingCall;
    private Call mCallWaitingCall;

    /**
     * Used to track the status of {@link #mVibrator} in the case of simultaneous incoming calls.
     */
    private volatile boolean mIsVibrating = false;

    private boolean mIsFlash = false;

    private int mSavedInCallVolume = 0;

    private final BroadcastReceiver mVolumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null ||
                    !intent.getAction().equals(AudioManager.VOLUME_CHANGED_ACTION)) {
                return;
            }
            if (intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1)
                    != AudioManager.STREAM_VOICE_CALL) {
                return;
            }
            int index = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 0);
            Log.d(this, "VolumeReceiver and new index is " + index);
            mSavedInCallVolume = index;
        }
    };

    /** Initializes the Ringer. */
    @VisibleForTesting
    public Ringer(
            InCallTonePlayer.Factory playerFactory,
            Context context,
            SystemSettingsUtil systemSettingsUtil,
            AsyncRingtonePlayer asyncRingtonePlayer,
            RingtoneFactory ringtoneFactory,
            Vibrator vibrator,
            VibrationEffectProxy vibrationEffectProxy,
            InCallController inCallController) {

        mSystemSettingsUtil = systemSettingsUtil;
        mPlayerFactory = playerFactory;
        mContext = context;
        // We don't rely on getSystemService(Context.VIBRATOR_SERVICE) to make sure this
        // vibrator object will be isolated from others.
        mVibrator = vibrator;
        mRingtonePlayer = asyncRingtonePlayer;
        mRingtoneFactory = ringtoneFactory;
        mInCallController = inCallController;
        mVibrationEffectProxy = vibrationEffectProxy;
        mUseSimplePattern = mContext.getResources().getBoolean(R.bool.use_simple_vibration_pattern);
        mVibrationPattern = Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.RINGTONE_VIBRATION_PATTERN, 0, UserHandle.USER_CURRENT);

        updateVibrationPattern();

        mSettingObserver = new SettingsObserver(mH);
        mContext.getContentResolver().registerContentObserver(
            Settings.System.getUriFor(Settings.System.RINGTONE_VIBRATION_PATTERN),
            true, mSettingObserver, UserHandle.USER_CURRENT);

        mIsHapticPlaybackSupportedByDevice =
                mSystemSettingsUtil.isHapticPlaybackSupported(mContext);
    }

    @VisibleForTesting
    public void setBlockOnRingingFuture(CompletableFuture<Void> future) {
        mBlockOnRingingFuture = future;
    }

    public boolean startPlayCrs(Call foregroundCall, boolean isHfpDeviceAttached) {
        if (foregroundCall == null) {
            Log.wtf(this, "startRinging called with null foreground call.");
            return false;
        }

        boolean isCrsCall = foregroundCall.isCrsCall();
        Log.i(this, "startPlayCrs called with video CRS is :: " + isCrsCall);
        if (!isCrsCall) {
            return false;
        }

        if (foregroundCall.getState() != CallState.RINGING
                && foregroundCall.getState() != CallState.SIMULATED_RINGING) {
            // Its possible for bluetooth to connect JUST as a call goes active, which would mean
            // the call would start ringing again.
            Log.i(this, "startRinging called for non-ringing foreground callid=%s",
                    foregroundCall.getId());
            return false;
        }

        AudioManager audioManager =
                mContext.getSystemService(AudioManager.class);
        LogUtils.EventTimer timer = new EventTimer();
        boolean isVolumeOverZero = audioManager.getStreamVolume(AudioManager.STREAM_RING) > 0;
        timer.record("isVolumeOverZero");
        boolean shouldRingForContact = shouldRingForContact(foregroundCall.getContactUri());
        timer.record("shouldRingForContact");
        boolean isSelfManaged = foregroundCall.isSelfManaged();
        timer.record("isSelfManaged");
        boolean isSilentRingingRequested = foregroundCall.isSilentRingingRequested();
        timer.record("isSilentRingRequested");

        boolean isRingerAudible = isVolumeOverZero && shouldRingForContact && isCrsCall;
        timer.record("isRingerAudible");
        boolean hasExternalRinger = hasExternalRinger(foregroundCall);
        timer.record("hasExternalRinger");
        // Don't do call waiting operations or vibration unless these are false.
        boolean isTheaterModeOn = mSystemSettingsUtil.isTheaterModeOn(mContext);
        timer.record("isTheaterModeOn");
        boolean letDialerHandleRinging = mInCallController.doesConnectedDialerSupportRinging();
        timer.record("letDialerHandleRinging");

        Log.i(this, "startRinging timings: " + timer);
        boolean endEarly = isTheaterModeOn || letDialerHandleRinging || isSelfManaged ||
                hasExternalRinger || isSilentRingingRequested;

        // Acquire audio focus under any of the following conditions:
        // 1. Should ring for contact and there's an HFP device attached
        // 2. Volume is over zero, we should ring for the contact, and there's a audible ringtone
        //    present, here is CRS from network.
        // 3. The call is self-managed.
        boolean shouldAcquireAudioFocus =
                isRingerAudible || (isHfpDeviceAttached && shouldRingForContact) || isSelfManaged;

        if (endEarly) {
            if (letDialerHandleRinging) {
                Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Dialer handles");
            }
            if (isSilentRingingRequested) {
                Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Silent ringing "
                        + "requested");
            }
            Log.i(this, "Ending early -- isTheaterModeOn=%s, letDialerHandleRinging=%s, " +
                            "isSelfManaged=%s, hasExternalRinger=%s, silentRingingRequested=%s",
                    isTheaterModeOn, letDialerHandleRinging, isSelfManaged, hasExternalRinger,
                    isSilentRingingRequested);
            if (mBlockOnRingingFuture != null) {
                mBlockOnRingingFuture.complete(null);
            }
            return shouldAcquireAudioFocus;
        }

        stopCallWaiting();
        VibrationEffect effect;
        // Determine if the settings and DND mode indicate that the vibrator can be used right now.
        boolean isVibratorEnabled = isVibratorEnabled(mContext, foregroundCall);
        if (isRingerAudible) {
            mRingingCall = foregroundCall;
            Log.addEvent(foregroundCall, LogUtils.Events.START_RINGER);
            // Because we wait until a contact info query to complete before processing a
            // call (for the purposes of direct-to-voicemail), the information about custom
            // ringtones should be available by the time this code executes. We can safely
            // request the custom ringtone from the call and expect it to be current.
            if (mSystemSettingsUtil.applyRampingRinger(mContext)) {
                Log.i(this, "start ramping ringer.");
                if (mSystemSettingsUtil.enableAudioCoupledVibrationForRampingRinger()) {
                    effect = getVibrationEffectForCall(mRingtoneFactory, foregroundCall);
                } else {
                    effect = mDefaultVibrationEffect;
                }
            } else {
                effect = getVibrationEffectForCall(mRingtoneFactory, foregroundCall);
            }
        } else {
            String reason = String.format(
                    "isVolumeOverZero=%s, shouldRingForContact=%s, isCrsCall=%s",
                    isVolumeOverZero, shouldRingForContact, isCrsCall);
            Log.i(this, "startRinging: skipping because ringer would not be audible. " + reason);
            Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Inaudible: " + reason);
            effect = mDefaultVibrationEffect;
        }

        Log.i(this, "isHfpDeviceAttached=%s, isVibratorEnabled=%s, isRingerAudible=%s, ",
                isHfpDeviceAttached, isVibratorEnabled, isRingerAudible);
        int ringVolumeLevel = audioManager.getStreamVolume(AudioManager.STREAM_RING);
        if (ringVolumeLevel != 0 && isRingerAudible) {
            Log.i(this, "Start play CRS with volume :: " + ringVolumeLevel);
            // Set the CRS volume with local ring volume  and save the old volume setting.
            mSavedInCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
            final IntentFilter filter = new IntentFilter();
            filter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
            mContext.registerReceiver(mVolumeReceiver, filter);
            audioManager.setStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    convertVolumeLevelFromRingToCrs(ringVolumeLevel), 0);
        }
        if (mBlockOnRingingFuture != null) {
            mBlockOnRingingFuture.complete(null);
        }
        maybeStartVibration(foregroundCall, shouldRingForContact,
                effect, isVibratorEnabled, isRingerAudible);

        return shouldAcquireAudioFocus;
    }

    private static int convertVolumeLevelFromRingToCrs(int ringVolume) {
        //CRS volume is same as call volume, MinVolume is 1 and MaxVolume 5.
        //The range of local ring volume is from 0 to 7, telephony needs to align
        //volume level between local ring and CRS.
        //local ring level <---> CRS volume level
        // 7/6             <--->      5
        // 5/4             <--->      4
        // 3               <--->      3
        // 2               <--->      2
        // 1               <--->      1
        // 0               <--->  silence CRS
        final int upperBound  = 5;
        final int middleBound = 4;
        if (ringVolume < middleBound) { // Linear mapping for lower bound.
            return ringVolume;
        } else if (ringVolume > upperBound  ) {  // Saturating mapping upper bound.
            return upperBound;
        } else {
            return middleBound;
        }
    }

    public boolean startRinging(Call foregroundCall, boolean isHfpDeviceAttached) {
        if (foregroundCall == null) {
            Log.wtf(this, "startRinging called with null foreground call.");
            return false;
        }

        if (foregroundCall.getState() != CallState.RINGING
                && foregroundCall.getState() != CallState.SIMULATED_RINGING) {
            // Its possible for bluetooth to connect JUST as a call goes active, which would mean
            // the call would start ringing again.
            Log.i(this, "startRinging called for non-ringing foreground callid=%s",
                    foregroundCall.getId());
            return false;
        }

        AudioManager audioManager =
                (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        LogUtils.EventTimer timer = new EventTimer();
        boolean isVolumeOverZero = audioManager.getStreamVolume(AudioManager.STREAM_RING) > 0;
        timer.record("isVolumeOverZero");
        boolean shouldRingForContact = shouldRingForContact(foregroundCall.getContactUri());
        timer.record("shouldRingForContact");
        boolean isRingtonePresent = !(mRingtoneFactory.getRingtone(foregroundCall) == null);
        timer.record("getRingtone");
        boolean isSelfManaged = foregroundCall.isSelfManaged();
        timer.record("isSelfManaged");
        boolean isSilentRingingRequested = foregroundCall.isSilentRingingRequested();
        timer.record("isSilentRingRequested");

        boolean isRingerAudible = isVolumeOverZero && shouldRingForContact && isRingtonePresent;
        timer.record("isRingerAudible");
        boolean hasExternalRinger = hasExternalRinger(foregroundCall);
        timer.record("hasExternalRinger");
        // Don't do call waiting operations or vibration unless these are false.
        boolean isTheaterModeOn = mSystemSettingsUtil.isTheaterModeOn(mContext);
        timer.record("isTheaterModeOn");
        boolean letDialerHandleRinging = mInCallController.doesConnectedDialerSupportRinging();
        timer.record("letDialerHandleRinging");

        Log.i(this, "startRinging timings: " + timer);
        boolean endEarly = isTheaterModeOn || letDialerHandleRinging || isSelfManaged ||
                hasExternalRinger || isSilentRingingRequested;

        // Acquire audio focus under any of the following conditions:
        // 1. Should ring for contact and there's an HFP device attached
        // 2. Volume is over zero, we should ring for the contact, and there's a audible ringtone
        //    present.
        // 3. The call is self-managed.
        boolean shouldAcquireAudioFocus =
                isRingerAudible || (isHfpDeviceAttached && shouldRingForContact) || isSelfManaged;

        if (endEarly) {
            if (letDialerHandleRinging) {
                Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Dialer handles");
            }
            if (isSilentRingingRequested) {
                Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Silent ringing "
                        + "requested");
            }
            Log.i(this, "Ending early -- isTheaterModeOn=%s, letDialerHandleRinging=%s, " +
                            "isSelfManaged=%s, hasExternalRinger=%s, silentRingingRequested=%s",
                    isTheaterModeOn, letDialerHandleRinging, isSelfManaged, hasExternalRinger,
                    isSilentRingingRequested);
            if (mBlockOnRingingFuture != null) {
                mBlockOnRingingFuture.complete(null);
            }
            return shouldAcquireAudioFocus;
        }

        stopCallWaiting();

        VibrationEffect effect;
        CompletableFuture<Boolean> hapticsFuture = null;
        // Determine if the settings and DND mode indicate that the vibrator can be used right now.
        boolean isVibratorEnabled = isVibratorEnabled(mContext, foregroundCall);
        if (isRingerAudible) {
            mRingingCall = foregroundCall;
            Log.addEvent(foregroundCall, LogUtils.Events.START_RINGER);
            // Because we wait until a contact info query to complete before processing a
            // call (for the purposes of direct-to-voicemail), the information about custom
            // ringtones should be available by the time this code executes. We can safely
            // request the custom ringtone from the call and expect it to be current.
            if (mSystemSettingsUtil.applyRampingRinger(mContext)) {
                Log.i(this, "start ramping ringer.");
                if (mSystemSettingsUtil.enableAudioCoupledVibrationForRampingRinger()) {
                    effect = getVibrationEffectForCall(mRingtoneFactory, foregroundCall);
                } else {
                    effect = mDefaultVibrationEffect;
                }
                if (mVolumeShaperConfig == null) {
                    float silencePoint = (float) (RAMPING_RINGER_VIBRATION_DURATION)
                        / (float) (RAMPING_RINGER_VIBRATION_DURATION + RAMPING_RINGER_DURATION);
                    mVolumeShaperConfig = new VolumeShaper.Configuration.Builder()
                        .setDuration(RAMPING_RINGER_VIBRATION_DURATION + RAMPING_RINGER_DURATION)
                        .setCurve(new float[] {0.f, silencePoint + EPSILON /*keep monotonicity*/,
                            1.f}, new float[] {0.f, 0.f, 1.f})
                        .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                        .build();
                }
                hapticsFuture = mRingtonePlayer.play(mRingtoneFactory, foregroundCall,
                        mVolumeShaperConfig, isVibratorEnabled);
            } else {
                final ContentResolver cr = mContext.getContentResolver();
                if (Settings.System.getInt(cr,
                        Settings.System.INCREASING_RING, 0) != 0) {
                    float startVolume = Settings.System.getFloat(cr,
                            Settings.System.INCREASING_RING_START_VOLUME, 0.1f);
                    int rampUpTime = Settings.System.getInt(cr,
                            Settings.System.INCREASING_RING_RAMP_UP_TIME, 20);
                    if (mVolumeShaperConfig == null
                        || mRampingRingerDuration != rampUpTime
                        || mRampingRingerStartVolume != startVolume) {
                        mVolumeShaperConfig = new VolumeShaper.Configuration.Builder()
                            .setDuration(rampUpTime * 1000)
                            .setCurve(new float[] {0.f, 1.f}, new float[] {startVolume, 1.f})
                            .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                            .build();
                        mRampingRingerDuration = rampUpTime;
                        mRampingRingerStartVolume = startVolume;
                    }
                } else {
                    mVolumeShaperConfig = null;
                }
                hapticsFuture = mRingtonePlayer.play(mRingtoneFactory, foregroundCall,
                        mVolumeShaperConfig, isVibratorEnabled);
                effect = getVibrationEffectForCall(mRingtoneFactory, foregroundCall);

            }
        } else {
            String reason = String.format(
                    "isVolumeOverZero=%s, shouldRingForContact=%s, isRingtonePresent=%s",
                    isVolumeOverZero, shouldRingForContact, isRingtonePresent);
            Log.i(this, "startRinging: skipping because ringer would not be audible. " + reason);
            Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Inaudible: " + reason);
            effect = mDefaultVibrationEffect;
        }

        if (hapticsFuture != null) {
            mVibrateFuture = hapticsFuture.thenAccept(isUsingAudioCoupledHaptics -> {
                if (!isUsingAudioCoupledHaptics || !mIsHapticPlaybackSupportedByDevice) {
                    Log.i(this, "startRinging: fileHasHaptics=%b, hapticsSupported=%b",
                            isUsingAudioCoupledHaptics, mIsHapticPlaybackSupportedByDevice);
                    maybeStartVibration(foregroundCall, shouldRingForContact, effect,
                            isVibratorEnabled, isRingerAudible);
                } else if (mSystemSettingsUtil.applyRampingRinger(mContext)
                           && !mSystemSettingsUtil.enableAudioCoupledVibrationForRampingRinger()) {
                    Log.i(this, "startRinging: apply ramping ringer vibration");
                    maybeStartVibration(foregroundCall, shouldRingForContact, effect,
                            isVibratorEnabled, isRingerAudible);
                } else {
                    Log.addEvent(foregroundCall, LogUtils.Events.SKIP_VIBRATION,
                            "using audio-coupled haptics");
                }
            });
            if (mBlockOnRingingFuture != null) {
                mVibrateFuture.whenComplete((v, e) -> mBlockOnRingingFuture.complete(null));
            }
        } else {
            if (mBlockOnRingingFuture != null) {
                mBlockOnRingingFuture.complete(null);
            }
            Log.w(this, "startRinging: No haptics future; fallback to default behavior");
            maybeStartVibration(foregroundCall, shouldRingForContact, effect, isVibratorEnabled,
                    isRingerAudible);
        }

        if (!mIsFlash && Settings.System.getIntForUser(mContext.getContentResolver(),  Settings.System.FLASH_ON_CALL_WAITING, 0, UserHandle.USER_CURRENT) == 1) {
            SyberiaUtils.toggleCameraFlashOn();
            mIsFlash = true;
        }

        return shouldAcquireAudioFocus;
    }

    private void maybeStartVibration(Call foregroundCall, boolean shouldRingForContact,
        VibrationEffect effect, boolean isVibrationEnabled, boolean isRingerAudible) {
        if (isVibrationEnabled
                && !mIsVibrating && shouldRingForContact) {
            if (mSystemSettingsUtil.applyRampingRinger(mContext)
                    && isRingerAudible) {
                Log.i(this, "start vibration for ramping ringer.");
                mIsVibrating = true;
                mVibrator.vibrate(effect, VIBRATION_ATTRIBUTES);
            } else {
                Log.i(this, "start normal vibration.");
                mIsVibrating = true;
                mVibrator.vibrate(effect, VIBRATION_ATTRIBUTES);
            }
        } else if (mIsVibrating) {
            Log.addEvent(foregroundCall, LogUtils.Events.SKIP_VIBRATION, "already vibrating");
        }
    }

    private VibrationEffect getVibrationEffectForCall(RingtoneFactory factory, Call call) {
        VibrationEffect effect = null;
        Ringtone ringtone = factory.getRingtone(call);
        Uri ringtoneUri = ringtone != null ? ringtone.getUri() : null;
        if (ringtoneUri != null) {
            try {
                effect = mVibrationEffectProxy.get(ringtoneUri, mContext);
            } catch (IllegalArgumentException iae) {
                // Deep in the bowels of the VibrationEffect class it is possible for an
                // IllegalArgumentException to be thrown if there is an invalid URI specified in the
                // device config, or a content provider failure.  Rather than crashing the Telecom
                // process we will just use the default vibration effect.
                Log.e(this, iae, "getVibrationEffectForCall: failed to get vibration effect");
                effect = null;
            }
        }

        if (effect == null) {
            effect = mDefaultVibrationEffect;
        }
        return effect;
    }

    public void startCallWaiting(Call call) {
        startCallWaiting(call, null);
    }

    public void startCallWaiting(Call call, String reason) {
        if (mSystemSettingsUtil.isTheaterModeOn(mContext)) {
            return;
        }

        if (mInCallController.doesConnectedDialerSupportRinging()) {
            Log.addEvent(call, LogUtils.Events.SKIP_RINGING, "Dialer handles");
            return;
        }

        if (call.isSelfManaged()) {
            Log.addEvent(call, LogUtils.Events.SKIP_RINGING, "Self-managed");
            return;
        }

        Log.v(this, "Playing call-waiting tone.");

        stopRinging();

        if (Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.INCALL_FEEDBACK_VIBRATE, 0, UserHandle.USER_CURRENT) == 1) {
            if (mVibrator.hasVibrator()) {
                mVibrator.vibrate(VibrationEffect.get(VibrationEffect.EFFECT_THUD));
            }
        }

        if (mCallWaitingPlayer == null) {
            Log.addEvent(call, LogUtils.Events.START_CALL_WAITING_TONE, reason);
            mCallWaitingCall = call;
            mCallWaitingPlayer =
                    mPlayerFactory.createPlayer(InCallTonePlayer.TONE_CALL_WAITING);
            mCallWaitingPlayer.startTone();
        }
    }

    private void stopPlayCrs() {
        if (mRingingCall != null) {
            Log.addEvent(mRingingCall, LogUtils.Events.STOP_RINGER);
            AudioManager audioManager =  mContext.getSystemService(AudioManager.class);
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, mSavedInCallVolume, 0);
            mContext.unregisterReceiver(mVolumeReceiver);
            mSavedInCallVolume = 0;
            mRingingCall = null;
        }
        // If we haven't started vibrating because we were waiting for the haptics info, cancel
        // it and don't vibrate at all.
        if (mVibrateFuture != null) {
            mVibrateFuture.cancel(true);
        }

        if (mIsVibrating) {
            Log.addEvent(mVibratingCall, LogUtils.Events.STOP_VIBRATOR);
            mVibrator.cancel();
            mIsVibrating = false;
            mVibratingCall = null;
        }
    }

    public void stopRinging() {
        if (mRingingCall != null && mRingingCall.isCrsCall()) {
            stopPlayCrs();
            return;
        }

        if (mRingingCall != null) {
            Log.addEvent(mRingingCall, LogUtils.Events.STOP_RINGER);
            mRingingCall = null;
        }

        mRingtonePlayer.stop();

        // If we haven't started vibrating because we were waiting for the haptics info, cancel
        // it and don't vibrate at all.
        if (mVibrateFuture != null) {
            mVibrateFuture.cancel(true);
        }

        if (mIsVibrating) {
            Log.addEvent(mVibratingCall, LogUtils.Events.STOP_VIBRATOR);
            mVibrator.cancel();
            mIsVibrating = false;
            mVibratingCall = null;
        }

        if (mIsFlash && Settings.System.getIntForUser(mContext.getContentResolver(), Settings.System.FLASH_ON_CALL_WAITING, 0, UserHandle.USER_CURRENT) == 1) {
            SyberiaUtils.toggleCameraFlashOff();
            mIsFlash = false;
        }
    }

    public void stopCallWaiting() {
        Log.v(this, "stop call waiting.");
        if (mCallWaitingPlayer != null) {
            if (mCallWaitingCall != null) {
                Log.addEvent(mCallWaitingCall, LogUtils.Events.STOP_CALL_WAITING_TONE);
                mCallWaitingCall = null;
            }

            mCallWaitingPlayer.stopTone();
            mCallWaitingPlayer = null;
        }
    }

    public boolean isRinging() {
        return mRingtonePlayer.isPlaying();
    }

    private boolean shouldRingForContact(Uri contactUri) {
        final NotificationManager manager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        final Bundle peopleExtras = new Bundle();
        if (contactUri != null) {
            ArrayList<Person> personList = new ArrayList<>();
            personList.add(new Person.Builder().setUri(contactUri.toString()).build());
            peopleExtras.putParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, personList);
        }
        return manager.matchesCallFilter(peopleExtras);
    }

    private boolean hasExternalRinger(Call foregroundCall) {
        Bundle intentExtras = foregroundCall.getIntentExtras();
        if (intentExtras != null) {
            return intentExtras.getBoolean(TelecomManager.EXTRA_CALL_EXTERNAL_RINGER, false);
        } else {
            return false;
        }
    }

    private boolean isVibratorEnabled(Context context, Call call) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager.getRingerModeInternal();
        boolean shouldVibrate;
        if (getVibrateWhenRinging(context)) {
            shouldVibrate = ringerMode != AudioManager.RINGER_MODE_SILENT;
        } else {
            shouldVibrate = ringerMode == AudioManager.RINGER_MODE_VIBRATE;
        }

        // Technically this should be in the calling method, but it seemed a little odd to pass
        // around a whole bunch of state just for logging purposes.
        if (shouldVibrate) {
            Log.addEvent(call, LogUtils.Events.START_VIBRATOR,
                    "hasVibrator=%b, userRequestsVibrate=%b, ringerMode=%d, isVibrating=%b",
                    mVibrator.hasVibrator(), mSystemSettingsUtil.canVibrateWhenRinging(context),
                    ringerMode, mIsVibrating);
        } else {
            Log.addEvent(call, LogUtils.Events.SKIP_VIBRATION,
                    "hasVibrator=%b, userRequestsVibrate=%b, ringerMode=%d, isVibrating=%b",
                    mVibrator.hasVibrator(), mSystemSettingsUtil.canVibrateWhenRinging(context),
                    ringerMode, mIsVibrating);
        }

        return shouldVibrate;
    }

    private boolean getVibrateWhenRinging(Context context) {
        if (!mVibrator.hasVibrator()) {
            return false;
        }
        return mSystemSettingsUtil.canVibrateWhenRinging(context)
            || mSystemSettingsUtil.applyRampingRinger(context);
    }

    private void updateVibrationPattern() {
        mVibrationPattern = Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.RINGTONE_VIBRATION_PATTERN, 0, UserHandle.USER_CURRENT);
        if (mUseSimplePattern) {
            switch (mVibrationPattern) {
                case 1:
                    mDefaultVibrationEffect = mVibrationEffectProxy.createWaveform(DZZZ_DA_VIBRATION_PATTERN,
                        FIVE_ELEMENTS_VIBRATION_AMPLITUDE, REPEAT_SIMPLE_VIBRATION_AT);
                    break;
                case 2:
                    mDefaultVibrationEffect = mVibrationEffectProxy.createWaveform(MM_MM_MM_VIBRATION_PATTERN,
                        SEVEN_ELEMENTS_VIBRATION_AMPLITUDE, REPEAT_SIMPLE_VIBRATION_AT);
                    break;
                case 3:
                    mDefaultVibrationEffect = mVibrationEffectProxy.createWaveform(DA_DA_DZZZ_VIBRATION_PATTERN,
                        SEVEN_ELEMENTS_VIBRATION_AMPLITUDE, REPEAT_SIMPLE_VIBRATION_AT);
                    break;
                case 4:
                    mDefaultVibrationEffect = mVibrationEffectProxy.createWaveform(DA_DZZZ_DA_VIBRATION_PATTERN,
                        SEVEN_ELEMENTS_VIBRATION_AMPLITUDE, REPEAT_SIMPLE_VIBRATION_AT);
                    break;
                case 5:
                    String customVibValue = Settings.System.getStringForUser(
                            mContext.getContentResolver(),
                            Settings.System.CUSTOM_RINGTONE_VIBRATION_PATTERN,
                            UserHandle.USER_CURRENT);
                    String[] customVib = new String[3];
                    if (customVibValue != null && !customVibValue.equals("")) {
                        customVib = customVibValue.split(",", 3);
                    }
                    else { // If no value - use default
                        customVib[0] = "0";
                        customVib[1] = "800";
                        customVib[2] = "800";
                    }
                    long[] vibPattern = {
                        0, // No delay before starting
                        Long.parseLong(customVib[0]), // How long to vibrate
                        400, // Delay
                        Long.parseLong(customVib[1]), // How long to vibrate
                        400, // Delay
                        Long.parseLong(customVib[2]), // How long to vibrate
                        400, // How long to wait before vibrating again
                    };
                    mDefaultVibrationEffect = mVibrationEffectProxy.createWaveform(vibPattern,
                            SEVEN_ELEMENTS_VIBRATION_AMPLITUDE, REPEAT_SIMPLE_VIBRATION_AT);
                    break;
                default:
                    mDefaultVibrationEffect = mVibrationEffectProxy.createWaveform(SIMPLE_VIBRATION_PATTERN,
                        SIMPLE_VIBRATION_AMPLITUDE, REPEAT_SIMPLE_VIBRATION_AT);
                    break;
            }
        } else {
            mDefaultVibrationEffect = mVibrationEffectProxy.createWaveform(PULSE_PATTERN,
                    PULSE_AMPLITUDE, REPEAT_VIBRATION_AT);
        }
    }

    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean SelfChange) {
            updateVibrationPattern();
        }
    }

    public void startVibratingForOutgoingCallActive() {
        if (!mIsVibrating
                && Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.VIBRATING_FOR_OUTGOING_CALL_ACCEPTED, 1) == 1) {
            mIsVibrating = true;
            java.util.concurrent.Executors.defaultThreadFactory().newThread(() -> {
                final VibrationEffect vibrationEffect =
                        mVibrationEffectProxy.createWaveform(SIMPLE_VIBRATION_PATTERN,
                        SIMPLE_VIBRATION_AMPLITUDE, REPEAT_SIMPLE_VIBRATION_AT);
                final AudioAttributes vibrationAttributes = new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .build();
                mVibrator.vibrate(vibrationEffect, vibrationAttributes);
                android.os.SystemClock.sleep(OUTGOING_CALL_VIBRATING_DURATION);
                mVibrator.cancel();
                mIsVibrating = false;
            }).start();
        }
    }
}
