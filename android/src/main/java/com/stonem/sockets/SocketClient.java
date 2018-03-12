package com.stonem.sockets;

import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;

import android.util.Log;
import android.os.AsyncTask;
import android.support.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Created by David Stoneham on 2017-08-03.
 */

public class SocketClient {
    public Socket clientSocket;


    private final String eTag = "REACT-NATIVE-SOCKETS";
    private final String event_closed = "socketClient_closed";
    private final String event_data = "socketClient_data";
    private final String event_error = "socketClient_error";
    private final String event_connect = "socketClient_connected";
    private String dstAddress;
    private int dstPort;
    private ReactContext mReactContext;
    private boolean isOpen = false;
    private boolean reconnectOnClose = false;
    private int reconnectDelay = 500;
    private int maxReconnectAttempts = -1;
    private int reconnectAttempts = 0;
    private boolean userDidClose = false;
    private boolean isFirstConnect = true;
    private BufferedInputStream bufferedInput;
    private boolean readingStream = false;
    private final byte EOT = 0x04;

    SocketClient(ReadableMap params, ReactContext reactContext) {
        //String addr, int port, boolean autoReconnect
        mReactContext = reactContext;
        dstAddress = params.getString("address");
        dstPort = params.getInt("port");
        if (params.hasKey("reconnect")) {
            reconnectOnClose = params.getBoolean("reconnect");
        }
        if (params.hasKey("maxReconnectAttempts")) {
            maxReconnectAttempts = params.getInt("maxReconnectAttempts");
        }
        if (params.hasKey("reconnectDelay")) {
            reconnectDelay = params.getInt("reconnectDelay");
        }

        Thread socketClientThread = new Thread(new SocketClientThread());
        socketClientThread.start();
    }

    public void disconnect(boolean wasUser) {
        try {
            if (clientSocket != null) {
                userDidClose = wasUser;
                isOpen = false;
                clientSocket.close();
                clientSocket = null;
                Log.d(eTag, "client closed");
            }
        } catch (IOException e) {
            handleIOException(e);
        }
    }

    private void sendEvent(ReactContext reactContext, String eventName, @Nullable WritableMap params) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(eventName, params);
    }

    protected void write(String message) {
        new AsyncTask<String, Void, Void>() {
            @Override
            protected void onPreExecute() {
                super.onPreExecute();
            }

            @Override
            protected Void doInBackground(String... params) {
                try {
                    if (clientSocket != null && clientSocket.getOutputStream() != null) {
                        String message = params[0];
                        OutputStream outputStream = clientSocket.getOutputStream();
                        PrintStream printStream = new PrintStream(outputStream);
                        printStream.print(message + (char) EOT);
                        printStream.flush();
                        //debug log
                        Log.d(eTag, "client sent message: " + message);
                    }else{
                        Log.d(eTag, "client could not sent message");
                    }
                } catch (IOException e) {
                    handleIOException(e);
                }
                return null;
            }

            protected void onPostExecute(Void dummy) {
            }
        }.execute(message);
    }

    public void onDestroy() {
        if (clientSocket != null) {
            try {
                clientSocket.close();
            } catch (IOException e) {
                Log.e(eTag, "Client Destroy IOException", e);
            }
        }
    }

    private class SocketClientThread extends Thread {
        @Override
        public void run() {
            while (isFirstConnect || (!userDidClose && reconnectOnClose)) {
                try {
                    if (connectSocket()) {
                        watchIncoming();
                        reconnectAttempts = 0;
                    } else {
                        reconnectAttempts++;
                    }
                    isFirstConnect = false;
                    if (maxReconnectAttempts == -1 || maxReconnectAttempts < reconnectAttempts) {
                        Thread.sleep(reconnectDelay);
                    } else {
                        reconnectOnClose = false;
                    }
                } catch (InterruptedException e) {
                    //debug log
                    Log.e(eTag, "Client InterruptedException", e);
                }
            }
        }
    }

    private boolean connectSocket() {
        try {
            clientSocket = new Socket(dstAddress, dstPort);
            isOpen = true;

            WritableMap eventParams = Arguments.createMap();
            sendEvent(mReactContext, event_connect, eventParams);
            return true;
        } catch (UnknownHostException e) {
            handleUnknownHostException(e);
        } catch (IOException e) {
            handleIOException(e);
        }
        return false;
    }

    private void watchIncoming() {
        try {
            String data = "";
            InputStream inputStream = clientSocket.getInputStream();
            while (isOpen) {
                int incomingByte = inputStream.read();

                if (incomingByte == -1) {
                    //debug log
                    Log.v(eTag, "Client disconnected");
                    isOpen = false;
                    //emit event
                    WritableMap eventParams = Arguments.createMap();
                    sendEvent(mReactContext, event_closed, eventParams);
                } else if (incomingByte == EOT) {
                    //debug log
                    Log.d(eTag, "client received message: " + data);
                    //emit event
                    WritableMap eventParams = Arguments.createMap();
                    eventParams.putString("data", data);
                    sendEvent(mReactContext, event_data, eventParams);
                    //clear incoming
                    data = "";
                } else {
                    data += (char) incomingByte;
                }
            }
        } catch (IOException e) {
            handleIOException(e);
        }
    }

    private void handleIOException(IOException e) {
        //debug log
        Log.e(eTag, "Client IOException", e);
        //emit event
        String message = e.getMessage();
        WritableMap eventParams = Arguments.createMap();
        eventParams.putString("error", message);
        if (message.equals("Socket closed")) {
            isOpen = false;
            sendEvent(mReactContext, event_closed, eventParams);
        } else {
            sendEvent(mReactContext, event_error, eventParams);
        }
    }

    private void handleUnknownHostException(UnknownHostException e) {
        //debug log
        Log.e(eTag, "Client UnknownHostException", e);
        //emit event
        String message = e.getMessage();
        WritableMap eventParams = Arguments.createMap();
        eventParams.putString("error", e.getMessage());
        sendEvent(mReactContext, event_error, eventParams);
    }

}
