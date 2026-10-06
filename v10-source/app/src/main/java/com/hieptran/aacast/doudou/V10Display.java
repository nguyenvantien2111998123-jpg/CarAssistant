package com.carassistant.v10;

public final class V10Display {

    public static final String PACKAGE =
            "com.carassistant.v10";

    public static final String PREFIX =
            "Car Assistant V10 ";

    private V10Display() {
    }

    public static String nameFor(int index) {
        return PREFIX + (index + 1);
    }

    public static boolean matches(
            String ownerPackage,
            String name,
            int type,
            int ownerUid) {

        if (!PACKAGE.equals(ownerPackage)
                || type != 5
                || ownerUid < 10000) {
            return false;
        }

        return (PREFIX + "1").equals(name);
    }
}
