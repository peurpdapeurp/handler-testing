package com.example.handlertesting;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import net.named_data.jndn.Data;
import net.named_data.jndn.Face;
import net.named_data.jndn.Interest;
import net.named_data.jndn.Name;
import net.named_data.jndn.OnData;
import net.named_data.jndn.encoding.EncodingException;
import net.named_data.jndn.security.KeyChain;
import net.named_data.jndn.security.SecurityException;
import net.named_data.jndn.security.identity.IdentityManager;
import net.named_data.jndn.security.identity.MemoryIdentityStorage;
import net.named_data.jndn.security.identity.MemoryPrivateKeyStorage;
import net.named_data.jndn.security.policy.SelfVerifyPolicyManager;

import java.io.IOException;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

public class NetworkThreadConsumer extends HandlerThread {

    private final static String TAG = "NetworkThreadConsumer";

    private Face face_;
    private KeyChain keyChain_;

    private long startTime_;
    private ConcurrentHashMap<Name, Handler> streamFetcherHandlers_;
    private Handler handler_;

    HashSet<Name> retransmits_;
    HashSet<Name> recvDatas_;

    // Private constants
    private static final int PROCESSING_INTERVAL_MS = 100;

    // Messages
    private static final int MSG_DO_SOME_WORK = 0;
    public static final int MSG_INTEREST_SEND_REQUEST = 1;

    private long getTimeSinceNetworkThreadStart() {
        return System.currentTimeMillis() - startTime_;
    }

    public void addStreamFetcherHandler(Name streamName, Handler handler) {
        streamFetcherHandlers_.put(streamName, handler);
    }

    public void removeStreamFetcherHandler(Name streamName) {
        streamFetcherHandlers_.remove(streamName);
    }

    public NetworkThreadConsumer() {
        super("NetworkThreadConsumer");
        streamFetcherHandlers_ = new ConcurrentHashMap<>();
        retransmits_ = new HashSet<>();
        recvDatas_ = new HashSet<>();
    }

    private void doSomeWork() {
        try {
            face_.processEvents();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (EncodingException e) {
            e.printStackTrace();
        }

        scheduleNextWork(SystemClock.uptimeMillis(), PROCESSING_INTERVAL_MS);
    }

    private void scheduleNextWork(long thisOperationStartTimeMs, long intervalMs) {
        handler_.removeMessages(MSG_DO_SOME_WORK);
        handler_.sendEmptyMessageAtTime(MSG_DO_SOME_WORK, thisOperationStartTimeMs + intervalMs);
    }

    private void sendInterest(Interest interest) {
        Log.d(TAG, getTimeSinceNetworkThreadStart() + ": " + "send interest (name " + interest.getName().toString() + ")");
        if (!retransmits_.contains(interest.getName())) {
            retransmits_.add(interest.getName());
        }
        else {
            Log.d(TAG, "INTEREST RETRANSMISSION (" + "name " + interest.getName() + ")");
        }
        try {
            face_.expressInterest(interest, new OnData() {
                        @Override
                        public void onData(Interest interest, Data data) {
                            long satisfiedTime = System.currentTimeMillis();
                            if (!recvDatas_.contains(data.getName())) {
                                recvDatas_.add(data.getName());
                            }
                            else {
                                Log.d(TAG, "DUPLICATE ONDATA (" + "name " + data.getName() + ", " + "retx " +
                                        retransmits_.contains(interest.getName()) + ")");
                            }
                            Log.d(TAG, getTimeSinceNetworkThreadStart() + ": " + "data received (time " + satisfiedTime + ")");

                            Handler streamFetcherHandler = streamFetcherHandlers_.get(data.getName().getPrefix(-1));
                            if (streamFetcherHandler == null) {
                                Log.w(TAG, getTimeSinceNetworkThreadStart() + ": " +
                                        "unable to find stream fetcher handler for stream name " + data.getName().getPrefix(-1));
                                return;
                            }
                            streamFetcherHandler.obtainMessage(StreamFetcher.MSG_DATA_RECEIVED,
                                    new StreamFetcher.DataInfo(data, satisfiedTime)).sendToTarget();
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("HandlerLeak")
    @Override
    protected void onLooperPrepared() {
        handler_ = new Handler() {
            @Override
            public void handleMessage(@NonNull Message msg) {
                switch (msg.what) {
                    case MSG_DO_SOME_WORK:
                        doSomeWork();
                        break;
                    case MSG_INTEREST_SEND_REQUEST:
                        Interest interest = (Interest) msg.obj;
                        sendInterest(interest);
                        break;
                    default:
                        throw new IllegalStateException();
                }
            }
        };
        startTime_ = System.currentTimeMillis();
        // set up keychain
        keyChain_ = configureKeyChain();
        // set up face
        face_ = new Face();
        try {
            face_.setCommandSigningInfo(keyChain_, keyChain_.getDefaultCertificateName());
        } catch (SecurityException e) {
            e.printStackTrace();
        }
        doSomeWork();
    }

    // taken from https://github.com/named-data-mobile/NFD-android/blob/4a20a88fb288403c6776f81c1d117cfc7fced122/app/src/main/java/net/named_data/nfd/utils/NfdcHelper.java
    private static KeyChain configureKeyChain() {

        final MemoryIdentityStorage identityStorage = new MemoryIdentityStorage();
        final MemoryPrivateKeyStorage privateKeyStorage = new MemoryPrivateKeyStorage();
        final KeyChain keyChain = new KeyChain(new IdentityManager(identityStorage, privateKeyStorage),
                new SelfVerifyPolicyManager(identityStorage));

        Name name = new Name("/tmp-identity");

        try {
            // create keys, certs if necessary
            if (!identityStorage.doesIdentityExist(name)) {
                keyChain.createIdentityAndCertificate(name);

                // set default identity
                keyChain.getIdentityManager().setDefaultIdentity(name);
            }
        }
        catch (SecurityException e){
            e.printStackTrace();
        }

        return keyChain;
    }

    public Handler getHandler() {
        return handler_;
    }

}