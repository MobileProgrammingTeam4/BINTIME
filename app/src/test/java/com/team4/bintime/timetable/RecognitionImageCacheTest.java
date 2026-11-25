package com.team4.bintime.timetable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** [BT-048] 검수 화면 원본 보기용 앱 캐시 사본. */
public class RecognitionImageCacheTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final String BATCH = "11111111-2222-3333-4444-555555555555";

    @Test
    public void save_writesFilesInIndexOrder_andDeleteRemovesBatch() throws IOException {
        File root = temp.newFolder("originals");
        RecognitionImageCache cache = new RecognitionImageCache(root);

        List<String> paths = cache.save(BATCH, Arrays.asList(new byte[]{1}, new byte[]{2, 3}));

        assertEquals(2, paths.size());
        assertTrue(paths.get(0).endsWith(BATCH + File.separator + "0.jpg"));
        assertArrayEquals(new byte[]{2, 3}, Files.readAllBytes(new File(paths.get(1)).toPath()));
        assertEquals(paths, RecognitionImageCache.existing(paths));

        cache.delete(BATCH);
        assertFalse(new File(root, BATCH).exists());
        assertTrue(RecognitionImageCache.existing(paths).isEmpty());
        cache.delete(BATCH); // 두 번 지워도 안전
    }

    @Test
    public void unsafeBatchIdOrEmptyInput_savesNothing() throws IOException {
        File root = temp.newFolder("originals");
        RecognitionImageCache cache = new RecognitionImageCache(root);

        assertTrue(cache.save("../escape", Collections.singletonList(new byte[]{1})).isEmpty());
        assertTrue(cache.save(null, Collections.singletonList(new byte[]{1})).isEmpty());
        assertTrue(cache.save(BATCH, Collections.emptyList()).isEmpty());
        assertFalse(new File(root.getParentFile(), "escape").exists());
    }

    @Test
    public void nullImage_rollsBackWholeBatch() throws IOException {
        File root = temp.newFolder("originals");
        RecognitionImageCache cache = new RecognitionImageCache(root);

        assertTrue(cache.save(BATCH, Arrays.asList(new byte[]{1}, null)).isEmpty());
        assertFalse(new File(root, BATCH).exists());
    }

    @Test
    public void clearAll_removesLeftoversFromEarlierRuns() throws IOException {
        File root = temp.newFolder("originals");
        RecognitionImageCache cache = new RecognitionImageCache(root);
        cache.save(BATCH, Collections.singletonList(new byte[]{1}));
        cache.save("other-batch", Collections.singletonList(new byte[]{2}));

        cache.clearAll();

        String[] left = root.list();
        assertEquals(0, left == null ? 0 : left.length);
        new RecognitionImageCache(new File(root, "missing")).clearAll(); // 폴더가 없어도 안전
    }

    @Test
    public void existing_skipsMissingAndNullPaths() throws IOException {
        File file = temp.newFile("0.jpg");
        assertEquals(Collections.singletonList(file.getAbsolutePath()), RecognitionImageCache.existing(
                Arrays.asList(null, file.getAbsolutePath(), new File(temp.getRoot(), "gone.jpg").getAbsolutePath())));
        assertTrue(RecognitionImageCache.existing(null).isEmpty());
    }
}
