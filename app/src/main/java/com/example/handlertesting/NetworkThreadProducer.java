package com.example.handlertesting;

import android.os.SystemClock;
import android.util.Log;

import net.named_data.jndn.Data;
import net.named_data.jndn.Face;
import net.named_data.jndn.Interest;
import net.named_data.jndn.InterestFilter;
import net.named_data.jndn.Name;
import net.named_data.jndn.OnInterestCallback;
import net.named_data.jndn.OnRegisterFailed;
import net.named_data.jndn.encoding.EncodingException;
import net.named_data.jndn.security.KeyChain;
import net.named_data.jndn.security.SecurityException;
import net.named_data.jndn.security.identity.IdentityManager;
import net.named_data.jndn.security.identity.MemoryIdentityStorage;
import net.named_data.jndn.security.identity.MemoryPrivateKeyStorage;
import net.named_data.jndn.security.pib.PibImpl;
import net.named_data.jndn.security.policy.SelfVerifyPolicyManager;
import net.named_data.jndn.security.tpm.TpmBackEnd;
import net.named_data.jndn.util.Blob;

import java.io.IOException;

public class NetworkThreadProducer implements Runnable {

    private final static String TAG = "NetworkThread";

    private Thread t_;
    Face face_;
    Name networkPrefix_;
    KeyChain keyChain_;

    public NetworkThreadProducer(Name networkPrefix) {
        networkPrefix_ = networkPrefix;
    }

    public void start() {
        if (t_ == null) {
            t_ = new Thread(this);
            t_.start();
        }
    }

    public void stop() {
        if (t_ != null) {
            t_.interrupt();
            try {
                t_.join();
            } catch (InterruptedException e) {}
            t_ = null;
        }
    }

    public void run() {

        Log.d(TAG,"NetworkThreadProducer started.");

        try {

            // set up keychain
            keyChain_ = configureKeyChain();

            // set up face
            face_ = new Face();
            try {
                face_.setCommandSigningInfo(keyChain_, keyChain_.getDefaultCertificateName());
            } catch (SecurityException e) {
                e.printStackTrace();
            }

            // register the prefix
            face_.registerPrefix(networkPrefix_,
                    new OnInterestCallback() {
                        @Override
                        public void onInterest(Name prefix, Interest interest, Face face, long interestFilterId, InterestFilter filter) {
                            Log.d(TAG, System.currentTimeMillis() + ": " +
                                    "receive interest (" +
                                    "name: " + interest.getName().toString() +
                                    ")");
                            Data data = new Data(interest.getName());
                            data.setContent(new Blob(new byte[] {0}));
                            try {
                                keyChain_.sign(data);
                                face_.putData(data);
                            } catch (IOException e) {
                                e.printStackTrace();
                            } catch (PibImpl.Error error) {
                                error.printStackTrace();
                            } catch (SecurityException e) {
                                e.printStackTrace();
                            } catch (TpmBackEnd.Error error) {
                                error.printStackTrace();
                            } catch (KeyChain.Error error) {
                                error.printStackTrace();
                            }
                        }
                    },
                    new OnRegisterFailed() {
                        @Override
                        public void onRegisterFailed(Name prefix) {

                        }
                    });

            while (!Thread.interrupted()) {
                face_.processEvents();
                SystemClock.sleep(100); // sleep to reduce battery usage
            }

        } catch (ArrayIndexOutOfBoundsException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (EncodingException e) {
            e.printStackTrace();
        } catch (SecurityException e) {
            e.printStackTrace();
        }

        Log.d(TAG,"NetworkThread stopped.");

    }

    // taken from https://github.com/named-data-mobile/NFD-android/blob/4a20a88fb288403c6776f81c1d117cfc7fced122/app/src/main/java/net/named_data/nfd/utils/NfdcHelper.java
    private KeyChain configureKeyChain() {

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

}
