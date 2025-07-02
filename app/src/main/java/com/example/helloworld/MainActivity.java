package com.example.helloworld;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

public class MainActivity extends AppCompatActivity {
    private Button btnSelectVideo;
    private static final int REQUEST_CODE_SELECT_VIDEO = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnSelectVideo = findViewById(R.id.btnSelectVideo);

        // 选择手机相册中的视频
        btnSelectVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("video/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, REQUEST_CODE_SELECT_VIDEO);
            }
        });

        Button btnConnectBluetooth = findViewById(R.id.btnConnectBluetooth);
        btnConnectBluetooth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, BluetoothConnectActivity.class);
                startActivity(intent);
            }
        });

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_SELECT_VIDEO && resultCode == RESULT_OK && data != null) {
            Uri selectedVideoUri = data.getData();
            if (selectedVideoUri != null) {
                // 如果需要持久化权限，可调用下面代码（仅当需要跨会话使用时添加）：
                // getContentResolver().takePersistableUriPermission(selectedVideoUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                // 启动 VideoProcessActivity，并传递选中视频的 Uri
                Intent intent = new Intent(MainActivity.this, VideoProcessActivity.class);
                intent.setData(selectedVideoUri);
                intent.putExtra("IS_ASSET", false); // 标识使用用户选中的视频
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            }
        }
    }
}
