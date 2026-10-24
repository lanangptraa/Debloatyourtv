package com.gathin.tvhome;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

final class WallpaperRotation {
    /** Playback orders persisted in config.json (wallpaper_order). */
    static final String ORDER_SEQUENTIAL = "sequential";
    static final String ORDER_SHUFFLE = "shuffle";
    static final String ORDER_TIME_CYCLE = "time_cycle";

    static final int SLOT_PAGI = 0;   // 05:00 - 10:59
    static final int SLOT_SIANG = 1;  // 11:00 - 14:59
    static final int SLOT_SORE = 2;   // 15:00 - 18:29
    static final int SLOT_MALAM = 3;  // 18:30 - 04:59

    static boolean validOrder(String order) {
        return ORDER_SEQUENTIAL.equals(order) || ORDER_SHUFFLE.equals(order) || ORDER_TIME_CYCLE.equals(order);
    }

    /** Returns the active time-of-day slot based on the system clock. */
    static int currentSlot() {
        Calendar cal = Calendar.getInstance();
        int minutesOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        if (minutesOfDay >= 5 * 60 && minutesOfDay < 11 * 60) {
            return SLOT_PAGI;
        } else if (minutesOfDay >= 11 * 60 && minutesOfDay < 15 * 60) {
            return SLOT_SIANG;
        } else if (minutesOfDay >= 15 * 60 && minutesOfDay < (18 * 60 + 30)) {
            return SLOT_SORE;
        } else {
            return SLOT_MALAM;
        }
    }

    /** Indonesian display label for the given time-of-day slot. */
    static String slotLabel(int slot) {
        switch (slot) {
            case SLOT_PAGI:
                return "Pagi (05:00–11:00)";
            case SLOT_SIANG:
                return "Siang (11:00–15:00)";
            case SLOT_SORE:
                return "Sore (15:00–18:30)";
            default:
                return "Malam (18:30–05:00)";
        }
    }

    static String slotShortName(int slot) {
        switch (slot) {
            case SLOT_PAGI:
                return "Pagi";
            case SLOT_SIANG:
                return "Siang";
            case SLOT_SORE:
                return "Sore";
            default:
                return "Malam";
        }
    }

    /** Classifies a wallpaper filename into its time-of-day slot (Pagi, Siang, Sore, Malam). */
    static int slotOfFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.contains("night") || lower.contains("galaxy") || lower.contains("aurora")
                || lower.contains("moon") || lower.contains("dusk") || lower.contains("city-lights")
                || lower.contains("starlit")) {
            return SLOT_MALAM;
        }
        if (lower.contains("alpine-lake") || lower.contains("sunset") || lower.contains("golden")
                || lower.contains("alpenglow") || lower.contains("autumn") || lower.contains("monsoon")) {
            return SLOT_SORE;
        }
        if (lower.contains("yosemite") || lower.contains("city-skyline") || lower.contains("above-the-clouds")
                || lower.contains("lighthouse") || lower.contains("dunes")) {
            return SLOT_SIANG;
        }
        // 00-mountain-peaks.jpg, 11-balloon-sunrise.jpg, 07-mirror-lake.jpg, 08-tea-hills.jpg
        return SLOT_PAGI;
    }

    /** Primary high-resolution 1080p wallpaper filename for each time-of-day slot. */
    static String primaryFileForSlot(int slot) {
        switch (slot) {
            case SLOT_PAGI:
                return "00-mountain-peaks.jpg";
            case SLOT_SIANG:
                return "00-yosemite-valley.jpg";
            case SLOT_SORE:
                return "00-alpine-lake.jpg";
            default:
                return "00-night-galaxy.jpg";
        }
    }

    /**
     * Picks the best wallpaper for the given time-of-day slot.
     * Prefers the high-res 1080p primary wallpaper when entering a new slot,
     * or rotates among the slot's high-res candidates when rotating within the same slot.
     */
    static File pickForSlot(List<File> allImages, int slot, String previous, boolean slotChanged) {
        if (allImages.isEmpty()) return null;
        String primaryName = primaryFileForSlot(slot);
        if (slotChanged || previous == null || previous.isEmpty()) {
            File primary = find(allImages, primaryName);
            if (primary != null) return primary;
        }
        List<File> slotHighRes = new ArrayList<>();
        List<File> slotAll = new ArrayList<>();
        for (File f : allImages) {
            if (slotOfFile(f.getName()) == slot) {
                slotAll.add(f);
                if (f.getName().startsWith("00-") || f.getName().startsWith("99-") || f.length() > 90_000L) {
                    slotHighRes.add(f);
                }
            }
        }
        List<File> pool = !slotHighRes.isEmpty() ? slotHighRes : (!slotAll.isEmpty() ? slotAll : allImages);
        return next(pool, previous);
    }

    static List<File> images(File directory) {
        List<File> result = new ArrayList<>();
        File[] entries = directory == null ? null : directory.listFiles();
        if (entries == null) return result;
        Arrays.sort(entries, Comparator.comparing(File::getName));
        for (File entry : entries) {
            String name = entry.getName().toLowerCase(Locale.ROOT);
            if (entry.isFile() && entry.length() > 0 && entry.length() <= 10 * 1024 * 1024
                    && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png") || name.endsWith(".webp"))) {
                result.add(entry);
                if (result.size() == 100) break;
            }
        }
        return result;
    }

    static File find(List<File> images, String name) {
        for (File image : images) if (image.getName().equals(name)) return image;
        return null;
    }

    static File pick(List<File> images, String previous, Random random) {
        List<File> candidates = new ArrayList<>(images);
        if (candidates.size() > 1) candidates.removeIf(image -> image.getName().equals(previous));
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * The next picture in the sorted collection, wrapping to the first at the end. The list from
     * {@link #images(File)} is sorted by filename, so this is a stable order across restarts. A
     * previous name that is no longer in the collection starts again at the first picture.
     */
    static File next(List<File> images, String previous) {
        if (images.isEmpty()) return null;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(previous)) {
                return images.get((i + 1) % images.size());
            }
        }
        return images.get(0);
    }

    /** Picks the next picture for {@code order}; defaults to time-of-day cycle. */
    static File pick(List<File> images, String previous, String order, Random random) {
        if (ORDER_TIME_CYCLE.equals(order) || ORDER_SEQUENTIAL.equals(order)) {
            return pickForSlot(images, currentSlot(), previous, false);
        }
        return pick(images, previous, random);
    }

    static boolean due(long previous, long now, long interval) {
        return interval > 0 && (previous == 0 || now < previous || now - previous >= interval);
    }
}
