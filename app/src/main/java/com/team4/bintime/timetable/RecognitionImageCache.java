package com.team4.bintime.timetable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * [BT-048] 검수 화면 '원본 보기'용 이미지 사본. 업로드한 JPEG를 앱 캐시 폴더({@code root}/{batchId}/{i}.jpg)에 둔다.
 * <ul>
 *   <li>앱 전용 캐시라 다른 앱이 읽지 못한다. Firestore·Storage 와 무관하다.</li>
 *   <li>검수가 끝나거나(저장·뒤로) 선택이 바뀌거나 화면을 떠나면 {@link TimetableImageUploader}가 지운다.</li>
 *   <li>프로세스가 끝나 지우지 못한 사본은 업로드 화면을 새로 열 때 {@link #clearAll}이 지운다.</li>
 * </ul>
 * java.io 만 써서 JVM 단위 테스트를 한다.
 */
final class RecognitionImageCache implements TimetableImageUploader.LocalCopy {

    /** 앱 캐시 아래 폴더 이름. */
    static final String DIRECTORY = "timetable_originals";

    private final File root;

    RecognitionImageCache(File root) {
        if (root == null) throw new IllegalArgumentException("null root");
        this.root = root;
    }

    @Override
    public List<String> save(String batchId, List<byte[]> jpegs) {
        if (!isSafeName(batchId) || jpegs == null || jpegs.isEmpty()) return Collections.emptyList();
        File dir = new File(root, batchId);
        deleteTree(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) return Collections.emptyList();
        List<String> paths = new ArrayList<>(jpegs.size());
        try {
            for (int i = 0; i < jpegs.size(); i++) {
                byte[] jpeg = jpegs.get(i);
                if (jpeg == null) throw new IOException("null image");
                File file = new File(dir, i + ".jpg");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(jpeg);
                }
                paths.add(file.getAbsolutePath());
            }
        } catch (IOException | RuntimeException e) {
            deleteTree(dir);
            return Collections.emptyList();
        }
        return paths;
    }

    @Override
    public void delete(String batchId) {
        if (!isSafeName(batchId)) return;
        deleteTree(new File(root, batchId));
    }

    /** 남은 사본을 모두 지운다(업로드 화면을 새로 열 때). */
    void clearAll() {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) deleteTree(child);
    }

    /** 아직 있는 파일만 순서대로 돌려준다. 프로세스 재시작 뒤 지워졌으면 빈 목록. */
    static List<String> existing(List<String> paths) {
        List<String> found = new ArrayList<>();
        if (paths == null) return found;
        for (String path : paths) {
            if (path != null && new File(path).isFile()) found.add(path);
        }
        return found;
    }

    /** batchId 는 앱이 만든 UUID다. 경로를 벗어나는 이름은 쓰지 않는다. */
    private static boolean isSafeName(String name) {
        return name != null && name.matches("[A-Za-z0-9-]{1,64}");
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
