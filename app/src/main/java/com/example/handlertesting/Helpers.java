package com.example.handlertesting;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class Helpers {

    /**
     * @param producerSamplingRate Audio sampling rate of producer (samples per second).
     * @param framesPerSegment ADTS frames per segment.
     */
    public static long calculateMsPerSeg(long producerSamplingRate, long framesPerSegment) {
        return (framesPerSegment * Constants.SAMPLES_PER_ADTS_FRAME *
                Constants.MILLISECONDS_PER_SECOND) / producerSamplingRate;
    }

    // https://stackoverflow.com/questions/4485128/how-do-i-convert-long-to-byte-and-back-in-java
    public static byte[] longToBytes(long x) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(x);
        return buffer.array();
    }

    // https://stackoverflow.com/questions/4485128/how-do-i-convert-long-to-byte-and-back-in-java
    public static long bytesToLong(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.put(bytes);
        buffer.flip();//need flip
        return buffer.getLong();
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    public static void printLongString(String TAG, String veryLongString) {
        int maxLogSize = 1000;
        for (int i = 0; i <= veryLongString.length() / maxLogSize; i++) {
            int start = i * maxLogSize;
            int end = (i + 1) * maxLogSize;
            end = end > veryLongString.length() ? veryLongString.length() : end;
            Log.d(TAG, veryLongString.substring(start, end));
        }
    }

    public static void writeHexStringToFile(String path, String hexString) {
        byte[] test_aac_file_byte_array = Helpers.hexStringToByteArray(hexString);
        File test_aac_file = new File(path);

        OutputStream os = null;
        try {
            os = new FileOutputStream(test_aac_file);
            os.write(test_aac_file_byte_array);
            os.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void printFileAsHexString(String TAG, String path) {
        byte[] buf = new byte[20000];
        byte[] real_contents = null;

        FileInputStream is = null;
        int read_size = 0;
        try {
            is = new FileInputStream(path);
            read_size = is.available();
            is.read(buf, 0, read_size);
            real_contents = Arrays.copyOf(buf, read_size);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        Log.d(TAG, "Contents of " + path + " (length: " + read_size + "):");
        Helpers.printLongString(TAG, Helpers.bytesToHex(real_contents));
    }

}
