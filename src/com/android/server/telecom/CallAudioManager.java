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

import android.content.Context;
import android.annotation.NonNull;
import android.media.IAudioService;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telecom.CallAudioState;
import android.telecom.Log;
import android.telecom.VideoProfile;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.telecom.CallAudioModeStateMachine.MessageArgs.Builder;
import com.android.server.telecom.bluetooth.BluetoothStateReceiver;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashSet;

public class CallAudioManager extends CallsManagerListenerBase {

    public interface AudioServiceFactory {
        IAudioService getAudioService();
    }

    private final String LOG_TAG = CallAudioManager.class.getSimpleName();

    private final LinkedHashSet<Call> mActiveDialingOrConnectingCalls;
    private final LinkedHashSet<Call> mRingingCalls;
    private final LinkedHashSet<Call> mHoldingCalls;
    private final LinkedHashSet<Call> mAudioProcessingCalls;
    private final Set<Call> mCalls;
    private final SparseArray<LinkedHashSet<Call>> mCallStateToCalls;

    private final CallAudioRouteStateMachine mCallAudioRouteStateMachine;
    private final CallAudioModeStateMachine mCallAudioModeStateMachine;
    private final BluetoothStateReceiver mBluetoothStateReceiver;
    private final CallsManager mCallsManager;
    private final InCallTonePlayer.Factory mPlayerFactory;
    private final Ringer mRinger;
    private final RingbackPlayer mRingbackPlayer;
    private final DtmfLocalTonePlayer mDtmfLocalTonePlayer;

    private Call mForegroundCall;
    private boolean mIsTonePlaying = false;
    private boolean mIsDisconnectedTonePlaying = false;
    private InCallTonePlayer mHoldTonePlayer;
    private boolean mIsInCrsMode = false;
    private int mOriginalCallType = Call.CALL_TYPE_UNKNOWN;

    public CallAudioManager(CallAudioRouteStateMachine callAudioRouteStateMachine,
            CallsManager callsManager,
            CallAudioModeStateMachine callAudioModeStateMachine,
            InCallTonePlayer.Factory playerFactory,
            Ringer ringer,
            RingbackPlayer ringbackPlayer,
            BluetoothStateReceiver bluetoothStateReceiver,
            DtmfLocalTonePlayer dtmfLocalTonePlayer) {
        mActiveDialingOrConnectingCalls = new LinkedHashSet<>(1);
        mRingingCalls = new LinkedHashSet<>(1);
        mHoldingCalls = new LinkedHashSet<>(1);
        mAudioProcessingCalls = new LinkedHashSet<>(1);
        mCalls = new HashSet<>();
        mCallStateToCalls = new SparseArray<LinkedHashSet<Call>>() {{
            put(CallState.CONNECTING, mActiveDialingOrConnectingCalls);
            put(CallState.ACTIVE, mActiveDialingOrConnectingCalls);
            put(CallState.DIALING, mActiveDialingOrConnectingCalls);
            put(CallState.PULLING, mActiveDialingOrConnectingCalls);
            put(CallState.RINGING, mRingingCalls);
            put(CallState.ON_HOLD, mHoldingCalls);
            put(CallState.SIMULATED_RINGING, mRingingCalls);
            put(CallState.AUDIO_PROCESSING, mAudioProcessingCalls);
        }};

        mCallAudioRouteStateMachine = callAudioRouteStateMachine;
        mCallAudioModeStateMachine = callAudioModeStateMachine;
        mCallsManager = callsManager;
        mPlayerFactory = playerFactory;
        mRinger = ringer;
        mRingbackPlayer = ringbackPlayer;
        mBluetoothStateReceiver = bluetoothStateReceiver;
        mDtmfLocalTonePlayer = dtmfLocalTonePlayer;

        mPlayerFactory.setCallAudioManager(this);
        mCallAudioModeStateMachine.setCallAudioManager(this);
        mCallAudioRouteStateMachine.setCallAudioManager(this);
    }

    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        if (shouldIgnoreCallForAudio(call)) {
            // No audio management for calls in a conference, or external calls.
            return;
        }
        Log.d(LOG_TAG, "Call state changed for TC@%s: %s -> %s", call.getId(),
                CallState.toString(oldState), CallState.toString(newState));

        removeCallFromAllBins(call);
        HashSet<Call> newBinForCall = getBinForCall(call);
        if (newBinForCall != null) {
            newBinForCall.add(call);
        }
        sendCallStatusToBluetoothStateReceiver();

        updateForegroundCall();
        if (shouldPlayDisconnectTone(oldState, newState)) {
            playToneForDisconnectedCall(call);
        }

        if (newState == CallState.ACTIVE && oldState == CallState.DIALING) {
            playToneAfterCallConnected(call);
        }
        //reset CRS mode once call state changed.
        if (mIsInCrsMode && (newState != CallState.RINGING)) {
            mIsInCrsMode = false;
            if ((mOriginalCallType == VideoProfile.STATE_AUDIO_ONLY)
                    && !mCallsManager.isWiredHandsetInOrBtAvailble()) {
                setAudioRoute(CallAudioState.ROUTE_EARPIECE, null);
            }
            mOriginalCallType = Call.CALL_TYPE_UNKNOWN;
        }
        onCallLeavingState(call, oldState);
        onCallEnteringState(call, newState);
    }

    @Override
    public void onCallAdded(Call call) {
        if (shouldIgnoreCallForAudio(call)) {
            return; // Don't do audio handling for calls in a conference, or external calls.
        }

        addCall(call);
    }

    @Override
    public void onCallRemoved(Call call) {
        if (shouldIgnoreCallForAudio(call)) {
            return; // Don't do audio handling for calls in a conference, or external calls.
        }

        removeCall(call);
    }

    private void addCall(Call call) {
        if (mCalls.contains(call)) {
            Log.w(LOG_TAG, "Call TC@%s is being added twice.", call.getId());
            return; // No guarantees that the same call won't get added twice.
        }

        Log.d(LOG_TAG, "Call added with id TC@%s in state %s", call.getId(),
                CallState.toString(call.getState()));

        HashSet<Call> newBinForCall = getBinForCall(call);
        if (newBinForCall != null) {
            newBinForCall.add(call);
        }
        updateForegroundCall();
        mCalls.add(call);
        sendCallStatusToBluetoothStateReceiver();

        onCallEnteringState(call, call.getState());
    }

    private void removeCall(Call call) {
        if (!mCalls.contains(call)) {
            return; // No guarantees that the same call won't get removed twice.
        }

        Log.d(LOG_TAG, "Call removed with id TC@%s in state %s", call.getId(),
                CallState.toString(call.getState()));

        removeCallFromAllBins(call);

        updateForegroundCall();
        mCalls.remove(call);
        sendCallStatusToBluetoothStateReceiver();

        onCallLeavingState(call, call.getState());
    }

    private void sendCallStatusToBluetoothStateReceiver() {
        // We're in a call if there are calls in mCalls that are not in mAudioProcessingCalls.
        boolean isInCall = !mAudioProcessingCalls.containsAll(mCalls);
        mBluetoothStateReceiver.setIsInCall(isInCall);
    }

    /**
     * Handles changes to the external state of a call.  External calls which become regular calls
     * should be tracked, and regular calls which become external should no longer be tracked.
     *
     * @param call The call.
     * @param isExternalCall {@code True} if the call is now external, {@code false} if it is now
     *      a regular call.
     */
    @Override
    public void onExternalCallChanged(Call call, boolean isExternalCall) {
        if (isExternalCall) {
            Log.d(LOG_TAG, "Removing call which became external ID %s", call.getId());
            removeCall(call);
        } else if (!isExternalCall) {
            Log.d(LOG_TAG, "Adding external call which was pulled with ID %s", call.getId());
            addCall(call);

            if (mCallsManager.isSpeakerphoneAutoEnabledForVideoCalls(call.getVideoState())) {
                // When pulling a video call, automatically enable the speakerphone.
                Log.d(LOG_TAG, "Switching to speaker because external video call %s was pulled." +
                        call.getId());
                mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                        CallAudioRouteStateMachine.SWITCH_SPEAKER);
            }
        }
    }

    /**
     * Determines if {@link CallAudioManager} should do any audio routing operations for a call.
     * We ignore child calls of a conference and external calls for audio routing purposes.
     *
     * @param call The call to check.
     * @return {@code true} if the call should be ignored for audio routing, {@code false}
     * otherwise
     */
    private boolean shouldIgnoreCallForAudio(Call call) {
        return call.getParentCall() != null || call.isExternalCall();
    }

    @Override
    public void onIncomingCallAnswered(Call call) {
        if (!mCalls.contains(call)) {
            return;
        }

        // Turn off mute when a new incoming call is answered iff it's not a handover.
        if (!call.isHandoverInProgress()) {
            mute(false /* shouldMute */);
        }

        maybeStopRingingAndCallWaitingForAnsweredOrRejectedCall(call);
    }

    @Override
    public void onSessionModifyRequestReceived(Call call, VideoProfile videoProfile) {
        if (videoProfile == null) {
            return;
        }

        if (call != mForegroundCall) {
            // We only play tones for foreground calls.
            return;
        }

        int previousVideoState = call.getVideoState();
        int newVideoState = videoProfile.getVideoState();
        Log.v(this, "onSessionModifyRequestReceived : videoProfile = " + VideoProfile
                .videoStateToString(newVideoState));

        boolean isUpgradeRequest = !VideoProfile.isReceptionEnabled(previousVideoState) &&
                VideoProfile.isReceptionEnabled(newVideoState);

        if (isUpgradeRequest) {
            mPlayerFactory.createPlayer(InCallTonePlayer.TONE_VIDEO_UPGRADE).startTone();
        }
    }

    /**
     * Play or stop a call hold tone for a call.  Triggered via
     * {@link Connection#sendConnectionEvent(String)} when the
     * {@link Connection#EVENT_ON_HOLD_TONE_START} event or
     * {@link Connection#EVENT_ON_HOLD_TONE_STOP} event is passed through to the
     *
     * @param call The call which requested the hold tone.
     */
    @Override
    public void onHoldToneRequested(Call call) {
        maybePlayHoldTone();
    }

    @Override
    public void onIsVoipAudioModeChanged(Call call) {
        if (call != mForegroundCall) {
            return;
        }
        mCallAudioModeStateMachine.sendMessageWithArgs(
                CallAudioModeStateMachine.FOREGROUND_VOIP_MODE_CHANGE,
                makeArgsForModeStateMachine());
    }

    @Override
    public void onRingbackRequested(Call call, boolean shouldRingback) {
        if (call == mForegroundCall && shouldRingback) {
            mRingbackPlayer.startRingbackForCall(call);
        } else {
            mRingbackPlayer.stopRingbackForCall(call);
        }
    }

    @Override
    public void onIncomingCallRejected(Call call, boolean rejectWithMessage, String message) {
        maybeStopRingingAndCallWaitingForAnsweredOrRejectedCall(call);
    }

    @Override
    public void onIsConferencedChanged(Call call) {
        // This indicates a conferencing change, which shouldn't impact any audio mode stuff.
        Call parentCall = call.getParentCall();
        if (parentCall == null) {
            // Indicates that the call should be tracked for audio purposes. Treat it as if it were
            // just added.
            Log.i(LOG_TAG, "Call TC@" + call.getId() + " left conference and will" +
                            " now be tracked by CallAudioManager.");
            onCallAdded(call);
        } else {
            // The call joined a conference, so stop tracking it.
            removeCallFromAllBins(call);
            updateForegroundCall();
            mCalls.remove(call);
        }
    }

    @Override
    public void onConnectionServiceChanged(Call call, ConnectionServiceWrapper oldCs,
            ConnectionServiceWrapper newCs) {
        mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                CallAudioRouteStateMachine.UPDATE_SYSTEM_AUDIO_ROUTE);
    }

    @Override
    public void onVideoStateChanged(Call call, int previousVideoState, int newVideoState) {
        if (call != getForegroundCall()) {
            Log.d(LOG_TAG, "Ignoring video state change from %s to %s for call %s -- not " +
                    "foreground.", VideoProfile.videoStateToString(previousVideoState),
                    VideoProfile.videoStateToString(newVideoState), call.getId());
            return;
        }

        if (!VideoProfile.isVideo(previousVideoState) &&
                mCallsManager.isSpeakerphoneAutoEnabledForVideoCalls(newVideoState)) {
            Log.d(LOG_TAG, "Switching to speaker because call %s transitioned video state from %s" +
                    " to %s", call.getId(), VideoProfile.videoStateToString(previousVideoState),
                    VideoProfile.videoStateToString(newVideoState));
            mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                    CallAudioRouteStateMachine.SWITCH_SPEAKER);
        }
    }

    public CallAudioState getCallAudioState() {
        return mCallAudioRouteStateMachine.getCurrentCallAudioState();
    }

    public Call getPossiblyHeldForegroundCall() {
        return mForegroundCall;
    }

    public Call getForegroundCall() {
        if (mForegroundCall != null && mForegroundCall.getState() != CallState.ON_HOLD) {
            return mForegroundCall;
        }
        return null;
    }

    @VisibleForTesting
    public void toggleMute() {
        // Don't mute if there are any emergency calls.
        if (mCallsManager.isInEmergencyCall()) {
            Log.v(this, "ignoring toggleMute for emergency call");
            return;
        }
        mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                CallAudioRouteStateMachine.TOGGLE_MUTE);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void onRingerModeChange() {
        mCallAudioModeStateMachine.sendMessageWithArgs(
                CallAudioModeStateMachine.RINGER_MODE_CHANGE, makeArgsForModeStateMachine());
    }

    @VisibleForTesting
    public void mute(boolean shouldMute) {
        Log.v(this, "mute, shouldMute: %b", shouldMute);

        // Don't mute if there are any emergency calls.
        if (mCallsManager.isInEmergencyCall()) {
            shouldMute = false;
            Log.v(this, "ignoring mute for emergency call");
        }

        mCallAudioRouteStateMachine.sendMessageWithSessionInfo(shouldMute
                ? CallAudioRouteStateMachine.MUTE_ON : CallAudioRouteStateMachine.MUTE_OFF);
    }

    /**
     * Changed the audio route, for example from earpiece to speaker phone.
     *
     * @param route The new audio route to use. See {@link CallAudioState}.
     * @param bluetoothAddress the address of the desired bluetooth device, if route is
     * {@link CallAudioState#ROUTE_BLUETOOTH}.
     */
    void setAudioRoute(int route, String bluetoothAddress) {
        Log.v(this, "setAudioRoute, route: %s", CallAudioState.audioRouteToString(route));
        switch (route) {
            case CallAudioState.ROUTE_BLUETOOTH:
                mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                        CallAudioRouteStateMachine.USER_SWITCH_BLUETOOTH, 0, bluetoothAddress);
                return;
            case CallAudioState.ROUTE_SPEAKER:
                mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                        CallAudioRouteStateMachine.USER_SWITCH_SPEAKER);
                return;
            case CallAudioState.ROUTE_WIRED_HEADSET:
                mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                        CallAudioRouteStateMachine.USER_SWITCH_HEADSET);
                return;
            case CallAudioState.ROUTE_EARPIECE:
                mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                        CallAudioRouteStateMachine.USER_SWITCH_EARPIECE);
                return;
            case CallAudioState.ROUTE_WIRED_OR_EARPIECE:
                mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                        CallAudioRouteStateMachine.USER_SWITCH_BASELINE_ROUTE,
                        CallAudioRouteStateMachine.NO_INCLUDE_BLUETOOTH_IN_BASELINE);
                return;
            default:
                Log.w(this, "InCallService requested an invalid audio route: %d", route);
        }
    }

    /**
     * Switch call audio routing to the baseline route, including bluetooth headsets if there are
     * any connected.
     */
    void switchBaseline() {
        Log.i(this, "switchBaseline");
        mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                CallAudioRouteStateMachine.USER_SWITCH_BASELINE_ROUTE,
                CallAudioRouteStateMachine.INCLUDE_BLUETOOTH_IN_BASELINE);
    }

    void silenceRingers() {
        synchronized (mCallsManager.getLock()) {
            for (Call call : mRingingCalls) {
                call.silence();
            }

            if(mIsInCrsMode) {
                Log.i(this, "Fire silence CRS.");
                onCallSilenceCrs();
            }
            mRinger.stopRinging();
            mRinger.stopCallWaiting();
        }
    }

    public boolean isRingtonePlaying() {
        return mRinger.isRinging();
    }

    @VisibleForTesting
    public boolean startRinging() {
        synchronized (mCallsManager.getLock()) {
            if (mIsInCrsMode) {
                Log.i(this, "Start to play CRS.");
                return mRinger.startPlayCrs(mForegroundCall,
                        mCallAudioRouteStateMachine.isHfpDeviceAvailable());
            }
            return mRinger.startRinging(mForegroundCall,
                    mCallAudioRouteStateMachine.isHfpDeviceAvailable());
        }
    }

    @VisibleForTesting
    public void startCallWaiting(String reason) {
        synchronized (mCallsManager.getLock()) {
            if (mRingingCalls.size() == 1) {
                mRinger.startCallWaiting(mRingingCalls.iterator().next(), reason);
            }
        }
    }

    @VisibleForTesting
    public void stopRinging() {
        synchronized (mCallsManager.getLock()) {
            mRinger.stopRinging();
        }
    }

    @VisibleForTesting
    public void stopCallWaiting() {
        synchronized (mCallsManager.getLock()) {
            mRinger.stopCallWaiting();
        }
    }

    @VisibleForTesting
    public void setCallAudioRouteFocusState(int focusState) {
        mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                CallAudioRouteStateMachine.SWITCH_FOCUS, focusState);
    }

    public void notifyAudioOperationsComplete() {
        mCallAudioModeStateMachine.sendMessageWithArgs(
                CallAudioModeStateMachine.AUDIO_OPERATIONS_COMPLETE, makeArgsForModeStateMachine());
    }

    @VisibleForTesting
    public CallAudioRouteStateMachine getCallAudioRouteStateMachine() {
        return mCallAudioRouteStateMachine;
    }

    @VisibleForTesting
    public CallAudioModeStateMachine getCallAudioModeStateMachine() {
        return mCallAudioModeStateMachine;
    }

    void dump(IndentingPrintWriter pw) {
        pw.println("All calls:");
        pw.increaseIndent();
        dumpCallsInCollection(pw, mCalls);
        pw.decreaseIndent();

        pw.println("Active dialing, or connecting calls:");
        pw.increaseIndent();
        dumpCallsInCollection(pw, mActiveDialingOrConnectingCalls);
        pw.decreaseIndent();

        pw.println("Ringing calls:");
        pw.increaseIndent();
        dumpCallsInCollection(pw, mRingingCalls);
        pw.decreaseIndent();

        pw.println("Holding calls:");
        pw.increaseIndent();
        dumpCallsInCollection(pw, mHoldingCalls);
        pw.decreaseIndent();

        pw.println("Foreground call:");
        pw.println(mForegroundCall);

        pw.println("CallAudioModeStateMachine pending messages:");
        pw.increaseIndent();
        mCallAudioModeStateMachine.dumpPendingMessages(pw);
        pw.decreaseIndent();

        pw.println("CallAudioRouteStateMachine pending messages:");
        pw.increaseIndent();
        mCallAudioRouteStateMachine.dumpPendingMessages(pw);
        pw.decreaseIndent();

        pw.println("BluetoothDeviceManager:");
        pw.increaseIndent();
        if (mBluetoothStateReceiver.getBluetoothDeviceManager() != null) {
            mBluetoothStateReceiver.getBluetoothDeviceManager().dump(pw);
        }
        pw.decreaseIndent();
    }

    @VisibleForTesting
    public void setIsTonePlaying(boolean isTonePlaying) {
        mIsTonePlaying = isTonePlaying;
        mCallAudioModeStateMachine.sendMessageWithArgs(
                isTonePlaying ? CallAudioModeStateMachine.TONE_STARTED_PLAYING
                        : CallAudioModeStateMachine.TONE_STOPPED_PLAYING,
                makeArgsForModeStateMachine());

        if (!isTonePlaying && mIsDisconnectedTonePlaying) {
            mCallsManager.onDisconnectedTonePlaying(false);
            mIsDisconnectedTonePlaying = false;
        }
    }

    private void onCallLeavingState(Call call, int state) {
        switch (state) {
            case CallState.ACTIVE:
            case CallState.CONNECTING:
                onCallLeavingActiveDialingOrConnecting();
                break;
            case CallState.RINGING:
            case CallState.SIMULATED_RINGING:
            case CallState.ANSWERED:
                onCallLeavingRinging();
                break;
            case CallState.ON_HOLD:
                onCallLeavingHold();
                break;
            case CallState.PULLING:
                onCallLeavingActiveDialingOrConnecting();
                break;
            case CallState.DIALING:
                stopRingbackForCall(call);
                onCallLeavingActiveDialingOrConnecting();
                break;
            case CallState.AUDIO_PROCESSING:
                onCallLeavingAudioProcessing();
                break;
        }
    }

    private void onCallEnteringState(Call call, int state) {
        switch (state) {
            case CallState.ACTIVE:
            case CallState.CONNECTING:
                onCallEnteringActiveDialingOrConnecting();
                break;
            case CallState.RINGING:
            case CallState.SIMULATED_RINGING:
                mIsInCrsMode = call.isCrsCall();
                mOriginalCallType = call.getOriginalCallType();
                if(mIsInCrsMode &&
                        !mCallsManager.isWiredHandsetInOrBtAvailble()) {
                    Log.i(LOG_TAG, "set Audio Route to SPEAKER");
                    setAudioRoute(CallAudioState.ROUTE_SPEAKER, null);
                }
                onCallEnteringRinging();
                break;
            case CallState.ON_HOLD:
                onCallEnteringHold();
                break;
            case CallState.PULLING:
                onCallEnteringActiveDialingOrConnecting();
                break;
            case CallState.DIALING:
                onCallEnteringActiveDialingOrConnecting();
                playRingbackForCall(call);
                break;
            case CallState.ANSWERED:
                if (call.can(android.telecom.Call.Details.CAPABILITY_SPEED_UP_MT_AUDIO)) {
                    onCallEnteringActiveDialingOrConnecting();
                }
                break;
            case CallState.AUDIO_PROCESSING:
                onCallEnteringAudioProcessing();
                break;
        }
    }

    private void onCallLeavingAudioProcessing() {
        if (mAudioProcessingCalls.size() == 0) {
            mCallAudioModeStateMachine.sendMessageWithArgs(
                    CallAudioModeStateMachine.NO_MORE_AUDIO_PROCESSING_CALLS,
                    makeArgsForModeStateMachine());
        }
    }

    private void onCallEnteringAudioProcessing() {
        if (mAudioProcessingCalls.size() == 1) {
            mCallAudioModeStateMachine.sendMessageWithArgs(
                    CallAudioModeStateMachine.NEW_AUDIO_PROCESSING_CALL,
                    makeArgsForModeStateMachine());
        }
    }

    private void onCallLeavingActiveDialingOrConnecting() {
        if (mActiveDialingOrConnectingCalls.size() == 0) {
            mCallAudioModeStateMachine.sendMessageWithArgs(
                    CallAudioModeStateMachine.NO_MORE_ACTIVE_OR_DIALING_CALLS,
                    makeArgsForModeStateMachine());
        }
    }

    private void onCallLeavingRinging() {
        if (mRingingCalls.size() == 0) {
            mCallAudioModeStateMachine.sendMessageWithArgs(
                    CallAudioModeStateMachine.NO_MORE_RINGING_CALLS,
                    makeArgsForModeStateMachine());
        }
    }

    private void onCallSilenceCrs() {
        if (mRingingCalls.size() == 1) {
            mCallAudioModeStateMachine.sendMessageWithArgs(
                    CallAudioModeStateMachine.CRS_CHANGE_SILENCE,
                    makeArgsForModeStateMachine());
        }
    }

    private void onCallLeavingHold() {
        if (mHoldingCalls.size() == 0) {
            mCallAudioModeStateMachine.sendMessageWithArgs(
                    CallAudioModeStateMachine.NO_MORE_HOLDING_CALLS,
                    makeArgsForModeStateMachine());
        }
    }

    private void onCallEnteringActiveDialingOrConnecting() {
        if (mActiveDialingOrConnectingCalls.size() == 1) {
            mCallAudioModeStateMachine.sendMessageWithArgs(
                    CallAudioModeStateMachine.NEW_ACTIVE_OR_DIALING_CALL,
                    makeArgsForModeStateMachine());
        }
    }

    private void onCallEnteringRinging() {
        if (mRingingCalls.size() == 1) {
            mCallAudioModeStateMachine.sendMessageWithArgs(
                    CallAudioModeStateMachine.NEW_RINGING_CALL,
                    makeArgsForModeStateMachine());
        }
    }

    private void onCallEnteringHold() {
        if (mHoldingCalls.size() == 1) {
            mCallAudioModeStateMachine.sendMessageWithArgs(
                    CallAudioModeStateMachine.NEW_HOLDING_CALL,
                    makeArgsForModeStateMachine());
        }
    }

    private void updateForegroundCall() {
        Call oldForegroundCall = mForegroundCall;
        if (mActiveDialingOrConnectingCalls.size() > 0) {
            // Give preference for connecting calls over active/dialing for foreground-ness.
            Call possibleConnectingCall = null;
            for (Call call : mActiveDialingOrConnectingCalls) {
                if (call.getState() == CallState.CONNECTING) {
                    possibleConnectingCall = call;
                }
            }
            mForegroundCall = possibleConnectingCall == null ?
                    mActiveDialingOrConnectingCalls.iterator().next() : possibleConnectingCall;
        } else if (mRingingCalls.size() > 0) {
            mForegroundCall = mRingingCalls.iterator().next();
        } else if (mHoldingCalls.size() > 0) {
            mForegroundCall = mHoldingCalls.iterator().next();
        } else {
            mForegroundCall = null;
        }

        if (mForegroundCall != oldForegroundCall) {
            mCallAudioRouteStateMachine.sendMessageWithSessionInfo(
                    CallAudioRouteStateMachine.UPDATE_SYSTEM_AUDIO_ROUTE);
            mDtmfLocalTonePlayer.onForegroundCallChanged(oldForegroundCall, mForegroundCall);
            maybePlayHoldTone();
        }
    }

    @NonNull
    private CallAudioModeStateMachine.MessageArgs makeArgsForModeStateMachine() {
        return new Builder()
                .setHasActiveOrDialingCalls(mActiveDialingOrConnectingCalls.size() > 0)
                .setHasRingingCalls(mRingingCalls.size() > 0)
                .setHasHoldingCalls(mHoldingCalls.size() > 0)
                .setHasAudioProcessingCalls(mAudioProcessingCalls.size() > 0)
                .setIsTonePlaying(mIsTonePlaying)
                .setForegroundCallIsVoip(
                        mForegroundCall != null && mForegroundCall.getIsVoipAudioMode())
                .setSession(Log.createSubsession())
                .setIsCrsCall(mIsInCrsMode).build();
    }

    private HashSet<Call> getBinForCall(Call call) {
        if (call.getState() == CallState.ANSWERED) {
            // If the call has the speed-up-mt-audio capability, treat answered state as active
            // for audio purposes.
            if (call.can(android.telecom.Call.Details.CAPABILITY_SPEED_UP_MT_AUDIO)) {
                return mActiveDialingOrConnectingCalls;
            }
            return mRingingCalls;
        }
        return mCallStateToCalls.get(call.getState());
    }

    private void removeCallFromAllBins(Call call) {
        for (int i = 0; i < mCallStateToCalls.size(); i++) {
            mCallStateToCalls.valueAt(i).remove(call);
        }
    }

    private void playToneAfterCallConnected(Call call) {
        final Context context = call.getContext();
        ToneGenerator toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
        try {
            if (Settings.System.getInt(context.getContentResolver(),
                        Settings.System.CALL_CONNECTED_TONE_ENABLED) == 1) {
                if (toneGenerator != null) {
                    Log.i(LOG_TAG, "playing tone");
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
                }
            }
        } catch (SettingNotFoundException e) {
            Log.e(this, e, "Settings exception when reading playing tone config");
        }
    }

    private void playToneForDisconnectedCall(Call call) {
        // If this call is being disconnected as a result of being handed over to another call,
        // we will not play a disconnect tone.
        if (call.isHandoverInProgress()) {
            Log.i(LOG_TAG, "Omitting tone because %s is being handed over.", call);
            return;
        }

        if (mForegroundCall != null && call != mForegroundCall && mCalls.size() > 1) {
            Log.v(LOG_TAG, "Omitting tone because we are not foreground" +
                    " and there is another call.");
            return;
        }

        if (call.getDisconnectCause() != null) {
            int toneToPlay = InCallTonePlayer.TONE_INVALID;

            Log.v(this, "Disconnect cause: %s.", call.getDisconnectCause());

            switch(call.getDisconnectCause().getTone()) {
                case ToneGenerator.TONE_SUP_BUSY:
                    toneToPlay = InCallTonePlayer.TONE_BUSY;
                    break;
                case ToneGenerator.TONE_SUP_CONGESTION:
                    toneToPlay = InCallTonePlayer.TONE_CONGESTION;
                    break;
                case ToneGenerator.TONE_CDMA_REORDER:
                    toneToPlay = InCallTonePlayer.TONE_REORDER;
                    break;
                case ToneGenerator.TONE_CDMA_ABBR_INTERCEPT:
                    toneToPlay = InCallTonePlayer.TONE_INTERCEPT;
                    break;
                case ToneGenerator.TONE_CDMA_CALLDROP_LITE:
                    toneToPlay = InCallTonePlayer.TONE_CDMA_DROP;
                    break;
                case ToneGenerator.TONE_SUP_ERROR:
                    toneToPlay = InCallTonePlayer.TONE_UNOBTAINABLE_NUMBER;
                    break;
                case ToneGenerator.TONE_PROP_PROMPT:
                    toneToPlay = InCallTonePlayer.TONE_CALL_ENDED;
                    break;
            }

            Log.d(this, "Found a disconnected call with tone to play %d.", toneToPlay);

            if (toneToPlay != InCallTonePlayer.TONE_INVALID) {
                boolean didToneStart = mPlayerFactory.createPlayer(toneToPlay).startTone();
                if (didToneStart) {
                    mCallsManager.onDisconnectedTonePlaying(true);
                    mIsDisconnectedTonePlaying = true;
                }
            }
        }
    }

    private void playRingbackForCall(Call call) {
        if (call == mForegroundCall && call.isRingbackRequested()) {
            mRingbackPlayer.startRingbackForCall(call);
        }
    }

    private void stopRingbackForCall(Call call) {
        mRingbackPlayer.stopRingbackForCall(call);
    }

    /**
     * Determines if a hold tone should be played and then starts or stops it accordingly.
     */
    private void maybePlayHoldTone() {
        if (shouldPlayHoldTone()) {
            if (mHoldTonePlayer == null) {
                mHoldTonePlayer = mPlayerFactory.createPlayer(InCallTonePlayer.TONE_CALL_WAITING);
                mHoldTonePlayer.startTone();
            }
        } else {
            if (mHoldTonePlayer != null) {
                mHoldTonePlayer.stopTone();
                mHoldTonePlayer = null;
            }
        }
    }

    /**
     * Determines if a hold tone should be played.
     * A hold tone should be played only if foreground call is equals with call which is
     * remotely held.
     *
     * @return {@code true} if the the hold tone should be played, {@code false} otherwise.
     */
    private boolean shouldPlayHoldTone() {
        Call foregroundCall = getForegroundCall();
        // If there is no foreground call, no hold tone should play.
        if (foregroundCall == null) {
            return false;
        }

        // If another call is ringing, no hold tone should play.
        if (mCallsManager.hasRingingCall()) {
            return false;
        }

        // If the foreground call isn't active, no hold tone should play. This might happen, for
        // example, if the user puts a remotely held call on hold itself.
        if (!foregroundCall.isActive()) {
            return false;
        }

        return foregroundCall.isRemotelyHeld();
    }

    private void dumpCallsInCollection(IndentingPrintWriter pw, Collection<Call> calls) {
        for (Call call : calls) {
            if (call != null) pw.println(call.getId());
        }
    }

    private void maybeStopRingingAndCallWaitingForAnsweredOrRejectedCall(Call call) {
        // Check to see if the call being answered/rejected is the only ringing call, since this
        // will be called before the connection service acknowledges the state change.
        synchronized (mCallsManager.getLock()) {
            if (mRingingCalls.size() == 0 ||
                    (mRingingCalls.size() == 1 && call == mRingingCalls.iterator().next())) {
                mRinger.stopRinging();
                mRinger.stopCallWaiting();
            }
        }
    }

    private boolean shouldPlayDisconnectTone(int oldState, int newState) {
        if (newState != CallState.DISCONNECTED) {
            return false;
        }
        return oldState == CallState.ACTIVE ||
                oldState == CallState.DIALING ||
                oldState == CallState.ON_HOLD;
    }

    @VisibleForTesting
    public Set<Call> getTrackedCalls() {
        return mCalls;
    }

    @VisibleForTesting
    public SparseArray<LinkedHashSet<Call>> getCallStateToCalls() {
        return mCallStateToCalls;
    }
}
