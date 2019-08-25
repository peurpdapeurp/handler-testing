package com.example.handlertesting;

import android.os.Handler;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import net.named_data.jndn.Name;

public class MainActivity extends AppCompatActivity {

    Name dummyStreamName_;

    NetworkThread networkThread_;
    Handler networkThreadHandler_;
    StreamFetcher streamFetcher_;
    Handler streamFetcherHandler_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dummyStreamName_ = new Name("/dummy_stream");

        networkThread_ = new NetworkThread();
        networkThread_.start();
        while (networkThread_.getHandler() == null) {} // block until network thread's handler is initialized
        networkThreadHandler_ = networkThread_.getHandler();

        streamFetcher_ = new StreamFetcher(dummyStreamName_, 1000, networkThreadHandler_);
        streamFetcher_.start();
        while (streamFetcher_.getHandler() == null) {} // block until stream fetcher's handler is initialized
        streamFetcherHandler_ = streamFetcher_.getHandler();
        streamFetcher_.startFetchingStream();


    }
}
