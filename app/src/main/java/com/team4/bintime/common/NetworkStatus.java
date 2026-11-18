package com.team4.bintime.common;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * BT-030: 저장 전에 인터넷 연결이 있는지 본다. 프로필 저장(B)과 시간표 저장(C, BT-026)이 함께 쓴다.
 * Firestore 는 오프라인 쓰기를 기기에 보관하고 서버가 받을 때까지 끝내지 않아(오류 없음),
 * 비행기 모드에서 저장을 누르면 버튼이 눌린 채 연결될 때까지 기다리게 된다. 시작 전에 막고 안내한다.
 */
public final class NetworkStatus {

    private NetworkStatus() {}

    /** 인터넷을 쓸 수 있는 네트워크가 연결돼 있으면 true. 확인할 수 없으면 true(막지 않음). */
    public static boolean isOnline(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
