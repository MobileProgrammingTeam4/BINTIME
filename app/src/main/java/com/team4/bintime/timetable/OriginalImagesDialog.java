package com.team4.bintime.timetable;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.team4.bintime.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * [BT-048] 검수 화면 '원본 보기': 올린 이미지 전체를 전체 화면으로 보여 준다. 좌우로 밀거나 [이전]·[다음]으로 넘기고,
 * 두 손가락·두 번 탭으로 확대한다({@link ZoomImageView}). 이미지는 앱 캐시의 사본({@link RecognitionImageCache})을
 * background 스레드에서 화면 크기에 맞춰 줄여 읽는다(업로드 화면 썸네일과 같은 inSampleSize 방식).
 * 파일이 없으면(프로세스 재시작 뒤 정리됨) 안내 문구만 보인다.
 */
public class OriginalImagesDialog extends DialogFragment {

    static final String TAG = "timetable_original_images";
    private static final String ARG_PATHS = "paths";
    private static final String STATE_PAGE = "page";

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private List<String> paths = new ArrayList<>();
    private int page;
    private int loadGeneration;
    private ZoomImageView image;
    private TextView counter;
    private TextView missing;
    private View previous;
    private View next;

    static void show(FragmentManager fm, ArrayList<String> paths) {
        if (fm.findFragmentByTag(TAG) != null) return;
        OriginalImagesDialog dialog = new OriginalImagesDialog();
        Bundle args = new Bundle();
        args.putStringArrayList(ARG_PATHS, paths);
        dialog.setArguments(args);
        dialog.show(fm, TAG);
    }

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ArrayList<String> args = requireArguments().getStringArrayList(ARG_PATHS);
        if (args != null) paths = args;
        if (state != null) page = state.getInt(STATE_PAGE, 0);
        page = ZoomMath.page(page, 0, paths.size());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle state) {
        View view = inflater.inflate(R.layout.dialog_original_images, container, false);
        image = view.findViewById(R.id.original_image);
        counter = view.findViewById(R.id.original_counter);
        missing = view.findViewById(R.id.original_missing);
        previous = view.findViewById(R.id.original_previous);
        next = view.findViewById(R.id.original_next);
        view.findViewById(R.id.original_close).setOnClickListener(v -> dismiss());
        previous.setOnClickListener(v -> move(-1));
        next.setOnClickListener(v -> move(1));
        image.setSwipeListener(this::move);
        showPage();
        return view;
    }

    private void move(int direction) {
        int target = ZoomMath.page(page, direction, paths.size());
        if (target == page) return;
        page = target;
        showPage();
    }

    private void showPage() {
        int count = paths.size();
        counter.setText(getString(R.string.timetable_original_counter, count == 0 ? 0 : page + 1, count));
        previous.setEnabled(page > 0);
        previous.setAlpha(page > 0 ? 1f : 0.4f);
        next.setEnabled(page < count - 1);
        next.setAlpha(page < count - 1 ? 1f : 0.4f);
        image.setImageDrawable(null);
        image.setContentDescription(getString(R.string.timetable_original_image_description, page + 1));
        missing.setVisibility(View.GONE);
        if (count == 0) {
            missing.setVisibility(View.VISIBLE);
            return;
        }
        int generation = ++loadGeneration;
        String path = paths.get(page);
        int maxSide = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 2;
        loader.execute(() -> {
            Bitmap bitmap = decode(path, maxSide);
            View root = getView();
            if (root == null) return;
            root.post(() -> {
                if (generation != loadGeneration || getView() == null) return;
                if (bitmap == null) {
                    missing.setVisibility(View.VISIBLE);
                } else {
                    image.setImageBitmap(bitmap);
                }
            });
        });
    }

    /** 파일이 없거나 읽을 수 없으면 null. */
    private static Bitmap decode(String path, int maxSide) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxSide) {
                options.inSampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(path, options);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_PAGE, page);
    }

    @Override
    public void onDestroyView() {
        loadGeneration++;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }
}
