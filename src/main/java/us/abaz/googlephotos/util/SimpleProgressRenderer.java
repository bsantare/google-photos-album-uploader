package us.abaz.googlephotos.util;

import com.google.common.base.Strings;
import org.apache.commons.lang3.StringUtils;

public class SimpleProgressRenderer {
    private static final int TOTAL_PROGRESS_CHARS = 40;
    private static final String PROGRESS_CHARS = Strings.repeat("=", TOTAL_PROGRESS_CHARS);
    private static final String REMAINING_CHARS = Strings.repeat(" ", TOTAL_PROGRESS_CHARS);

    public static void renderProgress(int current, int total, int inProgress, int errors) {
        int progressChars = (int) Math.round((double) current / (double) total) * TOTAL_PROGRESS_CHARS;
        String errorsMsg = errors > 0 ? String.format(" , %d errors", errors) : StringUtils.EMPTY;
        System.out.print(
                String.format("\r[%s>%s] %d of %d, %d in progress%s",
                        PROGRESS_CHARS.substring(TOTAL_PROGRESS_CHARS - progressChars),
                        REMAINING_CHARS.substring(progressChars),
                        current,
                        total,
                        inProgress,
                        errorsMsg));
    }
}
