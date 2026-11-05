package com.team4.bintime;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** BT-001 시작 화면. BT-002에서 하단 탭 화면으로 교체된다. */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView title = new TextView(this);
        title.setText("BIN TIME");
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        setContentView(title);
    }
}
