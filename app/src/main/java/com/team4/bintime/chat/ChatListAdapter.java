package com.team4.bintime.chat;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.team4.bintime.R;
import com.team4.bintime.profile.ProfileRepositories;
import com.team4.bintime.profile.ProfileRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 채팅 목록 항목. Figma ChatListItem 기준이라 매칭 상태 레이블은 넣지 않는다. */
final class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.Holder> {

    private final List<ChatRoom> items = new ArrayList<>();
    private final Consumer<ChatRoom> onClick;

    ChatListAdapter(Consumer<ChatRoom> onClick) {
        this.onClick = onClick;
    }

    void submit(List<ChatRoom> rooms) {
        items.clear();
        items.addAll(rooms);
        notifyDataSetChanged();
    }

    /**
     * 사진이 있으면 사진, 없으면 이름을 표시한다. 목록과 말풍선이 함께 쓴다.
     * BT-034: 공개 프로필 사진 경로가 있으면 B 의 loadProfilePhoto(캐시·중복 요청 합치기)로 불러온다.
     * 경로를 태그로 기억해 같은 경로는 다시 받지 않고, 재활용된 행에 다른 사람의 늦은 결과를 넣지 않는다.
     * 실패하면 기본 이미지를 유지한다(재시도 루프 없음).
     */
    static void bindAvatar(ImageView photo, TextView initials, ChatRoom room) {
        boolean hasPhoto = room == null || room.otherHasPhoto;
        photo.setVisibility(hasPhoto ? View.VISIBLE : View.GONE);
        initials.setVisibility(hasPhoto ? View.GONE : View.VISIBLE);
        if (!hasPhoto) initials.setText(room.otherName);
        String path = hasPhoto && room != null ? room.otherPhotoPath : null;
        if (path == null) {
            if (photo.getTag() != null) photo.setImageResource(R.drawable.img_default_profile);
            photo.setTag(null);
            return;
        }
        if (path.equals(photo.getTag())) return;
        photo.setTag(path);
        photo.setImageResource(R.drawable.img_default_profile);
        ProfileRepositories.get(photo.getContext()).loadProfilePhoto(path, new ProfileRepository.Callback<Bitmap>() {
            @Override public void onSuccess(Bitmap bitmap) {
                if (bitmap != null && path.equals(photo.getTag())) photo.setImageBitmap(bitmap);
            }
            @Override public void onError(String message) { /* 기본 이미지 유지 */ }
        });
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_room, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        ChatRoom room = items.get(position);
        bindAvatar(h.photo, h.initials, room);
        h.name.setText(room.otherName);
        h.itemView.findViewById(R.id.chatArchiveBadge).setVisibility(room.archived ? View.VISIBLE : View.GONE);
        h.time.setText(ChatTimeFormatter.listTime(room.lastMessageAt, System.currentTimeMillis(),
                h.itemView.getContext().getString(R.string.chat_yesterday)));
        h.preview.setText(room.archived ? h.itemView.getContext().getString(R.string.chat_archive_preview) : room.lastMessage);
        h.unread.setVisibility(room.unreadCount > 0 ? View.VISIBLE : View.GONE);
        h.unread.setText(String.valueOf(room.unreadCount));
        h.itemView.setOnClickListener(v -> onClick.accept(room));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView photo;
        final TextView initials, name, time, preview, unread;

        Holder(View v) {
            super(v);
            photo = v.findViewById(R.id.chatAvatarPhoto);
            initials = v.findViewById(R.id.chatAvatarInitials);
            name = v.findViewById(R.id.chatRoomName);
            time = v.findViewById(R.id.chatRoomTime);
            preview = v.findViewById(R.id.chatRoomPreview);
            unread = v.findViewById(R.id.chatRoomUnread);
        }
    }
}
