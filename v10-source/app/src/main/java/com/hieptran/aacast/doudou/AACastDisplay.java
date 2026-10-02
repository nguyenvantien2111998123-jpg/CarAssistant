package com.carassistant.v10;

/**
 * Nhận diện display ảo do Car Assistant V9 tạo — dùng chung cho app và các hook Xposed.
 *
 * QUAN TRỌNG: 3 nơi phải khớp nhau:
 *   1. PaneView.ensureDisplay()  — tên display khi createVirtualDisplay
 *   2. class này                 — điều kiện nhận diện trong hook
 *   3. hook gearhead/input       — chỉ tác động lên display khớp điều kiện
 */
public final class AACastDisplay {

    public static final String PACKAGE = "com.carassistant.v10";
    public static final String PREFIX = "Car Assistant V9 ";

    private AACastDisplay() {
    }

    public static String nameFor(int paneIndex) {
        return PREFIX + (paneIndex + 1);
    }

    /** Display ảo này có phải của Car Assistant V9? */
    public static boolean matches(String ownerPackage, String name, int type, int ownerUid) {
        if (!PACKAGE.equals(ownerPackage) || type != 5 /* Display.TYPE_VIRTUAL (@hide) */
                || ownerUid < 10000) {
            return false;
        }
        return (PREFIX + "1").equals(name)
                || (PREFIX + "2").equals(name)
                || (PREFIX + "3").equals(name);
    }
}
