package com.team4.bintime.chat;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.FirebaseDatabase;
import com.team4.bintime.R;
import com.team4.bintime.auth.AuthRepositories;

import java.util.Objects;

/**
 * 채팅 저장소 선택 (BT-021). 인증·프로필·요청 저장소와 같은 기준으로 고른다.
 * google-services.json 이 있어 Firebase 가 초기화되면 RTDB 구현을, 없으면(CI·설정 전 팀원) 기존 Mock 을 쓴다.
 * 로그인 UID 가 바뀌면 새 저장소를 만든다. 채팅 목록·채팅방 화면이 이 선택기를 쓴다(BT-022).
 *
 * <p>RTDB 주소는 google-services.json 의 firebase_url(asia-southeast1)을 그대로 쓴다. 코드에 주소를 넣지 않는다.
 * 디버그 빌드에서 R.string.chat_rtdb_emulator_host 가 비어 있지 않으면 로컬 RTDB 에뮬레이터에 연결한다(docs/BT-021.md).
 */
public final class ChatRepositories {

    private static final String TAG = "ChatRepositories";
    private static final int EMULATOR_PORT = 9000;

    private static ChatRepository selected;
    private static String selectedUid;
    private static boolean emulatorConfigured;

    private ChatRepositories() {}

    public static synchronized ChatRepository get(Context context) {
        if (!AuthRepositories.isFirebaseEnabled(context)) return MockChatRepository.shared();
        String uid = AuthRepositories.get(context).getCurrentUid();
        if (!(selected instanceof FirebaseChatRepository) || !Objects.equals(selectedUid, uid)) {
            if (selected instanceof FirebaseChatRepository) ((FirebaseChatRepository) selected).close();
            selectedUid = uid;
            selected = new FirebaseChatRepository(uid, database(context), MatchInfoSource.UNAVAILABLE,
                    PartnerSource.UNAVAILABLE, context.getString(R.string.chat_partner_fallback_name));
        }
        return selected;
    }

    /** RTDB 주소가 없으면 null. useEmulator 는 인스턴스를 처음 쓰기 전에 한 번만 호출할 수 있다. */
    @Nullable
    private static FirebaseDatabase database(Context context) {
        FirebaseApp app = FirebaseApp.getInstance();
        if (TextUtils.isEmpty(app.getOptions().getDatabaseUrl())) {
            Log.e(TAG, "RTDB 주소가 없습니다. Firebase 콘솔에서 google-services.json 을 다시 받아 app/ 에 넣어 주세요.");
            return null;
        }
        FirebaseDatabase database = FirebaseDatabase.getInstance(app);
        String host = context.getString(R.string.chat_rtdb_emulator_host).trim();
        boolean debuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!emulatorConfigured && debuggable && !host.isEmpty()) {
            database.useEmulator(host, EMULATOR_PORT);
            Log.i(TAG, "RTDB emulator: " + host + ":" + EMULATOR_PORT);
        }
        emulatorConfigured = true;
        return database;
    }
}
