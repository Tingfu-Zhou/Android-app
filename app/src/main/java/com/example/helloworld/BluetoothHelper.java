package com.example.helloworld;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

import android.content.Context;


public class BluetoothHelper {
    private static final String TAG = "BluetoothHelper";
    private BluetoothSocket socket;
    private OutputStream outStream;
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public static BluetoothHelper globalHelper = null;

    // 用于蓝牙设备选择连接
    public BluetoothHelper(Context context, BluetoothDevice device) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "无权限连接蓝牙设备");
                return;
            }

            socket = device.createRfcommSocketToServiceRecord(MY_UUID);
            socket.connect();
            outStream = socket.getOutputStream();
            Log.d(TAG, "已连接到设备: " + device.getName());
        } catch (IOException e) {
            Log.e(TAG, "连接失败: " + e.getMessage());
        }
    }


    public void sendData(String data) {
        try {
            if (outStream != null) {
                outStream.write((data + "\n").getBytes());
                outStream.flush();
                Log.d(TAG, "发送数据: " + data);
            }
        } catch (IOException e) {
            Log.e(TAG, "发送失败: " + e.getMessage());
        }
    }

    public void close() {
        try {
            if (outStream != null) outStream.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
