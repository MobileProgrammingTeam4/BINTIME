package com.team4.bintime.chat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.team4.bintime.R;

import java.util.ArrayList;
import java.util.List;

/** 날짜 구분선과 보낸·받은 말풍선을 함께 표시한다. */
final class ChatMessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_DATE = 0;
    private static final int TYPE_INCOMING = 1;
    private static final int TYPE_OUTGOING = 2;

    private static final class Row {
        final String date;
        final ChatMessage message;

        Row(String date, ChatMessage message) {
            this.date = date;
            this.message = message;
        }
    }

    private final String currentUid;
    private final List<Row> rows = new ArrayList<>();
    private ChatRoom room;

    ChatMessageAdapter(String currentUid) {
        this.currentUid = currentUid;
    }

    void setRoom(ChatRoom room) {
        boolean avatarChanged = this.room == null || !this.room.otherName.equals(room.otherName)
                || this.room.otherHasPhoto != room.otherHasPhoto;
        this.room = room;
        if (avatarChanged) notifyDataSetChanged();
    }

    void submit(List<ChatMessage> messages) {
        rows.clear();
        ChatMessage previous = null;
        for (ChatMessage message : messages) {
            if (previous == null || !ChatTimeFormatter.sameDay(previous.createdAt, message.createdAt)) {
                rows.add(new Row(ChatTimeFormatter.dateDivider(message.createdAt), null));
            }
            rows.add(new Row(null, message));
            previous = message;
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        ChatMessage message = rows.get(position).message;
        if (message == null) return TYPE_DATE;
        return currentUid.equals(message.senderId) ? TYPE_OUTGOING : TYPE_INCOMING;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_DATE) {
            return new DateHolder(inflater.inflate(R.layout.item_chat_date_divider, parent, false));
        }
        int layout = viewType == TYPE_OUTGOING ? R.layout.item_chat_message_outgoing : R.layout.item_chat_message_incoming;
        return new MessageHolder(inflater.inflate(layout, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof DateHolder) {
            ((DateHolder) holder).date.setText(row.date);
            return;
        }
        MessageHolder h = (MessageHolder) holder;
        h.bubble.setText(row.message.content);
        h.time.setText(ChatTimeFormatter.messageTime(row.message.createdAt));
        if (h.photo != null) ChatListAdapter.bindAvatar(h.photo, h.initials, room);
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    private static final class DateHolder extends RecyclerView.ViewHolder {
        final TextView date;

        DateHolder(View v) {
            super(v);
            date = (TextView) v;
        }
    }

    private static final class MessageHolder extends RecyclerView.ViewHolder {
        final TextView bubble, time, initials;
        final ImageView photo;

        MessageHolder(View v) {
            super(v);
            bubble = v.findViewById(R.id.chatBubble);
            time = v.findViewById(R.id.chatMessageTime);
            photo = v.findViewById(R.id.chatAvatarPhoto);
            initials = v.findViewById(R.id.chatAvatarInitials);
        }
    }
}
