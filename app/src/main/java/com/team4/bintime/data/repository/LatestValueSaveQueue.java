package com.team4.bintime.data.repository;

/**
 * [BT-015/016] 마지막 값만 저장하는 직렬 저장 큐. Android에 의존하지 않아 JVM 단위 테스트로 검증한다.
 * <ul>
 *   <li>한 번에 저장 요청 하나만 보낸다. 저장 중에 들어온 {@link #update}·{@link #flush}는 "다시 저장 필요"로만
 *       표시하고, 현재 요청이 끝난 뒤 그 시점의 최신 값으로 한 번 더 저장한다. 연속으로 바뀌어도 마지막 값으로
 *       수렴하고, 앞선 요청이 뒤 요청보다 늦게 서버에 닿아 덮어쓰는 일이 없다.</li>
 *   <li>저장이 실패하면 "다시 저장 필요"를 유지한다. 실패한 요청 진행 중에 새 값이나 저장 요청이 있었으면 바로
 *       최신 값으로 다시 보내고, 없으면 다음 {@link #flush}·{@link #update} 때 재시도한다(실패 반복 루프 없음).</li>
 *   <li>화면과 수명이 분리돼 있다. 리스너를 떼도 진행 중·대기 중 저장은 취소되지 않는다.</li>
 *   <li>[PR #88 D 리뷰 P1] {@link StartGuard}를 주면 저장 요청을 <b>실제로 보내기 직전마다</b>(첫 flush와 대기 값
 *       재저장 모두) 확인한다. 거부되면 대기 값을 버리고 큐를 닫는다(이후 update·flush는 무시). 이미 보낸 요청은
 *       건드리지 않는다. 계정 변경 보호는 {@link OwnerUidGuard}가 맡는다.</li>
 * </ul>
 * 스레드: 모든 메서드와 {@link Completion} 호출은 같은 스레드(메인)에서 한다. 저장기가 동기로 완료해도 된다.
 *
 * @param <T> 저장할 값. 큐에 넣은 뒤 호출자가 바꾸지 않는다(사본을 넘긴다).
 * @param <F> 실패 정보
 */
public final class LatestValueSaveQueue<T, F> {

    public interface Saver<T, F> {
        /** value를 저장하고 끝나면 completion을 정확히 한 번 부른다. 두 번째 호출은 무시된다. */
        void save(T value, Completion<F> completion);
    }

    public interface Completion<F> {
        void onSuccess();

        void onFailure(F failure);
    }

    /** 저장 요청을 보내기 직전마다 부른다. false면 대기 값을 버리고 큐를 닫는다. */
    public interface StartGuard {
        boolean canStart();
    }

    public interface Listener<F> {
        /** 진행 중·대기 중인 저장 없이 마지막 값까지 저장됐다. */
        void onAllSaved();

        /** 저장이 실패했다. 값은 여전히 저장 필요 상태다({@link #needsSave()} == true). */
        void onSaveFailed(F failure);

        /** {@link StartGuard}가 거부해 대기 값을 버리고 큐를 닫았다(로그아웃·계정 변경). */
        default void onDiscarded() {
        }
    }

    private final Saver<T, F> saver;
    private final StartGuard guard;
    private Listener<F> listener;

    private T latest;
    /** update마다 1씩 증가. */
    private long latestVersion;
    /** 서버 저장이 확인된 마지막 버전. */
    private long savedVersion;
    private boolean saving;
    /** 저장 중에 flush가 들어왔다. */
    private boolean flushRequested;
    /** guard가 거부해 닫혔다. 더는 값을 받거나 저장하지 않는다. */
    private boolean closed;

    public LatestValueSaveQueue(Saver<T, F> saver) {
        this(saver, null);
    }

    /** @param guard 저장 요청 직전마다 확인할 조건. null이면 항상 허용. */
    public LatestValueSaveQueue(Saver<T, F> saver, StartGuard guard) {
        if (saver == null) throw new IllegalArgumentException("saver");
        this.saver = saver;
        this.guard = guard;
    }

    public void setListener(Listener<F> listener) {
        this.listener = listener;
    }

    /** 저장할 최신 값을 기록한다. 바로 저장하지는 않는다(디바운스 뒤 {@link #flush}). */
    public void update(T value) {
        if (closed) return;
        latest = value;
        latestVersion++;
    }

    /** 저장할 값이 있으면 저장한다. 저장 중이면 현재 요청이 끝난 뒤 최신 값으로 저장한다. */
    public void flush() {
        if (closed || !needsSave()) return;
        if (saving) {
            flushRequested = true;
            return;
        }
        start();
    }

    /** 아직 서버 저장이 확인되지 않은 값이 있다(대기·진행 중·실패 포함). */
    public boolean needsSave() {
        return latestVersion != savedVersion;
    }

    public boolean isSaving() {
        return saving;
    }

    /** {@link StartGuard} 거부로 닫혔다. */
    public boolean isClosed() {
        return closed;
    }

    /** 마지막으로 {@link #update}한 값. 없으면 null. */
    public T latest() {
        return latest;
    }

    private void start() {
        if (guard != null && !guard.canStart()) {
            discard();
            return;
        }
        saving = true;
        flushRequested = false;
        final long version = latestVersion;
        final boolean[] done = {false};
        saver.save(latest, new Completion<F>() {
            @Override
            public void onSuccess() {
                if (done[0]) return;
                done[0] = true;
                finish(version, true, null);
            }

            @Override
            public void onFailure(F failure) {
                if (done[0]) return;
                done[0] = true;
                finish(version, false, failure);
            }
        });
    }

    /** 대기 값을 버린다. 서버에는 아무것도 보내지 않는다. */
    private void discard() {
        closed = true;
        saving = false;
        flushRequested = false;
        latest = null;
        savedVersion = latestVersion;
        if (listener != null) listener.onDiscarded();
    }

    private void finish(long version, boolean success, F failure) {
        saving = false;
        if (success) savedVersion = version;
        boolean again = needsSave() && (latestVersion > version || flushRequested);
        if (!success && listener != null) listener.onSaveFailed(failure);
        if (again) {
            start();
        } else if (!needsSave() && listener != null) {
            listener.onAllSaved();
        }
    }
}
