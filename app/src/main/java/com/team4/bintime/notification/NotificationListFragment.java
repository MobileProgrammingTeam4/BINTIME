package com.team4.bintime.notification;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import com.team4.bintime.request.MatchAcceptedActivity;
import com.team4.bintime.request.MockRequestRepository;
import com.team4.bintime.request.RequestRepository;
import com.team4.bintime.request.RequestTabsController;
import com.team4.bintime.data.MockMatchRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class NotificationListFragment extends Fragment {
    private final MockNotificationRepository events = new MockNotificationRepository();
    private ActivityResultLauncher<Intent> accepted;
    private boolean demoEmpty;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        demoEmpty = state != null && state.getBoolean("demoEmpty");
        accepted = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if(result.getResultCode()==android.app.Activity.RESULT_OK) {
                boolean chat=result.getData()!=null && result.getData().getBooleanExtra(MatchAcceptedActivity.EXTRA_OPEN_CHAT,false);
                ((MainActivity) requireActivity()).selectTab(chat ? R.id.nav_chat : R.id.nav_matching);
            }
        });
        getChildFragmentManager().setFragmentResultListener(MatchRequestDialog.RESULT,this,(key,data)->{
            RequestRepository repo=((MainActivity)requireActivity()).requests();
            boolean changed=repo.respond(data.getString("id"),MockRequestRepository.Status.valueOf(data.getString("status")));
            render();
            if(changed && "ACCEPTED".equals(data.getString("status"))) accepted.launch(MatchAcceptedActivity.intent(requireContext(),repo.find(data.getString("id"))));
            else if(!changed) Toast.makeText(requireContext(),R.string.notification_processed,Toast.LENGTH_SHORT).show();
        });
    }
    @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle state) {
        return inflater.inflate(R.layout.fragment_notifications,parent,false);
    }
    @Override public void onViewCreated(@NonNull View root, Bundle state) {
        root.findViewById(R.id.notificationBack).setOnClickListener(v->getParentFragmentManager().popBackStack());
        root.findViewById(R.id.notificationDate).setOnLongClickListener(v -> {
            demoEmpty = !demoEmpty; render();
            Toast.makeText(requireContext(), "시연: 알림 빈 목록 전환", Toast.LENGTH_SHORT).show();
            return true;
        });
        render();
    }
    @Override public void onSaveInstanceState(@NonNull Bundle out) { out.putBoolean("demoEmpty", demoEmpty); super.onSaveInstanceState(out); }
    @Override public void onResume() { super.onResume(); render(); }
    private void render() {
        if(getView()==null) return;
        View root=requireView();
        ((TextView)root.findViewById(R.id.notificationDate)).setText(LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("오늘 · M월 d일 (E)",Locale.KOREAN)));
        LinearLayout rows=root.findViewById(R.id.notificationRows); rows.removeAllViews();
        RequestRepository repo=((MainActivity)requireActivity()).requests();
        String[] titles=getResources().getStringArray(R.array.notification_titles);
        com.team4.bintime.common.StateView empty = root.findViewById(R.id.notificationEmpty);
        if (demoEmpty) empty.showEmpty(getString(R.string.notification_empty), getString(R.string.notification_empty_message));
        else empty.hide();
        for(MockNotificationRepository.Item event:demoEmpty ? java.util.Collections.<MockNotificationRepository.Item>emptyList() : events.list()) {
            MockRequestRepository.Item request=repo.find(event.requestId);
            if(request==null) continue;
            View row=getLayoutInflater().inflate(R.layout.item_notification,rows,false);
            String name=MockMatchRepository.find(request.profileUid).name;
            ((TextView)row.findViewById(R.id.notificationTitle)).setText(titles[event.type.ordinal()]);
            ((TextView)row.findViewById(R.id.notificationTime)).setText(new String[]{"14:08","14:00","13:55","13:25"}[event.type.ordinal()]);
            int body=event.type==MockNotificationRepository.Type.MESSAGE?R.string.notification_message_body:event.type==MockNotificationRepository.Type.ACCEPTED?R.string.notification_match_body:event.type==MockNotificationRepository.Type.REQUEST?R.string.notification_request_body:R.string.notification_review_body;
            ((TextView)row.findViewById(R.id.notificationBody)).setText(getString(body,name,RequestTabsController.timeLabel(request)));
            String action;
            if(event.type==MockNotificationRepository.Type.REQUEST) action=events.canRespond(event,repo)?getString(R.string.notification_request_open):request.status()==MockRequestRepository.Status.ACCEPTED?getString(R.string.notification_request_accepted):getString(R.string.notification_processed);
            else action=getString(event.type==MockNotificationRepository.Type.MESSAGE?R.string.notification_chat:event.type==MockNotificationRepository.Type.ACCEPTED?R.string.notification_match_open:R.string.request_review);
            ((TextView)row.findViewById(R.id.notificationAction)).setText(action+" ›");
            ((ImageView)row.findViewById(R.id.notificationIcon)).setImageResource(event.type==MockNotificationRepository.Type.MESSAGE?R.drawable.ic_nav_chat:event.type==MockNotificationRepository.Type.ACCEPTED?R.drawable.ic_check:R.drawable.ic_users_round);
            row.setOnClickListener(v->{
                if(event.type==MockNotificationRepository.Type.MESSAGE) ((MainActivity)requireActivity()).selectTab(R.id.nav_chat);
                else if(event.type==MockNotificationRepository.Type.REVIEW) Toast.makeText(requireContext(),R.string.request_review_pending,Toast.LENGTH_SHORT).show();
                else if(event.type==MockNotificationRepository.Type.REQUEST && events.canRespond(event,repo)) MatchRequestDialog.showRequest(getChildFragmentManager(),request.id);
                else if(request.status()==MockRequestRepository.Status.ACCEPTED) accepted.launch(MatchAcceptedActivity.intent(requireContext(),request));
                else Toast.makeText(requireContext(),R.string.notification_processed,Toast.LENGTH_SHORT).show();
            });
            rows.addView(row);
        }
    }
}
