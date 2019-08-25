package com.example.handlertesting;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import net.named_data.jndn.ContentType;
import net.named_data.jndn.Data;
import net.named_data.jndn.Interest;
import net.named_data.jndn.Name;
import net.named_data.jndn.encoding.EncodingException;

import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Timer;

public class StreamFetcher extends HandlerThread {

    private static final String TAG = "StreamFetcher";

    int printStateCounter_ = 0;

    // Private constants
    private static final int FINAL_BLOCK_ID_UNKNOWN = -1;
    private static final int NO_SEGS_SENT = -1;
    private static final int PROCESSING_INTERVAL_MS = 100;

    // Messages
    public static final int MSG_DATA_RECEIVED = 0;
    private static final int MSG_DO_SOME_WORK = 1;

    private boolean currentlyFetchingStream_ = false;
    private PriorityQueue<Long> retransmissionQueue_;
    private Name currentStreamName_;
    private long currentStreamFinalBlockId_ = FINAL_BLOCK_ID_UNKNOWN;
    private long highestSegSent_ = NO_SEGS_SENT;
    private long msPerSegNum_;
    private long streamFetchStartTime_;
    private int numOutstandingInterests_ = 0;
    private HashMap<Long, Long> segSendTimes_;
    private HashMap<Long, Object> rtoTokens_;
    private CwndCalculator cwndCalculator_;
    private RttEstimator rttEstimator_;
    private Handler handler_ = null;
    private Handler networkThreadHandler_;

    public static class DataInfo {
        public DataInfo(Data data, long receiveTime) {
            this.data = data;
            this.receiveTime = receiveTime;
        }
        Data data;
        long receiveTime;
    }

    private long getTimeSinceStreamFetchStart() {
        return System.currentTimeMillis() - streamFetchStartTime_;
    }

    private void printState() {
        Log.d(TAG, "Current state of StreamFetcher (" + getTimeSinceStreamFetchStart() + "):" + "\n" +
                "currentStreamFinalBlockId_: " + currentStreamFinalBlockId_ + ", " +
                "highestSegSent_: " + highestSegSent_ + ", " +
                "numOutstandingInterests_: " + numOutstandingInterests_ + "\n" +
                "retransmissionQueue_: " + retransmissionQueue_ + "\n" +
                "segSendTimes_: " + segSendTimes_);
    }

    public StreamFetcher(Name streamName, long msPerSegNum, Handler networkThreadHandler) {
        super(TAG);
        cwndCalculator_ = new CwndCalculator();
        retransmissionQueue_ = new PriorityQueue<>();
        currentStreamName_ = streamName;
        segSendTimes_ = new HashMap<>();
        rtoTokens_ = new HashMap<>();
        rttEstimator_ = new RttEstimator();
        msPerSegNum_ = msPerSegNum;
        networkThreadHandler_ = networkThreadHandler;
        Log.d(TAG, "Generating segment numbers to fetch at " + msPerSegNum_ + " per segment number.");
    }

    public boolean startFetchingStream() {
        if (handler_ == null) return false;
        if (currentlyFetchingStream_) {
            Log.w(TAG, "Already fetching a stream with name : " + currentStreamName_.toUri());
            return false;
        }
        currentlyFetchingStream_ = true;
        streamFetchStartTime_ = System.currentTimeMillis();
        doSomeWork(); // start the doSomeWork processing cycle
        return true;
    }

    public Handler getHandler() {
        return handler_;
    }

    private void doSomeWork() {
        while (retransmissionQueue_.size() != 0 && withinCwnd()) {
            Long segNum = retransmissionQueue_.poll();
            if (segNum == null) continue;
            transmitInterest(segNum, true);
        }

        if (currentStreamFinalBlockId_ == FINAL_BLOCK_ID_UNKNOWN ||
            highestSegSent_ < currentStreamFinalBlockId_) {
            while (nextSegShouldBeSent() && withinCwnd()) {
                highestSegSent_++;
                transmitInterest(highestSegSent_, false);
            }
        }

        if (printStateCounter_ < 10) printStateCounter_++; else { printState(); printStateCounter_ = 0; }

        scheduleNextWork(SystemClock.uptimeMillis(), PROCESSING_INTERVAL_MS);
    }

    private void scheduleNextWork(long thisOperationStartTimeMs, long intervalMs) {
        handler_.removeMessages(MSG_DO_SOME_WORK);
        handler_.sendEmptyMessageAtTime(MSG_DO_SOME_WORK, thisOperationStartTimeMs + intervalMs);
    }

    private boolean nextSegShouldBeSent() {
        long timeSinceFetchStart = getTimeSinceStreamFetchStart();
        boolean nextSegShouldBeSent = false;
        if (timeSinceFetchStart / msPerSegNum_ > highestSegSent_) {
            nextSegShouldBeSent = true;
        }
        return nextSegShouldBeSent;
    }

    private void transmitInterest(final long segNum, boolean isRetransmission) {
        Interest interestToSend = new Interest(currentStreamName_);
        interestToSend.getName().appendSegment(segNum);
        //long rto = (long) rttEstimator_.getEstimatedRto();
        long rto = 5000;
        interestToSend.setInterestLifetimeMilliseconds(rto);
        interestToSend.setCanBePrefix(false);
        interestToSend.setMustBeFresh(false);
        Object rtoToken = new Object();
        handler_.postAtTime(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, getTimeSinceStreamFetchStart() + ": " + "rto timeout (seg num " + segNum + ")");
                numOutstandingInterests_--;
                retransmissionQueue_.add(segNum);
            }
        }, rtoToken, SystemClock.uptimeMillis() + rto);
        rtoTokens_.put(segNum, rtoToken);
        if (isRetransmission) {
            segSendTimes_.remove(segNum);
        } else {
            segSendTimes_.put(segNum, System.currentTimeMillis());
        }
        networkThreadHandler_.obtainMessage(NetworkThread.MSG_INTEREST_SEND_REQUEST, interestToSend).sendToTarget();
        numOutstandingInterests_++;
        Log.d(TAG, getTimeSinceStreamFetchStart() + ": " + "interest transmitted (seg num " + segNum + ", " + "rto " + rto + ", " + "retx: " + isRetransmission + ")");
    }

    private void processData(DataInfo dataInfo) {
        Data audioPacket = dataInfo.data;
        long receiveTime = dataInfo.receiveTime;
        long segNum;
        try {
            segNum = audioPacket.getName().get(-1).toSegment();
        } catch (EncodingException e) {
            e.printStackTrace();
            return;
        }

        if (segSendTimes_.containsKey(segNum)) {
            long rtt = segSendTimes_.get(segNum) - receiveTime;
            Log.d(TAG, getTimeSinceStreamFetchStart() + ": " + "rtt estimator add measure (rtt " + rtt + ", " +
                    "num outstanding interests  " + numOutstandingInterests_ + ")");
            rttEstimator_.addMeasurement(rtt, numOutstandingInterests_);
            segSendTimes_.remove(segNum);
        }

        handler_.removeCallbacksAndMessages(rtoTokens_.get(segNum));

        long finalBlockId = FINAL_BLOCK_ID_UNKNOWN;
        boolean audioPacketWasAppNack = audioPacket.getMetaInfo().getType() == ContentType.NACK;
        if (audioPacketWasAppNack) {
            finalBlockId = Helpers.bytesToLong(audioPacket.getContent().getImmutableArray());
            currentStreamFinalBlockId_ = finalBlockId;
        }
        else {
            Name.Component finalBlockIdComponent = audioPacket.getMetaInfo().getFinalBlockId();
            if (finalBlockIdComponent != null) {
                try {
                    finalBlockId = finalBlockIdComponent.toSegment();
                    currentStreamFinalBlockId_ = finalBlockId;
                }
                catch (EncodingException e) {
                    e.printStackTrace();
                }
            }
        }
        Log.d(TAG, getTimeSinceStreamFetchStart() + ": " + "receive data (seg num " + segNum + ", " +
                "app nack: " + audioPacketWasAppNack +
                ((finalBlockId == FINAL_BLOCK_ID_UNKNOWN) ? "" : ", final block id " + finalBlockId)
                + ")");
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
                    case MSG_DATA_RECEIVED:
                        processData((DataInfo) msg.obj);
                        break;
                    default:
                        throw new IllegalStateException();
                }
            }
        };
    }

    private class CwndCalculator {

        private static final String TAG = "CwndCalculator";

        private static final long MAX_CWND = 4; // max # of outstanding interests

        long currentCwnd_;

        public CwndCalculator() {
            currentCwnd_ = MAX_CWND;
        }

        public long getCurrentCwnd() {
            return currentCwnd_;
        }

    }

    private boolean withinCwnd() {
        return numOutstandingInterests_ < cwndCalculator_.getCurrentCwnd();
    }
}
