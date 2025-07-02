package com.example.helloworld;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.Set;

public class BluetoothConnectActivity extends Activity {
    private static final String TAG = "BluetoothConnectActivity";
    private static final int REQUEST_PERMISSION = 1;
    private ListView listView;
    private BluetoothAdapter bluetoothAdapter;
    private ArrayAdapter<String> adapter;
    private BluetoothDevice[] deviceArray;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        listView = new ListView(this);
        setContentView(listView);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_PERMISSION);
        } else {
            initBluetoothList();
        }
    }

    private void initBluetoothList() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "蓝牙未开启", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "无 BLUETOOTH_CONNECT 权限，无法访问蓝牙设备");
            return;
        }


        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        deviceArray = new BluetoothDevice[pairedDevices.size()];

        int i = 0;
        for (BluetoothDevice device : pairedDevices) {
            adapter.add(device.getName() + "\n" + device.getAddress());
            deviceArray[i++] = device;
        }

        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            BluetoothDevice selectedDevice = deviceArray[position];
            Toast.makeText(this, "连接中: " + selectedDevice.getName(), Toast.LENGTH_SHORT).show();
            BluetoothHelper.globalHelper = new BluetoothHelper(this, selectedDevice);
            Toast.makeText(this, "连接成功: " + selectedDevice.getName(), Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_PERMISSION &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initBluetoothList();
        } else {
            Toast.makeText(this, "未授予蓝牙权限", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
