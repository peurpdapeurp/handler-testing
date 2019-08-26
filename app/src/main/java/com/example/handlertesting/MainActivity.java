package com.example.handlertesting;

import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import net.named_data.jndn.Name;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final int PRODUCER = 0, CONSUMER = 1;
    private static int mode;

    Name currentStreamName_;

    NetworkThreadConsumer networkThreadConsumer_;
    NetworkThreadProducer networkThreadProducer_;
    StreamFetcher streamFetcher_;

    EditText consumerFetchRateInput_;
    EditText streamNameInput_;
    EditText streamIdInput_;
    Button startButton_;
    Button modeButton_;
    Button generateRandomIdButton_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        streamNameInput_ = (EditText) findViewById(R.id.stream_name_input);
        streamIdInput_ = (EditText) findViewById(R.id.stream_id_input);

        currentStreamName_ = new Name(getString(R.string.network_prefix))
                .append(streamNameInput_.getText().toString())
                .append(streamIdInput_.getText().toString())
                .appendVersion(0);

        consumerFetchRateInput_ = (EditText) findViewById(R.id.consumer_fetch_rate_input);

        modeButton_ = (Button) findViewById(R.id.mode_button);
        modeButton_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (modeButton_.getText().toString().equals(getString(R.string.producer_mode))) {
                    modeButton_.setText(getString(R.string.consumer_mode));
                }
                else {
                    modeButton_.setText(getString(R.string.producer_mode));
                }
            }
        });

        generateRandomIdButton_ = (Button) findViewById(R.id.generate_id_button);
        generateRandomIdButton_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                streamIdInput_.setText(Long.toString(Helpers.getRandomLongBetweenRange(0, 10000)));
            }
        });

        startButton_ = (Button) findViewById(R.id.start_button);
        startButton_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                modeButton_.setEnabled(false);
                startButton_.setEnabled(false);

                if (modeButton_.getText().toString().equals(getString(R.string.producer_mode)))
                    mode = PRODUCER;
                else
                    mode = CONSUMER;

                switch (mode) {
                    case PRODUCER:
                        networkThreadProducer_ = new NetworkThreadProducer(currentStreamName_);
                        networkThreadProducer_.start();
                        Log.d(TAG, "Successfully started producer.");
                        break;
                    case CONSUMER:
                        networkThreadConsumer_ = new NetworkThreadConsumer();
                        networkThreadConsumer_.start();
                        while (networkThreadConsumer_.getHandler() == null) {} // block until network thread's handler initialized

                        streamFetcher_ = new StreamFetcher(currentStreamName_,
                                Long.parseLong(consumerFetchRateInput_.getText().toString()),
                                networkThreadConsumer_.getHandler());
                        streamFetcher_.start();
                        while (streamFetcher_.getHandler() == null) {} // block until stream fetcher's handler initialized

                        networkThreadConsumer_.addStreamFetcherHandler(currentStreamName_, streamFetcher_.getHandler());

                        boolean ret = streamFetcher_.startFetchingStream();
                        if (ret) Log.d(TAG, "Successfully started consumer."); else Log.d(TAG, "Failed to start consumer.");
                        break;
                }
            }
        });

    }
}
